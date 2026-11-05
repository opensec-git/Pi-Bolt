import { existsSync, mkdirSync, mkdtempSync, rmSync, statSync, symlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { describe, expect, test } from "vitest";
import {
	cleanupHerdrShimLink,
	herdrShimLink,
	isHerdrShim,
	reExecAsPi,
	shouldReExecAsPi,
} from "../src/extensions/herdr/shim.ts";

/** Run a re-exec with execve stubbed out, and return the link the process it would have started removes. */
function reExecWithStubbedExecve(env: NodeJS.ProcessEnv): string | undefined {
	let link: string | undefined;
	const execve = process.execve;
	process.execve = ((_file: string, _args?: readonly string[], environment?: NodeJS.ProcessEnv) => {
		link = environment?.PIBOLT_HERDR_SHIM_LINK;
	}) as typeof process.execve;
	try {
		expect(reExecAsPi(["--herdr-shim"], env)).toBe(false);
	} finally {
		process.execve = execve;
	}
	return link;
}

describe("Herdr pi shim", () => {
	test("re-execs only a process that Herdr would not see as pi", () => {
		expect(shouldReExecAsPi("/usr/local/bin/pi-bolt", "darwin")).toBe(true);
		expect(shouldReExecAsPi("/tmp/pi-bolt-herdr-1/pi", "darwin")).toBe(false);
		expect(shouldReExecAsPi("pi", "linux")).toBe(false);
		expect(shouldReExecAsPi("pi-bolt.exe", "win32")).toBe(false);
	});

	test("the temporary link is named pi", () => {
		expect(basename(herdrShimLink("/tmp/pi-bolt-herdr-abc123"))).toBe("pi");
	});

	test("shim mode comes from the environment", () => {
		expect(isHerdrShim({ PIBOLT_HERDR_SHIM: "1" })).toBe(true);
		expect(isHerdrShim({})).toBe(false);
	});

	// The link a re-exec goes through is removed by the process it starts, and by nothing else: without
	// PIBOLT_HERDR_SHIM_LINK in its environment it would stay in the temporary directory with the private one mkdtemp made.
	test("the process a re-exec starts removes the link it went through", () => {
		const link = reExecWithStubbedExecve({});
		expect(link).toBeDefined();
		expect(existsSync(link!)).toBe(true);
		expect(basename(link!)).toBe("pi");
		expect(resolve(dirname(dirname(link!)))).toBe(resolve(tmpdir()));

		cleanupHerdrShimLink({ PIBOLT_HERDR_SHIM_LINK: link });
		expect(existsSync(link!)).toBe(false);
		expect(existsSync(dirname(link!))).toBe(false);
	});

	// The link has to be out of reach of any other user between symlinkSync and execve, or they could point it at their
	// own executable. mkdtemp makes the directory 0700 and names it unpredictably, so it cannot be pre-created either.
	test("the directory that holds the link is private to this process", () => {
		const link = reExecWithStubbedExecve({});
		try {
			expect(link).toBeDefined();
			const dir = dirname(link!);
			expect(basename(dir).startsWith("pi-bolt-herdr-")).toBe(true);
			expect(basename(dir)).not.toBe(`pi-bolt-herdr-${process.pid}`);
			if (process.platform !== "win32") expect(statSync(dir).mode & 0o777).toBe(0o700);
		} finally {
			cleanupHerdrShimLink({ PIBOLT_HERDR_SHIM_LINK: link });
		}
	});

	test("a process that did not re-exec has no link to remove", () => {
		const env: NodeJS.ProcessEnv = {};
		expect(() => cleanupHerdrShimLink(env)).not.toThrow();
		expect(env).toEqual({});
	});

	// PIBOLT_HERDR_SHIM_LINK is inherited from a parent process, so cleanup only removes the shape a re-exec creates.
	test("cleanup leaves a link outside the temporary directory alone", () => {
		const parent = mkdtempSync(join(tmpdir(), "pi-bolt-herdr-test-"));
		const dir = join(parent, "pi-bolt-herdr-nested");
		mkdirSync(dir);
		const link = herdrShimLink(dir);
		symlinkSync(process.execPath, link);
		try {
			cleanupHerdrShimLink({ PIBOLT_HERDR_SHIM_LINK: link });
			expect(existsSync(link)).toBe(true);
			expect(existsSync(dir)).toBe(true);
		} finally {
			rmSync(parent, { recursive: true, force: true });
		}
	});

	test("cleanup leaves a directory that is not a shim directory alone", () => {
		const dir = mkdtempSync(join(tmpdir(), "pi-bolt-other-test-"));
		const link = herdrShimLink(dir);
		symlinkSync(process.execPath, link);
		try {
			cleanupHerdrShimLink({ PIBOLT_HERDR_SHIM_LINK: link });
			expect(existsSync(link)).toBe(true);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});
});
