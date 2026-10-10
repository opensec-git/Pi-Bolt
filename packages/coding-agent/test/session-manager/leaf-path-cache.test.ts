import { describe, expect, it } from "vitest";
import {
	buildContextEntries,
	buildSessionProjection,
	type SessionEntry,
	SessionManager,
} from "../../src/core/session-manager.ts";
import { assistantMsg, userMsg } from "../utilities.ts";

// The session manager keeps the path to its leaf between calls and extends it on append. Whatever happens to the tree,
// what it answers must be what walking the tree from the leaf gives.
function walk(session: SessionManager): SessionEntry[] {
	const path: SessionEntry[] = [];
	for (let id = session.getLeafId(); id; ) {
		const entry = session.getEntry(id);
		if (!entry) break;
		path.push(entry);
		id = entry.parentId;
	}
	return path.reverse();
}

describe("SessionManager leaf path cache", () => {
	it("answers as a walk of the tree does through appends, compactions, branches and resets", () => {
		let seed = 12345;
		const random = () => {
			seed = (seed * 1103515245 + 12345) & 0x7fffffff;
			return seed / 0x7fffffff;
		};
		const session = SessionManager.inMemory();
		for (let step = 0; step < 400; step++) {
			const roll = random();
			const path = walk(session);
			if (roll < 0.55 || path.length === 0) {
				session.appendMessage(step % 2 ? assistantMsg(`a${step}`) : userMsg(`u${step}`));
			} else if (roll < 0.65) {
				const kept = path[Math.floor(random() * path.length)];
				session.appendCompaction(`summary ${step}`, kept.id, 1000);
			} else if (roll < 0.85) {
				const entries = session.getEntries();
				session.branch(entries[Math.floor(random() * entries.length)].id);
			} else if (roll < 0.9) {
				session.resetLeaf();
			} else {
				session.appendThinkingLevelChange(step % 3 ? "high" : "low");
			}

			const expected = walk(session);
			expect(session.getBranch()).toEqual(expected);
			const entries = session.getEntries();
			const leafId = session.getLeafId();
			expect(session.buildContextEntries()).toEqual(buildContextEntries(entries, leafId));
			expect(session.buildSessionProjection()).toEqual(buildSessionProjection(entries, leafId));
		}
	});

	it("hands out copies that do not change the cached path", () => {
		const session = SessionManager.inMemory();
		session.appendMessage(userMsg("one"));
		session.appendMessage(assistantMsg("two"));
		const branch = session.getBranch();
		branch.pop();
		session.buildContextEntries().pop();
		session.appendMessage(userMsg("three"));
		expect(session.getBranch().map((entry) => entry.type === "message" && entry.message.role)).toEqual([
			"user",
			"assistant",
			"user",
		]);
	});
});
