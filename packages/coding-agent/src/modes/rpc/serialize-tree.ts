import type { SessionTreeNode } from "../../core/session-manager.ts";
import { serializeJsonLine } from "./jsonl.ts";
import type { RpcResponse } from "./rpc-types.ts";

type TreeResponse = Extract<RpcResponse, { command: "get_tree"; success: true }>;

// The packaged runtimes encode several thousand levels efficiently. Beyond that,
// native JSON serialization can spend almost a second exhausting the call stack.
const NATIVE_DEPTH_LIMIT = 8192;

function treeDepth(tree: SessionTreeNode[]): number {
	let maximum = 0;
	const pending = [{ nodes: tree, depth: 1 }];
	while (pending.length) {
		const { nodes, depth } = pending.pop()!;
		for (let node of nodes) {
			let level = depth;
			while (node.children.length) {
				// Bound the preliminary walk for exceptionally deep trees and imported cycles.
				if (level >= 4 * NATIVE_DEPTH_LIMIT) return Infinity;
				level++;
				if (node.children.length > 1) {
					pending.push({ nodes: node.children, depth: level });
					break;
				}
				node = node.children[0];
			}
			maximum = Math.max(maximum, level);
		}
	}
	return maximum;
}

function isStackOverflow(error: unknown): boolean {
	return error instanceof RangeError && error.message.includes("call stack");
}

export function serializeTreeResponse(response: TreeResponse): string {
	const { tree, leafId } = response.data;
	// Below these outer levels every subtree fits in the native serializer.
	let outerLevels = treeDepth(tree) - NATIVE_DEPTH_LIMIT;
	if (outerLevels <= 0) {
		try {
			return serializeJsonLine(response);
		} catch (error) {
			if (!isStackOverflow(error)) throw error;
			outerLevels = Infinity;
		}
	}

	const { id, type, command, success } = response;
	const parts = [JSON.stringify({ id, type, command, success }).slice(0, -1), ',"data":{"tree":['];
	const active = new Set<SessionTreeNode>();
	const stack: { nodes: SessionTreeNode[]; next: number; parent?: SessionTreeNode }[] = [{ nodes: tree, next: 0 }];
	while (stack.length) {
		const frame = stack[stack.length - 1];
		if (frame.next === frame.nodes.length) {
			stack.pop();
			parts.push("]");
			const parent = frame.parent;
			if (parent) {
				if (parent.label !== undefined) parts.push(',"label":', JSON.stringify(parent.label));
				if (parent.labelTimestamp !== undefined)
					parts.push(',"labelTimestamp":', JSON.stringify(parent.labelTimestamp));
				parts.push("}");
				active.delete(parent);
			}
			continue;
		}

		if (frame.next) parts.push(",");
		const node = frame.nodes[frame.next++];
		if (stack.length > outerLevels) {
			try {
				parts.push(JSON.stringify(node));
				continue;
			} catch (error) {
				if (!isStackOverflow(error)) throw error;
				// The embedding uses a smaller stack. Finish the record iteratively.
				outerLevels = Infinity;
			}
		}
		if (!node.children.length) {
			parts.push(JSON.stringify(node));
			continue;
		}
		if (active.has(node)) throw new TypeError("Circular session tree");
		active.add(node);
		parts.push(JSON.stringify({ entry: node.entry }).slice(0, -1), ',"children":[');
		stack.push({ nodes: node.children, next: 0, parent: node });
	}
	parts.push(',"leafId":', JSON.stringify(leafId), "}}\n");
	return parts.join("");
}
