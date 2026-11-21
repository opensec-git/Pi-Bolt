import assert from "node:assert/strict";
import { test } from "node:test";
import { AltScreenSearchIndex } from "../src/alt-screen-search.ts";
import { StdinBuffer } from "../src/stdin-buffer.ts";
import { BoundedTerminalWriter } from "../src/terminal-writer.ts";
import { visibleWidth, wrapTextWithAnsi } from "../src/utils.ts";

test("search tracks terminal columns across blank lines, truncation and in-place edits", () => {
	const lines = ["    old", "", "\x1b[31m界\x1b[0m  beta", ...Array<string>(400).fill("elsewhere")];
	const index = new AltScreenSearchIndex();
	const matches = () => index.search(lines, "old 界 beta").matches;
	const expected = [
		{
			segments: [
				{ row: 0, startCol: 4, endCol: 7 },
				{ row: 2, startCol: 0, endCol: 2 },
				{ row: 2, startCol: 4, endCol: 8 },
			],
		},
	];
	assert.deepEqual(matches(), expected);
	lines.length = 3;
	assert.deepEqual(matches(), expected);
	lines[2] = "beta";
	assert.deepEqual(matches(), []);
	lines.length = 0;
	assert.deepEqual(matches(), []);
	lines.push("old", "界 beta");
	assert.deepEqual(matches(), [
		{
			segments: [
				{ row: 0, startCol: 0, endCol: 3 },
				{ row: 1, startCol: 0, endCol: 2 },
				{ row: 1, startCol: 3, endCol: 7 },
			],
		},
	]);
});

test("bounded replay preserves UTF-8 at the chunk boundary and synchronized output order", () => {
	const chunks: string[] = [];
	const out = new BoundedTerminalWriter((chunk) => chunks.push(chunk));
	const begin = "\x1b[?2026h";
	const end = "\x1b[?2026l";
	const body = `${"a".repeat(1024 * 1024 - begin.length - 1)}😀${"z".repeat(1024 * 1024)}`;
	out.append(begin);
	out.append(body);
	out.append(end);
	out.flush();
	assert.ok(chunks.length >= 3);
	assert.ok(chunks.every((chunk) => chunk.length <= 1024 * 1024));
	assert.equal(Buffer.concat(chunks.map((chunk) => Buffer.from(chunk))).toString(), begin + body + end);
});

test("independently split paste delimiters preserve false prefixes, Unicode and following keys", () => {
	const start = "\x1b[200~";
	const end = "\x1b[201~";
	const body = `${"漢".repeat(8192)}\x1b[201x😀`;
	for (let i = 1; i < start.length; i++) {
		for (let j = 1; j < end.length; j++) {
			const events: string[][] = [];
			const input = new StdinBuffer();
			input.on("data", (value) => events.push(["key", value]));
			input.on("paste", (value) => events.push(["paste", value]));
			for (const chunk of [
				`q${start.slice(0, i)}`,
				start.slice(i),
				body,
				end.slice(0, j),
				`${end.slice(j) + start}2${end}\x1b[A`,
			])
				input.process(chunk);
			assert.deepEqual(events, [
				["key", "q"],
				["paste", body],
				["paste", "2"],
				["key", "\x1b[A"],
			]);
			assert.equal(input.getBuffer(), "");
			input.destroy();
		}
	}
});

test("wrapping respects whitespace around long words and whole wide graphemes", () => {
	assert.deepEqual(wrapTextWithAnsi("  ab    cd   abcdefgh", 4), ["  ab", "cd", "abcd", "efgh"]);
	assert.equal(visibleWidth("漢\ufe0f각"), 4);
	assert.equal(visibleWidth("Aか\u3099カ漢"), 7);
	assert.deepEqual(wrapTextWithAnsi("Aか\u3099カ漢", 4), ["Aか\u3099", "カ漢"]);
});

test("search keeps earlier results intact when edits create or remove a cross-row match", () => {
	const index = new AltScreenSearchIndex();
	const lines = ["a a", "a", "", "b"];
	const first = index.search(lines, "a a").matches;
	assert.equal(first.length, 1);
	lines[3] = "a a";
	const updated = index.search(lines, "a a").matches;
	assert.equal(first.length, 1);
	assert.deepEqual(updated[1]?.segments, [
		{ row: 1, startCol: 0, endCol: 1 },
		{ row: 3, startCol: 0, endCol: 1 },
	]);
	lines[0] = "a";
	assert.deepEqual(index.search(lines, "a a").matches[1]?.segments, [
		{ row: 3, startCol: 0, endCol: 1 },
		{ row: 3, startCol: 2, endCol: 3 },
	]);
	assert.equal(index.search(lines, "a").matches.length, 4);
});

test("empty slots in an extension's render output separate searchable rows", () => {
	const lines = new Array<string>(3);
	lines[0] = "Word";
	lines[2] = "  WORD";
	assert.deepEqual(new AltScreenSearchIndex().search(lines, "word word").matches, [
		{
			segments: [
				{ row: 0, startCol: 0, endCol: 4 },
				{ row: 2, startCol: 2, endCol: 6 },
			],
		},
	]);
});
