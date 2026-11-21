/**
 * From the repository root after building the workspace packages:
 * node --expose-gc packages/coding-agent/test/tool-row-memory-bench.ts
 * Each run reports retained JS heap above a warmed baseline for 20,000 compacted tool rows.
 */
import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";
import { Text, type TUI } from "@earendil-works/pi-tui";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

const count = 20_000;
const width = 100;
const cwd = process.cwd();
// The row only uses requestRender().
const ui = { requestRender() {} } as TUI;
const renderer = { renderCall: () => new Text("read /source/file.md", 0, 0) };
const result = { content: [{ type: "text", text: "one\ntwo\nthree" }], isError: false };

function createCompactedRow(): ToolExecutionComponent {
	const row = new ToolExecutionComponent("read", "tool-id", {}, { compactable: true }, renderer, ui, cwd);
	row.updateResult(result);
	row.renderShared(width);
	assert(row.compact());
	return row.compactedCopy();
}

function heapUsed(): number {
	const collect = globalThis.gc;
	assert(collect, "Run with --expose-gc");
	for (let i = 0; i < 5; i++) collect();
	return process.memoryUsage().heapUsed;
}

initTheme("dark");
for (let i = 0; i < 2_000; i++) createCompactedRow();
const before = heapUsed();
const start = performance.now();
const rows = Array.from({ length: count }, createCompactedRow);
const elapsedMs = performance.now() - start;
const retainedBytes = heapUsed() - before;
console.log(
	JSON.stringify({ rows: rows.length, retainedBytes, elapsedMs, linesPerRow: rows[0]!.renderShared(width).length }),
);
