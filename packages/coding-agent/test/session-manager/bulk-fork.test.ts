import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import * as fs from "fs";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionManager } from "../../src/core/session-manager.ts";

vi.mock("fs", async (importOriginal) => {
	const actual = await importOriginal<typeof fs>();
	return { ...actual, linkSync: vi.fn(actual.linkSync), writeFileSync: vi.fn(actual.writeFileSync) };
});

describe("bulk session forks", () => {
	let dir: string;

	beforeEach(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-fork-"));
	});
	afterEach(() => {
		vi.restoreAllMocks();
		rmSync(dir, { recursive: true, force: true });
	});

	it.each(["memory", "disk"])("avoids collisions with retained entries and replacement labels in %s", (mode) => {
		const session = mode === "memory" ? SessionManager.inMemory(dir) : SessionManager.create(dir, dir);
		const ids = ["00000001", "00000002", "00000003", "00000004"];
		vi.spyOn(crypto, "randomUUID").mockImplementation(() => `${ids.shift()}-0000-4000-8000-000000000000`);
		const first = session.appendMessage({ role: "user", content: "A", timestamp: 1 });
		session.appendLabelChange(first, "first");
		const second = session.appendMessage({ role: "user", content: "B", timestamp: 2 });
		const leaf = session.appendLabelChange(second, "second");
		const original = session.getEntries().filter((entry) => entry.type === "label");
		ids.push(first, "1234abcd", "1234abcd", "abcd1234");
		const file = session.createBranchedSession(leaf);
		const restored = file ? SessionManager.open(file) : session;
		const entries = restored.getEntries();
		expect(entries.map((e) => e.id)).toEqual([first, second, "1234abcd", "abcd1234"]);
		expect(entries.map((e) => e.parentId)).toEqual([null, first, second, "1234abcd"]);
		expect(entries.filter((e) => e.type === "label").map((e) => [e.targetId, e.label, e.timestamp])).toEqual(
			original.map((e) => [e.targetId, e.label, e.timestamp]),
		);
	});

	it("keeps an existing fork when two attempts select the same filename", () => {
		const source = join(dir, "source.jsonl");
		const model = SessionManager.inMemory(dir);
		model.appendMessage({ role: "user", timestamp: 1, content: "source" });
		const original = `${[model.getHeader(), ...model.getEntries()].map((e) => JSON.stringify(e)).join("\n")}\n`;
		writeFileSync(source, original);
		vi.useFakeTimers();
		try {
			vi.setSystemTime(new Date("2026-01-01T00:00:00Z"));
			const file = SessionManager.forkFrom(source, dir, dir, { id: "same" }).getSessionFile()!;
			const existing = readFileSync(file);
			expect(() => SessionManager.forkFrom(source, dir, dir, { id: "same" })).toThrow();
			expect(readFileSync(file)).toEqual(existing);
			expect(readFileSync(source, "utf8")).toBe(original);
			expect(readdirSync(dir).sort()).toEqual(["2026-01-01T00-00-00-000Z_same.jsonl", "source.jsonl"]);
		} finally {
			vi.useRealTimers();
		}
	});

	it("uses an exclusive copy on filesystems without hard links", () => {
		const source = join(dir, "source.jsonl");
		const model = SessionManager.inMemory(dir);
		model.appendCustomEntry("unicode", "𐍈".repeat(50000));
		writeFileSync(source, [model.getHeader(), ...model.getEntries()].map((e) => JSON.stringify(e)).join("\n"));
		vi.mocked(fs.linkSync).mockImplementationOnce((temp, dest) => {
			expect(existsSync(dest)).toBe(false);
			expect(readFileSync(temp, "utf8").endsWith("\n")).toBe(true);
			throw Object.assign(new Error("Link unsupported"), { code: "ENOTSUP" });
		});
		const fork = SessionManager.forkFrom(source, dir, dir);
		expect(SessionManager.open(fork.getSessionFile()!).getEntries()).toEqual(model.getEntries());
		expect(readFileSync(source, "utf8").endsWith("\n")).toBe(true);
		expect(readdirSync(dir).some((path) => path.endsWith(".tmp"))).toBe(false);
	});

	it("discards an incomplete copy when writing a bulk record fails", () => {
		const source = join(dir, "source.jsonl");
		const model = SessionManager.inMemory(dir);
		model.appendCustomEntry("first", "a".repeat(100000));
		model.appendCustomEntry("second", "b".repeat(100000));
		const original = `${[model.getHeader(), ...model.getEntries()].map((e) => JSON.stringify(e)).join("\n")}\n`;
		writeFileSync(source, original);
		vi.mocked(fs.writeFileSync)
			.mockImplementationOnce(writeFileSync)
			.mockImplementationOnce(() => {
				throw Object.assign(new Error("Disk full"), { code: "ENOSPC" });
			});
		expect(() => SessionManager.forkFrom(source, dir, dir)).toThrow("Disk full");
		expect(readFileSync(source, "utf8")).toBe(original);
		expect(readdirSync(dir)).toEqual(["source.jsonl"]);
	});

	it("rejects a non-header first record before creating the destination directory", () => {
		const source = join(dir, "invalid.jsonl");
		const dest = join(dir, "new-dir");
		writeFileSync(source, `42\n${JSON.stringify(SessionManager.inMemory(dir).getHeader())}`);
		expect(() => SessionManager.forkFrom(source, dir, dest)).toThrow("empty or invalid");
		expect(existsSync(dest)).toBe(false);
		expect(readFileSync(source, "utf8").endsWith("\n")).toBe(false);
	});

	it("cleans up its temporary file on publication errors", () => {
		const source = join(dir, "source.jsonl");
		writeFileSync(source, `${JSON.stringify(SessionManager.inMemory(dir).getHeader())}\n`);
		const error = Object.assign(new Error("Write failed"), { code: "EIO" });
		vi.mocked(fs.linkSync).mockImplementationOnce(() => {
			throw error;
		});
		expect(() => SessionManager.forkFrom(source, dir, dir)).toThrow(error);
		expect(readdirSync(dir)).toEqual(["source.jsonl"]);
	});

	it("preserves complete records across batch boundaries and normalizes imported JSON on reopen", () => {
		const source = join(dir, "source.jsonl");
		const model = SessionManager.inMemory(dir);
		const header = JSON.stringify(model.getHeader());
		for (const width of [12, 33000, 66000, 12]) model.appendCustomEntry("notes", "📝\n".repeat(width));
		const records = model.getEntries().map((e) => JSON.stringify(e));
		const number = '{"type":"custom","id":"n","parentId":null,"customType":"number","data":[1e400,-0]}';
		writeFileSync(source, [header, ...records, "partial{", header, number].join("\n"));
		const fork = SessionManager.forkFrom(source, dir, dir);
		const stored = readFileSync(fork.getSessionFile()!, "utf8").split("\n");
		expect(stored.slice(1)).toEqual([...records, JSON.stringify(JSON.parse(number)), ""]);
		expect(fork.getEntries()).toEqual(SessionManager.open(fork.getSessionFile()!).getEntries());
	});
});
