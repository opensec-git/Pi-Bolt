#!/usr/bin/env python3
"""Pauses of the TUI: the longest stretch in which Pi writes nothing to the terminal while it should be drawing (a spinner
turns, a tool call's arguments stream in, an answer streams). A pause is the program busy with one thing; nothing typed is taken
up during it. Reports, for each step, the CPU, the 99th percentile and the longest gap between two writes (the p99 frame time
and the longest stall of the main thread, as the terminal sees them), and where in the step the longest one was.

--gc also logs every garbage collection and reports its pauses (99th percentile, longest, count): with BUN_JSC_logGC=1 for a
Bun build (JavaScriptCore's own log), and for Node with gc_log.cjs preloaded (perf_hooks "gc" entries). Both go to standard
error, which goes to a file instead of the terminal. The logging costs a little, so the frame times of a --gc run are not those
of a plain one: run once without it for the frames, once with it for the collections.

Steps are scenarios of fake_model_stress.py: write:KB (a file written through a tool call), md:CHARS (a Markdown answer).
On Windows Pi runs in a ConPTY: the gaps are between the updates the console sends, which come at most once a console frame.

Example: bench/pauses.py --build pi-bolt=./out/pi-bolt/pi --steps write:50,write:200,write:400
         bench/pauses.py --gc --steps md:20000,write:50 --out pauses.jsonl --build pi-bolt=./out/pi-bolt/pi
"""

import argparse
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import ANSI, MODEL_ARGS, Tty, cpu_ms, fake_model, memory_mb, parse_builds, pi_env, pi_home, pinned, workdir

HERE = Path(__file__).resolve().parent
GC_PAUSE = re.compile(rb"p=([\d.]+)ms")


def is_node(build):
    return Path(build.argv[0]).stem.lower().startswith("node")


def gc_env(build):
    """What turns the GC log on: JavaScriptCore's option for Bun and Pi-Bolt, a preloaded observer for Node."""
    if is_node(build):
        # (Forward slashes: Node takes a backslash in NODE_OPTIONS for an escape.)
        return {"NODE_OPTIONS": f'--require "{(HERE / "gc_log.cjs").as_posix()}"'}
    return {"BUN_JSC_logGC": "1"}


def percentile(ordered, q):
    return ordered[max(0, int(len(ordered) * q) - 1)] if ordered else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command (repeatable)")
    ap.add_argument("--steps", default="write:50,write:200,write:400", help="scenarios of fake_model_stress.py, in one session")
    ap.add_argument("--pace-ms", type=float, default=2, help="between chunks")
    ap.add_argument("--gc", action="store_true", help="log garbage collections and report their pauses")
    ap.add_argument("--cpus", help="pin Pi to these CPUs (taskset list)")
    ap.add_argument("--out", help="append the results to this JSONL file")
    a = ap.parse_args()
    head = f"{'build':12} {'step':10} {'wall s':>7} {'cpu s':>6} {'gap p99 ms':>10} {'longest ms':>10} {'at':>5} {'own MB':>7}"
    print(head + (f" {'gc p99 ms':>9} {'gc max ms':>9} {'gcs':>5}" if a.gc else ""))
    for build in parse_builds(a.build):
        log = None
        if a.gc:
            fd, log = tempfile.mkstemp(prefix="pibolt-gc-", suffix=".txt")
            os.close(fd)
        with fake_model(pace_ms=a.pace_ms, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
            env = pi_env(home, gc_env(build) if a.gc else None)
            tty = Tty(pinned([*build.argv, "--no-session", *MODEL_ARGS], a.cpus), env, cwd, cols=160, rows=48, stderr=log)
            if not tty.wait_for("fake-model", 0, time.perf_counter() + 60):
                sys.exit(f"{build.name}: the TUI did not start")
            tty.settle(0.5, time.perf_counter() + 5)
            for answer, step in enumerate(a.steps.split(","), 1):
                log_at = os.path.getsize(log) if log else 0
                start, cpu, began = len(tty.buf), cpu_ms(tty.pid), time.perf_counter()
                tty.send(f"SCENARIO {step} CWD {cwd}".encode() + b"\r")
                marker = (f"Done: md, answer {answer}." if step.startswith("md:") else f"Done: {step}.").encode()
                gaps, last, deadline = [], began, began + 1800
                while time.perf_counter() < deadline:
                    if tty.pump(0.002):
                        now = time.perf_counter()
                        gaps.append(((now - last) * 1000, now - began))
                        last = now
                        if marker in ANSI.sub(b"", tty.buf[max(start, len(tty.buf) - 8192):]):
                            break
                wall = time.perf_counter() - began
                longest, at = max(gaps)
                ordered = sorted(gap for gap, _ in gaps)
                used = (cpu_ms(tty.pid) - cpu) / 1000
                memory = memory_mb(tty.pid)
                line = (f"{build.name:12} {step:10} {wall:7.1f} {used:6.2f} {percentile(ordered, 0.99):10.0f} "
                        f"{longest:10.0f} {100 * at / wall:4.0f}% {memory.get('own', 0):7.0f}")
                row = {"build": build.name, "step": step, "wall_s": round(wall, 2), "cpu_s": round(used, 3),
                       "frame_ms_p99": round(percentile(ordered, 0.99), 1), "longest_ms": round(longest, 1),
                       "longest_at": round(at / wall, 3), "frames": len(gaps), "memory": memory, "gc_logged": a.gc}
                tty.settle(0.3, time.perf_counter() + 3)
                if log:
                    # (Read after the screen has settled: Node reports its collections a little after they end.)
                    with open(log, "rb") as f:
                        f.seek(log_at)
                        pauses = sorted(float(p) for p in GC_PAUSE.findall(f.read()))
                    row["gc"] = {"count": len(pauses), "p99_ms": round(percentile(pauses, 0.99), 2) if pauses else None,
                                 "max_ms": round(pauses[-1], 2) if pauses else None, "total_ms": round(sum(pauses), 1)}
                    line += f" {percentile(pauses, 0.99) or 0:9.1f} {(pauses[-1] if pauses else 0):9.1f} {len(pauses):5}"
                print(line, flush=True)
                if a.out:
                    with open(a.out, "a") as f:
                        f.write(json.dumps(row) + "\n")
            tty.quit()
        if log:
            print(f"   GC log: {log}", file=sys.stderr)


if __name__ == "__main__":
    main()
