#!/usr/bin/env python3
"""Symbolicates msample files and aggregates. Usage: nsym.py PI_BIN DIR [--within SUBSTR] [--top N] [--before SUBSTR]
--within: only samples whose stack has a frame matching SUBSTR (e.g. evaluate). --before: drop samples from the first that
has a frame matching SUBSTR on (e.g. the phase after module init)."""
import collections, glob, os, re, subprocess, sys, json
binp, d = sys.argv[1], sys.argv[2]
def opt(k, dflt=None):
    return sys.argv[sys.argv.index(k) + 1] if k in sys.argv else dflt
within, top, before = opt("--within"), int(opt("--top", "50")), opt("--before")
runs = []
pcs = set()
for f in sorted(glob.glob(os.path.join(d, "s*.txt"))):
    rows = []
    for line in open(f):
        parts = line.split()
        if len(parts) < 2: continue
        st = [int(x, 16) for x in parts[1:]]
        lr = st[1]
        st = [st[0]] + st[2:]  # drop the lr guess
        rows.append(st); pcs.update(st)
    runs.append(rows)
cache = os.path.join(d, "sym.json")
sym = json.load(open(cache)) if os.path.exists(cache) else {}
todo = sorted(p for p in pcs if str(p) not in sym and 0x100000000 <= p < 0x110000000)
if todo:
    out = subprocess.run(["atos", "-o", binp, "-l", "0x100000000"] + [hex(p - 1 if i else p) for i, p in enumerate(todo)], capture_output=True, text=True).stdout.split("\n")
    for p, s in zip(todo, out):
        s = re.sub(r" \(in [^)]*\)", "", s); s = re.sub(r" \([^()]*:\d+\)$", "", s); s = re.sub(r" \+ \d+$", "", s)
        s = re.sub(r"\(.*\)( const)?$", "", s) if "(" in s and not s.startswith("0x") else s
        sym[str(p)] = s[:140]
    json.dump(sym, open(cache, "w"))
def name(p):
    if 0x100000000 <= p < 0x110000000: return sym.get(str(p), hex(p))
    if 0x20000000000 <= p < 0x30000000000: return "[aot code/region]"
    if 0x180000000 <= p < 0x200000000: return "[system library]"
    return "[other]"
selfc = collections.Counter(); incl = collections.Counter(); n = 0
for rows in runs:
    for st in rows:
        names = [name(p) for p in st]
        if before and any(before in x for x in names): break
        if within and not any(w in x for w in within.split('|') for x in names): continue
        n += 1
        selfc[names[0]] += 1
        for x in set(names): incl[x] += 1
print(f"{len(runs)} runs, {n} samples ({n/max(1,len(runs)):.1f}/run)")
print("--- self"); [print(f"{v:6} {100*v/n:5.1f}% {k}") for k, v in selfc.most_common(top)]
print("--- inclusive"); [print(f"{v:6} {100*v/n:5.1f}% {k}") for k, v in incl.most_common(top)]
