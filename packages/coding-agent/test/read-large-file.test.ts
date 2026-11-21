import { appendFileSync, mkdtempSync, renameSync, rmSync, utimesSync, writeFileSync } from "fs";
import { type FileHandle, open, readFile } from "fs/promises";
import { tmpdir } from "os";
import { join } from "path";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { createReadToolDefinition } from "../src/core/tools/read.ts";

/** Deterministic pseudo-random numbers, so a failing case can be found again. */
function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state * 1103515245 + 12345) & 0x7fffffff;
		return state / 0x7fffffff;
	};
}

/** Text of `bytes` bytes or more with lines of varied length and content (ASCII, multi-byte, invalid UTF-8, "\r"). */
function makeFile(seed: number, bytes: number, options: { longLines?: boolean; invalid?: boolean } = {}): Buffer {
	const next = random(seed);
	const parts: Buffer[] = [];
	let length = 0;
	let line = 0;
	while (length < bytes) {
		const kind = next();
		let text: Buffer;
		if (options.longLines && kind < 0.02) text = Buffer.from("L".repeat(20_000 + Math.floor(next() * 80_000)));
		else if (kind < 0.1) text = Buffer.alloc(0);
		else if (kind < 0.3) text = Buffer.from(`café \u{1f600} 中文 line ${line}`.repeat(1 + Math.floor(next() * 4)));
		else if (options.invalid && kind < 0.4)
			text = Buffer.from([0x61, 0xe2, 0x82, 0xff, 0xc3, 0x80, 0x80, 0xf0, 0x9f]);
		else if (kind < 0.45) text = Buffer.from(`crlf ${line}\r`);
		else text = Buffer.from(`line ${line} ${"x".repeat(Math.floor(next() * 120))}`);
		parts.push(text, Buffer.from("\n"));
		length += text.length + 1;
		line++;
	}
	return Buffer.concat(parts);
}

describe("read tool on files read in chunks", () => {
	let dir: string;
	// The default tool reads files of 1 MiB or more in chunks; one with its own readFile reads them whole, as before.
	const chunked = () => createReadToolDefinition(dir);
	const whole = () =>
		createReadToolDefinition(dir, {
			operations: {
				readFile: (path) => readFile(path),
				access: async () => {},
				detectImageMimeType: async () => null,
			},
		});

	beforeAll(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-read-large-"));
	});

	afterAll(() => {
		rmSync(dir, { recursive: true, force: true });
	});

	async function expectSame(
		file: string,
		args: { offset?: number; limit?: number },
		local = chunked(),
	): Promise<void> {
		const run = async (tool: ReturnType<typeof createReadToolDefinition>) => {
			try {
				const result = await tool.execute("id", { path: file, ...args }, undefined, undefined, undefined as never);
				return result;
			} catch (error) {
				return { error: (error as Error).message };
			}
		};
		const expected = await run(whole());
		const actual = await run(local);
		expect(actual, `${file} ${JSON.stringify(args)}`).toStrictEqual(expected);
	}

	const offsets = (lines: number, next: () => number) => [
		{},
		{ offset: 1 },
		{ limit: 10 },
		{ limit: 0 },
		{ offset: 2, limit: 1 },
		{ offset: Math.floor(lines / 2), limit: 50 },
		{ offset: Math.floor(lines * next()) + 1 },
		{ offset: Math.floor(lines * next()) + 1, limit: Math.floor(next() * 3000) },
		{ offset: lines - 1 },
		{ offset: lines },
		{ offset: lines + 1 },
		{ offset: lines + 5, limit: 3 },
		{ offset: 2.7, limit: 4.2 },
	];

	it("gives the output of reading the whole file", async () => {
		const cases: { name: string; content: Buffer }[] = [
			{ name: "ascii.log", content: makeFile(1, 3 * 1024 * 1024) },
			{ name: "mixed.txt", content: makeFile(2, 1024 * 1024 + 17, { invalid: true }) },
			{ name: "long-lines.txt", content: makeFile(3, 2 * 1024 * 1024, { longLines: true, invalid: true }) },
			{ name: "no-trailing-newline.txt", content: makeFile(4, 1024 * 1024).subarray(0, -1) },
			{ name: "one-line.txt", content: Buffer.from("y".repeat(1024 * 1024 + 3)) },
			{
				name: "first-line-long.txt",
				content: Buffer.concat([Buffer.from("é".repeat(60_000)), Buffer.from("\n"), makeFile(5, 1024 * 1024)]),
			},
			{
				name: "bom-invalid-at-chunk-ends.txt",
				content: Buffer.concat([
					Buffer.from([0xef, 0xbb, 0xbf]),
					Buffer.from("a".repeat(1024 * 1024 - 4)),
					Buffer.from([0xe2, 0x82, 0xac, 0xf0, 0x9f, 0x98, 0x80, 0xc3, 0x0a, 0x80, 0x80, 0x80, 0x80]),
					Buffer.from("\n".repeat(2500)),
					Buffer.from("é".repeat(30_000)),
				]),
			},
			{ name: "newlines.txt", content: Buffer.from("\n".repeat(1024 * 1024 + 1)) },
			{
				// A first line whose size shows as 60.1KB decoded (61,492 bytes) and 60.0KB as raw bytes (61,490).
				name: "first-line-invalid.txt",
				content: Buffer.concat([
					Buffer.from("a".repeat(61_489)),
					Buffer.from([0xff, 0x0a]),
					makeFile(7, 1024 * 1024),
				]),
			},
			{
				// 61,491 bytes decoded shows as 60.0KB, one more as 60.1KB.
				name: "first-line-boundary.txt",
				content: Buffer.concat([
					Buffer.from("a".repeat(61_488)),
					Buffer.from([0xff, 0x0a]),
					makeFile(8, 1024 * 1024),
				]),
			},
		];
		const next = random(42);
		for (const { name, content } of cases) {
			writeFileSync(join(dir, name), content);
			let lines = 1;
			for (const byte of content) if (byte === 0x0a) lines++;
			const local = chunked();
			for (const args of offsets(lines, next)) await expectSame(name, args, local);
		}
	});

	it("reads either side of indexed chunk boundaries and invalidates after writes or file replacement", async () => {
		const name = "changing.log";
		const path = join(dir, name);
		const local = chunked();
		const chunkSize = 1024 * 1024;
		const initial = `${"a".repeat(chunkSize - 1)}\n${"é\r\n".repeat(chunkSize / 2)}last`;
		writeFileSync(path, initial);
		const originalTime = new Date("2025-01-01T00:00:00Z");
		utimesSync(path, originalTime, originalTime);
		await expectSame(name, { offset: 2, limit: 2 }, local);
		await expectSame(name, { offset: 3, limit: 2 }, local);
		await expectSame(name, { offset: chunkSize / 4 + 20, limit: 3 }, local);
		writeFileSync(path, initial.replaceAll("é\r\n", "abcd"));
		utimesSync(path, originalTime, originalTime);
		await expectSame(name, { offset: 3, limit: 2 }, local);
		writeFileSync(`${path}.replacement`, initial);
		utimesSync(`${path}.replacement`, originalTime, originalTime);
		renameSync(`${path}.replacement`, path);
		await expectSame(name, { offset: 3, limit: 2 }, local);
		appendFileSync(path, "\nnew\n");
		await expectSame(name, { offset: chunkSize / 2 + 3, limit: 2 }, local);
		writeFileSync(path, "shortened");
		await expectSame(name, { offset: 2, limit: 2 }, local);
	});

	it("continues at the verified byte position for an adjacent page, including long and empty final lines", async () => {
		const name = "adjacent.log";
		const path = join(dir, name);
		const prefix = Buffer.from("é\r\n".repeat(300_000));
		writeFileSync(path, Buffer.concat([prefix, Buffer.from([0xff, 10]), Buffer.from(`${"a".repeat(90_000)}\n`)]));
		const local = chunked();
		await expectSame(name, { offset: 299_999, limit: 2 }, local);
		const handle = await open(path, "r");
		const prototype: FileHandle = Object.getPrototypeOf(handle);
		await handle.close();
		const pageReadSizes: number[] = [];
		const intercept = new Proxy(prototype.read, {
			apply(target, receiver, args) {
				// The MIME signature probe also reads from byte zero.
				if (args[3] === prefix.length) pageReadSizes.push(args[2]);
				return Reflect.apply(target, receiver, args);
			},
		});
		const spy = vi.spyOn(prototype, "read").mockImplementation(intercept);
		try {
			await expectSame(name, { offset: 300_001, limit: 1 }, local);
			expect(pageReadSizes).toHaveLength(1);
			expect(pageReadSizes[0]).toBeLessThan(1024 * 1024);
			await expectSame(name, { offset: 300_002, limit: 1 }, local);
			await expectSame(name, { offset: 300_003, limit: 1 }, local);
		} finally {
			spy.mockRestore();
		}
	});

	it("follows two open-ended pages across malformed UTF-8 without rereading the tail", async () => {
		const name = "malformed-pages.txt";
		const path = join(dir, name);
		const page = Buffer.concat([Buffer.from("é\r\n".repeat(1999)), Buffer.from([0xff, 10])]);
		writeFileSync(path, Buffer.concat(Array(150).fill(page)));
		const local = chunked();
		await expectSame(name, {}, local);
		const handle = await open(path, "r");
		const prototype: FileHandle = Object.getPrototypeOf(handle);
		await handle.close();
		let requested = 0;
		const intercept = new Proxy(prototype.read, {
			apply(target, receiver, args) {
				requested += args[2];
				return Reflect.apply(target, receiver, args);
			},
		});
		const spy = vi.spyOn(prototype, "read").mockImplementation(intercept);
		try {
			await expectSame(name, { offset: 2001 }, local);
			await expectSame(name, { offset: 4001 }, local);
			expect(requested).toBeLessThan(256 * 1024);
		} finally {
			spy.mockRestore();
		}
	});

	it("reuses measured totals across arbitrary jumps in a byte-preserving suffix", async () => {
		const name = "scattered-reads.txt";
		// This unfinished four-byte sequence decodes to one three-byte replacement character.
		const line = Buffer.concat([Buffer.from("record É "), Buffer.from([0xf0, 0x9f, 0x98, 13, 10])]);
		const records = Buffer.concat(Array(150_000).fill(line));
		for (const ending of ["last line", ""]) {
			writeFileSync(join(dir, name), Buffer.concat([records, Buffer.from(ending)]));
			const local = chunked();
			await expectSame(name, { offset: 1001 }, local);
			await expectSame(name, { offset: 90_001 }, local);
			await expectSame(name, { offset: 75_321, limit: 10 }, local);
			await expectSame(name, { offset: 1201 }, local);
			await expectSame(name, { offset: 150_001 }, local);
			await expectSame(name, { offset: 150_002 }, local);
		}
	});

	it("measures an oversized first line on a continuation and keeps the final empty line addressable", async () => {
		const name = "giant-following-line.txt";
		writeFileSync(
			join(dir, name),
			Buffer.concat([Buffer.from("a\n".repeat(2000)), Buffer.alloc(1_100_000, 0xff), Buffer.from("\n")]),
		);
		const local = chunked();
		await expectSame(name, {}, local);
		await expectSame(name, { offset: 2001 }, local);
		await expectSame(name, { offset: 2002 }, local);
	});

	it("rereads the visible prefix when returning to a measured oversized line after a bounded read", async () => {
		const name = "repeated-oversized.txt";
		const path = join(dir, name);
		writeFileSync(
			path,
			Buffer.concat([Buffer.from("entry\n"), Buffer.alloc(1_200_000, 0xff), Buffer.from("\ntail")]),
		);
		const local = chunked();
		await expectSame(name, { offset: 2 }, local);
		await expectSame(name, { offset: 3, limit: 1 }, local);
		const handle = await open(path, "r");
		const prototype: FileHandle = Object.getPrototypeOf(handle);
		await handle.close();
		const reads: { position: number; length: number }[] = [];
		const intercept = new Proxy(prototype.read, {
			apply(target, receiver, args) {
				if (args[3] !== 0) reads.push({ position: args[3], length: args[2] });
				return Reflect.apply(target, receiver, args);
			},
		});
		const spy = vi.spyOn(prototype, "read").mockImplementation(intercept);
		try {
			await expectSame(name, { offset: 2 }, local);
			expect(reads).toHaveLength(1);
			expect(reads[0]?.position).toBe(6);
			expect(reads[0]?.length).toBeLessThan(128 * 1024);
		} finally {
			spy.mockRestore();
		}
	});

	it.each([
		{ offset: 400_000, limit: undefined },
		{ offset: 400_000, limit: 2 },
		{ offset: 400_002, limit: 2 },
		{ offset: 402_000, limit: undefined },
	])("restarts from byte zero if a rewrite races an indexed read at $offset", async ({ offset, limit }) => {
		const path = join(dir, "racing.log");
		writeFileSync(path, "abc\n".repeat(1024 * 1024));
		const local = chunked();
		const args = { path, offset, limit };
		const page = (tool: ReturnType<typeof chunked>) =>
			tool.execute("id", args, undefined, undefined, undefined as never);
		await local.execute("id", { path, offset: 400_000, limit }, undefined, undefined, undefined as never);
		const handle = await open(path, "r");
		const prototype: FileHandle = Object.getPrototypeOf(handle);
		await handle.close();
		let rewrote = false;
		const intercept = new Proxy(prototype.read, {
			apply(target, receiver, args) {
				if (!rewrote && args[3] > 0) {
					rewrote = true;
					writeFileSync(path, "changed\n".repeat(512 * 1024));
				}
				return Reflect.apply(target, receiver, args);
			},
		});
		const spy = vi.spyOn(prototype, "read").mockImplementation(intercept);
		try {
			const actual = await page(local);
			expect(rewrote).toBe(true);
			expect(actual).toStrictEqual(await page(whole()));
		} finally {
			spy.mockRestore();
		}
	});

	it("keeps files under 1 MiB on the whole-file path", async () => {
		writeFileSync(join(dir, "small.txt"), makeFile(6, 200_000, { invalid: true }));
		await expectSame("small.txt", { offset: 3, limit: 7 });
	});
});
