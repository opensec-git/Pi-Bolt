#!/usr/bin/env python3
"""What Pi costs while it waits at its prompt: the TUI after one prompt is answered, left alone for a while. Reports CPU per
second of idle (all threads; on Linux also per thread, from /proc) and the process's own memory at the start and end of the wait.

Usage: idle.py --build name=command [--build ...] [--seconds 30] [--rounds 3]"""

import argparse
import contextlib
import os
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import (  # noqa: E402
    DONE, MACOS, MODEL_ARGS, PROMPT, Tty, cpu_ms, done, fake_model, memory_mb, parse_builds, pi_env, pi_home, workdir,
)


def threads(pid: int) -> dict:
    """CPU nanoseconds and context switches of each thread, by name (Linux)."""
    out = {}
    with contextlib.suppress(OSError):
        for tid in os.listdir(f"/proc/{pid}/task"):
            with contextlib.suppress(OSError):
                name = open(f"/proc/{pid}/task/{tid}/comm").read().strip()
                ns = int(open(f"/proc/{pid}/task/{tid}/schedstat").read().split()[0])
                switches = 0
                for line in open(f"/proc/{pid}/task/{tid}/status"):
                    if line.startswith(("voluntary_ctxt_switches", "nonvoluntary_ctxt_switches")):
                        switches += int(line.split()[1])
                cpu, sw = out.get(name, (0, 0))
                out[name] = (cpu + ns, sw + switches)
    return out


def run(build, seconds: float):
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        tty = Tty([*build.argv, "--no-session", *MODEL_ARGS], pi_env(home), cwd)
        deadline = time.perf_counter() + 120
        assert tty.wait_for("fake-model", 0, deadline), "the TUI did not start"
        start = len(tty.buf)
        tty.send(PROMPT.encode() + b"\r")
        assert tty.wait_for(done(1), start, deadline), "no answer"
        tty.settle(2.0, deadline)
        mem0 = memory_mb(tty.pid)
        cpu0, th0, t0 = cpu_ms(tty.pid), threads(tty.pid), time.perf_counter()
        end = t0 + seconds
        while time.perf_counter() < end:
            tty.pump(0.2)
        cpu1, th1, t1 = cpu_ms(tty.pid), threads(tty.pid), time.perf_counter()
        mem1 = memory_mb(tty.pid)
        tty.quit()
    per_thread = {}
    for name, (ns, sw) in th1.items():
        ns0, sw0 = th0.get(name, (0, 0))
        if ns - ns0 > 0 or sw - sw0 > 0:
            per_thread[name] = (round((ns - ns0) / 1e6 / (t1 - t0), 3), round((sw - sw0) / (t1 - t0), 1))
    return {"build": build.name, "cpu_ms_per_s": round((cpu1 - cpu0) / (t1 - t0), 3), "own_mb": [mem0.get("own"), mem1.get("own")],
            "threads": per_thread}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True)
    ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--rounds", type=int, default=3)
    args = ap.parse_args()
    builds = parse_builds(args.build)
    results = {b.name: [] for b in builds}
    for _ in range(args.rounds):
        for b in builds:
            r = run(b, args.seconds)
            results[b.name].append(r)
            print(r, flush=True)
    print("\nbuild                 idle CPU ms/s (median)   own MB at end (median)")
    for name, rs in results.items():
        print(f"{name:20s}  {statistics.median(r['cpu_ms_per_s'] for r in rs):10.3f}   "
              f"{statistics.median(r['own_mb'][1] or 0 for r in rs):10.1f}")


if __name__ == "__main__":
    main()
