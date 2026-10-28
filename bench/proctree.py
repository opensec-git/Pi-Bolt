"""The CPU and memory of a program's whole process tree (a program that runs a server or helpers as child processes is
counted with them): a thread samples the tree every 5 ms. Per process it keeps the last CPU time seen (so a child that exits
keeps what it used up to its last sample) and the memory; the tree's memory is the sum over the processes alive at a sample.

Linux: CPU from each thread's schedstat (exited threads at their last sample), own memory = RssAnon, rss = VmRSS; the peak is the largest sum seen.
macOS: CPU, instructions and cycles from proc_pid_rusage; own = physical footprint; the peak is the sum of each process's
lifetime maximum footprint (an upper bound when processes peak at different times)."""

import contextlib
import ctypes
import os
import signal
import sys
import threading

MACOS = sys.platform == "darwin"
if MACOS:
    _libproc = ctypes.CDLL("/usr/lib/libproc.dylib")
    _libc = ctypes.CDLL("/usr/lib/libSystem.B.dylib")
    _tb = (ctypes.c_uint32 * 2)()
    _libc.mach_timebase_info(_tb)
    _TICKS_NS = _tb[0] / _tb[1]


def children(pid):
    if MACOS:
        buf = (ctypes.c_int * 512)()
        n = _libproc.proc_listchildpids(pid, buf, ctypes.sizeof(buf))
        return [buf[i] for i in range(max(n, 0)) if buf[i] > 0]
    out = []
    with contextlib.suppress(OSError):
        for tid in os.listdir(f"/proc/{pid}/task"):
            with contextlib.suppress(OSError):
                out += [int(x) for x in open(f"/proc/{pid}/task/{tid}/children").read().split()]
    return out


def tree(pid):
    pids, todo = [], [pid]
    while todo:
        p = todo.pop()
        pids.append(p)
        todo += children(p)
    return pids


def sample(pid):
    """{cpu_ms, own_mb, rss_mb, maxfp_mb, instr_m, cycles_m} of one process, or None if gone."""
    if MACOS:
        buf = (ctypes.c_uint64 * 64)()
        if _libproc.proc_pid_rusage(pid, 4, buf) != 0:
            return None
        ri = list(buf)[2:]
        return {"cpu_ms": (ri[0] + ri[1]) * _TICKS_NS / 1e6, "rss_mb": ri[6] / 2**20, "own_mb": ri[7] / 2**20,
                "maxfp_mb": ri[28] / 2**20, "instr_m": ri[29] / 1e6, "cycles_m": ri[30] / 1e6}
    r = {}
    try:
        for line in open(f"/proc/{pid}/status"):
            if line.startswith("RssAnon:"):
                r["own_mb"] = int(line.split()[1]) / 1024
            elif line.startswith("VmRSS:"):
                r["rss_mb"] = int(line.split()[1]) / 1024
        if "own_mb" not in r:
            return None  # a zombie
        # Per thread: a thread that exits leaves the listing, so the sampler keeps each thread's last reading.
        threads = {}
        for tid in os.listdir(f"/proc/{pid}/task"):
            with contextlib.suppress(OSError):
                threads[tid] = int(open(f"/proc/{pid}/task/{tid}/schedstat").read().split()[0]) / 1e6
        r["threads"] = threads
    except OSError:
        return None
    return r


class TreeSampler:
    def __init__(self, pid, interval=0.005):
        self.pid, self.interval = pid, interval
        self.last = {}  # pid -> last sample
        self.peak_own_mb = 0.0
        self.lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _tick(self):
        alive = {}
        for p in tree(self.pid):
            s = sample(p)
            if s:
                alive[p] = s
        with self.lock:
            for p, s in alive.items():
                prev = self.last.get(p)
                if prev and "maxfp_mb" in prev:
                    s["maxfp_mb"] = max(s["maxfp_mb"], prev["maxfp_mb"])
                if "threads" in s:
                    s["threads"] = {**(prev or {}).get("threads", {}), **s["threads"]}
                    s["cpu_ms"] = sum(s["threads"].values())
                self.last[p] = s
            if MACOS:
                self.peak_own_mb = sum(s["maxfp_mb"] for s in self.last.values())
            else:
                self.peak_own_mb = max(self.peak_own_mb, sum(s.get("own_mb", 0) for s in alive.values()))
            self.alive = alive
        return bool(alive)

    def _run(self):
        while not self._stop.is_set():
            self._tick()
            self._stop.wait(self.interval)

    def snapshot(self):
        """Now: the tree's CPU (all processes seen, exited ones at their last sample), instructions, and the memory of the
        processes alive."""
        self._tick()
        with self.lock:
            snap = {"cpu_ms": sum(s["cpu_ms"] for s in self.last.values()),
                    "own_mb": sum(s.get("own_mb", 0) for s in self.alive.values()),
                    "rss_mb": sum(s.get("rss_mb", 0) for s in self.alive.values()),
                    "peak_own_mb": self.peak_own_mb, "procs": len(self.alive)}
            if MACOS:
                snap["instr_m"] = sum(s["instr_m"] for s in self.last.values())
                snap["cycles_m"] = sum(s["cycles_m"] for s in self.last.values())
            return snap

    def stop(self):
        self._stop.set()
        self._thread.join()


def kill_tree(pid):
    pids = tree(pid)
    with contextlib.suppress(OSError):
        os.killpg(pid, signal.SIGKILL)
    for p in pids:
        with contextlib.suppress(OSError):
            os.kill(p, signal.SIGKILL)
