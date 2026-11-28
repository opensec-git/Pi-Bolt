// What edit-diff.ts remembers of an edit is let go once it is no longer needed (w132 claim L1, hunt-tools-11): the fuzzy
// normalization (the file's content and its normalized copy) and the last line diff (of window slices that keep the old
// and new contents alive) stayed in memory after the edit of a 20 MB file until the next edit: 58 MiB here on Node.
import { setFlagsFromString } from "node:v8";
import { runInNewContext } from "node:vm";
import { afterEach, expect, test, vi } from "vitest";
import { createEditTool } from "../src/core/tools/edit.ts";

setFlagsFromString("--expose-gc");
const gc = runInNewContext("gc") as () => void;

const MIB = 1024 * 1024;

/** Heap and external memory after full collections. */
function retained(): number {
	gc();
	gc();
	const usage = process.memoryUsage();
	return usage.heapUsed + usage.external;
}

/** A 20 MB file with trailing spaces: its normalized copy is a second string of about its size. */
const fileBytes = () => Buffer.from(`${"line of ascii text  \n".repeat(1_000_000)}MARKER_LINE\n`);

function editTool() {
	return createEditTool(process.cwd(), {
		operations: { access: async () => {}, readFile: async () => fileBytes(), writeFile: async () => {} },
	});
}

afterEach(() => {
	vi.useRealTimers();
});

test("a written edit leaves no copy of the file in memory", async () => {
	const tool = editTool();
	// A small edit first, so the module and the tool's own memory are in the baseline.
	await createEditTool(process.cwd(), {
		operations: { access: async () => {}, readFile: async () => Buffer.from("a\n"), writeFile: async () => {} },
	}).execute("warm", { path: "small", edits: [{ oldText: "a", newText: "b" }] });
	const before = retained();
	await tool.execute("t", { path: "big", edits: [{ oldText: "MARKER_LINE", newText: "CHANGED_LINE" }] });
	expect((retained() - before) / MIB).toBeLessThan(8);
});

test("after an edit that failed, the copy is kept for a retry, then let go", async () => {
	vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
	const tool = editTool();
	const before = retained();
	await expect(tool.execute("t", { path: "big", edits: [{ oldText: "NOT_THERE", newText: "x" }] })).rejects.toThrow(
		"Could not find",
	);
	expect((retained() - before) / MIB).toBeGreaterThan(15);
	vi.advanceTimersByTime(30_000);
	expect((retained() - before) / MIB).toBeLessThan(8);
});
