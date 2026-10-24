#!/usr/bin/env python3
"""Exercise optional extension installs without downloads or a real agent directory.

Run with: python3 tests/installer/extensions.py
"""

import os
from pathlib import Path
import select
import subprocess
import tempfile
import time
import unittest

if os.name != "nt":
    import pty


ROOT = Path(__file__).resolve().parents[2]
PACKAGES = ("opensec-pi-subagents", "opensec-pi-todo", "opensec-pi-fff")


def run_in_terminal(command, cwd, env, typed, timeout=10):
    """Run command on a pseudo-terminal of its own (its /dev/tty), typing each (prompt, keys[, delay]) entry's keys one at a
    time once prompt is on screen, as a person would (delay seconds before each key, 0.1 by default). Returns the output."""
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(cwd)
        os.execve(command[0], command, env)
    out = b""
    pending = list(typed)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if pending and pending[0][0].encode() in out:
            _, keys, *rest = pending.pop(0)
            for key in keys:
                time.sleep(rest[0] if rest else 0.1)
                os.write(fd, key.encode())
        ready, _, _ = select.select([fd], [], [], 0.1)
        if not ready:
            continue
        try:
            chunk = os.read(fd, 4096)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
    else:
        os.kill(pid, 9)
    os.waitpid(pid, 0)
    return out.decode("utf-8", "replace")


# Regression for #28: optional Bun extensions must not enter the shared package list.
class ExtensionInstallTests(unittest.TestCase):
    # Regression for #28: source checkouts must still support direct installer execution.
    @unittest.skipIf(os.name == "nt", "POSIX executable permissions")
    def test_installer_is_executable(self):
        self.assertTrue(os.access(ROOT / "install.sh", os.X_OK))

    def run_offer(self, integrity="same", install_status=0, choice="yes", typed=None):
        with tempfile.TemporaryDirectory(prefix="installer-extensions-") as directory:
            root = Path(directory)
            binary_dir = root / "build"
            binary_dir.mkdir()
            binary = binary_dir / "pi"
            binary.write_text('''#!/bin/sh
if [ "$1" = list ]; then exit 0; fi
printf '%s\\n' "$*" >> "$COMMAND_LOG"
if [ "$1" = install ]; then exit "$INSTALL_STATUS"; fi
''')
            binary.chmod(0o755)
            installer = (ROOT / "install.sh").read_text()
            self.assertTrue(installer.rstrip().endswith('main "$@"'))
            # Load the real installer functions without installing the executable itself.
            functions = installer.rsplit('main "$@"', 1)[0]
            runner = root / "offer.sh"
            runner.write_text(functions + '''
set -eu
setup_style
INSTALL=$TEST_INSTALL
NAME=build
PINS="opensec-pi-subagents 1.0.0 sha512-test
opensec-pi-todo 1.0.0 sha512-test
opensec-pi-fff 1.0.0 sha512-test"
pinned_tarball_matches() { return 0; }
installed_matches() { printf '%s\\n' "$TEST_INTEGRITY"; }
# (The menu's key, then a second for the download, while which the Enter typed after the key arrives.)
if [ -n "${TEST_MENU:-}" ]; then printf 'Choose an action\\n'; key=$(read_key); sleep 1; fi
offer_extensions
''')
            log = root / "commands"
            if typed is not None:
                # Asked at a terminal, after the menu's single key, as install.sh does.
                env = {name: value for name, value in os.environ.items() if not name.startswith("PIBOLT_")}
                env.update(TEST_INSTALL=str(root), COMMAND_LOG=str(log), INSTALL_STATUS=str(install_status),
                           TEST_INTEGRITY=integrity, TEST_MENU="1")
                output = run_in_terminal(["/bin/sh", str(runner)], root, env, typed)
                return log.read_text().splitlines() if log.exists() else [], output
            result = subprocess.run(
                ["sh", str(runner)],
                cwd=root,
                env={
                    **os.environ,
                    "TEST_INSTALL": str(root),
                    "COMMAND_LOG": str(log),
                    "INSTALL_STATUS": str(install_status),
                    "TEST_INTEGRITY": integrity,
                    "PIBOLT_EXTENSIONS": choice,
                    "PIBOLT_YES": "1",
                },
                capture_output=True,
                text=True,
                timeout=10,
                check=True,
            )
            return log.read_text().splitlines() if log.exists() else [], result.stdout

    def test_installs_only_into_explicit_pi_bolt_scope(self):
        commands, _ = self.run_offer()
        self.assertEqual(commands, [f"install --pi-bolt npm:{name}@1.0.0" for name in PACKAGES])

    def test_integrity_failure_removes_from_the_same_scope(self):
        commands, _ = self.run_offer(integrity="different")
        self.assertEqual(commands, [
            f"{action} --pi-bolt npm:{name}@1.0.0"
            for name in PACKAGES for action in ("install", "remove")
        ])

    def test_failed_install_guidance_keeps_explicit_scope(self):
        commands, output = self.run_offer(install_status=1)
        self.assertEqual(commands, [f"install --pi-bolt npm:{name}@1.0.0" for name in PACKAGES])
        for name in PACKAGES:
            self.assertIn(f"Install it later with: pi-bolt install --pi-bolt npm:{name}", output)

    def test_opt_out_does_not_install(self):
        commands, _ = self.run_offer(choice="no")
        self.assertEqual(commands, [])

    # Regression for #103: the Enter typed after the menu's key must not answer "Install them?".
    @unittest.skipIf(os.name == "nt", "POSIX terminal")
    def test_no_typed_after_the_menu_does_not_install(self):
        commands, output = self.run_offer(choice="", typed=[("Choose an action", "y\r"), ("Install them?", "n\r")])
        self.assertEqual(commands, [], output)
        self.assertIn("You can install them later", output)

    # Regression for #103: an answer typed the moment the question appears is the answer, not type-ahead.
    @unittest.skipIf(os.name == "nt", "POSIX terminal")
    def test_no_typed_as_soon_as_asked_does_not_install(self):
        commands, output = self.run_offer(choice="", typed=[("Choose an action", "y\r"), ("Install them?", "n\r", 0)])
        self.assertEqual(commands, [], output)
        self.assertIn("You can install them later", output)

    @unittest.skipIf(os.name == "nt", "POSIX terminal")
    def test_enter_or_yes_typed_after_the_menu_installs(self):
        for answer in ("\r", "y\r"):
            commands, output = self.run_offer(choice="", typed=[("Choose an action", "y\r"), ("Install them?", answer)])
            self.assertEqual(commands, [f"install --pi-bolt npm:{name}@1.0.0" for name in PACKAGES], output)


if __name__ == "__main__":
    unittest.main()
