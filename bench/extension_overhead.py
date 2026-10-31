#!/usr/bin/env python3
"""Extension overhead: what the extensions in an agent directory cost per prompt.

Every other tool here runs against a throwaway Pi home holding only the fake model, so they
measure the build and nothing else. That is the right default for comparing builds, but it
cannot answer the question a user with a configured Pi actually has: what do *my* extensions
cost? Extension hooks fire on every prompt, every turn and every tool call, so their cost
scales with the session, not with startup.

Two modes:

  --agent-dir DIR   copy DIR into each run's Pi home (settings.json, models.json and auth.json
                    are still replaced with the fake model, so nothing real is contacted).
                    This is the "what do my extensions cost" mode.
  (default)         no extensions, the same throwaway home every other tool uses. Use it as the
                    baseline column, or to confirm the tool itself costs nothing.

Example:

    bench/extension_overhead.py \\
        --build pi-bolt=out/pi-bolt/pi \\
        --agent-dir ~/.pi/agent --prompts 40

Reports per prompt: wall, CPU, and the request body size the provider is charged for. Request
size is included because an extension that rewrites the system prompt every turn costs tokens
whether or not the model reads them, and that cost does not show up in wall time at all.
"""

import argparse
import contextlib
import json
import os
import shutil
import statistics
import sys
import tempfile
import time
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import (  # noqa: E402
    MODEL_ARGS, PROMPT, Tty, cpu_ms, done, fake_model, memory_mb, parse_builds, pi_env, pinned, workdir,
)

# Copied from an agent directory into the run's home. Only what an extension can read: the
# extension tree itself plus the config it consults. Credentials are deliberately excluded and
# replaced below, so a real provider is never contacted and no secret enters a run.
COPY = ("extensions", "AGENTS.md", "orchestrator.json", "chain.json")
SKIP = ("node_modules", ".pi", "*.bak*", "sessions", "logs", "memory")


@contextlib.contextmanager
def extension_home(port: int, agent_dir: Optional[Path]):
    """A Pi home that is the throwaway default, or that default plus the given extensions."""
    home = Path(tempfile.mkdtemp(prefix="pibolt-ext-"))
    models = [{"id": "fake-model", "contextWindow": 200000, "maxTokens": 8192}]
    (home / "models.json").write_text(json.dumps({"providers": {
        "fake": {"baseUrl": f"http://127.0.0.1:{port}/v1", "api": "openai-completions", "apiKey": "fake", "models": models},
    }}, indent=2))
    (home / "settings.json").write_text(json.dumps({"lastChangelogVersion": "9999.0.0", "theme": "dark"}))
    (home / "auth.json").write_text("{}")
    if agent_dir:
        for name in COPY:
            src = agent_dir / name
            if not src.exists():
                continue
            dest = home / name
            if src.is_dir():
                shutil.copytree(src, dest, symlinks=True, ignore=shutil.ignore_patterns(*SKIP))
            else:
                shutil.copy2(src, dest)
    try:
        yield home
    finally:
        shutil.rmtree(home, ignore_errors=True)


def session(build, prompts, every, cpus, agent_dir):
    sizes = tempfile.NamedTemporaryFile(prefix="pibolt-ext-sizes-", delete=False).name
    rows = []
    try:
        with fake_model(log_sizes=sizes) as port, extension_home(port, agent_dir) as home, workdir() as cwd:
            tty = Tty(pinned([*build.argv, "--no-session", *MODEL_ARGS], cpus), pi_env(home), cwd)
            deadline = time.perf_counter() + 3600
            if not tty.wait_for("fake-model", 0, deadline):
                return [{"error": "never became interactive"}]
            tty.settle(0.1, deadline)
            t_block, c_block = time.perf_counter(), cpu_ms(tty.pid)
            prev_bytes = int(Path(sizes).read_text().split()[-1]) if os.path.getsize(sizes) else 0
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
                    cur = int(Path(sizes).read_text().split()[-1]) if os.path.getsize(sizes) else 0
                    rows.append({
                        "prompt": i,
                        "ms_per_prompt": round((now - t_block) * 1000 / every, 1),
                        "cpu_ms_per_prompt": round((c - c_block) / every, 1),
                        "rss_mb": m.get("rss"),
                        "own_mb": m.get("own"),
                        "request_mb": round((cur - prev_bytes) / 1e6 / every, 3),
                    })
                    prev_bytes = cur
            tty.quit()
    finally:
        try:
            os.unlink(sizes)
        except OSError:
            pass
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True, help="name=command that starts Pi (repeatable)")
    ap.add_argument("--agent-dir", help="copy this agent directory's extensions into every run (e.g. ~/.pi/agent)")
    ap.add_argument("--prompts", type=int, default=20)
    ap.add_argument("--every", type=int, default=5, help="report every N prompts")
    ap.add_argument("--cpus", help="pin every run to these cores (taskset list, e.g. 8-15)")
    ap.add_argument("--out", help="append raw results to this JSONL file")
    args = ap.parse_args()

    agent_dir = Path(os.path.expanduser(args.agent_dir)) if args.agent_dir else None
    if agent_dir and not agent_dir.is_dir():
        sys.exit(f"--agent-dir is not a directory: {agent_dir}")

    print(f"extensions: {agent_dir if agent_dir else 'none (throwaway home)'}")
    print(f"prompts: {args.prompts}, reporting every {args.every}\n")
    print(f"{'build':<22}{'prompt':>7}{'ms/prompt':>12}{'cpu/prompt':>12}{'rss_mb':>9}{'req_mb':>9}")

    for build in parse_builds(args.build):
        rows = session(build, args.prompts, args.every, args.cpus, agent_dir)
        for r in rows:
            if "error" in r:
                print(f"{build.name:<22}{'-- ' + r['error'] + ' --':>40}")
                continue
            print(f"{build.name:<22}{r['prompt']:>7}{r['ms_per_prompt']:>12.1f}"
                  f"{r['cpu_ms_per_prompt']:>12.1f}{r['rss_mb'] or 0:>9.0f}{r['request_mb']:>9.3f}")
        if args.out:
            with open(args.out, "a") as fh:
                for r in rows:
                    fh.write(json.dumps({"build": build.name, "agent_dir": str(agent_dir) if agent_dir else None, **r}) + "\n")
        valid = [r for r in rows if "cpu_ms_per_prompt" in r]
        if valid:
            print(f"{'  median':<22}{'':>7}{statistics.median([r['ms_per_prompt'] for r in valid]):>12.1f}"
                  f"{statistics.median([r['cpu_ms_per_prompt'] for r in valid]):>12.1f}\n")


if __name__ == "__main__":
    main()
