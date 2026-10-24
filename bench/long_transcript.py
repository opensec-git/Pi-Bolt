#!/usr/bin/env python3
"""Frame cost on a long transcript: Pi continues (`-c`) a session of N entries (assistant turns with thinking, Markdown and a
bash call, their tool results, a user message every 20 turns: about 2.6 KB an entry, as in a session that ran for hours) and
streams a Markdown answer at the pace of a fast model. Reports the CPU and frames of the answer, and on macOS the instructions
retired per frame: what a frame costs must not grow with the length of the transcript. With --keys, a key is typed every
--key-interval seconds while the answer streams, and the time until the editor shows it is reported (echo latency).

Example: bench/long_transcript.py --build pi-bolt=./out/pi-bolt/pi --entries 0,500,1000,2292,4000 --mode fullscreen,regular
"""

import argparse
import json
import random
import re
import statistics
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import ANSI, MACOS, MODEL_ARGS, Tty, _rusage_macos, cpu_ms, fake_model, parse_builds, pi_env, pi_home, pinned, workdir

WORDS = ("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi "
         "omega the of and to in is that for it with as was on be at by this had not are but from or have").split()
BYTES_PER_ENTRY = 2620


def words(rng, n):
    return " ".join(rng.choice(WORDS) for _ in range(n))


def markdown(rng, chars):
    out = []
    while sum(len(x) for x in out) < chars:
        r = rng.random()
        if r < 0.15:
            out.append("## " + words(rng, 4).title())
        elif r < 0.3:
            out.append("\n".join(f"- **{words(rng, 2)}**: {words(rng, 10)} `{rng.choice(WORDS)}()`" for _ in range(4)))
        elif r < 0.4:
            out.append("```ts\n" + "\n".join(f"const {rng.choice(WORDS)} = {rng.choice(WORDS)}({rng.randint(0, 99)});" for _ in range(6)) + "\n```")
        else:
            out.append(words(rng, 60))
    return "\n\n".join(out)


def write_session(path: Path, cwd: str, entries: int):
    """A session of about `entries` entries for `cwd`, the same for the same size."""
    rng = random.Random(1)
    per_pair = BYTES_PER_ENTRY * 2
    think, text, tool = per_pair // 2, per_pair // 4, per_pair // 5
    ts = 1_700_000_000
    stamp = lambda: time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime(ts))  # noqa: E731
    lines = [{"type": "session", "version": 3, "id": str(uuid.UUID(int=entries)), "timestamp": stamp(), "cwd": cwd}]
    parent = None

    def add(entry):
        nonlocal parent, ts
        ts += 1
        entry.update(id=f"{len(lines):08x}", parentId=parent, timestamp=stamp())
        parent = entry["id"]
        lines.append(entry)

    add({"type": "model_change", "provider": "fake", "modelId": "fake-model"})
    turn = 0
    while len(lines) - 1 < entries:
        if turn % 20 == 0:
            add({"type": "message", "message": {"role": "user", "content": [{"type": "text", "text": "Next task: " + words(rng, 30)}], "timestamp": ts * 1000}})
        call = f"call_h{turn}"
        add({"type": "message", "message": {"role": "assistant", "content": [
            {"type": "thinking", "thinking": words(rng, think // 6), "thinkingSignature": "reasoning_content"},
            {"type": "text", "text": markdown(rng, text)},
            {"type": "toolCall", "id": call, "name": "bash", "arguments": {"command": f"echo history {turn}"}}],
            "api": "openai-completions", "provider": "fake", "model": "fake-model",
            "usage": {"input": 1000, "output": 500, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 1500,
                      "cost": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0, "total": 0}},
            "stopReason": "toolUse", "timestamp": ts * 1000}})
        add({"type": "message", "message": {"role": "toolResult", "toolCallId": call, "toolName": "bash",
                                             "content": [{"type": "text", "text": "\n".join(words(rng, 12) for _ in range(tool // 70))}],
                                             "isError": False, "timestamp": ts * 1000}})
        turn += 1
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(line) + "\n" for line in lines))


def instructions(pid):
    ri = _rusage_macos(pid) if MACOS else None
    return ri[29] if ri else None


def run(build, entries, mode, a):
    with fake_model(pace_ms=a.pace_ms, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
        # (A context window the history fits in: no compaction before the answer.)
        models = json.loads((home / "models.json").read_text())
        for provider in models["providers"].values():
            for model in provider["models"]:
                model["contextWindow"] = 100_000_000
        (home / "models.json").write_text(json.dumps(models))
        settings = json.loads((home / "settings.json").read_text())
        settings["tuiMode"] = mode
        (home / "settings.json").write_text(json.dumps(settings))
        args = [*build.argv, *MODEL_ARGS]
        real = str(Path(cwd).resolve())
        if entries:
            # (The folder Pi derives from its working directory, as getDefaultSessionDirPath() does; on Windows the temporary
            # folder may reach Pi in its short form, so both forms get the session.)
            for folder in {str(cwd), real}:
                safe = "--" + re.sub(r"[/\\:]", "-", re.sub(r"^[/\\]", "", folder)) + "--"
                write_session(home / "sessions" / safe / "2026-01-01T00-00-00-000Z_t.jsonl", real, entries)
            args.append("-c")
        else:
            args.append("--no-session")
        tty = Tty(pinned(args, a.cpus), pi_env(home), cwd, cols=a.cols, rows=a.rows)
        if not tty.wait_for("fake-model", 0, time.perf_counter() + 120):
            sys.exit(f"{build.name}: the TUI did not start")
        tty.settle(1.0, time.perf_counter() + 30)
        start, cpu, instr, began = len(tty.buf), cpu_ms(tty.pid), instructions(tty.pid), time.perf_counter()
        tty.send(f"SCENARIO md:{a.size} CWD {cwd}".encode() + b"\r")
        latencies = []
        typed = ""
        next_key = time.perf_counter() + 1.0
        needle = b"Done: md, answer"
        scan = start
        deadline = time.perf_counter() + 1800
        while time.perf_counter() < deadline:
            tty.pump(0.005)
            if needle in ANSI.sub(b"", tty.buf[scan:]):
                break
            scan = max(start, len(tty.buf) - 8192)
            if a.keys and time.perf_counter() >= next_key:
                typed += "x"
                sent_at, mark = time.perf_counter(), len(tty.buf)
                tty.send(b"x")
                shown = typed.encode()
                while time.perf_counter() < sent_at + 5:
                    tty.pump(0.002)
                    if shown in ANSI.sub(b"", tty.buf[mark:]):
                        break
                latencies.append(1000 * (time.perf_counter() - sent_at))
                next_key = sent_at + a.key_interval
        else:
            sys.exit(f"{build.name}: no answer")
        wall = time.perf_counter() - began
        used = (cpu_ms(tty.pid) - cpu) / 1000
        frames = tty.buf.count(b"\x1b[?2026h", start)
        written = len(tty.buf) - start
        after = instructions(tty.pid)
        ri = _rusage_macos(tty.pid) if MACOS else None
        tty.quit()
    row = {"build": build.name, "entries": entries, "mode": mode, "size": a.size, "wall_s": round(wall, 2), "cpu_s": round(used, 3),
           "frames": frames, "fps": round(frames / wall, 1), "kb_written": round(written / 1024)}
    if instr is not None and after is not None:
        row["minstr"] = round((after - instr) / 1e6)
        row["minstr_per_frame"] = round((after - instr) / 1e6 / max(frames, 1), 2)
    if ri:
        # (Physical footprint when the answer is done, and the largest it was, startup included.)
        row["own_mb_end"] = round(ri[7] / (1 << 20), 1)
    if getattr(tty, "peak_footprint_mb", None):
        row["peak_own_mb"] = round(tty.peak_footprint_mb, 1)
    if latencies:
        row["echo_ms_median"] = round(statistics.median(latencies), 1)
        row["echo_ms_p95"] = round(sorted(latencies)[min(len(latencies) - 1, int(len(latencies) * 0.95))], 1)
        row["echo_ms_max"] = round(max(latencies), 1)
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command (repeatable)")
    ap.add_argument("--entries", default="0,500,1000,2292,4000", help="transcript sizes, in session entries")
    ap.add_argument("--mode", default="fullscreen", help="tuiMode values to run: fullscreen, regular or both (comma-separated)")
    ap.add_argument("--size", type=int, default=6000, help="characters in the answer")
    ap.add_argument("--pace-ms", type=float, default=20, help="between chunks of 24 characters")
    ap.add_argument("--rounds", type=int, default=1, help="rounds, builds interleaved; medians are printed at the end")
    ap.add_argument("--keys", action="store_true", help="type while the answer streams and report the echo latency")
    ap.add_argument("--key-interval", type=float, default=0.5, help="seconds between keys typed (--keys)")
    ap.add_argument("--cols", type=int, default=200)
    ap.add_argument("--rows", type=int, default=50)
    ap.add_argument("--cpus", help="pin Pi to these CPUs (taskset list)")
    ap.add_argument("--json", help="also append each row to this JSONL file")
    a = ap.parse_args()
    builds = parse_builds(a.build)
    rows = []
    for r in range(a.rounds):
        for mode in a.mode.split(","):
            for entries in [int(e) for e in a.entries.split(",")]:
                for build in builds if r % 2 == 0 else list(reversed(builds)):
                    row = run(build, entries, mode, a)
                    rows.append(row)
                    print(json.dumps(row), flush=True)
                    if a.json:
                        with open(a.json, "a") as f:
                            f.write(json.dumps(row) + "\n")
    if a.rounds > 1:
        print("--- medians")
        keys = sorted({(row["mode"], row["entries"], row["build"]) for row in rows})
        for mode, entries, name in keys:
            group = [row for row in rows if (row["mode"], row["entries"], row["build"]) == (mode, entries, name)]
            out = {"build": name, "mode": mode, "entries": entries}
            for field in ("cpu_s", "fps", "minstr_per_frame", "own_mb_end", "peak_own_mb", "echo_ms_median", "echo_ms_p95", "echo_ms_max"):
                values = [row[field] for row in group if field in row]
                if values:
                    out[field] = round(statistics.median(values), 2)
            print(json.dumps(out), flush=True)


if __name__ == "__main__":
    main()
