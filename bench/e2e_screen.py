#!/usr/bin/env python3
"""End-to-end check of what the terminal shows: an answer that uses every kind of Markdown and a code block in every language
Pi highlights (scripts/lib/training.md) streams into the TUI, in tmux, a few characters at a time, and the whole scrollback
(text and colors) must be what the reference build shows. The TUI renders a streaming message from what it kept of the last
render; this is the check that what it keeps changes nothing on screen.

Example: bench/e2e_screen.py --reference bun=./out/pi-stable/pi --build pi-bolt=./out/pi-bolt/pi
"""

import argparse
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import MODEL_ARGS, fake_model, parse_builds, pi_env, pi_home, workdir

TRAINING = Path(__file__).resolve().parent.parent / "scripts" / "lib" / "training.md"


def tmux(*args):
    return subprocess.run(["tmux", "-L", "pibolt-e2e-screen", *args], capture_output=True, text=True)


def screen(build, width, pace_ms):
    with fake_model(pace_ms=pace_ms, script="fake_model_stress.py") as port, pi_home(port) as home, workdir() as cwd:
        env = pi_env(home)
        tmux("kill-server")
        # (Not the alternate screen: the scrollback is the transcript.)
        command = " ".join(shlex.quote(x) for x in [*build.argv, "--no-session", "--tui-mode", "regular", *MODEL_ARGS])
        variables = []
        for name in ("PI_CODING_AGENT_DIR", "PI_OFFLINE", "PI_SKIP_VERSION_CHECK", "PI_TELEMETRY"):
            variables += ["-e", f"{name}={env[name]}"]
        tmux("new-session", "-d", "-x", str(width), "-y", "50", "-c", str(cwd), *variables, command)
        tmux("set-option", "-g", "history-limit", "200000")
        deadline = time.time() + 60
        while time.time() < deadline and "fake-model" not in tmux("capture-pane", "-p").stdout:
            time.sleep(0.2)
        tmux("send-keys", "-l", f"SCENARIO mdfile:{TRAINING} CWD /work")
        tmux("send-keys", "Enter")
        deadline = time.time() + 900
        done = False
        while time.time() < deadline and not done:
            time.sleep(0.5)
            done = "Done: mdfile, answer 1." in tmux("capture-pane", "-p").stdout
        time.sleep(1.5)
        text = tmux("capture-pane", "-p", "-e", "-S", "-", "-E", "-").stdout
        tmux("send-keys", "-l", "/quit")
        tmux("send-keys", "Enter")
        time.sleep(0.5)
        tmux("kill-server")
    # The working directory is in the footer, with a name of its own each time.
    text = re.sub(r"[^\s\x1b]*/pibolt-work-\w+", "<work>", text)  # (/tmp/... on Linux, /private/var/folders/... on macOS)
    # So is the share of the context used, which the system prompt counts in: it names the folder the build is installed in
    # (out/pi-stable, out/pi-bolt), a few characters that can move the rounded figure.
    return done, re.sub(r"\d+(?:\.\d+)?%/(\d+k)", r"<ctx>%/\1", text)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--reference", required=True, help="name=command of the build whose screen is taken as correct")
    ap.add_argument("--build", action="append", required=True, help="name=command to check (repeatable)")
    ap.add_argument("--widths", default="120,80", help="terminal widths to check at")
    ap.add_argument("--pace-ms", type=float, default=1, help="between chunks of 24 characters")
    a = ap.parse_args()
    reference = parse_builds([a.reference])[0]
    status = 0
    for width in [int(w) for w in a.widths.split(",")]:
        done, expected = screen(reference, width, a.pace_ms)
        if not done:
            sys.exit(f"the reference did not finish at width {width}")
        for build in parse_builds(a.build):
            done, actual = screen(build, width, a.pace_ms)
            if done and actual == expected:
                print(f"PASS {build.name}, {width} columns: {actual.count(chr(10))} lines, the same")
                continue
            status = 1
            a_lines, e_lines = actual.split("\n"), expected.split("\n")
            differing = [i for i in range(min(len(a_lines), len(e_lines))) if a_lines[i] != e_lines[i]]
            print(f"FAIL {build.name}, {width} columns: {'not finished, ' if not done else ''}{len(a_lines)} lines against {len(e_lines)}, {len(differing)} differ")
            for i in differing[:3]:
                print(f"   line {i + 1}: {e_lines[i]!r}\n        got: {a_lines[i]!r}")
    sys.exit(status)


if __name__ == "__main__":
    main()
