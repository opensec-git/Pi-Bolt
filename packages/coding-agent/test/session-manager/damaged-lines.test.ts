import { execFileSync } from "child_process";
import * as fs from "fs";
import { mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join, resolve } from "path";
import { pathToFileURL } from "url";
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

	it.skipIf(process.platform === "win32")(
		"cuts off a line whose last write failed, as the session rolls it back",
		() => {
			const session = SessionManager.create(dir, dir);
			session.appendMessage(userMsg("first"));
			const file = session.getSessionFile()!;
			const before = readFileSync(file, "utf8");
			const actual = vi.mocked(fs.writeSync).getMockImplementation()!;
			// The record is written, all but its line feed; writing the rest fails.
			vi.mocked(fs.writeSync)
				.mockImplementationOnce((fd: number, data: unknown) => actual(fd, String(data).slice(0, -1)))
				.mockImplementationOnce(() => {
					throw Object.assign(new Error("No space left on device"), { code: "ENOSPC" });
				});
			expect(() => session.appendMessage(assistantMsg("not saved"))).toThrow("No space left");
			expect(readFileSync(file, "utf8")).toBe(before);

			session.appendMessage(assistantMsg("after the disk was freed"));
			const expected = session.getEntries().map((entry) => entry.id);
			expect(expected).toHaveLength(2);
			expect(
				SessionManager.open(file, dir)
					.getEntries()
					.map((entry) => entry.id),
			).toEqual(expected);
		},
	);

	it.skipIf(process.platform === "win32")(
		"does not cut a line that another process appended after a failed write",
		() => {
			const session = SessionManager.create(dir, dir);
			session.appendMessage(userMsg("first"));
			const file = session.getSessionFile()!;
			const actual = vi.mocked(fs.writeSync).getMockImplementation()!;
			const other = '{"type":"custom","customType":"other","id":"other","parentId":null,"timestamp":"x"}\n';
			vi.mocked(fs.writeSync)
				.mockImplementationOnce((fd: number, data: unknown) => actual(fd, String(data).slice(0, 25)))
				.mockImplementationOnce(() => {
					fs.appendFileSync(file, other);
					throw Object.assign(new Error("No space left on device"), { code: "ENOSPC" });
				});
			expect(() => session.appendMessage(assistantMsg("lost"))).toThrow("No space left");
			expect(readFileSync(file, "utf8").endsWith(other)).toBe(true);
		},
	);

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

	// A last line that parses as JSON but is not an entry (`{}`, `42`, `[1]`, a record without an id) left the leaf id
	// undefined, and `_leafPath()` then read `.path` of the missing cache: `pi-bolt -c` crashed on every start.
	it.each(["{}", "42", "[1,2,3]", '"text"', "true", '{"type":"message","message":{"role":"user","content":"x"}}'])(
		"opens a file whose last line %s parses but is not an entry",
		(line) => {
			const { file, ids } = writeSession();
			rewrite(file, (content) => {
				content.push(line);
			});
			vi.spyOn(console, "warn").mockImplementation(() => {});

			const session = SessionManager.open(file, dir);

			expect(session.getBranch().map((entry) => entry.id)).toEqual(ids);
			expect(session.buildSessionContext().messages[0]).toMatchObject({ role: "user", content: "original prompt" });
		},
	);

	it("cuts parent links that form a cycle like a missing parent", () => {
		// e1 and e2 name each other; e3 hangs below the cycle. In a second file a repeated id closes a cycle (the last
		// definition of "a" names "b", whose parent is "a"). Walking the parent links never ended before, so the session
		// is opened in a child process that is killed when it does not finish in time.
		const message = (id: string, parentId: string | null) => ({
			type: "message",
			id,
			parentId,
			timestamp: "2026-01-01T00:00:00.000Z",
			message: userMsg(id),
		});
		const header = { type: "session", version: 3, id: "cycle", timestamp: "2026-01-01T00:00:00.000Z", cwd: dir };
		const write = (name: string, entries: object[]) => {
			const file = join(dir, name);
			writeFileSync(file, `${[header, ...entries].map((entry) => JSON.stringify(entry)).join("\n")}\n`);
			return file;
		};
		const cycle = write("cycle.jsonl", [
			message("r", null),
			message("e1", "e2"),
			message("e2", "e1"),
			message("e3", "e2"),
		]);
		const repeated = write("repeated.jsonl", [message("a", null), message("b", "a"), message("a", "b")]);
		const resolverUrl = pathToFileURL(resolve(__dirname, "../../src/experimental/source-resolver.ts")).href;
		const managerUrl = pathToFileURL(resolve(__dirname, "../../src/core/session-manager.ts")).href;
		const summaryUrl = pathToFileURL(resolve(__dirname, "../../src/core/compaction/branch-summarization.ts")).href;
		const script = `
			const { SessionManager } = await import(${JSON.stringify(managerUrl)});
			const { collectEntriesForBranchSummary } = await import(${JSON.stringify(summaryUrl)});
			const ids = (entries) => entries.map((entry) => entry.id);
			const tree = (nodes) => nodes.map((node) => [node.entry.id, tree(node.children)]);
			const result = {};
			for (const [name, file] of Object.entries(${JSON.stringify({ cycle, repeated })})) {
				const session = SessionManager.open(file, ${JSON.stringify(dir)});
				result[name] = {
					branch: ids(session.getBranch()),
					fromE2: name === "cycle" ? ids(session.getBranch("e2")) : undefined,
					tree: tree(session.getTree()),
					messages: session.buildSessionContext().messages.length,
					summary: name === "cycle" ? ids(collectEntriesForBranchSummary(session, "e3", "r").entries) : undefined,
				};
			}
			process.stdout.write(JSON.stringify(result));
		`;
		const output = execFileSync(
			process.execPath,
			["--import", resolverUrl, "--input-type=module", "--eval", script],
			{
				encoding: "utf-8",
				stdio: ["ignore", "pipe", "pipe"],
				timeout: 15_000,
			},
		);

		expect(JSON.parse(output)).toEqual({
			cycle: {
				branch: ["e1", "e2", "e3"],
				fromE2: ["e1", "e2"],
				tree: [
					["r", []],
					["e1", [["e2", [["e3", []]]]]],
				],
				messages: 3,
				summary: ["e1", "e2", "e3"],
			},
			repeated: expect.objectContaining({ branch: ["b", "a"], messages: 2 }),
		});
	}, 30_000);
});
