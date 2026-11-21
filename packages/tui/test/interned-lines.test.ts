import assert from "node:assert/strict";
import { it } from "node:test";
import { internLine } from "../src/utils.ts";

it("keeps each cached row's exact UTF-16 after later insertions and eviction", () => {
	const inputs = [
		"",
		"\u0000",
		"\uFEFFhead",
		"\ud800",
		"tail\udfff",
		"漢字🦎",
		...[255, 256, 257].map((n) => `${"x".repeat(n - 1)}\ud800`),
	];
	const cached = inputs.map(internLine);
	for (let i = 0; i < 1100; i++) internLine(`row ${i}`);
	assert.deepEqual(cached, inputs);
	assert.deepEqual(inputs.map(internLine), inputs);
});
