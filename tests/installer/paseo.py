#!/usr/bin/env python3
"""Test Paseo registration with isolated homes, no downloads and no running daemon.

Run with: python3 tests/installer/paseo.py
Node runs the installer's embedded JavaScript in place of the release's Bun runtime.
Set PIBOLT_TEST_BINARY to a release's pi executable to test that runtime instead.
"""

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


@unittest.skipIf(os.name == "nt", "POSIX installer")
class PaseoInstallTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="installer-paseo-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.paseo = self.home / ".paseo"
        self.config = self.paseo / "config.json"
        # Quotes, backslashes and whitespace must remain data in shell and JSON.
        self.bin = self.root / "bin space ' quote \" dollar $ backslash \\"
        self.bin.mkdir()
        self.build = self.root / "build"
        self.build.mkdir()
        runtime = os.environ.get("PIBOLT_TEST_BINARY") or shutil.which("node")
        self.assertIsNotNone(runtime, "Node or PIBOLT_TEST_BINARY is required")
        for name in ("pi", "pi-termux"):
            binary = self.build / name
            binary.write_text(
                '#!/bin/sh\n[ "$BUN_BE_BUN" = 1 ] || exit 2\n'
                'printf "%s\\n" "$0" >> "$RUNTIME_LOG"\n'
                f'exec {shlex.quote(runtime)} "$@"\n'
            )
            binary.chmod(0o755)
        # Detect accidental attempts to contact or restart a real daemon.
        paseo = self.bin / "paseo"
        paseo.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$PASEO_LOG"\nexit 1\n')
        paseo.chmod(0o755)
        self.runner = self.root / "offer.sh"
        installer = (ROOT / "install.sh").read_text()
        self.assertTrue(installer.rstrip().endswith('main "$@"'))
        self.functions = installer.rsplit('main "$@"', 1)[0]
        self.env = {
            key: value for key, value in os.environ.items()
            if not key.startswith(("PIBOLT_", "PASEO_"))
        }
        self.env.update(
            HOME=str(self.home), TEST_INSTALL=str(self.root), TEST_BIN=str(self.bin),
            RUNTIME_LOG=str(self.root / "runtime.log"), PASEO_LOG=str(self.root / "paseo.log"),
            PATH=f"{self.bin}:{os.environ['PATH']}", PIBOLT_YES="1", PIBOLT_PASEO="yes",
        )

    def save(self, config):
        self.paseo.mkdir(parents=True, exist_ok=True)
        self.config.write_text(json.dumps(config))

    def run_offer(self, choice="yes", setup="", **env):
        self.runner.write_text(self.functions + '''
set -eu
setup_style
INSTALL=$TEST_INSTALL
BIN_DIR=$TEST_BIN
NAME=build
TERMUX=""
''' + setup + '\noffer_paseo\nprintf "offer finished\\n"\n')
        result = subprocess.run(
            ["sh", str(self.runner)], cwd=self.root,
            env={**self.env, "PIBOLT_PASEO": choice, **env},
            capture_output=True, text=True, timeout=15, check=True,
        )
        self.assertIn("offer finished", result.stdout)
        self.assertFalse((self.root / "paseo.log").exists(), "must not contact a daemon")
        self.assertEqual(list(self.paseo.glob("config.json.pi-bolt-*")), [])
        return result.stdout + result.stderr

    def assert_provider(self, config=None):
        config = config or self.config
        self.assertEqual(json.loads(config.read_text())["agents"]["providers"]["pi-bolt"], {
            "extends": "pi", "label": "Pi-Bolt", "command": [str(self.bin / "pi-bolt")],
        })

    def test_creates_config_without_extra_dependencies(self):
        output = self.run_offer()
        self.assert_provider()
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)
        self.assertIn("paseo reload", output)

    def test_preserves_existing_configuration_and_permissions(self):
        original = {"version": 1, "daemon": {"listen": "127.0.0.1:6767"}, "agents": {
            "other": True, "providers": {"pi": {"command": ["custom-pi"]}, "claude": {"enabled": False}},
        }}
        self.save(original)
        self.config.chmod(0o640)
        self.run_offer()
        self.assert_provider()
        result = json.loads(self.config.read_text())
        del result["agents"]["providers"]["pi-bolt"]
        self.assertEqual(result, original)
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o640)

    def test_existing_provider_is_untouched_including_disabled_or_invalid_entries(self):
        for provider in ({"extends": "pi", "enabled": False, "command": ["my-wrapper"]}, None, "custom"):
            with self.subTest(provider=provider):
                self.save({"agents": {"providers": {"pi-bolt": provider}}})
                before = self.config.read_bytes()
                output = self.run_offer()
                self.assertEqual(self.config.read_bytes(), before)
                self.assertIn("left unchanged", output)

    def test_reinstall_is_idempotent(self):
        self.run_offer()
        before = self.config.read_bytes(), self.config.stat().st_mtime_ns
        self.run_offer()
        self.assertEqual((self.config.read_bytes(), self.config.stat().st_mtime_ns), before)

    def test_custom_paseo_home(self):
        custom = self.root / "custom paseo"
        self.run_offer(PASEO_HOME=str(custom))
        self.assert_provider(custom / "config.json")
        self.assertFalse(self.paseo.exists())

    def test_relative_paths_survive_runtime_isolation(self):
        (self.root / "tmp").mkdir()
        self.run_offer(TEST_BIN=os.path.relpath(self.bin, self.root), TEST_INSTALL=".",
                       PASEO_HOME="home/.paseo", TMPDIR="tmp")
        self.assert_provider()
        self.assertEqual(list((self.root / "tmp").iterdir()), [])

    def test_caller_project_cannot_preload_code(self):
        marker = self.root / "preload-ran"
        (self.root / "bunfig.toml").write_text('preload = ["./preload.cjs"]\n')
        (self.root / "preload.cjs").write_text(
            f'require("node:fs").writeFileSync({json.dumps(str(marker))}, "ran");\n'
        )
        self.run_offer()
        self.assert_provider()
        self.assertFalse(marker.exists(), "registration must not load the caller's Bun preloads")

    def test_termux_uses_its_launcher(self):
        self.run_offer(setup='TERMUX=1')
        self.assert_provider()
        self.assertIn("pi-termux", (self.root / "runtime.log").read_text())

    def test_no_and_unattended_default_do_not_write_or_run_runtime(self):
        for choice in ("no", ""):
            with self.subTest(choice=choice):
                self.run_offer(choice=choice)
                self.assertFalse(self.paseo.exists())
                self.assertFalse((self.root / "runtime.log").exists())

    def test_invalid_option_is_nonfatal(self):
        self.assertIn("must be yes or no", self.run_offer(choice="wrong"))
        self.assertFalse(self.paseo.exists())

    def test_detected_paseo_prompts_and_respects_answer(self):
        # Stub terminal input only; execute the real detection and offer logic.
        for answer in ("no", "yes"):
            with self.subTest(answer=answer):
                output = self.run_offer(choice="", setup=f'''
has_tty() {{ return 0; }}
ask() {{ printf '%s\\n' "$1"; answer={answer}; }}
''')
                self.assertIn("Add Pi-Bolt as a Paseo provider", output)
                self.assertEqual(self.config.exists(), answer == "yes")

    def test_absent_paseo_does_not_prompt(self):
        (self.bin / "paseo").unlink()
        self.run_offer(choice="", setup='''
has_tty() { return 0; }
# Make detection deterministic even when the test host has Paseo installed.
command() { return 1; }
ask() { exit 99; }
''')
        self.assertFalse(self.paseo.exists())

    def test_home_detects_desktop_paseo_without_cli(self):
        self.paseo.mkdir()
        self.run_offer(choice="", setup='''
has_tty() { return 0; }
command() { return 1; }
ask() { answer=yes; }
''')
        self.assert_provider()

    def test_invalid_json_and_containers_are_preserved(self):
        for content in ('{broken', 'null', '[]', '{"agents":null}', '{"agents":[]}',
                        '{"agents":{"providers":false}}', '{"agents":{"providers":[]}}'):
            with self.subTest(content=content):
                self.paseo.mkdir(exist_ok=True)
                self.config.write_text(content)
                output = self.run_offer()
                self.assertEqual(self.config.read_text(), content)
                self.assertIn("Configure Paseo manually", output)

    def test_symlink_is_preserved(self):
        self.paseo.mkdir()
        target = self.root / "managed.json"
        target.write_text('{}\n')
        self.config.symlink_to(target)
        output = self.run_offer()
        self.assertTrue(self.config.is_symlink())
        self.assertEqual(target.read_text(), '{}\n')
        self.assertIn("not a regular file", output)

    def test_write_failure_does_not_fail_installation(self):
        self.paseo.write_text("not a directory")
        output = self.run_offer()
        self.assertIn("Pi-Bolt itself is installed", output)
        self.assertEqual(self.paseo.read_text(), "not a directory")


if __name__ == "__main__":
    unittest.main()
