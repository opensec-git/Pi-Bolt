#!/usr/bin/env python3
"""Stress and load tests: what production use does to Pi that the functional checks do not. Against a fake model
(fake_model_stress.py), for each build:

  concurrent  N Pi processes at once (-p), each doing about twenty turns of large tool work (megabytes of command output,
              a 1 MB file written, read, edited and grepped, 3,000 files, Unicode and binary files, three tool calls at once,
              failing tools, a command that times out). Every process must finish, and each one's digest of what its tools
              returned must equal the reference build's.
  faults      a 2 MB streamed answer, tool arguments a few bytes at a time, a connection cut mid-answer, HTTP 500 and 429,
              a stream that is not JSON: Pi must end on its own, without crashing.
  signals     SIGINT and SIGTERM while a tool runs, and stdout closed early: Pi must exit promptly, without crashing.
  soak        one Pi process in RPC mode answering many prompts in a row; memory is sampled after every prompt, and must
              level off. One run's floor moves by about a megabyte with when the collector happens to run, so to compare
              builds take the median of several runs (--soak-runs).

A crash is: death by a signal Pi did not ask for, or a crash report ("panic", "has crashed", "Segmentation fault") in the output.

Example: bench/stress.py --reference stable=./out/pi-stable/pi --build pi-bolt=./out/pi-bolt/pi --concurrency 32 --soak 300
Memory of two builds: bench/stress.py --only soak --soak-runs 15 --build old=./old/pi --build new=./new/pi
"""

import argparse
import json
import os
import queue
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import FIXTURES, MACOS, fake_model, maxrss_mb, memory_mb, parse_builds, pi_env, pi_home

# Which of the model server's APIs Pi talks to it with (--api).
MODEL_ARGS = ["--model", "fake/fake-model"]
CRASH = re.compile(rb"panic|has crashed|Segmentation fault|SIGSEGV|SIGILL|SIGBUS|Illegal instruction|core dumped|ASSERTION FAILED")
DIGEST = re.compile(rb"(\d+) tool results, (\d+) characters, digest ([0-9a-f]{16}), sent ([0-9a-f]{16})\. Done: (\w+)\.")
failures = []


def fail(build, what):
    failures.append(f"{build}: {what}")
    print(f"  FAIL {what}", flush=True)


def new_workdir(root: Path, name: str) -> Path:
    d = root / name
    d.mkdir(parents=True)
    shutil.copytree(FIXTURES, d / "fixture")
    return d


def run_print(build, home, cwd: Path, scenario, timeout=600, extra_env=None):
    """Runs one -p prompt; returns (exit code, output, peak resident MB, seconds)."""
    start = time.perf_counter()
    p = subprocess.Popen([*build.argv, "-p", "--no-session", *MODEL_ARGS, f"SCENARIO {scenario} CWD {cwd}"], cwd=cwd,
                         env=pi_env(home, {"TERM": "dumb", **(extra_env or {})}), stdin=subprocess.DEVNULL,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = b""
    timer = threading.Timer(timeout, p.kill)
    timer.start()
    try:
        out = p.stdout.read()
        _, status, ru = os.wait4(p.pid, 0)
        p.returncode = os.waitstatus_to_exitcode(status)
    finally:
        timer.cancel()
    return p.returncode, out, maxrss_mb(ru), time.perf_counter() - start


def crashed(code, out):
    return (code is not None and code < 0 and code not in (-signal.SIGINT, -signal.SIGTERM, -signal.SIGPIPE)) or CRASH.search(out)


def concurrent(build, port, root, n, reference_digest):
    print(f"[{build.name}] concurrent: {n} processes, heavy tool work", flush=True)
    results = [None] * n
    with pi_home(port) as home:
        dirs = [new_workdir(root, f"{build.name}-c{i}") for i in range(n)]

        def one(i):
            results[i] = run_print(build, home, dirs[i], "heavy")

        start = time.perf_counter()
        threads = [threading.Thread(target=one, args=(i,)) for i in range(n)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        wall = time.perf_counter() - start
    digests = {}
    for i, (code, out, rss, secs) in enumerate(results):
        m = DIGEST.search(out)
        if crashed(code, out):
            fail(build.name, f"concurrent #{i} crashed (exit {code}): {out[-400:]!r}")
        elif code != 0 or not m:
            fail(build.name, f"concurrent #{i} did not finish (exit {code}): {out[-400:]!r}")
        else:
            digests.setdefault(m.group(0).decode(), []).append(i)
    rss = sorted(r[2] for r in results)
    secs = sorted(r[3] for r in results)
    print(f"  {n} done in {wall:.1f}s wall; per process {secs[len(secs) // 2]:.1f}s median, {secs[-1]:.1f}s max; "
          f"peak memory {rss[len(rss) // 2]:.0f} MB median, {rss[-1]:.0f} MB max", flush=True)
    if len(digests) > 1:
        fail(build.name, f"concurrent runs disagree: {json.dumps({k: len(v) for k, v in digests.items()})}")
    for d in digests:
        print(f"  {d} ({len(digests[d])} processes)", flush=True)
        if reference_digest and d != reference_digest:
            fail(build.name, f"tool results differ from the reference: {d} vs {reference_digest}")
    return next(iter(digests), None)


def faults(build, port, root):
    print(f"[{build.name}] faults", flush=True)
    with pi_home(port) as home:
        for scenario, expect_ok in [("stream", True), ("bigargs", True), ("drop", False), ("http500", False), ("http429", False), ("garbage", False)]:
            code, out, rss, secs = run_print(build, home, new_workdir(root, f"{build.name}-{scenario}"), scenario, timeout=300)
            done = f"Done: {scenario}.".encode() in out
            status = "ok"
            if crashed(code, out):
                status = "CRASH"
                fail(build.name, f"{scenario} crashed (exit {code}): {out[-400:]!r}")
            elif expect_ok and (code != 0 or not done):
                status = "FAIL"
                fail(build.name, f"{scenario} did not finish (exit {code}): {out[-300:]!r}")
            elif not expect_ok and done:
                status = "FAIL"
                fail(build.name, f"{scenario} claimed success")
            last = out.strip().splitlines()[-1][:110].decode("utf-8", "replace") if out.strip() else ""
            print(f"  {scenario:8} exit {code}, {secs:.1f}s, {rss:.0f} MB, {status}: {last}", flush=True)


def signals(build, port, root):
    print(f"[{build.name}] signals", flush=True)
    with pi_home(port) as home:
        for sig in (signal.SIGINT, signal.SIGTERM):
            cwd = new_workdir(root, f"{build.name}-sig{int(sig)}")
            p = subprocess.Popen([*build.argv, "-p", "--no-session", *MODEL_ARGS, f"SCENARIO sleep CWD {cwd}"], cwd=cwd,
                                 env=pi_env(home, {"TERM": "dumb"}), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 start_new_session=True)
            time.sleep(4)
            sent = time.perf_counter()
            os.killpg(p.pid, sig)
            try:
                out, _ = p.communicate(timeout=15)
                code = p.returncode
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGKILL)
                out, _ = p.communicate()
                code = None
                fail(build.name, f"{sig.name}: still running 15s later")
            took = time.perf_counter() - sent
            if crashed(code, out):
                fail(build.name, f"{sig.name}: crashed (exit {code}): {out[-300:]!r}")
            leftover = subprocess.run(["pgrep", "-s", str(p.pid)], capture_output=True, text=True).stdout.split()
            if leftover:
                fail(build.name, f"{sig.name}: left processes behind: {leftover}")
                for pid in leftover:
                    os.kill(int(pid), signal.SIGKILL)
            print(f"  {sig.name}: exit {code} after {took:.2f}s", flush=True)
        # Output closed early: `pi -p ... | head -c 200`.
        cwd = new_workdir(root, f"{build.name}-pipe")
        r = subprocess.run(f"{' '.join(build.argv)} -p --no-session {' '.join(MODEL_ARGS)} 'SCENARIO stream CWD {cwd}' 2>&1 | head -c 200 >/dev/null; echo ${{PIPESTATUS[0]}}",
                           shell=True, executable="/bin/bash", cwd=cwd, env=pi_env(home, {"TERM": "dumb"}), stdin=subprocess.DEVNULL, capture_output=True, timeout=300)
        code = int(r.stdout.strip() or 0)
        if code > 128 and code - 128 not in (signal.SIGPIPE,):
            fail(build.name, f"stdout closed early: killed by signal {code - 128}")
        print(f"  stdout closed early: exit {code}", flush=True)


def resident_mb(pid):
    # (macOS: the physical footprint. Resident memory there also counts the clean pages of the executable that have been read,
    # which only grow as more of it is used, and say nothing about what is kept.)
    if MACOS:
        return memory_mb(pid).get("own", 0.0)
    try:
        for line in open(f"/proc/{pid}/status"):
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    except OSError:
        pass
    return 0.0


def soak(build, port, root, prompts, run=0):
    """Returns the floor of memory in the last quarter of the run (MB), or None."""
    print(f"[{build.name}] soak: {prompts} prompts in one RPC session" + (f" (run {run + 1})" if run else ""), flush=True)
    cwd = new_workdir(root, f"{build.name}-soak-{run}")
    with pi_home(port) as home:
        p = subprocess.Popen([*build.argv, "--mode", "rpc", "--no-session", *MODEL_ARGS], cwd=cwd, env=pi_env(home, {"TERM": "dumb"}),
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stderr, lines = [], queue.Queue()
        threading.Thread(target=lambda: stderr.append(p.stderr.read()), daemon=True).start()

        def read_stdout():
            for line in p.stdout:
                lines.put(line)
            lines.put(b"")

        threading.Thread(target=read_stdout, daemon=True).start()
        samples, done, start, recent = [], 0, time.perf_counter(), []
        try:
            for i in range(prompts):
                scenario = "heavy" if i % 50 == 49 else "light"
                p.stdin.write(json.dumps({"id": f"r{i}", "type": "prompt", "message": f"SCENARIO {scenario} CWD {cwd}"}).encode() + b"\n")
                p.stdin.flush()
                finished = False
                deadline = time.perf_counter() + 300
                while time.perf_counter() < deadline:
                    try:
                        line = lines.get(timeout=max(deadline - time.perf_counter(), 0.01))
                    except queue.Empty:
                        break
                    if not line:
                        break
                    recent = (recent + [line[:150]])[-6:]
                    try:
                        event = json.loads(line)
                    except ValueError:
                        continue
                    if event.get("type") == "response" and event.get("success") is False:
                        fail(build.name, f"soak prompt {i} rejected: {line[:200]!r}")
                        break
                    if event.get("type") == "agent_settled":
                        finished = True
                        break
                if not finished:
                    fail(build.name, f"soak prompt {i} did not finish (process exit {p.poll()}); last events: {recent!r}")
                    break
                done += 1
                # After every prompt: the floors below are the least of these, and sparser samples miss the low points.
                samples.append((i, resident_mb(p.pid)))
                if i % 50 == 0:
                    # A fresh conversation now and then, as people start new tasks; the context otherwise grows past the window.
                    p.stdin.write(json.dumps({"id": f"n{i}", "type": "new_session"}).encode() + b"\n")
                    p.stdin.flush()
                    # Its answer before the next prompt, or the new session would replace the conversation that prompt started.
                    deadline = time.perf_counter() + 60
                    while time.perf_counter() < deadline:
                        try:
                            line = lines.get(timeout=max(deadline - time.perf_counter(), 0.01))
                        except queue.Empty:
                            break
                        if not line or b'"id":"n%d"' % i in line:
                            break
        finally:
            p.stdin.close()
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait()
        err = b"".join(stderr)
        if crashed(p.returncode, err):
            fail(build.name, f"soak: crashed (exit {p.returncode}): {err[-400:]!r}")
    took = time.perf_counter() - start
    print(f"  {done}/{prompts} prompts in {took:.0f}s ({1000 * took / max(done, 1):.0f} ms each); exit {p.returncode}", flush=True)
    shown = [(i, mb) for n, (i, mb) in enumerate(samples) if i % 10 == 0 or n == len(samples) - 1]
    print(f"  {'footprint' if MACOS else 'resident'} MB by prompt: " + ", ".join(f"{i}:{mb:.0f}" for i, mb in shown), flush=True)
    if len(samples) < 8:
        return None
    # Memory rises and falls with each collection, so compare floors: the least in the last quarter of the run against the
    # least in its second quarter (the first is warm-up). What is retained for good raises the floor.
    quarter = len(samples) // 4
    early = min(mb for _, mb in samples[quarter:2 * quarter])
    late = min(mb for _, mb in samples[3 * quarter:])
    span = samples[-1][0] - samples[quarter][0]
    per100 = 100 * (late - early) / max(span, 1)
    print(f"  floor of memory: {early:.1f} MB in the second quarter, {late:.1f} MB in the last ({per100:+.1f} MB per 100 prompts)", flush=True)
    # (Memory stays up for some tens of prompts after each heavy one, every fiftieth: a quarter of a short run can lie
    # wholly within that, and says nothing.)
    if late - early > 50 and prompts >= 400:
        fail(build.name, f"soak: memory keeps growing, its floor {early:.0f} -> {late:.0f} MB")
    return late


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reference", help="name=command of the build whose tool results are taken as correct")
    ap.add_argument("--build", action="append", required=True, help="name=command to test (repeatable)")
    ap.add_argument("--concurrency", type=int, default=16)
    ap.add_argument("--soak", type=int, default=200, help="prompts in the soak test (0: none)")
    ap.add_argument("--soak-runs", type=int, default=1, help="soak runs per build, each a new process; with more than one, the "
                    "median of their floors is printed too")
    ap.add_argument("--only", default="concurrent,faults,signals,soak")
    ap.add_argument("--api", default="completions", choices=["completions", "anthropic", "responses"],
                    help="the API Pi talks to the model with: OpenAI chat completions (default), Anthropic messages, OpenAI responses")
    a = ap.parse_args()
    MODEL_ARGS[1] = {"completions": "fake/fake-model", "anthropic": "fake-anthropic/fake-model", "responses": "fake-responses/fake-model"}[a.api]
    only = set(a.only.split(","))
    root = Path(tempfile.mkdtemp(prefix="pibolt-stress-"))
    reference_digest = None
    with fake_model(script="fake_model_stress.py") as port:
        if a.reference:
            ref = parse_builds([a.reference])[0]
            code, out, _, _ = run_print(ref, *_home_and_dir(port, root, ref.name))
            m = DIGEST.search(out)
            if code != 0 or not m:
                sys.exit(f"the reference did not finish (exit {code}): {out[-600:]!r}")
            reference_digest = m.group(0).decode()
            print(f"reference {ref.name}: {reference_digest}", flush=True)
        for build in parse_builds(a.build):
            if "concurrent" in only:
                concurrent(build, port, root, a.concurrency, reference_digest)
            if "faults" in only:
                faults(build, port, root)
            if "signals" in only:
                signals(build, port, root)
            if "soak" in only and a.soak:
                floors = [f for f in (soak(build, port, root, a.soak, run) for run in range(a.soak_runs)) if f is not None]
                if len(floors) > 1:
                    floors.sort()
                    print(f"[{build.name}] floor of memory in the last quarter, {len(floors)} runs: median {statistics.median(floors):.1f} MB "
                          f"({floors[0]:.1f} to {floors[-1]:.1f})", flush=True)
    shutil.rmtree(root, ignore_errors=True)
    print("\n" + ("PASS" if not failures else f"FAIL ({len(failures)})\n  " + "\n  ".join(failures)))
    sys.exit(1 if failures else 0)


_homes = []


def _home_and_dir(port, root, name):
    ctx = pi_home(port)
    home = ctx.__enter__()
    _homes.append(ctx)
    return home, new_workdir(root, f"{name}-ref"), "heavy"


if __name__ == "__main__":
    main()
