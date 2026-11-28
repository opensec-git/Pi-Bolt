// What the in-process grep and find (native-search.ts) return where ripgrep and fd, which Pi runs, are the reference:
// cases a differential test against them found to differ. Each test names its finding of the w132 hunt (hunt-tools-N).
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { createGrepTool } from "../src/core/tools/grep.ts";
import { nativeFind, nativeGrep, requiredLiteral } from "../src/core/tools/native-search.ts";

let root: string;

function write(rel: string, contents: string | Buffer = "needle\n"): void {
	const file = join(root, rel);
	mkdirSync(dirname(file), { recursive: true });
	writeFileSync(file, contents);
}

beforeEach(() => {
	root = mkdtempSync(join(tmpdir(), "pibolt-parity-"));
	// git's global excludes file is looked for under HOME and XDG_CONFIG_HOME: none unless a test writes one.
	vi.stubEnv("HOME", join(root, "home"));
	vi.stubEnv("XDG_CONFIG_HOME", join(root, "home", ".config"));
});

afterEach(() => {
	rmSync(root, { recursive: true, force: true });
});

const grep = async (pattern: string, extra: Partial<Parameters<typeof nativeGrep>[0]> = {}) =>
	(
		await nativeGrep({ root: join(root, "ws"), isDirectory: true, pattern, limit: 100, keepChars: 4000, ...extra })
	).matches.map((m) => `${m.filePath.slice(join(root, "ws").length + 1)}:${m.lineNumber}:${m.lineText}`);

const find = (pattern: string) => nativeFind({ root: join(root, "ws"), pattern, limit: 100 });

describe("find", () => {
	// hunt-tools-1: Pi runs fd with its smart case: a pattern without capital letters matches names in any case.
	test("a pattern in lower case matches names in any case; one with a capital letter only its own case", async () => {
		write("ws/README.md");
		write("ws/Dockerfile");
		write("ws/docs/Guide.MD");
		write("ws/readme.txt");
		expect(await find("readme*")).toEqual(["README.md", "readme.txt"]);
		expect(await find("*dockerfile*")).toEqual(["Dockerfile"]);
		expect(await find("docs/*.md")).toEqual(["docs/Guide.MD"]);
		expect(await find("README*")).toEqual(["README.md"]);
		expect(await find("*.MD")).toEqual(["docs/Guide.MD"]);
	});

	// hunt-tools-3: fd applies git's global excludes file also outside a repository (Pi passes --no-require-git there).
	test("git's global excludes file applies outside a repository", async () => {
		write("home/.config/git/ignore", "*.log\n");
		write("ws/a.log");
		write("ws/b.txt");
		expect(await find("*")).toEqual(["b.txt"]);
	});

	// hunt-tools-4: outside a repository fd runs with --no-require-git, and the .gitignore files above a nested repository
	// still apply inside it.
	test("outside a repository, the .gitignore files above a nested repository apply inside it", async () => {
		write("ws/.gitignore", "*.log\n");
		mkdirSync(join(root, "ws", "proj", ".git"), { recursive: true });
		write("ws/a.log");
		write("ws/proj/b.log");
		write("ws/proj/c.txt");
		expect(await find("*.log")).toEqual([]);
	});
});

describe("grep", () => {
	// hunt-tools-2: ripgrep matches its --glob against folders too: `!vendor` leaves the folder out, and a glob that takes a
	// folder's name walks it even when an ignore file leaves it out.
	test("a glob is matched against folders as well as files", async () => {
		mkdirSync(join(root, "ws", ".git"), { recursive: true });
		write("ws/.gitignore", "gen/\n");
		write("ws/vendor/a.ts");
		write("ws/node_modules/b.js");
		write("ws/src/c.ts");
		write("ws/gen/d.ts");
		expect(await grep("needle", { glob: "!vendor" })).toEqual(["node_modules/b.js:1:needle", "src/c.ts:1:needle"]);
		expect(await grep("needle", { glob: "!node_modules" })).toEqual(["src/c.ts:1:needle", "vendor/a.ts:1:needle"]);
		expect(await grep("needle", { glob: "*" })).toEqual([
			"gen/d.ts:1:needle",
			"node_modules/b.js:1:needle",
			"src/c.ts:1:needle",
			"vendor/a.ts:1:needle",
		]);
	});

	// hunt-tools-3: ripgrep applies git's global excludes file in every repository, nested ones and ones below a search
	// folder that is in none.
	test("git's global excludes file applies in nested repositories and in repositories below the search folder", async () => {
		write("home/.config/git/ignore", "*.log\n");
		mkdirSync(join(root, "ws", "outer", ".git"), { recursive: true });
		mkdirSync(join(root, "ws", "outer", "inner", ".git"), { recursive: true });
		write("ws/outer/a.log");
		write("ws/outer/inner/b.log");
		write("ws/outer/inner/c.txt");
		write("ws/loose.log");
		expect(await grep("needle")).toEqual(["loose.log:1:needle", "outer/inner/c.txt:1:needle"]);
		expect(await grep("needle", { root: join(root, "ws", "outer") })).toEqual(["outer/inner/c.txt:1:needle"]);
	});

	// hunt-tools-5: a `]` escaped inside a class does not end it. `[\]a-z]+bc` has no literal "a-z]" every match contains.
	test("an escaped ] inside a class", async () => {
		expect(requiredLiteral("[\\]a-z]+bc")).toBeUndefined();
		expect(requiredLiteral("[\\]x]yzw")).toBe("yzw");
		write("ws/a.txt", "]abc\nfoo\n");
		expect(await grep("[\\]a-z]+bc")).toEqual(["a.txt:1:]abc"]);
		expect(await grep("[\\]x]abc")).toEqual(["a.txt:1:]abc"]);
	});

	// hunt-tools-6: a CR is not a line break: in a file with CRLF line endings `^$` finds the empty lines only (JavaScript's
	// multiline mode let `^` match after the CR of every line), and `$` still matches before a line's CRLF.
	test("^ and $ in a file with CRLF line endings, and a lone CR inside a line", async () => {
		write("ws/crlf.txt", "first\r\n\r\nthird bar\r\n  \r\nfoo\rbar\r\n");
		write("ws/pad.txt", `${"x\r\n".repeat(20000)}\r\nend\r\n`);
		expect(await grep("^$", { glob: "crlf.txt" })).toEqual(["crlf.txt:2:"]);
		expect(await grep("^\\s*$", { glob: "crlf.txt" })).toEqual(["crlf.txt:2:", "crlf.txt:4:  "]);
		expect(await grep("bar$")).toEqual(["crlf.txt:3:third bar", "crlf.txt:5:foo\rbar"]);
		expect(await grep("^bar")).toEqual([]);
		expect(await grep("\\r$", { glob: "crlf.txt" })).toEqual([
			"crlf.txt:1:first",
			"crlf.txt:2:",
			"crlf.txt:3:third bar",
			"crlf.txt:4:  ",
			"crlf.txt:5:foo\rbar",
		]);
		// An expression without a literal, searched a window of lines at a time.
		expect(await grep("^$", { glob: "pad.txt" })).toEqual(["pad.txt:20001:"]);
	});

	// hunt-tools-8: ripgrep leaves out a UTF-8 byte order mark and searches UTF-16 files that start with one.
	test("byte order marks", async () => {
		write("ws/bom8.cs", Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from("using System;\nusing X;\n")]));
		write(
			"ws/utf16le.txt",
			Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from("first line\r\nusing utf16\r\n", "utf16le")]),
		);
		const be = Buffer.from("using big endian\n", "utf16le").swap16();
		write("ws/utf16be.txt", Buffer.concat([Buffer.from([0xfe, 0xff]), be]));
		expect(await grep("^using")).toEqual([
			"bom8.cs:1:using System;",
			"bom8.cs:2:using X;",
			"utf16be.txt:1:using big endian",
			"utf16le.txt:2:using utf16",
		]);
	});

	// hunt-tools-7: ripgrep's POSIX classes, `\<` and `\>`, and a `]` first in a class.
	test("forms of ripgrep's own syntax that have a JavaScript counterpart", async () => {
		write("ws/a.txt", "hello  there\nthere\nthereafter\n]x\n");
		expect(await grep("[[:space:]]{2}")).toEqual(["a.txt:1:hello  there"]);
		expect(await grep("^[[:alpha:]]+$")).toEqual(["a.txt:2:there", "a.txt:3:thereafter"]);
		expect(await grep("\\<there\\>")).toEqual(["a.txt:1:hello  there", "a.txt:2:there"]);
		expect(await grep("\\<\\w+after\\>")).toEqual(["a.txt:3:thereafter"]);
		expect(await grep("[]]x")).toEqual(["a.txt:4:]x"]);
	});

	// hunt-tools-4: outside a repository, ripgrep reads the .ignore files of the folders above the search folder; and a
	// .ignore file decides over a .gitignore file, a deeper one too.
	test("the .ignore files above the search folder outside a repository, and .ignore over .gitignore", async () => {
		write("top/.ignore", "*.tmp\n");
		write("top/ws/a.tmp");
		write("top/ws/b.txt");
		const below = await nativeGrep({
			root: join(root, "top", "ws"),
			isDirectory: true,
			pattern: "needle",
			limit: 10,
			keepChars: 100,
		});
		expect(below.matches.map((m) => m.filePath.slice(root.length + 1))).toEqual(["top/ws/b.txt"]);
		mkdirSync(join(root, "ws", ".git"), { recursive: true });
		write("ws/.ignore", "!keep.log\n");
		write("ws/sub/.gitignore", "*.log\n");
		write("ws/sub/keep.log");
		write("ws/sub/other.log");
		expect(await grep("needle")).toEqual(["sub/keep.log:1:needle"]);
	});

	// hunt-tools-6: a line of many CRs is passed over at once when it has no match, not tested again from each place.
	test("an anchored expression over lines of many CRs ends quickly", async () => {
		write("ws/cr.txt", `${"x\r".repeat(4000)}y\n`.repeat(200));
		const start = performance.now();
		expect(await grep("y?x$")).toEqual([]);
		expect(performance.now() - start).toBeLessThan(2000);
	});

	// hunt-tools-8: the context lines of a match in a file with a byte order mark, and a UTF-8 one in a file read a piece at
	// a time.
	test("context lines and large files with a byte order mark", async () => {
		write(
			"ws/utf16.txt",
			Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from("one\r\ntwo needle\r\nthree\r\n", "utf16le")]),
		);
		write("ws/bom8.txt", Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from("one\nneedle two\n")]));
		const result = await createGrepTool(join(root, "ws"), { native: true }).execute("t", {
			pattern: "needle",
			context: 1,
		});
		expect((result.content[0] as { text: string }).text).toBe(
			[
				"bom8.txt-1- one",
				"bom8.txt:2: needle two",
				"utf16.txt-1- one",
				"utf16.txt:2: two needle",
				"utf16.txt-3- three",
			].join("\n"),
		);
		write(
			"ws/big.txt",
			Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(`start\n${"line\n".repeat(1_000_000)}`)]),
		);
		expect(await grep("^start", { wholeFileBytes: 1024, pieceBytes: 4096 })).toEqual(["big.txt:1:start"]);
	});
});
