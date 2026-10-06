#!/usr/bin/env python3
"""Benchmarks Pi builds against each other.

Scenarios (each run is a fresh process):
  startup      `pi --version`
  headless     `pi -p "<prompt>"`: one prompt, 5 model turns (4 `read` calls + an answer)
  interactive  the real TUI on a pseudo-terminal: time to interactive, 5 prompts (25 model turns), /quit

Runs are interleaved round-robin across builds, so background load hits all of them alike; pin them to the same cores with
--cpus. CPU time and peak memory come from wait4() and cover all threads.

Example:
  bench/benchmark.py --runs 15 --cpus 8-15 \\
      --build pi-bolt=./out/pi/pi \\
      --build bun="./out/pi-stable/pi" \\
      --build node="node ./pi/packages/coding-agent/dist/cli.js"
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import (
    DONE, MACOS, MODEL_ARGS, PROMPT, WINDOWS, Tty, done, fake_model, maxrss_mb, median, parse_builds, peak_footprint_mb, pi_env,
    pi_home, pinned, workdir,
)

if WINDOWS:
    import winproc


def run_plain(build, args, env, cwd, cpus):
    if WINDOWS:
        out, res = winproc.run([*build.argv, *args], env, cwd)
        return out, {"ok": res["exit"] == 0, "wall_ms": res["wall_ms"], "cpu_ms": res["cpu_ms"], "job_cpu_ms": res["job_cpu_ms"],
                     "peak_mb": res["peak_mb"], "peak_private_mb": res["peak_private_mb"]}
    t0 = time.perf_counter()
    p = subprocess.Popen(pinned([*build.argv, *args], cpus), env=env, cwd=cwd, stdin=subprocess.DEVNULL,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.read()
    peak_fp = peak_footprint_mb(p.pid)
    _, status, ru = os.wait4(p.pid, 0)
    r = {"ok": os.waitstatus_to_exitcode(status) == 0, "wall_ms": (time.perf_counter() - t0) * 1e3,
         "cpu_ms": (ru.ru_utime + ru.ru_stime) * 1e3, "peak_mb": maxrss_mb(ru)}
    if peak_fp is not None:
        r["peak_fp_mb"] = peak_fp
    return out, r


def startup(build, env, cwd, cpus):
    out, r = run_plain(build, ["--version"], env, cwd, cpus)
    r["ok"] = r["ok"] and bool(out.strip())
    return r


def headless(build, env, cwd, cpus):
    out, r = run_plain(build, ["-p", "--no-session", *MODEL_ARGS, PROMPT], env, cwd, cpus)
    r["ok"] = r["ok"] and DONE.encode() in out
    return r


def interactive(build, env, cwd, cpus, prompts=5):
    t0 = time.perf_counter()
    tty = Tty(pinned([*build.argv, "--no-session", *MODEL_ARGS], cpus), env, cwd)
    deadline = t0 + 120
    r = {"ok": False}
    if not tty.wait_for("fake-model", 0, deadline):
        tty.quit(1)
        return r
    r["tti_ms"] = (time.perf_counter() - t0) * 1e3
    tty.settle(0.05, deadline)
    turns = 0.0
    for n in range(1, prompts + 1):
        start = len(tty.buf)
        ts = time.perf_counter()
        tty.send(PROMPT.encode() + b"\r")
        if not tty.wait_for(done(n), start, deadline):
            break
        turns += (time.perf_counter() - ts) * 1e3
        tty.settle(0.03, deadline)
    else:
        r["turns_ms"] = turns
    status, ru = tty.quit()
    r["ok"] = "turns_ms" in r and status == 0
    r["wall_ms"] = (time.perf_counter() - t0) * 1e3
    r["cpu_ms"] = (ru.ru_utime + ru.ru_stime) * 1e3
    r["peak_mb"] = maxrss_mb(ru)
    if WINDOWS:
        r["peak_private_mb"] = ru.result["peak_private_mb"]
        r["job_cpu_ms"] = ru.result["job_cpu_ms"]
    if getattr(tty, "peak_footprint_mb", None) is not None:
        r["peak_fp_mb"] = tty.peak_footprint_mb
    return r


SCENARIOS = {"startup": startup, "headless": headless, "interactive": interactive}
COLUMNS = {
    "startup": ["wall_ms", "cpu_ms", "peak_mb"],
    "headless": ["wall_ms", "cpu_ms", "peak_mb"],
    "interactive": ["tti_ms", "turns_ms", "wall_ms", "cpu_ms", "peak_mb"],
}
if MACOS:
    # (peak_mb, ru_maxrss, counts clean file pages and freed pages the kernel may take back; the footprint does not.)
    for columns in COLUMNS.values():
        columns.append("peak_fp_mb")
if WINDOWS:
    # peak_mb is the peak working set; peak_private_mb the peak commit charge (private bytes). cpu_ms is the main process's,
    # by cycles; job_cpu_ms adds what it started, in clock ticks.
    for columns in COLUMNS.values():
        columns += ["peak_private_mb", "job_cpu_ms"]


def summarize(rows, baseline=None):
    for scenario, columns in COLUMNS.items():
        rs = [r for r in rows if r["scenario"] == scenario and r["ok"]]
        if not rs:
            continue
        names = list(dict.fromkeys(r["build"] for r in rs))
        stats = {n: {c: median([r.get(c) for r in rs if r["build"] == n]) for c in columns} for n in names}
        base = stats.get(baseline) if baseline else None
        print(f"\n{scenario}")
        print("  " + "build".ljust(22) + "".join(c.rjust(16) for c in columns))
        for n in names:
            cells = []
            for c in columns:
                v = stats[n][c]
                cell = "-" if v is None else f"{v:.0f}"
                if base and base[c] and v is not None and n != baseline:
                    cell += f" ({(v / base[c] - 1) * 100:+.0f}%)"
                cells.append(cell.rjust(16))
            print("  " + n.ljust(22) + "".join(cells))
    failed = [r for r in rows if not r["ok"]]
    if failed:
        print(f"\n{len(failed)} failed runs: " + ", ".join(sorted({f'{r["scenario"]}/{r["build"]}' for r in failed})))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command that starts Pi (repeatable)")
    ap.add_argument("--runs", type=int, default=10)
    ap.add_argument("--warmup", type=int, default=2)
    ap.add_argument("--scenarios", default="startup,headless,interactive")
    ap.add_argument("--cpus", help="pin every run to these cores (taskset list, e.g. 8-15)")
    ap.add_argument("--baseline", help="build to compare the others with (default: the last --build)")
    ap.add_argument("--out", help="append raw results to this JSONL file")
    a = ap.parse_args()
    builds = parse_builds(a.build)
    rows = []
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env = pi_env(home)
        for scenario in a.scenarios.split(","):
            fn = SCENARIOS[scenario]
            for i in range(a.warmup + a.runs):
                for build in builds:
                    r = fn(build, env, cwd, a.cpus)
                    if i < a.warmup:
                        continue
                    r.update(build=build.name, scenario=scenario, run=i - a.warmup)
                    rows.append(r)
                    if a.out:
                        with open(a.out, "a") as f:
                            f.write(json.dumps(r) + "\n")
                print(f"{scenario}: round {i + 1}/{a.warmup + a.runs}", file=sys.stderr, flush=True)
    summarize(rows, a.baseline or builds[-1].name)


if __name__ == "__main__":
    main()
