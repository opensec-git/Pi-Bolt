#!/usr/bin/env python3
"""tmux_check.py for Windows: Pi in a ConPTY (the pseudo-console behind Windows Terminal, 160x48) instead of a tmux pane,
against a model that streams at a human pace (one event every 10 ms). There is no tmux on Windows; the keys go into the
console's input and what Pi draws is read back from the VT stream the console sends its terminal (winproc.ConPty).

It runs the same steps as tmux_check.py and writes the same fields, so report.py reads either:
  - time to interactive; keystroke-to-screen latency (median and p95 of 40 keys); a 20 KB bracketed paste
  - streamed prompts: wall time, CPU used while streaming (all threads), bytes and frames written to the terminal
  - resizing the console while a reply streams, Escape to abort one, then a normal prompt again
  - idle CPU at the prompt once the screen is still, memory after each stage, and a clean /quit
  - anything that looks like an error on screen
and, beyond tmux_check.py:
  - frame_ms_p99, frame_ms_max: the time between two screen updates while the replies stream (99th percentile, longest)
  - memory: "own" is the private bytes (commit charge), "private_ws" the private working set, "rss" the working set
  - peak: the process's peak working set, private working set and private bytes, and the rise of the system's commit charge

What differs from tmux: the bytes and frames are what the console sends on, not what Pi wrote. ConPTY keeps its own copy of the
screen and sends the changes, at most once a frame, so prompt_kb_written is smaller than with tmux and the frame times have the
console's frame interval as a floor; frames are counted by the synchronized-update marks (CSI ?2026h) Pi writes, which ConPTY
passes on. The console host (conhost) that does this work is not Pi's and is not counted in Pi's CPU.

Example: python bench\\conpty_check.py --prompts 4 --rounds 3 --build pi-bolt=out\\pi-bolt\\pi.exe --build bun=out\\pi-stable\\pi.exe
"""

import argparse
import json
import re
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import ANSI, MODEL_ARGS, PROMPT, Tty, cpu_ms, done, fake_model, median, memory_mb, parse_builds, pi_env, pi_home, workdir
from tmux_check import ERRORS

COLS, ROWS = 160, 48


def pump_for(tty, seconds):
    """Reads what the console sends for `seconds` (a console that is not read stops the program writing to it)."""
    end = time.perf_counter() + seconds
    while (left := end - time.perf_counter()) > 0:
        tty.pump(min(left, 0.05))


def wait_re(tty, pattern, since, timeout):
    """Until the text drawn after byte `since` matches `pattern`."""
    end = time.perf_counter() + timeout
    while time.perf_counter() < end:
        tty.pump(0.01)
        if re.search(pattern, tty.screen_text(since)):
            return True
    return False


def stream(tty, text, since, timeout, gaps):
    """Until `text` is drawn after byte `since`; appends the time between two screen updates, in ms, to gaps."""
    needle = text.encode()
    end = time.perf_counter() + timeout
    last = None
    scan = since
    while time.perf_counter() < end:
        if tty.pump(0.002):
            now = time.perf_counter()
            if last is not None:
                gaps.append((now - last) * 1000)
            last = now
            if needle in ANSI.sub(b"", tty.buf[scan:]):
                return True
            scan = max(since, len(tty.buf) - 8192)
    return False


def check(build, env, cwd, prompts):
    r = {"build": build.name, "terminal": "conpty"}
    t0 = time.perf_counter()
    tty = Tty([*build.argv, "--no-session", *MODEL_ARGS], env, cwd, cols=COLS, rows=ROWS)
    pid = tty.pid
    status = None
    try:
        if not tty.wait_for("fake-model", 0, time.perf_counter() + 30):
            r["error"] = "never became interactive"
            return r
        r["tti_ms"] = round((time.perf_counter() - t0) * 1000, 1)
        pump_for(tty, 0.3)
        r["memory_at_start"] = memory_mb(pid)

        latencies = []
        for i in range(40):
            ch = "abcdefghij"[i % 10]
            start = len(tty.buf)
            ts = time.perf_counter()
            tty.send(ch.encode())
            if not tty.wait_for(ch, start, time.perf_counter() + 5):
                r["error"] = f"keystroke {i} never appeared"
                break
            latencies.append((time.perf_counter() - ts) * 1000)
        if latencies:
            r["key_ms_median"] = round(statistics.median(latencies), 2)
            r["key_ms_p95"] = round(sorted(latencies)[int(len(latencies) * 0.95) - 1], 2)
        tty.send(b"\x15")  # Ctrl+U
        pump_for(tty, 0.2)

        # (As tmux paste-buffer -p sends it: bracketed, lines ending in CR.)
        big = "\r".join(f"line {i}: lorem ipsum dolor sit amet" for i in range(400))
        start = len(tty.buf)
        c0, ts = cpu_ms(pid), time.perf_counter()
        tty.send(b"\x1b[200~" + big.encode() + b"\x1b[201~")
        shown = wait_re(tty, r"[Pp]aste|line 399", start, 10)
        r["paste_ms"] = round((time.perf_counter() - ts) * 1000, 1) if shown else None
        pump_for(tty, 0.3)
        r["paste_cpu_ms"] = round(cpu_ms(pid) - c0, 1)
        tty.send(b"\x15")
        pump_for(tty, 0.1)
        tty.send(b"\x1b")
        pump_for(tty, 0.3)

        walls, cpus_used, written, frames, gaps = [], [], [], [], []
        for i in range(prompts):
            size0 = len(tty.buf)
            c0, ts = cpu_ms(pid), time.perf_counter()
            tty.send(PROMPT.encode() + b"\r")
            if not stream(tty, done(i + 1), size0, 120, gaps):
                r.setdefault("warnings", []).append(f"prompt {i}: no final answer")
            tty.settle(0.3, time.perf_counter() + 120)
            walls.append((time.perf_counter() - ts - 0.3) * 1000)
            cpus_used.append(cpu_ms(pid) - c0)
            data = tty.buf[size0:]
            written.append(len(data))
            frames.append(data.count(b"\x1b[?2026h"))
        r["prompt_wall_ms"] = round(median(walls), 1)
        r["prompt_cpu_ms"] = round(median(cpus_used), 1)
        r["prompt_kb_written"] = round(median(written) / 1024, 1)
        r["prompt_frames"] = median(frames)
        if gaps:
            ordered = sorted(gaps)
            r["frame_ms_p99"] = round(ordered[max(0, int(len(ordered) * 0.99) - 1)], 1)
            r["frame_ms_max"] = round(ordered[-1], 1)
        r["memory_after_prompts"] = memory_mb(pid)

        size0 = len(tty.buf)
        tty.send(PROMPT.encode() + b"\r")
        pump_for(tty, 0.8)
        for cols, rows in ((100, 30), (200, 60), (80, 24), (COLS, ROWS)):
            tty.resize(cols, rows)
            pump_for(tty, 0.25)
        r["resize_while_streaming"] = "ok" if tty.wait_for(done(prompts + 1), size0, time.perf_counter() + 120) else "answer missing"
        tty.settle(0.5, time.perf_counter() + 30)
        tty.send(PROMPT.encode() + b"\r")
        pump_for(tty, 1.0)
        start = len(tty.buf)
        tty.send(b"\x1b")
        aborted = wait_re(tty, r"[Aa]bort|[Ii]nterrupt|[Cc]ancel", start, 5)
        r["escape_aborts"] = "ok" if aborted else "no abort shown"
        tty.settle(0.5, time.perf_counter() + 30)
        size0 = len(tty.buf)
        tty.send(b"Say hi\r")
        # (The prompt that was aborted is the one before it.)
        r["prompt_after_abort"] = "ok" if tty.wait_for(done(prompts + 3), size0, time.perf_counter() + 120) else "no answer"

        tty.settle(1.0, time.perf_counter() + 30)
        size0, c0 = len(tty.buf), cpu_ms(pid)
        pump_for(tty, 5)
        r["idle_cpu_ms_per_s"] = round((cpu_ms(pid) - c0) / 5, 2)
        r["idle_bytes_written"] = len(tty.buf) - size0
        r["memory_at_end"] = memory_mb(pid)
        text = ANSI.sub(b"", tty.buf).decode("utf-8", "replace")
        r["errors_on_screen"] = sorted(set(ERRORS.findall(text)))
    finally:
        status, ru = tty.quit()
    r["quit"] = "ok" if status is not None else "still running"
    res = ru.result
    r["peak"] = {"rss": res["peak_mb"], "private_ws": res["peak_private_ws_mb"], "own": res["peak_private_mb"],
                 "system_commit": res["system_commit_peak_mb"]}
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command that starts Pi (repeatable)")
    ap.add_argument("--prompts", type=int, default=4)
    ap.add_argument("--rounds", type=int, default=1, help="repeat the whole check, interleaving builds")
    ap.add_argument("--pace-ms", type=float, default=10, help="model streaming pace")
    ap.add_argument("--cpus", help="ignored on Windows (no pinning to cores); accepted for tmux_check.py's command line")
    ap.add_argument("--out", help="append raw results to this JSONL file")
    a = ap.parse_args()
    if a.cpus:
        print("note: --cpus is ignored on Windows", file=sys.stderr)
    builds = parse_builds(a.build)
    results = []
    with fake_model(pace_ms=a.pace_ms) as port, pi_home(port) as home, workdir() as cwd:
        env = pi_env(home)
        for _ in range(a.rounds):
            for build in builds:
                r = check(build, env, cwd, a.prompts)
                results.append(r)
                print(json.dumps(r), file=sys.stderr, flush=True)
                if a.out:
                    with open(a.out, "a") as f:
                        f.write(json.dumps(r) + "\n")
    keys = ["tti_ms", "key_ms_median", "paste_ms", "prompt_wall_ms", "prompt_cpu_ms", "prompt_frames", "frame_ms_p99",
            "idle_cpu_ms_per_s"]
    print("build".ljust(18) + "".join(k.rjust(18) for k in keys) + "   private bytes start/end MB   private working set end MB   checks")
    for build in builds:
        rs = [r for r in results if r["build"] == build.name]
        vals = [median([r.get(k) for r in rs]) for k in keys]
        mem = [median([r.get(m, {}).get("own") for r in rs]) for m in ("memory_at_start", "memory_at_end")]
        ws = median([r.get("memory_at_end", {}).get("private_ws") for r in rs])
        checks = set()
        for r in rs:
            checks.update(v for v in (r.get("resize_while_streaming"), r.get("escape_aborts"), r.get("prompt_after_abort"), r.get("quit"), r.get("error")) if v)
            checks.update(r.get("errors_on_screen", []) + r.get("warnings", []))
        print(build.name.ljust(18) + "".join(("-" if v is None else f"{v:.1f}").rjust(18) for v in vals) + f"   {mem}   {ws}   {sorted(checks)}")


if __name__ == "__main__":
    main()
