#!/usr/bin/env python3
"""The built-in grep tool on an ordinary source tree: `pi -p` with a fake model (fake_model_grep.py) that has Pi run seven
ordinary regular-expression searches over the working folder, one per turn, then answers. Prints one JSON record per run:
wall and CPU time of the whole `pi -p` (start, seven searches, eight model turns), peak memory, and on macOS instructions.
Every run's answer must report the same number of result lines as the first build's first run.

Usage: grep_tree.py --build name=command [--build ...] --tree DIR [--rounds 12]
  --tree  the folder searched, e.g. `git archive <commit> packages | tar -x -C DIR` (the run's working folder)
  Builds go through every order, one order per round (rounds: a multiple of the number of orders).
"""
import argparse
import itertools
import json
import os
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from benchmark import run_plain
from harness import MODEL_ARGS, fake_model, parse_builds, pi_env, pi_home

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--build", action="append", required=True)
ap.add_argument("--tree", required=True)
ap.add_argument("--rounds", type=int, default=12)
args = ap.parse_args()
builds = parse_builds(args.build)
tree = os.path.abspath(args.tree)
orders = list(itertools.permutations(builds))
if args.rounds % len(orders):
    sys.exit(f"--rounds must be a multiple of {len(orders)}")
reference = None
rng = random.Random(42)
with fake_model(script="fake_model_grep.py") as port, pi_home(port) as home:
    env = pi_env(home)

    def once(build):
        out, r = run_plain(build, ["-p", "--no-session", *MODEL_ARGS, "Search the tree"], env, tree, None)
        found = re.search(rb"Done: (\d+) searches, (\d+) lines\.", out)
        r["searches"], r["lines"] = (int(found[1]), int(found[2])) if found else (0, 0)
        return r

    for build in builds:  # warm-up: page cache for the executables and the tree; not recorded
        once(build)
    for i in range(args.rounds):
        if i % len(orders) == 0:
            rng.shuffle(orders)
        for build in orders[i % len(orders)]:
            r = once(build)
            reference = reference or r["lines"]
            r["ok"] = r["ok"] and r["searches"] == 7 and r["lines"] == reference
            print(json.dumps(dict(build=build.name, round=i, **r)), flush=True)
