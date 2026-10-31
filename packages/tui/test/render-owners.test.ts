import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { Box } from "../src/components/box.ts";
import { Text } from "../src/components/text.ts";
import { Container } from "../src/tui.ts";

describe("render owner subscriptions", () => {
	it("a shared child updates every live parent and its ancestors", () => {
		const child = new Text("before", 0, 0);
		const roots: Container[] = [];
		for (let i = 0; i < 2; i++) {
			const box = new Box(0, 0);
			box.addChild(child);
			const root = new Container();
			root.addChild(box);
			root.render(20);
			root.render(21);
			roots.push(root);
		}
		child.setText("after");
		for (const root of roots) assert.deepEqual(root.render(21), ["after".padEnd(21)]);
	});

	it("a shared child does not retain discarded parents", () => {
		const fixture = fileURLToPath(new URL("./fixtures/render-owners-gc.ts", import.meta.url));
		const result = spawnSync(process.execPath, ["--expose-gc", fixture], {
			encoding: "utf8",
			timeout: 30_000,
		});
		assert.ifError(result.error);
		assert.equal(result.status, 0, result.stdout + result.stderr);
	});
});
