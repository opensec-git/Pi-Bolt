import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, test } from "vitest";
import { isHerdrPi, reExecAsPi, reExecTarget, shouldReExecAsPi } from "../src/extensions/herdr/pi-name.ts";

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

interface ExecveCall {
	file: string;
	args: readonly string[];
	env: NodeJS.ProcessEnv;
}

/** Run a re-exec with execve stubbed out, and return what the process it would have started was called with. */
function reExecWithStubbedExecve(args: readonly string[], env: NodeJS.ProcessEnv): ExecveCall[] {
	const calls: ExecveCall[] = [];
	const execve = process.execve;
	process.execve = ((file: string, args_?: readonly string[], environment?: NodeJS.ProcessEnv) => {
		calls.push({ file, args: args_ ?? [], env: environment ?? {} });
	}) as typeof process.execve;
	try {
		expect(reExecAsPi(args, env)).toBe(false);
	} finally {
		process.execve = execve;
	}
	return calls;
}

describe("Herdr pi name", () => {
	test("re-execs only a process that Herdr would not see as pi", () => {
		expect(shouldReExecAsPi("/usr/local/bin/pi-bolt", "darwin")).toBe(true);
		expect(shouldReExecAsPi("/opt/homebrew/bin/pi-bolt", "linux")).toBe(true);
		expect(shouldReExecAsPi("pi", "darwin")).toBe(false);
		expect(shouldReExecAsPi("/opt/homebrew/bin/pi", "linux")).toBe(false);
		expect(shouldReExecAsPi("pi-bolt.exe", "win32")).toBe(false);
		expect(shouldReExecAsPi("pi.exe", "win32")).toBe(false);
	});

	test("Pi-integration mode comes from the environment", () => {
		expect(isHerdrPi({ PIBOLT_HERDR_PI: "1" })).toBe(true);
		expect(isHerdrPi({})).toBe(false);
	});

	// Herdr's agent table matches argv[0]'s basename, so the re-exec has to name the same executable `pi`; no `pi` file is
	// involved, and the flag and its arguments are passed through unchanged.
	test("the re-exec runs the same executable with pi as argv[0]", () => {
		const args = ["--herdr-pi", "--session", "/home/user/.pi/session.jsonl"];
		const env: NodeJS.ProcessEnv = { PIBOLT_HERDR_PI: "1", HERDR_ENV: "1" };
		const calls = reExecWithStubbedExecve(args, env);

		expect(calls).toHaveLength(1);
		expect(calls[0].file).toBe(process.execPath);
		expect(calls[0].args).toEqual(["pi", ...args]);
		expect(calls[0].env).toBe(env);
		// Nothing is added to the environment of the process a re-exec starts: there is no temporary link to remove.
		expect(env).toEqual({ PIBOLT_HERDR_PI: "1", HERDR_ENV: "1" });
	});

	// PR #157 review: on macOS the re-exec started pi-bin itself, which takes no spawn helper, so every program Pi then
	// started ran without it. The launcher beside pi-bin forks one and starts pi-bin with argv[0] as given.
	test("on macOS the re-exec goes through the launcher", () => {
		const dir = darwinBuild();
		expect(reExecTarget(join(dir, "pi-bin"), "darwin")).toBe(join(dir, "pi"));
		// No launcher beside it (a pi-bin started by hand): pi-bin itself.
		rmSync(join(dir, "pi"));
		expect(reExecTarget(join(dir, "pi-bin"), "darwin")).toBe(join(dir, "pi-bin"));
		// Elsewhere the executable is the one that runs.
		expect(reExecTarget("/opt/pi-bolt/pi-bolt-linux-x64/pi", "linux")).toBe("/opt/pi-bolt/pi-bolt-linux-x64/pi");
	});

	test.runIf(process.platform === "darwin")("a macOS re-exec runs the launcher with pi as argv[0]", () => {
		const dir = darwinBuild();
		const execPath = process.execPath;
		process.execPath = join(dir, "pi-bin");
		try {
			const calls = reExecWithStubbedExecve(["--herdr-pi"], {});
			expect(calls.map((call) => [call.file, call.args])).toEqual([[join(dir, "pi"), ["pi", "--herdr-pi"]]]);
		} finally {
			process.execPath = execPath;
		}
	});

	test("a runtime without execve does not re-exec", () => {
		const execve = process.execve;
		// The check is `typeof process.execve`, so an absent one is the same as a platform that has none.
		delete (process as { execve?: typeof execve }).execve;
		try {
			expect(reExecAsPi(["--herdr-pi"], {})).toBe(false);
		} finally {
			process.execve = execve;
		}
	});

	test("a failed re-exec leaves the process reporting as pi-bolt", () => {
		const execve = process.execve;
		process.execve = (() => {
			throw new Error("EACCES");
		}) as typeof process.execve;
		try {
			expect(reExecAsPi(["--herdr-pi"], { PIBOLT_HERDR_PI: "1" })).toBe(false);
		} finally {
			process.execve = execve;
		}
	});
});
