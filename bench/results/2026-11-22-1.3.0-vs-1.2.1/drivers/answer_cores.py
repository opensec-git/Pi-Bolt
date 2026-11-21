#!/usr/bin/env python3
"""macOS diagnostic for the TUI single answer of answer.py: per run, the CPU time, instructions and cycles of the answer, split
by performance cores and by QoS class (proc_pid_rusage, RUSAGE_INFO_V6), to tell more work from slower cores. With
--answers 2 the same process answers the prompt a second time, reported as answer 2."""
import argparse
import ctypes
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from answer import b, marker
import harness

FIELDS = {"user": 0, "system": 1, "qos_default": 18, "qos_maintenance": 19, "qos_background": 20, "qos_utility": 21,
          "qos_legacy": 22, "qos_user_initiated": 23, "qos_user_interactive": 24, "instructions": 29, "cycles": 30,
          "runnable": 34, "user_p": 36, "system_p": 37, "instructions_p": 38, "cycles_p": 39}
TIMES = {"user", "system", "runnable", "user_p", "system_p"} | {k for k in FIELDS if k.startswith("qos_")}


def sample(pid):
    harness._rusage_macos(pid)  # (loads libproc and the timebase)
    buf = (ctypes.c_uint64 * 64)()
    assert harness._libproc.proc_pid_rusage(pid, 6, buf) == 0
    values = list(buf)[2:]
    return {k: values[i] for k, i in FIELDS.items()}


def run(build, env, cwd, answers=1):
    prompt = f"SCENARIO md:5000 CWD {cwd.as_posix()}"
    tty = b.Tty([*build.argv, "--no-session", *b.MODEL_ARGS], env, cwd, cols=160, rows=48)
    try:
        deadline = time.perf_counter() + 25
        assert tty.wait_for("fake-model", 0, deadline)
        tty.settle(0.05, deadline)
        results = []
        for n in range(1, answers + 1):
            before, start, offset = sample(tty.pid), time.perf_counter(), len(tty.buf)
            tty.send(prompt.encode() + b"\r")
            assert tty.wait_for(marker.replace("answer 1.", f"answer {n}."), offset, deadline + 25 * n)
            wall, after = (time.perf_counter() - start) * 1000, sample(tty.pid)
            out = {"build": build.name, "answer": n, "wall_ms": round(wall, 2)}
            for k in FIELDS:
                d = after[k] - before[k]
                out[k + ("_ms" if k in TIMES else "_m")] = round(d * harness._ticks_ns / 1e6, 3) if k in TIMES else round(d / 1e6, 2)
            results.append(out)
            tty.settle(0.2, deadline + 25 * n)
    finally:
        tty.quit()
    return results


ap = argparse.ArgumentParser(description=__doc__)
ap.add_argument("--build", action="append", required=True)
ap.add_argument("--rounds", type=int, default=12)
ap.add_argument("--answers", type=int, default=1)
args = ap.parse_args()
builds = b.parse_builds(args.build)
with b.fake_model(script=str(Path(__file__).resolve().with_name("model.py"))) as port, b.pi_home(port) as home, b.workdir() as cwd:
    env = b.pi_env(home)
    for build in builds:  # (a warm-up run each, not reported)
        run(build, env, cwd, args.answers)
    for r in range(args.rounds):
        for build in builds if r % 2 == 0 else builds[::-1]:
            for result in run(build, env, cwd, args.answers):
                print(json.dumps(dict(round=r, **result)), flush=True)
