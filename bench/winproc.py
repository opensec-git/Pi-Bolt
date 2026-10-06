"""Windows processes for the benchmark tools: Job objects, CPU and memory accounting, and ConPTY.

Every process is started suspended, put in a Job object of its own and then resumed, so that what it starts (bash, tools) is
counted with it, as wait4() counts a child's waited-for children on Linux. What is measured:

- CPU: the job's user + kernel time (every process that ran in it), and the cycles of the main process
  (QueryProcessCycleTime) converted to milliseconds at the time-stamp counter's rate. Thread times on Windows advance in clock
  ticks (15.6 ms by default), so short runs are measured by cycles.
- Memory: the main process's peak working set and peak private bytes (commit charge), from GetProcessMemoryInfo, and the
  job's peak private bytes of any one process. And the system's commit charge (GetPerformanceInfo), which also counts what a
  process's private bytes do not: an executable's image section is charged for its writable and uninitialized pages, once, when
  it is first mapped, and stays charged while Windows keeps the image cached, after the process has exited. Its rise while the
  process ran (sampled) and what is left of it after the process exited; both are system-wide, so they are only meaningful on an
  otherwise quiet machine, and repeated.

Pi runs in a ConPTY (CreatePseudoConsole), the Windows pseudo-terminal behind Windows Terminal, instead of tmux.
"""

from __future__ import annotations

import ctypes
import os
import queue
import subprocess
import threading
import time
from ctypes import wintypes as w

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)

CREATE_SUSPENDED = 0x4
CREATE_UNICODE_ENVIRONMENT = 0x400
EXTENDED_STARTUPINFO_PRESENT = 0x80000
CREATE_NO_WINDOW = 0x08000000
PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016
INFINITE = 0xFFFFFFFF
WAIT_TIMEOUT = 0x102
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
JobObjectBasicAccountingInformation = 1
JobObjectExtendedLimitInformation = 9
STARTF_USESTDHANDLES = 0x100


class STARTUPINFOW(ctypes.Structure):
    _fields_ = [("cb", w.DWORD), ("lpReserved", w.LPWSTR), ("lpDesktop", w.LPWSTR), ("lpTitle", w.LPWSTR),
                ("dwX", w.DWORD), ("dwY", w.DWORD), ("dwXSize", w.DWORD), ("dwYSize", w.DWORD),
                ("dwXCountChars", w.DWORD), ("dwYCountChars", w.DWORD), ("dwFillAttribute", w.DWORD),
                ("dwFlags", w.DWORD), ("wShowWindow", w.WORD), ("cbReserved2", w.WORD), ("lpReserved2", ctypes.c_void_p),
                ("hStdInput", w.HANDLE), ("hStdOutput", w.HANDLE), ("hStdError", w.HANDLE)]


class STARTUPINFOEXW(ctypes.Structure):
    _fields_ = [("StartupInfo", STARTUPINFOW), ("lpAttributeList", ctypes.c_void_p)]


class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [("hProcess", w.HANDLE), ("hThread", w.HANDLE), ("dwProcessId", w.DWORD), ("dwThreadId", w.DWORD)]


class IO_COUNTERS(ctypes.Structure):
    _fields_ = [(n, ctypes.c_ulonglong) for n in ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                                                  "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]


class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", w.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t), ("MaximumWorkingSetSize", ctypes.c_size_t),
                ("ActiveProcessLimit", w.DWORD), ("Affinity", ctypes.c_size_t), ("PriorityClass", w.DWORD),
                ("SchedulingClass", w.DWORD)]


class JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION), ("IoInfo", IO_COUNTERS),
                ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]


class JOBOBJECT_BASIC_ACCOUNTING_INFORMATION(ctypes.Structure):
    _fields_ = [("TotalUserTime", ctypes.c_longlong), ("TotalKernelTime", ctypes.c_longlong),
                ("ThisPeriodTotalUserTime", ctypes.c_longlong), ("ThisPeriodTotalKernelTime", ctypes.c_longlong),
                ("TotalPageFaultCount", w.DWORD), ("TotalProcesses", w.DWORD), ("ActiveProcesses", w.DWORD),
                ("TotalTerminatedProcesses", w.DWORD)]


class PROCESS_MEMORY_COUNTERS_EX(ctypes.Structure):
    _fields_ = [("cb", w.DWORD), ("PageFaultCount", w.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t), ("PrivateUsage", ctypes.c_size_t)]


class COORD(ctypes.Structure):
    _fields_ = [("X", ctypes.c_short), ("Y", ctypes.c_short)]


def _check(ok):
    if not ok:
        raise ctypes.WinError(ctypes.get_last_error())
    return ok


k32.CreateJobObjectW.restype = w.HANDLE
k32.CreatePseudoConsole.argtypes = [COORD, w.HANDLE, w.HANDLE, w.DWORD, ctypes.POINTER(ctypes.c_void_p)]
k32.ResizePseudoConsole.argtypes = [ctypes.c_void_p, COORD]
k32.ClosePseudoConsole.argtypes = [ctypes.c_void_p]
k32.CreateProcessW.argtypes = [w.LPCWSTR, w.LPWSTR, ctypes.c_void_p, ctypes.c_void_p, w.BOOL, w.DWORD, ctypes.c_void_p,
                               w.LPCWSTR, ctypes.c_void_p, ctypes.POINTER(PROCESS_INFORMATION)]
k32.ReadFile.argtypes = [w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p]
k32.WriteFile.argtypes = [w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p]
k32.QueryInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD)]
k32.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
k32.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
k32.QueryProcessCycleTime.argtypes = [w.HANDLE, ctypes.POINTER(ctypes.c_ulonglong)]
k32.QueryThreadCycleTime.argtypes = [w.HANDLE, ctypes.POINTER(ctypes.c_ulonglong)]
k32.GetCurrentThread.restype = w.HANDLE
k32.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
k32.GetExitCodeProcess.argtypes = [w.HANDLE, ctypes.POINTER(w.DWORD)]
k32.TerminateJobObject.argtypes = [w.HANDLE, w.UINT]
k32.CloseHandle.argtypes = [w.HANDLE]
k32.ResumeThread.argtypes = [w.HANDLE]
k32.InitializeProcThreadAttributeList.argtypes = [ctypes.c_void_p, w.DWORD, w.DWORD, ctypes.POINTER(ctypes.c_size_t)]
k32.UpdateProcThreadAttribute.argtypes = [ctypes.c_void_p, w.DWORD, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t,
                                          ctypes.c_void_p, ctypes.c_void_p]
k32.CreatePipe.argtypes = [ctypes.POINTER(w.HANDLE), ctypes.POINTER(w.HANDLE), ctypes.c_void_p, w.DWORD]
k32.SetHandleInformation.argtypes = [w.HANDLE, w.DWORD, w.DWORD]
psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, ctypes.c_void_p, w.DWORD]


class PERFORMANCE_INFORMATION(ctypes.Structure):
    _fields_ = [("cb", w.DWORD), ("CommitTotal", ctypes.c_size_t), ("CommitLimit", ctypes.c_size_t), ("CommitPeak", ctypes.c_size_t),
                ("PhysicalTotal", ctypes.c_size_t), ("PhysicalAvailable", ctypes.c_size_t), ("SystemCache", ctypes.c_size_t),
                ("KernelTotal", ctypes.c_size_t), ("KernelPaged", ctypes.c_size_t), ("KernelNonpaged", ctypes.c_size_t),
                ("PageSize", ctypes.c_size_t), ("HandleCount", w.DWORD), ("ProcessCount", w.DWORD), ("ThreadCount", w.DWORD)]


psapi.GetPerformanceInfo.argtypes = [ctypes.c_void_p, w.DWORD]


def system_commit_mb() -> float:
    """The system's commit charge now (every process, the kernel, and the images' sections), in MB."""
    info = PERFORMANCE_INFORMATION()
    info.cb = ctypes.sizeof(info)
    psapi.GetPerformanceInfo(ctypes.byref(info), info.cb)
    return info.CommitTotal * info.PageSize / 2**20


_tsc_hz = None


def tsc_hz() -> float:
    """The rate at which QueryProcessCycleTime counts: measured once against the performance counter, on this thread, busy."""
    global _tsc_hz
    if _tsc_hz is None:
        thread = k32.GetCurrentThread()
        c0, c1 = ctypes.c_ulonglong(), ctypes.c_ulonglong()
        best = 0.0
        for _ in range(3):
            k32.QueryThreadCycleTime(thread, ctypes.byref(c0))
            t0 = time.perf_counter()
            while time.perf_counter() - t0 < 0.2:
                pass
            k32.QueryThreadCycleTime(thread, ctypes.byref(c1))
            best = max(best, (c1.value - c0.value) / (time.perf_counter() - t0))
        _tsc_hz = best
    return _tsc_hz


def _cmdline(argv: list[str]) -> str:
    return subprocess.list2cmdline(argv)


def _env_block(env: dict | None):
    if env is None:
        return None
    # Sorted, case-insensitively, as Windows expects.
    text = "".join(f"{k}={v}\0" for k, v in sorted(env.items(), key=lambda kv: kv[0].upper())) + "\0"
    return ctypes.create_unicode_buffer(text, len(text))


class Measured:
    """A process started suspended in a Job object of its own. Use .result() after it has exited."""

    def __init__(self, argv, env=None, cwd=None, pseudo_console=None, stdin=None, stdout=None, stderr=None):
        self.job = _check(k32.CreateJobObjectW(None, None))
        limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        _check(k32.SetInformationJobObject(self.job, JobObjectExtendedLimitInformation, ctypes.byref(limits), ctypes.sizeof(limits)))
        si = STARTUPINFOEXW()
        si.StartupInfo.cb = ctypes.sizeof(STARTUPINFOEXW)
        flags = CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT
        inherit = False
        self._attrs = None
        if pseudo_console is not None:
            size = ctypes.c_size_t()
            k32.InitializeProcThreadAttributeList(None, 1, 0, ctypes.byref(size))
            self._attrs = ctypes.create_string_buffer(size.value)
            _check(k32.InitializeProcThreadAttributeList(self._attrs, 1, 0, ctypes.byref(size)))
            self._hpc = ctypes.c_void_p(pseudo_console)
            _check(k32.UpdateProcThreadAttribute(self._attrs, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, self._hpc,
                                                 ctypes.sizeof(ctypes.c_void_p), None, None))
            si.lpAttributeList = ctypes.cast(self._attrs, ctypes.c_void_p)
            # (No standard handles of ours: the pseudo-console's.)
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES
        else:
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES
            si.StartupInfo.hStdInput, si.StartupInfo.hStdOutput, si.StartupInfo.hStdError = stdin, stdout, stderr
            inherit = True
            flags |= CREATE_NO_WINDOW
        pi = PROCESS_INFORMATION()
        cmd = ctypes.create_unicode_buffer(_cmdline(argv))
        self.env_block = _env_block(env)
        self.commit_before = system_commit_mb()
        self.commit_peak = self.commit_before
        self.t0 = time.perf_counter()
        _check(k32.CreateProcessW(None, cmd, None, None, inherit, flags, self.env_block, None if cwd is None else str(cwd),
                                  ctypes.byref(si), ctypes.byref(pi)))
        self.process, self.pid = pi.hProcess, pi.dwProcessId
        _check(k32.AssignProcessToJobObject(self.job, self.process))
        k32.ResumeThread(pi.hThread)
        k32.CloseHandle(pi.hThread)
        self._result = None
        # The system's commit charge, every 50 ms while the process runs (one call each: nothing to speak of).
        self._sampling = threading.Thread(target=self._sample_commit, daemon=True)
        self._sampling.start()

    def _sample_commit(self):
        while self.process and k32.WaitForSingleObject(self.process, 50) == WAIT_TIMEOUT:
            self.commit_peak = max(self.commit_peak, system_commit_mb())

    def poll(self):
        if k32.WaitForSingleObject(self.process, 0) == WAIT_TIMEOUT:
            return None
        code = w.DWORD()
        k32.GetExitCodeProcess(self.process, ctypes.byref(code))
        return code.value

    def wait(self, timeout=None):
        k32.WaitForSingleObject(self.process, INFINITE if timeout is None else int(timeout * 1000))
        return self.poll()

    def kill(self):
        k32.TerminateJobObject(self.job, 1)

    def cpu_ms(self) -> float:
        """CPU of the main process so far, by cycles."""
        cycles = ctypes.c_ulonglong()
        k32.QueryProcessCycleTime(self.process, ctypes.byref(cycles))
        return cycles.value / tsc_hz() * 1e3

    def memory_mb(self) -> dict:
        """Working set and private bytes of the main process, now."""
        pmc = PROCESS_MEMORY_COUNTERS_EX()
        pmc.cb = ctypes.sizeof(pmc)
        psapi.GetProcessMemoryInfo(self.process, ctypes.byref(pmc), pmc.cb)
        return {"rss": round(pmc.WorkingSetSize / 2**20, 1), "own": round(pmc.PrivateUsage / 2**20, 1)}

    def result(self) -> dict:
        """After exit: wall time, CPU and peak memory, in the benchmark tools' fields."""
        if self._result is not None:
            return self._result
        wall = (time.perf_counter() - self.t0) * 1e3
        acct = JOBOBJECT_BASIC_ACCOUNTING_INFORMATION()
        k32.QueryInformationJobObject(self.job, JobObjectBasicAccountingInformation, ctypes.byref(acct), ctypes.sizeof(acct), None)
        ext = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
        k32.QueryInformationJobObject(self.job, JobObjectExtendedLimitInformation, ctypes.byref(ext), ctypes.sizeof(ext), None)
        pmc = PROCESS_MEMORY_COUNTERS_EX()
        pmc.cb = ctypes.sizeof(pmc)
        psapi.GetProcessMemoryInfo(self.process, ctypes.byref(pmc), pmc.cb)
        cycles = ctypes.c_ulonglong()
        k32.QueryProcessCycleTime(self.process, ctypes.byref(cycles))
        code = self.poll()
        commit_after = system_commit_mb()
        self._result = {
            "exit": code,
            "wall_ms": wall,
            # The main process by cycles; with everything else the job ran (by clock ticks) added.
            "cpu_ms": cycles.value / tsc_hz() * 1e3,
            "job_cpu_ms": (acct.TotalUserTime + acct.TotalKernelTime) / 1e4,
            "processes": acct.TotalProcesses,
            "peak_mb": round(pmc.PeakWorkingSetSize / 2**20, 1),  # peak working set (like ru_maxrss)
            "peak_private_mb": round(pmc.PeakPagefileUsage / 2**20, 1),  # peak commit charge of the main process
            "job_peak_private_mb": round(ext.PeakProcessMemoryUsed / 2**20, 1),
            "page_faults": pmc.PageFaultCount,
            # System-wide (see the top of this file): the rise while it ran, and what is still charged after it exited.
            "system_commit_peak_mb": round(self.commit_peak - self.commit_before, 1),
            "system_commit_left_mb": round(commit_after - self.commit_before, 1),
        }
        return self._result

    def close(self):
        if self.process:
            k32.TerminateJobObject(self.job, 1) if self.poll() is None else None
            self._sampling.join(timeout=5)  # (before its handle goes)
            k32.CloseHandle(self.process)
            self.process = None
        if self.job:
            k32.CloseHandle(self.job)  # (kills what is left: JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            self.job = None


def run(argv, env=None, cwd=None, timeout=300) -> tuple[bytes, dict]:
    """Runs a command to completion with stdin from NUL and stdout+stderr captured. Returns (output, result())."""
    import msvcrt
    import tempfile

    with tempfile.TemporaryFile() as out, open(os.devnull, "rb") as devnull:
        def handle(f):
            h = msvcrt.get_osfhandle(f.fileno())
            k32.SetHandleInformation(h, 1, 1)  # HANDLE_FLAG_INHERIT
            return h

        p = Measured(argv, env, cwd, stdin=handle(devnull), stdout=handle(out), stderr=handle(out))
        try:
            if p.wait(timeout) is None:
                p.kill()
                p.wait()
            r = p.result()
        finally:
            p.close()
        out.seek(0)
        return out.read(), r


class ConPty:
    """A pseudo-console running one process: bytes in, the screen's VT stream out."""

    def __init__(self, argv, env, cwd, cols=120, rows=40):
        in_read, in_write, out_read, out_write = w.HANDLE(), w.HANDLE(), w.HANDLE(), w.HANDLE()
        _check(k32.CreatePipe(ctypes.byref(in_read), ctypes.byref(in_write), None, 0))
        _check(k32.CreatePipe(ctypes.byref(out_read), ctypes.byref(out_write), None, 0))
        self.hpc = ctypes.c_void_p()
        hr = k32.CreatePseudoConsole(COORD(cols, rows), in_read, out_write, 0, ctypes.byref(self.hpc))
        if hr != 0:
            raise OSError(f"CreatePseudoConsole failed: {hr:#x}")
        self.proc = Measured(argv, env, cwd, pseudo_console=self.hpc.value)
        # The console has its own copies now.
        k32.CloseHandle(in_read)
        k32.CloseHandle(out_write)
        self.inp, self.out = in_write, out_read
        self.chunks: queue.Queue[bytes | None] = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        buf = ctypes.create_string_buffer(1 << 16)
        n = w.DWORD()
        while k32.ReadFile(self.out, buf, len(buf), ctypes.byref(n), None) and n.value:
            self.chunks.put(buf.raw[:n.value])
        self.chunks.put(None)

    def read(self, timeout) -> bytes | None:
        """Output that arrived within `timeout` seconds; b"" if none did; None once the console has closed."""
        try:
            chunk = self.chunks.get(timeout=timeout)
        except queue.Empty:
            return b""
        if chunk is None:
            self.chunks.put(None)
            return None
        parts = [chunk]
        while True:
            try:
                more = self.chunks.get_nowait()
            except queue.Empty:
                break
            if more is None:
                self.chunks.put(None)
                break
            parts.append(more)
        return b"".join(parts)

    def write(self, data: bytes):
        n = w.DWORD()
        k32.WriteFile(self.inp, data, len(data), ctypes.byref(n), None)

    def resize(self, cols, rows):
        k32.ResizePseudoConsole(self.hpc, COORD(cols, rows))

    def close(self):
        """Closes the console (which ends what still runs in it) and the pipes."""
        if self.hpc:
            # The reader keeps draining while the console closes (ClosePseudoConsole can block until its output is read), and
            # sees the end of it once it has.
            k32.ClosePseudoConsole(self.hpc)
            self.hpc = None
            self.reader.join(5)
        for h in (self.inp, self.out):
            if h:
                k32.CloseHandle(h)
        self.inp = self.out = None
        self.proc.close()


def open_process(pid: int):
    """A handle for querying a process that is not ours to wait for (0 if it is gone)."""
    PROCESS_QUERY_LIMITED_INFORMATION, PROCESS_VM_READ = 0x1000, 0x10
    k32.OpenProcess.restype = w.HANDLE
    return k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, False, pid)


def alive(pid: int) -> bool:
    h = open_process(pid)
    if not h:
        return False
    try:
        return k32.WaitForSingleObject(h, 0) == WAIT_TIMEOUT
    finally:
        k32.CloseHandle(h)


def cpu_ms(pid: int) -> float:
    h = open_process(pid)
    if not h:
        return 0.0
    try:
        cycles = ctypes.c_ulonglong()
        k32.QueryProcessCycleTime(h, ctypes.byref(cycles))
        return cycles.value / tsc_hz() * 1e3
    finally:
        k32.CloseHandle(h)


def memory_mb(pid: int) -> dict:
    h = open_process(pid)
    if not h:
        return {}
    try:
        pmc = PROCESS_MEMORY_COUNTERS_EX()
        pmc.cb = ctypes.sizeof(pmc)
        psapi.GetProcessMemoryInfo(h, ctypes.byref(pmc), pmc.cb)
        return {"rss": round(pmc.WorkingSetSize / 2**20, 1), "own": round(pmc.PrivateUsage / 2**20, 1)}
    finally:
        k32.CloseHandle(h)


class Usage:
    """What wait4() gives on Linux, from a Measured process's result(), for the tools that read rusage fields."""

    def __init__(self, result: dict):
        self.result = result
        self.ru_utime = result["cpu_ms"] / 1e3  # (all of it: the cycles do not tell user from kernel)
        self.ru_stime = 0.0
        self.ru_maxrss = result["peak_mb"] * 1024  # KiB, as on Linux
