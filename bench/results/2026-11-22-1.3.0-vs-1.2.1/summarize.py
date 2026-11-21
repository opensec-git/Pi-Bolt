#!/usr/bin/env python3
"""Prints the tables of README.md from the raw results that measure.sh wrote, one folder per OS.

Usage: summarize.py [--mac DIR] [--linux DIR]   (default: mac/ and linux/ next to this file, where present)

Each cell is the median for old -> new and the change. "n" is the number of runs per build. A row is listed under "Worse
beyond noise" when the new build's median is more than 3% higher (worse) and a two-sided Mann-Whitney U test of the two
builds' runs gives p < 0.05. Folders mac-followup/ and linux-followup/, when present, get a table of every build against the
first with the same test.
"""
import argparse
import ast
import json
import math
import re
from pathlib import Path
from statistics import median

here = Path(__file__).resolve().parent
worse = []


def names(folder):
    found = re.findall(r"^build (\S+):", (folder / "builds.txt").read_text(), re.M)
    return found[0], found[1]


def jsonl(folder, name):
    path = folder / name
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def idle(folder):
    path = folder / "idle.txt"
    if not path.exists():
        return []
    return [ast.literal_eval(line) for line in path.read_text().splitlines() if line.startswith("{")]


def long_answer(folder):
    """Rows of long_answer.py's table: build, then the numeric columns."""
    path = folder / "standard-long-answer.txt"
    rows = []
    if path.exists():
        for line in path.read_text().splitlines():
            parts = line.split()
            if not parts or parts[0] == "build":
                continue
            cpu_s, frames = float(parts[4]), int(parts[7])
            row = {"build": parts[0], "cpu_ms_per_frame": cpu_s * 1000 / frames, "own_mb": float(parts[6])}
            if parts[9] != "-":
                row["instr_m_per_frame"] = float(parts[9])
            rows.append(row)
    return rows


def mann_whitney_p(a, b):
    """Two-sided p of the Mann-Whitney U test, normal approximation with tie correction."""
    n1, n2 = len(a), len(b)
    if n1 < 3 or n2 < 3:
        return None
    ranked = sorted([(v, 0) for v in a] + [(v, 1) for v in b])
    ranks, i, ties = [0.0] * len(ranked), 0, 0.0
    while i < len(ranked):
        j = i
        while j + 1 < len(ranked) and ranked[j + 1][0] == ranked[i][0]:
            j += 1
        for k in range(i, j + 1):
            ranks[k] = (i + j) / 2 + 1
        t = j - i + 1
        ties += t ** 3 - t
        i = j + 1
    r1 = sum(r for r, (_, g) in zip(ranks, ranked) if g == 0)
    u = r1 - n1 * (n1 + 1) / 2
    n = n1 + n2
    sigma = math.sqrt(n1 * n2 / 12 * ((n + 1) - ties / (n * (n - 1))))
    if sigma == 0:
        return 1.0
    z = (abs(u - n1 * n2 / 2) - 0.5) / sigma
    return math.erfc(max(z, 0) / math.sqrt(2))


def pair(rows, old, new, value, label, os_name, keep=None):
    """Medians of old and new, the change, and the number of runs per build."""
    keep = keep or (lambda r: True)
    a = [value(r) for r in rows if r["build"] == old and keep(r)]
    b = [value(r) for r in rows if r["build"] == new and keep(r)]
    a, b = [v for v in a if v is not None], [v for v in b if v is not None]
    if not a or not b:
        return None
    ma, mb = median(a), median(b)
    change = (mb / ma - 1) * 100 if ma else 0.0
    p = mann_whitney_p(a, b)
    if change > 3 and p is not None and p < 0.05:
        worse.append(f"{os_name}: {label}: {ma:.4g} -> {mb:.4g} ({change:+.1f}%), n={len(a)}/{len(b)}, p={p:.3f}")
    return ma, mb, change, min(len(a), len(b))


def cell(result, digits):
    if result is None:
        return "-"
    ma, mb, change, n = result
    return f"{ma:.{digits}f} → {mb:.{digits}f} ({change:+.1f}%)"


def main_table(folders):
    hosts = list(folders)
    heads = {"mac": "macOS arm64", "linux": "Linux x64"}
    print("| Measurement | n | " + " | ".join(heads[h] for h in hosts) + " |")
    print("|---|---:|" + "---:|" * len(hosts))

    def row(label, load, value, digits=2, keep=None):
        results = []
        for host in hosts:
            old, new = names(folders[host])
            results.append(pair(load(folders[host]), old, new, value, label, host, keep))
        counts = sorted({r[3] for r in results if r})
        print(f"| {label} | {'/'.join(map(str, counts))} | " + " | ".join(cell(r, digits) for r in results) + " |")

    def modes(f):
        return jsonl(f, "modes.jsonl")

    row("Blank-home launch to visible typing, ms", lambda f: jsonl(f, "startup.jsonl"), lambda r: r["editor_ms"])
    for mode in ("headless", "rpc", "tui"):
        keep = (lambda m: lambda r: r["mode"] == m)(mode)
        row(f"{mode.upper()} single answer · elapsed time, ms", modes, lambda r: r["wall_ms"], keep=keep)
        row(f"{mode.upper()} single answer · client CPU, ms", modes, lambda r: r["cpu_ms"], keep=keep)
        if mode != "headless":
            row(f"{mode.upper()} single answer · final private memory, MiB", modes, lambda r: r["private_mib"], 1, keep)
    session = lambda f: [r for r in jsonl(f, "session.jsonl") if r["requests"] == 505]
    row("101 interactive tool prompts · elapsed ms/prompt", session, lambda r: r["wall_ms"])
    row("101 interactive tool prompts · client CPU ms/prompt", session, lambda r: r["cpu_ms"])
    row("101 interactive tool prompts · final private MiB", session, lambda r: r["private_mib"])
    restored = lambda f: [r for r in jsonl(f, "restored.jsonl") if r["prior_messages"] == 6000 and r["prompts"] == 60]
    row("6,000 restored messages in RPC · elapsed ms/prompt", restored, lambda r: r["wall_ms_per_prompt"])
    row("6,000 restored messages in RPC · client CPU ms/prompt", restored, lambda r: r["cpu_ms_per_prompt"])
    row("6,000 restored messages in RPC · final private MiB", restored, lambda r: r["private_mib"])
    row("Post-answer idle CPU, ms/s", idle, lambda r: r["cpu_ms_per_s"], 3)
    row("Post-answer idle private memory, MiB", idle, lambda r: r["own_mb"][-1], 1)

    print("\n| Special operation | n | " + " | ".join(heads[h] for h in hosts) + " |")
    print("|---|---:|" + "---:|" * len(hosts))
    rpc = lambda f: jsonl(f, "special-rpc.jsonl")
    for command in ("get_entries", "get_session_stats"):
        row(f"{command} on a 12,000-entry session, CPU ms/query", rpc, lambda r: r["cpu_ms_per_request"], 4,
            (lambda c: lambda r: r["command"] == c)(command))
    tree = lambda f: jsonl(f, "special-tree.jsonl")
    for shape in ("linear", "branched"):
        row(f"{shape} session tree search, elapsed ms/keystroke", tree,
            lambda r: median(s["wall_ms"] for s in r["samples"] if s["action"] == "filter"), 2,
            (lambda s: lambda r: r["shape"] == s)(shape))
        row(f"{shape} session tree search, client CPU ms/keystroke", tree,
            lambda r: median(s["cpu_ms"] for s in r["samples"] if s["action"] == "filter"), 2,
            (lambda s: lambda r: r["shape"] == s)(shape))
    preview = lambda f: jsonl(f, "special-preview.jsonl")
    for size in (8, 32):
        for field, label in (("private_mib", "private MiB at delivery"), ("cpu_ms", "client CPU ms"),
                             ("wall_ms", "elapsed ms to delivery")):
            row(f"{size} Mi characters of nested edit text, {label}", preview, lambda r, k=field: r[k], 1,
                (lambda s: lambda r: r["source_mib"] == s)(size))


def standard_table(host, folder):
    old, new = names(folder)
    mac = host == "mac"

    def med(rows, build, value, keep=lambda r: True):
        values = [value(r) for r in rows if r["build"] == build and keep(r) and r.get("ok", True)]
        values = [v for v in values if v is not None]
        return (median(values), len(values)) if values else (None, 0)

    def fmt(rows, value, digits, unit, label, keep=lambda r: True):
        a, na = med(rows, old, value, keep)
        b, nb = med(rows, new, value, keep)
        if a is None or b is None:
            return "", 0
        pair(rows, old, new, value, label, host, lambda r: keep(r) and r.get("ok", True))
        return f"{a:.{digits}f} / **{b:.{digits}f}**{unit}", min(na, nb)

    counts = set()
    lines = []

    def bench_row(title, rows, scenario):
        keep = lambda r: r["scenario"] == scenario
        cpu, n = fmt(rows, lambda r: r.get("cpu_ms"), 0, " ms", f"{title}, CPU", keep)
        counts.add(n)
        if mac:
            instr, _ = fmt(rows, lambda r: r.get("instr_m"), 0, " M", f"{title}, instructions", keep)
            mem, _ = fmt(rows, lambda r: r.get("peak_fp_mb"), 0, " MB", f"{title}, peak footprint", keep)
            lines.append(f"| {title} | {instr} | {cpu} | {mem} |")
        else:
            mem, _ = fmt(rows, lambda r: r.get("peak_own_mb"), 0, " MB", f"{title}, peak private memory", keep)
            lines.append(f"| {title} | {cpu} | {mem} |")

    blank = jsonl(folder, "standard-blank.jsonl")
    typical = jsonl(folder, "standard-realistic.jsonl")
    ext = jsonl(folder, "standard-extensions.jsonl")
    bench_row("`pi --version`", blank, "startup")
    bench_row("`pi -p` (5 model turns), blank home", blank, "headless")
    bench_row("`pi -p`, typical home", typical, "headless")
    if ext:
        bench_row("`pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo`", ext, "headless")
    bench_row("Interactive, 5 prompts", blank, "interactive")
    answers = long_answer(folder)
    na = min(sum(r["build"] == old for r in answers), sum(r["build"] == new for r in answers))
    if mac:
        v, _ = fmt(answers, lambda r: r.get("instr_m_per_frame"), 2, " M", "60,000-character answer, instructions per frame")
        lines.append(f"| 60,000-character answer, per frame ({na} runs) | {v} | | |")
    else:
        v, _ = fmt(answers, lambda r: r["cpu_ms_per_frame"], 2, " ms", "60,000-character answer, CPU per frame")
        lines.append(f"| 60,000-character answer, per frame ({na} runs) | {v} | |")
    resume = [dict(build=r["build"], **r["record"]) for r in jsonl(folder, "standard-resume.jsonl")]
    nr = min(sum(r["build"] == old for r in resume), sum(r["build"] == new for r in resume))
    title = f"Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame ({nr} runs)"
    if mac:
        instr, _ = fmt(resume, lambda r: r.get("instr_at_first_m"), 0, " M", "resume, instructions to the first frame")
        mem, _ = fmt(resume, lambda r: r.get("own_mb"), 0, " MB", "resume, footprint settled")
        lines.append(f"| {title} | {instr} | | {mem} |")
        settled, _ = fmt(resume, lambda r: r.get("instr_settled_m"), 0, " M", "resume, instructions until settled")
        cpu, _ = fmt(resume, lambda r: r.get("cpu_settled_ms"), 0, " ms", "resume, CPU until settled")
        lines.append(f"| Resuming, until everything is drawn and settled ({nr} runs) | {settled} | {cpu} | |")
    else:
        cpu, _ = fmt(resume, lambda r: r.get("cpu_at_first_ms"), 0, " ms", "resume, CPU to the first frame")
        mem, _ = fmt(resume, lambda r: r.get("own_mb"), 0, " MB", "resume, private memory settled")
        lines.append(f"| {title} | {cpu} | {mem} |")
        cpu, _ = fmt(resume, lambda r: r.get("cpu_settled_ms"), 0, " ms", "resume, CPU until settled")
        lines.append(f"| Resuming, until everything is drawn and settled ({nr} runs) | {cpu} | |")
    runs = "/".join(map(str, sorted(c for c in counts if c)))
    if mac:
        print(f"Measured on macOS on Apple silicon (an M5), {old} / **{new}**, medians of {runs} runs, builds interleaved:\n")
        print("| | Instructions | CPU | Peak memory |\n|---|---|---|---|")
    else:
        print(f"Measured on Linux x86-64 (an AMD EPYC 9355P, shared and loaded), {old} / **{new}**, medians of {runs} runs, "
              "builds interleaved:\n")
        print("| | CPU | Peak memory |\n|---|---|---|")
    print("\n".join(lines))


def followup(host, folder):
    """The follow-up runs of the rows that read worse: every build against the first, with p and run counts."""
    modes, idles = jsonl(folder, "modes.jsonl"), idle(folder)
    base = names(folder)[0]
    builds = [base] + [n for n in dict.fromkeys(r["build"] for r in modes + idles) if n != base]
    print(f"| Measurement ({host}) | Build | n | Median | Change from {base} | p |\n|---|---|---:|---:|---:|---:|")

    def show(label, rows, value, digits):
        a = [value(r) for r in rows if r["build"] == base and value(r) is not None]
        if not a:
            return
        print(f"| {label} | {base} | {len(a)} | {median(a):.{digits}f} | | |")
        for build in builds[1:]:
            b = [value(r) for r in rows if r["build"] == build and value(r) is not None]
            if b:
                p = mann_whitney_p(a, b)
                print(f"| | {build} | {len(b)} | {median(b):.{digits}f} | {(median(b) / median(a) - 1) * 100:+.1f}% | "
                      f"{p:.3f} |")

    for mode in ("rpc", "tui"):
        rows = [r for r in modes if r["mode"] == mode]
        show(f"{mode.upper()} single answer, elapsed ms", rows, lambda r: r["wall_ms"], 2)
        show(f"{mode.upper()} single answer, client CPU ms", rows, lambda r: r["cpu_ms"], 2)
        show(f"{mode.upper()} single answer, instructions (M)", rows, lambda r: r.get("instr_m"), 2)
        show(f"{mode.upper()} single answer, private MiB", rows, lambda r: r["private_mib"], 1)
    show("Idle CPU, ms/s", idles, lambda r: r["cpu_ms_per_s"], 3)
    show("Idle instructions, M/s", idles, lambda r: r.get("instr_m_per_s"), 3)
    show("Idle wakeups, per s", idles, lambda r: r.get("wakeups_per_s"), 1)
    show("Idle private memory at the end, MiB", idles, lambda r: r["own_mb"][-1], 1)


ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--mac", type=Path, default=here / "mac")
ap.add_argument("--linux", type=Path, default=here / "linux")
args = ap.parse_args()
folders = {h: f for h, f in (("mac", args.mac), ("linux", args.linux)) if (f / "builds.txt").exists()}
print("## Workloads of the candidate's comparison\n")
main_table(folders)
for host, folder in folders.items():
    print(f"\n## Standard set, {host}\n")
    standard_table(host, folder)
print("\n## Worse beyond noise\n")
print("\n".join(f"- {w}" for w in dict.fromkeys(worse)) or "None.")
for host in ("mac", "linux"):
    folder = here / f"{host}-followup"
    if (folder / "modes.jsonl").exists() or (folder / "idle.txt").exists():
        print(f"\n## Follow-up, {host}\n")
        followup(host, folder)
