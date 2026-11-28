// What the in-process grep and find (native-search.ts) return where ripgrep and fd, which Pi runs, are the reference:
// cases a differential test against them found to differ. Each test names its finding of the w132 hunt (hunt-tools-N).
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { createGrepTool } from "../src/core/tools/grep.ts";
import { NAMED_FILE_NULS, nativeFind, nativeGrep, requiredLiteral } from "../src/core/tools/native-search.ts";

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

	// hunt-tools-9: ripgrep and fd stop at their limit; the walk of the whole tree came first, and took minutes in a home
	// folder. A part of the walk that has the results is the last one walked: "0/0x" in the second part is not reached.
	test("a search that has its results in the first part of the walk walks no further", async () => {
		for (const name of ["x1", "x2", "x3", "x4", "x5"]) write(`ws/${name}`);
		write("ws/0/0x");
		const parts = { walkPartEntries: 3 };
		expect(await nativeFind({ root: join(root, "ws"), pattern: "*x*", limit: 3, ...parts })).toEqual([
			"x1",
			"x2",
			"x3",
		]);
		expect(await grep("needle", { limit: 3, ...parts })).toEqual(["x1:1:needle", "x2:1:needle", "x3:1:needle"]);
		// Within one part, as without parts: in path order.
		expect(await nativeFind({ root: join(root, "ws"), pattern: "*x*", limit: 3 })).toEqual(["0/0x", "x1", "x2"]);
	});

	// hunt-tools-10: ripgrep searches a file named on its own even when it has NUL bytes; a file with one that the walk finds
	// is passed over, as before. How depends on how it reads the file (NAMED_FILE_NULS): on macOS each NUL byte is a line
	// break and the lines are numbered so; on Linux and Windows, where it maps the file into memory, the NUL bytes are
	// characters of their lines. (Linux CI, ripgrep 14.1.0, failed the macOS expectation: PR #351.)
	test("a file named on its own with NUL bytes", async () => {
		write("ws/bin.dat", Buffer.from("needle one\nabc\0def needle\nneedle two\nx\0\0y\nlast needle"));
		const asLineBreaks = ["1:needle one", "3:def needle", "4:needle two", "8:last needle"];
		const asText = ["1:needle one", "2:abc\0def needle", "3:needle two", "5:last needle"];
		const named = {
			root: join(root, "ws", "bin.dat"),
			isDirectory: false,
			pattern: "needle",
			limit: 10,
			keepChars: 100,
		};
		const lines = (result: Awaited<ReturnType<typeof nativeGrep>>) =>
			result.matches.map((m) => `${m.lineNumber}:${m.lineText}`);
		for (const [namedFileNuls, expected] of [
			["nulIsLineBreak", asLineBreaks],
			["nulIsText", asText],
		] as const) {
			expect(lines(await nativeGrep({ ...named, namedFileNuls }))).toEqual(expected);
			const pieces = { ...named, namedFileNuls, wholeFileBytes: 8, pieceBytes: 16 };
			expect(lines(await nativeGrep(pieces))).toEqual(expected);
		}
		expect(NAMED_FILE_NULS).toBe(process.platform === "darwin" ? "nulIsLineBreak" : "nulIsText");
		expect(await grep("needle")).toEqual([]);
		const withContext = await createGrepTool(join(root, "ws"), { native: true }).execute("t", {
			pattern: "needle",
			path: "bin.dat",
			context: 1,
		});
		const text = (withContext.content[0] as { text: string }).text;
		expect(text).toBe(
			(NAMED_FILE_NULS === "nulIsLineBreak"
				? [
						"bin.dat:1: needle one",
						"bin.dat-2- abc",
						"bin.dat:3: def needle",
						"bin.dat:4: needle two",
						"bin.dat-5- x",
						"bin.dat-7- y",
						"bin.dat:8: last needle",
					]
				: [
						"bin.dat:1: needle one",
						"bin.dat:2: abc\0def needle",
						"bin.dat:3: needle two",
						"bin.dat-4- x\0\0y",
						"bin.dat:5: last needle",
					]
			).join("\n"),
		);
		if (spawnSync("rg", ["--version"], { stdio: "ignore" }).status === 0) {
			const withRg = await createGrepTool(join(root, "ws"), { native: false }).execute("t", {
				pattern: "needle",
				path: "bin.dat",
				context: 1,
			});
			expect((withRg.content[0] as { text: string }).text).toBe(text);
		}
	});
});
