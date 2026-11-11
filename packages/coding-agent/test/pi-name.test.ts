import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, test, vi } from "vitest";
import { type HerdrPiProcess, reExecTarget } from "../src/extensions/herdr/pi-name.ts";

const cleanups: Array<() => void> = [];
afterEach(() => {
	for (const cleanup of cleanups.splice(0).reverse()) cleanup();
});

/** A macOS build's folder: the launcher `pi` and the executable `pi-bin` it starts. */
function darwinBuild(): string {
	const dir = mkdtempSync(join(tmpdir(), "pi-name-"));
	cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
	writeFileSync(join(dir, "pi"), "");
	writeFileSync(join(dir, "pi-bin"), "");
	return dir;
}

/** pi-name.ts as a new process has it: the mode is module state, set once per process. */
async function freshPiName() {
	vi.resetModules();
	return await import("../src/extensions/herdr/pi-name.ts");
}

/** A process in a Herdr pane (the variables Herdr sets in it), started as `pi-bolt`. */
function inHerdr(overrides: Partial<HerdrPiProcess> = {}): HerdrPiProcess {
	return {
		env: { HERDR_ENV: "1", HERDR_PANE_ID: "w1:p2", HERDR_SOCKET_PATH: "/tmp/herdr.sock" },
		platform: "linux",
		argv0: "/opt/pi-bolt/bin/pi-bolt",
		execPath: "/opt/pi-bolt/pi-bolt-linux-x64/pi",
		...overrides,
	};
}

interface ExecveCall {
	file: string;
	args: readonly string[];
	env: NodeJS.ProcessEnv;
}

/** Run `fn` with execve stubbed out (so it returns), and return what each re-exec would have started. */
function withStubbedExecve(fn: () => void): ExecveCall[] {
	const calls: ExecveCall[] = [];
	const execve = process.execve;
	process.execve = ((file: string, args?: readonly string[], env?: NodeJS.ProcessEnv) => {
		calls.push({ file, args: args ?? [], env: env ?? {} });
	}) as typeof process.execve;
	try {
		fn();
	} finally {
		process.execve = execve;
	}
	return calls;
}

describe("Herdr pi name", () => {
	// Herdr's agent table matches argv[0]'s basename, so the re-exec names the same executable `pi`; no `pi` file is
	// involved, and the flag and its arguments are passed through unchanged, so the new process takes the same mode.
	test("an interactive run in a Herdr pane re-execs as pi", async () => {
		const { startHerdrPi } = await freshPiName();
		const args = ["--herdr-pi", "--session", "/home/user/.pi/session.jsonl"];
		const proc = inHerdr();
		const calls = withStubbedExecve(() => expect(startHerdrPi(args, true, proc)).toBe("re-exec-failed"));
		expect(calls).toEqual([{ file: proc.execPath, args: ["pi", ...args], env: proc.env }]);
	});

	test("a process already named pi, or not interactive, reports as Pi without a re-exec", async () => {
		for (const [argv0, interactive] of [
			["pi", true],
			["/usr/local/bin/pi", true],
			["pi-bolt", false],
		] as const) {
			const { isHerdrPi, startHerdrPi } = await freshPiName();
			const calls = withStubbedExecve(() =>
				expect(startHerdrPi(["--herdr-pi"], interactive, inHerdr({ argv0 }))).toBe("on"),
			);
			expect(calls).toEqual([]);
			expect(isHerdrPi()).toBe(true);
		}
	});

	// PR #157 review: the option re-exec'd wherever HERDR_ENV was set, a second start for a process with no pane to report
	// to (Herdr also sets HERDR_PANE_ID and its socket's path).
	test("outside a Herdr pane the option does nothing", async () => {
		for (const env of [{}, { HERDR_ENV: "1" }, { HERDR_ENV: "1", HERDR_PANE_ID: "w1:p2" }]) {
			const { isHerdrPi, startHerdrPi } = await freshPiName();
			const calls = withStubbedExecve(() =>
				expect(startHerdrPi(["--herdr-pi"], true, inHerdr({ env }))).toBe("off"),
			);
			expect(calls).toEqual([]);
			expect(isHerdrPi()).toBe(false);
		}
	});

	// PR #157 review: the mode was PIBOLT_HERDR_PI=1 in the environment, which every program Pi started inherited: a
	// pi-bolt run from a tool in the same pane reported as Herdr's Pi integration without the option.
	test("the mode is not passed on in the environment", async () => {
		const { isHerdrPi, startHerdrPi } = await freshPiName();
		const proc = inHerdr();
		const before = { ...proc.env };
		const calls = withStubbedExecve(() => startHerdrPi(["--herdr-pi"], true, proc));
		expect(calls[0].env).toEqual(before);
		expect(startHerdrPi(["--herdr-pi"], false, proc)).toBe("on");
		expect(isHerdrPi()).toBe(true);
		expect(proc.env).toEqual(before);
		expect(process.env.PIBOLT_HERDR_PI).toBeUndefined();
	});

	// PR #157 review: the check read argv[0], which is what was typed: `pi` in cmd.exe starts pi.exe with argv[0] `pi`.
	test("on Windows the executable's file decides, not the command typed", async () => {
		const windows = (argv0: string, execPath: string) =>
			inHerdr({
				platform: "win32",
				argv0,
				execPath,
				env: { HERDR_ENV: "1", HERDR_PANE_ID: "w1:p2", HERDR_BIN_PATH: "herdr.exe" },
			});
		for (const [argv0, execPath, expected] of [
			["pi", "C:\\Users\\me\\.pi-bolt\\bin\\pi.exe", "on"],
			["PI.EXE", "C:\\Users\\me\\.pi-bolt\\bin\\PI.EXE", "on"],
			["pi.exe", "C:\\Users\\me\\.pi-bolt\\pi-bolt-win32-x64\\pi-bolt.exe", "not-pi-exe"],
			["pi-bolt", "C:\\Users\\me\\.pi-bolt\\pi-bolt-win32-x64\\pi-bolt.exe", "not-pi-exe"],
		] as const) {
			const { isHerdrPi, startHerdrPi } = await freshPiName();
			// Windows has no execve: never a re-exec.
			const calls = withStubbedExecve(() =>
				expect(startHerdrPi(["--herdr-pi"], true, windows(argv0, execPath))).toBe(expected),
			);
			expect(calls).toEqual([]);
			expect(isHerdrPi()).toBe(expected === "on");
		}
	});

	// PR #157 review: on macOS the re-exec started pi-bin itself, which takes no spawn helper, so every program Pi then
	// started ran without it. The launcher beside pi-bin forks one and starts pi-bin with argv[0] as given.
	test("on macOS the re-exec goes through the launcher", async () => {
		const dir = darwinBuild();
		expect(reExecTarget(join(dir, "pi-bin"), "darwin")).toBe(join(dir, "pi"));
		const { startHerdrPi } = await freshPiName();
		const calls = withStubbedExecve(() =>
			startHerdrPi(["--herdr-pi"], true, inHerdr({ platform: "darwin", execPath: join(dir, "pi-bin") })),
		);
		expect(calls.map((call) => [call.file, call.args])).toEqual([[join(dir, "pi"), ["pi", "--herdr-pi"]]]);
		// No launcher beside it (a pi-bin started by hand): pi-bin itself.
		rmSync(join(dir, "pi"));
		expect(reExecTarget(join(dir, "pi-bin"), "darwin")).toBe(join(dir, "pi-bin"));
		// Elsewhere the executable is the one that runs.
		expect(reExecTarget("/opt/pi-bolt/pi-bolt-linux-x64/pi", "linux")).toBe("/opt/pi-bolt/pi-bolt-linux-x64/pi");
	});

	test("a runtime without execve, or a failed one, leaves the process reporting as pi-bolt", async () => {
		const execve = process.execve;
		try {
			// The check is `typeof process.execve`, so an absent one is the same as a platform that has none.
			delete (process as { execve?: typeof execve }).execve;
			let piName = await freshPiName();
			expect(piName.startHerdrPi(["--herdr-pi"], true, inHerdr())).toBe("re-exec-failed");
			expect(piName.isHerdrPi()).toBe(false);
			process.execve = (() => {
				throw new Error("EACCES");
			}) as typeof process.execve;
			piName = await freshPiName();
			expect(piName.startHerdrPi(["--herdr-pi"], true, inHerdr())).toBe("re-exec-failed");
			expect(piName.isHerdrPi()).toBe(false);
		} finally {
			process.execve = execve;
		}
	});
});
