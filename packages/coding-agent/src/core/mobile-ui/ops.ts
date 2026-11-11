/**
 * `updateView` ops (docs/mobile-ui.md#updates) applied to a copy of a view. The caller validates the result: an op
 * that leaves an invalid view fails the whole update.
 */

import type { MobileView, MobileViewNode, MobileViewOp } from "./types.ts";
import { forEachNode } from "./validate.ts";

type Located = { node: MobileViewNode; siblings: MobileViewNode[]; index: number };

function locate(view: MobileView, id: string): Located | undefined {
	let found: Located | undefined;
	forEachNode(view, (node, siblings, index) => {
		if (node.id === id) found = { node, siblings, index };
		return found !== undefined;
	});
	return found;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `ops` applied to a copy of `view`. Throws an Error naming the op that cannot apply. */
export function applyViewOps(view: MobileView, ops: readonly MobileViewOp[]): MobileView {
	if (!Array.isArray(ops)) throw new Error("ops: expected a list");
	const next = structuredClone(view);
	for (const [index, op] of ops.entries()) {
		const at = `ops[${index}]`;
		if (!isRecord(op)) throw new Error(`${at}: expected an op object`);
		switch (op.op) {
			case "set": {
				const target = typeof op.id === "string" ? locate(next, op.id) : undefined;
				if (!target) throw new Error(`${at}: no node with id ${JSON.stringify(op.id)}`);
				if (!isRecord(op.props)) throw new Error(`${at}: props: expected an object`);
				const node = target.node as unknown as Record<string, unknown>;
				for (const [name, value] of Object.entries(op.props)) {
					if ((name === "type" || name === "id") && value !== node[name]) {
						throw new Error(`${at}: props: ${name} cannot change`);
					}
					if (value === null) delete node[name];
					else node[name] = structuredClone(value);
				}
				break;
			}
			case "replace": {
				const target = typeof op.id === "string" ? locate(next, op.id) : undefined;
				if (!target) throw new Error(`${at}: no node with id ${JSON.stringify(op.id)}`);
				if (!isRecord(op.node)) throw new Error(`${at}: node: expected a node object`);
				target.siblings[target.index] = structuredClone(op.node) as unknown as MobileViewNode;
				break;
			}
			case "remove": {
				const target = typeof op.id === "string" ? locate(next, op.id) : undefined;
				if (!target) throw new Error(`${at}: no node with id ${JSON.stringify(op.id)}`);
				target.siblings.splice(target.index, 1);
				break;
			}
			case "insert": {
				if (!isRecord(op.node)) throw new Error(`${at}: node: expected a node object`);
				let children: MobileViewNode[];
				if (op.parent === undefined) {
					children = next.body;
				} else {
					const parent = typeof op.parent === "string" ? locate(next, op.parent) : undefined;
					const list = parent ? (parent.node as unknown as { children?: unknown }).children : undefined;
					if (!parent) throw new Error(`${at}: no node with id ${JSON.stringify(op.parent)}`);
					if (!Array.isArray(list)) throw new Error(`${at}: ${JSON.stringify(op.parent)} has no children`);
					children = list as MobileViewNode[];
				}
				const position: unknown = op.index === undefined ? children.length : op.index;
				if (
					typeof position !== "number" ||
					!Number.isInteger(position) ||
					position < 0 ||
					position > children.length
				) {
					throw new Error(`${at}: index: expected 0 to ${children.length}`);
				}
				children.splice(position, 0, structuredClone(op.node) as unknown as MobileViewNode);
				break;
			}
			default:
				throw new Error(`${at}: unknown op ${JSON.stringify((op as { op?: unknown }).op)}`);
		}
	}
	return next;
}
