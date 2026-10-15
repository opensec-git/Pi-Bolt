#!/usr/bin/env python3
"""smaps_top.py FILE: the mappings of an smaps dump by resident size, and totals by kind."""
import sys, collections
maps, cur = [], None
for line in open(sys.argv[1]):
    parts = line.split()
    if "-" in parts[0] and len(parts) >= 5 and ":" not in parts[0]:
        cur = {"range": parts[0], "perm": parts[1], "path": parts[5] if len(parts) > 5 else "[anon]", "kb": {}}
        maps.append(cur)
    elif cur is not None and parts[0].endswith(":") and len(parts) >= 2 and parts[1].isdigit():
        cur["kb"][parts[0][:-1]] = int(parts[1])
tot = collections.Counter()
for m in maps:
    for k in ("Rss", "Pss", "Anonymous", "Private_Dirty", "Shared_Clean", "Private_Clean"):
        tot[k] += m["kb"].get(k, 0)
print("totals MB:", {k: round(v / 1024, 1) for k, v in tot.items()})
by = collections.Counter()
for m in maps:
    by[m["path"].split("/")[-1] + " " + m["perm"]] += m["kb"].get("Rss", 0)
print("by file+perm (MB):")
for k, v in by.most_common(15):
    print(f"  {v/1024:7.1f}  {k}")
print("largest mappings:")
for m in sorted(maps, key=lambda m: -m["kb"].get("Rss", 0))[:15]:
    k = m["kb"]
    print(f"  rss {k.get('Rss',0)/1024:6.1f} anon {k.get('Anonymous',0)/1024:6.1f} size {k.get('Size',0)/1024:8.1f} {m['perm']} {m['range']} {m['path']} THP {k.get('AnonHugePages',0)/1024:.1f}")
