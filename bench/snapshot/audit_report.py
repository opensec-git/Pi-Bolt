#!/usr/bin/env python3
"""Summary of an audit (PiBoltSnapshot.cpp): per module self instructions, purity by its own notes. Usage: audit_report.py A.jsonl [--top N]"""
import json, sys, collections
rows = []
for l in open(sys.argv[1]):
    try:
        rows.append(json.loads(l))
    except Exception:
        pass
top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 40
mods = [r for r in rows if r.get("m") is not None]
tot = sum(r["self"] for r in mods)
def impure(r):
    out = []
    for n in r["notes"]:
        k = n.split(":", 1)[0]
        if k.startswith("env") or k.startswith("process.") or k in ("setTimeout", "setInterval", "setImmediate", "queueMicrotask", "fetch"):
            out.append(n)
        elif k == "changed":
            out.append(n)
    return out
pure = [r for r in mods if not impure(r)]
print(f"modules {len(mods)} (builtins {sum(1 for r in mods if r['m'].startswith('builtin:'))}), self instructions {tot/1e6:.1f} M")
print(f"own-pure (no env/process/timer notes, nothing changed that it did not make): {len(pure)}, {sum(r['self'] for r in pure)/1e6:.1f} M")
reasons = collections.Counter()
for r in mods:
    for n in impure(r):
        reasons[n.split("=")[0] if n.startswith("changed") else n.split(":", 1)[0] + ":" + n.split(":", 1)[1].split("=")[0] if ":" in n else n] += 1
print("\nreasons:", reasons.most_common(30))
print("\ntop modules by self instructions:")
for r in sorted(mods, key=lambda r: -r["self"])[:top]:
    imp = impure(r)
    print(f"{r['self']/1e6:7.2f} M {r['cells']:6d} cells  {'PURE ' if not imp else 'impure'} {r['m'][-60:]:60s} {';'.join(imp)[:120]}")
