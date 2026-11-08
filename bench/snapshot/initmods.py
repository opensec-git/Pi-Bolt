#!/usr/bin/env python3
"""Module init by bundled module: samples before the first one inside Pi's `main`, attributed to the innermost frame that is in
a chunk of the bundle (url cli-*/main-*/...), mapped to the module whose `// path` marker precedes that line in the chunk.
Native time below a JS frame counts for that frame. Usage: initmods.py PROFDIR CHUNKDIR [--top N] [--self]"""
import bisect, collections, glob, json, os, re, sys

prof, chunks = sys.argv[1], sys.argv[2]
top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 60
marks = {}
for path in glob.glob(os.path.join(chunks, "**", "*.js"), recursive=True):
    lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
    ms = [(i, m.group(1)) for i, l in enumerate(lines) if (m := re.match(r"^// (\S+\.(?:[cm]?[jt]sx?|json|wasm|txt|md))\s*$", l))]
    marks[os.path.basename(path)] = ([i for i, _ in ms], [n for _, n in ms])
by_mod = collections.Counter(); by_chunk = collections.Counter(); by_fn = collections.Counter()
runs = 0; n_init = 0; unknown = collections.Counter()
for path in glob.glob(os.path.join(prof, "*.cpuprofile")):
    p = json.load(open(path)); nodes = {n["id"]: n for n in p["nodes"]}; parent = {}
    for n in p["nodes"]:
        for c in n.get("children", []):
            parent[c] = n["id"]
    def stack(sid):
        out = []; cur = sid
        while cur is not None:
            out.append(nodes[cur]["callFrame"]); cur = parent.get(cur)
        return out
    samples = p["samples"]; cut = None
    for i, sid in enumerate(samples):
        if any(f["functionName"] == "main" and os.path.basename(f["url"]).startswith("cli-") for f in stack(sid)):
            cut = i; break
    if cut is None:
        continue
    runs += 1
    for sid in samples[:cut]:
        st = stack(sid)
        if st[0]["functionName"] in ("(idle)", "(program)", "(root)", "(garbage collector)"):
            continue
        n_init += 1
        frame = next((f for f in st if os.path.basename(f["url"]) in marks), None)
        if frame is None:
            outer = next((os.path.basename(f["url"]) for f in reversed(st) if f["url"]), "(native)")
            unknown[outer] += 1
            continue
        c = os.path.basename(frame["url"]); starts, names = marks[c]
        k = bisect.bisect_right(starts, frame["lineNumber"]) - 1
        mod = names[k] if k >= 0 else "(chunk prologue)"
        by_mod[mod] += 1; by_chunk[c] += 1; by_fn[f"{frame['functionName'] or '(top)'} {mod}"] += 1
print(f"{runs} runs, {n_init} init samples ({n_init / max(1, runs):.1f}/run)")
print("not under a bundle frame:", sum(unknown.values()), unknown.most_common(8))
print("\nby chunk:")
for k, v in by_chunk.most_common(20):
    print(f"{v:6} {100 * v / n_init:5.1f}% {k}")
print("\nby module:")
for k, v in by_mod.most_common(top):
    print(f"{v:6} {100 * v / n_init:5.1f}% {k}")
print("\nby function:")
for k, v in by_fn.most_common(30):
    print(f"{v:6} {100 * v / n_init:5.1f}% {k}")
