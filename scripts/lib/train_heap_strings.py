"""Puts the strings a Pi-Bolt build touches when it runs first in a training profile's order file (its S lines), in the order it
first touches them. The order file's strings are what decoding Pi's bytecode read first; a prebuilt heap has nothing left to
decode, and what it touches as it starts is mostly what the engine looks up by name (its own identifiers, single characters,
the global object's properties) and what Pi's code then uses. Each string has three parts in the heap: its record in the
string table, its atom's StringImpl and its JSString. The build lays all three out in S order, so these are on few pages.
Windows only: the runs are traced as a debugger does it.

  python scripts/lib/train_heap_strings.py EXE ORDER_FILE

EXE: a Pi-Bolt build of the profile's Pi version (scripts\build-pi.ps1). ORDER_FILE: the profile's bytecode.order, rewritten
in place. The runs: `--version`, a headless prompt, a TUI session of one prompt, each against the bench's fake model.

How a run is traced: at the loader's breakpoint (relocations done, nothing of the program run yet) the pages of the strings' three
parts get PAGE_GUARD. An access faults; its address is recorded; the thread single-steps it with the page unguarded; the page is
guarded again. So every access is seen, not just the first to each page, at the cost of two debug events each (a TUI session
takes minutes). An access by another thread while a page is unguarded for one is missed.
"""
import bisect
import ctypes
import json
import mmap
import msvcrt
import os
import struct
import sys
import tempfile
import threading
import time
from ctypes import wintypes as w
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
import winproc  # noqa: E402
from harness import DONE, MODEL_ARGS, PROMPT, Tty, done, fake_model, pi_env, pi_home, workdir  # noqa: E402

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.VirtualProtectEx.argtypes = [w.HANDLE, ctypes.c_void_p, ctypes.c_size_t, w.DWORD, ctypes.POINTER(w.DWORD)]
k32.VirtualQueryEx.argtypes = [w.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t]
k32.GetThreadContext.argtypes = [w.HANDLE, ctypes.c_void_p]
k32.SetThreadContext.argtypes = [w.HANDLE, ctypes.c_void_p]
k32.ContinueDebugEvent.argtypes = [w.DWORD, w.DWORD, w.DWORD]
k32.CloseHandle.argtypes = [w.HANDLE]
DEBUG_ONLY_THIS_PROCESS = 0x2
EXCEPTION_DEBUG_EVENT, CREATE_THREAD_DEBUG_EVENT, CREATE_PROCESS_DEBUG_EVENT = 1, 2, 3
EXIT_THREAD_DEBUG_EVENT, EXIT_PROCESS_DEBUG_EVENT, LOAD_DLL_DEBUG_EVENT = 4, 5, 6
DBG_CONTINUE, DBG_EXCEPTION_NOT_HANDLED = 0x00010002, 0x80010001
STATUS_GUARD_PAGE_VIOLATION, STATUS_BREAKPOINT, STATUS_SINGLE_STEP = 0x80000001, 0x80000003, 0x80000004
PAGE_GUARD, MEM_COMMIT = 0x100, 0x1000
PAGE = 4096
MAGIC = b"BTHEAP08"  # StaticHeap::Header::expectedMagic
MASK = (1 << 64) - 1


class DEBUG_EVENT(ctypes.Structure):
    _fields_ = [("dwDebugEventCode", w.DWORD), ("dwProcessId", w.DWORD), ("dwThreadId", w.DWORD), ("pad", w.DWORD),
                ("u", ctypes.c_byte * 160)]


class MBI(ctypes.Structure):
    _fields_ = [("BaseAddress", ctypes.c_void_p), ("AllocationBase", ctypes.c_void_p), ("AllocationProtect", w.DWORD),
                ("PartitionId", w.WORD), ("RegionSize", ctypes.c_size_t), ("State", w.DWORD), ("Protect", w.DWORD), ("Type", w.DWORD)]


def order_string_hash(text):
    """bytecodeOrderStringHash (JavaScriptCore's CachedTypes.cpp: OrderHasher over each UTF-16 unit, low byte then high byte)."""
    h = 0xcbf29ce484222325
    for byte in text.encode("utf-16-le", "surrogatepass"):
        h = ((h ^ byte) * 0x100000001b3) & MASK
    h ^= h >> 33
    h = (h * 0xff51afd7ed558ccd) & MASK
    h ^= h >> 33
    h = (h * 0xc4ceb9fe1a85ec53) & MASK
    h ^= h >> 33
    return min(h, MASK - 2)


class Strings:
    """The executable's strings as its prebuilt heap has them: for each, its text and where its three parts are (region offsets,
    from the start of the static region, which is where it is in every process)."""

    def __init__(self, exe):
        f = open(exe, "rb")
        m = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        pe = struct.unpack_from("<I", m, 0x3c)[0]
        count_of_sections = struct.unpack_from("<H", m, pe + 6)[0]
        optional = struct.unpack_from("<H", m, pe + 20)[0]
        image_base = struct.unpack_from("<Q", m, pe + 24 + 24)[0]
        sections = []
        for i in range(count_of_sections):
            name, _, rva, raw_size, raw = struct.unpack_from("<8sIIII", m, pe + 24 + optional + 40 * i)
            sections.append((name.rstrip(b"\0"), rva, raw, raw_size))
        region = next((s for s in sections if s[0] == b".pbreg"), None)
        if region is None:
            sys.exit(f"{exe} is not a Pi-Bolt executable (no .pbreg section)")
        self.region_rva = (region[1] + 0xffff) & ~0xffff
        preferred_region = image_base + self.region_rva

        def file_at(offset):
            rva = self.region_rva + offset
            return next((raw + rva - start for _, start, raw, size in sections if start <= rva < start + size), None)

        # The heap's header (StaticHeap::Header): the one whose regionBase is an address.
        at = m.find(MAGIC)
        while at != -1:
            header = struct.unpack_from("<44Q", m, at)
            if 0x100000000 < header[42] < 1 << 47 and header[2] < 1 << 31:
                break
            at = m.find(MAGIC, at + 8)
        else:
            sys.exit(f"{exe} has no prebuilt heap of this runtime's ({MAGIC.decode()})")
        strings, slots, region_base = header[15], header[17], header[42]
        table = file_at(strings - region_base)
        count = struct.unpack_from("<I", m, table)[0]
        offsets = struct.unpack_from(f"<{count}I", m, table + 4)
        slots_in_file = file_at(slots - region_base)
        self.texts = []
        parts = {}  # start -> (size, ordinal)
        for ordinal in range(count):
            record = table + offsets[ordinal]
            word = struct.unpack_from("<I", m, record)[0]
            length, is8 = word & 0x7fffffff, word >> 31
            size = length if is8 else 2 * length
            chars = bytes(m[record + 8: record + 8 + size])
            self.texts.append(chars.decode("latin1") if is8 else chars.decode("utf-16-le", "surrogatepass"))
            parts[strings - region_base + offsets[ordinal]] = (8 + size, ordinal)
            cell = struct.unpack_from("<Q", m, slots_in_file + 8 * ordinal)[0] & ~7
            cell_in_file = cell and file_at(cell - preferred_region)
            if cell_in_file:
                parts[cell - preferred_region] = (16, ordinal)
                parts[struct.unpack_from("<Q", m, cell_in_file + 8)[0] - preferred_region] = (24, ordinal)
        self.starts = sorted(parts)
        self.parts = parts
        # The parts lie in three runs (the table, the StringImpls, the JSStrings): what is guarded.
        self.ranges = []
        begin = self.starts[0]
        for a, b in zip(self.starts, self.starts[1:]):
            if b - a > 1 << 20:
                self.ranges.append((begin, a + parts[a][0]))
                begin = b
        self.ranges.append((begin, self.starts[-1] + parts[self.starts[-1]][0]))

    def ordinal_at(self, offset):
        k = bisect.bisect_right(self.starts, offset + 7) - 1
        if k < 0 or offset >= self.starts[k] + self.parts[self.starts[k]][0]:
            return None
        return self.parts[self.starts[k]][1]


def set_trap_flag(thread):
    context = ctypes.create_string_buffer(1232 + 16)
    address = (ctypes.addressof(context) + 15) & ~15
    ctypes.c_uint32.from_address(address + 0x30).value = 0x100001  # ContextFlags: CONTEXT_CONTROL
    if k32.GetThreadContext(thread, ctypes.c_void_p(address)):
        ctypes.c_uint32.from_address(address + 0x44).value |= 0x100  # EFlags: TF
        k32.SetThreadContext(thread, ctypes.c_void_p(address))


def trace(start, region_rva, ranges):
    """Runs a program under the tracer: start() makes it this thread's debuggee and returns its process handle. The region
    offsets accessed in the ranges, in 8-byte granules, in the order of their first access."""
    process = start()
    threads, protection, pending = {}, {}, {}
    order, seen = [], set()
    region = None
    armed = False

    def guard(page):
        old = w.DWORD()
        k32.VirtualProtectEx(process, ctypes.c_void_p(page), PAGE, protection[page] | PAGE_GUARD, ctypes.byref(old))

    event = DEBUG_EVENT()
    while True:
        k32.WaitForDebugEvent(ctypes.byref(event), 0xFFFFFFFF)
        code, u = event.dwDebugEventCode, bytes(event.u)
        status = DBG_CONTINUE
        if code == CREATE_PROCESS_DEBUG_EVENT:
            k32.CloseHandle(struct.unpack_from("<Q", u, 0)[0])
            threads[event.dwThreadId] = struct.unpack_from("<Q", u, 16)[0]
            region = struct.unpack_from("<Q", u, 24)[0] + region_rva
        elif code == CREATE_THREAD_DEBUG_EVENT:
            threads[event.dwThreadId] = struct.unpack_from("<Q", u, 0)[0]
        elif code == EXIT_THREAD_DEBUG_EVENT:
            threads.pop(event.dwThreadId, None)
        elif code == LOAD_DLL_DEBUG_EVENT:
            k32.CloseHandle(struct.unpack_from("<Q", u, 0)[0])
        elif code == EXCEPTION_DEBUG_EVENT:
            exception = struct.unpack_from("<I", u, 0)[0]
            if exception == STATUS_BREAKPOINT and not armed:
                armed = True
                for begin, end in ranges:
                    for page in range(region + (begin & ~0xfff), region + end, PAGE):
                        mbi = MBI()
                        k32.VirtualQueryEx(process, ctypes.c_void_p(page), ctypes.byref(mbi), ctypes.sizeof(mbi))
                        if mbi.State == MEM_COMMIT:
                            protection[page] = mbi.Protect & ~PAGE_GUARD
                            guard(page)
            elif exception == STATUS_GUARD_PAGE_VIOLATION and armed:
                address = struct.unpack_from("<Q", u, 40)[0]  # ExceptionInformation[1]
                if address & ~0xfff in protection:
                    granule = (address - region) & ~7
                    if granule not in seen:
                        seen.add(granule)
                        order.append(granule)
                    pending.setdefault(event.dwThreadId, []).append(address & ~0xfff)
                    set_trap_flag(threads[event.dwThreadId])
                else:
                    status = DBG_EXCEPTION_NOT_HANDLED  # (a guard page of the program's own: a stack's)
            elif exception == STATUS_SINGLE_STEP and event.dwThreadId in pending:
                for page in pending.pop(event.dwThreadId):
                    guard(page)
            elif exception != STATUS_BREAKPOINT:
                status = DBG_EXCEPTION_NOT_HANDLED
        elif code == EXIT_PROCESS_DEBUG_EVENT:
            k32.ContinueDebugEvent(event.dwProcessId, event.dwThreadId, DBG_CONTINUE)
            return order
        k32.ContinueDebugEvent(event.dwProcessId, event.dwThreadId, status)


def run_plain(argv, env, cwd, output):
    """A run as winproc.run() starts one (stdin from NUL, its output to a file, in a Job object), as this thread's debuggee."""
    with open(os.devnull, "rb") as devnull:
        handles = [msvcrt.get_osfhandle(f.fileno()) for f in (devnull, output)]
        for handle in handles:
            k32.SetHandleInformation(handle, 1, 1)  # HANDLE_FLAG_INHERIT
        try:
            # (Kept: its Job object ends the process when it is closed.)
            run_plain.process = winproc.Measured(argv, env, cwd, stdin=handles[0], stdout=handles[1], stderr=handles[1])
            return run_plain.process.process
        finally:
            for handle in handles:
                k32.SetHandleInformation(handle, 1, 0)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    exe, order_file = os.path.abspath(sys.argv[1]), sys.argv[2]
    strings = Strings(exe)
    print(f"{len(strings.texts)} strings; guarding " + ", ".join(f"{a:#x}-{b:#x}" for a, b in strings.ranges), flush=True)
    # (Measured starts its process suspended and resumes it: as a debuggee, with this.)
    winproc.CREATE_SUSPENDED |= DEBUG_ONLY_THIS_PROCESS
    touched = []
    with fake_model() as port, pi_home(port) as home, workdir() as cwd, tempfile.TemporaryFile() as output:
        env = pi_env(home)

        def plain(name, args, expect):
            t0 = time.perf_counter()
            output.seek(0)
            output.truncate()
            touched.append(trace(lambda: run_plain([exe, *args], env, cwd, output), strings.region_rva, strings.ranges))
            output.seek(0)
            if expect.encode() not in output.read():
                sys.exit(f"{name}: the run did not complete")
            print(f"{name}: {len(touched[-1])} granules ({time.perf_counter() - t0:.0f} s)", flush=True)

        plain("--version", ["--version"], "Pi-Bolt")
        plain("headless", ["-p", "--no-session", *MODEL_ARGS, PROMPT], DONE)
        # The TUI: started on this thread (a debuggee is the thread's that made it), driven from another.
        t0 = time.perf_counter()
        box = {}

        def start():
            box["tty"] = Tty([exe, "--no-session", *MODEL_ARGS], env, cwd)
            return box["tty"].console.proc.process

        def drive():
            while "tty" not in box:
                time.sleep(0.01)
            tty = box["tty"]
            deadline = time.perf_counter() + 1800
            box["ok"] = tty.wait_for("fake-model", 0, deadline)
            if box["ok"]:
                tty.settle(0.2, deadline)
                since = len(tty.buf)
                tty.send(PROMPT.encode() + b"\r")
                box["ok"] = tty.wait_for(done(1), since, deadline)
                tty.settle(0.2, deadline)
            tty.send(b"/quit\r")

        driver = threading.Thread(target=drive, daemon=True)
        driver.start()
        touched.append(trace(start, strings.region_rva, strings.ranges))
        driver.join(30)
        if not box.get("ok"):
            sys.exit("TUI: the session did not complete")
        box["tty"].console.close()
        print(f"TUI: {len(touched[-1])} granules ({time.perf_counter() - t0:.0f} s)", flush=True)
    # --version's strings first (every run starts with them), then the TUI's, then the headless run's.
    first, seen = [], set()
    for run in (touched[0], touched[2], touched[1]):
        for granule in run:
            ordinal = strings.ordinal_at(granule)
            if ordinal is not None and ordinal not in seen:
                seen.add(ordinal)
                first.append(order_string_hash(strings.texts[ordinal]))
    lines = Path(order_file).read_text().splitlines()
    hashes = set(first)
    others = [line for line in lines[1:] if not line.startswith("S ")]
    rest = [line for line in lines[1:] if line.startswith("S ") and int(line.split()[1], 16) not in hashes]
    text = "\n".join([lines[0], *others, *(f"S {h:016x}" for h in first), *rest]) + "\n"
    Path(order_file).write_text(text, newline="\n")
    print(f"{order_file}: {len(first)} strings the runs touched first, then {len(rest)} of the order file's")


if __name__ == "__main__":
    main()
