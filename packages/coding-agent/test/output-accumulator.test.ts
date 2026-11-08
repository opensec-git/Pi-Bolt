import { mkdtempSync, rmSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { afterEach, describe, expect, it, vi } from "vitest";
import type * as OutputFiles from "../src/utils/output-files.ts";

const scratch = mkdtempSync(join(tmpdir(), "pi-output-accumulator-"));

// The full output file with a size limit of 1000 bytes, as the real one is past 1 GiB.
vi.mock("../src/utils/output-files.ts", async (importOriginal) => {
	const actual = await importOriginal<typeof OutputFiles>();
	let files = 0;
	return {
		...actual,
		createOutputFile: () => new actual.OutputFile(join(scratch, `out-${files++}.log`), 1000),
	};
});

import { OutputAccumulator } from "../src/core/tools/output-accumulator.ts";
import { truncateTail } from "../src/core/tools/truncate.ts";

describe("OutputAccumulator.readFullOutput", () => {
	afterEach(() => {
		rmSync(scratch, { recursive: true, force: true });
	});

	it("ends with the output's real end when the full output file reached its size limit", async () => {
		const output = new OutputAccumulator({ maxBytes: 100 });
		output.append(Buffer.from("first line\n"));
		for (let i = 0; i < 30; i++) output.append(Buffer.from(`${"y".repeat(99)}\n`));
		output.append(Buffer.from("TESTS: 3 failed\n"));
		output.finish();
		output.closeTempFile();

		const full = await output.readFullOutput(200);

		expect(full.truncated).toBe(true);
		const [head, omitted, tail] = full.content.split(/\n\n\[\.\.\. (\d+) bytes omitted \.\.\.\]\n\n/);
		expect(head.startsWith("first line\n")).toBe(true);
		expect(tail).toMatch(/TESTS: 3 failed\n?$/);
		expect(full.content).not.toContain("was not saved");
		// Of all the output, not of the file.
		expect(Number(omitted)).toBe(11 + 30 * 100 + 16 - head.length - tail.length);
	});
});

describe("OutputAccumulator.snapshot", () => {
	afterEach(() => {
		rmSync(scratch, { recursive: true, force: true });
	});

	/** A deterministic pseudo-random sequence (mulberry32), so a failure can be reproduced. */
	function random(seed: number): () => number {
		let a = seed;
		return () => {
			a = (a + 0x6d2b79f5) | 0;
			let t = Math.imul(a ^ (a >>> 15), 1 | a);
			t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
			return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
		};
	}

	function lines(count: number, width: number, extra = ""): string {
		let text = "";
		for (let i = 0; i < count; i++) text += `line ${i} ${"x".repeat(width)}${i % 97 === 0 ? extra : ""}\n`;
		return text;
	}

	/** The output in pieces: one, of 256 KB (a pipe's reads), or of random sizes cut anywhere, inside characters too. */
	function chunkings(bytes: Buffer): Buffer[][] {
		const fixed: Buffer[] = [];
		for (let at = 0; at < bytes.length; at += 256 * 1024) fixed.push(bytes.subarray(at, at + 256 * 1024));
		const next = random(bytes.length);
		const varied: Buffer[] = [];
		for (let at = 0; at < bytes.length; ) {
			const size = next() < 0.3 ? Math.floor(next() * 64) + 1 : Math.floor(next() * 300 * 1024) + 1;
			varied.push(bytes.subarray(at, at + size));
			at += size;
		}
		return [[bytes], fixed, varied];
	}

	// The tail kept for a command's output is the same however the output arrives, and what truncating all of it keeps.
	it.each([
		["ASCII, cut by bytes", lines(40_000, 70)],
		["ASCII, cut by lines", lines(40_000, 5)],
		["non-ASCII now and then", lines(40_000, 70, "é😀")],
		["ASCII, then a non-ASCII end", `${lines(20_000, 70)}${lines(10, 70, "日本語")}`],
		["ASCII without a final newline", `${lines(20_000, 70)}no newline at the end`],
		["one long last line", `${lines(5_000, 70)}${"z".repeat(300 * 1024)}`],
	])("%s", (_name, text) => {
		const bytes = Buffer.from(text, "utf-8");
		const expected = truncateTail(text);
		for (const chunks of chunkings(bytes)) {
			const output = new OutputAccumulator();
			for (const chunk of chunks) output.append(chunk);
			output.finish();
			const { content, truncation } = output.snapshot();
			output.closeTempFile();
			if (!expected.lastLinePartial) expect(content).toBe(expected.content);
			expect(truncation.totalLines).toBe(expected.totalLines);
			expect(truncation.totalBytes).toBe(expected.totalBytes);
			expect(truncation.truncated).toBe(expected.truncated);
		}
	});

	it("keeps the same tail with ASCII chunks larger than the rolling window as with small ones", () => {
		const text = lines(30_000, 70);
		const bytes = Buffer.from(text, "utf-8");
		const results = chunkings(bytes).map((chunks) => {
			const output = new OutputAccumulator();
			for (const chunk of chunks) output.append(chunk);
			output.finish();
			const snapshot = output.snapshot();
			output.closeTempFile();
			return { snapshot, lastLineBytes: output.getLastLineBytes() };
		});
		for (const result of results.slice(1)) expect(result).toEqual(results[0]);
	});
});
