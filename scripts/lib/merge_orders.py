#!/usr/bin/env python3
"""Merges order files (BUN_BYTECODE_ORDER_OUT) recorded by the same training sessions on differently built executables of one
Pi: in one file and split (scripts/build-pi.sh and scripts/build-pi.ps1, --splitting). A function's name is a hash of its text,
and the texts differ where the code reaches into another chunk, so some functions have one name in each. A build finds the
names it has and ignores the rest; so that each build finds its functions in the order its own recording has them, a name
only a later file has goes right after the name before it in that file that the merged order already has.
Usage: merge_orders.py FIRST [MORE...] > MERGED"""
import sys


def read(path):
    with open(path, encoding="ascii") as file:
        lines = file.read().splitlines()
    if not lines or lines[0] != "v2":
        sys.exit(f"{path}: not an order file of this format (v2)")
    kinds = {}
    for line in lines[1:]:
        kind, _, name = line.partition(" ")
        kinds.setdefault(kind, []).append(name)
    return kinds


def interleave(merged, more):
    have = set(merged)
    after = {}  # a name of `merged` -> the names `more` adds after it, in its order
    anchor = None
    for name in more:
        if name in have:
            anchor = name
        else:
            after.setdefault(anchor, []).append(name)
            have.add(name)
    out = after.get(None, [])
    for name in merged:
        out.append(name)
        out.extend(after.get(name, []))
    return out


files = [read(path) for path in sys.argv[1:]]
if not files:
    sys.exit(__doc__)
order = []
for kind in ("F", "K", "M", "N", "S"):
    names = list(dict.fromkeys(files[0].get(kind, [])))
    for more in files[1:]:
        names = interleave(names, more.get(kind, []))
    order.extend(f"{kind} {name}" for name in names)
sys.stdout.write("\n".join(["v2", *order]) + "\n")
