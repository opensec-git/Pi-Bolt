import { mkdtempSync, rmSync, truncateSync, writeFileSync } from "node:fs";
import type * as FsPromises from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, expect, test, vi } from "vitest";
import { nativeGrep } from "../src/core/tools/native-search.ts";

// The size of every file the search read whole.
const readWhole = vi.hoisted(() => [] as number[]);
vi.mock("node:fs/promises", async (importOriginal) => {
	const actual = await importOriginal<typeof FsPromises>();
	const readFile = async (...args: Parameters<typeof actual.readFile>) => {
		const contents = await actual.readFile(...args);
		readWhole.push(contents.length);
		return contents;
	};
	return { ...actual, readFile, default: { ...actual, readFile } };
});

let dir: string;

beforeAll(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-large-"));
	writeFileSync(join(dir, "logreg.py"), "class LogisticRegression:\n    pass\n");
	// Sparse: no disk space, and all NUL bytes, as the start of a virtual disk or a model's weights is.
	writeFileSync(join(dir, "model.gguf"), "GGUF\0");
	truncateSync(join(dir, "model.gguf"), 512 * 1024 * 1024);
	// Text, with a match on its last line.
	writeFileSync(
		join(dir, "train.log"),
		`${"epoch=1 loss=0.693\n".repeat(400_000)}class LogisticRegression in a log\n`,
	);
});

afterAll(() => {
	rmSync(dir, { recursive: true, force: true });
});

// #163: grep read every file whole, all the files it reads ahead at once: a search through a folder with large files (a
// virtual disk, a model's weights) held gigabytes and read them at the disk's speed. A large file is read a piece at a time.
test("large files are not read whole, and are searched all the same", async () => {
	readWhole.length = 0;
	const result = await nativeGrep({
		root: dir,
		isDirectory: true,
		pattern: "class LogisticRegression",
		limit: 100,
		keepChars: 500,
	});
	expect(result.matches.map((m) => `${m.filePath.slice(dir.length + 1)}:${m.lineNumber}:${m.lineText}`)).toEqual([
		"logreg.py:1:class LogisticRegression:",
		"train.log:400001:class LogisticRegression in a log",
	]);
	expect(readWhole).toEqual([35]);
});
