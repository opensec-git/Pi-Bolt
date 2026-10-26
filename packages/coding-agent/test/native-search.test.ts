import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import { createFindTool } from "../src/core/tools/find.ts";
import { createGrepTool } from "../src/core/tools/grep.ts";
import {
	compileGlob,
	expandBraces,
	globMatch,
	nativeFind,
	nativeGrep,
	requiredLiteral,
	translatePattern,
	walk,
} from "../src/core/tools/native-search.ts";

let root: string;

function write(rel: string, contents: string | Buffer = "needle\n"): void {
	const file = join(root, rel);
	mkdirSync(dirname(file), { recursive: true });
	writeFileSync(file, contents);
}

/**
 * A repository: ignore rules of every kind (.gitignore with negation, folder-only and anchored rules, .ignore, a parent's
 * rules, .git/info/exclude), hidden files, a binary file, a symbolic link, a nested repository with rules of its own.
 */
beforeAll(() => {
	root = mkdtempSync(join(tmpdir(), "pibolt-search-"));
	mkdirSync(join(root, ".git", "info"), { recursive: true });
	writeFileSync(join(root, ".git", "HEAD"), "ref: refs/heads/main\n");
	writeFileSync(join(root, ".git", "info", "exclude"), "excluded.txt\n");
	write(".gitignore", "build/\n*.log\n!keep.log\n/rooted.ts\ndocs/**/*.tmp\n");
	write(".ignore", "src/generated/\n");
	write("src/a.ts", "const needle = 1;\nconst other = 2;\nfunction needleFn() {}\n");
	write("src/sub/b.ts", "needle two\r\nNEEDLE upper\r\n");
	write("src/sub/rooted.ts", "needle not rooted\n");
	write("src/generated/g.ts", "needle generated\n");
	write("src/.gitignore", "local.ts\n");
	write("src/local.ts", "needle local\n");
	write("rooted.ts", "needle rooted\n");
	write("build/out.ts", "needle build\n");
	write("app.log", "needle log\n");
	write("keep.log", "needle keep\n");
	write("excluded.txt", "needle excluded\n");
	write("docs/a/b.tmp", "needle tmp\n");
	write("docs/a/b.md", "needle md\n");
	write(".hidden/h.ts", "needle hidden\n");
	write("bin.dat", Buffer.from("needle\0binary\n"));
	write("dir.ts/inner.txt", "no match here\n");
	write("nested/.git/HEAD", "ref\n");
	write("nested/.gitignore", "skip.ts\n");
	write("nested/skip.ts", "needle skip\n");
	write("nested/build/keep.ts", "needle nested build\n"); // (The outer repository's build/ rule stops at the nested one.)
	symlinkSync(join(root, "src", "a.ts"), join(root, "link.ts"));
});

afterAll(() => {
	rmSync(root, { recursive: true, force: true });
});

describe("globs", () => {
	test("*, ? and [...] stay within a name; ** crosses folders", () => {
		expect(globMatch("*.ts", "a.ts")).toBe(true);
		expect(globMatch("*.ts", "src/a.ts")).toBe(false);
		expect(globMatch("**/*.ts", "a.ts")).toBe(true);
		expect(globMatch("**/*.ts", "src/sub/a.ts")).toBe(true);
		expect(globMatch("src/**/*.ts", "src/a.ts")).toBe(true);
		expect(globMatch("src/**", "src/x/y")).toBe(true);
		expect(globMatch("a?c", "abc")).toBe(true);
		expect(globMatch("a?c", "a/c")).toBe(false);
		expect(globMatch("[a-c]x", "bx")).toBe(true);
		expect(globMatch("[!a-c]x", "bx")).toBe(false);
		expect(globMatch("\\*x", "*x")).toBe(true);
		expect(globMatch("[x", "[x")).toBe(true);
	});

	// Trying every way each `*` could end took 28 s on the first of these (exponential time); it is linear now.
	test("many stars match in linear time", () => {
		const stars = `${"*a".repeat(10)}*b`;
		const as = "a".repeat(40);
		const start = performance.now();
		expect(globMatch(stars, as)).toBe(false);
		expect(compileGlob(stars)(as)).toBe(false);
		expect(globMatch(stars, `${as}b`)).toBe(true);
		// A match found only after the search the first way gives up on.
		expect(globMatch(`**/${stars}`, `${as}/${"a".repeat(10)}b`)).toBe(true);
		expect(globMatch(`${"**/".repeat(10)}${stars}`, `${"a/".repeat(30)}${as}`)).toBe(false);
		expect(globMatch(`${"**a".repeat(10)}**b`, `${as}/${as}`)).toBe(false);
		// The budget carries over from one match to the next: a match after a hard one starts with what is left.
		const match = compileGlob(stars);
		expect([
			match(as),
			match(`${as}b`),
			match(as),
			match(`${"a".repeat(10)}b`),
			match("ab"),
			match(`${as}b`),
		]).toEqual([false, true, false, true, false, true]);
		expect(performance.now() - start).toBeLessThan(100);
	});

	test("braces expand", () => {
		expect(expandBraces("*.{ts,tsx}")).toEqual(["*.ts", "*.tsx"]);
		expect(expandBraces("{a,b{c,d}}")).toEqual(["a", "bc", "bd"]);
		expect(expandBraces("plain")).toEqual(["plain"]);
	});
});

describe("regular expressions", () => {
	test("ripgrep's inline flag and named groups", () => {
		expect(translatePattern("(?i)foo")).toEqual({ source: "foo", ignoreCase: true });
		expect(translatePattern("(?P<word>\\w+)")).toEqual({ source: "(?<word>\\w+)", ignoreCase: false });
		expect(translatePattern("n\\u{65}edle")).toEqual({ source: "n\\u0065edle", ignoreCase: false });
		expect(translatePattern("\\x{1F600}|\\\\u{65}").source).toBe("(?:\\ud83d\\ude00)|\\\\u{65}");
	});

	test("the literal every match contains, only when it is sure", () => {
		expect(requiredLiteral("function needleFn\\(")).toBe("function needleFn");
		expect(requiredLiteral("needles?")).toBe("needle");
		expect(requiredLiteral("foo|bar")).toBeUndefined();
		expect(requiredLiteral("(abc)?def")).toBe("def");
		expect(requiredLiteral("a.b")).toBeUndefined();
		// An escape that stands for a character (or more) is not its text: `\x66oo` matches "foo", not "66oo".
		expect(requiredLiteral("\\x66oo")).toBeUndefined();
		expect(requiredLiteral("n\\u0065edle")).toBeUndefined();
		expect(requiredLiteral("\\p{L}abc")).toBeUndefined();
		expect(requiredLiteral("(a)\\1bcd")).toBeUndefined();
		expect(requiredLiteral("needle\\.\\d+")).toBe("needle");
	});
});

describe("the walk", () => {
	test("leaves out what the ignore files do, and .git", async () => {
		const files = (await walk({ root, requireGit: true }))
			.filter((entry) => entry.kind !== "dir")
			.map((entry) => entry.rel)
			.sort();
		expect(files).toEqual([
			".gitignore",
			".hidden/h.ts",
			".ignore",
			"bin.dat",
			"dir.ts/inner.txt",
			"docs/a/b.md",
			"keep.log",
			"link.ts",
			"nested/.gitignore",
			"nested/build/keep.ts",
			"src/.gitignore",
			"src/a.ts",
			"src/sub/b.ts",
			"src/sub/rooted.ts",
		]);
	});

	test("keeps only the entries asked for, and still walks the folders it does not keep", async () => {
		const all = await walk({ root, requireGit: true });
		const kept = await walk({ root, requireGit: true, keep: (rel, kind) => kind === "file" && rel.endsWith(".ts") });
		const ts = all.filter((entry) => entry.kind === "file" && entry.rel.endsWith(".ts"));
		expect(kept.map((entry) => entry.rel).sort()).toEqual(ts.map((entry) => entry.rel).sort());
		expect(kept.map((entry) => entry.rel)).toContain("src/sub/b.ts");
	});

	test("a folder below the repository's root still has its parents' rules", async () => {
		const files = (await walk({ root: join(root, "src"), requireGit: true })).map((entry) => entry.rel).sort();
		expect(files).toEqual([".gitignore", "a.ts", "sub", "sub/b.ts", "sub/rooted.ts"]);
	});

	test("outside a repository, .gitignore counts for find and not for grep", async () => {
		const outside = mkdtempSync(join(tmpdir(), "pibolt-norepo-"));
		try {
			writeFileSync(join(outside, ".gitignore"), "x.ts\n");
			writeFileSync(join(outside, "x.ts"), "needle\n");
			expect((await walk({ root: outside, requireGit: true })).map((e) => e.rel)).toContain("x.ts");
			expect((await walk({ root: outside, requireGit: false })).map((e) => e.rel)).not.toContain("x.ts");
		} finally {
			rmSync(outside, { recursive: true, force: true });
		}
	});
});

describe("grep", () => {
	const grep = (pattern: string, extra: Partial<Parameters<typeof nativeGrep>[0]> = {}) =>
		nativeGrep({ root, isDirectory: true, pattern, limit: 100, keepChars: 4000, ...extra });
	const where = (result: Awaited<ReturnType<typeof nativeGrep>>) =>
		result.matches.map((m) => `${m.filePath.slice(root.length + 1)}:${m.lineNumber}:${m.lineText}`);

	test("a literal, line by line in path order; binary files and symbolic links passed over", async () => {
		expect(where(await grep("needle", { literal: true }))).toEqual([
			".hidden/h.ts:1:needle hidden",
			"docs/a/b.md:1:needle md",
			"keep.log:1:needle keep",
			"nested/build/keep.ts:1:needle nested build",
			"src/a.ts:1:const needle = 1;",
			"src/a.ts:3:function needleFn() {}",
			"src/sub/b.ts:1:needle two",
			"src/sub/rooted.ts:1:needle not rooted",
		]);
	});

	// As ripgrep's --glob, a file the glob takes is searched even when an ignore file leaves it out (not in an ignored folder).
	test("a regular expression, case-insensitive, and a glob", async () => {
		expect(where(await grep("^needle\\s\\w+$", { ignoreCase: true, glob: "*.ts" }))).toEqual([
			".hidden/h.ts:1:needle hidden",
			"nested/skip.ts:1:needle skip",
			"rooted.ts:1:needle rooted",
			"src/local.ts:1:needle local",
			"src/sub/b.ts:1:needle two",
			"src/sub/b.ts:2:NEEDLE upper",
		]);
		expect(where(await grep("(?i)^NEEDLE two", { glob: "src/**/*.ts" }))).toEqual(["src/sub/b.ts:1:needle two"]);
		expect(where(await grep("needle", { glob: "!*.ts" })).map((line) => line.split(":")[0])).toEqual([
			"docs/a/b.md",
			"keep.log",
		]);
	});

	test("a literal in either case, and an expression run only on the lines that have its literal", async () => {
		expect(where(await grep("NEEDLE TWO", { literal: true, ignoreCase: true }))).toEqual([
			"src/sub/b.ts:1:needle two",
		]);
		expect(where(await grep("needle\\w*\\(", {}))).toEqual(["src/a.ts:3:function needleFn() {}"]);
	});

	test("an escape that stands for a character", async () => {
		expect(where(await grep("\\x6eeedle two"))).toEqual(["src/sub/b.ts:1:needle two"]);
	});

	test("a match is within one line", async () => {
		expect(where(await grep("needle = 1;\\s+const"))).toEqual([]);
		expect(where(await grep("other = 2;\\s*"))).toEqual(["src/a.ts:2:const other = 2;"]);
	});

	test("the limit stops the search", async () => {
		const result = await grep("needle", { limit: 3 });
		expect(result.limitReached).toBe(true);
		expect(result.matches).toHaveLength(3);
	});

	// A file larger than `wholeFileBytes` is read a piece at a time: the same matches, line numbers and lines as when it is
	// read whole, with lines cut by the pieces anywhere (the 7-byte pieces cut most lines, and the long line many times).
	test("a file read a piece at a time has the matches of the file read whole", async () => {
		const dir = mkdtempSync(join(tmpdir(), "pibolt-pieces-"));
		try {
			const long = `${"x".repeat(5000)} needle in a long line ${"y".repeat(3000)}`;
			writeFileSync(join(dir, "a.txt"), `needle\nno\r\nNEEDLE two\r\n\n  \n${long}\nend needle`);
			writeFileSync(join(dir, "b.txt"), "first\nneedle\n\n");
			writeFileSync(join(dir, "c.txt"), `needle before a NUL\n${"z".repeat(100)}\0\n`);
			writeFileSync(join(dir, "d.txt"), "é needle ü\n日本 needle\n");
			const search = (pattern: string, extra: Partial<Parameters<typeof nativeGrep>[0]>) =>
				nativeGrep({ root: dir, isDirectory: true, pattern, limit: 100, keepChars: 60, ...extra });
			for (const query of [
				{ pattern: "needle", literal: true },
				{ pattern: "NEEDLE", literal: true, ignoreCase: true },
				{ pattern: "needle\\s\\w+" },
				{ pattern: "(?i)needle (two|in)" },
				{ pattern: "^\\s*$" },
				{ pattern: "^" },
				{ pattern: "e$" },
				{ pattern: "x+ needle" },
				{ pattern: "needle", limit: 3 },
			]) {
				const whole = await search(query.pattern, query);
				expect(whole.matches.length).toBeGreaterThan(0);
				for (const pieceBytes of [1, 7, 64, 4096]) {
					expect(await search(query.pattern, { ...query, wholeFileBytes: 0, pieceBytes })).toEqual(whole);
				}
			}
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	// An expression without a literal runs over 16 KB windows of whole lines: the lines that match, as line by line.
	test("an expression without a literal finds the lines that match, across windows", async () => {
		const dir = mkdtempSync(join(tmpdir(), "pibolt-windows-"));
		try {
			const lines = Array.from({ length: 6000 }, (_, i) =>
				i % 7 === 0 ? "" : i % 11 === 0 ? `  ${i}x\r` : `${"w".repeat(i % 40)} ${i} line`,
			);
			const contents = `${lines.join("\n")}\n`;
			writeFileSync(join(dir, "big.txt"), contents);
			for (const [pattern, flags] of [
				["^\\s*$", ""],
				["^", ""],
				["e$", ""],
				["[0-9]{3}x", ""],
				["W{3} [0-9]+5 LINE", "i"],
			]) {
				const regex = new RegExp(pattern, `${flags}m`);
				const expected = lines
					.map((text, i) => ({ text, lineNumber: i + 1 }))
					.filter(({ text }) => regex.test(text))
					.slice(0, 2000)
					.map(({ text, lineNumber }) => `${lineNumber}:${text.replace(/\r$/, "")}`);
				const result = await nativeGrep({
					root: join(dir, "big.txt"),
					isDirectory: false,
					pattern,
					ignoreCase: flags === "i",
					limit: 2000,
					keepChars: 100,
				});
				expect(result.matches.map((m) => `${m.lineNumber}:${m.lineText}`)).toEqual(expected);
			}
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	test("a file of its own, and a bad expression", async () => {
		const result = await nativeGrep({
			root: join(root, "src", "a.ts"),
			isDirectory: false,
			pattern: "needle",
			limit: 10,
			keepChars: 100,
		});
		expect(result.matches.map((m) => m.lineNumber)).toEqual([1, 3]);
		await expect(grep("(unclosed")).rejects.toThrow("regex parse error");
	});
});

describe("find", () => {
	test("by name, folders with a trailing /, symbolic links listed", async () => {
		expect(await nativeFind({ root, pattern: "*.ts", limit: 100 })).toEqual([
			".hidden/h.ts",
			"dir.ts/",
			"link.ts",
			"nested/build/keep.ts",
			"src/a.ts",
			"src/sub/b.ts",
			"src/sub/rooted.ts",
		]);
	});

	test("by path, and the limit", async () => {
		expect(await nativeFind({ root, pattern: "src/**/*.ts", limit: 100 })).toEqual([
			"src/a.ts",
			"src/sub/b.ts",
			"src/sub/rooted.ts",
		]);
		expect(await nativeFind({ root, pattern: "*.{md,log}", limit: 1 })).toEqual(["docs/a/b.md"]);
	});
});

const has = (command: string) => spawnSync(command, ["--version"], { stdio: "ignore" }).status === 0;

// The tools' output with ripgrep and fd and in-process, on the same files: the same lines (as sets: ripgrep and fd give
// them in no fixed order; .git, which they would search, holds nothing that matches).
describe.skipIf(!has("rg") || !has("fd"))("the same results as ripgrep and fd", () => {
	const text = (result: { content: Array<{ type: string; text?: string }> }) =>
		(result.content[0] as { text: string }).text.split("\n").sort();

	test.each([
		{ pattern: "needle" },
		{ pattern: "needle", ignoreCase: true, glob: "*.ts" },
		{ pattern: "^const \\w+ = \\d;$" },
		{ pattern: "needle", context: 1 },
		{ pattern: "(?i)NEEDLE (two|upper)" },
		{ pattern: "\\x6eeedle" },
		{ pattern: "n\\u{65}edle \\w+" },
		// earendil-works/pi#10998
		{ pattern: "needle", glob: "src/**/*.ts" },
		{ pattern: "needle", path: "src", glob: "sub/*.ts" },
	])("grep %o", async (input) => {
		const withRg = await createGrepTool(root, { native: false }).execute("t", input);
		const inProcess = await createGrepTool(root, { native: true }).execute("t", input);
		expect(text(inProcess)).toEqual(text(withRg));
	});

	test.each([{ pattern: "*.ts" }, { pattern: "src/**/*.ts" }, { pattern: "*.{md,log}" }, { pattern: "b*" }])(
		"find %o",
		async (input) => {
			const withFd = await createFindTool(root, { native: false }).execute("t", input);
			const inProcess = await createFindTool(root, { native: true }).execute("t", input);
			expect(text(inProcess)).toEqual(text(withFd));
		},
	);
});

const git = (cwd: string, ...args: string[]) =>
	spawnSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", "-c", "init.defaultBranch=main", ...args], {
		cwd,
		stdio: "ignore",
	}).status === 0;

// A linked worktree's .git is a file naming its folder in the main repository, whose info/exclude it shares.
describe.skipIf(!has("git"))("a linked worktree", () => {
	test("its info/exclude is the repository's, as git rev-parse --git-path info/exclude finds it", async () => {
		const top = mkdtempSync(join(tmpdir(), "pibolt-worktree-"));
		try {
			const main = join(top, "main");
			mkdirSync(main);
			expect(git(main, "init", "-q") && git(main, "commit", "-q", "--allow-empty", "-m", "x")).toBe(true);
			expect(git(main, "worktree", "add", "-q", join(top, "linked"))).toBe(true);
			writeFileSync(join(main, ".git", "info", "exclude"), "secret.txt\n");
			writeFileSync(join(top, "linked", "secret.txt"), "needle\n");
			writeFileSync(join(top, "linked", "plain.txt"), "needle\n");
			const rels = (await walk({ root: join(top, "linked"), requireGit: true })).map((entry) => entry.rel);
			expect(rels).toContain("plain.txt");
			expect(rels).not.toContain("secret.txt");
		} finally {
			rmSync(top, { recursive: true, force: true });
		}
	});
});

describe("limits", () => {
	test("braces expand to at most 1024 patterns", async () => {
		expect(expandBraces("{a,b}".repeat(10))).toHaveLength(1024);
		expect(() => expandBraces("{a,b}".repeat(11))).toThrow("too many patterns");
		// (Twenty groups would be a million patterns, built before the first match.)
		await expect(nativeFind({ root, pattern: "{a,b}".repeat(20), limit: 10 })).rejects.toThrow("too many patterns");
	});

	test.skipIf(process.platform === "win32")(
		"an ignore file that is a device or a pipe is passed over, not read",
		async () => {
			const dir = mkdtempSync(join(tmpdir(), "pibolt-special-"));
			try {
				writeFileSync(join(dir, "a.ts"), "needle\n");
				symlinkSync("/dev/zero", join(dir, ".gitignore"));
				expect(spawnSync("mkfifo", [join(dir, ".ignore")]).status).toBe(0);
				// (The link is listed, as fd lists links; the pipe is neither a file nor a folder.)
				expect((await walk({ root: dir, requireGit: false })).map((entry) => entry.rel).sort()).toEqual([
					".gitignore",
					"a.ts",
				]);
				// A search of the pipe itself: nothing to read.
				const fifo = await nativeGrep({
					root: join(dir, ".ignore"),
					isDirectory: false,
					pattern: "x",
					limit: 10,
					keepChars: 100,
				});
				expect(fifo.matches).toEqual([]);
			} finally {
				rmSync(dir, { recursive: true, force: true });
			}
		},
		10_000,
	);

	// An expression that backtracks a long time on each line (about 40 ms here): the search lets the event loop run between
	// lines, so the abort comes through at once instead of after every line was tried.
	test("a slow expression can be aborted", async () => {
		const dir = mkdtempSync(join(tmpdir(), "pibolt-slow-"));
		try {
			writeFileSync(join(dir, "slow.txt"), `${"xyz".padEnd(25, "a")}\n`.repeat(60));
			const controller = new AbortController();
			setTimeout(() => controller.abort(), 100);
			const start = performance.now();
			await expect(
				nativeGrep({
					root: dir,
					isDirectory: true,
					pattern: "xyz(a+)+c",
					limit: 10,
					keepChars: 100,
					signal: controller.signal,
				}),
			).rejects.toThrow("Operation aborted");
			expect(performance.now() - start).toBeLessThan(1000);
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});
});
