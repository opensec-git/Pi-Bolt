#!/usr/bin/env python3
"""Plugin cost: launch time and a plugin's hot loop, with the plugin compiled into the executable or loaded at run time.

The plugin is examples/plugins/extensions/word-count.ts. Its /words command counts the words of a file character by character
and reports how long the count took, measured inside Pi with performance.now(). Each build is given as name=command, plus how
the plugin reaches it:

  --compiled name=command   the executable has the plugin compiled in (scripts/build-pi.sh --plugins examples/plugins/plugins.ts)
  --runtime name=command    the plugin is copied to the Pi home's extensions/ folder and loaded at run time (jiti)
  --none name=command       no plugin: the launch baseline

Example:
  bench/plugin_bench.py --compiled pi-bolt=out/pi-bolt-plugins/pi --runtime pi-bolt=out/pi-bolt/pi \\
      --runtime bun=out/pi-stable/pi --none pi-bolt=out/pi-bolt/pi

On Windows it runs as it is (Pi in a ConPTY). --runtime and --none need only the usual builds; --compiled needs a build with the
plugin compiled in, which scripts\\build-pi.ps1 cannot make yet (scripts/build-pi.sh --plugins can, on Linux and macOS).
"""

import argparse
import json
import os
import re
import shutil
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import MODEL_ARGS, Tty, fake_model, parse_builds, pi_env, pi_home, pinned, workdir

PLUGIN = Path(__file__).resolve().parents[1] / "examples/plugins/extensions/word-count.ts"
LINE = "lorem ipsum dolor sit amet, consectetur adipiscing elit\n"
REPEAT = 300_000  # 16.8 million characters


def session(build, mode, commands, cpus):
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        # A different file per command: the TUI does not redraw a notification identical to the one before.
        for k in range(commands):
            (cwd / f"words{k}.txt").write_text(LINE * REPEAT + "x " * k)
        if mode == "runtime":
            (home / "extensions").mkdir()
            shutil.copy(PLUGIN, home / "extensions" / PLUGIN.name)
        t0 = time.perf_counter()
        tty = Tty(pinned([*build.argv, "--no-session", *MODEL_ARGS], cpus), pi_env(home), cwd)
        deadline = t0 + 60
        if not tty.wait_for("fake-model", 0, deadline):
            raise RuntimeError(f"{build.name}: never became interactive")
        launch = (time.perf_counter() - t0) * 1000
        # Whether the plugin is there is what /words says below (it fails if the command does not answer). Pi 1.1.0 no longer lists
        # its extensions at startup (ctrl+o shows them), so a build without the plugin is only checked not to list it.
        if mode == "none" and tty.wait_for("word-count", 0, time.perf_counter() + 0.5):
            raise RuntimeError(f"{build.name} ({mode}): the plugin is loaded")
        tty.settle(0.2, time.perf_counter() + 5)
        loops = []
        for k in range(commands):
            start = len(tty.buf)
            tty.send(f"/words words{k}.txt\r".encode())
            if not tty.wait_for(" ms)", start, time.perf_counter() + 120):
                raise RuntimeError(f"{build.name} ({mode}): /words did not answer")
            loops.append(float(re.findall(r"words \(([\d.]+) ms\)", tty.screen_text(start))[-1]))
            tty.settle(0.2, time.perf_counter() + 5)
        tty.quit()
        return launch, loops


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for mode in ("compiled", "runtime", "none"):
        ap.add_argument(f"--{mode}", action="append", default=[], help="name=command (repeatable)")
    ap.add_argument("--runs", type=int, default=5, help="sessions per build")
    ap.add_argument("--commands", type=int, default=5, help="/words runs per session")
    ap.add_argument("--cpus", help="pin Pi to these cores (taskset list)")
    ap.add_argument("--out", help="append the results to this JSONL file")
    a = ap.parse_args()
    builds = [(b, mode) for mode in ("compiled", "runtime", "none") for b in parse_builds(getattr(a, mode))]
    results = {(b.name, mode): [] for b, mode in builds}
    for run in range(a.runs):  # round-robin, so background load hits every build alike
        for build, mode in builds:
            launch, loops = session(build, mode, a.commands if mode != "none" else 0, a.cpus)
            results[(build.name, mode)].append((launch, loops))
            if a.out:
                with open(a.out, "a") as f:
                    f.write(json.dumps({"build": build.name, "plugin": mode, "run": run, "launch_ms": round(launch, 1),
                                        "loop_ms": loops}) + "\n")
    print(f"{'build':16s} {'plugin':10s} {'launch':>10s} {'first /words':>14s} {'steady /words':>15s}")
    for (name, mode), rows in results.items():
        launch = statistics.median(r[0] for r in rows)
        line = f"{name:16s} {mode:10s} {launch:8.1f}ms"
        if rows[0][1]:
            first = statistics.median(r[1][0] for r in rows)
            steady = statistics.median(statistics.median(r[1][1:]) for r in rows)
            line += f" {first:12.1f}ms {steady:13.1f}ms"
        print(line)


if __name__ == "__main__":
    main()
