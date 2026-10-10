"""What a run of a program has in its working set when it ends, by where those pages are: each section of the executable, each
other module, and private memory. Windows only.

  python bench/ws_at_exit.py EXE [ARGS...]

The program runs under this script as its debugger, which sees it at its exit, before its address space goes: what is resident
then is what the run touched (less what the system trimmed, which for a short run is nothing). _NO_DEBUG_HEAP=1, so that the
process heap is the one it has without a debugger. Page faults, CPU time and peak working set are those of the whole run.
"""
import collections
import ctypes
import os
import struct
import subprocess
import sys
from ctypes import wintypes as w

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)
DEBUG_ONLY_THIS_PROCESS = 0x2
CREATE_PROCESS_DEBUG_EVENT, EXIT_PROCESS_DEBUG_EVENT, LOAD_DLL_DEBUG_EVENT = 3, 5, 6
EXCEPTION_DEBUG_EVENT = 1
DBG_CONTINUE, DBG_EXCEPTION_NOT_HANDLED = 0x00010002, 0x80010001
PAGE = 4096


class STARTUPINFOW(ctypes.Structure):
    _fields_ = [("cb", w.DWORD), ("lpReserved", w.LPWSTR), ("lpDesktop", w.LPWSTR), ("lpTitle", w.LPWSTR), ("dwX", w.DWORD),
                ("dwY", w.DWORD), ("dwXSize", w.DWORD), ("dwYSize", w.DWORD), ("dwXCountChars", w.DWORD), ("dwYCountChars", w.DWORD),
                ("dwFillAttribute", w.DWORD), ("dwFlags", w.DWORD), ("wShowWindow", w.WORD), ("cbReserved2", w.WORD),
                ("lpReserved2", ctypes.c_void_p), ("hStdInput", w.HANDLE), ("hStdOutput", w.HANDLE), ("hStdError", w.HANDLE)]


class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [("hProcess", w.HANDLE), ("hThread", w.HANDLE), ("dwProcessId", w.DWORD), ("dwThreadId", w.DWORD)]


class DEBUG_EVENT(ctypes.Structure):
    # The union is read by offset: on x64 it starts at 16.
    _fields_ = [("dwDebugEventCode", w.DWORD), ("dwProcessId", w.DWORD), ("dwThreadId", w.DWORD), ("pad", w.DWORD),
                ("u", ctypes.c_byte * 160)]


class PMC(ctypes.Structure):
    _fields_ = [("cb", w.DWORD), ("PageFaultCount", w.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t), ("a", ctypes.c_size_t), ("b", ctypes.c_size_t), ("c", ctypes.c_size_t),
                ("d", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]


class MBI(ctypes.Structure):
    _fields_ = [("BaseAddress", ctypes.c_void_p), ("AllocationBase", ctypes.c_void_p), ("AllocationProtect", w.DWORD),
                ("PartitionId", w.WORD), ("RegionSize", ctypes.c_size_t), ("State", w.DWORD), ("Protect", w.DWORD), ("Type", w.DWORD)]


def read(process, address, size):
    buffer = ctypes.create_string_buffer(size)
    done = ctypes.c_size_t()
    if not k32.ReadProcessMemory(process, ctypes.c_void_p(address), buffer, size, ctypes.byref(done)):
        return None
    return buffer.raw[:done.value]


def sections_of(process, base):
    header = read(process, base, 4096)
    pe = struct.unpack_from("<I", header, 0x3C)[0]
    count = struct.unpack_from("<H", header, pe + 6)[0]
    optional = struct.unpack_from("<H", header, pe + 20)[0]
    table = pe + 24 + optional
    sections = [("(headers)", 0, 4096)]
    for i in range(count):
        name, vsize, rva = struct.unpack_from("<8sII", header, table + 40 * i)
        sections.append((name.rstrip(b"\0").decode(errors="replace"), rva, vsize))
    return sections


def module_name(process, base):
    name = ctypes.create_unicode_buffer(520)
    psapi.GetMappedFileNameW(process, ctypes.c_void_p(base), name, 520)
    return os.path.basename(name.value) or hex(base)


def region_size(process, base):
    mbi = MBI()
    size, address = 0, base
    while k32.VirtualQueryEx(process, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi)) and (mbi.AllocationBase or 0) == base:
        size += mbi.RegionSize
        address += mbi.RegionSize
    return size


def working_set(process):
    entries = 1 << 16
    while True:
        buffer = (ctypes.c_size_t * (entries + 1))()
        if psapi.QueryWorkingSet(process, buffer, ctypes.sizeof(buffer)):
            return [buffer[1 + i] for i in range(buffer[0])]
        entries = max(entries * 2, buffer[0] + 1024)


def run(argv):
    os.environ["_NO_DEBUG_HEAP"] = "1"
    si = STARTUPINFOW()
    si.cb = ctypes.sizeof(si)
    pi = PROCESS_INFORMATION()
    cmd = ctypes.create_unicode_buffer(subprocess.list2cmdline(argv))
    if not k32.CreateProcessW(None, cmd, None, None, True, DEBUG_ONLY_THIS_PROCESS, None, None, ctypes.byref(si), ctypes.byref(pi)):
        sys.exit(f"could not start: error {ctypes.get_last_error()}")
    exe_base = None
    modules = {}
    event = DEBUG_EVENT()
    while True:
        k32.WaitForDebugEvent(ctypes.byref(event), 0xFFFFFFFF)
        code = event.dwDebugEventCode
        status = DBG_CONTINUE
        if code == CREATE_PROCESS_DEBUG_EVENT:
            exe_base = struct.unpack_from("<Q", bytes(event.u), 24)[0]  # lpBaseOfImage
            k32.CloseHandle(ctypes.c_void_p(struct.unpack_from("<Q", bytes(event.u), 0)[0]))  # hFile
        elif code == LOAD_DLL_DEBUG_EVENT:
            k32.CloseHandle(ctypes.c_void_p(struct.unpack_from("<Q", bytes(event.u), 0)[0]))
            modules[struct.unpack_from("<Q", bytes(event.u), 8)[0]] = None
        elif code == EXCEPTION_DEBUG_EVENT:
            first_chance = struct.unpack_from("<I", bytes(event.u), 152)[0]
            exception = struct.unpack_from("<I", bytes(event.u), 0)[0]
            # The loader's breakpoint is ours to continue; everything else is the program's.
            status = DBG_CONTINUE if exception == 0x80000003 else DBG_EXCEPTION_NOT_HANDLED
        elif code == EXIT_PROCESS_DEBUG_EVENT:
            report(pi.hProcess, exe_base, modules, struct.unpack_from("<I", bytes(event.u), 0)[0])
            k32.ContinueDebugEvent(event.dwProcessId, event.dwThreadId, DBG_CONTINUE)
            break
        k32.ContinueDebugEvent(event.dwProcessId, event.dwThreadId, status)


def report(process, exe_base, modules, exit_code):
    pmc = PMC()
    pmc.cb = ctypes.sizeof(pmc)
    psapi.GetProcessMemoryInfo(process, ctypes.byref(pmc), pmc.cb)
    times = [w.FILETIME() for _ in range(4)]
    k32.GetProcessTimes(process, *[ctypes.byref(t) for t in times])
    ms = lambda t: ((t.dwHighDateTime << 32) | t.dwLowDateTime) / 1e4
    pages = working_set(process)
    exe_sections = sections_of(process, exe_base)
    exe_size = max(rva + size for _, rva, size in exe_sections)
    groups = collections.Counter()
    shared = collections.Counter()
    reservations = collections.Counter()
    heap_pages = []
    names = {}
    for entry in pages:
        address = entry & ~0xFFF
        if exe_base <= address < exe_base + exe_size:
            rva = address - exe_base
            where = next((f"exe {name}" for name, start, size in reversed(exe_sections) if start <= rva < start + size), "exe ?")
            if where == "exe .pbheap":
                heap_pages.append(rva)
        else:
            mbi = MBI()
            k32.VirtualQueryEx(process, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi))
            base = mbi.AllocationBase or 0
            if mbi.Type == 0x1000000:  # MEM_IMAGE
                if base not in names:
                    names[base] = module_name(process, base)
                where = f"dll {names[base]}"
            elif mbi.Type == 0x40000:  # MEM_MAPPED
                where = "mapped"
            else:
                where = "private"
                reservations[base] += 1
        groups[where] += 1
        if entry & 0x100:  # Shared
            shared[where] += 1
    print(f"exit {exit_code:#x}; page faults {pmc.PageFaultCount}; peak working set {pmc.PeakWorkingSetSize >> 20} MB; "
          f"user {ms(times[3]):.0f} ms, kernel {ms(times[2]):.0f} ms (15.6 ms ticks)")
    print(f"resident at exit: {len(pages)} pages ({len(pages) * PAGE >> 20} MB)")
    print(f"{'where':40} {'pages':>7} {'MB':>7} {'shared':>7}")
    for where, count in groups.most_common(40):
        print(f"{where:40} {count:7} {count * PAGE / 2**20:7.1f} {shared[where]:7}")
    print("private, by reservation (base, reserved MB, resident pages):")
    for base, count in reservations.most_common(12):
        mbi = MBI()
        size, address = 0, base
        while k32.VirtualQueryEx(process, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi)) and (mbi.AllocationBase or 0) == base:
            size += mbi.RegionSize
            address += mbi.RegionSize
        print(f"  {base:#016x} {size / 2**20:9.1f} {count:7}")
    # Executable memory outside images (JIT): Control Flow Guard marks all of it valid, at a bitmap page per 256 KB of it.
    mbi = MBI()
    address, executable = 0, collections.Counter()
    while k32.VirtualQueryEx(process, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi)):
        if mbi.Type == 0x20000 and mbi.Protect & 0xF0:  # MEM_PRIVATE, PAGE_EXECUTE*
            executable[mbi.AllocationBase or 0] += mbi.RegionSize
        elif mbi.Type == 0x20000 and mbi.State == 0x2000 and mbi.AllocationProtect & 0xF0:  # reserved as executable
            executable[mbi.AllocationBase or 0] += mbi.RegionSize
        address = (mbi.BaseAddress or 0) + mbi.RegionSize
        if address >= 1 << 47:
            break
    if executable:
        print("private executable reservations: " + ", ".join(f"{base:#x} {size / 2**20:.0f} MB" for base, size in executable.most_common(6)))
    # Commit charge, which is what private bytes count: private memory by reservation, and the executable's writable sections
    # (copy-on-write pages are charged when the image is mapped).
    committed = collections.Counter()
    address = 0
    while k32.VirtualQueryEx(process, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi)):
        if mbi.State == 0x1000:
            if mbi.Type == 0x20000:
                committed[f"private {mbi.AllocationBase or 0:#x}"] += mbi.RegionSize
            elif mbi.Type == 0x1000000 and mbi.Protect & 0xCC:  # image, writable or copy-on-write
                base = mbi.AllocationBase or 0
                where = "exe" if base == exe_base else module_name(process, base)
                committed[f"image-writable {where}"] += mbi.RegionSize
        address = (mbi.BaseAddress or 0) + mbi.RegionSize
        if address >= 1 << 47:
            break
    total = sum(committed.values())
    print(f"commit (private + writable image): {total / 2**20:.1f} MB; largest:")
    for what, size in committed.most_common(int(os.environ.get("WS_COMMIT_TOP", "8"))):
        reserved = region_size(process, int(what.split()[-1], 16)) if what.startswith("private") else 0
        print(f"  {what:44} {size / 2**20:8.1f} MB" + (f" (of {reserved / 2**20:.0f} MB reserved)" if reserved else ""))
    if os.environ.get("WS_STACK_SCAN"):
        # The deepest pages of the busiest 18 MB reservation (a thread's stack): what looks like a return address into the
        # executable's code there, from the deepest up. Those frames are what reached that deep.
        text = next((rva, size) for name, rva, size in exe_sections if name == ".text")
        stack = next(base for base, _ in reservations.most_common() if region_size(process, base) == 0x1200000)
        resident = sorted(p & ~0xFFF for p in pages if stack <= (p & ~0xFFF) < stack + 0x1200000)
        print(f"stack {stack:#x}: deepest resident page at +{resident[0] - stack:#x}, {len(resident)} pages")
        zero = sum(1 for page in resident if not any(read(process, page, PAGE) or b"\1"))
        runs, start = [], resident[0]
        for previous, page in zip(resident, resident[1:] + [None]):
            if page != previous + PAGE:
                runs.append(f"+{start - stack:#x}..+{previous + PAGE - stack:#x}")
                start = page
        print(f"  {zero} of them all zeros; resident runs: {' '.join(runs[:12])}")
        found = []
        for page in resident:
            data = read(process, page, PAGE) or b""
            for offset in range(0, len(data), 8):
                value = struct.unpack_from("<Q", data, offset)[0]
                if exe_base + text[0] <= value < exe_base + text[0] + text[1]:
                    found.append((page + offset, value - exe_base))
            if len(found) > int(os.environ["WS_STACK_SCAN"]):
                break
        for at, rva in found:
            print(f"  +{at - stack:#09x} rva {rva:#x}")
    if heap_pages:
        # Where in the prebuilt heap's section the resident pages are, by megabyte from its start.
        start = next(rva for name, rva, _ in exe_sections if name == ".pbheap")
        per_mb = collections.Counter((rva - start) >> 20 for rva in heap_pages)
        print(".pbheap resident pages per MB from its start: " + " ".join(f"{mb}:{per_mb[mb]}" for mb in sorted(per_mb)))


if __name__ == "__main__":
    run(sys.argv[1:])
