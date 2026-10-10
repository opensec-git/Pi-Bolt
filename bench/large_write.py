#!/usr/bin/env python3
"""A large file written through a tool call: the model streams the call's arguments (the whole file) 16 characters at a time,
about what a model sends per token, and Pi shows the call as it arrives. Reports the wall and CPU time of the prompt (pi -p), for
files of each size.

Example: bench/large_write.py --build pi-bolt=./out/pi-bolt/pi --build bun=./out/pi-stable/pi --sizes 50,200
"""

import argparse
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import FIXTURES, MODEL_ARGS, WINDOWS, fake_model, parse_builds, pi_env, pi_home, pinned

if WINDOWS:
    import winproc


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command (repeatable)")
    ap.add_argument("--sizes", default="50,200", help="sizes of the file in KB")
    ap.add_argument("--cpus", help="pin Pi to these CPUs (taskset list)")
    ap.add_argument("--timeout", type=float, default=900)
    a = ap.parse_args()
    print(f"{'build':12} {'file':>7} {'wall s':>8} {'cpu s':>8}  result")
    with fake_model(script="fake_model_stress.py") as port, pi_home(port) as home:
        for build in parse_builds(a.build):
            for size in [int(s) for s in a.sizes.split(",")]:
                with tempfile.TemporaryDirectory(prefix="pibolt-write-") as cwd:
                    argv = [*build.argv, "-p", "--no-session", *MODEL_ARGS, f"SCENARIO write:{size} CWD {cwd}"]
                    env = pi_env(home, {"TERM": "dumb"})
                    began = time.perf_counter()
                    if WINDOWS:
                        # (In a Job object: what Pi starts is counted with it, as RUSAGE_CHILDREN counts it on Linux.)
                        out, measured = winproc.run(argv, env, cwd, timeout=a.timeout)
                        returncode, cpu = measured["exit"], measured["job_cpu_ms"] / 1e3
                    else:
                        p = subprocess.Popen(pinned(argv, a.cpus), cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                             stderr=subprocess.STDOUT)
                        try:
                            out, _ = p.communicate(timeout=a.timeout)
                        except subprocess.TimeoutExpired:
                            p.kill()
                            out, _ = p.communicate()
                        returncode, cpu = p.returncode, cpu_of_children()
                    wall = time.perf_counter() - began
                    written = Path(cwd, "big", "source.ts")
                    size_ok = written.exists() and written.stat().st_size == size * 1024
                    result = "ok" if returncode == 0 and size_ok and b"Done: write" in out else f"FAILED (exit {returncode})"
                    print(f"{build.name:12} {size:5} KB {wall:8.1f} {cpu:8.1f}  {result}", flush=True)


_last_children = [0.0]


def cpu_of_children():
    """CPU seconds the children waited for since the last call (user + system)."""
    import resource
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    total = usage.ru_utime + usage.ru_stime
    used = total - _last_children[0]
    _last_children[0] = total
    return used


if __name__ == "__main__":
    main()
