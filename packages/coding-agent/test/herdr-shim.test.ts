import { describe, expect, test } from "vitest";
import { isHerdrShim, reExecAsPi, shouldReExecAsPi } from "../src/extensions/herdr/shim.ts";

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

describe("Herdr pi shim", () => {
	test("re-execs only a process that Herdr would not see as pi", () => {
		expect(shouldReExecAsPi("/usr/local/bin/pi-bolt", "darwin")).toBe(true);
		expect(shouldReExecAsPi("/opt/homebrew/bin/pi-bolt", "linux")).toBe(true);
		expect(shouldReExecAsPi("pi", "darwin")).toBe(false);
		expect(shouldReExecAsPi("/opt/homebrew/bin/pi", "linux")).toBe(false);
		expect(shouldReExecAsPi("pi-bolt.exe", "win32")).toBe(false);
		expect(shouldReExecAsPi("pi.exe", "win32")).toBe(false);
	});

	test("shim mode comes from the environment", () => {
		expect(isHerdrShim({ PIBOLT_HERDR_SHIM: "1" })).toBe(true);
		expect(isHerdrShim({})).toBe(false);
	});

	// Herdr's agent table matches argv[0]'s basename, so the re-exec has to name the same executable `pi`; no `pi` file is
	// involved, and the flag and its arguments are passed through unchanged.
	test("the re-exec runs the same executable with pi as argv[0]", () => {
		const args = ["--herdr-shim", "--session", "/home/user/.pi/session.jsonl"];
		const env: NodeJS.ProcessEnv = { PIBOLT_HERDR_SHIM: "1", HERDR_ENV: "1" };
		const calls = reExecWithStubbedExecve(args, env);

		expect(calls).toHaveLength(1);
		expect(calls[0].file).toBe(process.execPath);
		expect(calls[0].args).toEqual(["pi", ...args]);
		expect(calls[0].env).toBe(env);
		// Nothing is added to the environment of the process a re-exec starts: there is no temporary link to remove.
		expect(env).toEqual({ PIBOLT_HERDR_SHIM: "1", HERDR_ENV: "1" });
	});

	test("a runtime without execve does not re-exec", () => {
		const execve = process.execve;
		// The check is `typeof process.execve`, so an absent one is the same as a platform that has none.
		delete (process as { execve?: typeof execve }).execve;
		try {
			expect(reExecAsPi(["--herdr-shim"], {})).toBe(false);
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
			expect(reExecAsPi(["--herdr-shim"], { PIBOLT_HERDR_SHIM: "1" })).toBe(false);
		} finally {
			process.execve = execve;
		}
	});
});
