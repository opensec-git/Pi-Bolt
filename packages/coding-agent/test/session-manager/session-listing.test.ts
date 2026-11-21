import {
	createReadStream,
	mkdirSync,
	readdirSync,
	readFileSync,
	rmSync,
	statSync,
	utimesSync,
	writeFileSync,
} from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { createInterface } from "readline";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { type SessionInfo, SessionManager } from "../../src/core/session-manager.ts";

const INDEX_FILE = ".session-index.json";
const CHUNK = 1024 * 1024;

/**
 * The listing as 0.9.5 read a session file: readline over the decoded file and JSON.parse of every line. The listing
 * must give the same SessionInfo for every file.
 */
async function referenceSessionInfo(filePath: string): Promise<SessionInfo | null> {
	try {
		const stats = statSync(filePath);
		let header: any = null;
		let messageCount = 0;
		let firstMessage = "";
		const allMessages: string[] = [];
		let name: string | undefined;
		let lastActivityTime: number | undefined;
		const rl = createInterface({ input: createReadStream(filePath, { encoding: "utf8" }), crlfDelay: Infinity });
		for await (const line of rl) {
			let entry: any = null;
			if (line.trim()) {
				try {
					entry = JSON.parse(line);
				} catch {}
			}
			if (!entry) continue;
			if (!header) {
				if (entry.type !== "session") return null;
				header = entry;
				continue;
			}
			if (entry.type === "session_info") name = entry.name?.trim() || undefined;
			if (entry.type !== "message") continue;
			messageCount++;
			const message = entry.message;
			const hasContent = typeof message.role === "string" && "content" in message;
			if (hasContent && (message.role === "user" || message.role === "assistant")) {
				const t = typeof message.timestamp === "number" ? message.timestamp : new Date(entry.timestamp).getTime();
				if (!Number.isNaN(t)) lastActivityTime = Math.max(lastActivityTime ?? 0, t);
			}
			if (!hasContent) continue;
			if (message.role !== "user" && message.role !== "assistant") continue;
			const text =
				typeof message.content === "string"
					? message.content
					: message.content
							.filter((b: any) => b.type === "text")
							.map((b: any) => b.text)
							.join(" ");
			if (!text) continue;
			allMessages.push(text);
			if (!firstMessage && message.role === "user") firstMessage = text;
		}
		if (!header) return null;
		const headerTime = typeof header.timestamp === "string" ? new Date(header.timestamp).getTime() : NaN;
		return {
			path: filePath,
			id: header.id,
			cwd: typeof header.cwd === "string" ? header.cwd : "",
			name,
			parentSessionPath: header.parentSession,
			created: new Date(header.timestamp),
			modified:
				typeof lastActivityTime === "number" && lastActivityTime > 0
					? new Date(lastActivityTime)
					: !Number.isNaN(headerTime)
						? new Date(headerTime)
						: stats.mtime,
			messageCount,
			firstMessage: firstMessage || "(no messages)",
			allMessagesText: allMessages.join(" "),
		};
	} catch {
		return null;
	}
}

/** SessionInfo with dates as times, so that equal lists compare equal, invalid dates included. */
function comparable(info: SessionInfo | null | undefined) {
	if (!info) return info;
	return { ...info, created: info.created.getTime(), modified: info.modified.getTime(), keys: Object.keys(info) };
}

const header = (id: string, extra: Record<string, unknown> = {}) =>
	JSON.stringify({ type: "session", version: 3, id, timestamp: "2025-01-01T00:00:00.000Z", cwd: "/work", ...extra });

let entryCounter = 0;
function messageLine(message: Record<string, unknown>, timestamp = "2025-01-02T00:00:00.000Z"): string {
	entryCounter++;
	return JSON.stringify({
		type: "message",
		id: `e${entryCounter}`,
		parentId: entryCounter === 1 ? null : `e${entryCounter - 1}`,
		timestamp,
		message,
	});
}

const user = (text: string, timestamp?: number) => messageLine({ role: "user", content: text, timestamp });
const assistant = (text: string, timestamp = 1_735_800_000_000) =>
	messageLine({ role: "assistant", content: [{ type: "text", text }], timestamp });
const toolResult = (text: string) =>
	messageLine({
		role: "toolResult",
		toolCallId: "call_1",
		toolName: "bash",
		content: [{ type: "text", text }],
		isError: false,
		timestamp: 1_735_800_000_001,
	});
const systemPrompt = (text: string) =>
	messageLine({ role: "system", content: text, tools: [{ name: "read", parameters: { type: "object" } }] });
const custom = (data: unknown) =>
	JSON.stringify({ type: "custom", customType: "ext", data, id: "c1", parentId: null, timestamp: "2025" });
const sessionInfo = (name: unknown) =>
	JSON.stringify({ type: "session_info", id: "s1", parentId: null, timestamp: "2025", name });

/** Session files that each test a way the listing could read a file differently from the reference. */
function writeCorpus(dir: string): void {
	const files: Record<string, string | Buffer> = {
		"plain.jsonl": [header("plain"), user("hello"), assistant("hi there"), toolResult("output"), ""].join("\n"),
		"tool-heavy.jsonl": [
			header("tools", { parentSession: "/parent.jsonl" }),
			user("run it", 1_735_700_000_000),
			systemPrompt("You are a coding agent"),
			...Array.from({ length: 50 }, (_, i) => toolResult(`line ${i}\n`.repeat(i))),
			custom({ big: "x".repeat(10_000) }),
			assistant("done"),
			"",
		].join("\n"),
		"crlf.jsonl": [header("crlf"), user("crlf user"), toolResult("t"), assistant("crlf reply"), ""].join("\r\n"),
		// A lone "\r" splits a line for readline: here into two entries that each parse.
		"lone-cr.jsonl": `${header("lone-cr")}\n${user("a")}\r${assistant("b")}\r${toolResult("c")}\n`,
		"bom.jsonl": Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(`${header("bom")}\n${user("x")}\n`)]),
		"bom-second-line.jsonl": Buffer.concat([
			Buffer.from(`${header("bom2")}\n`),
			Buffer.from([0xef, 0xbb, 0xbf]),
			Buffer.from(`${user("after bom")}\n${toolResult("r")}\n`),
		]),
		"invalid-utf8.jsonl": Buffer.concat([
			Buffer.from(`${header("utf8")}\n`),
			Buffer.from(user("bad: ").slice(0, -20)),
			Buffer.from([0xe2, 0x82, 0xff, 0xc3, 0x0a, 0xf0, 0x9f, 0x98]),
			Buffer.from(`\n${user("café \u{1f600}")}\n`),
			// Invalid bytes inside a string: the line still parses, and counts.
			...toolResult("[X]")
				.split("[X]")
				.flatMap((part, i) =>
					i === 0 ? [Buffer.from(part), Buffer.from([0xf0, 0x9f, 0xc3])] : [Buffer.from(part)],
				),
			Buffer.from(`\n${assistant("end")}`),
		]),
		"malformed.jsonl": [
			"not json",
			header("malformed"),
			"{broken",
			user("u"),
			// A tool result cut short counts only when it is still a whole entry.
			toolResult("cut").slice(0, 60),
			toolResult("cut").slice(0, -1),
			`${messageLine({ role: "toolResult", content: [] })} `,
			sessionInfo("  named  "),
			assistant("a"),
			"   ",
			"",
		].join("\n"),
		"session-info-clear.jsonl": [header("named"), sessionInfo("first"), user("u"), sessionInfo(""), ""].join("\n"),
		"session-info-not-string.jsonl": [header("bad-name"), sessionInfo(42), user("u"), ""].join("\n"),
		"no-trailing-newline.jsonl": [header("tail"), user("u"), toolResult("last")].join("\n"),
		"not-a-session.jsonl": [user("no header"), header("late"), ""].join("\n"),
		"empty.jsonl": "",
		"blank-lines.jsonl": "\n\n\r\n",
		"no-messages.jsonl": `${header("empty-session", { timestamp: "not a date" })}\n`,
		"odd-header.jsonl": `${JSON.stringify({ type: "session", id: 7, timestamp: 12345, cwd: 3 })}\n${user("u")}\n`,
		"escaped-type.jsonl": [
			header("escaped"),
			'{"type":"mess\\u0061ge","id":"x","parentId":null,"timestamp":"2025","message":{"role":"user","content":"escaped type"}}',
			'{"type":"message","id":"x\\"y","parentId":null,"timestamp":"2025","message":{"role":"toolResult","content":[]}}',
			'{"type":"message","id":"x","parentId":null,"timestamp":"2025","message":{"role":"us\\u0065r","content":"escaped role"}}',
			"",
		].join("\n"),
		"other-key-order.jsonl": [
			header("order"),
			JSON.stringify({ id: "x", type: "message", message: { role: "toolResult", content: [] } }),
			JSON.stringify({ type: "message", id: "y", message: { content: "user first", role: "user" } }),
			"",
		].join("\n"),
	};
	// Lines longer than the read chunk, and line breaks at chunk boundaries.
	const big = toolResult("y".repeat(CHUNK * 2));
	files["long-lines.jsonl"] = [header("long"), big, assistant("z".repeat(CHUNK + 7)), user("after"), ""].join("\n");
	const prefix = `${header("boundary")}\n`;
	const filler = (length: number) => toolResult("f".repeat(length - toolResult("").length));
	files["cr-at-boundary.jsonl"] = `${prefix}${filler(CHUNK - prefix.length - 1)}\r\n${user("next")}\n`;
	files["lf-at-boundary.jsonl"] = `${prefix}${filler(CHUNK - prefix.length)}\n${user("next")}\n`;
	files["lone-cr-at-boundary.jsonl"] = `${prefix}${filler(CHUNK - prefix.length - 1)}\r${user("next")}\n`;
	for (const [name, content] of Object.entries(files)) writeFileSync(join(dir, name), content);
	writeFileSync(join(dir, "ignored.txt"), header("ignored"));
}

describe("session listing", () => {
	let dir: string;

	beforeEach(() => {
		dir = join(tmpdir(), `session-listing-${process.pid}-${Date.now()}`);
		mkdirSync(dir, { recursive: true });
	});

	afterEach(() => {
		rmSync(dir, { recursive: true, force: true });
	});

	async function expectSameAsReference(sessions: SessionInfo[], cwd?: string): Promise<void> {
		const files = readdirJsonl(dir);
		const expected: SessionInfo[] = [];
		for (const file of files) {
			const info = await referenceSessionInfo(join(dir, file));
			if (info && (cwd === undefined || info.cwd === cwd)) expected.push(info);
		}
		expected.sort((a, b) => b.modified.getTime() - a.modified.getTime());
		expect(sessions.map(comparable)).toStrictEqual(expected.map(comparable));
	}

	function readdirJsonl(folder: string): string[] {
		return readdirSync(folder)
			.filter((file) => file.endsWith(".jsonl"))
			.sort((a, b) => b.localeCompare(a));
	}

	it("filters ID prefixes by header while preserving full session metadata and activity ordering", async () => {
		const write = (name: string, id: string, cwd: string, activity: number, separator: string) =>
			writeFileSync(join(dir, name), [header(id, { cwd }), user(id, activity), ""].join(separator));
		write("target-misleading.jsonl", "unrelated", "/work", 999, "\n");
		write("z.jsonl", "part-old", "/work", 300, "\r");
		write("a.jsonl", "part-latest", "/work", 800, "\r\n");
		write("other.jsonl", "part-elsewhere", "/elsewhere", 950, "\n");
		// A header can span the smaller first read, or follow a malformed line.
		writeFileSync(
			join(dir, "wide.jsonl"),
			["incomplete", header("part-wide", { padding: "x".repeat(8192) }), user("wide", 400)].join("\n"),
		);
		const local = await SessionManager.list("/work", dir, undefined, undefined, "part-");
		expect(local.map((s) => s.id)).toEqual(["part-latest", "part-wide", "part-old"]);
		expect(readdirSync(dir)).not.toContain(INDEX_FILE);
		const all = await SessionManager.listAll(dir, undefined, undefined, "part-");
		const full = await SessionManager.listAll(dir);
		expect(all).toEqual(full.filter((s) => s.id.startsWith("part-")));
		const index = readFileSync(join(dir, INDEX_FILE));
		expect(await SessionManager.listAll(dir, undefined, undefined, "missing")).toEqual([]);
		expect(readFileSync(join(dir, INDEX_FILE))).toEqual(index);
		expect(await SessionManager.listAll(dir)).toEqual(full);
		write("a.jsonl", "renamed", "/work", 1000, "\n");
		const changed = await SessionManager.listAll(dir, undefined, undefined, "part-");
		expect(changed.map((s) => s.id)).toEqual(["part-elsewhere", "part-wide", "part-old"]);
		expect((await SessionManager.listAll(dir, undefined, undefined, "renamed"))[0]?.path).toBe(join(dir, "a.jsonl"));
		expect(readFileSync(join(dir, INDEX_FILE))).toEqual(index);
	});

	it("recovers a prefix match from the transcript if its cached ID is missing", async () => {
		const file = join(dir, "renamed.jsonl");
		writeFileSync(file, header("actual-id"));
		await SessionManager.listAll(dir);
		const indexPath = join(dir, INDEX_FILE);
		const cached = JSON.parse(readFileSync(indexPath, "utf8"));
		delete cached.sessions[file].info.id;
		writeFileSync(indexPath, JSON.stringify(cached));
		expect((await SessionManager.listAll(dir, undefined, undefined, "actual")).map((s) => s.id)).toEqual([
			"actual-id",
		]);
	});

	it("gives what reading and parsing every line gives", async () => {
		writeCorpus(dir);
		const sessions = await SessionManager.listAll(dir);
		expect(sessions.length).toBeGreaterThan(15);
		await expectSameAsReference(sessions);
		// The same from the index, without reading the sessions.
		expect(JSON.parse(readFileSync(join(dir, INDEX_FILE), "utf8")).version).toBe(1);
		await expectSameAsReference(await SessionManager.listAll(dir));
		await expectSameAsReference(await SessionManager.list("/work", dir), "/work");
	});

	it("counts a tool result line damaged in its middle, which 0.9.5 skipped", async () => {
		// Lines that a listing only counts are not parsed, so a line that still starts and ends like an entry is counted.
		// Pi does not write such lines: it writes whole lines from JSON.stringify, and a write cut short loses the end.
		const [before, after] = toolResult("[X]").split("[X]");
		const file = join(dir, "damaged.jsonl");
		writeFileSync(file, `${header("damaged")}\n${before}\u0000${after}\n${user("u")}\n`);
		const [info] = await SessionManager.listAll(dir);
		expect(info?.messageCount).toBe(2);
		expect((await referenceSessionInfo(file))?.messageCount).toBe(1);
	});

	it("uses an index entry only while the file's stats match", async () => {
		writeCorpus(dir);
		await SessionManager.listAll(dir);
		const file = join(dir, "plain.jsonl");
		const index = JSON.parse(readFileSync(join(dir, INDEX_FILE), "utf8"));
		index.sessions[file].info.messageCount = 999;
		writeFileSync(join(dir, INDEX_FILE), JSON.stringify(index));
		const fromIndex = await SessionManager.listAll(dir);
		expect(fromIndex.find((s) => s.path === file)?.messageCount).toBe(999);

		// An append changes the size and times: the file is read again.
		writeFileSync(join(dir, "plain.jsonl"), `${readFileSync(file, "utf8")}${user("more")}\n`);
		const afterAppend = await SessionManager.listAll(dir);
		expect(afterAppend.find((s) => s.path === file)?.messageCount).toBe(4);
		await expectSameAsReference(afterAppend);

		// A rewrite to the same size with the old modification time still changes the change time.
		const before = statSync(file);
		writeFileSync(file, readFileSync(file, "utf8").replace("more", "MORE"));
		utimesSync(file, before.atime, before.mtime);
		const afterRewrite = await SessionManager.listAll(dir);
		expect(afterRewrite.find((s) => s.path === file)?.allMessagesText).toContain("MORE");
		await expectSameAsReference(afterRewrite);
	});

	it("drops deleted sessions from the index and adds new ones", async () => {
		writeCorpus(dir);
		await SessionManager.listAll(dir);
		rmSync(join(dir, "plain.jsonl"));
		writeFileSync(join(dir, "new.jsonl"), `${header("new")}\n${user("new one")}\n`);
		await expectSameAsReference(await SessionManager.listAll(dir));
		const index = JSON.parse(readFileSync(join(dir, INDEX_FILE), "utf8"));
		expect(Object.keys(index.sessions)).not.toContain(join(dir, "plain.jsonl"));
		expect(Object.keys(index.sessions)).toContain(join(dir, "new.jsonl"));
	});

	it("reads every session again when the index is corrupt", async () => {
		writeCorpus(dir);
		await SessionManager.listAll(dir);
		const valid = readFileSync(join(dir, INDEX_FILE), "utf8");
		const corrupt = [
			valid.slice(0, valid.length / 2),
			"null",
			'{"version":1,"sessions":null}',
			JSON.stringify({ version: 2, sessions: JSON.parse(valid).sessions }),
			// Entries of the wrong shape are not used.
			valid.replaceAll('"messageCount":', '"messageCount":"x","was":'),
			valid.replaceAll('"revision":', '"revision":0,"was":'),
		];
		for (const content of corrupt) {
			writeFileSync(join(dir, INDEX_FILE), content);
			await expectSameAsReference(await SessionManager.listAll(dir));
			// The listing writes a usable index again.
			expect(JSON.parse(readFileSync(join(dir, INDEX_FILE), "utf8")).version).toBe(1);
		}
	});

	it("gives correct results to listings that run at once", async () => {
		writeCorpus(dir);
		const listings = await Promise.all(Array.from({ length: 4 }, () => SessionManager.listAll(dir)));
		for (const sessions of listings) await expectSameAsReference(sessions);
		await expectSameAsReference(await SessionManager.listAll(dir));
		expect(readdirSync(dir).filter((file) => file.endsWith(".tmp"))).toEqual([]);
	});

	it("lists sessions of a folder it cannot write the index to", async () => {
		writeCorpus(dir);
		mkdirSync(join(dir, INDEX_FILE));
		await expectSameAsReference(await SessionManager.listAll(dir));
		await expectSameAsReference(await SessionManager.listAll(dir));
	});

	it("lists sessions written by SessionManager", async () => {
		const session = SessionManager.create("/work", dir);
		session.appendMessage({ role: "user", content: "first", timestamp: 1_735_700_000_000 });
		session.appendSessionInfo("named");
		session.appendMessage({
			role: "toolResult",
			toolCallId: "c",
			toolName: "read",
			content: [{ type: "text", text: "file" }],
			isError: false,
			timestamp: 1_735_700_000_001,
		});
		await expectSameAsReference(await SessionManager.list("/work", dir), "/work");
		session.appendMessage({ role: "user", content: "second", timestamp: 1_735_700_000_002 });
		const sessions = await SessionManager.list("/work", dir);
		expect(sessions[0]?.messageCount).toBe(3);
		await expectSameAsReference(sessions, "/work");
	});
});
