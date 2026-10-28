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
import statistics
import subprocess
import sys
import shutil
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness
from harness import (ANSI, DONE, MACOS, PROMPT, Tty, done, fake_model, maxrss_mb, median, parse_builds, peak_footprint_mb, pi_env,
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


# Keys typed while an answer streams: each one a letter not typed before and in no answer (Latin Extended, U+0100 on), so that its echo is
# that character showing up, whether a program redraws the whole editor line or only the cell that changed.
KEYS_TYPED = [chr(c) for c in range(0x100, 0x250) if chr(c).isalpha()]


def echo_while(tty, start, needle, typed_every, deadline):
    """Pumps until `needle` is on screen after `start`, typing a key every `typed_every` seconds; returns the times (ms) until
    each key showed, or None if `needle` never came."""
    latencies, next_key, scan = [], time.perf_counter() + 1.0, start
    while time.perf_counter() < deadline:
        tty.pump(0.005)
        if needle in ANSI.sub(b"", tty.buf[scan:]):
            return latencies
        scan = max(start, len(tty.buf) - 8192)
        if time.perf_counter() >= next_key and len(latencies) < len(KEYS_TYPED):
            key = KEYS_TYPED[len(latencies)].encode()
            sent, mark = time.perf_counter(), len(tty.buf)
            tty.send(key)
            while time.perf_counter() < sent + 5 and key not in tty.buf[mark:]:
                tty.pump(0.002)
            latencies.append(1000 * (time.perf_counter() - sent))
            next_key = sent + typed_every
    return None


def pct(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * q))] if values else None


def sc_interactive(prog, a):
    """Day to day: the TUI, 5 prompts (each 4 reads and an answer) one after another."""
    with fake_model() as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["tui"])
        r = {"ok": False, "tti_ms": tti}
        if ok:
            tty.settle(0.05, time.perf_counter() + 10)
            turns, t_all = 0.0, time.perf_counter()
            for n in range(1, 6):
                start, t = len(tty.buf), time.perf_counter()
                tty.send(PROMPT.encode() + prog.p["submit"].encode())
                if not tty.wait_for(done(n), start, time.perf_counter() + 300):
                    break
                turns += (time.perf_counter() - t) * 1e3
                tty.settle(0.03, time.perf_counter() + 10)
            else:
                s1 = tree.snapshot()
                r.update(ok=True, turns_ms=turns, cpu_ms=s1["cpu_ms"], peak_own_mb=s1["peak_own_mb"], own_mb=s1["own_mb"])
                if MACOS:
                    r["instr_m"] = s1["instr_m"]
        if not r["ok"]:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def sc_typing(prog, a):
    """Day to day: a 20,000-character answer streams while the user types a key every 0.25 s: how long each takes to show."""
    with fake_model(pace_ms=20, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["tui"], cols=160, rows=48)
        r = {"ok": False}
        if ok:
            tty.settle(0.5, time.perf_counter() + 5)
            start, s0 = len(tty.buf), tree.snapshot()
            tty.send(f"SCENARIO md:20000 CWD {cwd}".encode() + prog.p["submit"].encode())
            lat = echo_while(tty, start, b"Done: md, answer 1.", 0.25, time.perf_counter() + 600)
            if lat:
                s1 = tree.snapshot()
                r.update(ok=True, echo_ms=statistics.median(lat), echo_p95_ms=pct(lat, 0.95), echo_max_ms=max(lat),
                         keys=len(lat), cpu_s=(s1["cpu_ms"] - s0["cpu_ms"]) / 1e3)
                if MACOS:
                    r["instr_m"] = s1["instr_m"] - s0["instr_m"]
        if not r["ok"]:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def sc_longsession(prog, a):
    """Heavy use: one process, --prompts prompts (each 4 reads and an answer; the conversation grows by the four files each
    time): CPU and time per prompt over the last 5, memory at the end, and the size of the last request (a program that
    compacts sends less)."""
    sizes = tempfile.NamedTemporaryFile(prefix="forkbench-sizes-", delete=False).name
    with fake_model(log_sizes=sizes) as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["tui"])
        r = {"ok": False}
        if ok:
            tty.settle(0.1, time.perf_counter() + 10)
            marks = []
            for n in range(1, a.prompts + 1):
                start, t = len(tty.buf), time.perf_counter()
                tty.send(PROMPT.encode() + prog.p["submit"].encode())
                if not tty.wait_for(done(n), start, time.perf_counter() + 600):
                    r["stopped_at"] = n
                    break
                tty.settle(0.03, time.perf_counter() + 10)
                marks.append((time.perf_counter() - t, tree.snapshot()))
            else:
                last = marks[-6:]
                s1 = last[-1][1]
                r.update(ok=True, ms_per_prompt=statistics.median(m[0] for m in last[1:]) * 1e3,
                         cpu_ms_per_prompt=(s1["cpu_ms"] - last[0][1]["cpu_ms"]) / 5, own_mb=s1["own_mb"],
                         peak_own_mb=s1["peak_own_mb"], cpu_s_total=s1["cpu_ms"] / 1e3)
                if MACOS:
                    r["instr_m_per_prompt"] = (s1["instr_m"] - last[0][1]["instr_m"]) / 5
        with contextlib.suppress(OSError, ValueError):
            r["last_request_kb"] = round(int(open(sizes).read().split()[-1]) / 1024)
        if not r["ok"]:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
    os.unlink(sizes)
    return r


def sc_resumed(prog, a):
    """Heavy use: continue a 2,292-entry Pi session (a day of work) and stream a 6,000-character answer while typing:
    CPU of the answer and how long keys take to show."""
    if not prog.p.get("session_dir"):
        return None
    with fake_model(pace_ms=20, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
        env, ctx = prog.setup(home, port, cwd)
        big_context(home)
        real = str(Path(cwd).resolve())
        for folder in {str(cwd), real}:
            safe = "--" + re.sub(r"[/\\:]", "-", re.sub(r"^[/\\]", "", folder)) + "--"
            write_session(Path(prog.p["session_dir"].format(**ctx, safe=safe)) / "2026-01-01T00-00-00-000Z_t.jsonl", real, 2292)
        tty, tree, ok, tti = tui(prog, env, ctx, cwd, a.cpus, prog.p["continue"], cols=160, rows=48)
        r = {"ok": False}
        if ok:
            quiet(tty, tree)
            start, s0 = len(tty.buf), tree.snapshot()
            tty.send(f"SCENARIO md:6000 CWD {cwd}".encode() + prog.p["submit"].encode())
            lat = echo_while(tty, start, b"Done: md, answer", 0.25, time.perf_counter() + 600)
            if lat is not None:
                s1 = tree.snapshot()
                r.update(ok=True, cpu_s=(s1["cpu_ms"] - s0["cpu_ms"]) / 1e3, echo_ms=statistics.median(lat) if lat else None,
                         echo_p95_ms=pct(lat, 0.95), keys=len(lat), own_mb=s1["own_mb"])
                if MACOS:
                    r["instr_m"] = s1["instr_m"] - s0["instr_m"]
        if not r["ok"]:
            r["screen"] = tty.screen_text()[-400:]
        kill(tty, tree)
        return r


def big_context(home):
    """A context window the long history fits in (no compaction), in Pi's models.json and OMP's models.yml."""
    models = json.loads((home / "models.json").read_text())
    for provider in models["providers"].values():
        for model in provider["models"]:
            model["contextWindow"] = 100_000_000
    (home / "models.json").write_text(json.dumps(models))
    (home / "models.yml").write_text(json.dumps(models))


def quiet(tty, tree, limit=120):
    """Until the whole tree used under 3% of a core for half a second (the history read): returns when that began."""
    c, deadline = tree.snapshot()["cpu_ms"], time.perf_counter() + limit
    while time.perf_counter() < deadline:
        quiet_from = time.perf_counter()
        while time.perf_counter() < quiet_from + 0.5:
            tty.pump(0.05)
        c1 = tree.snapshot()["cpu_ms"]
        if c1 - c < 15:
            break
        c = c1
    return quiet_from


SCENARIOS = {"version": sc_version, "headless": sc_headless, "ready": sc_ready, "stream": sc_stream, "idle": sc_idle,
             "session": sc_session, "work": sc_work, "interactive": sc_interactive,
             "typing": sc_typing, "longsession": sc_longsession, "resumed": sc_resumed}
KEYS = {"version": ["wall_ms", "cpu_ms", "own_mb", "rss_mb", "instr_m"], "headless": ["wall_ms", "cpu_ms", "own_mb", "rss_mb", "instr_m"],
        "ready": ["tti_ms", "cpu_ms", "own_mb", "rss_mb", "instr_m"], "stream": ["wall_s", "cpu_s", "peak_own_mb", "own_end_mb", "instr_m"],
        "idle": ["cpu_ms_per_s", "own_mb"], "work": ["requests", "system_chars", "last_request_kb", "files_read"],
        "interactive": ["tti_ms", "turns_ms", "cpu_ms", "peak_own_mb", "instr_m"], "typing": ["echo_ms", "echo_p95_ms", "echo_max_ms", "cpu_s", "instr_m"],
        "longsession": ["ms_per_prompt", "cpu_ms_per_prompt", "own_mb", "peak_own_mb", "last_request_kb", "instr_m_per_prompt"],
        "resumed": ["cpu_s", "echo_ms", "echo_p95_ms", "own_mb", "instr_m"], "session": ["tti_ms", "loaded_ms", "own_mb", "rss_mb", "cpu_ms", "shown_kb"]}


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
    ap.add_argument("--prompts", type=int, default=40)
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
