#!/usr/bin/env python3
"""A long interactive session: N prompts (each 5 model turns with 4 file reads) in one Pi process. Every few prompts it reports the
time per prompt, CPU per prompt, memory, and how large the conversation sent to the model has grown, to expose slowdowns and
growth that short runs hide.

On Windows (ConPTY, winproc.py) memory is the working set and, as "own", the private bytes (commit charge), plus the private
working set; the last row also has the session's peaks (working set, private working set, private bytes) and the rise of the
system's commit charge.

Example (300 tool calls, a context well past a million tokens):
  bench/long_session.py --prompts 75 --every 25 --build pi-bolt=./out/pi/pi --build bun=./out/pi-stable/pi
"""

import argparse
import json
import os
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import (
    MODEL_ARGS, PROMPT, WINDOWS, Tty, cpu_ms, done, fake_model, memory_mb, parse_builds, pi_env, pi_home, pinned, workdir,
)


def session(build, prompts, every, cpus):
    sizes = tempfile.NamedTemporaryFile(prefix="pibolt-sizes-", delete=False).name
    rows = []
    with fake_model(log_sizes=sizes) as port, pi_home(port) as home, workdir() as cwd:
        tty = Tty(pinned([*build.argv, "--no-session", *MODEL_ARGS], cpus), pi_env(home), cwd)
        deadline = time.perf_counter() + 7200
        if not tty.wait_for("fake-model", 0, deadline):
            return [{"error": "never became interactive"}], None
        tty.settle(0.1, deadline)
        t_block, c_block = time.perf_counter(), cpu_ms(tty.pid)
        for i in range(1, prompts + 1):
            start = len(tty.buf)
            tty.send(PROMPT.encode() + b"\r")
            if not tty.wait_for(done(i), start, deadline):
                rows.append({"prompt": i, "error": "no answer"})
                break
            tty.settle(0.03, deadline)
            if i % every == 0:
                now, c = time.perf_counter(), cpu_ms(tty.pid)
                m = memory_mb(tty.pid)
                last = int(open(sizes).read().split()[-1])
                rows.append({"prompt": i, "ms_per_prompt": round((now - t_block) * 1000 / every, 1),
                             "cpu_ms_per_prompt": round((c - c_block) / every, 1), "rss_mb": m.get("rss"), "own_mb": m.get("own"),
                             "request_mb": round(last / 1e6, 2), "tokens_m": round(last / 4e6, 2)})
                if WINDOWS:
                    # (own_mb is the private bytes, the commit charge; the private working set is what of it is resident.)
                    rows[-1]["private_ws_mb"] = m.get("private_ws")
                t_block, c_block = now, c
        status, ru = tty.quit()
        if WINDOWS and rows and "error" not in rows[-1]:
            # The peaks over the whole session, from the Job object and the sampling (winproc.py).
            res = ru.result
            rows[-1].update(peak_mb=res["peak_mb"], peak_private_mb=res["peak_private_mb"],
                            peak_private_ws_mb=res["peak_private_ws_mb"], system_commit_peak_mb=res["system_commit_peak_mb"],
                            job_cpu_ms=round(res["job_cpu_ms"], 1))
    os.unlink(sizes)
    return rows, status


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command that starts Pi (repeatable)")
    ap.add_argument("--prompts", type=int, default=40)
    ap.add_argument("--every", type=int, default=10)
    ap.add_argument("--cpus", help="pin Pi to these cores (taskset list)")
    ap.add_argument("--out", help="append the results to this JSONL file")
    a = ap.parse_args()
    for build in parse_builds(a.build):
        rows, status = session(build, a.prompts, a.every, a.cpus)
        print(f"== {build.name} (exit {status})")
        for row in rows:
            if "error" in row:
                print(f"   {row.get('prompt', '')}: {row['error']}")
                continue
            print(f"   {row['prompt']:4d}: {row['ms_per_prompt']:7.1f} ms/prompt   cpu {row['cpu_ms_per_prompt']:7.1f} ms/prompt   "
                  f"memory {row['rss_mb']} MB (own {row['own_mb']} MB)   request {row['request_mb']:5.1f} MB (~{row['tokens_m']:.1f}M tokens)"
                  + (f"   private working set {row['private_ws_mb']} MB" if "private_ws_mb" in row else ""))
            if "peak_mb" in row:
                print(f"         peaks: working set {row['peak_mb']} MB, private working set {row['peak_private_ws_mb']} MB, "
                      f"private bytes {row['peak_private_mb']} MB, system commit +{row['system_commit_peak_mb']} MB")
        if a.out:
            with open(a.out, "a") as f:
                for row in rows:
                    f.write(json.dumps({"build": build.name, "exit": status, **row}) + "\n")


if __name__ == "__main__":
    main()
