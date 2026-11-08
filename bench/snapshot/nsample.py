#!/usr/bin/env python3
"""Native samples of `pi -p` (headless, 5 turns): N runs of PI_BIN under msample. Usage: nsample.py BENCH PI_BIN OUTDIR N [interval_us] [mode]"""
import os, subprocess, sys
bench, pibin, out, n = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
iv = sys.argv[5] if len(sys.argv) > 5 else "100"
mode = sys.argv[6] if len(sys.argv) > 6 else "headless"
sys.path.insert(0, bench)
import harness
os.makedirs(out, exist_ok=True)
ms = os.path.join(os.path.dirname(os.path.abspath(__file__)), "msample")
with harness.fake_model() as port, harness.pi_home(port) as home, harness.workdir() as cwd:
    env = harness.pi_env(home)
    for i in range(n):
        args = ["--version"] if mode == "version" else ["-p", "--no-session", *harness.MODEL_ARGS, harness.PROMPT]
        p = subprocess.run([ms, os.path.join(out, f"s{i}.txt"), iv, pibin, *args], env=env, cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True)
        if p.returncode: print("run", i, "exit", p.returncode, p.stderr[-300:])
