import { existsSync } from "node:fs";
import { basename, dirname } from "node:path";
import { describe, expect, test } from "vitest";
import {
	cleanupHerdrShimLink,
	herdrShimLink,
	isHerdrShim,
	reExecAsPi,
	shouldReExecAsPi,
} from "../src/extensions/herdr/shim.ts";

describe("Herdr pi shim", () => {
	test("re-execs only a process that Herdr would not see as pi", () => {
		expect(shouldReExecAsPi("/usr/local/bin/pi-bolt", "darwin")).toBe(true);
		expect(shouldReExecAsPi("/tmp/pi-bolt-herdr-1/pi", "darwin")).toBe(false);
		expect(shouldReExecAsPi("pi", "linux")).toBe(false);
		expect(shouldReExecAsPi("pi-bolt.exe", "win32")).toBe(false);
	});

	test("the temporary link is named pi", () => {
		const link = herdrShimLink(7, "/tmp");
		expect(basename(link)).toBe("pi");
		expect(link).toBe("/tmp/pi-bolt-herdr-7/pi");
	});

	test("shim mode comes from the environment", () => {
		expect(isHerdrShim({ PIBOLT_HERDR_SHIM: "1" })).toBe(true);
		expect(isHerdrShim({})).toBe(false);
	});

	// The link a re-exec goes through is removed by the process it starts, and by nothing else: without
	// PIBOLT_HERDR_SHIM_LINK in its environment it would stay in the temporary directory under its pid.
	test("the process a re-exec starts removes the link it went through", () => {
		const env: NodeJS.ProcessEnv = {};
		let execveEnv: NodeJS.ProcessEnv | undefined;
		const execve = process.execve;
		process.execve = ((_file: string, _args?: readonly string[], environment?: NodeJS.ProcessEnv) => {
			execveEnv = environment;
		}) as typeof process.execve;
		try {
			expect(reExecAsPi(["--herdr-shim"], env)).toBe(false);
		} finally {
			process.execve = execve;
		}
		const link = herdrShimLink();
		expect(execveEnv?.PIBOLT_HERDR_SHIM_LINK).toBe(link);
		expect(existsSync(link)).toBe(true);

		cleanupHerdrShimLink({ PIBOLT_HERDR_SHIM_LINK: link });
		expect(existsSync(link)).toBe(false);
		expect(existsSync(dirname(link))).toBe(false);
	});

	test("a process that did not re-exec has no link to remove", () => {
		const env: NodeJS.ProcessEnv = {};
		expect(() => cleanupHerdrShimLink(env)).not.toThrow();
		expect(env).toEqual({});
	});
});
