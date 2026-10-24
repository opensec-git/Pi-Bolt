"""Shared pieces of the Pi-Bolt benchmark and test tools.

A *build* is a name and the command that starts Pi, given as ``name=command`` on the command line, e.g.::

    --build pi-bolt=./dist/pi/pi --build bun=~/.bun/bin/bun ./pi/dist/bun/cli.js --build node="node ./pi/dist/cli.js"

Every run gets its own fake model server (fake_model.py) on a free port and a throwaway Pi home that points at it, so runs are
isolated from the user's Pi configuration and from each other.
"""

from __future__ import annotations

import contextlib
import json
import os
import re
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path

WINDOWS = sys.platform == "win32"
if WINDOWS:
    # Job objects, cycle counts and ConPTY instead of wait4(), /proc and pty (winproc.py).
    import winproc
else:
    import fcntl
    import pty
    import select
    import termios

HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "fixtures"
PROMPT = "Read the four fixture files"
DONE = "Done: read all four files"


def done(prompt: int) -> str:
    """How the fake model ends its answer to the nth prompt of a session. Wait for this, not for DONE: the answers before it
    are on the screen too, and are written again when the screen is redrawn."""
    return f"{DONE} (prompt {prompt})."
MODEL_ARGS = ["--model", "fake/fake-model"]
# What Pi shows for a prompt submitted while it is still starting (the prompt stays in the editor).
STARTUP_IN_PROGRESS = b"Startup is still in progress"
ANSI = re.compile(rb"\x1b\[[0-9;?<>=]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[=>78]|\x1b[()][A-Z0-9]")


@dataclass
class Build:
    name: str
    argv: list[str]


def parse_builds(specs: list[str]) -> list[Build]:
    builds = []
    for spec in specs:
        if "=" not in spec:
            raise SystemExit(f"--build wants name=command, got {spec!r}")
        name, command = spec.split("=", 1)
        argv = [os.path.expanduser(a) for a in shlex.split(command, posix=not WINDOWS)]
        if WINDOWS:
            argv = [a[1:-1] if len(a) > 1 and a[0] == a[-1] == '"' else a for a in argv]
        # Runs change directory: a relative path to the executable is taken from where the tool was started.
        argv = [os.path.abspath(a) if ("/" in a or os.sep in a) and os.path.exists(a) else a for a in argv]
        if not shutil.which(argv[0]) and not os.access(argv[0], os.X_OK):
            raise SystemExit(f"build {name}: {argv[0]} is not executable")
        builds.append(Build(name, argv))
    warm_page_cache(builds)
    return builds


def warm_page_cache(builds: list[Build]) -> None:
    """Reads every build's files once, so that all are equally in the page cache. The kernel maps neighbouring pages of a file
    that are already cached along with the one that faulted, so a freshly written executable shows up to 10% more resident
    memory than the same executable read from disk; comparing a fresh build with an old one would be unfair either way. On
    Linux each file is first dropped from the page cache, so that every build is read back from disk the same way: pages a
    file was written with sit in the cache in smaller units than pages read ahead from disk, and an executable extracted moments
    before started 17% slower than the same bytes read back (Pi-Bolt 0.7.3, pi --version)."""
    for build in builds:
        # (A macOS build's pi is a launcher: the executable is pi-bin beside it.)
        paths = [*build.argv, *(os.path.join(os.path.dirname(p), "pi-bin") for p in build.argv if "/" in p)]
        for path in paths:
            if ("/" in path or os.sep in path) and os.path.isfile(path):
                with open(path, "rb") as f:
                    if hasattr(os, "posix_fadvise") and not MACOS:
                        os.fsync(f.fileno())
                        os.posix_fadvise(f.fileno(), 0, 0, os.POSIX_FADV_DONTNEED)
                    while f.read(1 << 22):
                        pass


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@contextlib.contextmanager
def fake_model(pace_ms: float = 0, script: str = "fake_model.py", log_sizes: str | None = None):
    """Starts a fake OpenAI-compatible model server; yields its port."""
    port = free_port()
    args = [sys.executable, str(HERE / script), str(port)]
    if pace_ms:
        args += ["--pace-ms", str(pace_ms)]
    if log_sizes:
        args += ["--log-sizes", log_sizes]
    proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        # Up to a minute: Python's first start on a fresh machine (a CI runner) can take many seconds, and a run that starts
        # before the server listens records a connection error that the reference run does not.
        deadline = time.time() + 60
        while True:
            try:
                socket.create_connection(("127.0.0.1", port), timeout=0.5).close()
                break
            except OSError:
                if proc.poll() is not None or time.time() > deadline:
                    raise RuntimeError(f"the fake model server ({script}) did not start on port {port}")
                time.sleep(0.05)
        yield port
    finally:
        proc.terminate()
        proc.wait()


@contextlib.contextmanager
def pi_home(port: int):
    """A Pi agent directory with only the fake model configured."""
    home = Path(tempfile.mkdtemp(prefix="pibolt-home-"))
    models = [{"id": "fake-model", "contextWindow": 200000, "maxTokens": 8192}]
    # (The other two speak the other APIs: fake_model_stress.py answers all three.)
    (home / "models.json").write_text(json.dumps({"providers": {
        "fake": {"baseUrl": f"http://127.0.0.1:{port}/v1", "api": "openai-completions", "apiKey": "fake", "models": models},
        "fake-anthropic": {"baseUrl": f"http://127.0.0.1:{port}", "api": "anthropic-messages", "apiKey": "fake", "models": models},
        "fake-responses": {"baseUrl": f"http://127.0.0.1:{port}/v1", "api": "openai-responses", "apiKey": "fake", "models": models},
    }}, indent=2))
    # A changelog seen far in the future: no "what's new" screen on start.
    (home / "settings.json").write_text(json.dumps({"lastChangelogVersion": "9999.0.0", "theme": "dark"}))
    (home / "auth.json").write_text("{}")
    # (OMP, the oh-my-pi fork, reads models.yml; JSON is YAML.)
    (home / "models.yml").write_text((home / "models.json").read_text())
    try:
        yield home
    finally:
        shutil.rmtree(home, ignore_errors=True)


@contextlib.contextmanager
def workdir():
    """A scratch project directory holding the fixture files the fake model asks Pi to read."""
    d = Path(tempfile.mkdtemp(prefix="pibolt-work-"))
    shutil.copytree(FIXTURES, d / "fixture")
    try:
        yield d
    finally:
        shutil.rmtree(d, ignore_errors=True)


def pi_env(home: Path, extra: dict | None = None) -> dict:
    # Nothing from the caller's BUN_* / NODE_* environment: those change what a build does.
    env = {k: v for k, v in os.environ.items() if not k.startswith(("BUN_", "NODE_", "PI_"))}
    env = {k: v for k, v in env.items() if not k.startswith("PIG_")}
    env.update({"PI_CODING_AGENT_DIR": str(home), "PI_OFFLINE": "1", "PI_SKIP_VERSION_CHECK": "1", "PI_TELEMETRY": "0",
                "TERM": "xterm-256color"})
    # (PiG, the Go port, reads its own names for the same settings.)
    env.update({"PIG_HOME": str(home), "PIG_CODING_AGENT_DIR": str(home), "PIG_OFFLINE": "1"})
    env.update(extra or {})
    return env


MACOS = sys.platform == "darwin"


def pinned(argv: list[str], cpus: str | None) -> list[str]:
    if cpus and (MACOS or WINDOWS):
        # macOS cannot pin a process to cores: runs share the machine (interleaving still spreads its load evenly).
        if not getattr(pinned, "warned", False):
            print(f"note: --cpus is ignored on {'Windows' if WINDOWS else 'macOS'}", file=sys.stderr)
            pinned.warned = True
        return list(argv)
    return ["taskset", "-c", cpus, *argv] if cpus else list(argv)


def maxrss_mb(ru) -> float:
    """Peak resident memory from a wait4()/getrusage() result: Linux counts it in KiB, macOS in bytes."""
    return ru.ru_maxrss / (1 << 20 if MACOS else 1024)


def alive(pid: int) -> bool:
    """Whether a process (not necessarily a child) still exists."""
    if WINDOWS:
        return winproc.alive(pid)  # (os.kill(pid, 0) would terminate it there)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _rusage_macos(pid: int):
    """proc_pid_rusage(RUSAGE_INFO_V4) as a list of its 64-bit fields after the UUID, or None if the process is gone."""
    import ctypes
    global _libproc, _ticks_ns
    if "_libproc" not in globals():
        _libproc = ctypes.CDLL("/usr/lib/libproc.dylib")
        libc = ctypes.CDLL("/usr/lib/libSystem.B.dylib")
        timebase = (ctypes.c_uint32 * 2)()
        libc.mach_timebase_info(timebase)
        _ticks_ns = timebase[0] / timebase[1]  # ri_*_time are in Mach absolute time units (24 MHz ticks on Apple silicon)
    buf = (ctypes.c_uint64 * 64)()
    if _libproc.proc_pid_rusage(pid, 4, buf) != 0:
        return None
    return list(buf)[2:]


def peak_footprint_mb(pid: int, block: bool = True) -> float | None:
    """macOS: the largest physical footprint a child had (ri_lifetime_max_phys_footprint), read once it has exited and before
    it is reaped: its memory at the peak without the clean file pages and the freed pages the kernel may take back at will,
    which ru_maxrss counts too. None elsewhere, or (block False) while the child still runs, or if it is not a child to wait
    for. Reap it after (wait4)."""
    if not MACOS:
        return None
    import ctypes
    global _libc
    if "_libc" not in globals():
        _libc = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
    info = ctypes.create_string_buffer(128)  # siginfo_t: si_signo, si_errno, si_code, then si_pid
    # waitid(P_PID, pid, &info, WEXITED | WNOWAIT [| WNOHANG])
    if _libc.waitid(1, pid, info, 0x4 | 0x20 | (0 if block else 0x1)) != 0:
        return None
    if int.from_bytes(info.raw[12:16], "little") != pid:
        return None
    ri = _rusage_macos(pid)
    global last_exit_counters
    # Instructions and cycles retired (millions): steadier than CPU time on a machine that moves work between core types.
    last_exit_counters = {"instr_m": round(ri[29] / 1e6, 1), "cycles_m": round(ri[30] / 1e6, 1)} if ri else {}
    # (Exited, in any case: 0.0 if its figures cannot be read, so that whoever asked goes on to reap it.)
    return round(ri[28] / (1 << 20), 1) if ri else 0.0


last_exit_counters: dict = {}


class PeakOwnSampler:
    """Linux: the largest private memory a running process reached, the counterpart of macOS's peak footprint. ru_maxrss also
    counts the clean pages of the files it maps (Pi-Bolt's executable maps its 114 MB static heap and compiled code; a Go
    binary maps its own text), which the kernel can drop and share. RssAnon (in /proc/<pid>/status) is the process's own
    resident memory: its heaps, and file pages it has written to. Linux keeps no peak of it, so a thread samples it every
    `interval` seconds from start() until the process is gone or stop(): a spike shorter than that can be missed.
    Elsewhere it does nothing and `peak_mb` stays None."""

    def __init__(self, pid: int, interval: float = 0.002):
        self.pid, self.interval, self.peak_mb = pid, interval, None
        self._stop = threading.Event()
        self._thread = None
        if sys.platform.startswith("linux"):
            self._thread = threading.Thread(target=self._run, daemon=True)
            self._thread.start()

    def _sample(self) -> bool:
        try:
            with open(f"/proc/{self.pid}/status") as f:
                for line in f:
                    if line.startswith("RssAnon:"):
                        mb = round(int(line.split()[1]) / 1024, 1)
                        self.peak_mb = mb if self.peak_mb is None else max(self.peak_mb, mb)
                        return True
        except OSError:
            pass
        return False  # gone, or reaped (a zombie's status has no RssAnon line)

    def _run(self):
        while not self._stop.is_set() and self._sample():
            self._stop.wait(self.interval)

    def stop(self) -> float | None:
        """Stops sampling (call it before the process is reaped, or right after) and returns the peak in MB."""
        if self._thread:
            self._sample()
            self._stop.set()
            self._thread.join()
        return self.peak_mb


class WinTty:
    """Tty's counterpart on Windows: Pi in a ConPTY, in a Job object. quit() returns (status, winproc.Usage)."""

    def __init__(self, argv, env, cwd, cols=120, rows=40, stderr=None):
        self.console = winproc.ConPty(argv, env, cwd, cols, rows, stderr=stderr)
        self.pid = self.console.proc.pid
        self.buf = b""

    def pump(self, timeout):
        data = self.console.read(timeout)
        if not data:
            return False
        self.buf += data
        # (ConPTY answers most queries itself; these it passes on to the terminal, which is us.)
        if b"\x1b[c" in data:
            self.console.write(b"\x1b[?62;22c")
        if b"\x1b]11;?" in data:
            self.console.write(b"\x1b]11;rgb:0000/0000/0000\x1b\\")
        if b"\x1b[6n" in data:
            self.console.write(b"\x1b[1;1R")
        return True

    def send(self, data: bytes):
        self.console.write(data)

    def resize(self, cols, rows):
        self.console.resize(cols, rows)

    wait_for = lambda self, *a: Tty.wait_for(self, *a)  # noqa: E731
    settle = lambda self, *a: Tty.settle(self, *a)  # noqa: E731
    screen_text = lambda self, *a: Tty.screen_text(self, *a)  # noqa: E731

    def quit(self, timeout=10):
        self.send(b"/quit\r")
        end = time.perf_counter() + timeout
        status = None
        while time.perf_counter() < end:
            self.pump(0.02)
            status = self.console.proc.poll()
            if status is not None:
                break
        if status is None:
            self.console.proc.kill()
            self.console.proc.wait()
        result = self.console.proc.result()
        self.console.close()
        return status, winproc.Usage(result)


class Tty:
    """A minimal terminal on a pseudo-terminal: answers the capability queries the TUI sends the way xterm does. stderr: a file
    the program's standard error goes to instead of the terminal (diagnostics the TUI must not draw over)."""

    def __new__(cls, *args, **kwargs):
        return WinTty(*args, **kwargs) if WINDOWS else super().__new__(cls)

    def __init__(self, argv, env, cwd, cols=120, rows=40, stderr=None):
        self.slow_quit = any(a.endswith("opencode.sh") for a in argv)
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.chdir(cwd)
            if stderr is not None:
                os.dup2(os.open(stderr, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644), 2)
            os.execvpe(argv[0], argv, env)
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.buf = b""

    def pump(self, timeout):
        r, _, _ = select.select([self.fd], [], [], timeout)
        if not r:
            return False
        try:
            data = os.read(self.fd, 1 << 16)
        except OSError:
            return False
        self.buf += data
        if b"\x1b[c" in data:
            os.write(self.fd, b"\x1b[?62;22c")
        if b"\x1b]11;?" in data:
            os.write(self.fd, b"\x1b]11;rgb:0000/0000/0000\x1b\\")
        return True

    def send(self, data: bytes):
        os.write(self.fd, data)

    def wait_for(self, text, since, deadline):
        """Until `text` is on the screen (written after `since`). A prompt submitted before Pi has finished starting is
        kept in the editor with the status "Startup is still in progress" (a tool may see Pi's screen before startup ends,
        and type at once): Enter is pressed again then, as a person would, until Pi takes it."""
        needle = text.encode()
        scan = since
        startup_scan = since
        # (When Pi said so last, and when Enter was pressed again. The status may stay on the screen without being drawn
        # again, so Enter is pressed every 0.3 s for a while: on an empty editor it does nothing.)
        startup_seen = sent = -1.0
        while time.perf_counter() < deadline:
            self.pump(0.01)
            if needle in ANSI.sub(b"", self.buf[scan:]):
                return True
            now = time.perf_counter()
            if STARTUP_IN_PROGRESS in ANSI.sub(b"", self.buf[startup_scan:]):
                startup_scan = len(self.buf)
                startup_seen = now
            else:
                # (Only the end is read again: reading all that a long transcript wrote, at every read, took minutes.)
                startup_scan = max(startup_scan, len(self.buf) - 8192)
            if startup_seen >= 0 and now - startup_seen < 10 and now - sent > 0.3:
                self.send(b"\r")
                sent = now
            scan = max(since, len(self.buf) - 8192)
        return False

    def settle(self, quiet, deadline):
        """Until the screen has not changed for `quiet` seconds."""
        while time.perf_counter() < deadline:
            if not self.pump(quiet):
                return True
        return False

    def screen_text(self, since=0):
        return re.sub(r"\s+", " ", ANSI.sub(b" ", self.buf[since:]).decode("utf-8", "replace"))

    def quit(self, timeout=10):
        """Sends /quit; returns the exit status, or None if it had to be killed."""
        if self.slow_quit:
            # (OpenCode opens its command menu on "/": Enter is sent once it is open.)
            self.send(b"/quit")
            end = time.perf_counter() + 0.3
            while time.perf_counter() < end:
                self.pump(0.02)
            self.send(b"\r")
        else:
            self.send(b"/quit\r")
        end = time.perf_counter() + timeout
        while time.perf_counter() < end:
            self.pump(0.02)
            if MACOS:
                # (Before it is reaped, if it has exited. If that cannot be told, wait4 below still can.)
                self.peak_footprint_mb = peak_footprint_mb(self.pid, block=False) or None
                if self.peak_footprint_mb is not None:
                    self.exit_counters = dict(last_exit_counters)
            pid, status, ru = os.wait4(self.pid, os.WNOHANG)
            if pid:
                os.close(self.fd)
                return os.waitstatus_to_exitcode(status), ru
        os.kill(self.pid, 9)
        _, status, ru = os.wait4(self.pid, 0)
        os.close(self.fd)
        return None, ru


def cpu_ms(pid: int) -> float:
    """CPU time of every thread of a live process: from schedstat (nanoseconds), on macOS from proc_pid_rusage, on Windows
    from its cycles."""
    if WINDOWS:
        return winproc.cpu_ms(pid)
    if MACOS:
        ri = _rusage_macos(pid)
        return (ri[0] + ri[1]) * _ticks_ns / 1e6 if ri else 0.0
    total = 0
    for tid in os.listdir(f"/proc/{pid}/task"):
        with contextlib.suppress(OSError):
            total += int(open(f"/proc/{pid}/task/{tid}/schedstat").read().split()[0])
    return total / 1e6


def memory_mb(pid: int) -> dict:
    """Resident memory, and the process's own (private dirty) memory: what it costs beyond shared, droppable file pages. On
    macOS "own" is the physical footprint (what Activity Monitor shows: dirty and compressed memory, without clean file pages).
    On Windows: the working set, and "own" is the private bytes (commit charge)."""
    if WINDOWS:
        return winproc.memory_mb(pid)
    if MACOS:
        ri = _rusage_macos(pid)
        return {"rss": round(ri[6] / (1 << 20), 1), "own": round(ri[7] / (1 << 20), 1)} if ri else {}
    out = {}
    with contextlib.suppress(OSError):
        for line in open(f"/proc/{pid}/smaps_rollup"):
            key, value = line.split(":", 1)
            if key in ("Rss", "Private_Dirty"):
                out[{"Rss": "rss", "Private_Dirty": "own"}[key]] = round(int(value.split()[0]) / 1024, 1)
    return out


def median(values):
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    mid = len(values) // 2
    return values[mid] if len(values) % 2 else (values[mid - 1] + values[mid]) / 2
