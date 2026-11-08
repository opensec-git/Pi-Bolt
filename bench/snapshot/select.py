#!/usr/bin/env python3
"""Which bundled modules go in the snapshot, from audits (PiBoltSnapshot.cpp) and the bundle's sources (for the modules each
one starts). A module is in it if its own top-level code noted nothing (no environment read, no process or timer call, nothing
changed that it did not make, no variable of another module set) in every audit it ran in, and every module it starts is in it
too. Usage: select.py BUNDLE_DIR OUT.txt AUDIT.jsonl [AUDIT.jsonl ...]"""
import json, os, re, sys, collections
bundle, out, audits = sys.argv[1], sys.argv[2], sys.argv[3:]
notes = collections.defaultdict(list); selfi = collections.Counter(); seen = set()
for a in audits:
    for line in open(a):
        try:
            r = json.loads(line)
        except Exception:
            continue
        m = r.get("m")
        if not m or m.startswith("builtin:") or "self" not in r:
            continue
        seen.add(m); selfi[m] = max(selfi[m], r["self"])
        notes[m].extend(r["notes"])
# Static edges: in each module's text, the init_/require_ functions it calls.
mark = re.compile(r"^// (\S+)\s*$", re.M)
defn = re.compile(r"^var (init_\w+|require_\w+) = __(?:esm|commonJS)\(", re.M)
call = re.compile(r"\b(init_\w+|require_\w+)\(\)")
owner = {}; text = {}
for f in os.listdir(bundle):
    if not f.endswith(".js"):
        continue
    t = open(os.path.join(bundle, f), encoding="utf-8", errors="replace").read()
    ms = list(mark.finditer(t))
    for i, m in enumerate(ms):
        body = t[m.end(): ms[i + 1].start() if i + 1 < len(ms) else len(t)]
        name = m.group(1)
        text[name] = text.get(name, "") + body
        for d in defn.finditer(body):
            owner[d.group(1)] = name
deps = {m: set() for m in text}
for m, body in text.items():
    own = {d.group(1) for d in defn.finditer(body)}
    for c in call.finditer(body):
        fn = c.group(1)
        if fn not in own and fn in owner:
            deps[m].add(owner[fn])
def blocking(n):
    # Reading an environment variable that is not set is allowed: the snapshot of the module is used only while it is not.
    return not ((n.startswith("env:") or n.startswith("env?:")) and n.endswith("=undefined"))
for m in list(notes):
    notes[m] = [n for n in notes[m] if blocking(n)]
pure = {m for m in seen if not notes[m]}
changed = True
while changed:
    changed = False
    for m in list(pure):
        if any(d not in pure for d in deps.get(m, ())):
            pure.discard(m); changed = True
tot = sum(selfi[m] for m in seen); kept = sum(selfi[m] for m in pure)
print(f"{len(seen)} modules ran, {len(pure)} selected: {kept/1e6:.1f} of {tot/1e6:.1f} M instructions of their own top-level code")
blockers = collections.Counter()
for m in seen - pure:
    for n in notes[m][:1]:
        blockers[n.split("{")[0][:60]] += selfi[m]
print("what keeps the rest out (by their instructions):")
for k, v in blockers.most_common(15):
    print(f"  {v/1e6:6.2f} M  {k}")
open(out, "w").write("".join(m + "\n" for m in sorted(pure)))
