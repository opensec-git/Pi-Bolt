#!/usr/bin/env python3
"""Runs a Pi build once with BUN_MODULE_SNAPSHOT_AUDIT. Usage: audit_run.py BENCH PI OUT.jsonl [headless|interactive|version] [KEY=VAL...]"""
import os, subprocess, sys, time
bench, pi, out = sys.argv[1:4]
mode = sys.argv[4] if len(sys.argv) > 4 else "headless"
extra = dict(kv.split("=", 1) for kv in sys.argv[5:])
sys.path.insert(0, bench)
import harness
with harness.fake_model() as port, harness.pi_home(port) as home, harness.workdir() as cwd:
    env = harness.pi_env(home, {"BUN_MODULE_SNAPSHOT_AUDIT": os.path.abspath(out), **extra})
    if mode == "interactive":
        tty = harness.Tty([pi, "--no-session", *harness.MODEL_ARGS], env, cwd)
        deadline = time.perf_counter() + 120
        ok = tty.wait_for("fake-model", 0, deadline)
        tty.settle(0.2, deadline)
        status, _ = tty.quit(20)
        try:
            os.kill(tty.pid, 9)
        except OSError:
            pass
        print("interactive", ok, status)
    else:
        args = ["--version"] if mode == "version" else ["-p", "--no-session", *harness.MODEL_ARGS, harness.PROMPT]
        try:
            p = subprocess.run([pi, *args], env=env, cwd=cwd, capture_output=True, text=True, timeout=240, stdin=subprocess.DEVNULL)
            print(p.returncode, p.stdout[-200:], p.stderr[-1500:])
        except subprocess.TimeoutExpired:
            print("timed out")
