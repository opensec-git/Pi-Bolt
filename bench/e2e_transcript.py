#!/usr/bin/env python3
"""End-to-end check of a resumed transcript once Pi has been idle: Pi continues (`-c`) a session of N entries (long_transcript.py's)
in tmux, waits past the idle moment at which settled entries let go of what they were rendered from (and are put back as
fresh copies), and then does what makes entries render again: expand the tool output, hide and show thinking blocks, resize
the terminal and back, scroll, and run a prompt. After each step the screen (text and colors; in regular mode the whole
scrollback) must be what the reference build shows.

Example: bench/e2e_transcript.py --reference base=./out/base/pi --build pi-bolt=./out/pi-bolt/pi --entries 600
"""

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import MODEL_ARGS, fake_model, parse_builds, pi_env, pi_home, workdir
from long_transcript import write_session

SOCKET = "pibolt-e2e-transcript"


def tmux(*args):
    return subprocess.run(["tmux", "-L", SOCKET, *args], capture_output=True, text=True)


def screens(build, mode, entries, width):
    """The screens after each step, as (step, text)."""
    shown = []
    with fake_model(script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
        models = json.loads((home / "models.json").read_text())
        for provider in models["providers"].values():
            for model in provider["models"]:
                model["contextWindow"] = 100_000_000
        (home / "models.json").write_text(json.dumps(models))
        settings = json.loads((home / "settings.json").read_text())
        settings["tuiMode"] = mode
        (home / "settings.json").write_text(json.dumps(settings))
        real = str(Path(cwd).resolve())
        for folder in {str(cwd), real}:
            safe = "--" + re.sub(r"[/\\:]", "-", re.sub(r"^[/\\]", "", folder)) + "--"
            write_session(home / "sessions" / safe / "2026-01-01T00-00-00-000Z_t.jsonl", real, entries)
        env = pi_env(home)
        tmux("kill-server")
        command = " ".join(shlex.quote(x) for x in [*build.argv, *MODEL_ARGS, "-c"])
        variables = []
        for name in ("PI_CODING_AGENT_DIR", "PI_OFFLINE", "PI_SKIP_VERSION_CHECK", "PI_TELEMETRY"):
            variables += ["-e", f"{name}={env[name]}"]
        tmux("new-session", "-d", "-x", str(width), "-y", "40", "-c", str(cwd), *variables, command)
        tmux("set-option", "-g", "history-limit", "200000")
        deadline = time.time() + 120
        while time.time() < deadline and "fake-model" not in tmux("capture-pane", "-p").stdout:
            time.sleep(0.2)

        def capture(step, wait=1.5):
            time.sleep(wait)
            args = ["capture-pane", "-p", "-e"] + (["-S", "-", "-E", "-"] if mode == "regular" else [])
            shown.append((step, tmux(*args).stdout))

        def keys(*k):
            tmux("send-keys", *k)

        # (Past the idle compaction, and the highlight languages loading after the first frame.)
        capture("resumed, idle", 4)
        keys("C-o")
        capture("tools expanded")
        keys("C-t")
        capture("thinking hidden")
        keys("C-t")
        capture("thinking shown")
        keys("C-o")
        capture("tools collapsed", 3)
        tmux("resize-window", "-x", str(width - 30))
        capture("narrower", 3)
        tmux("resize-window", "-x", str(width))
        capture("width again", 3)
        if mode == "fullscreen":
            for _ in range(6):
                keys("PageUp")
            capture("scrolled up")
            for _ in range(6):
                keys("PageDown")
            capture("scrolled down")
        tmux("send-keys", "-l", "SCENARIO light CWD /work")
        keys("Enter")
        capture("after a prompt", 4)
        keys("C-o")
        capture("tools expanded again")
        tmux("send-keys", "-l", "/quit")
        keys("Enter")
        time.sleep(0.5)
        tmux("kill-server")
    # The working directory is in the footer, with a name of its own each time.
    return [(step, re.sub(r"[^\s\x1b]*/pibolt-work-\w+", "<work>", text)) for step, text in shown]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reference", required=True, help="name=command of the build whose screens are taken as correct")
    ap.add_argument("--build", action="append", required=True, help="name=command to check (repeatable)")
    ap.add_argument("--entries", type=int, default=600)
    ap.add_argument("--modes", default="fullscreen,regular")
    ap.add_argument("--width", type=int, default=120)
    a = ap.parse_args()
    reference = parse_builds([a.reference])[0]
    status = 0
    for mode in a.modes.split(","):
        expected = screens(reference, mode, a.entries, a.width)
        for build in parse_builds(a.build):
            actual = screens(build, mode, a.entries, a.width)
            bad = [step for (step, text), (_, want) in zip(actual, expected) if text != want]
            if len(actual) != len(expected):
                bad.append("number of steps")
            if bad:
                status = 1
                print(f"FAIL {build.name}, {mode}: {', '.join(bad)}")
            else:
                print(f"PASS {build.name}, {mode}: {len(actual)} screens the same")
    sys.exit(status)


if __name__ == "__main__":
    main()
