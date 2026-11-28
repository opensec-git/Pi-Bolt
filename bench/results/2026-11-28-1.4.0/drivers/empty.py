"""Measure the first painted keystrokes with a genuinely empty user home, empty project and no model or credentials."""
import argparse
import itertools
import json
import os
from pathlib import Path
import random
import signal
import sys
import tempfile
import termios
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness

def run(build):
    with tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as cwd:
        env = dict(HOME=home, PATH="/opt/homebrew/bin:/usr/bin:/bin", TERM="xterm-256color", LC_ALL="C",
                   PI_OFFLINE="1", PIG_OFFLINE="1", PI_TELEMETRY="0", PI_SKIP_VERSION_CHECK="1")
        start = time.perf_counter()
        tty = harness.Tty([*build.argv, "--no-session"], env, cwd)
        deadline = start + 6
        try:
            while time.perf_counter() < deadline:
                tty.pump(max(0, deadline - time.perf_counter()))
                if tty.buf and not termios.tcgetattr(tty.fd)[3] & (termios.ECHO | termios.ICANON):
                    break
            else:
                raise AssertionError(tty.screen_text()[-900:])
            onboarding_ms = None
            if build.name == "pig":
                assert tty.wait_for("skip setup", 0, deadline), tty.screen_text()[-900:]
                onboarding_ms = (time.perf_counter() - start) * 1000
                offset = len(tty.buf)
                tty.send(b"\x03")
                # PiG can paint while handing the terminal from onboarding to the editor.
                # Wait for output and raw mode together so kernel echo cannot pass the typing check.
                while len(tty.buf) == offset or termios.tcgetattr(tty.fd)[3] & (termios.ECHO | termios.ICANON):
                    remaining = deadline - time.perf_counter()
                    if tty.eof or remaining <= 0:
                        raise AssertionError(tty.screen_text()[-900:])
                    tty.pump(remaining if len(tty.buf) == offset else min(.002, remaining))
            offset = len(tty.buf)
            assert not termios.tcgetattr(tty.fd)[3] & (termios.ECHO | termios.ICANON)
            marker = b"typingprobe"
            tty.send(marker)
            while marker not in tty.buf[offset:]:
                if tty.eof or time.perf_counter() >= deadline:
                    raise AssertionError(tty.screen_text()[-900:])
                tty.pump(max(0, deadline - time.perf_counter()))
            return {"editor_ms": (time.perf_counter() - start) * 1000, "onboarding_ms": onboarding_ms}
        finally:
            # The probe is never submitted. Reap this disposable child after seeing its rendered edit.
            try:
                os.kill(tty.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            os.waitpid(tty.pid, 0)
            os.close(tty.fd)

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--build", action="append", required=True)
    p.add_argument("--rounds", type=int, default=30)
    args = p.parse_args()
    builds = harness.parse_builds(args.build)
    orders = list(itertools.permutations(builds))
    rng = random.Random(42)
    for i in range(args.rounds):
        if i % len(orders) == 0:
            rng.shuffle(orders)
        for build in orders[i % len(orders)]:
            print(json.dumps(dict(trial=i, build=build.name, **run(build))), flush=True)
