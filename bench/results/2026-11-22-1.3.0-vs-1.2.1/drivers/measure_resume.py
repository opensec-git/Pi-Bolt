#!/usr/bin/env python3
"""Resume (-c) of an N-entry session: instructions (macOS) or CPU until the TUI shows the model name and settles; optional
CPU profile. Prints one JSON record per run.
usage: measure_resume.py PI ENTRIES MODE(fullscreen|regular)   env PROF_DIR for --cpu-prof
"""
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness
from harness import MODEL_ARGS, Tty, fake_model, pi_env, pi_home, workdir
import long_transcript

binary, entries, mode = sys.argv[1], int(sys.argv[2]), sys.argv[3]
with fake_model(script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
    models = json.loads((home / "models.json").read_text())
    for provider in models["providers"].values():
        for model in provider["models"]:
            model["contextWindow"] = 100_000_000
    (home / "models.json").write_text(json.dumps(models))
    settings = json.loads((home / "settings.json").read_text())
    settings["tuiMode"] = mode
    (home / "settings.json").write_text(json.dumps(settings))
    real = str(Path(cwd).resolve())
    for folder in {str(cwd), real}:
        safe = "--" + re.sub(r"[/\\:]", "-", re.sub(r"^[/\\]", "", folder)) + "--"
        long_transcript.write_session(home / "sessions" / safe / "2026-01-01T00-00-00-000Z_t.jsonl", real, entries)
    envx = {}
    if os.environ.get("PROF_DIR"):
        envx["BUN_OPTIONS"] = f"--cpu-prof --cpu-prof-interval={os.environ.get('PROF_US', '500')} --cpu-prof-dir={os.environ['PROF_DIR']}"
    began = time.perf_counter()
    tty = Tty([binary, *MODEL_ARGS, "-c"], pi_env(home, envx), cwd, cols=160, rows=48)
    if not tty.wait_for("fake-model", 0, time.perf_counter() + 120):
        sys.exit("the TUI did not start")
    first = time.perf_counter() - began
    record = dict(entries=entries, mode=mode, first_frame_ms=round(first * 1000, 1), cpu_at_first_ms=round(harness.cpu_ms(tty.pid), 1))
    if harness.MACOS:
        record["instr_at_first_m"] = round(harness._rusage_macos(tty.pid)[29] / 1e6, 1)
    tty.settle(1.0, time.perf_counter() + 30)
    record["cpu_settled_ms"] = round(harness.cpu_ms(tty.pid), 1)
    record["own_mb"] = harness.memory_mb(tty.pid).get("own")
    if harness.MACOS:
        ri = harness._rusage_macos(tty.pid)
        record["instr_settled_m"] = round(ri[29] / 1e6, 1)
        # (own_mb is the footprint once settled, the figure the 1.2.1 notes give; this is the lifetime peak.)
        record["peak_fp_mb"] = round(ri[28] / (1 << 20), 1)
    record["bytes"] = len(tty.buf)
    print(json.dumps(record), flush=True)
    tty.quit()
