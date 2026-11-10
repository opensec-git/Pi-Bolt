/**
 * MCP mode (`--mcp`): Pi as a Model Context Protocol server (https://modelcontextprotocol.io) on stdin and stdout, so other
 * agents and MCP clients hand Pi a task. The `prompt` tool runs a prompt in a Pi session in a given directory and returns
 * Pi's answer, with progress notifications while it works; a cancelled call stops it. Sessions stay open for later calls,
 * which continue them by id, and they are Pi's session files: `list_sessions` finds the terminal's sessions too, and
 * `pi --session <id>` resumes one started here.
 *
 * Each session is created as the other modes create theirs (the same runtime factory, so settings, extensions, skills,
 * prompt templates, MCP servers and project trust apply as usual). Extensions that ask the user reach the client as
 * elicitation forms when it offers them; otherwise they get no answer, as in print mode.
 */

import { realpathSync, statSync } from "node:fs";
import { isAbsolute, join } from "node:path";
import type { Readable } from "node:stream";
import type { Api, Model } from "@earendil-works/pi-ai";
import { COMMAND_NAME, getDocsPath, PRODUCT_NAME, VERSION } from "../../config.ts";
import type { AgentSession } from "../../core/agent-session.ts";
import {
	type AgentSessionRuntime,
	type CreateAgentSessionRuntimeFactory,
	createAgentSessionRuntime,
} from "../../core/agent-session-runtime.ts";
import { formatNoApiKeyFoundMessage } from "../../core/auth-guidance.ts";
import type { ExtensionUIDialogOptions, ExtensionUIHost } from "../../core/extensions/index.ts";
import { findExactModelReferenceMatch } from "../../core/model-resolver.ts";
import { flushRawStdout, takeOverStdout, writeRawStdout } from "../../core/output-guard.ts";
import { SessionManager } from "../../core/session-manager.ts";
import { SettingsManager } from "../../core/settings-manager.ts";
import { attachJsonlLineReader } from "../../modes/rpc/jsonl.ts";
import { killTrackedDetachedChildren } from "../../utils/shell.ts";
import { resultText, toolCallInfo } from "../acp/translate.ts";
import { clientUIContext, settleWithin } from "../client-ui.ts";
import { errorText, isRecord, JsonRpcConnection, JsonRpcError, JsonRpcErrorCode, type JsonRpcId } from "../json-rpc.ts";
import {
	LIST_SESSIONS_TOOL,
	type ListSessionsArgs,
	type McpToolResult,
	PROMPT_TOOL,
	type PromptArgs,
	TOOLS,
	toolError,
} from "./tools.ts";

/** MCP versions this server speaks, newest first. */
const PROTOCOL_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
/** Sessions kept open for later calls; the one used longest ago closes first (it can be continued from its file). */
const MAX_OPEN_SESSIONS = 8;
const DEFAULT_LIST_LIMIT = 20;
/** Progress messages show this much of Pi's text. */
const PROGRESS_TEXT_CHARS = 200;
/** A command's output goes out as progress at most this often. */
const PROGRESS_OUTPUT_INTERVAL_MS = 1000;
/** With nothing else to report for this long, progress says again what Pi is doing. */
const PROGRESS_HEARTBEAT_MS = 5000;

const INSTRUCTIONS = [
	`${PRODUCT_NAME} is a coding agent. Use \`prompt\` to give it a task in a directory: it reads, edits and runs commands`,
	"there and answers when done. Continue a conversation by passing the `sessionId` of an earlier result.",
].join(" ");

export interface McpModeOptions {
	/** The runtime factory of main.ts, which builds each session as the other modes build theirs. */
	createRuntime: CreateAgentSessionRuntimeFactory;
	agentDir: string;
	/** Session directory from `--session-dir` or the environment; otherwise each directory's setting or default. */
	sessionDir?: string;
	/** `--offline`: no model catalog refresh. */
	offline?: boolean;
	/** The directory of calls that name none. */
	cwd: string;
}

/** Serves MCP on stdin and stdout until the client closes them. */
export async function runMcpMode(options: McpModeOptions): Promise<never> {
	takeOverStdout();
	const server = new McpServer(options, (line) => writeRawStdout(line));
	let shuttingDown = false;
	const shutdown = async (exitCode: number, flush: boolean) => {
		if (shuttingDown) process.exit(exitCode);
		shuttingDown = true;
		detach();
		process.stdin.pause();
		await server.dispose();
		if (flush) await flushRawStdout();
		process.exit(exitCode);
	};
	const signals: NodeJS.Signals[] = process.platform === "win32" ? ["SIGTERM"] : ["SIGTERM", "SIGHUP"];
	for (const signal of signals) {
		process.on(signal, () => {
			killTrackedDetachedChildren();
			void shutdown(signal === "SIGHUP" ? 129 : 143, false);
		});
	}
	const detach = server.attach(process.stdin, () => void shutdown(0, true));
	return new Promise<never>(() => {});
}

/** One MCP connection. Exported for tests, which drive it without stdio. */
export class McpServer {
	readonly connection: JsonRpcConnection;
	private readonly options: McpModeOptions;
	/** Open sessions, the one used most recently last. (Not by id: an extension can change a session's id.) */
	private readonly sessions = new Set<PiSession>();
	/** Sessions being opened from their files, by id. */
	private readonly opening = new Map<string, Promise<PiSession>>();
	/** Running `tools/call` requests, which `notifications/cancelled` stops. */
	private readonly calls = new Map<JsonRpcId, AbortController>();
	private clientCapabilities: Record<string, unknown> = {};
	/** Session creation runs one at a time: extensions load and project trust is decided per session. */
	private creating: Promise<unknown> = Promise.resolve();
	private refreshedModels = false;

	constructor(options: McpModeOptions, write: (line: string) => void) {
		this.options = options;
		this.connection = new JsonRpcConnection({
			write,
			onRequest: (method, params, id) => this.handleRequest(method, params, id),
			onNotification: (method, params) => this.handleNotification(method, params),
		});
	}

	/** Reads the client's messages from `input`; `onEnd` runs when it closes. Returns a function that stops reading. */
	attach(input: Readable, onEnd: () => void): () => void {
		const detachLines = attachJsonlLineReader(input, (line) => this.connection.receive(line));
		input.on("end", onEnd);
		return () => {
			detachLines();
			input.off("end", onEnd);
		};
	}

	/** Whether the client answers elicitation forms (`{}` meant forms before the 2025-11-25 version split them). */
	get elicitsForms(): boolean {
		const elicitation = this.clientCapabilities.elicitation;
		return isRecord(elicitation) && (elicitation.form !== undefined || elicitation.url === undefined);
	}

	async dispose(): Promise<void> {
		for (const call of this.calls.values()) call.abort();
		const sessions = [...this.sessions.values()];
		this.sessions.clear();
		await Promise.allSettled(sessions.map((session) => session.dispose()));
		this.connection.close();
	}

	private async handleRequest(method: string, raw: unknown, id: JsonRpcId): Promise<unknown> {
		const params = isRecord(raw) ? raw : {};
		switch (method) {
			case "initialize":
				return this.initialize(params);
			case "ping":
				return {};
			case "tools/list":
				return { tools: TOOLS };
			case "tools/call":
				return this.callTool(params, id);
			default:
				throw new JsonRpcError(JsonRpcErrorCode.MethodNotFound, `Method not found: ${method}`);
		}
	}

	private handleNotification(method: string, raw: unknown): void {
		if (method !== "notifications/cancelled" || !isRecord(raw)) return;
		const id = raw.requestId;
		if (typeof id !== "string" && typeof id !== "number") return;
		const call = this.calls.get(id);
		if (!call) return;
		this.connection.abandon(id);
		call.abort();
	}

	private initialize(params: Record<string, unknown>) {
		this.clientCapabilities = isRecord(params.capabilities) ? params.capabilities : {};
		const requested = typeof params.protocolVersion === "string" ? params.protocolVersion : "";
		return {
			protocolVersion: PROTOCOL_VERSIONS.includes(requested) ? requested : PROTOCOL_VERSIONS[0],
			capabilities: { tools: { listChanged: false } },
			serverInfo: { name: COMMAND_NAME, title: PRODUCT_NAME, version: VERSION },
			instructions: INSTRUCTIONS,
		};
	}

	private async callTool(params: Record<string, unknown>, id: JsonRpcId): Promise<McpToolResult> {
		const name = params.name;
		const args = isRecord(params.arguments) ? params.arguments : {};
		if (name === LIST_SESSIONS_TOOL.name) return this.listSessions(args as ListSessionsArgs);
		if (name !== PROMPT_TOOL.name) throw new JsonRpcError(JsonRpcErrorCode.InvalidParams, `Unknown tool: ${name}`);
		const meta = isRecord(params._meta) ? params._meta : {};
		const progressToken =
			typeof meta.progressToken === "string" || typeof meta.progressToken === "number"
				? meta.progressToken
				: undefined;
		const controller = new AbortController();
		this.calls.set(id, controller);
		try {
			const prompt = promptArgs(args);
			if (typeof prompt === "string") return toolError(prompt);
			const session = await this.sessionFor(prompt);
			const progress =
				progressToken === undefined
					? undefined
					: (() => {
							let count = 0;
							return (message: string) =>
								this.connection.notify("notifications/progress", {
									progressToken,
									progress: ++count,
									message,
								});
						})();
			return await session.prompt(prompt, controller.signal, progress);
		} catch (error) {
			return toolError(errorText(error));
		} finally {
			this.calls.delete(id);
		}
	}

	/** Session directory for sessions in `cwd`: `--session-dir`, else the user's `sessionDir` setting, else Pi's default. */
	private sessionDirFor(cwd: string): string | undefined {
		return (
			this.options.sessionDir ??
			SettingsManager.create(cwd, this.options.agentDir, { projectTrusted: false }).getSessionDir()
		);
	}

	/** The session a prompt names, opened from its file if it is not open, or a new one. */
	private async sessionFor(args: PromptArgs): Promise<PiSession> {
		const cwd = args.cwd === undefined ? undefined : requireDirectory(args.cwd);
		if (args.sessionId === undefined) {
			const directory = cwd ?? this.options.cwd;
			return this.open(SessionManager.create(directory, this.sessionDirFor(directory)));
		}
		const id = args.sessionId;
		// A call that names a session another call is still opening waits for that one: one runtime per session file.
		const session = this.takeOpen(id) ?? (await (this.opening.get(id) ?? this.openById(id, cwd)));
		if (cwd !== undefined && !sameDirectory(cwd, session.cwd))
			throw new Error(`Session ${id} works in ${session.cwd}, not ${cwd}`);
		return session;
	}

	/**
	 * The open session whose id is `id`, now the most recently used. Matched by the session's current id: an extension can
	 * move an open session to another one, and then the old id names the old session's file again.
	 */
	private takeOpen(id: string): PiSession | undefined {
		for (const session of this.sessions) {
			if (session.id !== id) continue;
			this.sessions.delete(session);
			this.sessions.add(session);
			return session;
		}
		return undefined;
	}

	/** Opens a session from its file, found in `cwd`'s session directory, else among all sessions. */
	private openById(id: string, cwd: string | undefined): Promise<PiSession> {
		const opening = (async () => {
			const directory = cwd ?? this.options.cwd;
			const sessionDir = this.sessionDirFor(directory);
			let path = SessionManager.findById(directory, id, sessionDir);
			// The session directory of the settings holds the sessions of every directory, and the default one too.
			for (const dir of new Set([sessionDir, this.options.sessionDir])) {
				path ??= (await SessionManager.listAll(dir)).find((info) => info.id === id)?.path;
			}
			if (!path) throw new Error(`Session not found: ${id}`);
			return this.open(
				cwd === undefined ? SessionManager.open(path) : SessionManager.open(path, this.sessionDirFor(cwd), cwd),
				true,
			);
		})().finally(() => this.opening.delete(id));
		this.opening.set(id, opening);
		return opening;
	}

	private async open(sessionManager: SessionManager, resumed = false): Promise<PiSession> {
		const create = this.creating.then(async () => {
			await this.closeIdleSessions(MAX_OPEN_SESSIONS - 1);
			const runtime = await createAgentSessionRuntime(this.options.createRuntime, {
				cwd: sessionManager.getCwd(),
				agentDir: this.options.agentDir,
				sessionManager,
				sessionStartEvent: resumed ? { type: "session_start", reason: "resume" } : undefined,
			});
			for (const diagnostic of runtime.diagnostics) {
				console.error(`${diagnostic.type === "error" ? "Error" : "Warning"}: ${diagnostic.message}`);
			}
			// As the other modes exit on them: a session whose extension failed to load, or whose `--provider` is unknown
			// (it would run on another model), is not the session asked for.
			const errors = runtime.diagnostics.filter((diagnostic) => diagnostic.type === "error");
			if (errors.length > 0) {
				await runtime.dispose();
				throw new Error(errors.map((error) => error.message).join("\n"));
			}
			const session = new PiSession(this, runtime);
			await session.bind();
			this.sessions.add(session);
			if (!this.refreshedModels && !this.options.offline) {
				this.refreshedModels = true;
				void runtime.services.modelRuntime.refresh({ signal: AbortSignal.timeout(15_000) }).catch(() => {});
			}
			return session;
		});
		this.creating = create.catch(() => {});
		return create;
	}

	/** Closes the sessions over the limit once they are idle (they were all working when the last one opened). */
	closeExcessSessions(): void {
		const close = this.creating.then(() => this.closeIdleSessions(MAX_OPEN_SESSIONS));
		this.creating = close.catch(() => {});
	}

	/** Closes the sessions used longest ago that are not working, until at most `keep` are open. */
	private async closeIdleSessions(keep: number): Promise<void> {
		for (const session of this.sessions) {
			if (this.sessions.size <= keep) return;
			if (session.busy) continue;
			this.sessions.delete(session);
			await session.dispose();
		}
	}

	private async listSessions(args: ListSessionsArgs): Promise<McpToolResult> {
		let cwd: string;
		try {
			cwd = args.cwd === undefined ? this.options.cwd : requireDirectory(args.cwd);
		} catch (error) {
			return toolError(errorText(error));
		}
		const limit =
			typeof args.limit === "number" && Number.isInteger(args.limit) && args.limit > 0
				? Math.min(args.limit, 100)
				: DEFAULT_LIST_LIMIT;
		// (Sessions of old Pi versions have no directory.)
		const infos = (await SessionManager.list(cwd, this.sessionDirFor(cwd)))
			.filter((info) => info.cwd !== "")
			.slice(0, limit);
		const sessions = infos.map((info) => ({
			sessionId: info.id,
			cwd: info.cwd,
			title: info.name || info.firstMessage.replace(/\s+/g, " ").trim().slice(0, 100),
			updatedAt: info.modified.toISOString(),
		}));
		const text =
			sessions.length === 0
				? `No sessions in ${cwd}.`
				: sessions.map((session) => `${session.sessionId}  ${session.updatedAt}  ${session.title}`).join("\n");
		return { content: [{ type: "text", text }], structuredContent: { sessions } };
	}

	/** Asks the client to fill in a form; undefined when it declines or cannot. */
	async elicit(
		message: string,
		properties: Record<string, Record<string, unknown>>,
		opts: ExtensionUIDialogOptions | undefined,
	): Promise<Record<string, unknown> | undefined> {
		if (opts?.signal?.aborted) return undefined;
		const request = this.connection.request<{ action?: string; content?: unknown }>("elicitation/create", {
			mode: "form",
			message,
			requestedSchema: { type: "object", properties, required: Object.keys(properties) },
		});
		const response = await settleWithin(request, opts);
		return response?.action === "accept" && isRecord(response.content) ? response.content : undefined;
	}
}

/**
 * Progress notifications of one call: what Pi does (each tool, the first line of each message), a command's latest output
 * line at most once a second, and what it is doing again when nothing was sent for a while, so that clients whose timeout
 * progress resets keep waiting through long commands and replies.
 */
class ProgressReport {
	private readonly notify: (message: string) => void;
	private activity = "Working";
	private lastSent = Date.now();
	private lastOutput = 0;
	private readonly timer: ReturnType<typeof setInterval>;

	constructor(notify: (message: string) => void) {
		this.notify = notify;
		this.timer = setInterval(() => {
			if (Date.now() - this.lastSent >= PROGRESS_HEARTBEAT_MS) this.send(this.activity);
		}, 1000);
		this.timer.unref?.();
	}

	/** What Pi is doing, sent with the next heartbeat. */
	doing(activity: string): void {
		this.activity = activity;
	}

	send(message: string): void {
		const text = message.length > PROGRESS_TEXT_CHARS ? `${message.slice(0, PROGRESS_TEXT_CHARS)}…` : message;
		this.activity = text;
		this.lastSent = Date.now();
		this.notify(text);
	}

	output(line: string): void {
		const now = Date.now();
		if (now - this.lastOutput < PROGRESS_OUTPUT_INTERVAL_MS) return;
		this.lastOutput = now;
		const activity = this.activity;
		this.send(line);
		// The heartbeat repeats the command, not one line of its output.
		this.activity = activity;
	}

	stop(): void {
		clearInterval(this.timer);
	}
}

type Usage = {
	inputTokens: number;
	outputTokens: number;
	cachedReadTokens: number;
	cachedWriteTokens: number;
	totalTokens: number;
	cost?: number;
};

/** One Pi session: its runtime, and the prompts it runs one after the other. */
class PiSession {
	private readonly server: McpServer;
	private readonly runtime: AgentSessionRuntime;
	private queue: Promise<unknown> = Promise.resolve();
	private pending = 0;
	private disposed = false;

	constructor(server: McpServer, runtime: AgentSessionRuntime) {
		this.server = server;
		this.runtime = runtime;
	}

	private get session(): AgentSession {
		return this.runtime.session;
	}

	/** The session's id, which can change when an extension starts or switches sessions. */
	get id(): string {
		return this.session.sessionId;
	}

	get cwd(): string {
		return this.runtime.cwd;
	}

	get busy(): boolean {
		return this.pending > 0;
	}

	async bind(): Promise<void> {
		this.runtime.setRebindSession(async () => this.rebind());
		await this.rebind();
	}

	private async rebind(): Promise<void> {
		const session = this.session;
		await session.bindExtensions({
			// Without forms to answer them, dialogs get no answer, as in print mode.
			uiContext: this.server.elicitsForms ? this.createUIContext() : undefined,
			// Extensions see a headless host whose dialogs a client answers, as in RPC mode.
			mode: "rpc",
			commandContextActions: {
				waitForIdle: () => this.session.waitForIdle(),
				newSession: async (options) => this.runtime.newSession(options),
				fork: async (entryId, forkOptions) => {
					const result = await this.runtime.fork(entryId, forkOptions);
					return { cancelled: result.cancelled };
				},
				navigateTree: async (targetId, options) => {
					const result = await this.session.navigateTree(targetId, {
						summarize: options?.summarize,
						customInstructions: options?.customInstructions,
						replaceInstructions: options?.replaceInstructions,
						label: options?.label,
					});
					return { cancelled: result.cancelled };
				},
				switchSession: async (sessionPath, options) => this.runtime.switchSession(sessionPath, options),
				reload: async () => {
					await this.session.reload();
				},
			},
			onError: (err) => console.error(`Extension error (${err.extensionPath}): ${err.error}`),
		});
	}

	async dispose(): Promise<void> {
		if (this.disposed) return;
		this.disposed = true;
		await this.runtime.dispose();
	}

	prompt(args: PromptArgs, signal: AbortSignal, progress?: (message: string) => void): Promise<McpToolResult> {
		this.pending++;
		const run = this.queue.then(() => this.runPrompt(args, signal, progress));
		this.queue = run.catch(() => {});
		return run.finally(() => {
			this.pending--;
			if (this.pending === 0) this.server.closeExcessSessions();
		});
	}

	private async runPrompt(
		args: PromptArgs,
		signal: AbortSignal,
		progress: ((message: string) => void) | undefined,
	): Promise<McpToolResult> {
		if (signal.aborted) return toolError("Cancelled");
		const session = this.session;
		if (args.model !== undefined) {
			const scoped = session.scopedModels;
			const models: Model<Api>[] =
				scoped.length > 0 ? scoped.map((entry) => entry.model) : [...session.modelRuntime.getAvailableSnapshot()];
			const model = findExactModelReferenceMatch(args.model, models);
			if (!model) return toolError(`Unknown model: ${args.model}. Available: ${models.map(modelKey).join(", ")}`);
			await session.setModel(model, { persist: false });
		}
		if (args.thinking !== undefined) {
			const levels = session.getAvailableThinkingLevels();
			const level = levels.find((candidate) => candidate === args.thinking);
			if (!level) return toolError(`Unknown thinking level: ${args.thinking}. Available: ${levels.join(", ")}`);
			session.setThinkingLevel(level, { persist: false });
		}
		if (!session.model) return toolError(`No models available. ${setupHelp()}`);

		let usage: Usage | undefined;
		let last: { text: string; stopReason: string; errorMessage?: string } | undefined;
		const report = progress ? new ProgressReport(progress) : undefined;
		const unsubscribe = session.subscribe((event) => {
			switch (event.type) {
				case "message_start":
					if (event.message.role === "assistant") report?.doing("Thinking");
					break;
				case "message_end": {
					const message = event.message;
					if (message.role !== "assistant") break;
					usage = addUsage(usage, message.usage);
					const text = message.content
						.filter((part) => part.type === "text")
						.map((part) => part.text)
						.join("");
					last = { text, stopReason: message.stopReason, errorMessage: message.errorMessage };
					const line = text.trim().split("\n")[0];
					if (line) report?.send(line);
					break;
				}
				case "tool_execution_start":
					if (!event.parentToolCallId) report?.send(toolCallInfo(event.toolName, event.args, this.cwd).title);
					break;
				case "tool_execution_update":
					// A command's latest output line, while it runs.
					if (!event.parentToolCallId && event.toolName === "bash") {
						const line = resultText(event.partialResult).trimEnd().split("\n").at(-1)?.trim();
						if (line) report?.output(line);
					}
					break;
				case "compaction_start":
					report?.send("Compacting the conversation");
					break;
				case "auto_retry_start":
					report?.send(`Retrying (${event.attempt}/${event.maxAttempts}): ${event.errorMessage}`);
					break;
				default:
					break;
			}
		});
		const abort = () => void session.abort();
		signal.addEventListener("abort", abort, { once: true });
		try {
			await session.prompt(args.prompt, { source: "rpc" });
			await session.waitForIdle();
		} catch (error) {
			if (signal.aborted) return toolError("Cancelled");
			const provider = session.model?.provider;
			if (provider && errorText(error) === formatNoApiKeyFoundMessage(provider)) {
				// Pi's own message points to /login, which the client does not have.
				const problem = provider === "unknown" ? "No model is set up." : `No API key found for ${provider}.`;
				return toolError(`${problem} ${setupHelp()}`);
			}
			return toolError(errorText(error));
		} finally {
			signal.removeEventListener("abort", abort);
			unsubscribe();
			report?.stop();
		}
		const final = last as typeof last;
		const stopReason = signal.aborted
			? "cancelled"
			: final?.stopReason === "error"
				? "error"
				: final?.stopReason === "aborted"
					? "cancelled"
					: final?.stopReason === "length"
						? "max_tokens"
						: "end_turn";
		const total = usage as Usage | undefined;
		const structured = {
			sessionId: this.id,
			text: final?.text ?? "",
			stopReason,
			// (Not for models that report no usage.)
			...(total && total.totalTokens > 0 ? { usage: total } : {}),
		};
		const answer =
			stopReason === "error"
				? final?.errorMessage || "The model request failed"
				: stopReason === "cancelled"
					? "Cancelled"
					: structured.text;
		return {
			content: [
				{ type: "text", text: answer },
				{ type: "text", text: `sessionId: ${this.id}` },
			],
			structuredContent: structured,
			...(stopReason === "error" || stopReason === "cancelled" ? { isError: true } : {}),
		};
	}

	private createUIContext(): ExtensionUIHost {
		const server = this.server;
		return clientUIContext({
			select: async (title, options, opts) => {
				const answer = await server.elicit(title, { choice: { type: "string", title, enum: options } }, opts);
				return typeof answer?.choice === "string" && options.includes(answer.choice) ? answer.choice : undefined;
			},
			confirm: async (title, message, opts) => {
				const answer = await server.elicit(
					message ? `${title}\n\n${message}` : title,
					{ confirm: { type: "boolean", title: "Yes", default: false } },
					opts,
				);
				return answer?.confirm === true;
			},
			input: async (title, placeholder, opts) => {
				const field = { type: "string", title, ...(placeholder ? { description: placeholder } : {}) };
				const answer = await server.elicit(title, { value: field }, opts);
				return typeof answer?.value === "string" ? answer.value : undefined;
			},
			editor: async (title, prefill) => {
				const field = { type: "string", title, ...(prefill ? { default: prefill } : {}) };
				const answer = await server.elicit(title, { value: field }, undefined);
				return typeof answer?.value === "string" ? answer.value : undefined;
			},
			notify: (message, type) =>
				console.error(type === "error" || type === "warning" ? `${type}: ${message}` : message),
		});
	}
}

/** The prompt tool's arguments, or what is wrong with them. */
function promptArgs(args: Record<string, unknown>): PromptArgs | string {
	if (typeof args.prompt !== "string" || args.prompt.trim() === "") return "`prompt` must be a non-empty string";
	for (const key of ["cwd", "sessionId", "model", "thinking"] as const) {
		if (args[key] !== undefined && typeof args[key] !== "string") return `\`${key}\` must be a string`;
	}
	return args as unknown as PromptArgs;
}

function addUsage(
	total: Usage | undefined,
	usage:
		| { input: number; output: number; cacheRead: number; cacheWrite: number; cost?: { total: number } }
		| undefined,
): Usage | undefined {
	if (!usage) return total;
	const input = usage.input + usage.cacheRead + usage.cacheWrite;
	const cost = (total?.cost ?? 0) + (usage.cost?.total ?? 0);
	return {
		inputTokens: (total?.inputTokens ?? 0) + input,
		outputTokens: (total?.outputTokens ?? 0) + usage.output,
		cachedReadTokens: (total?.cachedReadTokens ?? 0) + usage.cacheRead,
		cachedWriteTokens: (total?.cachedWriteTokens ?? 0) + usage.cacheWrite,
		totalTokens: (total?.totalTokens ?? 0) + input + usage.output,
		...(cost > 0 ? { cost } : {}),
	};
}

/** How to set up a model, which MCP mode cannot do itself. */
function setupHelp(): string {
	return [
		`Run \`${COMMAND_NAME}\` in a terminal and use /login, or give the server a provider's API key in its environment`,
		`(\`env\` in the client's MCP server settings). See:\n  ${join(getDocsPath(), "providers.md")}`,
	].join(" ");
}

function modelKey(model: Pick<Model<Api>, "provider" | "id">): string {
	return `${model.provider}/${model.id}`;
}

function sameDirectory(a: string, b: string): boolean {
	try {
		return realpathSync(a) === realpathSync(b);
	} catch {
		return a === b;
	}
}

/** A directory for a call, which must be an existing absolute path. */
function requireDirectory(cwd: string): string {
	let isDirectory = false;
	try {
		isDirectory = isAbsolute(cwd) && statSync(cwd).isDirectory();
	} catch {
		// Reported below.
	}
	if (!isDirectory) throw new Error(`cwd is not an absolute path of a directory: ${cwd}`);
	return cwd;
}
