import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, realpathSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { SessionManager } from "../../src/core/session-manager.ts";
import { generateKeyPair } from "../../src/remote-control/noise.ts";
import { isSecretPath, REVIEW_LIMITS } from "../../src/remote-control/review.ts";
import { RemoteControlServer } from "../../src/remote-control/server.ts";
import { RemoteControlStore } from "../../src/remote-control/store.ts";
import { createHarness } from "./harness.ts";
import { connectTestClient, type TestClient } from "./remote-control-client.ts";

type Data = Record<string, unknown>;

describe("remote control review", () => {
	const cleanups: Array<() => unknown> = [];

	afterEach(async () => {
		while (cleanups.length > 0) await cleanups.pop()?.();
	});

	function tempDir(prefix = "pi-review-"): string {
		const dir = realpathSync(mkdtempSync(join(tmpdir(), prefix)));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		return dir;
	}

	function git(cwd: string, ...args: string[]): string {
		return execFileSync(
			"git",
			["-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", ...args],
			{ cwd, encoding: "utf8" },
		);
	}

	/** A repository with two commits, then: `a.txt` modified, `new.txt` staged, `notes.md` untracked, `old.txt` renamed. */
	function makeRepo(): string {
		const repo = tempDir();
		git(repo, "init", "-q", "-b", "main");
		writeFileSync(join(repo, "a.txt"), "one\ntwo\n");
		writeFileSync(join(repo, "old.txt"), "moving\n");
		mkdirSync(join(repo, "sub"));
		writeFileSync(join(repo, "sub", "x.ts"), "export const x = 1;\n");
		git(repo, "add", ".");
		git(repo, "commit", "-q", "-m", "first");
		writeFileSync(join(repo, "a.txt"), "one\ntwo\nthree\n");
		writeFileSync(join(repo, ".env"), "TOKEN=hunter2\n");
		git(repo, "add", ".");
		git(repo, "commit", "-q", "-m", "second", "-m", "with a body");
		writeFileSync(join(repo, "a.txt"), "one\n2\nthree\n");
		writeFileSync(join(repo, "new.txt"), "fresh\n");
		git(repo, "add", "new.txt");
		git(repo, "mv", "old.txt", "moved.txt");
		writeFileSync(join(repo, "notes.md"), "# notes\n");
		return repo;
	}

	async function connect(
		cwd: string,
		options: { readSecretFiles?: boolean } = {},
	): Promise<{ phone: TestClient; hello: Data }> {
		const harness = await createHarness({ sessionManager: SessionManager.inMemory(cwd) });
		cleanups.push(() => harness.cleanup());
		const server = new RemoteControlServer({
			store: new RemoteControlStore(tempDir("pi-review-store-")),
			getSession: () => harness.session,
			hostName: "test-host",
			version: "9.9.9",
			bindAddress: "127.0.0.1",
			readSecretFiles: () => options.readSecretFiles === true,
		});
		const port = await server.start();
		cleanups.push(() => server.stop());
		const phone = await connectTestClient(server.pairingUri([`ws://127.0.0.1:${port}/rc`]), generateKeyPair());
		cleanups.push(() => phone.close());
		const hello = await phone.waitFor((message) => message.type === "remote_hello");
		return { phone, hello };
	}

	async function ok(phone: TestClient, command: Data): Promise<Data> {
		const response = await phone.request(command);
		expect(response, JSON.stringify(response)).toMatchObject({ type: "response", success: true });
		return response.data as Data;
	}

	async function refused(phone: TestClient, command: Data): Promise<string> {
		const response = await phone.request(command);
		expect(response.success, JSON.stringify(response)).toBe(false);
		return String(response.error);
	}

	it("says it can review in remote_hello", async () => {
		const { hello } = await connect(tempDir());
		expect(hello.host).toMatchObject({ review: true });
	});

	it("reports branch, entries with kinds and line counts, and the total", async () => {
		const repo = makeRepo();
		const { phone } = await connect(repo);
		const status = await ok(phone, { type: "rc_git_status" });
		expect(status.repo).toBe(true);
		expect(status.truncated).toBe(false);
		expect(status.branch).toMatchObject({ head: "main", upstream: null, ahead: 0, behind: 0 });
		const entries = status.entries as Data[];
		const byPath = Object.fromEntries(entries.map((entry) => [entry.path, entry]));
		expect(byPath["a.txt"]).toMatchObject({
			index: null,
			worktree: "M",
			kind: "modified",
			staged: null,
			unstaged: { added: 1, removed: 1 },
		});
		expect(byPath["new.txt"]).toMatchObject({
			index: "A",
			worktree: null,
			kind: "added",
			staged: { added: 1, removed: 0 },
		});
		expect(byPath["notes.md"]).toMatchObject({ index: null, worktree: "?", kind: "untracked" });
		expect(byPath["moved.txt"]).toMatchObject({ index: "R", kind: "renamed", origPath: "old.txt" });
		expect(status.total).toEqual({ added: 2, removed: 1 });
	});

	it("keeps status, diffs and history inside a subfolder cwd, with paths relative to it", async () => {
		const repo = makeRepo();
		writeFileSync(join(repo, "sub", "x.ts"), "export const x = 2;\n");
		const { phone } = await connect(join(repo, "sub"));
		const status = await ok(phone, { type: "rc_git_status" });
		expect((status.entries as Data[]).map((entry) => entry.path)).toEqual(["x.ts"]);
		const diff = await ok(phone, { type: "rc_git_diff", path: "x.ts" });
		expect(diff.diff).toContain("+++ b/x.ts");
		const log = await ok(phone, { type: "rc_git_log" });
		expect((log.commits as Data[]).map((commit) => commit.subject)).toEqual(["first"]);
	});

	it("answers outside a repository without failing", async () => {
		const { phone } = await connect(tempDir());
		expect(await ok(phone, { type: "rc_git_status" })).toEqual({
			repo: false,
			branch: null,
			entries: [],
			total: { added: 0, removed: 0 },
			truncated: false,
		});
		expect(await ok(phone, { type: "rc_git_log" })).toEqual({ repo: false, commits: [], more: false });
	});

	it("diffs the unstaged, staged and untracked sides", async () => {
		const repo = makeRepo();
		const { phone } = await connect(repo);
		const unstaged = await ok(phone, { type: "rc_git_diff", path: "a.txt" });
		expect(unstaged).toMatchObject({ path: "a.txt", staged: false, untracked: false, truncated: false });
		expect(unstaged.diff).toContain("-two\n+2\n");
		const staged = await ok(phone, { type: "rc_git_diff", path: "new.txt", staged: true });
		expect(staged.diff).toContain("+fresh");
		const renamed = await ok(phone, { type: "rc_git_diff", path: "moved.txt", origPath: "old.txt", staged: true });
		expect(renamed.diff).toContain("rename from old.txt");
		const untracked = await ok(phone, { type: "rc_git_diff", path: "notes.md" });
		expect(untracked).toMatchObject({ untracked: true });
		expect(untracked.diff).toContain("+# notes");
	});

	it("cuts a large diff at a line end", async () => {
		const repo = makeRepo();
		const line = `${"x".repeat(99)}\n`;
		writeFileSync(join(repo, "big.txt"), line.repeat(Math.ceil((REVIEW_LIMITS.diffBytes * 1.5) / line.length)));
		const { phone } = await connect(repo);
		const diff = await ok(phone, { type: "rc_git_diff", path: "big.txt" });
		expect(diff.truncated).toBe(true);
		const text = String(diff.diff);
		expect(Buffer.byteLength(text)).toBeLessThanOrEqual(REVIEW_LIMITS.diffBytes);
		expect(text.endsWith("\n")).toBe(true);
	});

	it("refuses paths that leave the folder, by .., absolute path or symlink", async () => {
		const repo = makeRepo();
		const outside = tempDir("pi-review-outside-");
		writeFileSync(join(outside, "private.txt"), "private\n");
		symlinkSync(outside, join(repo, "link-dir"));
		symlinkSync(join(outside, "private.txt"), join(repo, "link-file"));
		const { phone } = await connect(repo);
		for (const type of ["rc_read_file", "rc_git_diff", "rc_list_dir"]) {
			expect(await refused(phone, { type, path: "../x" })).toContain("leaves the session's folder");
			expect(await refused(phone, { type, path: "sub/../../x" })).toContain("leaves the session's folder");
			expect(await refused(phone, { type, path: join(outside, "private.txt") })).toContain("relative");
		}
		expect(await refused(phone, { type: "rc_read_file", path: "link-file" })).toContain("leaves");
		expect(await refused(phone, { type: "rc_read_file", path: "link-dir/private.txt" })).toContain("leaves");
		expect(await refused(phone, { type: "rc_list_dir", path: "link-dir" })).toContain("leaves");
		expect(await refused(phone, { type: "rc_git_diff", path: "link-dir/private.txt" })).toContain("leaves");
		expect(await refused(phone, { type: "rc_read_file", path: 42 })).toContain("path must be a string");
	});

	it("lists a folder with kinds, sizes and symlinks", async () => {
		const repo = makeRepo();
		const outside = tempDir("pi-review-outside-");
		symlinkSync(outside, join(repo, "away"));
		symlinkSync(join(repo, "sub"), join(repo, "here"));
		const { phone } = await connect(repo);
		const listing = await ok(phone, { type: "rc_list_dir" });
		expect(listing.path).toBe("");
		const byName = Object.fromEntries((listing.entries as Data[]).map((entry) => [entry.name, entry]));
		expect(byName["a.txt"]).toMatchObject({ kind: "file", size: 12 });
		expect(byName.sub).toMatchObject({ kind: "dir" });
		expect(byName.here).toMatchObject({ kind: "dir", symlink: true });
		expect(byName.away).toMatchObject({ symlink: true, outside: true });
		expect(byName[".git"]).toMatchObject({ kind: "dir" });
		const nested = await ok(phone, { type: "rc_list_dir", path: "sub" });
		expect((nested.entries as Data[]).map((entry) => entry.name)).toEqual(["x.ts"]);
		expect(await refused(phone, { type: "rc_list_dir", path: "a.txt" })).toContain("not a folder");
	});

	it("reads text, flags binary files, and caps large ones", async () => {
		const repo = makeRepo();
		writeFileSync(join(repo, "image.bin"), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0x01]));
		// A multi-byte character straddles the cap: the content stops before it.
		writeFileSync(join(repo, "large.txt"), `${"a".repeat(REVIEW_LIMITS.fileBytes - 1)}é and more`);
		const { phone } = await connect(repo);
		expect(await ok(phone, { type: "rc_read_file", path: "a.txt" })).toMatchObject({
			path: "a.txt",
			size: 12,
			binary: false,
			truncated: false,
			content: "one\n2\nthree\n",
		});
		const binary = await ok(phone, { type: "rc_read_file", path: "image.bin" });
		expect(binary).toMatchObject({ binary: true, truncated: false, size: 6 });
		expect(binary.content).toBeUndefined();
		const large = await ok(phone, { type: "rc_read_file", path: "large.txt" });
		expect(large.truncated).toBe(true);
		expect(large.content).toBe("a".repeat(REVIEW_LIMITS.fileBytes - 1));
		expect(await refused(phone, { type: "rc_read_file", path: "sub" })).toContain("not a file");
		if (process.platform !== "win32") {
			// Opening a FIFO for reading would wait for a writer.
			execFileSync("mkfifo", [join(repo, "pipe")]);
			expect(await refused(phone, { type: "rc_read_file", path: "pipe" })).toContain("not a file");
		}
		expect(await refused(phone, { type: "rc_read_file", path: "missing.txt" })).toContain("does not exist");
	});

	it("refuses secret files unless the user opted in, and withholds them from commits", async () => {
		const repo = makeRepo();
		mkdirSync(join(repo, "keys"));
		writeFileSync(join(repo, "keys", "id_ed25519"), "PRIVATE\n");
		writeFileSync(join(repo, "server.pem"), "PEM\n");
		const { phone } = await connect(repo);
		for (const path of [".env", "keys/id_ed25519", "server.pem", ".git/config"]) {
			expect(await refused(phone, { type: "rc_read_file", path })).toContain("looks like a secret file");
		}
		expect(await refused(phone, { type: "rc_git_diff", path: ".env" })).toContain("looks like a secret file");
		const log = await ok(phone, { type: "rc_git_log", limit: 1 });
		const second = (log.commits as Data[])[0];
		const shown = await ok(phone, { type: "rc_git_show", hash: second.hash });
		expect(shown.withheld).toEqual([".env"]);
		expect(shown.diff).toContain("+three");
		expect(shown.diff).not.toContain("hunter2");

		const { phone: trusting } = await connect(repo, { readSecretFiles: true });
		expect(await ok(trusting, { type: "rc_read_file", path: ".env" })).toMatchObject({ content: "TOKEN=hunter2\n" });
		const all = await ok(trusting, { type: "rc_git_show", hash: second.hash });
		expect(all.withheld).toEqual([]);
		expect(all.diff).toContain("hunter2");
	});

	it("refuses a folder path in a diff, so files below it are not shown past the secret check", async () => {
		const repo = makeRepo();
		mkdirSync(join(repo, "config"));
		writeFileSync(join(repo, "config", ".env"), "TOKEN=old\n");
		git(repo, "add", "config");
		git(repo, "commit", "-q", "-m", "config");
		writeFileSync(join(repo, "config", ".env"), "TOKEN=changed\n");
		const { phone } = await connect(repo);
		expect(await refused(phone, { type: "rc_git_diff", path: "config" })).toContain("not a file");
		git(repo, "add", "config");
		expect(await refused(phone, { type: "rc_git_diff", path: "config", staged: true })).toContain("not a file");
		// A deleted folder no longer exists on disk, but git still matches the files below it.
		git(repo, "rm", "-q", "-r", "-f", "config");
		expect(await refused(phone, { type: "rc_git_diff", path: "config", staged: true })).toContain("not a file");
		expect(await ok(phone, { type: "rc_git_diff", path: "a.txt" })).toMatchObject({ path: "a.txt" });
	});

	it("shows a submodule as commit ids even when the repository asks for its full diff", async () => {
		const repo = makeRepo();
		const module = join(repo, "module");
		mkdirSync(module);
		git(module, "init", "-q", "-b", "main");
		writeFileSync(join(module, ".env"), "TOKEN=module-old\n");
		git(module, "add", ".env");
		git(module, "commit", "-q", "-m", "one");
		// The nested repository is added as a gitlink, like a submodule.
		git(repo, "update-index", "--add", "--cacheinfo", `160000,${git(module, "rev-parse", "HEAD").trim()},module`);
		git(repo, "commit", "-q", "-m", "add module");
		git(repo, "config", "diff.submodule", "diff");
		writeFileSync(join(module, ".env"), "TOKEN=module-new\n");
		git(module, "commit", "-q", "-am", "two");
		const { phone } = await connect(repo);
		const unstaged = await ok(phone, { type: "rc_git_diff", path: "module" });
		expect(unstaged.diff).toContain("Subproject commit");
		expect(unstaged.diff).not.toContain("TOKEN");
		git(repo, "add", "module");
		const staged = await ok(phone, { type: "rc_git_diff", path: "module", staged: true });
		expect(staged.diff).not.toContain("TOKEN");
		git(repo, "commit", "-q", "-m", "bump module");
		const shown = await ok(phone, { type: "rc_git_show", hash: git(repo, "rev-parse", "HEAD").trim() });
		expect(shown.diff).toContain("Subproject commit");
		expect(shown.diff).not.toContain("TOKEN");
	});

	it.skipIf(process.platform === "win32")("never runs the repository's content filters", async () => {
		const repo = makeRepo();
		const markers = tempDir("pi-review-markers-");
		writeFileSync(join(repo, ".gitattributes"), "visible.txt filter=probe\nother.txt filter=pro.be\n");
		writeFileSync(join(repo, "visible.txt"), "one\n");
		writeFileSync(join(repo, "other.txt"), "one\n");
		git(repo, "add", ".gitattributes", "visible.txt", "other.txt");
		git(repo, "commit", "-q", "-m", "filtered files");
		git(repo, "config", "filter.probe.clean", `touch '${join(markers, "clean")}'; cat`);
		git(repo, "config", "filter.probe.required", "true");
		git(repo, "config", "filter.pro.be.process", `touch '${join(markers, "process")}'`);
		writeFileSync(join(repo, "visible.txt"), "two\n");
		writeFileSync(join(repo, "other.txt"), "two\n");
		writeFileSync(join(repo, "untracked.txt"), "new\n");
		const { phone } = await connect(repo);
		const status = await ok(phone, { type: "rc_git_status" });
		expect((status.entries as Data[]).map((entry) => entry.path)).toEqual(
			expect.arrayContaining(["visible.txt", "other.txt"]),
		);
		expect((await ok(phone, { type: "rc_git_diff", path: "visible.txt" })).diff).toContain("+two");
		expect((await ok(phone, { type: "rc_git_diff", path: "other.txt" })).diff).toContain("+two");
		git(repo, "-c", "filter.probe.clean=cat", "-c", "filter.pro.be.process=", "add", "visible.txt", "other.txt");
		await ok(phone, { type: "rc_git_diff", path: "visible.txt", staged: true });
		expect(readdirSync(markers)).toEqual([]);
	});

	it("refuses a folder as the source of a staged rename", async () => {
		const repo = makeRepo();
		mkdirSync(join(repo, "config"));
		const rest = Array.from({ length: 20 }, (_, i) => `SETTING_${i}=${i}\n`).join("");
		writeFileSync(join(repo, "config", ".env"), `TOKEN=old-secret\n${rest}`);
		git(repo, "add", "config");
		git(repo, "commit", "-q", "-m", "config");
		git(repo, "mv", "config/.env", "public.txt");
		const { phone } = await connect(repo);
		const error = await refused(phone, { type: "rc_git_diff", path: "public.txt", origPath: "config", staged: true });
		expect(error).toContain("not a file");
		expect(error).not.toContain("old-secret");
	});

	it("shows only commits, not a blob or tree by its id", async () => {
		const repo = makeRepo();
		const { phone } = await connect(repo);
		for (const object of ["HEAD:.env", "HEAD^{tree}"]) {
			const id = git(repo, "rev-parse", object).trim();
			const error = await refused(phone, { type: "rc_git_show", hash: id });
			expect(error).toContain("is not a commit");
			expect(error).not.toContain("hunter2");
		}
	});

	it("withholds a whole rename when either side is a secret file", async () => {
		const repo = makeRepo();
		mkdirSync(join(repo, "config"));
		// Enough unchanged lines that git detects the rename (50% similar).
		const rest = Array.from({ length: 20 }, (_, i) => `SETTING_${i}=${i}\n`).join("");
		writeFileSync(join(repo, "config", ".env"), `TOKEN=old-secret\n${rest}`);
		git(repo, "add", "config");
		git(repo, "commit", "-q", "-m", "config");
		git(repo, "mv", "config/.env", "config/public.txt");
		writeFileSync(join(repo, "config", "public.txt"), `TOKEN=changed\n${rest}`);
		writeFileSync(join(repo, "visible.txt"), "visible\n");
		git(repo, "add", "-A", "config", "visible.txt");
		git(repo, "commit", "-q", "-m", "rename");
		const { phone } = await connect(repo);
		const shown = await ok(phone, { type: "rc_git_show", hash: git(repo, "rev-parse", "HEAD").trim() });
		expect(shown.withheld).toEqual(["config/.env", "config/public.txt"]);
		expect(shown.diff).toContain("+visible");
		expect(shown.diff).not.toContain("TOKEN");
	});

	it("withholds a file that becomes a folder holding a secret file", async () => {
		const repo = makeRepo();
		writeFileSync(join(repo, "cfg"), "plain\n");
		git(repo, "add", "cfg");
		git(repo, "commit", "-q", "-m", "cfg file");
		git(repo, "rm", "-q", "cfg");
		mkdirSync(join(repo, "cfg"));
		writeFileSync(join(repo, "cfg", ".env"), "TOKEN=nested\n");
		git(repo, "add", "cfg");
		git(repo, "commit", "-q", "-m", "cfg folder");
		const { phone } = await connect(repo);
		const shown = await ok(phone, { type: "rc_git_show", hash: git(repo, "rev-parse", "HEAD").trim() });
		expect(shown.withheld).toEqual(expect.arrayContaining(["cfg", "cfg/.env"]));
		expect(shown.diff).not.toContain("nested");
	});

	it.skipIf(process.platform === "win32")("refuses a symlink that resolves to a secret file", async () => {
		const repo = makeRepo();
		mkdirSync(join(repo, "config"));
		writeFileSync(join(repo, "config", ".env"), "TOKEN=linked\n");
		mkdirSync(join(repo, ".ssh"));
		writeFileSync(join(repo, ".ssh", "config"), "Host x\n");
		symlinkSync(join(repo, "config", ".env"), join(repo, "alias.txt"));
		symlinkSync(join(repo, ".ssh"), join(repo, "keys-link"));
		const { phone } = await connect(repo);
		for (const path of ["alias.txt", "keys-link/config"]) {
			expect(await refused(phone, { type: "rc_read_file", path })).toContain("looks like a secret file");
			expect(await refused(phone, { type: "rc_git_diff", path })).toContain("looks like a secret file");
		}
		const { phone: trusting } = await connect(repo, { readSecretFiles: true });
		expect(await ok(trusting, { type: "rc_read_file", path: "alias.txt" })).toMatchObject({
			content: "TOKEN=linked\n",
		});
	});

	it("pages the history and shows a commit", async () => {
		const repo = makeRepo();
		const { phone } = await connect(repo);
		const first = await ok(phone, { type: "rc_git_log", limit: 1 });
		expect(first.more).toBe(true);
		const [newest] = first.commits as Data[];
		expect(newest).toMatchObject({
			subject: "second",
			body: "with a body",
			author: "Test",
			email: "test@example.com",
		});
		expect(newest.refs).toEqual(["HEAD -> main"]);
		const rest = await ok(phone, { type: "rc_git_log", limit: 10, skip: 1 });
		expect(rest.more).toBe(false);
		const [oldest] = rest.commits as Data[];
		expect(oldest).toMatchObject({ subject: "first", parents: [] });
		expect(newest.parents).toEqual([oldest.hash]);

		const shown = await ok(phone, { type: "rc_git_show", hash: String(oldest.short) });
		expect((shown.commit as Data).hash).toBe(oldest.hash);
		expect(shown.diff).toContain("+++ b/old.txt");
		expect(shown.truncated).toBe(false);
	});

	it("takes only a hex commit hash", async () => {
		const repo = makeRepo();
		const { phone } = await connect(repo);
		for (const hash of ["HEAD", "--output=/tmp/pwned", "main", "abc", "ABCDEF12", 7]) {
			expect(await refused(phone, { type: "rc_git_show", hash })).toContain("hash must be a commit hash");
		}
		expect(await refused(phone, { type: "rc_git_show", hash: "deadbeefdeadbeef" })).toBeTruthy();
	});

	it("matches the secret patterns", () => {
		for (const path of [
			".env",
			".env.local",
			"app/.envrc",
			"id_rsa",
			"home/.ssh/config",
			"a/.git/config",
			"cert.PEM",
			"store.p12",
			".npmrc",
			"auth.json",
			"id_ed25519.pub",
		]) {
			expect(isSecretPath(path), path).toBe(true);
		}
		for (const path of ["src/env.ts", "id_card.tsx", "README.md", "keys.ts", "environment/a.txt", "gitignore"]) {
			expect(isSecretPath(path), path).toBe(false);
		}
	});
});
