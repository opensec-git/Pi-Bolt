#!/usr/bin/env python3
"""Turns benchmark results into the charts and tables of the README and docs/BENCHMARKS.md.

Reads the JSONL files the tools append to (--out) from one results folder:
  benchmark.jsonl  bench/benchmark.py       startup, headless, interactive
  long.jsonl       bench/long_session.py    a long session
  tmux.jsonl       bench/tmux_check.py      Pi in a tmux pane, replies streaming at human pace
  conpty.jsonl     bench/conpty_check.py    the same on Windows, in a ConPTY (read when there is no tmux.jsonl)
  pauses.jsonl     bench/pauses.py --out    frame times and stalls; GC pauses (--gc): rows added where present
  plugins.jsonl    bench/plugin_bench.py    a plugin compiled in vs loaded at run time
  long_answer-*.txt, large_write-*.txt   bench/long_answer.py, bench/large_write.py (their tables, one file per run)
and writes, for each chart, a light and a dark SVG (for GitHub's <picture> theme switch) to --images, and the tables (Markdown) to
stdout. Figures are medians over runs. Results taken on Windows (benchmark.jsonl with private bytes) get the Windows memory rows:
peak working set, private working set, private bytes (commit) and the rise of the system's commit charge. Where benchmark.jsonl
has the process floor (benchmark.py --floor), the Time and CPU tables add its rows and each build's figures over it.
--label name=text names a build's column (e.g. --label "bun=Pi 1.0.3 on stock Bun 1.4.2").

Example: bench/report.py results/2026-10-02 --images docs/images --builds pi-bolt,bun,node
"""

import argparse
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path

LABELS = {"pi-bolt": "Pi-Bolt", "bun": "Bun 1.4.2", "node": "Node 22", "node24": "Node 24", "pi-bolt-jit": "Pi-Bolt (JIT on)"}
# Pi-Bolt is the accent; every other build (an earlier Pi-Bolt release too) is the same neutral, so the eye goes to Pi-Bolt and
# each bar's label names the rest. Checked with the dataviz palette validator against GitHub's page surfaces (#ffffff,
# #0d1117): >= 3:1 contrast in both themes, accent and neutral >= 15.4 apart in normal vision and >= 12 for color-blind readers.
ACCENT = {"light": "#2a78d6", "dark": "#3987e5"}
NEUTRAL = {"light": "#848d97", "dark": "#6e7681"}
# GitHub's own text and border tokens, on no background of their own: the images take the page's.
THEME = {
    "light": {"text": "#1f2328", "muted": "#59636e", "rule": "#d1d9e0"},
    "dark": {"text": "#f0f6fc", "muted": "#9198a1", "rule": "#3d444d"},
}
# What ratios compare Pi-Bolt with: Pi on Bun, the runtime Pi ships for.
REFERENCE = "bun"
# The Pi version the results are of, from environment.txt.
PI_VERSION = ["1.0.0"]
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
FLOOR = "floor"  # benchmark.py --floor's runs: the process floor


def load(path):
    return [json.loads(line) for line in open(path)] if path.exists() else []


def med(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def fmt(value, unit):
    if value is None:
        return "–"
    if unit == "ms" and value >= 1000:
        return f"{value:,.0f} ms"
    if unit == "ms" and -10 < value < 10:  # (below zero: a figure over the process floor)
        return f"{value:.1f} ms"
    if unit == "s":
        return f"{value:.1f} s"
    if unit == "%":
        return f"{value:.0f}%"
    return f"{value:,.0f} {unit}"


def color_of(build, i, theme):
    """Pi-Bolt (and its JIT-on build) in the accent, every other build in the neutral."""
    ours = (LABELS["pi-bolt"], LABELS["pi-bolt-jit"])
    return ACCENT[theme] if build in ("pi-bolt", "pi-bolt-jit") or build.startswith(ours) else NEUTRAL[theme]


def is_release(build):
    """An earlier Pi-Bolt release run alongside (pi-bolt-0.7.0)."""
    return build.startswith("pi-bolt-") and build[8:9].isdigit()


def text_width(text, size):
    """About how wide a line of the system sans is: 0.56 em a character (digits and lowercase), enough to place a note."""
    return 0.53 * size * len(text)


def ratio_text(mine, base, kind):
    """Pi-Bolt against the second build: "2.9x faster" for time, "2.8x less" for CPU and memory; None when within 5%."""
    if not mine or not base or kind == "none":
        return None
    better, worse = ("faster", "slower") if kind == "time" else ("less", "more")
    if base / mine >= 1.05:
        r = base / mine
        return f"{r:.0f}× {better}" if r >= 10 else f"{r:.1f}× {better}"
    if mine / base >= 1.05:
        return f"{mine / base:.2f}× {worse}"
    return None


def chart(title, subtitle, panels, builds, theme, path):
    """panels: [(name, unit, {build: value})]. Horizontal bars, one panel per metric, two panels per row."""
    t = THEME[theme]
    panels = [p if len(p) == 4 else (*p, "time" if p[1] == "ms" and "CPU" not in title and "CPU" not in p[0] else "amount") for p in panels]
    panels = [p for p in panels if any(v is not None for v in p[2].values())]
    width, pad = 880, 24
    label_size, value_size = 13.5, 13.5
    label_w = max(80, int(max(text_width(LABELS.get(b, b), label_size) for b in builds)) + 16)
    columns = 2 if label_w <= 170 else 1
    col_w = (width - pad * (columns + 1)) // columns
    row_h, bar_h = 30, 16
    panel_h = 42 + row_h * len(builds) + 18
    rows = (len(panels) + columns - 1) // columns
    head = 78
    height = head + rows * panel_h
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" '
           f'aria-label="{title}: {subtitle}">',
           f'<style>text{{font-family:{FONT}}}</style>',
           f'<text x="{pad}" y="32" font-size="21" font-weight="650" fill="{t["text"]}">{title}</text>',
           f'<text x="{pad}" y="56" font-size="13.5" fill="{t["muted"]}">{subtitle}</text>']
    for p, (name, unit, values, kind) in enumerate(panels):
        x0 = pad + (p % columns) * (col_w + pad)
        y0 = head + (p // columns) * panel_h
        out.append(f'<text x="{x0}" y="{y0 + 18}" font-size="15" font-weight="600" fill="{t["text"]}">{name}</text>')
        top = max(v for v in values.values() if v is not None)
        bar_x = x0 + label_w
        # Room after the longest bar for its value; Pi-Bolt's ratio sits after its own, shorter bar.
        bar_w = col_w - label_w - 84
        base = values.get(REFERENCE if REFERENCE in builds else builds[1]) if len(builds) > 1 else None  # what Pi-Bolt is compared with
        for i, b in enumerate(builds):
            v = values.get(b)
            y = y0 + 36 + i * row_h
            mid = y + bar_h / 2 + 4.5
            out.append(f'<text x="{bar_x - 10}" y="{mid:.1f}" font-size="{label_size}" text-anchor="end" fill="{t["muted"]}">{LABELS.get(b, b)}</text>')
            if v is None:
                continue
            w = max(4, bar_w * v / top)
            # Rounded at the data end, square at the baseline.
            out.append(f'<path d="M{bar_x},{y} h{w - 4:.1f} a4,4 0 0 1 4,4 v{bar_h - 8} a4,4 0 0 1 -4,4 h{-(w - 4):.1f} z" fill="{color_of(b, i, theme)}"/>')
            weight = "650" if i == 0 else "400"
            value = fmt(v, unit)
            out.append(f'<text x="{bar_x + w + 8:.1f}" y="{mid:.1f}" font-size="{value_size}" font-weight="{weight}" fill="{t["text"]}">{value}</text>')
            note = ratio_text(v, base, kind) if i == 0 else None
            if note:
                # (After the value, which is bold: wider than text_width says.)
                px = bar_x + w + 8 + text_width(value, value_size) * 1.12 + 12
                if px + text_width(note, 12.5) <= x0 + col_w:  # (only where it fits in its panel)
                    out.append(f'<text x="{px:.1f}" y="{mid:.1f}" font-size="12.5" fill="{t["muted"]}">{note}</text>')
        out.append(f'<line x1="{bar_x}" y1="{y0 + 30}" x2="{bar_x}" y2="{y0 + 36 + len(builds) * row_h - 8}" stroke="{t["rule"]}"/>')
    out.append("</svg>")
    path.write_text("\n".join(out) + "\n")


def hero(tiles, builds, theme, path, title=None, subtitle=None):
    """The README's headline image: a column per metric, Pi-Bolt's figure large, how it compares in plain words, and a bar for
    every build. tiles: [(title, unit, {build: value}, better)] where better is "faster" or "less". With a title, a heading.
    No backgrounds and no badges: hairlines between the columns, text in the page's ink, color only on the bars."""
    t = THEME[theme]
    width, pad, gap = 880, 24, 28
    tile_w = (width - 2 * pad - gap * (len(tiles) - 1)) / len(tiles)
    top = 66 if title else 0
    reference = REFERENCE if REFERENCE in builds else builds[1]
    earlier = next((b for b in builds if is_release(b)), None)
    bars_y = 132 if earlier else 114
    tile_h = bars_y + 30 * len(builds)
    height = top + tile_h + 10
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" '
           f'aria-label="{title or "Pi-Bolt compared with Bun and Node"}">',
           f'<style>text{{font-family:{FONT}}}</style>']
    if title:
        out.append(f'<text x="{pad}" y="32" font-size="21" font-weight="650" fill="{t["text"]}">{title}</text>')
        out.append(f'<text x="{pad}" y="54" font-size="13.5" fill="{t["muted"]}">{subtitle or ""}</text>')

    def compared(mine, other, better, name):
        if not mine or not other:
            return None
        ratio = other / mine
        if abs(ratio - 1) < 0.05:
            return f"same as {name}"
        word = better if ratio >= 1 else ("slower" if better == "faster" else "more")
        r = ratio if ratio >= 1 else 1 / ratio
        return f"{r:.0f}× {word} than {name}" if r >= 10 else f"{r:.1f}× {word} than {name}"

    for i, (name, unit, values, better) in enumerate(tiles):
        x = pad + i * (tile_w + gap)
        y = top + 4
        if i:
            out.append(f'<line x1="{x - gap / 2:.1f}" y1="{y + 6}" x2="{x - gap / 2:.1f}" y2="{y + tile_h - 6}" stroke="{t["rule"]}"/>')
        out.append(f'<text x="{x:.1f}" y="{y + 18}" font-size="13" font-weight="600" fill="{t["muted"]}">{name}</text>')
        mine = values.get(builds[0])
        shown = fmt(mine, unit)
        figure, _, unit_text = shown.rpartition(" ") if " " in shown else (shown, "", "")
        if unit_text:
            figure += f'<tspan font-size="16" font-weight="600" fill="{t["muted"]}" dx="5">{unit_text}</tspan>'
        out.append(f'<text x="{x:.1f}" y="{y + 58}" font-size="36" font-weight="700" fill="{t["text"]}">{figure}</text>')
        line = compared(mine, values.get(reference), better, LABELS.get(reference, reference).split()[0])
        if line:
            out.append(f'<text x="{x:.1f}" y="{y + 84}" font-size="13" font-weight="600" fill="{t["text"]}">{line}</text>')
        if earlier:
            line = compared(mine, values.get(earlier), better, LABELS.get(earlier, earlier).replace("Pi-Bolt ", ""))
            if line:
                out.append(f'<text x="{x:.1f}" y="{y + 102}" font-size="13" fill="{t["muted"]}">{line}</text>')
        most = max(v for v in values.values() if v)
        for j, b in enumerate(builds):
            v = values.get(b)
            yy = y + bars_y + j * 30
            weight = "650" if j == 0 else "400"
            ink = t["text"] if j == 0 else t["muted"]
            out.append(f'<text x="{x:.1f}" y="{yy}" font-size="12.5" font-weight="{weight}" fill="{ink}">{LABELS.get(b, b)}</text>')
            if not v:
                continue
            out.append(f'<text x="{x + tile_w:.1f}" y="{yy}" font-size="12.5" font-weight="{weight}" text-anchor="end" fill="{ink}">{fmt(v, unit)}</text>')
            bw = max(6, tile_w * v / most)
            out.append(f'<rect x="{x:.1f}" y="{yy + 7}" width="{bw:.1f}" height="6" rx="3" fill="{color_of(b, j, theme)}"/>')
    out.append("</svg>")
    path.write_text("\n".join(out) + "\n")


def read_tables(results, pattern):
    """The rows of the plain tables long_answer.py and large_write.py print, from every run's file."""
    rows = []
    for f in sorted(results.glob(pattern)):
        lines = [line.split() for line in f.read_text().splitlines() if line.strip()]
        rows += lines[1:]
    return rows


def long_hero(results, images, builds):
    """Long answers and large files: CPU of streaming an answer, the share of a core it keeps, and the time to write a file."""
    answers, writes = read_tables(results, "long_answer-*.txt"), read_tables(results, "large_write-*.txt")
    if not answers or not writes:
        return
    def mean(values):
        values = list(values)
        return statistics.mean(values) if values else None
    def answer_cpu(build, chars):
        return mean(float(r[3]) for r in answers if r[0] == build and r[1] == chars)
    def answer_share(build, chars):
        # CPU over wall-clock time, so the figure is that of the two runs together.
        rs = [r for r in answers if r[0] == build and r[1] == chars]
        return 100 * sum(float(r[3]) for r in rs) / sum(float(r[2]) for r in rs) if rs else None
    def write_wall(build, size):
        return mean(float(r[3]) for r in writes if r[0] == build and r[1] == size)
    def vals(fn, *args):
        return {x: fn(x, *args) for x in builds}
    tiles = [
        ("CPU, 20,000-char answer", "s", vals(answer_cpu, "20000"), "less"),
        ("CPU, 60,000-char answer", "s", vals(answer_cpu, "60000"), "less"),
        ("Share of a core, streaming", "%", vals(answer_share, "60000"), "less"),
        ("Writing a 200 KB file", "s", vals(write_wall, "200"), "faster"),
    ]
    if not all(any(tile[2].values()) for tile in tiles):
        return  # (runs of other sizes than these)
    for theme in ("light", "dark"):
        hero(tiles, builds, theme, images / f"bench-long-{theme}.svg", "Long answers and large files",
             "Answers streamed at 1,200 characters a second; a file written through a tool call. Lower is better.")


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join(["---"] + ["---:"] * (len(header) - 1)) + "|"]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results", type=Path)
    ap.add_argument("--images", type=Path, required=True)
    ap.add_argument("--builds", default="pi-bolt,bun,node", help="builds to show, in order (the first is compared with the second)")
    ap.add_argument("--label", action="append", default=[], help="name=text: what a build's column is called (repeatable)")
    a = ap.parse_args()
    builds = a.builds.split(",")
    for b in builds:
        if is_release(b):
            LABELS[b] = f"Pi-Bolt {b[8:]}"
    a.images.mkdir(parents=True, exist_ok=True)
    # The versions the results were taken with, where the run says (environment.txt): "bun: 1.4.2", "node: v26.10.0".
    env = a.results / "environment.txt"
    pi_version = PI_VERSION[0]
    if env.exists():
        for line in env.read_text(encoding="utf-8-sig").splitlines():
            key, _, value = line.partition(": ")
            # (A line may say more than the version: "bun: Pi 1.0.0 on stock Bun 1.4.2". The runtime's own version is the label.)
            versions = re.findall(r"(?:Bun |^v?)(\d+\.\d+\.\d+)", value.strip())
            if key == "pi-bolt":
                # "0.7.3, Pi 1.1.0 (Pi-Bolt 0.7.3, linux-x64, JIT off)" or "1.0.0 (Pi-Bolt 0.6.1, linux-x64, JIT off), from ..."
                bolt = re.search(r"Pi-Bolt (\d+\.\d+\.\d+)", value)
                if bolt:
                    LABELS["pi-bolt"] = f"Pi-Bolt {bolt.group(1)}"
                pi = re.search(r"(\d+\.\d+\.\d+) \(Pi-Bolt", value)
                if pi:
                    PI_VERSION[0] = pi.group(1)
                elif versions:
                    PI_VERSION[0] = versions[0]  # ("pi-bolt: 1.0.3": the Pi it is)
                pi_version = PI_VERSION[0]
            elif key == "bun" and versions:
                LABELS["bun"] = f"Bun {versions[-1]}"
            elif key in ("node", "node22", "node24") and versions:
                LABELS[key] = f"Node {versions[-1].split('.')[0]}"
    for spec in a.label:
        name, _, text = spec.partition("=")
        LABELS[name] = text

    long_hero(a.results, a.images, builds)
    if not (a.results / "benchmark.jsonl").exists():
        return

    bench = defaultdict(list)
    for r in load(a.results / "benchmark.jsonl"):
        if r.get("ok"):
            bench[(r["build"], r["scenario"])].append(r)
    def b(build, scenario, key):
        return med(r.get(key) for r in bench[(build, scenario)])

    long_rows = defaultdict(list)
    for r in load(a.results / "long.jsonl"):
        if "error" not in r:
            long_rows[r["build"]].append(r)
    last_prompt = max((r["prompt"] for rs in long_rows.values() for r in rs), default=None)
    def lg(build, key):
        return med(r.get(key) for r in long_rows[build] if r["prompt"] == last_prompt)
    # How large the conversation is at the end of the long session, for the labels ("4.2M-token session").
    tokens = med(r.get("tokens_m") for rs in long_rows.values() for r in rs if r["prompt"] == last_prompt)
    session = f"{tokens:.1f}M-token session" if tokens else "long session"

    # The streaming check: tmux_check.py's, or on Windows conpty_check.py's (the same fields, Pi in a ConPTY).
    tmux = defaultdict(list)
    terminal = "tmux"
    for r in load(a.results / "tmux.jsonl") or load(a.results / "conpty.jsonl"):
        tmux[r["build"]].append(r)
        if r.get("terminal") == "conpty":
            terminal = "ConPTY"
    def tm(build, key):
        return med(r[key] if not isinstance(r[key], dict) else None for r in tmux[build]) if key in (tmux[build][0] if tmux[build] else {}) else None
    def tm_own(build):
        return med(r["memory_at_end"]["own"] for r in tmux[build]) if tmux[build] else None
    def tm_mem(build, stage, key):
        return med(r.get(stage, {}).get(key) for r in tmux[build]) if tmux[build] else None

    # Windows: the figures have more than one kind of memory (winproc.py). "Own" there is the private bytes (commit charge).
    windows = any("peak_private_mb" in r for rs in bench.values() for r in rs)

    # Smoothness (pauses.py --out): frame times from a plain run, GC pauses from a --gc run.
    pauses = defaultdict(list)
    for r in load(a.results / "pauses.jsonl"):
        pauses[(r["build"], r["step"], bool(r.get("gc_logged")))].append(r)
    def step_name(step):
        kind, _, size = step.partition(":")
        return f"{int(size):,}-char answer" if kind == "md" else f"{size} KB file written" if kind == "write" else step
    def frame_steps(gc):
        return list(dict.fromkeys(step for (_, step, logged) in pauses if logged == gc))

    def vals(fn):
        return {x: fn(x) for x in builds}

    speed = [
        ("Launch to interactive (TUI)", "ms", vals(lambda x: b(x, "interactive", "tti_ms"))),
        ("pi --version", "ms", vals(lambda x: b(x, "startup", "wall_ms"))),
        ("pi -p: one prompt, 4 tool calls", "ms", vals(lambda x: b(x, "headless", "wall_ms"))),
        (f"Time per prompt, {session}", "ms", vals(lambda x: lg(x, "ms_per_prompt"))),
    ]
    if any(bench[(x, "interactive-default-theme")] for x in builds):
        speed.append(("Launch to interactive (TUI), default theme", "ms", vals(lambda x: b(x, "interactive-default-theme", "tti_ms"))))
    cpu = [
        ("Interactive session: 5 prompts", "ms", vals(lambda x: b(x, "interactive", "cpu_ms"))),
        ("pi -p: one prompt", "ms", vals(lambda x: b(x, "headless", "cpu_ms"))),
        ("pi --version", "ms", vals(lambda x: b(x, "startup", "cpu_ms"))),
        (f"Per prompt, {session}", "ms", vals(lambda x: lg(x, "cpu_ms_per_prompt"))),
    ]
    if not windows:
        memory = [
            ("Peak memory, interactive session", "MB", vals(lambda x: b(x, "interactive", "peak_mb"))),
            (f"Own memory, {terminal} session", "MB", vals(tm_own)),
            (f"Own memory, end of {session}", "MB", vals(lambda x: lg(x, "own_mb"))),
            (f"Streaming replies ({terminal}), CPU", "ms", vals(lambda x: tm(x, "prompt_cpu_ms")), "amount"),
        ]
        own = "Own memory = private dirty pages"
    else:
        # Working set (resident, shared pages too), private working set (resident and the process's own), private bytes (the
        # commit charge: what it has committed, resident or not), and the rise of the system's commit charge while it ran.
        memory = [
            ("Peak working set, interactive session", "MB", vals(lambda x: b(x, "interactive", "peak_mb"))),
            ("Peak private working set, interactive session", "MB", vals(lambda x: b(x, "interactive", "peak_private_ws_mb"))),
            ("Peak private bytes (commit), interactive session", "MB", vals(lambda x: b(x, "interactive", "peak_private_mb"))),
            ("System commit rise, interactive session", "MB", vals(lambda x: b(x, "interactive", "system_commit_peak_mb"))),
            (f"Private working set, {terminal} session", "MB", vals(lambda x: tm_mem(x, "memory_at_end", "private_ws"))),
            (f"Private bytes (commit), {terminal} session", "MB", vals(tm_own)),
            (f"Private working set, end of {session}", "MB", vals(lambda x: lg(x, "private_ws_mb"))),
            (f"Private bytes (commit), end of {session}", "MB", vals(lambda x: lg(x, "own_mb"))),
            (f"Streaming replies ({terminal}), CPU", "ms", vals(lambda x: tm(x, "prompt_cpu_ms")), "amount"),
        ]
        own = "Private bytes = commit charge"
    # Smoothness, where measured: the time between two screen updates while streaming (99th percentile), the longest stall,
    # and the garbage collector's pauses.
    if tm(builds[0], "frame_ms_p99") is not None:
        memory.append((f"Frame time p99, streaming replies ({terminal})", "ms", vals(lambda x: tm(x, "frame_ms_p99")), "amount"))
    for step in frame_steps(False):
        rows_of = lambda x, s=step: pauses[(x, s, False)]  # noqa: E731
        memory.append((f"Frame time p99, {step_name(step)}", "ms", vals(lambda x, f=rows_of: med(r["frame_ms_p99"] for r in f(x))), "amount"))
        if step.startswith("write:"):
            memory.append((f"Longest stall, {step_name(step)}", "ms", vals(lambda x, f=rows_of: med(r["longest_ms"] for r in f(x))), "amount"))
    gc_steps = frame_steps(True)
    for step in gc_steps:
        memory.append((f"GC pause p99, {step_name(step)}", "ms",
                       vals(lambda x, s=step: med(r["gc"]["p99_ms"] for r in pauses[(x, s, True)])), "amount"))
    if gc_steps:
        memory.append(("Longest GC pause", "ms", vals(lambda x: max((r["gc"]["max_ms"] for s in gc_steps for r in pauses[(x, s, True)]
                                                                    if r["gc"]["max_ms"] is not None), default=None)), "amount"))
    for name, title, subtitle, panels in [
        ("speed", "Time", f"Wall-clock time, lower is better. Ratios compare Pi-Bolt with Bun. Pi {pi_version}, medians.", speed),
        ("cpu", "CPU time", f"All threads, lower is better. Ratios compare Pi-Bolt with Bun. Pi {pi_version}, medians.", cpu),
        ("memory", "Memory and streaming", f"Lower is better. {own}. Ratios compare Pi-Bolt with Bun.", memory),
    ]:
        for theme in ("light", "dark"):
            chart(title, subtitle, panels, builds, theme, a.images / f"bench-{name}-{theme}.svg")

    tiles = [
        ("Ready to type", "ms", vals(lambda x: b(x, "interactive", "tti_ms")), "faster"),
        ("CPU per session", "ms", vals(lambda x: b(x, "interactive", "cpu_ms")), "less"),
        ("CPU while streaming", "ms", vals(lambda x: tm(x, "prompt_cpu_ms")), "less"),
        ("Memory, long session", "MB", vals(lambda x: lg(x, "own_mb")), "less"),
    ]
    for theme in ("light", "dark"):
        hero(tiles, builds, theme, a.images / f"bench-hero-{theme}.svg")

    plugins = defaultdict(list)
    for r in load(a.results / "plugins.jsonl"):
        plugins[(r["build"], r["plugin"])].append(r)
    if plugins:
        configs = [k for k in plugins if k[1] != "none"]
        names = {k: f"{LABELS.get(k[0], k[0])}, {'compiled in' if k[1] == 'compiled' else 'run time'}" for k in configs}
        panels = [
            ("Hot loop in the plugin (/words)", "ms", {names[k]: med(med(r["loop_ms"][1:]) for r in plugins[k]) for k in configs}, "none"),
            ("Launch to interactive", "ms", {names[k]: med(r["launch_ms"] for r in plugins[k]) for k in configs}, "none"),
        ]
        order = [names[k] for k in configs]
        for theme in ("light", "dark"):
            chart("Plugins", "A Pi extension compiled into the executable vs loaded at run time. Lower is better.", panels, order, theme,
                  a.images / f"bench-plugins-{theme}.svg")

    # The process floor (benchmark.py --floor: the build "floor", bench/floor/floor.c), where measured: rows for it, the same in
    # every column, and for each build what it takes over it. Tables only; the charts are left as they are.
    floor_speed, floor_cpu = [], []
    for scenario, key, name in [("interactive", "tti_ms", "launch to interactive"), ("startup", "wall_ms", "--version"),
                                ("headless", "wall_ms", "-p")]:
        if b(FLOOR, scenario, key) is not None:
            floor_speed.append((f"Process floor (minimal program): {name}", "ms", vals(lambda x, s=scenario, k=key: b(FLOOR, s, k))))
    for scenario, name in [("interactive", "interactive session"), ("headless", "-p"), ("startup", "--version")]:
        if b(FLOOR, scenario, "cpu_ms") is not None:
            floor_cpu.append((f"Process floor (minimal program): {name}", "ms", vals(lambda x, s=scenario: b(FLOOR, s, "cpu_ms"))))
    def over_floor(panels, scenarios, key=None):
        out = []
        for (name, unit, values, *_), scenario in zip(panels, scenarios):
            k = key or ("tti_ms" if scenario == "interactive" else "wall_ms")
            floor = b(FLOOR, scenario, k)
            if floor is not None:
                out.append((f"{name}, over floor", unit, {x: None if v is None else v - floor for x, v in values.items()}))
        return out
    if floor_speed:
        floor_speed += over_floor(speed[:3], ["interactive", "startup", "headless"])
    if floor_cpu:
        floor_cpu += over_floor(cpu[:3], ["interactive", "headless", "startup"], "cpu_ms")

    # Tables.
    def row(name, unit, values, *_):
        return [name] + [fmt(values.get(x), unit) for x in builds]
    header = ["", *[LABELS.get(x, x) for x in builds]]
    print("### Time\n\n" + table(header, [row(*p) for p in speed + floor_speed]))
    print("\n### CPU\n\n" + table(header, [row(*p) for p in cpu + floor_cpu]))
    if floor_speed or floor_cpu:
        print("\nProcess floor: a minimal native program (bench/floor/floor.c: prints a line; in the ConPTY, the marker the TUI is "
              "waited for) started the same way, in the same rounds. Over floor: a build's median less the floor's.")
    print("\n### Memory and streaming\n\n" + table(header, [row(*p) for p in memory]))
    if plugins:
        print("\n### Plugins\n\n" + table(["", "launch", "hot loop"], [[names[k], fmt(med(r["launch_ms"] for r in plugins[k]), "ms"),
              fmt(med(med(r["loop_ms"][1:]) for r in plugins[k]), "ms")] for k in configs]))
    counts = {s: len(bench[(builds[0], s)]) for s in ("startup", "headless", "interactive")}
    print(f"\nruns: {counts}, long sessions: {len({(r['build'], r.get('exit')) for rs in long_rows.values() for r in rs})}, "
          f"{terminal} rounds: {len(tmux.get(builds[0], []))}")


if __name__ == "__main__":
    main()
