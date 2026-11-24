import * as fs from "fs";
import { mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionManager } from "../../src/core/session-manager.ts";
import { assistantMsg, userMsg } from "../utilities.ts";

vi.mock("fs", async (importOriginal) => {
	const actual = await importOriginal<typeof fs>();
	return { ...actual, writeSync: vi.fn(actual.writeSync) };
});

const lines = (file: string) => readFileSync(file, "utf8").trimEnd().split("\n");

describe("SessionManager with damaged lines", () => {
	let dir: string;

	beforeEach(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-session-damaged-"));
	});

	afterEach(() => {
		vi.restoreAllMocks();
		rmSync(dir, { recursive: true, force: true });
	});

	/** A session of alternating messages: the file has the header, then one line per entry id. */
	function writeSession(): { file: string; ids: string[] } {
		const session = SessionManager.create(dir, dir);
		const ids = [session.appendMessage(userMsg("original prompt"))];
		for (let i = 0; i < 4; i++) {
			ids.push(session.appendMessage(assistantMsg(`answer ${i}`)));
			ids.push(session.appendMessage(userMsg(`question ${i}`)));
		}
		return { file: session.getSessionFile()!, ids };
	}

	function rewrite(file: string, edit: (lines: string[]) => void): string {
		const content = lines(file);
		edit(content);
		const text = `${content.join("\n")}\n`;
		writeFileSync(file, text);
		return text;
	}

	it("keeps the whole branch across a line cut short by a partial write", () => {
		const { file, ids } = writeSession();
		// Line 4 holds ids[3].
		const damaged = rewrite(file, (content) => {
			content[4] = content[4].slice(0, 30);
		});
		const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

		const session = SessionManager.open(file, dir);

		const kept = ids.filter((id) => id !== ids[3]);
		expect(session.getBranch().map((entry) => entry.id)).toEqual(kept);
		expect(session.getEntry(ids[4])?.parentId).toBe(ids[2]);
		expect(session.buildSessionContext().messages[0]).toMatchObject({ role: "user", content: "original prompt" });
		expect(warn).toHaveBeenCalledTimes(1);
		expect(warn.mock.calls[0][0]).toContain("relinked 1 entry");
		// The repair is in memory only.
		expect(readFileSync(file, "utf8")).toBe(damaged);
	});

	it("keeps the whole branch across a partial line fused with the next entry", () => {
		const { file, ids } = writeSession();
		// A partial ids[3] with ids[4] written straight after it: both are lost to JSON.parse.
		rewrite(file, (content) => {
			content.splice(4, 2, content[4].slice(0, 30) + content[5]);
		});
		vi.spyOn(console, "warn").mockImplementation(() => {});

		const session = SessionManager.open(file, dir);

		const kept = ids.filter((id) => id !== ids[3] && id !== ids[4]);
		expect(session.getBranch().map((entry) => entry.id)).toEqual(kept);
		expect(session.getEntry(ids[5])?.parentId).toBe(ids[2]);
		expect(session.buildSessionContext().messages[0]).toMatchObject({ role: "user", content: "original prompt" });
		// New entries continue the repaired branch.
		const next = session.appendMessage(assistantMsg("after reload"));
		expect(SessionManager.open(file, dir).getBranch()[0].id).toBe(ids[0]);
		expect(SessionManager.open(file, dir).getBranch().at(-1)?.id).toBe(next);
	});

	it("leaves a clean file as it is", () => {
		const { file, ids } = writeSession();
		const written = lines(file).map((line) => JSON.parse(line));
		const warn = vi.spyOn(console, "warn");

		const session = SessionManager.open(file, dir);

		expect(session.getEntries()).toEqual(written.slice(1));
		expect(session.getBranch().map((entry) => entry.id)).toEqual(ids);
		expect(session.getEntry(ids[0])?.parentId).toBeNull();
		expect(warn).not.toHaveBeenCalled();
	});

	it("keeps forks on their own parents", () => {
		const session = SessionManager.create(dir, dir);
		const root = session.appendMessage(userMsg("root"));
		const answer = session.appendMessage(assistantMsg("answer"));
		const left = session.appendMessage(userMsg("left"));
		session.appendMessage(assistantMsg("left answer"));
		session.branch(answer);
		const right = session.appendMessage(userMsg("right"));
		const file = session.getSessionFile()!;
		const warn = vi.spyOn(console, "warn");

		const reopened = SessionManager.open(file, dir);
		expect(reopened.getEntry(right)?.parentId).toBe(answer);
		expect(reopened.getBranch().map((entry) => entry.id)).toEqual([root, answer, right]);
		expect(reopened.getChildren(answer).map((entry) => entry.id)).toEqual([left, right]);
		expect(warn).not.toHaveBeenCalled();
	});

	it("leaves a file with a parent after its child as stock Pi reads it", () => {
		// An imported file: C names B, which comes after it, and E names an entry that is not in the file.
		const message = (id: string, parentId: string | null) => ({
			type: "message",
			id,
			parentId,
			timestamp: "2026-01-01T00:00:00.000Z",
			message: userMsg(id),
		});
		const file = join(dir, "imported.jsonl");
		const header = { type: "session", version: 3, id: "imported", timestamp: "2026-01-01T00:00:00.000Z", cwd: dir };
		const entries = [
			message("A", null),
			message("C", "B"),
			message("B", "A"),
			message("D", "C"),
			message("E", "lost"),
		];
		writeFileSync(file, `${[header, ...entries].map((entry) => JSON.stringify(entry)).join("\n")}\n`);
		const warn = vi.spyOn(console, "warn");

		const session = SessionManager.open(file, dir);

		expect(session.getEntries()).toEqual(entries);
		expect(session.getBranch("D").map((entry) => entry.id)).toEqual(["A", "B", "C", "D"]);
		expect(session.getBranch().map((entry) => entry.id)).toEqual(["E"]);
		expect(session.getTree().map((node) => node.entry.id)).toEqual(["A", "E"]);
		expect(warn).not.toHaveBeenCalled();
	});

	it.skipIf(process.platform === "win32")("ends a partial line left by a failed write before the next append", () => {
		const session = SessionManager.create(dir, dir);
		const first = session.appendMessage(userMsg("first"));
		const file = session.getSessionFile()!;
		const actual = vi.mocked(fs.writeSync).getMockImplementation()!;
		vi.mocked(fs.writeSync).mockImplementationOnce((fd: number, data: unknown) => {
			actual(fd, String(data).slice(0, 25));
			throw Object.assign(new Error("No space left on device"), { code: "ENOSPC" });
		});
		expect(() => session.appendMessage(assistantMsg("lost"))).toThrow("No space left");
		expect(readFileSync(file, "utf8").endsWith("\n")).toBe(false);

		const second = session.appendMessage(assistantMsg("after the disk was freed"));

		const content = lines(file);
		expect(content).toHaveLength(4);
		expect(JSON.parse(content[3]).id).toBe(second);
		expect(
			SessionManager.open(file, dir)
				.getBranch()
				.map((entry) => entry.id),
		).toEqual([first, second]);
	});

	it("ends a partial line in a file that replaced the session file before appending to it", () => {
		const session = SessionManager.create(dir, dir);
		session.appendMessage(userMsg("first"));
		const file = session.getSessionFile()!;
		writeFileSync(`${file}.new`, `${readFileSync(file, "utf8")}{"type":"custom","customType":"opense`);
		renameSync(`${file}.new`, file);

		const second = session.appendMessage(assistantMsg("next"));

		const content = lines(file);
		expect(content).toHaveLength(4);
		expect(content[2]).toBe('{"type":"custom","customType":"opense');
		expect(JSON.parse(content[3]).id).toBe(second);
	});
});
