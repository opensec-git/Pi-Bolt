import { PassThrough } from "node:stream";
import { describe, expect, it } from "vitest";
import { formatStdinIgnoredNotice, getStdinFirstDataTimeoutMs, readPipedStdin } from "../src/utils/piped-stdin.ts";

// upstream #10415: `pi -p "prompt"` waited for the end of piped stdin, so a parent that kept the pipe open without
// writing blocked the run forever.
describe("readPipedStdin", () => {
	it("does not wait for an open pipe that sends nothing when a prompt was given", async () => {
		const stdin = new PassThrough();
		const startedAt = Date.now();
		const result = await Promise.race([
			readPipedStdin(stdin, 50),
			new Promise<string>((resolve) => setTimeout(() => resolve("blocked"), 1_000)),
		]);
		expect(result).toBeUndefined();
		expect(Date.now() - startedAt).toBeLessThan(1_000);
		expect(stdin.destroyed).toBe(true);
	});

	// A slow producer's input must not be dropped without a word.
	it("reports stdin it did not read, once", async () => {
		const stdin = new PassThrough();
		let notices = 0;
		expect(await readPipedStdin(stdin, 50, () => notices++)).toBeUndefined();
		expect(notices).toBe(1);
		expect(formatStdinIgnoredNotice(2_000)).toBe(
			"stdin sent nothing within 2 s and was not read; set PI_STDIN_TIMEOUT_MS=0 to wait for it",
		);
	});

	it("reports nothing when stdin sent data or ended in time", async () => {
		let notices = 0;
		const withData = new PassThrough();
		const reading = readPipedStdin(withData, 50, () => notices++);
		withData.write("input");
		await new Promise((resolve) => setTimeout(resolve, 150));
		withData.end();
		expect(await reading).toBe("input");
		const empty = new PassThrough();
		const emptyReading = readPipedStdin(empty, 50, () => notices++);
		empty.end();
		expect(await emptyReading).toBeUndefined();
		expect(notices).toBe(0);
	});

	it("reads to the end once data arrived, however long the rest takes", async () => {
		const stdin = new PassThrough();
		const reading = readPipedStdin(stdin, 50);
		stdin.write("first part\n");
		await new Promise((resolve) => setTimeout(resolve, 150));
		stdin.end("second part\n");
		expect(await reading).toBe("first part\nsecond part");
	});

	it("waits for the end of input without a timeout", async () => {
		const stdin = new PassThrough();
		const reading = readPipedStdin(stdin);
		await new Promise((resolve) => setTimeout(resolve, 100));
		stdin.end("late input");
		expect(await reading).toBe("late input");
	});

	it("does not read a terminal", async () => {
		const stdin = Object.assign(new PassThrough(), { isTTY: true });
		expect(await readPipedStdin(stdin, 50)).toBeUndefined();
	});

	it("takes the timeout from PI_STDIN_TIMEOUT_MS", () => {
		expect(getStdinFirstDataTimeoutMs({})).toBe(2_000);
		expect(getStdinFirstDataTimeoutMs({ PI_STDIN_TIMEOUT_MS: "0" })).toBe(0);
		expect(getStdinFirstDataTimeoutMs({ PI_STDIN_TIMEOUT_MS: "500" })).toBe(500);
		expect(getStdinFirstDataTimeoutMs({ PI_STDIN_TIMEOUT_MS: "soon" })).toBe(2_000);
	});
});
