import { chmodSync, mkdtempSync, rmSync, statSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { getDefaultSessionDir, SessionManager } from "../../src/core/session-manager.ts";
import { assistantMsg, userMsg } from "../utilities.ts";

const mode = (path: string) => statSync(path).mode & 0o777;

// Windows keeps only the read-only bit of a mode; there transcripts inherit the access list of the user's profile.
describe.skipIf(process.platform === "win32")("session file modes", () => {
	let root: string;

	beforeEach(() => {
		root = mkdtempSync(join(tmpdir(), "session-modes-"));
	});

	afterEach(() => {
		rmSync(root, { recursive: true, force: true });
	});

	it("creates session folders and transcripts that only the user can read", async () => {
		const agentDir = join(root, "agent");
		const sessionDir = getDefaultSessionDir(join(root, "project"), agentDir);
		expect(mode(sessionDir)).toBe(0o700);

		const custom = join(root, "custom", "sessions");
		const session = SessionManager.create(join(root, "project"), custom);
		expect(mode(custom)).toBe(0o700);
		session.appendMessage(userMsg("hi"));
		session.appendMessage(assistantMsg("hello"));
		const file = session.getSessionFile()!;
		expect(mode(file)).toBe(0o600);

		const fork = SessionManager.forkFrom(file, join(root, "project"), join(root, "forks"));
		expect(mode(join(root, "forks"))).toBe(0o700);
		expect(mode(fork.getSessionFile()!)).toBe(0o600);

		// The listing's index holds the text of the sessions' messages.
		await SessionManager.list(join(root, "project"), custom);
		expect(mode(join(custom, ".session-index.json"))).toBe(0o600);
	});

	it("takes other users' access away from a transcript it writes to", () => {
		const dir = join(root, "sessions");
		const first = SessionManager.create(root, dir);
		first.appendMessage(userMsg("hi"));
		first.appendMessage(assistantMsg("hello"));
		const file = first.getSessionFile()!;
		// As earlier versions created them.
		chmodSync(file, 0o644);

		const reopened = SessionManager.open(file, dir);
		reopened.appendMessage(userMsg("again"));
		expect(mode(file)).toBe(0o600);
	});

	it("leaves files in the session folder that it does not write alone", () => {
		const dir = join(root, "sessions");
		const other = join(dir, "notes.txt");
		const session = SessionManager.create(root, dir);
		writeFileSync(other, "mine", { mode: 0o644 });
		session.appendMessage(userMsg("hi"));
		session.appendMessage(assistantMsg("hello"));
		expect(mode(other)).toBe(0o644);
	});
});
