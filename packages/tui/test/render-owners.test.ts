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

	for (const length of [5, 2048]) {
		it(`updates every occurrence of a shared child as a ${length}-row layout changes`, () => {
			const row = new Text("first", 0, 0);
			const filler = new Text("-", 0, 0);
			const root = new Container();
			const rows = Array.from({ length }, (_, i) => (i === 1 || i === length - 1 ? row : filler));
			root.children = rows;
			const check = (value: string) => {
				const expected = rows.map((child) => (child === row ? value : "-").padEnd(10));
				assert.deepEqual(root.render(10), expected);
			};
			check("first");
			row.setText("second");
			check("second");
			root.addChild(row);
			row.setText("third");
			check("third");
			root.removeChild(row);
			row.setText("fourth");
			check("fourth");
			root.children.unshift(row);
			row.setText("fifth");
			check("fifth");
		});
	}

	for (const ComponentType of [Container, Box]) {
		for (const source of ["getter", "setter"]) {
			it(`notices later edits through the ${ComponentType.name} child list from its ${source}`, () => {
				const group = new ComponentType(0, 0);
				const parent = new Container();
				const root = new Container();
				parent.addChild(group);
				root.addChild(parent);
				const list = source === "getter" ? group.children : [];
				if (source === "setter") group.children = list;
				const check = (...expected: string[]) => {
					assert.deepEqual(
						root.render(12).map((line) => line.trimEnd()),
						expected,
					);
				};
				check();
				list.push(new Text("one", 0, 0), new Text("two", 0, 0));
				check("one", "two");
				list.reverse();
				check("two", "one");
				list[0] = new Text("new", 0, 0);
				check("new", "one");
				list.pop();
				check("new");
				group.clear();
				list.push(new Text("detached", 0, 0));
				check();
			});
		}
	}

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
