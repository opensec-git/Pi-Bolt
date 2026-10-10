#!/usr/bin/env python3
"""Pi the way people use it: in a real terminal multiplexer pane (tmux, 160x48), driven with send-keys and read back with
capture-pane, against a model that streams at a human pace (one event every 10 ms).

For each build it reports:
  - time to interactive; keystroke-to-screen latency (median and p95 of 40 keys); a 20 KB bracketed paste
  - streamed prompts: wall time, CPU used while streaming (all threads), bytes and frames written to the terminal
  - resizing the window while a reply streams, Escape to abort one, then a normal prompt again
  - idle CPU at the prompt once the screen is still, memory (resident and own) after each stage, and a clean /quit
  - anything that looks like an error on screen

Example: bench/tmux_check.py --prompts 4 --rounds 3 --build pi-bolt=./out/pi/pi --build bun=./out/pi-stable/pi

On Windows, where there is no tmux, it runs conpty_check.py: the same steps and fields, with Pi in a ConPTY.
"""

import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import (
    ANSI, MACOS, MODEL_ARGS, PROMPT, WINDOWS, alive, cpu_ms, done, fake_model, median, memory_mb, parse_builds, pi_env, pi_home, workdir,
)

SOCKET = "pibolt-check"
ERRORS = re.compile(r"TypeError|ReferenceError|RangeError|SyntaxError|panic\(|Segmentation fault|Bun has crashed|oh no:|Unhandled|uncaught", re.I)


def tmux(*args, check=False):
    return subprocess.run(["tmux", "-L", SOCKET, *args], capture_output=True, text=True, check=check)


def capture(session):
    return tmux("capture-pane", "-p", "-t", session).stdout


def wait_screen(session, predicate, timeout, poll=0.002):
    end = time.perf_counter() + timeout
    while time.perf_counter() < end:
        if predicate(capture(session)):
            return True
        time.sleep(poll)
    return False


def wait_output(path, since, text, timeout):
    """Until `text` is written to the terminal after byte `since` of what the program wrote."""
    end = time.perf_counter() + timeout
    needle = text.encode()
    while time.perf_counter() < end:
        with open(path, "rb") as f:
            f.seek(since)
            if needle in ANSI.sub(b"", f.read()):
                return True
        time.sleep(0.01)
    return False


def wait_still(session, quiet, limit):
    """Until the screen has not changed for `quiet` seconds."""
    last = capture(session)
    since = time.perf_counter()
    end = time.perf_counter() + limit
    while time.perf_counter() - since < quiet and time.perf_counter() < end:
        time.sleep(0.02)
        now = capture(session)
        if now != last:
            last, since = now, time.perf_counter()


def check(build, env, cwd, prompts, cpus, out_dir):
    session = f"pibolt-{os.getpid()}"
    out_file = out_dir / f"tmux-{build.name}.out"
    out_file.unlink(missing_ok=True)
    command = "exec env -i " + " ".join(shlex.quote(f"{k}={v}") for k, v in env.items() if not k.startswith("TMUX"))
    command += (f" taskset -c {cpus} " if cpus and not MACOS else " ") + " ".join(shlex.quote(a) for a in [*build.argv, "--no-session", *MODEL_ARGS])
    r = {"build": build.name}
    t0 = time.perf_counter()
    tmux("new-session", "-d", "-s", session, "-x", "160", "-y", "48", "-c", str(cwd), command, check=True)
    tmux("pipe-pane", "-t", session, "-o", f"cat >> {shlex.quote(str(out_file))}")
    pid = int(tmux("display", "-p", "-t", session, "#{pane_pid}").stdout.strip())
    try:
        if not wait_screen(session, lambda s: "fake-model" in s, 30):
            r["error"] = "never became interactive"
            return r
        r["tti_ms"] = round((time.perf_counter() - t0) * 1000, 1)
        time.sleep(0.3)
        r["memory_at_start"] = memory_mb(pid)

        latencies = []
        for i in range(40):
            ch = "abcdefghij"[i % 10]
            before = capture(session).count(ch)
            ts = time.perf_counter()
            tmux("send-keys", "-t", session, "-l", ch)
            if not wait_screen(session, lambda s: s.count(ch) > before, 5, 0.001):
                r["error"] = f"keystroke {i} never appeared"
                break
            latencies.append((time.perf_counter() - ts) * 1000)
        if latencies:
            r["key_ms_median"] = round(statistics.median(latencies), 2)
            r["key_ms_p95"] = round(sorted(latencies)[int(len(latencies) * 0.95) - 1], 2)
        tmux("send-keys", "-t", session, "C-u")
        time.sleep(0.2)

        tmux("set-buffer", "-b", "big", "\n".join(f"line {i}: lorem ipsum dolor sit amet" for i in range(400)))
        c0, ts = cpu_ms(pid), time.perf_counter()
        tmux("paste-buffer", "-p", "-t", session, "-b", "big")
        shown = wait_screen(session, lambda s: re.search(r"[Pp]aste|line 399", s) is not None, 10)
        r["paste_ms"] = round((time.perf_counter() - ts) * 1000, 1) if shown else None
        time.sleep(0.3)
        r["paste_cpu_ms"] = round(cpu_ms(pid) - c0, 1)
        tmux("send-keys", "-t", session, "C-u")
        time.sleep(0.1)
        tmux("send-keys", "-t", session, "Escape")
        time.sleep(0.3)

        walls, cpus_used, written, frames = [], [], [], []
        for i in range(prompts):
            size0 = out_file.stat().st_size
            c0, ts = cpu_ms(pid), time.perf_counter()
            tmux("send-keys", "-t", session, "-l", PROMPT)
            tmux("send-keys", "-t", session, "Enter")
            if not wait_output(out_file, size0, done(i + 1), 120):
                r.setdefault("warnings", []).append(f"prompt {i}: no final answer")
            wait_still(session, 0.3, 120)
            walls.append((time.perf_counter() - ts - 0.3) * 1000)
            cpus_used.append(cpu_ms(pid) - c0)
            data = out_file.read_bytes()[size0:]
            written.append(len(data))
            frames.append(data.count(b"\x1b[?2026h"))
        r["prompt_wall_ms"] = round(median(walls), 1)
        r["prompt_cpu_ms"] = round(median(cpus_used), 1)
        r["prompt_kb_written"] = round(median(written) / 1024, 1)
        r["prompt_frames"] = median(frames)
        r["memory_after_prompts"] = memory_mb(pid)

        size0 = out_file.stat().st_size
        tmux("send-keys", "-t", session, "-l", PROMPT)
        tmux("send-keys", "-t", session, "Enter")
        time.sleep(0.8)
        for cols, rows in (("100", "30"), ("200", "60"), ("80", "24"), ("160", "48")):
            tmux("resize-window", "-t", session, "-x", cols, "-y", rows)
            time.sleep(0.25)
        r["resize_while_streaming"] = "ok" if wait_output(out_file, size0, done(prompts + 1), 120) else "answer missing"
        wait_still(session, 0.5, 30)
        tmux("send-keys", "-t", session, "-l", PROMPT)
        tmux("send-keys", "-t", session, "Enter")
        time.sleep(1.0)
        tmux("send-keys", "-t", session, "Escape")
        aborted = wait_screen(session, lambda s: re.search(r"[Aa]bort|[Ii]nterrupt|[Cc]ancel", s) is not None, 5)
        r["escape_aborts"] = "ok" if aborted else "no abort shown"
        wait_still(session, 0.5, 30)
        size0 = out_file.stat().st_size
        tmux("send-keys", "-t", session, "-l", "Say hi")
        tmux("send-keys", "-t", session, "Enter")
        # (The prompt that was aborted is the one before it.)
        r["prompt_after_abort"] = "ok" if wait_output(out_file, size0, done(prompts + 3), 120) else "no answer"

        wait_still(session, 1.0, 30)
        size0, c0 = out_file.stat().st_size, cpu_ms(pid)
        time.sleep(5)
        r["idle_cpu_ms_per_s"] = round((cpu_ms(pid) - c0) / 5, 2)
        r["idle_bytes_written"] = out_file.stat().st_size - size0
        r["memory_at_end"] = memory_mb(pid)
        text = ANSI.sub(b"", out_file.read_bytes()).decode("utf-8", "replace")
        r["errors_on_screen"] = sorted(set(ERRORS.findall(text)))

        tmux("send-keys", "-t", session, "-l", "/quit")
        tmux("send-keys", "-t", session, "Enter")
        end = time.perf_counter() + 10
        while time.perf_counter() < end and alive(pid):
            time.sleep(0.05)
        r["quit"] = "ok" if not alive(pid) else "still running"
        return r
    finally:
        tmux("kill-session", "-t", session)


def main():
    if WINDOWS:
        # No tmux on Windows: the same check in a ConPTY, with the same command line and fields (conpty_check.py).
        import conpty_check
        return conpty_check.main()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command that starts Pi (repeatable)")
    ap.add_argument("--prompts", type=int, default=4)
    ap.add_argument("--rounds", type=int, default=1, help="repeat the whole check, interleaving builds")
    ap.add_argument("--pace-ms", type=float, default=10, help="model streaming pace")
    ap.add_argument("--cpus", help="pin Pi to these cores (taskset list)")
    ap.add_argument("--out", help="append raw results to this JSONL file")
    a = ap.parse_args()
    if not subprocess.run(["which", "tmux"], capture_output=True).returncode == 0:
        raise SystemExit("tmux is required")
    builds = parse_builds(a.build)
    results = []
    out_dir = Path(a.out).resolve().parent if a.out else Path("/tmp")
    with fake_model(pace_ms=a.pace_ms) as port, pi_home(port) as home, workdir() as cwd:
        env = pi_env(home)
        for _ in range(a.rounds):
            for build in builds:
                r = check(build, env, cwd, a.prompts, a.cpus, out_dir)
                results.append(r)
                print(json.dumps(r), file=sys.stderr, flush=True)
                if a.out:
                    with open(a.out, "a") as f:
                        f.write(json.dumps(r) + "\n")
    tmux("kill-server")
    keys = ["tti_ms", "key_ms_median", "paste_ms", "prompt_wall_ms", "prompt_cpu_ms", "prompt_frames", "idle_cpu_ms_per_s"]
    print("build".ljust(18) + "".join(k.rjust(18) for k in keys) + "   own memory start/end MB   checks")
    for build in builds:
        rs = [r for r in results if r["build"] == build.name]
        vals = [median([r.get(k) for r in rs]) for k in keys]
        mem = [median([r.get(m, {}).get("own") for r in rs]) for m in ("memory_at_start", "memory_at_end")]
        checks = set()
        for r in rs:
            checks.update(v for v in (r.get("resize_while_streaming"), r.get("escape_aborts"), r.get("prompt_after_abort"), r.get("quit"), r.get("error")) if v)
            checks.update(r.get("errors_on_screen", []) + r.get("warnings", []))
        print(build.name.ljust(18) + "".join(("-" if v is None else f"{v:.1f}").rjust(18) for v in vals) + f"   {mem}   {sorted(checks)}")


if __name__ == "__main__":
    main()
