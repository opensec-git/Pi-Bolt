#!/usr/bin/env python3
"""Prints the tables of README.md from the raw results that measure.sh wrote, one folder per OS.

Usage: summarize.py [--mac DIR] [--linux DIR] [--notes]
  (default: mac/ and linux/ next to this file, where present; --notes also prints the standard set in the shape of the
  release notes)

The builds are read from builds.txt in the order measure.sh measured them: the first is the baseline (1.2.1), the second the
previous release (1.3.0), the third the candidate (1.3.1). Each row gives the three medians, "n" (the number of runs per
build) and the candidate's change from the other two. A row is listed under "Worse beyond noise" when the candidate's median
is more than 3% higher (worse) than that of one of the other builds and a two-sided Mann-Whitney U test of the two builds'
runs gives p < 0.05. "Not ahead of the baseline" lists every row in which the candidate's median is not lower than the
baseline's, with p. Folders mac-followup/, linux-followup/, mac-sdk/ and linux-sdk/, when present, get a table of every build
against the first with the same test.
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
not_ahead = []
HEADS = {"mac": "macOS arm64", "linux": "Linux x64"}


def names(folder):
    found = list(dict.fromkeys(re.findall(r"^build (\S+):", (folder / "builds.txt").read_text(), re.M)))
    assert len(found) == 3, f"{folder}/builds.txt: three builds expected, found {found}"
    return found


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


def fmt_p(p):
    return "-" if p is None else ("<0.001" if p < 0.001 else f"{p:.3f}")


def compare(rows, builds, value, label, os_name, keep=None):
    """Medians of the three builds, the candidate's change from the other two with p, and the runs per build."""
    keep = keep or (lambda r: True)
    values = []
    for build in builds:
        v = [value(r) for r in rows if r["build"] == build and keep(r)]
        values.append([x for x in v if x is not None])
    if not all(values):
        return None
    meds = [median(v) for v in values]
    cand = values[2]
    changes = []
    for i in (0, 1):
        change = (meds[2] / meds[i] - 1) * 100 if meds[i] else 0.0
        p = mann_whitney_p(values[i], cand)
        changes.append((change, p))
        line = (f"{HEADS[os_name]}: {label}: {builds[i]} {meds[i]:.4g} -> {builds[2]} {meds[2]:.4g} ({change:+.1f}%), "
                f"n={len(values[i])}/{len(cand)}, p={fmt_p(p)}")
        if change > 3 and p is not None and p < 0.05:
            worse.append(line)
        if i == 0 and meds[2] >= meds[0]:
            not_ahead.append(line)
    return meds, changes, min(len(v) for v in values)


def change_cell(change, p):
    mark = "" if p is None or p >= 0.05 else "*"
    return f"{change:+.1f}%{mark}"


def main_table(host, folder):
    builds = names(folder)
    head = " | ".join(builds)
    print(f"| Measurement | n | {head} | {builds[2]} vs {builds[0]} | {builds[2]} vs {builds[1]} |")
    print("|---|---:|---:|---:|---:|---:|---:|")

    def row(label, load, value, digits=2, keep=None):
        result = compare(load(folder), builds, value, label, host, keep)
        if result is None:
            return
        meds, changes, n = result
        cells = [f"{m:.{digits}f}" for m in meds]
        cells[2] = f"**{cells[2]}**"
        print(f"| {label} | {n} | " + " | ".join(cells) + " | " + " | ".join(change_cell(*c) for c in changes) + " |")

    def modes(f):
        return jsonl(f, "modes.jsonl")

    row("Blank-home launch to visible typing, ms", lambda f: jsonl(f, "startup.jsonl"), lambda r: r["editor_ms"])
    for mode in ("headless", "rpc", "tui"):
        keep = (lambda m: lambda r: r["mode"] == m)(mode)
        row(f"{mode.upper()} single answer · elapsed time, ms", modes, lambda r: r["wall_ms"], keep=keep)
        row(f"{mode.upper()} single answer · client CPU, ms", modes, lambda r: r["cpu_ms"], keep=keep)
        if mode != "headless":
            row(f"{mode.upper()} single answer · instructions, M", modes, lambda r: r.get("instr_m"), keep=keep)
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
    row("Post-answer idle instructions, M/s", idle, lambda r: r.get("instr_m_per_s"), 3)
    row("Post-answer idle private memory, MiB", idle, lambda r: r["own_mb"][-1], 1)

    print(f"\n| Special operation | n | {head} | {builds[2]} vs {builds[0]} | {builds[2]} vs {builds[1]} |")
    print("|---|---:|---:|---:|---:|---:|---:|")
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
    grep = lambda f: [r for r in jsonl(f, "grep.jsonl") if r["ok"]]
    row("grep tool, 7 searches of a source tree in `pi -p`, elapsed ms", grep, lambda r: r["wall_ms"], 0)
    row("grep tool, 7 searches of a source tree in `pi -p`, client CPU ms", grep, lambda r: r["cpu_ms"], 0)
    row("grep tool, 7 searches of a source tree in `pi -p`, instructions, M", grep, lambda r: r.get("instr_m"), 0)


def standard_table(host, folder, notes):
    """The standard set: README shape (with n and the changes), or with notes=True the release notes' shape."""
    builds = names(folder)
    mac = host == "mac"
    counts = set()
    lines = []

    def fmt(rows, value, digits, unit, label, keep=lambda r: True):
        result = compare(rows, builds, value, label, host, lambda r: keep(r) and r.get("ok", True))
        if result is None:
            return "", 0, ""
        meds, changes, n = result
        cells = [f"{m:.{digits}f}" for m in meds]
        text = f"{cells[0]} / {cells[1]} / **{cells[2]}**{unit}"
        return text, n, " / ".join(change_cell(*c) for c in changes)

    def emit(title, n, cells):
        """cells: (text, changes) per column."""
        if notes:
            lines.append(f"| {title} | " + " | ".join(c[0] for c in cells) + " |")
        else:
            lines.append(f"| {title} | {n} | " + " | ".join(f"{c[0]} ({c[1]})" if c[1] else c[0] for c in cells) + " |")

    def bench_row(title, rows, scenario):
        keep = lambda r: r["scenario"] == scenario
        cpu, n, cpu_c = fmt(rows, lambda r: r.get("cpu_ms"), 0, " ms", f"{title}, CPU", keep)
        counts.add(n)
        if mac:
            instr, _, instr_c = fmt(rows, lambda r: r.get("instr_m"), 0, " M", f"{title}, instructions", keep)
            mem, _, mem_c = fmt(rows, lambda r: r.get("peak_fp_mb"), 0, " MB", f"{title}, peak footprint", keep)
            emit(title, n, [(instr, instr_c), (cpu, cpu_c), (mem, mem_c)])
        else:
            mem, _, mem_c = fmt(rows, lambda r: r.get("peak_own_mb"), 0, " MB", f"{title}, peak private memory", keep)
            emit(title, n, [(cpu, cpu_c), (mem, mem_c)])

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
    na = min(sum(r["build"] == b for r in answers) for b in builds)
    title = f"60,000-character answer, per frame ({na} runs)" if notes else "60,000-character answer, per frame"
    if mac:
        v, _, c = fmt(answers, lambda r: r.get("instr_m_per_frame"), 2, " M", "60,000-character answer, instructions per frame")
        emit(title, na, [(v, c), ("", ""), ("", "")])
    else:
        v, _, c = fmt(answers, lambda r: r["cpu_ms_per_frame"], 2, " ms", "60,000-character answer, CPU per frame")
        emit(title, na, [(v, c), ("", "")])
    resume = [dict(build=r["build"], **r["record"]) for r in jsonl(folder, "standard-resume.jsonl")]
    nr = min(sum(r["build"] == b for r in resume) for b in builds)
    title = "Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame"
    title += f" ({nr} runs)" if notes else ""
    settled_title = "Resuming, until drawing settles" + (f" ({nr} runs)" if notes else "")
    if mac:
        instr, _, ic = fmt(resume, lambda r: r.get("instr_at_first_m"), 0, " M", "resume, instructions to the first frame")
        mem, _, mc = fmt(resume, lambda r: r.get("own_mb"), 0, " MB", "resume, footprint settled")
        emit(title, nr, [(instr, ic), ("", ""), (mem, mc)])
        settled, _, sc = fmt(resume, lambda r: r.get("instr_settled_m"), 0, " M", "resume, instructions until settled")
        cpu, _, cc = fmt(resume, lambda r: r.get("cpu_settled_ms"), 0, " ms", "resume, CPU until settled")
        if not notes:
            emit(settled_title, nr, [(settled, sc), (cpu, cc), ("", "")])
    else:
        cpu, _, cc = fmt(resume, lambda r: r.get("cpu_at_first_ms"), 0, " ms", "resume, CPU to the first frame")
        mem, _, mc = fmt(resume, lambda r: r.get("own_mb"), 0, " MB", "resume, private memory settled")
        emit(title, nr, [(cpu, cc), (mem, mc)])
        cpu, _, sc = fmt(resume, lambda r: r.get("cpu_settled_ms"), 0, " ms", "resume, CPU until settled")
        if not notes:
            emit(settled_title, nr, [(cpu, sc), ("", "")])
    runs = "/".join(map(str, sorted(c for c in counts if c)))
    order = f"{builds[0]} / {builds[1]} / **{builds[2]}**"
    if mac:
        print(f"Measured on macOS on Apple silicon (an M5), {order}, medians of {runs} runs, builds interleaved:\n")
        print("| | Instructions | CPU | Peak memory |\n|---|---|---|---|" if notes else
              "| | n | Instructions | CPU | Peak memory |\n|---|---:|---|---|---|")
    else:
        print(f"Measured on Linux x86-64 (an AMD EPYC 9355P, shared with other work), {order}, medians of {runs} runs, "
              "builds interleaved:\n")
        print("| | CPU | Peak memory |\n|---|---|---|" if notes else "| | n | CPU | Peak memory |\n|---|---:|---|---|")
    print("\n".join(lines))


def followup(host, folder):
    """Follow-up runs: in each row, every build against the first build that has runs in it, with p and run counts."""
    modes, idles, tree = jsonl(folder, "modes.jsonl"), idle(folder), jsonl(folder, "special-tree.jsonl")
    cores, inter = jsonl(folder, "tui-cores.jsonl"), jsonl(folder, "standard-interactive.jsonl")
    found = re.findall(r"^build (\S+):", (folder / "builds.txt").read_text(), re.M)
    builds = list(dict.fromkeys(found + [r["build"] for r in modes + idles + tree + cores + inter]))
    print(f"| Measurement ({host}) | Build | n | Median | Change from the first build | p |\n|---|---|---:|---:|---:|---:|")

    def show(label, rows, value, digits):
        runs = {b: [v for v in (value(r) for r in rows if r["build"] == b) if v is not None] for b in builds}
        present = [b for b in builds if runs[b]]
        if not present:
            return
        base, a = present[0], runs[present[0]]
        print(f"| {label} | {base} | {len(a)} | {median(a):.{digits}f} | | |")
        for build in present[1:]:
            b = runs[build]
            p = mann_whitney_p(a, b)
            print(f"| | {build} | {len(b)} | {median(b):.{digits}f} | {(median(b) / median(a) - 1) * 100:+.1f}% | {fmt_p(p)} |")

    for mode in ("headless", "rpc", "tui"):
        rows = [r for r in modes if r["mode"] == mode]
        show(f"{mode.upper()} single answer, elapsed ms", rows, lambda r: r["wall_ms"], 2)
        show(f"{mode.upper()} single answer, client CPU ms", rows, lambda r: r["cpu_ms"], 2)
        show(f"{mode.upper()} single answer, instructions (M)", rows, lambda r: r.get("instr_m"), 2)
        show(f"{mode.upper()} single answer, private MiB", rows, lambda r: r.get("private_mib"), 1)
    for key, label, digits in (("user_ms", "user CPU ms", 2), ("system_ms", "system CPU ms", 2),
                               ("instructions_m", "instructions (M)", 2), ("cycles_m", "cycles (M)", 2)):
        show(f"TUI single answer by rusage, {label}", cores, lambda r, k=key: r.get(k), digits)
    show("Idle CPU, ms/s", idles, lambda r: r["cpu_ms_per_s"], 3)
    show("Idle instructions, M/s", idles, lambda r: r.get("instr_m_per_s"), 3)
    show("Idle wakeups, per s", idles, lambda r: r.get("wakeups_per_s"), 1)
    show("Idle private memory at the end, MiB", idles, lambda r: r["own_mb"][-1], 1)
    inter_ok = [r for r in inter if r.get("ok", True)]
    show("Interactive, 5 prompts, CPU ms", inter_ok, lambda r: r.get("cpu_ms"), 1)
    show("Interactive, 5 prompts, instructions (M)", inter_ok, lambda r: r.get("instr_m"), 0)
    for shape in ("linear", "branched"):
        rows = [r for r in tree if r["shape"] == shape]
        for key, label in (("wall_ms", "elapsed"), ("cpu_ms", "client CPU")):
            show(f"{shape} session tree search, {label} ms/keystroke", rows,
                 lambda r, k=key: median(s[k] for s in r["samples"] if s["action"] == "filter"), 2)


ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--mac", type=Path, default=here / "mac")
ap.add_argument("--linux", type=Path, default=here / "linux")
ap.add_argument("--notes", action="store_true", help="also print the standard set in the shape of the release notes")
args = ap.parse_args()
folders = {h: f for h, f in (("mac", args.mac), ("linux", args.linux)) if (f / "builds.txt").exists()}
for host, folder in folders.items():
    print(f"## Workloads, {HEADS[host]}\n")
    print("Changes marked * have p < 0.05 (two-sided Mann-Whitney U).\n")
    main_table(host, folder)
    print(f"\n## Standard set, {HEADS[host]}\n")
    standard_table(host, folder, notes=False)
if args.notes:
    for host, folder in folders.items():
        print(f"\n## Standard set for the release notes, {HEADS[host]}\n")
        standard_table(host, folder, notes=True)
print("\n## Worse beyond noise\n")
print("\n".join(f"- {w}" for w in dict.fromkeys(worse)) or "None.")
print("\n## Not ahead of the baseline\n")
print("\n".join(f"- {w}" for w in dict.fromkeys(not_ahead)) or "None.")
for name in ("mac-followup", "linux-followup", "mac-sdk", "linux-sdk"):
    folder = here / name
    if (folder / "builds.txt").exists():
        print(f"\n## {name}\n")
        followup(name.split("-")[0], folder)
