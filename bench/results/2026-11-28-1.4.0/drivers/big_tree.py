#!/usr/bin/env python3
"""The built-in grep and find tools in a large tree: `pi -p` with a fake model (fake_model_bigtree.py) that has Pi run one search
over the working folder with the tool's default limit: grep for `function`, or find for `readme*`. Prints one JSON record per run:
the tool, wall and CPU time of the whole `pi -p` (start, the search, two model turns), peak memory, on macOS instructions, and the
number of lines of the tool's result. A run is ok when Pi exits with 0 and the answer came back.

Usage: big_tree.py --build name=command [--build ...] --tree DIR [--tools grep,find] [--find-pattern GLOB] [--rounds 12]
  --find-pattern  find's pattern instead of `readme*`
  --tree  the folder searched (the run's working folder), e.g. a checkout with its node_modules and without its .git and
          .gitignore files, so that both tools walk all of it
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
ap.add_argument("--tools", default="grep,find")
ap.add_argument("--find-pattern")
ap.add_argument("--rounds", type=int, default=12)
args = ap.parse_args()
builds = parse_builds(args.build)
tree = os.path.abspath(args.tree)
orders = list(itertools.permutations(builds))
if args.rounds % len(orders):
    sys.exit(f"--rounds must be a multiple of {len(orders)}")
rng = random.Random(42)
with fake_model(script="fake_model_bigtree.py") as port, pi_home(port) as home:
    env = pi_env(home)

    def once(build, tool):
        prompt = f"BIGTREE {tool}" + (f" {args.find_pattern}" if tool == "find" and args.find_pattern else "")
        out, r = run_plain(build, ["-p", "--no-session", *MODEL_ARGS, prompt], env, tree, None)
        found = re.search(rb"Done: (grep|find), (\d+) lines\.", out)
        r["tool"], r["lines"] = tool, int(found[2]) if found else 0
        if tool == "find" and args.find_pattern:
            r["pattern"] = args.find_pattern
        r["ok"] = r["ok"] and found is not None and r["lines"] > 1
        return r

    for tool in args.tools.split(","):
        for build in builds:  # warm-up: page cache for the executables and the tree; not recorded
            once(build, tool)
        for i in range(args.rounds):
            if i % len(orders) == 0:
                rng.shuffle(orders)
            for build in orders[i % len(orders)]:
                print(json.dumps(dict(build=build.name, round=i, **once(build, tool))), flush=True)
