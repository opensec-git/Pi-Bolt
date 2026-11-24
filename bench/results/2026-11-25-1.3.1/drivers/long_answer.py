#!/usr/bin/env python3
"""Long answers in the TUI: the CPU it takes to stream a Markdown answer (headings, lists, code blocks, tables) of each size, at
the pace of a fast model. Each chunk makes the TUI render the message again, so what a chunk costs must not grow with the
length of the answer. With --file, the answers are those Markdown files instead, streamed the same way: long code blocks,
lists and tables, which the generated answers (small blocks) do not have; bench/answers/ has a set. Reports the frames each
answer drew and, on macOS, the instructions retired per frame.

Example: bench/long_answer.py --build pi-bolt=./out/pi-bolt/pi --build bun=./out/pi-stable/pi
         bench/long_answer.py --build pi-bolt=./out/pi-bolt/pi --sizes "" --file bench/answers/code150.md --file bench/answers/prose.md
"""

import argparse
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import MACOS, MODEL_ARGS, Tty, _rusage_macos, cpu_ms, fake_model, memory_mb, parse_builds, pi_env, pi_home, pinned, workdir


def instructions(pid):
    ri = _rusage_macos(pid) if MACOS else None
    return ri[29] if ri else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command (repeatable)")
    ap.add_argument("--sizes", default="5000,20000,60000", help="characters in each generated answer (\"\": none)")
    ap.add_argument("--file", action="append", default=[], help="also stream this Markdown file as an answer (repeatable)")
    ap.add_argument("--pace-ms", type=float, default=20, help="between chunks of 24 characters (20: 1,200 characters a second)")
    ap.add_argument("--cpus", help="pin Pi to these CPUs (taskset list)")
    a = ap.parse_args()
    # (What the prompt names: the fake model takes the path up to the next space.)
    answers = [(f"md:{size}", "md", str(size), size) for size in (int(s) for s in a.sizes.split(",") if s)]
    for file in a.file:
        path = os.path.abspath(file)
        if not os.path.isfile(path) or any(c.isspace() for c in path):
            sys.exit(f"--file {file}: not a file, or a path with spaces")
        answers.append((f"mdfile:{path}", "mdfile", os.path.basename(path), len(Path(path).read_text(encoding="utf-8"))))
    if not answers:
        sys.exit("no answers: give --sizes or --file")
    print(f"{'build':12} {'answer':>14} {'characters':>10} {'wall s':>8} {'cpu s':>8} {'of a core':>10} {'own MB':>7} {'frames':>7} "
          f"{'M instr':>8} {'M instr/frame':>14}")
    for build in parse_builds(a.build):
        with fake_model(pace_ms=a.pace_ms, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
            tty = Tty(pinned([*build.argv, "--no-session", *MODEL_ARGS], a.cpus), pi_env(home), cwd, cols=160, rows=48)
            if not tty.wait_for("fake-model", 0, time.perf_counter() + 60):
                sys.exit(f"{build.name}: the TUI did not start")
            tty.settle(0.5, time.perf_counter() + 5)
            for answer, (scenario, kind, label, size) in enumerate(answers, 1):
                start, cpu, instr, began = len(tty.buf), cpu_ms(tty.pid), instructions(tty.pid), time.perf_counter()
                tty.send(f"SCENARIO {scenario} CWD {cwd}".encode() + b"\r")
                if not tty.wait_for(f"Done: {kind}, answer {answer}.", start, time.perf_counter() + 1800):
                    sys.exit(f"{build.name}: no answer {label}")
                wall = time.perf_counter() - began
                tty.settle(0.3, time.perf_counter() + 5)
                used = (cpu_ms(tty.pid) - cpu) / 1000
                # (Frames: the synchronized-output blocks the TUI wrote, one per frame.)
                frames = tty.buf.count(b"\x1b[?2026h", start)
                after = instructions(tty.pid)
                minstr = f"{(after - instr) / 1e6:8.0f} {(after - instr) / 1e6 / max(frames, 1):14.2f}" if instr is not None and after else f"{'-':>8} {'-':>14}"
                print(f"{build.name:12} {label:>14} {size:10} {wall:8.1f} {used:8.2f} {100 * used / wall:9.0f}% "
                      f"{memory_mb(tty.pid).get('own', 0):7.0f} {frames:7} {minstr}", flush=True)
            tty.quit()


if __name__ == "__main__":
    main()
