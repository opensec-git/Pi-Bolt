// A real Pi-Bolt remote control host for the iOS protocol tests (ios/PiBoltKit/Tests/PiBoltProtocolTests/LiveHostTests):
// Pi-Bolt's own RemoteControlServer (packages/coding-agent/src/remote-control/server.ts) in front of agent sessions on
// the test suite's faux model, so no provider, key or network is involved. It behaves like the background service
// (`host.service`): it lists its sessions and switches between them.
//
// Run from a checkout after `npm ci --ignore-scripts`:
//   node --import ./ios/fixtures/workspace-hooks.mjs ios/fixtures/rc-host.ts
//
// stdout: one JSON line per event. First {"event":"ready","uri":<pairing link>,"port":<n>,"floodPort":<n>,"silentPort":<n>}:
// the flood port answers the handshake with an oversized frame, the silent port never answers.
// stdin: one command per line:
//   pair       -> {"event":"pair","uri":<a new pairing link>}
//   handoff    -> closes every phone with 1012 and listens again on the same port, as the service does when a
//                 terminal takes over; {"event":"handoff"}
//   devices    -> {"event":"devices","names":[...]}
// The process exits when stdin closes.
//
// Prompts the faux model understands: a prompt containing "tool" gets a `shout` tool call, then a reply that quotes
// its result; "demo" the same call, then a markdown reply with every kind of block; "wait" gets a `wait` tool call
// that runs until it is aborted; "subagent" gets an `Agent` call (below), then a reply that quotes its answer;
// anything else gets thinking and an echo.
//
// Subagents: the `Agent` tool runs a child session the way the subagents extension does (opensec-pi-subagents), so the
// server's `subagent_subscribe` streams it (`features: ["subagent_stream"]`): saved next to the parent's file, naming it
// as `parentSession`, named `general-purpose#<first 8 of the agent id>`, its first prompt "CHILD: list the files". The
// child thinks and calls `shout` after about 1.5 s, then answers "Subagent found two files." after another 1.5 s. The
// call reports progress (`details.activity`) while it runs and ends with `details: { agentId, status: "completed" }`.
//
// Review (`host.review`): every session's cwd is one git repository, `main` with one commit (README.md, src/app.ts),
// then README.md modified, `notes.txt` untracked and a `.env` (a secret: rc_read_file refuses it).
//
// Mobile UI kit (Pi-Bolt 1.1.6): the extension commands `/panel` (a panel view, then an update) and `/sheet` (a sheet
// that closes by itself after 3 s), with the view channel bound as the background service binds it.
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer as createNetServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import {
	type AssistantMessage,
	fauxAssistantMessage,
	fauxText,
	fauxThinking,
	fauxToolCall,
} from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { WebSocketServer } from "ws";
import type { ExtensionAPI } from "../../packages/coding-agent/src/core/extensions/types.ts";
import type { MobileView } from "../../packages/coding-agent/src/core/mobile-ui/types.ts";
import { SessionManager } from "../../packages/coding-agent/src/core/session-manager.ts";
import { CloseCode } from "../../packages/coding-agent/src/remote-control/protocol.ts";
import { RemoteControlServer } from "../../packages/coding-agent/src/remote-control/server.ts";
import { RemoteControlStore } from "../../packages/coding-agent/src/remote-control/store.ts";
import { createHarness, createTestUiContext, type Harness } from "../../packages/coding-agent/test/suite/harness.ts";

function emit(event: Record<string, unknown>): void {
	process.stdout.write(`${JSON.stringify(event)}\n`);
}

function freePort(): Promise<number> {
	return new Promise((resolve, reject) => {
		const server = createNetServer();
		server.on("error", reject);
		server.listen(0, "127.0.0.1", () => {
			const address = server.address();
			const port = typeof address === "object" && address ? address.port : 0;
			server.close(() => resolve(port));
		});
	});
}

const shoutTool: AgentTool = {
	name: "shout",
	label: "Shout",
	description: "Repeats text in capitals",
	parameters: Type.Object({ text: Type.String() }),
	execute: async (_id, params, _signal, onUpdate) => {
		const text = String((params as { text: string }).text);
		onUpdate?.({ content: [{ type: "text", text: "warming up" }], details: {} });
		await new Promise((resolve) => setTimeout(resolve, 150));
		return { content: [{ type: "text", text: text.toUpperCase() }], details: {} };
	},
};

const waitTool: AgentTool = {
	name: "wait",
	label: "Wait",
	description: "Waits until aborted",
	parameters: Type.Object({}),
	execute: async (_id, _params, signal) => {
		await new Promise<void>((resolve) => {
			const timer = setTimeout(resolve, 60_000);
			signal?.addEventListener(
				"abort",
				() => {
					clearTimeout(timer);
					resolve();
				},
				{ once: true },
			);
		});
		return { content: [{ type: "text", text: "waited" }], details: {} };
	},
};

function textOf(content: unknown): string {
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content.map((block) => (block?.type === "text" ? String(block.text) : "")).join("");
}

/** The scripted model: one reply per call, from the conversation so far. */
/** A reply with every block the transcript renders, for screenshots. */
const DEMO_REPLY = [
	"## Fixed the flaky test",
	"",
	"The race was in `connect()`: a **second** attempt could adopt a socket the first one had abandoned.",
	"",
	"- Serialized the attempts behind one lock",
	"- Closed abandoned sockets on every path",
	"- [x] Added a regression test",
	"",
	"```swift",
	"await connectLock.lock()",
	"defer { Task { await connectLock.unlock() } }",
	"```",
	"",
	"| Run | Before | After |",
	"|---|---:|---:|",
	"| CI | 3 of 20 failed | 0 of 50 |",
].join("\n");

function reply(context: { messages: Array<{ role: string; content: unknown }> }): AssistantMessage {
	const last = context.messages[context.messages.length - 1];
	const prompt = textOf([...context.messages].reverse().find((message) => message.role === "user")?.content);
	if (last?.role === "toolResult") {
		if ((last as { toolName?: string }).toolName === "Agent")
			return fauxAssistantMessage(`The subagent said: ${textOf(last.content)}`);
		if (/\bdemo\b/i.test(prompt))
			return fauxAssistantMessage([fauxThinking("The test passes now."), fauxText(DEMO_REPLY)]);
		return fauxAssistantMessage(`The tool said ${textOf(last.content)}.`);
	}
	const text = textOf(last?.content);
	if (/\b(tool|demo)\b/i.test(text))
		return fauxAssistantMessage(fauxToolCall("shout", { text: "hello" }), { stopReason: "toolUse" });
	if (/\bwait\b/i.test(text)) return fauxAssistantMessage(fauxToolCall("wait", {}), { stopReason: "toolUse" });
	if (/\bsubagent\b/i.test(text))
		return fauxAssistantMessage(
			fauxToolCall("Agent", {
				prompt: CHILD_PROMPT,
				description: "List files",
				subagent_type: "general-purpose",
			}),
			{ stopReason: "toolUse" },
		);
	return fauxAssistantMessage([
		fauxThinking("Reading the prompt."),
		fauxText(`Echo: ${text}. Some **bold** and \`code\`.`),
	]);
}

interface FixtureSession {
	path: string;
	harness: Harness;
	updatedAt: string;
}

const dirs: string[] = [];
const sessions: FixtureSession[] = [];
const children: Harness[] = [];

/** The review commands' working tree: one commit, then a modified, an untracked and a secret file. */
function makeRepo(): string {
	const dir = mkdtempSync(join(tmpdir(), "pibolt-ios-repo-"));
	dirs.push(dir);
	const git = (...args: string[]) => execFileSync("git", args, { cwd: dir, stdio: "ignore" });
	git("init", "-q", "-b", "main");
	git("config", "user.name", "Fixture");
	git("config", "user.email", "fixture@example.com");
	git("config", "commit.gpgsign", "false");
	mkdirSync(join(dir, "src"));
	writeFileSync(join(dir, "README.md"), "# Fixture\n\nA repository for the review commands.\n");
	writeFileSync(join(dir, "src", "app.ts"), 'export const greeting = "hello";\n');
	git("add", "README.md", "src/app.ts");
	git("commit", "-q", "-m", "Initial commit", "-m", "README and the app.");
	writeFileSync(join(dir, "README.md"), "# Fixture\n\nA repository for the review commands.\n\nA new line.\n");
	writeFileSync(join(dir, "notes.txt"), "untracked notes\n");
	writeFileSync(join(dir, ".env"), "API_KEY=not-a-real-key\n");
	return dir;
}

const repoDir = makeRepo();
const sessionDir = mkdtempSync(join(tmpdir(), "pibolt-ios-sessions-"));
dirs.push(sessionDir);
const CHILD_PROMPT = "CHILD: list the files";
const CHILD_ANSWER = "Subagent found two files.";

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Runs a child session as the subagents extension does, for the parent [owner]. */
function agentTool(owner: () => Harness | undefined): AgentTool {
	return {
		name: "Agent",
		label: "Agent",
		description: "Runs a subagent",
		parameters: Type.Object({
			prompt: Type.String(),
			description: Type.String(),
			subagent_type: Type.Optional(Type.String()),
		}),
		execute: async (_id, params, _signal, onUpdate) => {
			const { prompt, description } = params as { prompt: string; description: string };
			const agentId = randomBytes(8).toString("hex");
			const sessionManager = SessionManager.create(repoDir, sessionDir, {
				parentSession: owner()?.session.sessionFile,
			});
			const child = await createHarness({
				provider: `faux-child-${children.length + 1}`,
				sessionManager,
				tools: [shoutTool],
			});
			children.push(child);
			child.session.setSessionName(`general-purpose#${agentId.slice(0, 8)}`);
			child.setResponses([
				async () => {
					await sleep(1500);
					return fauxAssistantMessage(
						[fauxThinking("Listing the files."), fauxToolCall("shout", { text: "files" })],
						{
							stopReason: "toolUse",
						},
					);
				},
				async () => {
					await sleep(1500);
					return fauxAssistantMessage(CHILD_ANSWER);
				},
			]);
			let toolUses = 0;
			let turns = 0;
			const progress = (activity: string) =>
				onUpdate?.({
					content: [{ type: "text", text: activity }],
					details: { activity, toolUses, turnCount: turns, description, subagentType: "general-purpose" },
				});
			const unsubscribe = child.session.subscribe((event) => {
				if (event.type === "turn_start") turns++;
				if (event.type === "tool_execution_start") progress("running shout…");
				if (event.type === "tool_execution_end") {
					toolUses++;
					progress("thinking…");
				}
			});
			progress("starting…");
			try {
				await child.session.prompt(prompt);
			} finally {
				unsubscribe();
			}
			return {
				content: [{ type: "text", text: CHILD_ANSWER }],
				details: {
					agentId,
					status: "completed",
					description,
					subagentType: "general-purpose",
					toolUses,
					turnCount: turns,
				},
			};
		},
	};
}

/**
 * The mobile UI kit (docs/mobile-ui.md, Pi-Bolt 1.1.6): `/panel` sets a panel view and updates it, `/sheet` opens a sheet
 * that closes by itself after 3 s. Phones that declare "views" get the views; the iOS app does not (until 0.2.0), so its
 * tests check it ignores what still reaches it (the sheet, broadcast to every phone; `views` in `remote_hello`).
 */
const PANEL: MobileView = {
	v: 1,
	title: "Deploy",
	body: [{ type: "progress", id: "bar", value: 0, label: "Uploading" }],
	actions: [{ type: "button", id: "cancel", label: "Cancel" }],
};
const SHEET: MobileView = {
	v: 1,
	title: "Deploy settings",
	body: [
		{
			type: "form",
			id: "f",
			children: [{ type: "input", id: "branch", label: "Branch", value: "main" }],
			submit: { label: "Deploy" },
		},
	],
};

function viewsExtension(pi: ExtensionAPI): void {
	pi.registerCommand("panel", {
		description: "Show a panel view",
		handler: async (_args, ctx) => {
			ctx.ui.setView("deploy", PANEL);
			ctx.ui.updateView("deploy", [{ op: "set", id: "bar", props: { value: 0.5 } }]);
		},
	});
	pi.registerCommand("sheet", {
		description: "Open a sheet view",
		handler: async (_args, ctx) => {
			await ctx.ui.showView(SHEET, { key: "settings", timeout: 3000 });
		},
	});
}

async function newSession(): Promise<FixtureSession> {
	let harness: Harness | undefined;
	harness = await createHarness({
		tools: [shoutTool, waitTool, agentTool(() => harness)],
		sessionManager: SessionManager.create(repoDir, sessionDir),
		extensionFactories: [viewsExtension],
	});
	harness.setResponses(Array.from({ length: 500 }, () => reply));
	// As the background service binds a session: views to the phones that render them, sheets as their dialogs.
	await harness.session.bindExtensions({
		uiContext: createTestUiContext(),
		mode: "rpc",
		viewChannel: {
			hasNative: () => server.hasViewClients(),
			keepText: false,
			send: (request) => server.sendView(request),
			sheet: (request, signal) => server.requestViewSheet(request, signal),
			sheetChanged: (viewKey, view, rev) => server.updateViewSheet(viewKey, view, rev),
		},
	});
	const session = { path: `fixture://session/${sessions.length + 1}`, harness, updatedAt: new Date().toISOString() };
	sessions.push(session);
	return session;
}

let current = await newSession();
const agentDir = mkdtempSync(join(tmpdir(), "pibolt-ios-fixture-"));
dirs.push(agentDir);
const store = new RemoteControlStore(agentDir);
const port = await freePort();

const server: RemoteControlServer = new RemoteControlServer({
	store,
	getSession: () => current.harness.session,
	hostName: "fixture-host",
	version: "0.0.0-fixture",
	port,
	bindAddress: "127.0.0.1",
	hostInfo: { service: true },
	serviceCommands: {
		types: new Set(["list_projects", "list_sessions", "switch_session", "new_session"]),
		async run(command) {
			const cwd = repoDir;
			switch (command.type) {
				case "list_projects":
					return { projects: [{ cwd, name: "fixture", sessions: sessions.length, updatedAt: current.updatedAt }] };
				case "list_sessions":
					return {
						sessions: sessions.map((s, i) => ({
							path: s.path,
							id: `s${i + 1}`,
							cwd,
							name: `Session ${i + 1}`,
							preview: `preview ${i + 1}`,
							updatedAt: s.updatedAt,
							messageCount: s.harness.session.messages.length,
						})),
					};
				case "switch_session": {
					const target = sessions.find((s) => s.path === command.sessionPath);
					if (!target) throw new Error("No such session");
					current = target;
					setTimeout(() => server.sessionChanged(), 10);
					return { cancelled: false };
				}
				case "new_session":
					current = await newSession();
					setTimeout(() => server.sessionChanged(), 10);
					return { cancelled: false };
				default:
					throw new Error(`Unknown command ${command.type}`);
			}
		},
	},
});
await server.start();
const endpoint = `ws://127.0.0.1:${port}/rc`;
server.setEndpoints([endpoint]);

// A peer that is not the host: it answers the handshake with a frame far larger than any handshake reply.
const flood = new WebSocketServer({ host: "127.0.0.1", port: 0, handleProtocols: () => "pibolt-rc.1" });
flood.on("connection", (socket) => socket.on("message", () => socket.send(Buffer.alloc(200_000))));
await new Promise<void>((resolve) => flood.on("listening", () => resolve()));
const floodAddress = flood.address();
const floodPort = typeof floodAddress === "object" && floodAddress ? floodAddress.port : 0;

// A peer that accepts the WebSocket and never answers the handshake.
const silent = new WebSocketServer({ host: "127.0.0.1", port: 0, handleProtocols: () => "pibolt-rc.1" });
await new Promise<void>((resolve) => silent.on("listening", () => resolve()));
const silentAddress = silent.address();
const silentPort = typeof silentAddress === "object" && silentAddress ? silentAddress.port : 0;

emit({ event: "ready", uri: server.pairingUri([endpoint]), port, floodPort, silentPort });

const input = createInterface({ input: process.stdin });
input.on("line", async (line) => {
	switch (line.trim()) {
		case "pair":
			emit({ event: "pair", uri: server.pairingUri([endpoint]) });
			break;
		case "handoff":
			await server.stop(CloseCode.Handoff);
			await server.start();
			server.setEndpoints([endpoint]);
			emit({ event: "handoff" });
			break;
		case "devices":
			emit({ event: "devices", names: store.devices().map((device) => device.name) });
			break;
	}
});
input.on("close", async () => {
	await server.stop();
	flood.close();
	silent.close();
	for (const s of sessions) s.harness.cleanup();
	for (const child of children) child.cleanup();
	for (const dir of dirs) rmSync(dir, { recursive: true, force: true });
	process.exit(0);
});
