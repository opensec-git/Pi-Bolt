#!/usr/bin/env python3
"""Exercise optional extension installs without downloads or a real agent directory.

Run with: python3 tests/installer/extensions.py
"""

import hashlib
import json
import os
from pathlib import Path
import select
import subprocess
import tarfile
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

    @unittest.skipIf(os.name == "nt", "POSIX installer")
    def test_standalone_jit_command_alongside_default(self):
        with tempfile.TemporaryDirectory(prefix="installer-jit-") as directory:
            root = Path(directory).resolve()
            runner = root / "install.sh"
            functions = (ROOT / "install.sh").read_text().rsplit('main "$@"', 1)[0]
            runner.write_text(functions + '\nuname() { case "$1" in -s) echo Darwin ;; -m) echo arm64 ;; esac; }\n'
                              'sw_vers() { echo 13; }\nmain "$@"\n')
            env = {k: v for k, v in os.environ.items() if not k.startswith("PIBOLT_")}
            env.update(HOME=str(root), PIBOLT_EXTENSIONS="no", PIBOLT_NO_START="1", PIBOLT_YES="1")
            primary = root / ".local/bin/pi-bolt"
            jit = root / ".pi-bolt/pi-bolt-jit"

            # Standalone installers can test local fixtures from before signing with a pinned checksum list.
            def release(version):
                mirror = root / version
                mirror.mkdir()
                sums = f"# pi-bolt {version}\n"
                for variant in ("arm64", "arm64-jit"):
                    name = f"pi-bolt-darwin-{variant}"
                    build = mirror / name
                    build.mkdir()
                    binary = build / "pi"
                    binary.write_text('#!/bin/sh\ncase "$1" in list|service) echo "$PWD|$0|$*" >>"$HOME/commands";; esac\n'
                                      'if [ "$1" = service ]; then exit 7; fi\n'
                                      f'echo "{version} {variant}"\n')
                    binary.chmod(0o755)
                    (build / "pi-bolt.txt").write_text(f"Pi-Bolt {version} fixture\n")
                    archive = mirror / f"{name}.tar.gz"
                    with tarfile.open(archive, "w:gz") as tar:
                        tar.add(build, arcname=name)
                    sums += f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n"
                (mirror / "SHA256SUMS").write_text(sums)
                env.update(PIBOLT_VERSION=f"bolt-v{version}", PIBOLT_DOWNLOAD_BASE=mirror.as_uri(),
                           PIBOLT_UNVERIFIED=hashlib.sha256(sums.encode()).hexdigest())

            def install(build, restart=True, **options):
                log = root / "commands"
                log.write_text("")
                result = subprocess.run(["sh", str(runner)], env={**env, **options}, text=True,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
                prefix = f"{root}/.pi-bolt|{root}/.pi-bolt/pi-bolt-darwin-{build}/pi|"
                commands = ["list", "service restart"] if restart else ["list"]
                self.assertEqual(log.read_text().splitlines(), [prefix + command for command in commands])
                return result.stdout

            release("0.5.90")
            install("arm64")
            original = primary.resolve(strict=True)
            self.assertEqual(original.parent.name, "pi-bolt-darwin-arm64")
            self.assertFalse(jit.exists())
            jit.write_text("existing file")
            self.assertIn("Use the JIT executable directly.",
                          install("arm64-jit", restart=False, PIBOLT_VARIANT="jit", PIBOLT_NO_PATH="1"))
            self.assertEqual(jit.read_text(), "existing file")
            jit.unlink()
            for version, variant in [("0.5.90", "jit"), ("0.5.91", "arm64-jit")]:  # Updater passes the concrete variant.
                if version == "0.5.91":
                    release(version)
                output = install("arm64-jit", restart=False, PIBOLT_VARIANT=variant, PIBOLT_NO_PATH="1")
                self.assertIn("Run it with: ~/.pi-bolt/pi-bolt-jit", output)
                self.assertEqual(primary.resolve(strict=True), original)
                self.assertEqual(jit.resolve(strict=True).parent.name, "pi-bolt-darwin-arm64-jit")
                self.assertEqual(subprocess.check_output([jit, "--version"], text=True).strip(), f"{version} arm64-jit")
            previous = jit.resolve().parent.with_suffix(".previous") / "pi"
            self.assertEqual(subprocess.check_output([previous, "--version"], text=True).strip(), "0.5.90 arm64-jit")
            install("arm64", PIBOLT_VARIANT="arm64", PIBOLT_NO_PATH="1")  # Updating the primary restarts it.
            install("arm64-jit", PIBOLT_VARIANT="jit")
            self.assertEqual(primary.resolve(strict=True), jit.resolve(strict=True))
            install("arm64-jit", PIBOLT_VARIANT="arm64-jit", PIBOLT_NO_PATH="1")  # JIT is now the primary.
            output = run_in_terminal(["/bin/sh", str(runner)], root, {**env, "PIBOLT_YES": "0", "PIBOLT_VARIANT": "jit"},
                                     [("Choose an action", "u")])
            self.assertIn("Pi-Bolt was uninstalled.", output)
            self.assertFalse(jit.is_symlink())
            self.assertFalse(primary.is_symlink())
            self.assertFalse(previous.exists())

    @unittest.skipIf(os.name == "nt", "POSIX installer")
    def test_linux_path_command_follows_the_active_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            install = root / "install's $(echo injected)"
            package = install / "pi-bolt-linux-x64"
            package.mkdir(parents=True)
            (package / "pi-bolt.txt").write_text("Pi-Bolt 1.3.0 test\n")
            binary = package / "pi"
            binary.write_text('#!/usr/bin/env python3\nimport json, os, sys\n'
                              'print(json.dumps([sys.argv, os.getcwd(), os.getpid(), os.environ.get("MARKER")]))\n')
            binary.chmod(0o755)
            runner = root / "installer"
            source = (ROOT / "install.sh").read_text().rsplit('main "$@"', 1)[0]
            # Supply an already extracted local package. Exercise the normal post-install and menu paths.
            runner.write_text(source + '\nuname() { case "$1" in -s) echo Linux;; -m) echo x86_64;; esac; }\n'
                              'is_musl() { return 1; }\npreflight() { :; }\ninstall_release() { :; }\nmain "$@"\n')
            env = {"HOME": str(root), "PATH": os.environ["PATH"], "MARKER": "passed through",
                   "PIBOLT_INSTALL": str(install), "PIBOLT_VERSION": "bolt-v1.3.0", "PIBOLT_VARIANT": "x64",
                   "PIBOLT_YES": "1", "PIBOLT_NO_START": "1", "PIBOLT_EXTENSIONS": "no"}
            public = root / ".local/bin/pi-bolt"
            def reinstall(**extra):
                subprocess.run(["sh", runner], cwd=root, env={**env, **extra}, check=True, stdout=subprocess.PIPE)
            def check_run(expected):
                args = ["", "two words", "π"]
                with subprocess.Popen([public, *args], cwd=root, env=env, stdout=subprocess.PIPE) as proc:
                    output, _ = proc.communicate(timeout=5)
                    self.assertEqual(proc.returncode, 0)
                    self.assertEqual(json.loads(output), [[str(expected), *args], str(root), proc.pid, env["MARKER"]])
            reinstall()
            self.assertEqual(public.resolve(), binary)
            launcher = package / "pi-start"
            launcher.write_bytes(binary.read_bytes())
            launcher.chmod(0o755)
            reinstall(PIBOLT_NO_PATH="1")
            self.assertEqual(public.resolve(), binary)
            reinstall()
            entry = public.resolve()
            check_run(launcher)
            launcher.unlink()
            check_run(binary)
            reinstall(PIBOLT_NO_PATH="1")
            self.assertEqual(public.resolve(), entry)
            launcher.write_bytes(binary.read_bytes())
            launcher.chmod(0o755)
            check_run(launcher)
            output = run_in_terminal(["/bin/sh", str(runner)], root, {**env, "PIBOLT_YES": "0"},
                                     [("Choose an action", "u")])
            self.assertIn("Pi-Bolt was uninstalled.", output)
            self.assertFalse(entry.exists())
            self.assertFalse(public.is_symlink())

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
