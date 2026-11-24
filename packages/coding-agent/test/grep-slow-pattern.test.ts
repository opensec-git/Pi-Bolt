import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, expect, test, vi } from "vitest";

// What ensureTool("rg") gives the grep tool: ripgrep's path, nothing (not installed, no download), or a download that never
// ends.
const mocks = vi.hoisted(() => ({ ensureTool: vi.fn<(tool: string) => Promise<string | undefined>>() }));

vi.mock("../src/utils/tools-manager.ts", () => ({ ensureTool: mocks.ensureTool }));

import { createGrepTool } from "../src/core/tools/grep.ts";

const rgPath = spawnSync("rg", ["--version"], { stdio: "ignore" }).status === 0 ? "rg" : undefined;

const issuePattern = "Retrieve.*key.*operation|Get.*Key.*Operation|only key operation";
let dir: string;

beforeAll(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-grep-slow-"));
	// #342: the issue's synthetic file, one 327 KB line.
	writeFileSync(join(dir, "test.map"), `get ${"x".repeat(200)} key ${"y".repeat(200)}`.repeat(800));
	writeFileSync(join(dir, "notes.txt"), "how to get the key\nonly key operation here\n");
});

afterAll(() => rmSync(dir, { recursive: true, force: true }));

beforeEach(() => {
	mocks.ensureTool.mockReset();
});

const text = (result: { content: Array<{ type: string; text?: string }> }) =>
	(result.content[0] as { text: string }).text;

describe("grep with a pattern that could backtrack too long in-process", () => {
	// #342
	test.skipIf(!rgPath)("is handed to ripgrep, with ripgrep's results", async () => {
		mocks.ensureTool.mockResolvedValue(rgPath);
		const start = performance.now();
		const inProcess = await createGrepTool(dir, { native: true }).execute("t", {
			pattern: issuePattern,
			ignoreCase: true,
		});
		expect(performance.now() - start).toBeLessThan(5000);
		expect(mocks.ensureTool).toHaveBeenCalledWith("rg");
		const withRg = await createGrepTool(dir, { native: false }).execute("t", {
			pattern: issuePattern,
			ignoreCase: true,
		});
		expect(text(inProcess)).toBe(text(withRg));
		expect(text(inProcess)).toBe("notes.txt:2: only key operation here");
	});

	// #342
	test("without ripgrep, searches the other lines and names the one it passed over", async () => {
		mocks.ensureTool.mockResolvedValue(undefined);
		const start = performance.now();
		const result = await createGrepTool(dir, { native: true }).execute("t", {
			pattern: issuePattern,
			ignoreCase: true,
		});
		expect(performance.now() - start).toBeLessThan(5000);
		expect(text(result)).toBe(
			"notes.txt:2: only key operation here\n\n[1 line was not searched, as this pattern could take too long on it " +
				"without ripgrep (rg), which is not available: test.map:1. To search it, use a pattern with fewer .* or " +
				"nested repeats, or literal=true, or install ripgrep]",
		);
		const none = await createGrepTool(dir, { native: true }).execute("t", {
			pattern: "Get.*Key.*Nothing|xyz",
			ignoreCase: true,
		});
		expect(text(none)).toMatch(/^No matches found\n\n\[1 line was not searched, .* test\.map:1\./);
	});

	// #342
	test("can be aborted while ripgrep is looked for or downloaded", async () => {
		mocks.ensureTool.mockReturnValue(new Promise(() => {}));
		const controller = new AbortController();
		setTimeout(() => controller.abort(), 50);
		const start = performance.now();
		await expect(
			createGrepTool(dir, { native: true }).execute(
				"t",
				{ pattern: issuePattern, ignoreCase: true },
				controller.signal,
			),
		).rejects.toThrow("Operation aborted");
		expect(performance.now() - start).toBeLessThan(1000);
	});

	// #342
	test("a pattern that fits stays in-process, without looking for ripgrep", async () => {
		const result = await createGrepTool(dir, { native: true }).execute("t", { pattern: "get the \\w+|only" });
		expect(text(result)).toBe("notes.txt:1: how to get the key\nnotes.txt:2: only key operation here");
		expect(mocks.ensureTool).not.toHaveBeenCalled();
	});
});
