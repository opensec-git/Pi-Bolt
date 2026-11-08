#!/usr/bin/env python3
"""pi -p with and without the snapshot: exit, stdout equal? Usage: smoke.py PI [extra KEY=VAL]"""
import os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import harness
pi = sys.argv[1]; extra = dict(kv.split("=", 1) for kv in sys.argv[2:])
outs = []
with harness.fake_model() as port, harness.pi_home(port) as home, harness.workdir() as cwd:
    for e in ({"BUN_MODULE_SNAPSHOT": "0"}, {"BUN_MODULE_SNAPSHOT_VERBOSE": "1", **extra}):
        p = subprocess.run([pi, "-p", "--no-session", *harness.MODEL_ARGS, harness.PROMPT], env=harness.pi_env(home, e), cwd=cwd, capture_output=True, text=True, stdin=subprocess.DEVNULL)
        outs.append(p.stdout)
        print(e, "exit", p.returncode)
        err = p.stderr.strip().splitlines()
        print("  stderr:", len(err), "lines;", "\n  ".join(err[-12:]))
print("stdout equal:", outs[0] == outs[1], len(outs[0]))
