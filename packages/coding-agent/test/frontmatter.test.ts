import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parseDocument } from "yaml";
import { parseFrontmatter, parseSimpleFrontmatter, stripFrontmatter } from "../src/utils/frontmatter.ts";

/** yaml's result for `yamlString` as parseFrontmatter uses it, or "error"/"warning" when yaml reports one. */
function yamlResult(yamlString: string): unknown {
	const document = parseDocument(yamlString);
	if (document.errors.length > 0) return "error";
	if (document.warnings.length > 0) return "warning";
	return document.toJS() ?? {};
}

/** Checks that the simple parser, where it accepts `yamlString`, gives exactly yaml's result; true when it accepted it. */
function expectSameAsYaml(yamlString: string): boolean {
	const simple = parseSimpleFrontmatter(yamlString);
	if (simple === undefined) return false;
	const expected = yamlResult(yamlString);
	try {
		expect(simple).toStrictEqual(expected);
	} catch (error) {
		throw new Error(`simple parser differs from yaml for ${JSON.stringify(yamlString)}: ${String(error)}`);
	}
	return true;
}

/** Deterministic pseudo-random numbers (mulberry32), so a failing case is the same on every run. */
function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state + 0x6d2b79f5) | 0;
		let t = Math.imul(state ^ (state >>> 15), 1 | state);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

const KEYS = [
	"name",
	"description",
	"argument-hint",
	"disable-model-invocation",
	"_private",
	"a",
	"A1",
	"x_y-z",
	"y",
	"n",
	"on",
	"off",
	"yes",
	"true",
	"False",
	"null",
	"NULL",
	"__proto__",
	"constructor",
	"toString",
	"1abc",
	"-dash",
	"a.b",
	"a b",
	"ключ",
	"k".repeat(129),
];

const VALUES = [
	"",
	" ",
	"plain words",
	"Prompt number 7 does a thing",
	"[file]",
	'"[file]"',
	"'[file]'",
	'"quoted with \\"escape\\""',
	'"tab\\tescape"',
	"'it''s'",
	"'unterminated",
	'"unterminated',
	"'a'b'",
	'"a"b"',
	"''",
	'""',
	"' padded '",
	'" padded "',
	"'a' # comment",
	'"a" # comment',
	"value # comment",
	"value#not-comment",
	"#comment only",
	"a: nested",
	"ends with colon:",
	"url http://example.com:8080/x",
	"a :b",
	"x,y",
	"x}",
	"{x",
	"[x",
	"x]",
	"x ?",
	"? x",
	"!tag",
	"!!str x",
	"x!",
	"&anchor x",
	"*alias",
	"x & y * z",
	"|",
	">",
	"|-",
	"%directive",
	"@reserved",
	"`reserved",
	"-",
	"- item",
	"-5",
	"+5",
	"5",
	"0",
	"007",
	"1e3",
	"0x1F",
	"0o17",
	"1.5",
	".5",
	".inf",
	"-.inf",
	".nan",
	".NaN",
	"inf",
	"nan",
	"NaN",
	"Infinity",
	"12345678901234567890",
	"2024-01-01",
	"1st place",
	"true",
	"True",
	"TRUE",
	"tRUE",
	"false",
	"FALSE",
	"yes",
	"No",
	"on",
	"OFF",
	"y",
	"n",
	"null",
	"Null",
	"NULL",
	"nULL",
	"~",
	"~x",
	"<x",
	"=",
	"<<",
	"a\tb",
	"\tleading tab",
	"trailing tab\t",
	"trailing spaces   ",
	"a  b   c",
	"Don't stop",
	'say "hi" now',
	"caf\u00e9 \u00fcber na\u00efve",
	"\u65e5\u672c\u8a9e\u306e\u8aac\u660e",
	"emoji \u{1F600} ok",
	"\u00a0nbsp\u00a0",
	"ideographic\u3000space",
	"line\u2028separator",
	"para\u2029separator",
	"nel\u0085x",
	"bom\ufeffx",
	"nonchar\ufffex",
	"lone \ud800 surrogate",
	"bell\u0007x",
	"del\u007fx",
	"back\\slash",
	"C:\\path\\to",
	"'single \\ backslash'",
	"percent %d",
	"$ARGUMENTS and $1",
	"...",
	"---",
];

const SEPARATORS = [":", ": ", ":  ", ":\t", " : ", "::", ":", ": "];

function randomValue(next: () => number): string {
	const alphabet = [..."abcXYZ019 :#'\"-_[]{},!&*|>?%@`~.\\/\t\u00e9\u00a0\u3000\u2028\ufeff", "\u{1F600}"];
	const length = Math.floor(next() * 12);
	let value = "";
	for (let i = 0; i < length; i++) value += alphabet[Math.floor(next() * alphabet.length)];
	return value;
}

/** A value of words with punctuation inside them, as descriptions have: mostly accepted by the simple parser. */
function randomWords(next: () => number): string {
	const letters = [..."abcdefXYZ\u00e9\u65e5", "\u{1F600}"];
	const inner = [..."':#\"-,.!?&*%@`~/\\[]{}|>=<"];
	const words: string[] = [];
	const count = 1 + Math.floor(next() * 4);
	for (let i = 0; i < count; i++) {
		let word = pick(letters, next);
		const length = Math.floor(next() * 6);
		for (let j = 0; j < length; j++) word += next() < 0.25 ? pick(inner, next) : pick(letters, next);
		words.push(word);
	}
	return words.join(next() < 0.9 ? " " : "  ");
}

function pick<T>(items: readonly T[], next: () => number): T {
	return items[Math.floor(next() * items.length)];
}

describe("parseFrontmatter", () => {
	it("parses keys, strips quotes, and returns body", () => {
		const input = "---\nname: \"skill-name\"\ndescription: 'A desc'\nfoo-bar: value\n---\n\nBody text";
		const { frontmatter, body } = parseFrontmatter<Record<string, string>>(input);
		expect(frontmatter.name).toBe("skill-name");
		expect(frontmatter.description).toBe("A desc");
		expect(frontmatter["foo-bar"]).toBe("value");
		expect(body).toBe("Body text");
	});

	it("normalizes newlines and handles CRLF", () => {
		const input = "---\r\nname: test\r\n---\r\nLine one\r\nLine two";
		const { body } = parseFrontmatter<Record<string, string>>(input);
		expect(body).toBe("Line one\nLine two");
	});

	it("throws on invalid YAML frontmatter", () => {
		const input = "---\nfoo: [bar\n---\nBody";
		expect(() => parseFrontmatter<Record<string, string>>(input)).toThrow(/at line 1, column 10/);
	});

	it("parses | multiline yaml syntax", () => {
		const input = "---\ndescription: |\n  Line one\n  Line two\n---\n\nBody";
		const { frontmatter, body } = parseFrontmatter<Record<string, string>>(input);
		expect(frontmatter.description).toBe("Line one\nLine two\n");
		expect(body).toBe("Body");
	});

	it("returns original content when frontmatter is missing or unterminated", () => {
		const noFrontmatter = "Just text\nsecond line";
		const missingEnd = "---\nname: test\nBody without terminator";
		const resultNoFrontmatter = parseFrontmatter<Record<string, string>>(noFrontmatter);
		const resultMissingEnd = parseFrontmatter<Record<string, string>>(missingEnd);
		expect(resultNoFrontmatter.body).toBe("Just text\nsecond line");
		expect(resultMissingEnd.body).toBe(
			"---\nname: test\nBody without terminator".replace(/\r\n/g, "\n").replace(/\r/g, "\n"),
		);
	});

	it("returns empty object for empty or comment-only frontmatter", () => {
		const input = "---\n# just a comment\n---\nBody";
		const { frontmatter } = parseFrontmatter(input);
		expect(frontmatter).toEqual({});
	});
});

describe("parseSimpleFrontmatter", () => {
	it("reads simple key: value frontmatter as yaml does", () => {
		expect(parseSimpleFrontmatter('description: Prompt number 1 does a thing\nargument-hint: "[file]"')).toEqual({
			description: "Prompt number 1 does a thing",
			"argument-hint": "[file]",
		});
		expect(parseSimpleFrontmatter("name: x\ndisable-model-invocation: true\nempty:\nnone: ~\n\nq: 'it''s'")).toEqual({
			name: "x",
			"disable-model-invocation": true,
			empty: null,
			none: null,
			q: "it's",
		});
	});

	it("leaves everything else to yaml", () => {
		for (const yamlString of [
			"description: |\n  Line one",
			"tags:\n  - a",
			"a: [x, y]",
			"a: 1",
			"a: &x b",
			"a: !tag b",
			"# comment\na: b",
			"a: b # comment",
			"a: x\na: y",
			"  a: b",
			"a: b\n  continued",
			"a:b",
			'a: "esc\\"aped"',
			"null: x",
		]) {
			expect(parseSimpleFrontmatter(yamlString), yamlString).toBeUndefined();
		}
	});

	it("gives yaml's result for every generated frontmatter it accepts", () => {
		const next = random(20261110);
		let cases = 0;
		let accepted = 0;
		const check = (yamlString: string) => {
			cases++;
			if (expectSameAsYaml(yamlString)) accepted++;
		};
		// Every key with every listed value and separator.
		for (const key of KEYS) {
			for (const value of VALUES) {
				check(`${key}: ${value}`);
				check(`${key}:${value}`);
			}
		}
		for (const separator of SEPARATORS) {
			for (const value of VALUES) check(`name${separator}${value}`);
		}
		// Random documents of up to five lines mixing listed and random values, empty lines, duplicate keys and
		// trailing spaces.
		for (let i = 0; i < 12000; i++) {
			const lines: string[] = [];
			const lineCount = 1 + Math.floor(next() * 5);
			for (let line = 0; line < lineCount; line++) {
				const roll = next();
				if (roll < 0.05) {
					lines.push("");
					continue;
				}
				const key = next() < 0.8 ? pick(KEYS.slice(0, 12), next) : pick(KEYS, next);
				const roll2 = next();
				const value = roll2 < 0.3 ? pick(VALUES, next) : roll2 < 0.5 ? randomValue(next) : randomWords(next);
				const separator = next() < 0.85 ? ": " : pick(SEPARATORS, next);
				const trailing = next() < 0.1 ? "  " : "";
				lines.push(`${key}${separator}${value}${trailing}`);
			}
			check(lines.join("\n"));
		}
		expect(cases).toBeGreaterThan(10000);
		// The corpus must exercise the simple parser, not only its fallback.
		expect(accepted).toBeGreaterThan(3000);
	});

	it("gives yaml's result for whole files with Windows line ends and a byte order mark", () => {
		const next = random(7);
		for (let i = 0; i < 2000; i++) {
			const lines = [`description: ${pick(VALUES, next)}`, `argument-hint: ${pick(VALUES, next)}`];
			const content = `﻿---\r\n${lines.join("\r\n")}\r\n---\r\nBody ${i}\r\n`;
			const yamlString = lines.join("\n");
			const simple = parseSimpleFrontmatter(yamlString);
			if (simple === undefined) continue;
			const expected = yamlResult(yamlString);
			expect(parseFrontmatter(content), JSON.stringify(content)).toStrictEqual({
				frontmatter: expected,
				body: `Body ${i}`,
			});
		}
	});

	it("gives yaml's result for every frontmatter block in the package", () => {
		const packageDir = join(dirname(fileURLToPath(import.meta.url)), "..");
		const markdownFiles: string[] = [];
		const walk = (dir: string) => {
			for (const entry of readdirSync(dir, { withFileTypes: true })) {
				if (entry.name === "node_modules" || entry.name === "dist" || entry.name.startsWith(".")) continue;
				const path = join(dir, entry.name);
				if (entry.isDirectory()) walk(path);
				else if (entry.isFile() && entry.name.endsWith(".md")) markdownFiles.push(path);
			}
		};
		walk(packageDir);
		let blocks = 0;
		for (const file of markdownFiles) {
			const normalized = readFileSync(file, "utf-8").replace(/^﻿/, "").replace(/\r\n?/g, "\n");
			if (!normalized.startsWith("---")) continue;
			const end = normalized.indexOf("\n---", 3);
			if (end === -1) continue;
			blocks++;
			expectSameAsYaml(normalized.slice(4, end));
		}
		expect(blocks).toBeGreaterThan(10);
	});
});

describe("stripFrontmatter", () => {
	it("removes frontmatter and trims body", () => {
		const input = "---\nkey: value\n---\n\nBody\n";
		expect(stripFrontmatter(input)).toBe("Body");
	});

	it("returns body when no frontmatter present", () => {
		const input = "\n  No frontmatter body  \n";
		expect(stripFrontmatter(input)).toBe("\n  No frontmatter body  \n");
	});
});
