import { describe, expect, it, vi } from "vitest";
import { SessionManager } from "../../src/core/session-manager.ts";
import { assistantMsg, userMsg } from "../utilities.ts";

describe("file-order session statistics and cursors", () => {
	it("includes both branches, returns independent snapshots, and clears counts on replacement", () => {
		const session = SessionManager.inMemory();
		const first = session.appendMessage(userMsg("one"));
		session.appendMessage(assistantMsg("answer"));
		const snapshot = session.getEntryStats();
		expect(snapshot.totalMessages).toBe(2);
		snapshot.totalMessages = 100;
		session.branch(first);
		session.appendMessage(userMsg("two"));
		expect(session.getEntryStats()).toMatchObject({ totalMessages: 3, assistantMessages: 1, userMessages: 2 });
		expect(session.getEntriesAfter(first)).toEqual(session.getEntries().slice(1));
		expect(session.getEntriesAfter("missing")).toBeUndefined();
		session.newSession();
		expect(session.getEntryStats().totalMessages).toBe(0);
		expect(session.getEntriesAfter(first)).toBeUndefined();
	});

	it("drops a failed append even when statistics were read during persistence", () => {
		const session = SessionManager.inMemory();
		const base = session.appendMessage(userMsg("base"));
		expect(session.getEntryStats().totalMessages).toBe(1);
		const fail = vi.spyOn(session, "_persist").mockImplementationOnce(() => {
			expect(session.getEntryStats().totalMessages).toBe(2);
			throw new Error("disk full");
		});
		try {
			expect(() => session.appendMessage(userMsg("failed"))).toThrow("disk full");
		} finally {
			fail.mockRestore();
		}
		expect(session.getEntryStats().totalMessages).toBe(1);
		const saved = session.appendMessage(userMsg("retry"));
		expect(session.getEntriesAfter(base)?.map((entry) => entry.id)).toEqual([saved]);
		expect(session.getEntryStats().totalMessages).toBe(2);
	});

	it("resumes after the first match in an imported file with duplicate IDs", () => {
		const source = SessionManager.inMemory();
		const a = source.appendMessage(userMsg("first"));
		source.appendMessage(userMsg("middle"));
		source.appendMessage(userMsg("last"));
		const rows = source.getEntries();
		const imported = SessionManager.inMemory(process.cwd(), undefined, [
			source.getHeader()!,
			rows[0],
			rows[1],
			{ ...rows[2], id: a },
		]);
		expect(imported.getEntriesAfter(a)).toEqual(imported.getEntries().slice(1));
		expect(imported.getEntryStats().totalMessages).toBe(3);
	});
});
