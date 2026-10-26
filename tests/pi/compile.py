"""`pi-bolt compile` with a release's compile kit and runtime, into an installation made from the build under test:
the extensions installed here are compiled in and used in place of their files, and find the skills and prompts beside their
modules from import.meta.url as they do loaded from their files (#53); one changed since is loaded from its files with a warning; one removed since is not loaded; the build it replaced is kept, and `pi-bolt update --rollback` swaps the two
(and back); `pi-bolt update --channel beta` is kept for the installation; and `--out` builds without installing. An import()
of a package that is not installed is left to run time, where it fails inside the extension as loaded from its files, while a
static import of one still stops the build (#160); and an extension finds files, modules and Pi's packages from where it is
installed, as loaded from its files (loader parity). Uses the bench fake model.
  python3 tests/pi/compile.py PI KIT RUNTIME     (KIT: pi-bolt-kit-<platform>.tar.gz or its folder; RUNTIME: the runtime's bun)
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
import harness  # noqa: E402

pi, kit, runtime = (str(Path(arg).resolve()) for arg in sys.argv[1:4])
failures = 0


def check(name, ok, detail=""):
    global failures
    print(f"{'PASS' if ok else 'FAIL'} {name}{'' if ok else f': {detail}'}")
    failures += 0 if ok else 1


# An extension that says how it was loaded, and what its import.meta.url is: compiled in, its code is in the executable's own
# file system ($bunfs), which the first frame of a stack names. (Not import.meta.url: a compiled-in extension's is its installed
# file's, as when it is loaded from its files.)
PROBE = """import { writeFileSync } from "node:fs";
export default function () {
	const how = new Error().stack.split("\\n")[1].includes("/$bunfs/") ? "compiled" : "files";
	writeFileSync(`${process.env.PIBOLT_PROBE}/NAME`, `${how} ${import.meta.url}`);
}
"""
# And one that registers the skills and prompts beside its module, found from import.meta.url as extensions commonly do.
RESOURCES = """import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const here = dirname(fileURLToPath(import.meta.url));
export function resources(pi) {
	pi.on("resources_discover", () => ({ skillPaths: [join(here, "skills")], promptPaths: [join(here, "prompts")] }));
}
"""

# #160: an import() of a package the extension neither declares nor has installed, for something it does only when that
# package is there (as @plannotator/pi-extension loads the Claude Agent SDK). Loaded from its files, it fails when it runs, and
# the extension carries on.
OPTIONAL = """import { writeFileSync } from "node:fs";
async function sdk() {
	return await import("pibolt-missing-sdk-160");
}
export default async function () {
	const how = new Error().stack.split("\\n")[1].includes("/$bunfs/") ? "compiled" : "files";
	let found = "found";
	try {
		await sdk();
	} catch (error) {
		found = "caught";
	}
	writeFileSync(`${process.env.PIBOLT_PROBE}/optional-ext`, `${how} ${found}`);
}
"""

# Loader parity: what a compiled-in extension finds from where it is installed, as loaded from its files. #4: TypeScript's
# CommonJS output (`exports.__esModule`, `exports.default`). #5: __dirname, __filename, require.resolve, import.meta.resolve and a
# require() of a computed path, which the bundler resolved against the copy it deleted or the executable's own file system. #6: a
# module loaded from disk as it runs, which imports Pi's packages.
TSC_CJS = """"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.default = default_1;
function default_1() {
	const how = new Error().stack.split("\\n")[1].includes("/$bunfs/") ? "compiled" : "files";
	require("node:fs").writeFileSync(`${process.env.PIBOLT_PROBE}/tsc-cjs`, how);
}
"""
PARITY = """import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
const name = (n) => n;
export default async function () {
	const how = new Error().stack.split("\\n")[1].includes("/$bunfs/") ? "compiled" : "files";
	const seen = {};
	const see = async (key, f) => {
		try {
			seen[key] = await f();
		} catch (error) {
			seen[key] = `error: ${error.message}`;
		}
	};
	await see("dirname", () => readFileSync(join(__dirname, "data.txt"), "utf8"));
	await see("filename", () => __filename);
	await see("requireResolve", () => require.resolve("./data.txt"));
	await see("require", () => require(name("./dyn.cjs")));
	await see("metaResolve", () => import.meta.resolve("./data.txt"));
	await see("fromDisk", async () => (await import(pathToFileURL(join(__dirname, name("disk.ts"))).href)).found);
	writeFileSync(`${process.env.PIBOLT_PROBE}/parity-ext`, `${how} ${JSON.stringify(seen)}`);
}
"""
DISK = """import { Type } from "typebox";
import { getAgentDir } from "@earendil-works/pi-coding-agent";
export const found = [typeof Type.Object, typeof getAgentDir];
"""

version = subprocess.run([pi, "--version"], capture_output=True, text=True, check=True).stdout
platform, variant = re.search(r"Pi-Bolt [^,]+, (\w+)-(\S+), JIT", version).groups()
with tempfile.TemporaryDirectory(prefix="pibolt-compile-") as tmp:
    tmp = Path(tmp)
    install = tmp / "install"
    build = install / f"pi-bolt-{platform}-{variant}"
    shutil.copytree(Path(pi).parent, build, symlinks=True)
    agent, probe = tmp / "agent", tmp / "probe"
    (agent / "extensions" / "folder-ext").mkdir(parents=True)
    probe.mkdir()
    (agent / "extensions" / "file-ext.ts").write_text(PROBE.replace("NAME", "file-ext"))
    folder = agent / "extensions" / "folder-ext"
    (folder / "index.ts").write_text('import "./helper.ts";\nimport { resources } from "./src/resources.ts";\n'
                                     + PROBE.replace("NAME", "folder-ext").replace("export default function () {",
                                                                                   "export default function (pi) {\n\tresources(pi);"))
    (folder / "helper.ts").write_text("export const one = 1;\n")
    (agent / "extensions" / "optional-ext.ts").write_text(OPTIONAL)
    (agent / "extensions" / "tsc-cjs.js").write_text(TSC_CJS)
    parity = agent / "extensions" / "parity-ext"
    parity.mkdir()
    (parity / "index.js").write_text(PARITY)
    (parity / "data.txt").write_text("data")
    (parity / "dyn.cjs").write_text('module.exports = "dyn";\n')
    (parity / "disk.ts").write_text(DISK)
    (folder / "src").mkdir()
    (folder / "src" / "resources.ts").write_text(RESOURCES)
    (folder / "src" / "skills" / "probe-skill").mkdir(parents=True)
    (folder / "src" / "skills" / "probe-skill" / "SKILL.md").write_text(
        "---\nname: probe-skill\ndescription: A skill beside the extension's module.\n---\nSay probe.\n")
    (folder / "src" / "prompts").mkdir()
    (folder / "src" / "prompts" / "probe-prompt.md").write_text(
        "---\ndescription: A prompt beside the extension's module.\n---\nSay probe.\n")

    with harness.fake_model() as port, harness.pi_home(port) as home:
        for name in ("models.json", "auth.json", "settings.json"):
            shutil.copy(home / name, agent / name)
        env = harness.pi_env(agent, {"PIBOLT_PROBE": str(probe)})

        def compile_(*args):
            return subprocess.run([str(build / "pi"), "compile", "--kit", kit, "--runtime", runtime, *args], env=env,
                                  capture_output=True, text=True, stdin=subprocess.DEVNULL, cwd=tmp)

        def session():
            """`pi -p`: how each extension was loaded (its module's URL, or None), and what Pi printed."""
            for file in probe.iterdir():
                file.unlink()
            r = subprocess.run([str(build / "pi"), "-p", "--model", "fake/fake-model", "hello"], env=env,
                               capture_output=True, text=True, stdin=subprocess.DEVNULL, cwd=tmp)
            loaded = {name: tuple((probe / name).read_text().split(" ", 1)) if (probe / name).exists() else (None, None)
                      for name in ("file-ext", "folder-ext", "optional-ext", "parity-ext")}
            loaded["tsc-cjs"] = ((probe / "tsc-cjs").read_text(), None) if (probe / "tsc-cjs").exists() else (None, None)
            return r, loaded

        def commands():
            """The skills and prompts Pi loaded (RPC get_commands): no model needed."""
            r = subprocess.run([str(build / "pi"), "--mode", "rpc"], env=env, capture_output=True, text=True, cwd=tmp,
                               input='{"type":"get_commands","id":"1"}\n')
            for line in r.stdout.splitlines():
                message = json.loads(line) if line.startswith("{") else {}
                if message.get("command") == "get_commands":
                    return sorted(c["name"] for c in message["data"]["commands"] if c["source"] in ("skill", "prompt"))
            return f"no answer: {r.stderr[-300:]}"

        r, loaded = session()
        check("before: both extensions load from their files",
              r.returncode == 0 and all(loaded[name][0] == "files" and loaded[name][1].startswith("file:")
                                        for name in ("file-ext", "folder-ext")),
              f"{loaded} {r.stderr[-300:]}")
        check("before: an import() of a package that is not installed fails inside the extension (#160)",
              loaded["optional-ext"] == ("files", "caught"), str(loaded))
        from_files = {name: loaded[name][1] for name in ("file-ext", "folder-ext")}
        parity_from_files = loaded["parity-ext"]
        check("before: the loader-parity probes load from their files",
              parity_from_files[0] == "files" and loaded["tsc-cjs"][0] == "files"
              and json.loads(parity_from_files[1])["fromDisk"] == ["function", "function"], str(loaded))
        resources = commands()
        check("before: the skill and prompt beside the module are loaded",
              resources == ["probe-prompt", "skill:probe-skill"], str(resources))

        started = time.time()
        r = compile_()
        took = time.time() - started
        check("pi-bolt compile succeeds", r.returncode == 0, r.stdout[-500:] + r.stderr[-1500:])
        check("it names what it compiled in", "file-ext" in r.stderr and "folder-ext" in r.stderr, r.stderr[-500:])
        check("it says the import() that does not resolve is left to run time (#160)",
              'optional-ext: import("pibolt-missing-sdk-160") does not resolve: left to run time' in r.stderr,
              r.stderr[-800:])
        print(f"     (compile took {took:.1f} s)")
        record = json.loads((install / "compile.json").read_text()) if (install / "compile.json").exists() else {}
        check("compile.json keeps the extensions for the next update",
              sorted(record.get("extensions", [])) == ["file-ext", "folder-ext", "optional-ext", "parity-ext", "tsc-cjs"],
              str(record))
        check("the build it replaced is kept as .previous", (install / f"{build.name}.previous" / "pi").exists())
        about = (build / "pi-bolt.txt").read_text()
        check("pi-bolt.txt says what is compiled in", "Extensions compiled in" in about and "file-ext" in about, about)
        out = subprocess.run([str(build / "pi"), "--version"], capture_output=True, text=True).stdout
        check("--version is the same version", out == version, f"{out!r} vs {version!r}")

        r, loaded = session()
        check("both compiled-in extensions run from the executable",
              r.returncode == 0 and all(how == "compiled" for how, _ in loaded.values()), f"{loaded} {r.stderr[-300:]}")
        check("no warning while they are unchanged", "changed since" not in r.stderr, r.stderr[-300:])
        check("compiled in, import.meta.url is the installed file's, as loaded from its files (#53)",
              {name: loaded[name][1] for name in ("file-ext", "folder-ext")} == from_files, f"{loaded} vs {from_files}")
        check("compiled in, the import() of a package that is not installed fails inside the extension, as loaded from its files (#160)",
              loaded["optional-ext"] == ("compiled", "caught"), str(loaded))
        check("compiled in, TypeScript's CommonJS output loads, as from its files (loader parity #4)",
              loaded["tsc-cjs"][0] == "compiled", str(loaded))
        check("compiled in, paths, require() and modules loaded from disk are as from its files (loader parity #5, #6)",
              loaded["parity-ext"] == ("compiled", parity_from_files[1]), f"{loaded['parity-ext']} vs {parity_from_files}")
        resources = commands()
        check("compiled in, the skill and prompt beside the module are still loaded (#53)",
              resources == ["probe-prompt", "skill:probe-skill"], str(resources))

        helper = agent / "extensions" / "folder-ext" / "helper.ts"
        helper.write_text("export const one = 2;\n")
        later = time.time() + 5
        os.utime(helper, (later, later))
        r, loaded = session()
        check("a changed extension is loaded from its files, the other stays compiled in",
              loaded["folder-ext"][0] == "files" and loaded["file-ext"][0] == "compiled", str(loaded))
        check("with a warning to compile it in again", "changed since it was compiled in" in r.stderr + r.stdout,
              r.stderr[-400:])

        (agent / "extensions" / "file-ext.ts").unlink()
        r, loaded = session()
        check("a removed extension is not loaded at all", loaded["file-ext"] == (None, None), str(loaded))

        status = subprocess.run([str(build / "pi"), "compile", "--status"], env=env, capture_output=True, text=True)
        check("--status says what is compiled in", "file-ext" in status.stdout, status.stdout + status.stderr)

        def pi_bolt(*args):
            return subprocess.run([str(build / "pi"), *args], env=env, capture_output=True, text=True,
                                  stdin=subprocess.DEVNULL, cwd=tmp)

        r = pi_bolt("update", "--rollback")
        check("update --rollback puts back the build compile replaced",
              r.returncode == 0 and "Extensions compiled in" not in (build / "pi-bolt.txt").read_text()
              and "Extensions compiled in" in (install / f"{build.name}.previous" / "pi-bolt.txt").read_text(),
              r.stdout + r.stderr)
        r, loaded = session()
        check("after the rollback the extensions load from their files again", loaded["folder-ext"][0] == "files", str(loaded))
        r = pi_bolt("update", "--rollback")
        check("update --rollback again goes forward again",
              r.returncode == 0 and "Extensions compiled in" in (build / "pi-bolt.txt").read_text(), r.stdout + r.stderr)
        r = pi_bolt("update", "--channel", "beta")
        check("update --channel beta is kept for the installation",
              (install / "channel").exists() and (install / "channel").read_text().strip() == "beta", r.stdout + r.stderr)
        pi_bolt("update", "--channel", "stable")
        check("update --channel stable takes it away", not (install / "channel").exists())

        r = compile_("--out", str(tmp / "elsewhere"))
        check("--out builds without installing",
              r.returncode == 0 and (tmp / "elsewhere" / "pi").exists() and not (install / ".compile").exists(),
              r.stderr[-800:])
        leftovers = [p.name for p in install.iterdir() if p.name.startswith(".compile-")]
        check("nothing is left of the work folder", not leftovers, str(leftovers))

        # (#160) A static import of a package that is not there is the extension's own need: the build stops at it, as before.
        required = agent / "extensions" / "required-ext.ts"
        required.write_text('import { sdk } from "pibolt-missing-sdk-160";\nexport default function () {\n\treturn sdk;\n}\n')
        r = compile_("--only", "required-ext", "--out", str(tmp / "required"))
        check("a static import of a package that is not installed still stops the build (#160)",
              r.returncode != 0 and 'Could not resolve: "pibolt-missing-sdk-160"' in r.stderr, r.stderr[-800:])
        required.unlink()

print("compile:", "all passed" if failures == 0 else f"{failures} failed")
sys.exit(1 if failures else 0)
