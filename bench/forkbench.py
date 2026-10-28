#!/usr/bin/env python3
"""Pi and its ports on equal terms: one tool, interleaved rounds, an adapter per program (profiles.json) for what differs
(config files, environment, flags, the text that shows the TUI is ready).

  forkbench.py --profiles profiles.json --build name=command[@profile] ... [--rounds N] [--scenarios ...] --out rows.jsonl

Scenarios (each run a fresh process; builds interleaved, order rotated every round):
  version    `--version`: wall, CPU, peak memory (macOS: instructions)
  headless   `-p` against bench/fake_model.py: 5 model turns, 4 `read` calls
  ready      TUI on a pty until the ready marker is on screen: tti, CPU, memory then (macOS: instructions)
  stream     TUI, one Markdown answer of --size characters (fake_model_stress.py md:N, 24 characters every 20 ms):
             CPU and wall of the answer, peak own memory of the process (Linux RssAnon sampled, macOS lifetime max footprint)
  idle       TUI after one answered prompt, left alone --idle seconds: CPU per second
  session    TUI continuing (-c) a Pi session of --entries entries: time to ready, own memory 2 s after

Every run gets a scratch HOME (and XDG dirs) inside its throwaway Pi home, so no program reads the user's files.
"""

import argparse
import contextlib
import json
import os
import re
import subprocess
import sys
import shutil
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness
from harness import (DONE, MACOS, PROMPT, Tty, done, fake_model, maxrss_mb, median, parse_builds, peak_footprint_mb, pi_env,
                     pi_home, pinned, workdir)
from long_transcript import write_session
from proctree import TreeSampler, kill_tree

DEFAULT = {
    "model": ["--model", "fake/fake-model"],
    "version": ["--version"],
    "headless": ["-p", "--no-session", "@model", "{prompt}"],
    "tui": ["--no-session", "@model"],
    "continue": ["-c", "@model"],
    "ready": "fake-model",
    "env": {},
    "files": {},
    "session_dir": "{agent}/sessions/{safe}",
    "submit": "\r",
}


class Prog:
    def __init__(self, build, prof):
        self.build, self.p = build, {**DEFAULT, **prof}

    def expand(self, items, ctx):
        out = []
        for item in items:
            if item == "@model":
                out += self.p["model"]
            else:
                out.append(item.format(**ctx))
        return out

    def setup(self, home: Path, port: int, cwd: Path):
        """Scratch HOME and the program's own config in it; returns (env, ctx)."""
        scratch = home / "home"
        for sub in ("", ".config", ".local/share", ".cache", ".local/state"):
            (scratch / sub).mkdir(parents=True, exist_ok=True)
        ctx = {"port": port, "agent": str(home), "home": str(scratch), "cwd": str(cwd), "prompt": PROMPT}
        for rel, content in self.p["files"].items():
            path = scratch / rel.format(**ctx)
            path.parent.mkdir(parents=True, exist_ok=True)
            text = content if isinstance(content, str) else json.dumps(content)
            path.write_text(text.replace("{port}", str(port)).replace("{home}", str(scratch)).replace("{agent}", str(home)))
        extra = {"HOME": str(scratch), "XDG_CONFIG_HOME": str(scratch / ".config"), "XDG_DATA_HOME": str(scratch / ".local/share"),
                 "XDG_CACHE_HOME": str(scratch / ".cache"), "XDG_STATE_HOME": str(scratch / ".local/state"),
                 "TMPDIR": tempfile.gettempdir()}
        extra.update({k: v.format(**ctx) for k, v in self.p["env"].items()})
        return pi_env(home, extra), ctx


def run_plain(argv, env, cwd, cpus):
    t0 = time.perf_counter()
    p = subprocess.Popen(pinned(argv, cpus), env=env, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, start_new_session=True)
    tree = TreeSampler(p.pid)
    # (A program that never exits is killed after 3 minutes: the run then fails.)
    timer = threading.Timer(180, lambda: kill_tree(p.pid))
    timer.start()
    out = p.stdout.read()
    timer.cancel()
    fp = peak_footprint_mb(p.pid)  # macOS: the main process's lifetime peak, read before reaping
    main = dict(harness.last_exit_counters) if MACOS else {}
    snap = tree.snapshot()
    tree.stop()
    others = {q: s for q, s in tree.last.items() if q != p.pid}
    _, status, ru = os.wait4(p.pid, 0)
    # CPU: wait4 counts the process and the children it waited for.
    r = {"ok": os.waitstatus_to_exitcode(status) == 0, "wall_ms": (time.perf_counter() - t0) * 1e3,
         "cpu_ms": (ru.ru_utime + ru.ru_stime) * 1e3, "rss_mb": maxrss_mb(ru), "procs": 1 + len(others)}
    if MACOS:
        r["own_mb"] = (fp or 0) + sum(s["maxfp_mb"] for s in others.values())
        if main:
            r["instr_m"] = main["instr_m"] + sum(s["instr_m"] for s in others.values())
    else:
        r["own_mb"] = snap["peak_own_mb"]
    return out, r


def tui(prog, env, ctx, cwd, cpus, args, cols=120, rows=40):
    t0 = time.perf_counter()
    tty = Tty(pinned([*prog.build.argv, *prog.expand(args, ctx)], cpus), env, cwd, cols=cols, rows=rows)
    tree = TreeSampler(tty.pid)
    ok = tty.wait_for(prog.p["ready"], 0, t0 + 120)
    return tty, tree, ok, (time.perf_counter() - t0) * 1e3


def kill(tty, tree):
    tree.stop()
    kill_tree(tty.pid)
    with contextlib.suppress(ChildProcessError):
        os.wait4(tty.pid, 0)
    with contextlib.suppress(OSError):
        os.close(tty.fd)


def sc_version(prog, a):
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        out, r = run_plain([*prog.build.argv, *prog.expand(prog.p["version"], ctx)], env, cwd, a.cpus)
        r["ok"] = r["ok"] and bool(out.strip())
        r["out"] = out.decode(errors="replace").strip()[:120]
        return r


def sc_headless(prog, a):
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        out, r = run_plain([*prog.build.argv, *prog.expand(prog.p["headless"], ctx)], env, cwd, a.cpus)
        r["ok"] = r["ok"] and DONE.encode() in out
        if not r["ok"]:
            r["out"] = out.decode(errors="replace")[-400:]
        return r


def sc_ready(prog, a):
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["tui"])
        r = {"ok": ok, "tti_ms": tti, **tree.snapshot()}
        if not ok:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def sc_stream(prog, a):
    with fake_model(pace_ms=20, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["tui"], cols=160, rows=48)
        r = {"ok": False, "tti_ms": tti}
        if ok:
            tty.settle(0.5, time.perf_counter() + 5)
            start, s0, t0 = len(tty.buf), tree.snapshot(), time.perf_counter()
            tty.send(f"SCENARIO md:{a.size} CWD {cwd}".encode() + prog.p["submit"].encode())
            if tty.wait_for("Done: md, answer 1.", start, time.perf_counter() + 1800):
                wall = time.perf_counter() - t0
                tty.settle(0.3, time.perf_counter() + 5)
                s1 = tree.snapshot()
                r.update(ok=True, wall_s=wall, cpu_s=(s1["cpu_ms"] - s0["cpu_ms"]) / 1e3, own_end_mb=s1["own_mb"],
                         rss_end_mb=s1["rss_mb"], peak_own_mb=s1["peak_own_mb"], procs=s1["procs"])
                if MACOS:
                    r["instr_m"] = s1["instr_m"] - s0["instr_m"]
        if not r["ok"]:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def sc_idle(prog, a):
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["tui"])
        r = {"ok": False}
        if ok:
            start = len(tty.buf)
            tty.send(PROMPT.encode() + prog.p["submit"].encode())
            if tty.wait_for(done(1), start, time.perf_counter() + 120):
                tty.settle(2.0, time.perf_counter() + 30)
                s0, t0 = tree.snapshot(), time.perf_counter()
                while time.perf_counter() < t0 + a.idle:
                    tty.pump(0.2)
                s1 = tree.snapshot()
                r.update(ok=True, cpu_ms_per_s=(s1["cpu_ms"] - s0["cpu_ms"]) / (time.perf_counter() - t0),
                         own_mb=s1["own_mb"], procs=s1["procs"])
        if not r["ok"]:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def sc_session(prog, a):
    if not prog.p.get("session_dir"):
        return None
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        models = json.loads((home / "models.json").read_text())
        for provider in models["providers"].values():
            for model in provider["models"]:
                model["contextWindow"] = 100_000_000
        (home / "models.json").write_text(json.dumps(models))
        real = str(Path(cwd).resolve())
        for folder in {str(cwd), real}:
            safe = "--" + re.sub(r"[/\\:]", "-", re.sub(r"^[/\\]", "", folder)) + "--"
            d = Path(prog.p["session_dir"].format(**ctx, safe=safe))
            write_session(d / "2026-01-01T00-00-00-000Z_t.jsonl", real, a.entries)
        # The last tool call of the history: on screen once the history is.
        last = [json.loads(line) for line in (d / "2026-01-01T00-00-00-000Z_t.jsonl").read_text().splitlines()][-2]
        needle = last["message"]["content"][-1]["arguments"]["command"].split(" ", 1)[1]
        t0 = time.perf_counter()
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["continue"])
        r = {"ok": ok, "tti_ms": tti}
        if ok:
            # Loaded: the first half second in which the whole tree used under 3% of a core (some programs draw the
            # editor before they have read the history, and read it without writing anything).
            c, deadline = tree.snapshot()["cpu_ms"], time.perf_counter() + 120
            while time.perf_counter() < deadline:
                quiet_from = time.perf_counter()
                end = quiet_from + 0.5
                while time.perf_counter() < end:
                    tty.pump(0.05)
                c1 = tree.snapshot()["cpu_ms"]
                if c1 - c < 15:
                    break
                c = c1
            s = tree.snapshot()
            r.update(loaded_ms=(quiet_from - t0) * 1e3, own_mb=s["own_mb"], rss_mb=s["rss_mb"], cpu_ms=s["cpu_ms"],
                     procs=s["procs"], shown=bool(re.search(r"\b" + re.escape(needle) + r"\b", tty.screen_text())), shown_kb=round(len(tty.buf) / 1024))
        else:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def sc_work(prog, a):
    """What a `-p` prompt asks of the model: requests, tools offered, system prompt, and whether the four `read` results
    hold the files' contents (each fixture's longest line)."""
    dump = Path(tempfile.mkdtemp(prefix="forkbench-dump-"))
    os.environ["FAKE_MODEL_DUMP"] = str(dump)
    try:
        with fake_model() as port, pi_home(port) as home, workdir() as cwd:
            env, ctx = prog.setup(home, port, cwd)
            out, r = run_plain([*prog.build.argv, *prog.expand(prog.p["headless"], ctx)], env, cwd, a.cpus)
            r["ok"] = r["ok"] and DONE.encode() in out
            firsts = [max((cwd / "fixture" / f).read_text().splitlines(), key=len).strip()
                      for f in sorted(os.listdir(cwd / "fixture"))]
    finally:
        del os.environ["FAKE_MODEL_DUMP"]
    reqs = [json.loads(p.read_bytes()) for p in sorted(dump.iterdir())]
    shutil.rmtree(dump, ignore_errors=True)
    last = reqs[-1] if reqs else {}
    msgs = last.get("messages", [])
    system = " ".join(m["content"] if isinstance(m.get("content"), str) else json.dumps(m.get("content"))
                      for m in msgs if m.get("role") in ("system", "developer"))
    results = json.dumps([m for m in msgs if m.get("role") == "tool"])
    r.update(requests=len(reqs), tools=sorted(t.get("function", {}).get("name", "?") for t in last.get("tools", [])),
             system_chars=len(system), last_request_kb=round(len(json.dumps(last)) / 1024, 1),
             files_read=sum(1 for f in firsts if json.dumps(f)[1:-1][:40] in results))
    return r


SCENARIOS = {"version": sc_version, "headless": sc_headless, "ready": sc_ready, "stream": sc_stream, "idle": sc_idle,
             "session": sc_session, "work": sc_work}
KEYS = {"version": ["wall_ms", "cpu_ms", "own_mb", "rss_mb", "instr_m"], "headless": ["wall_ms", "cpu_ms", "own_mb", "rss_mb", "instr_m"],
        "ready": ["tti_ms", "cpu_ms", "own_mb", "rss_mb", "instr_m"], "stream": ["wall_s", "cpu_s", "peak_own_mb", "own_end_mb", "instr_m"],
        "idle": ["cpu_ms_per_s", "own_mb"], "work": ["requests", "system_chars", "last_request_kb", "files_read"], "session": ["tti_ms", "loaded_ms", "own_mb", "rss_mb", "cpu_ms", "shown_kb"]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command, profile from --profiles by name (or name@profile=command)")
    ap.add_argument("--profiles", required=True)
    ap.add_argument("--scenarios", default="version,headless,ready,stream,idle,session")
    ap.add_argument("--rounds", type=int, default=5)
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--size", type=int, default=60000)
    ap.add_argument("--idle", type=float, default=30)
    ap.add_argument("--entries", type=int, default=4000)
    ap.add_argument("--cpus")
    ap.add_argument("--skip", action="append", default=[], help="scenario:build to leave out (n/a)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    profiles = json.loads(Path(a.profiles).read_text())
    specs, profs = [], {}
    for spec in a.build:
        name, cmd = spec.split("=", 1)
        prof = name
        if "@" in name:
            name, prof = name.split("@", 1)
        specs.append(f"{name}={cmd}")
        profs[name] = profiles.get(prof, {})
    progs = [Prog(b, profs[b.name]) for b in parse_builds(specs)]
    skip = set(a.skip)
    rows = []
    with open(a.out, "a") as f:
        for sc in a.scenarios.split(","):
            fn = SCENARIOS[sc]
            for i in range(a.warmup + a.rounds):
                order = progs[i % len(progs):] + progs[:i % len(progs)]
                for prog in order:
                    if f"{sc}:{prog.build.name}" in skip or sc in prog.p.get("skip", []):
                        continue
                    try:
                        r = fn(prog, a)
                    except Exception as e:  # noqa: BLE001
                        r = {"ok": False, "error": repr(e)[:300]}
                    if r is None:
                        continue
                    r.update(scenario=sc, build=prog.build.name, round=i - a.warmup)
                    print(json.dumps({k: (round(v, 3) if isinstance(v, float) else v) for k, v in r.items()}), file=sys.stderr, flush=True)
                    if i >= a.warmup:
                        rows.append(r)
                        f.write(json.dumps(r) + "\n")
                        f.flush()
    for sc in a.scenarios.split(","):
        print(f"\n{sc}")
        for prog in progs:
            rs = [r for r in rows if r["scenario"] == sc and r["build"] == prog.build.name]
            good = [r for r in rs if r["ok"]]
            if not rs:
                print(f"  {prog.build.name:14} n/a")
                continue
            cells = " ".join(f"{k}={median([r.get(k) for r in good]):.3f}" for k in KEYS[sc]
                             if median([r.get(k) for r in good]) is not None)
            print(f"  {prog.build.name:14} {cells}  ok {len(good)}/{len(rs)}")


if __name__ == "__main__":
    main()
