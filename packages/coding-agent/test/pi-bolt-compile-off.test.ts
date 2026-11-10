import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type * as PiBolt from "../src/pi-bolt.ts";
import { runPiBoltCompile } from "../src/pi-bolt-compile.ts";

// A Pi-Bolt build (scripts/build-pi.sh defines PIBOLT_BUILD); the tests run as Pi otherwise. (vi.mock runs before the imports.)
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "0.9.1", variant: "arm64", jit: false },
}));

const realExecPath = process.execPath;
let install: string;
const sigintListeners = process.listenerCount("SIGINT");

beforeEach(() => {
	install = mkdtempSync(join(tmpdir(), "pibolt-off-"));
	mkdirSync(join(install, "pi-bolt-linux-x64"));
	Object.defineProperty(process, "execPath", { value: join(install, "pi-bolt-linux-x64", "pi"), configurable: true });
	writeFileSync(join(install, "compile.json"), JSON.stringify({ version: "0.9.1", extensions: ["word-count"] }));
});

afterEach(() => {
	vi.restoreAllMocks();
	Object.defineProperty(process, "execPath", { value: realExecPath, configurable: true });
	rmSync(install, { recursive: true, force: true });
});

describe.skipIf(process.platform === "win32")("pi-bolt compile --off", () => {
	test("keeps what was compiled in when the release could not be installed again", async () => {
		expect(await runPiBoltCompile(["--off"], async () => false)).toBe(1);
		expect(existsSync(join(install, "compile.json"))).toBe(true);
	});

	test("forgets it once the release is installed again", async () => {
		expect(await runPiBoltCompile(["--off"], async () => true)).toBe(0);
		expect(existsSync(join(install, "compile.json"))).toBe(false);
		expect(existsSync(join(install, "compile.json.off"))).toBe(false);
	});

	test("the installer it runs finds no compile.json, so it does not say to compile the extensions in again", async () => {
		// (install.sh prints "Extensions were compiled into the build this replaced: run pi-bolt compile ..." when it finds one.)
		let seenByInstaller: boolean | undefined;
		expect(
			await runPiBoltCompile(["--off"], async () => {
				seenByInstaller = existsSync(join(install, "compile.json"));
				return true;
			}),
		).toBe(0);
		expect(seenByInstaller).toBe(false);
	});

	// The review of #317: Ctrl-C while --off downloads the release ended the process without its `finally`, and the record
	// stayed set aside as compile.json.off, which nothing read.
	test("puts compile.json back when a stop signal ends it while the release downloads", async () => {
		const exit = vi.spyOn(process, "exit").mockImplementation((() => undefined) as typeof process.exit);
		let finish: (installed: boolean) => void = () => {};
		const running = runPiBoltCompile(["--off"], () => {
			process.emit("SIGINT");
			return new Promise<boolean>((resolve) => {
				finish = resolve;
			});
		});
		await new Promise((done) => setTimeout(done, 0));
		expect(exit).toHaveBeenCalledWith(130);
		expect(existsSync(join(install, "compile.json"))).toBe(true);
		expect(existsSync(join(install, "compile.json.off"))).toBe(false);
		finish(false);
		expect(await running).toBe(1);
		expect(process.listenerCount("SIGINT")).toBe(sigintListeners);
	});

	test("a record a killed --off left set aside is put back by the next compile that reads it", async () => {
		renameSync(join(install, "compile.json"), join(install, "compile.json.off"));
		vi.spyOn(console, "log").mockImplementation(() => {});
		expect(await runPiBoltCompile(["--status"], async () => true)).toBe(0);
		expect(existsSync(join(install, "compile.json"))).toBe(true);
		expect(existsSync(join(install, "compile.json.off"))).toBe(false);
	});

	test("puts compile.json back when the installer throws", async () => {
		expect(
			await runPiBoltCompile(["--off"], async () => {
				throw new Error("the installer did not start");
			}),
		).toBe(1);
		expect(existsSync(join(install, "compile.json"))).toBe(true);
	});
});

// Updating does not compile extensions: compile.json stays, and `pi-bolt compile` without options compiles the same again.
describe.skipIf(process.platform === "win32")("pi-bolt compile and an update", () => {
	const listed = async (args: string[]) => {
		const stderr = vi.spyOn(process.stderr, "write").mockImplementation(() => true);
		expect(await runPiBoltCompile(args, async () => true)).toBe(0);
		return stderr.mock.calls.map((call) => String(call[0])).join("");
	};
	beforeEach(() => {
		const agent = join(install, "agent");
		mkdirSync(join(agent, "extensions"), { recursive: true });
		for (const name of ["word-count", "other"]) {
			writeFileSync(join(agent, "extensions", `${name}.ts`), "export default () => {};\n");
		}
		vi.stubEnv("PI_CODING_AGENT_DIR", agent);
	});
	afterEach(() => {
		vi.unstubAllEnvs();
	});

	test("without options, a compile takes compile.json's --only: the same extensions as the last compile", async () => {
		writeFileSync(
			join(install, "compile.json"),
			JSON.stringify({ version: "0.9.0", extensions: ["word-count"], only: ["word-count"] }),
		);
		const same = await listed(["--list"]);
		expect(same).toContain("word-count (global): compiling");
		expect(same).toContain("other (global): not in --only");
		// (Any choice given is the command's: --no-exclude takes all of them.)
		const all = await listed(["--list", "--no-exclude"]);
		expect(all).toContain("other (global): compiling");
	});

	test("--after-update, which pi-bolt update of an earlier release runs in the build it installed, compiles nothing", async () => {
		const stderr = vi.spyOn(process.stderr, "write").mockImplementation(() => true);
		expect(await runPiBoltCompile(["--after-update"], async () => true)).toBe(0);
		expect(stderr).not.toHaveBeenCalled();
		expect(existsSync(join(install, "compile.json"))).toBe(true);
	});

	test("--status says the recorded extensions are not compiled into this build, and how to compile them in", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => {});
		expect(await runPiBoltCompile(["--status"], async () => true)).toBe(0);
		const said = log.mock.calls.flat().join("\n");
		expect(said).toContain("No extensions are compiled into this Pi-Bolt.");
		expect(said).toContain("compile.json records extensions compiled in (word-count), but this build has none");
		expect(said).toContain("an update");
		expect(said).toContain("Run `pi-bolt compile` to compile them in again.");
	});
});

describe.skipIf(process.platform === "win32")("the installer, after it replaced a build", () => {
	const installer = readFileSync(resolve(__dirname, "../../../install.sh"), "utf8");
	const functions = ["has_compiled_extensions", "compiled_extensions_note"].map((name) => {
		const found = new RegExp(`^${name}\\(\\) \\{\\n[\\s\\S]*?\\n\\}\\n`, "m").exec(installer)?.[0];
		expect(found, name).toBeDefined();
		return found;
	});
	/** What the installer says after it replaced the build in `replaced`. */
	const note = (replaced: string) =>
		spawnSync(
			"sh",
			[
				"-c",
				`${functions.join("\n")}\nb=""; if has_compiled_extensions "$REPLACED"; then b=1; fi; compiled_extensions_note "$b"`,
			],
			{ env: { ...process.env, INSTALL: install, REPLACED: replaced, bold: "", reset: "" }, encoding: "utf8" },
		).stdout;
	const build = (about: string) => {
		const folder = mkdtempSync(join(install, "build-"));
		writeFileSync(join(folder, "pi-bolt.txt"), about);
		return folder;
	};

	test("says the extensions load from their files now, and how to compile them in, when the build it replaced had them", () => {
		const compiled = build(
			"Pi-Bolt 0.9.0 (Pi 1.4.0)\nExtensions compiled in (pi-bolt compile, 2026-11-10): word-count\n",
		);
		expect(note(compiled)).toBe(
			"Extensions were compiled into the build this replaced: they load from their files now; run pi-bolt compile to compile them into this one.\n",
		);
	});

	test("nothing when it had none, though compile.json is kept from an earlier compile", () => {
		expect(note(build("Pi-Bolt 0.9.0 (Pi 1.4.0)\n"))).toBe("");
		expect(note(join(install, "none"))).toBe("");
	});

	test("nothing while pi-bolt compile --off installs the release again (compile.json set aside)", () => {
		rmSync(join(install, "compile.json"));
		expect(note(build("Pi-Bolt 0.9.0\nExtensions compiled in (pi-bolt compile, 2026-11-10): word-count\n"))).toBe("");
	});
});
