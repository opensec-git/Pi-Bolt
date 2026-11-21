#!/usr/bin/env python3
"""Frame cost on a long transcript: Pi continues (`-c`) a session of N entries (assistant turns with thinking, Markdown and a
bash call, their tool results, a user message every 20 turns: about 2.6 KB an entry, as in a session that ran for hours) and
streams a Markdown answer at the pace of a fast model. Reports the CPU and frames of the answer, and on macOS the instructions
retired per frame: what a frame costs must not grow with the length of the transcript. With --keys, a key is typed every
--key-interval seconds while the answer streams, and the time until the editor shows it is reported (echo latency).

Example: bench/long_transcript.py --build pi-bolt=./out/pi-bolt/pi --entries 0,500,1000,2292,4000 --mode fullscreen,regular

On native macOS, `--balanced-seed SEED` compares three builds. Use a multiple of six rounds to give each build an equal
number of measurements in each position. JSON rows include counters through the answer marker and after a 300 ms drain.
Recent macOS versions also provide performance-core counters.
The comparison summary gives paired changes and approximate 95% intervals for the mean change.
"""

import argparse
import itertools
import json
import math
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


def process_snapshot(pid, flavor=None):
    # The first call initializes Mach's timebase in the harness.
    if flavor is None:
        flavor = 6
        ri = _rusage_macos(pid, flavor)
        if ri is None:
            flavor = 4
            ri = _rusage_macos(pid, flavor)
    else:
        ri = _rusage_macos(pid, flavor)
    if ri is None:
        raise RuntimeError(f"could not read process counters for PID {pid}")
    from harness import _ticks_ns

    ms_per_tick = _ticks_ns / 1e6
    counters = {
        "user_ms": ri[0] * ms_per_tick,
        "system_ms": ri[1] * ms_per_tick,
        "instructions": ri[29],
        "cycles": ri[30],
        "process_start": ri[8],
        "footprint_bytes": ri[7],
        "rusage_flavor": flavor,
    }
    if flavor == 6:
        # Indices count uint64_t fields after the UUID in struct rusage_info_v6 (sys/resource.h).
        counters.update(pcore_user_ms=ri[36] * ms_per_tick, pcore_system_ms=ri[37] * ms_per_tick,
                        pcore_instructions=ri[38], pcore_cycles=ri[39], energy_nj=ri[40], pcore_energy_nj=ri[41])
    return counters


def phase_counters(start, end):
    if start["process_start"] != end["process_start"]:
        raise RuntimeError("process identity changed between snapshots")
    fields = ("user_ms", "system_ms", "instructions", "cycles")
    if start["rusage_flavor"] == 6:
        fields += ("pcore_user_ms", "pcore_system_ms", "pcore_instructions", "pcore_cycles", "energy_nj", "pcore_energy_nj")
    delta = {key: end[key] - start[key] for key in fields}
    if any(value < 0 for value in delta.values()):
        raise RuntimeError("process counters decreased between snapshots")
    delta["cpu_ms"] = delta["user_ms"] + delta["system_ms"]
    if start["rusage_flavor"] == 6:
        delta["pcore_cpu_ms"] = delta["pcore_user_ms"] + delta["pcore_system_ms"]
    return delta


def run(build, entries, mode, a):
    balanced = getattr(a, "balanced_seed", None) is not None
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
            status, _ = tty.quit()
            sys.exit(f"{build.name}: the TUI did not start (exit {status})")
        settled = tty.settle(1.0, time.perf_counter() + 30)
        if getattr(a, "pre_prompt_wait", 0):
            # A quiet viewport can still have terminal-invisible transcript restoration in progress.
            end_wait = time.perf_counter() + a.pre_prompt_wait
            while (remaining := end_wait - time.perf_counter()) > 0:
                tty.pump(min(0.05, remaining))
        if tty.eof:
            status, _ = tty.quit()
            raise RuntimeError(f"{build.name}: terminal closed before submitting the prompt (exit {status})")
        if balanced:
            if not settled:
                raise RuntimeError(f"{build.name}: no quiet period before prompt")
            initial = process_snapshot(tty.pid)
        else:
            cpu, instr = cpu_ms(tty.pid), instructions(tty.pid)
        start, began = len(tty.buf), time.perf_counter()
        tty.send(f"SCENARIO md:{a.size} CWD {cwd}".encode() + b"\r")
        latencies = []
        missed_echoes = 0
        typed = ""
        next_key = time.perf_counter() + 1.0
        needle = b"Done: md, answer"
        scan = start
        deadline = time.perf_counter() + 1800
        while time.perf_counter() < deadline:
            tty.pump(0.005)
            if needle in ANSI.sub(b"", tty.buf[scan:]):
                break
            if tty.eof:
                status, _ = tty.quit()
                raise RuntimeError(f"{build.name}: terminal closed before the answer (exit {status})")
            scan = max(start, len(tty.buf) - 8192)
            if a.keys and time.perf_counter() >= next_key:
                typed += "x"
                sent_at, mark = time.perf_counter(), len(tty.buf)
                tty.send(b"x")
                shown = typed.encode()
                while time.perf_counter() < sent_at + 5:
                    tty.pump(0.002)
                    if shown in ANSI.sub(b"", tty.buf[mark:]) or tty.eof:
                        break
                else:
                    missed_echoes += 1
                latencies.append(1000 * (time.perf_counter() - sent_at))
                next_key = sent_at + a.key_interval
        else:
            status, _ = tty.quit()
            sys.exit(f"{build.name}: no answer (exit {status})")
        wall = time.perf_counter() - began
        if balanced:
            marker = process_snapshot(tty.pid, initial["rusage_flavor"])
            to_marker = phase_counters(initial, marker)
            used = to_marker["cpu_ms"] / 1000
            instr, after = initial["instructions"], marker["instructions"]
            if after <= instr or marker["cycles"] <= initial["cycles"]:
                raise RuntimeError(f"{build.name}: instruction or cycle counters unavailable")
        else:
            used = (cpu_ms(tty.pid) - cpu) / 1000
        frames = tty.buf.count(b"\x1b[?2026h", start)
        written = len(tty.buf) - start
        if balanced:
            drain_started = time.perf_counter()
            while (remaining := drain_started + 0.3 - time.perf_counter()) > 0:
                tty.pump(min(0.01, remaining))
            drained = process_snapshot(tty.pid, initial["rusage_flavor"])
            output = {
                "submit_to_marker": to_marker,
                "marker_to_drain": phase_counters(marker, drained),
                "submit_to_drain": phase_counters(initial, drained),
                "rusage_flavor": initial["rusage_flavor"],
                "marker_to_drain_wall_ms": (time.perf_counter() - drain_started) * 1000,
                "marker_footprint_bytes": marker["footprint_bytes"],
                "drained_footprint_bytes": drained["footprint_bytes"],
                "marker_frames": frames,
                "drained_frames": tty.buf.count(b"\x1b[?2026h", start),
                "marker_output_bytes": written,
                "drained_output_bytes": len(tty.buf) - start,
                "key_echo_ms": latencies,
            }
            ri = None
        else:
            after = instructions(tty.pid)
            ri = _rusage_macos(tty.pid) if MACOS else None
        # Clear the injected typing before /quit. Completion is checked outside the timing window.
        tty.buf = b""
        clear_settled = True
        if typed:
            tty.send(b"\x15")
            clear_settled = tty.settle(0.1, time.perf_counter() + 3)
        tty.buf = b""
        quit_started = time.perf_counter()
        status, _ = tty.quit(timeout=30, keep_tail=4096) if balanced else tty.quit()
        if status != 0:
            outcome = "timed out waiting for /quit" if status is None else f"/quit exited with status {status}"
            raise RuntimeError(
                f"{build.name}: {outcome} after {time.perf_counter() - quit_started:.1f}s "
                f"(PID {tty.pid}, {mode}, {entries} entries, {len(latencies)} keys, "
                f"editor settled after clear={clear_settled}); terminal tail={tty.screen_text()[-400:]!r}"
            )
    row = {"build": build.name, "entries": entries, "mode": mode, "size": a.size, "wall_s": round(wall, 2), "cpu_s": round(used, 3),
           "frames": frames, "fps": round(frames / wall, 1), "kb_written": round(written / 1024),
           "exit_status": status, "keys_sent": len(latencies), "key_echo_timeouts": missed_echoes}
    if balanced:
        row.update(output, own_mb_end=round(marker["footprint_bytes"] / (1 << 20), 1))
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


def balanced_orders(builds, rounds, seed):
    """Each cycle covers all six orders, with the order of rounds randomized."""
    rng = random.Random(seed)
    for _ in range(rounds // 6):
        cycle = list(itertools.permutations(builds))
        rng.shuffle(cycle)
        yield from cycle


def balanced_summary(rows, names, seed):
    """Sample whole matched rounds to preserve covariance between builds."""
    for mode, entries in sorted({(row["mode"], row["entries"]) for row in rows}):
        condition = [row for row in rows if row["mode"] == mode and row["entries"] == entries]
        by_round = {}
        for row in condition:
            by_round.setdefault(row["round"], {})[row["build"]] = row
        for reference, target in itertools.combinations(names, 2):
            for phase in ("submit_to_marker", "submit_to_drain"):
                for metric in ("cpu_ms", "instructions"):
                    pairs = [(group[reference][phase][metric], group[target][phase][metric]) for group in by_round.values()]
                    deltas = [candidate - baseline for baseline, candidate in pairs]
                    rng = random.Random(seed)
                    sampled_means = sorted(statistics.fmean(rng.choices(deltas, k=len(deltas))) for _ in range(10_000))
                    print(json.dumps({
                        "mode": mode, "entries": entries, "reference": reference, "target": target,
                        "phase": phase, "metric": metric, "matched_rounds": len(pairs),
                        "reference_median": statistics.median(value[0] for value in pairs),
                        "target_median": statistics.median(value[1] for value in pairs),
                        "mean_paired_delta": statistics.mean(deltas),
                        "median_paired_delta": statistics.median(deltas),
                        "paired_bootstrap_95_interval": [
                            sampled_means[math.floor(0.025 * len(sampled_means))],
                            sampled_means[math.ceil(0.975 * len(sampled_means)) - 1],
                        ],
                    }), flush=True)


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
    ap.add_argument("--balanced-seed", type=int, help="macOS only: balance exactly three builds across all six run orders, with phase counters")
    ap.add_argument("--pre-prompt-wait", type=float, default=0, help="extra seconds to drain output after the initial quiet period, before measuring")
    a = ap.parse_args()
    if not math.isfinite(a.pre_prompt_wait) or a.pre_prompt_wait < 0:
        ap.error("--pre-prompt-wait must be a finite nonnegative number")
    builds = parse_builds(a.build)
    balanced = a.balanced_seed is not None
    if balanced and (not MACOS or len(builds) != 3 or len({build.name for build in builds}) != 3 or a.rounds < 6 or a.rounds % 6):
        ap.error("--balanced-seed requires macOS, three distinctly named builds, and a positive multiple of six rounds")
    orders = balanced_orders(builds, a.rounds, a.balanced_seed) if balanced else (
        builds if r % 2 == 0 else list(reversed(builds)) for r in range(a.rounds)
    )
    rows = []
    for r, order in enumerate(orders, 1):
        for mode in a.mode.split(","):
            for entries in [int(e) for e in a.entries.split(",")]:
                for slot, build in enumerate(order, 1):
                    row = run(build, entries, mode, a)
                    if balanced:
                        row.update(round=r, slot=slot, order=[item.name for item in order], seed=a.balanced_seed,
                                   cols=a.cols, rows=a.rows, pace_ms=a.pace_ms, key_interval_s=a.key_interval if a.keys else None)
                    if a.pre_prompt_wait:
                        row["pre_prompt_wait_s"] = a.pre_prompt_wait
                    rows.append(row)
                    print(json.dumps(row), flush=True)
                    if a.json:
                        with open(a.json, "a") as f:
                            f.write(json.dumps(row) + "\n")
    if balanced:
        print("--- matched comparisons", flush=True)
        balanced_summary(rows, [build.name for build in builds], a.balanced_seed)
    elif a.rounds > 1:
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
