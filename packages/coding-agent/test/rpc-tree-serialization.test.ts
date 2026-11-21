import { describe, expect, it } from "vitest";
import type { SessionTreeNode } from "../src/core/session-manager.ts";
import type { RpcResponse } from "../src/modes/rpc/rpc-types.ts";
import { serializeTreeResponse } from "../src/modes/rpc/serialize-tree.ts";

function node(i: number): SessionTreeNode {
	return {
		entry: {
			type: "custom",
			id: String(i),
			parentId: i ? String(i - 1) : null,
			timestamp: "2026-11-21T00:00:00Z",
			customType: "example",
			data: { text: '\n"🌿\u2028\u2029' },
		},
		children: [],
		label: i % 5 === 0 ? '界"\n' : undefined,
		labelTimestamp: i % 3 === 0 ? "2026-11-21" : undefined,
	};
}

function response(tree: SessionTreeNode[], id?: string): Extract<RpcResponse, { command: "get_tree"; success: true }> {
	return {
		id,
		type: "response",
		command: "get_tree",
		success: true,
		data: { tree, leafId: tree[0]?.entry.id ?? null },
	};
}

describe("RPC tree serialization", () => {
	it("matches native JSONL for ordinary trees, including shared nodes and optional fields", () => {
		for (const depth of [0, 1, 128, 129, 512]) {
			const roots = Array.from({ length: depth }, (_, i) => node(i));
			for (let i = 1; i < roots.length; i++) roots[i - 1].children.push(roots[i]);
			const tree = depth ? [roots[0], node(200), roots[depth - 1]] : [];
			for (const id of [undefined, '測"\n']) {
				const record = response(tree, id);
				expect(serializeTreeResponse(record)).toBe(`${JSON.stringify(record)}\n`);
			}
		}
	});

	it("delivers a 20,000-entry branch in one record", () => {
		const root = node(0);
		let last = root;
		for (let i = 1; i < 20000; i++) {
			const child = node(i);
			last.children.push(child);
			last = child;
		}
		const raw = serializeTreeResponse(response([root, last], '深"\n'));
		expect(raw.split("\n")).toHaveLength(2);
		const parsed = JSON.parse(raw) as Extract<RpcResponse, { command: "get_tree"; success: true }>;
		expect(parsed.id).toBe('深"\n');
		expect(parsed.data.tree).toHaveLength(2);
		expect(parsed.data.tree[1]).toEqual(last);
		let current = parsed.data.tree[0];
		let original = root;
		for (let i = 1; i < 20000; i++) {
			if (
				current.entry.id !== original.entry.id ||
				current.label !== original.label ||
				current.labelTimestamp !== original.labelTimestamp ||
				current.children.length !== 1
			) {
				throw new Error(`Unexpected tree node at position ${i - 1}`);
			}
			current = current.children[0];
			original = original.children[0];
		}
		expect(current).toEqual(last);
	});

	it("propagates an entry serialization error without evaluating the entry twice", () => {
		const root = node(0);
		let calls = 0;
		Object.assign(root.entry, {
			toJSON() {
				calls++;
				throw new RangeError("entry value out of range");
			},
		});
		expect(() => serializeTreeResponse(response([root]))).toThrow("entry value out of range");
		expect(calls).toBe(1);
	});

	it("rejects cycles while allowing the same node on different branches", () => {
		const root = node(0);
		root.children.push(root);
		expect(() => serializeTreeResponse(response([root]))).toThrow(/circular/i);
	});
});
