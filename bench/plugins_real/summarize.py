"""The real-extension measurements as tables (Markdown): bench/plugins_real/summarize.py RESULTS_DIR
  actions.jsonl (measure.py actions) and tui.jsonl (measure.py tui), medians over the rounds."""
import json
import statistics
import sys
from pathlib import Path

results = Path(sys.argv[1])
BUILDS = ["pi-bolt", "pi-bolt-jit", "bun"]
NAMES = {"pi-bolt": "Pi-Bolt, JIT off", "pi-bolt-jit": "Pi-Bolt, JIT on", "bun": "Pi on stock Bun"}


def med(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def fmt(v, digits=0):
    return "-" if v is None else f"{v:,.{digits}f}"


tui = [json.loads(line) for line in (results / "tui.jsonl").read_text().splitlines() if line.strip()]
extensions = list(dict.fromkeys(r["extension"] for r in tui))
by = {}
for r in tui:
    if r.get("ok"):
        by.setdefault((r["extension"], r["build"]), []).append(r)


def stat(extension, build, key):
    return med([r.get(key) for r in by.get((extension, build), [])])


print("### Launch and use in the TUI (medians; + is over no extension, same build)\n")
print("| Extension | Build | Launch to interactive | CPU per prompt | Frame time p99 | Peak private bytes |")
print("|---|---|---:|---:|---:|---:|")
for extension in extensions:
    for build in BUILDS:
        tti, cpu, p99, priv = (stat(extension, build, k) for k in ("tti_ms", "cpu_per_prompt_ms", "frame_p99_ms", "peak_private_mb"))
        base = [stat("none", build, k) for k in ("tti_ms", "cpu_per_prompt_ms", "frame_p99_ms", "peak_private_mb")]
        def delta(v, b):
            return "" if extension == "none" or v is None or b is None else f" ({v - b:+,.0f})"
        print(f"| {extension} | {NAMES[build]} | {fmt(tti)} ms{delta(tti, base[0])} | {fmt(cpu)} ms{delta(cpu, base[1])} | "
              f"{fmt(p99, 1)} ms | {fmt(priv)} MB{delta(priv, base[3])} |")

actions = [json.loads(line) for line in (results / "actions.jsonl").read_text().splitlines() if line.strip()]
print("\n### One typical action (`pi -p`, medians; the action's own cost is the run with it less a plain prompt's)\n")
print("| Extension | Build | Run with the action | Plain prompt | The action's own time | Its own CPU |")
print("|---|---|---:|---:|---:|---:|")
for extension in dict.fromkeys(r["extension"] for r in actions):
    for build in BUILDS:
        def pick(kind, key):
            return med([r[key] for r in actions if r["extension"] == extension and r["build"] == build and r["kind"] == kind and r["ok"]])
        a, p = pick("action", "wall_ms"), pick("plain", "wall_ms")
        ac, pc = pick("action", "cpu_ms"), pick("plain", "cpu_ms")
        print(f"| {extension} | {NAMES[build]} | {fmt(a)} ms | {fmt(p)} ms | {fmt(a - p)} ms | {fmt(ac - pc)} ms |")
