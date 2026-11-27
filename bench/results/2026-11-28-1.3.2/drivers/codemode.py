#!/usr/bin/env python3
"""Codemode with many workers: `pi -p --tools codemode` with a fake model (fake_model_codemode.py) that makes N `codemode`
calls at once, each a small script with a checksum, with PI_CODEMODE_MAX_WORKERS set so that all N run at the same time.
Prints one JSON record per run: the exit status (a negative status is the signal that ended Pi), how many scripts completed
with the expected checksum, wall and CPU time of the whole `pi -p`, peak memory, and on macOS instructions. A run is ok when
Pi exits with 0 and all N scripts completed; the end of the output of a run that is not ok is kept in `tail`.

Usage: codemode.py --build name=command [--build ...] [--calls 4,20] [--rounds 6] [--max-workers 51]
  Builds go through every order, one order per round (rounds: a multiple of the number of orders).
"""
import argparse
import itertools
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness
from harness import MODEL_ARGS, PeakOwnSampler, fake_model, maxrss_mb, parse_builds, peak_footprint_mb, pi_env, pi_home, workdir

TIMEOUT_S = 180


def run(build, env, cwd, calls):
    t0 = time.perf_counter()
    p = subprocess.Popen([*build.argv, "-p", "--no-session", *MODEL_ARGS, "--tools", "codemode", f"CODEMODE {calls}"],
                         env=env, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    killer = threading.Timer(TIMEOUT_S, p.kill)
    killer.start()
    own = PeakOwnSampler(p.pid)
    out = p.stdout.read()
    peak_fp = peak_footprint_mb(p.pid)
    peak_own = own.stop()
    _, status, ru = os.wait4(p.pid, 0)
    timed_out = not killer.is_alive()
    killer.cancel()
    exit_status = os.waitstatus_to_exitcode(status)
    found = re.search(rb"Done: (\d+) of (\d+) scripts\.", out)
    completed = int(found[1]) if found else 0
    r = {"calls": calls, "exit": exit_status, "timed_out": timed_out, "completed": completed,
         "ok": exit_status == 0 and completed == calls, "wall_ms": (time.perf_counter() - t0) * 1e3,
         "cpu_ms": (ru.ru_utime + ru.ru_stime) * 1e3, "peak_mb": maxrss_mb(ru)}
    if peak_own is not None:
        r["peak_own_mb"] = peak_own
    if peak_fp is not None:
        r["peak_fp_mb"] = peak_fp
        r.update(harness.last_exit_counters)
    if not r["ok"]:
        r["tail"] = out[-400:].decode(errors="replace")
    return r


ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--build", action="append", required=True)
ap.add_argument("--calls", default="4,20")
ap.add_argument("--rounds", type=int, default=6)
ap.add_argument("--max-workers", type=int, default=51)
args = ap.parse_args()
builds = parse_builds(args.build)
orders = list(itertools.permutations(builds))
if args.rounds % len(orders):
    sys.exit(f"--rounds must be a multiple of {len(orders)}")
rng = random.Random(42)
with fake_model(script="fake_model_codemode.py") as port, pi_home(port) as home, workdir() as cwd:
    env = pi_env(home, {"PI_CODEMODE_MAX_WORKERS": str(args.max_workers)})
    for calls in (int(c) for c in args.calls.split(",")):
        for build in builds:  # warm-up: page cache; not recorded
            run(build, env, cwd, calls)
        for i in range(args.rounds):
            if i % len(orders) == 0:
                rng.shuffle(orders)
            for build in orders[i % len(orders)]:
                print(json.dumps(dict(build=build.name, round=i, **run(build, env, cwd, calls))), flush=True)
