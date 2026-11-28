// hunt-core: ACP `session/load` opens one runtime per session (finding hunt-core-1).
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { ENV_AGENT_DIR } from "../src/config.ts";

const cliPath = resolve(__dirname, "../src/cli.ts");
const sourceResolverUrl = pathToFileURL(resolve(__dirname, "../src/experimental/source-resolver.ts")).href;

type Message = { jsonrpc: "2.0"; id?: number; method?: string; params?: unknown; result?: unknown; error?: unknown };

describe("ACP session/load", () => {
	let root: string;
	let agentDir: string;
	let projectDir: string;
	let sessionDir: string;
	let logPath: string;
	let extensionPath: string;
	const sessionId = "0197aaaa-bbbb-7ccc-8ddd-eeeeeeeeeeee";

	beforeAll(() => {
		root = mkdtempSync(join(tmpdir(), "hunt-core-acp-"));
		agentDir = join(root, "agent");
		projectDir = join(root, "project");
		sessionDir = join(root, "sessions");
		logPath = join(root, "lifecycle.log");
		extensionPath = join(root, "lifecycle.ts");
		for (const dir of [agentDir, projectDir, sessionDir]) mkdirSync(dir, { recursive: true });
		writeFileSync(join(agentDir, "settings.json"), JSON.stringify({ lastChangelogVersion: "9999.0.0" }));
		writeFileSync(join(agentDir, "auth.json"), "{}");
		const header = {
			type: "session",
			version: 3,
			id: sessionId,
			timestamp: "2026-01-01T00:00:00.000Z",
			cwd: projectDir,
		};
		const user = {
			type: "message",
			id: "a1b2c3d4",
			parentId: null,
			timestamp: "2026-01-01T00:00:01.000Z",
			message: { role: "user", content: "hello", timestamp: 1767225601000 },
		};
		writeFileSync(
			join(sessionDir, `2026-01-01T00-00-00-000Z_${sessionId}.jsonl`),
			`${JSON.stringify(header)}\n${JSON.stringify(user)}\n`,
		);
		writeFileSync(
			extensionPath,
			`import { appendFileSync } from "node:fs";
export default function (pi) {
	pi.on("session_start", (event) => appendFileSync(${JSON.stringify(logPath)}, "start " + event.reason + "\\n"));
	pi.on("session_shutdown", () => appendFileSync(${JSON.stringify(logPath)}, "shutdown\\n"));
}
`,
		);
	});

	afterAll(() => {
		rmSync(root, { recursive: true, force: true });
	});

	it("opens a session that two concurrent loads name only once, and closes every runtime it opened", async () => {
		const child = spawn(
			process.execPath,
			["--import", sourceResolverUrl, cliPath, "--acp", "--session-dir", sessionDir, "-e", extensionPath],
			{
				cwd: projectDir,
				env: { ...process.env, [ENV_AGENT_DIR]: agentDir, PI_OFFLINE: "1" },
				stdio: ["pipe", "pipe", "pipe"],
			},
		);
		let stderr = "";
		child.stderr.on("data", (chunk) => {
			stderr += chunk.toString();
		});
		const pending = new Map<number, (message: Message) => void>();
		let buffer = "";
		child.stdout.setEncoding("utf8");
		child.stdout.on("data", (chunk: string) => {
			buffer += chunk;
			for (let index = buffer.indexOf("\n"); index !== -1; index = buffer.indexOf("\n")) {
				const line = buffer.slice(0, index);
				buffer = buffer.slice(index + 1);
				if (!line.trim()) continue;
				const message = JSON.parse(line) as Message;
				if (message.id !== undefined && !message.method) pending.get(message.id)?.(message);
			}
		});
		let nextId = 1;
		const call = (method: string, params: unknown) =>
			new Promise<Message>((resolvePromise) => {
				const id = nextId++;
				pending.set(id, resolvePromise);
				child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
			});
		await call("initialize", { protocolVersion: 1, clientCapabilities: {} });
		// An editor that reconnects (or two panels) loads the same session twice before the first load answered.
		const loads = await Promise.all([
			call("session/load", { sessionId, cwd: projectDir, mcpServers: [] }),
			call("session/load", { sessionId, cwd: projectDir, mcpServers: [] }),
		]);
		expect(loads.map((load) => load.error)).toEqual([undefined, undefined]);
		child.stdin.end();
		const code = await new Promise<number | null>((resolvePromise) => child.once("exit", resolvePromise));
		expect(code, stderr).toBe(0);
		const lines = existsSync(logPath) ? readFileSync(logPath, "utf8").trim().split("\n") : [];
		const starts = lines.filter((line) => line.startsWith("start")).length;
		const shutdowns = lines.filter((line) => line === "shutdown").length;
		// Every runtime that was started is shut down when the editor goes away.
		expect(shutdowns).toBe(starts);
		// And one session file is driven by one runtime.
		expect(starts).toBe(1);
	}, 60_000);
});
