import { afterEach, describe, expect, it, vi } from "vitest";
import { stream as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { stream as streamGoogle } from "../src/api/google-generative-ai.ts";
import { stream as streamCompletions } from "../src/api/openai-completions.ts";
import { stream as streamResponses } from "../src/api/openai-responses.ts";
import type {
	Api,
	AssistantMessage,
	FetchFunction,
	ImageContent,
	Message,
	Model,
	TextContent,
	Tool,
	ToolResultMessage,
	TranscriptContext,
} from "../src/types.ts";
import { requestCacheStats, setRequestCacheEnabled } from "../src/utils/request-cache.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

/**
 * Differential test of request-cache.ts: random conversations (tool calls, images, thinking, errors, aborted replies,
 * orphan calls, renamed tool call ids, system messages, compaction, branch switches and switching back, messages
 * replaced by edited copies, duplicate message objects, a reply sent while it streams and then completed in place,
 * model, compat, thinking and cache retention switches, payload hooks that change the payload in place or replace it,
 * two conversations, overlapping requests). Every request is sent twice: by the cached path with the conversation's
 * own message objects, and without the cache with a copy of the same context. The bodies must be byte-identical.
 */

type Rng = () => number;

function mulberry32(seed: number): Rng {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) >>> 0;
		let t = a;
		t = Math.imul(t ^ (t >>> 15), t | 1);
		t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

const pick = <T>(rng: Rng, items: readonly T[]): T => items[Math.floor(rng() * items.length)];
const chance = (rng: Rng, p: number) => rng() < p;

const TEXTS = [
	"",
	"   ",
	"plain text",
	"unicode é ü 漢字 → \u{1F600}",
	"lone surrogate \ud800 here",
	'quotes " and \\ backslashes \n newlines',
	"x".repeat(1500),
	"long é ".repeat(400),
];

function text(rng: Rng, n: number): string {
	return chance(rng, 0.3) ? pick(rng, TEXTS) : `t${n} ${pick(rng, TEXTS).slice(0, 40)} ${n}`;
}

function image(rng: Rng): ImageContent {
	return {
		type: "image",
		data: `iVBORw0KGgo${Math.floor(rng() * 1000)}`,
		mimeType: pick(rng, ["image/png", "image/jpeg"]),
	};
}

interface Sender {
	api: Api;
	provider: string;
	model: string;
}

const TOOLS: Tool[] = [
	{
		name: "read",
		description: "Read a file",
		parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] },
	},
	{
		name: "bash",
		description: "Run a command",
		parameters: { type: "object", properties: { command: { type: "string" } }, required: ["command"] },
	},
];

function model<TApi extends Api>(
	api: TApi,
	id: string,
	provider: string,
	extra: Partial<Model<TApi>> = {},
): Model<TApi> {
	return {
		id,
		name: id,
		api,
		provider,
		baseUrl: api === "google-generative-ai" ? "https://google.test/v1beta" : "https://upstream.test/v1",
		reasoning: false,
		input: ["text", "image"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 1_000_000,
		maxTokens: 1_000,
		...extra,
	};
}

interface Adapter {
	name: string;
	models: Model<Api>[];
	/** Sends the request; resolves once it was sent (the fake upstream rejects it). */
	send(model: Model<Api>, context: TranscriptContext, options: SendOptions): Promise<unknown>;
	signature(rng: Rng, sender: Sender): string | undefined;
}

interface SendOptions {
	sessionId: string;
	fetch: FetchFunction;
	onPayload: ((payload: unknown) => unknown) | undefined;
	cacheRetention: "none" | "short" | "long";
	reasoning: "low" | "high" | undefined;
}

const common = (o: SendOptions) => ({
	apiKey: "k",
	maxRetries: 0,
	sessionId: o.sessionId,
	// The conversation's messages are never changed in place (only replaced by edited copies): the cache may be used.
	immutableMessages: true,
	fetch: o.fetch,
	onPayload: o.onPayload,
	cacheRetention: o.cacheRetention,
});

const ADAPTERS: Adapter[] = [
	{
		name: "openai-completions",
		models: [
			model("openai-completions", "m1", "p1"),
			model("openai-completions", "m1", "p1", { input: ["text"] }),
			model("openai-completions", "m2", "p1", { reasoning: true, compat: { supportsDeveloperRole: true } }),
			model("openai-completions", "m1", "p1", { compat: { cacheControlFormat: "anthropic" } }),
			model("openai-completions", "m3", "p2", {
				compat: {
					requiresAssistantAfterToolResult: true,
					requiresToolResultName: true,
					requiresThinkingAsText: true,
				},
			}),
			model("openai-completions", "m1", "openai"),
			model("openai-completions", "m1", "p1", { compat: { supportsMidConvoSystemMessages: true } }),
		] as Model<Api>[],
		send: (m, context, o) =>
			streamCompletions(m as Model<"openai-completions">, context, {
				...common(o),
				reasoningEffort: o.reasoning,
			}).result(),
		signature: (rng) =>
			pick(rng, ["reasoning_content", "reasoning", '[{"type":"reasoning.text","text":"r"}]', undefined]),
	},
	{
		name: "anthropic-messages",
		models: [
			model("anthropic-messages", "claude-a", "anthropic"),
			model("anthropic-messages", "claude-a", "anthropic", { input: ["text"] }),
			model("anthropic-messages", "claude-b", "anthropic", { compat: { allowEmptySignature: true } }),
			model("anthropic-messages", "claude-a", "anthropic", {
				compat: { supportsMidConvoSystemMessages: true, supportsMidConvoToolChanges: true },
			}),
			model("anthropic-messages", "claude-c", "anthropic", {
				reasoning: true,
				compat: { supportsMidConvoEffort: true },
			}),
		] as Model<Api>[],
		send: (m, context, o) =>
			streamAnthropic(m as Model<"anthropic-messages">, context, {
				...common(o),
				...(o.reasoning ? { thinkingEnabled: true, effort: o.reasoning } : {}),
			}).result(),
		signature: (rng) => pick(rng, ["sig-abc", "", "  ", undefined]),
	},
	{
		name: "openai-responses",
		models: [
			model("openai-responses", "gpt-a", "openai"),
			model("openai-responses", "gpt-a", "openai", { input: ["text"] }),
			model("openai-responses", "gpt-b", "openai", { reasoning: true, compat: { supportsMidConvoEffort: true } }),
			model("openai-responses", "gpt-a", "other", { compat: { supportsMidConvoSystemMessages: true } }),
		] as Model<Api>[],
		send: (m, context, o) =>
			streamResponses(m as Model<"openai-responses">, context, {
				...common(o),
				reasoningEffort: o.reasoning,
			}).result(),
		signature: (rng, sender) =>
			sender.api === "openai-responses"
				? pick(rng, ['{"type":"reasoning","id":"rs_1","summary":[]}', undefined])
				: pick(rng, ["sig", undefined]),
	},
	{
		name: "google-generative-ai",
		models: [
			model("google-generative-ai", "gemini-2.5-pro", "google"),
			model("google-generative-ai", "gemini-3-pro", "google"),
			model("google-generative-ai", "gemini-3-pro", "google", { input: ["text"] }),
			model("google-generative-ai", "claude-x", "google"),
		] as Model<Api>[],
		send: (m, context, o) => streamGoogle(m as Model<"google-generative-ai">, context, common(o)).result(),
		signature: (rng) => pick(rng, ["c2lnbmF0dXJl", "", undefined]),
	},
];

const ERROR_RESPONSE = () =>
	new Response(JSON.stringify({ error: { message: "rejected", type: "invalid_request_error" } }), {
		status: 400,
		headers: { "content-type": "application/json" },
	});

/** A fetch request-body.ts takes for the runtime's own (a bound function named "fetch"), recording the bodies. */
function recordingFetch(bodies: string[]): FetchFunction {
	const spy = async (_url: unknown, init?: RequestInit) => {
		const body = init?.body;
		// Yield first, so that overlapping requests interleave.
		await Promise.resolve();
		bodies.push(body instanceof Blob ? await body.text() : String(body));
		return ERROR_RESPONSE();
	};
	return Object.defineProperty(spy.bind(undefined), "name", { value: "fetch" }) as FetchFunction;
}

class Conversation {
	readonly rng: Rng;
	readonly adapter: Adapter;
	messages: Message[] = [];
	n = 0;
	/** Branches left, to switch back to: the messages as they were. */
	branches: Message[][] = [];
	pendingCalls: { id: string; name: string }[] = [];
	/** A reply still streaming (stop reason "pending"), completed by the next step. */
	pending: AssistantMessage | undefined;

	constructor(rng: Rng, adapter: Adapter) {
		this.rng = rng;
		this.adapter = adapter;
	}

	sender(): Sender {
		const rng = this.rng;
		if (chance(rng, 0.6)) {
			const m = pick(rng, this.adapter.models);
			return { api: m.api, provider: m.provider, model: m.id };
		}
		const other = pick(rng, ADAPTERS);
		const m = pick(rng, other.models);
		return { api: m.api, provider: m.provider, model: m.id };
	}

	user(): Message {
		const rng = this.rng;
		const n = this.n++;
		if (chance(rng, 0.3)) return { role: "user", content: text(rng, n), timestamp: n };
		const content: (TextContent | ImageContent)[] = [];
		const blocks = 1 + Math.floor(rng() * 3);
		for (let i = 0; i < blocks; i++)
			content.push(chance(rng, 0.2) ? image(rng) : { type: "text", text: text(rng, n) });
		return { role: "user", content, timestamp: n };
	}

	assistant(): AssistantMessage {
		const rng = this.rng;
		const n = this.n++;
		const sender = this.sender();
		const content: AssistantMessage["content"] = [];
		if (chance(rng, 0.6)) {
			content.push(
				chance(rng, 0.1)
					? { type: "thinking", thinking: "", thinkingSignature: "redacted-payload", redacted: true }
					: { type: "thinking", thinking: text(rng, n), thinkingSignature: this.adapter.signature(rng, sender) },
			);
		}
		if (chance(rng, 0.8)) {
			content.push({
				type: "text",
				text: text(rng, n),
				...(chance(rng, 0.3)
					? {
							textSignature: pick(rng, [
								'{"v":1,"id":"msg_x1"}',
								"msg_plain",
								`{"v":1,"id":"msg_${"y".repeat(80)}"}`,
							]),
						}
					: {}),
			});
		}
		const calls = chance(rng, 0.6) ? 1 + Math.floor(rng() * 3) : 0;
		this.pendingCalls = [];
		for (let i = 0; i < calls; i++) {
			const id = pick(rng, [
				`call_${n}_${i}`,
				`call_${n}_${i}|fc_${n}${i}`,
				`call:${n}/${i}|item+${n}=`,
				`toolu_${n}${i}`,
			]);
			const name = pick(rng, ["read", "bash"]);
			this.pendingCalls.push({ id, name });
			content.push({
				type: "toolCall",
				id,
				name,
				arguments: chance(rng, 0.5) ? { path: `f${n}.ts` } : { command: `echo ${text(rng, n).slice(0, 20)}`, n },
				...(chance(rng, 0.2) ? { thoughtSignature: "dGhvdWdodA==" } : {}),
			});
		}
		const stopReason = pick(rng, ["toolUse", "stop", "stop", "error", "aborted", "length"] as const);
		return {
			role: "assistant",
			content,
			api: sender.api,
			provider: sender.provider,
			model: sender.model,
			...(chance(rng, 0.2) ? { providerThinkingLevel: pick(rng, ["low", "high"]) } : {}),
			usage: {
				input: 10,
				output: 5,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 15,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			},
			stopReason: calls > 0 && stopReason === "stop" ? "toolUse" : stopReason,
			timestamp: n,
		} as AssistantMessage;
	}

	toolResults(): ToolResultMessage[] {
		const rng = this.rng;
		const out: ToolResultMessage[] = [];
		for (const call of this.pendingCalls) {
			// Some calls stay unanswered (orphans): transformMessages answers them.
			if (chance(rng, 0.15)) continue;
			const n = this.n++;
			const content: (TextContent | ImageContent)[] = [];
			if (chance(rng, 0.85)) content.push({ type: "text", text: text(rng, n) });
			if (chance(rng, 0.2)) content.push(image(rng));
			if (chance(rng, 0.1)) content.push({ type: "text", text: text(rng, n) });
			out.push({
				role: "toolResult",
				toolCallId: chance(rng, 0.05) ? `unknown_${n}` : call.id,
				toolName: call.name,
				content,
				isError: chance(rng, 0.2),
				...(chance(rng, 0.3) ? { details: { diff: text(rng, n) } } : {}),
				timestamp: n,
			} as ToolResultMessage);
		}
		this.pendingCalls = [];
		return out;
	}

	system(): Message {
		const rng = this.rng;
		const n = this.n++;
		return {
			role: "system",
			content: chance(rng, 0.5) ? `System update ${n}` : "",
			...(chance(rng, 0.4) ? { toolsAdded: [pick(rng, TOOLS)] } : {}),
			...(chance(rng, 0.2) ? { toolsRemoved: [{ name: pick(rng, ["read", "bash"]) }] } : {}),
			timestamp: n,
		} as Message;
	}

	/** One step of the conversation before a request. */
	step(): void {
		const rng = this.rng;
		// A reply streaming in may be sent again while it streams (a cache-warming replay), then completes.
		if (this.pending && chance(rng, 0.5)) return;
		if (this.pending) {
			// The streamed reply completes: changed in place, as the agent loop's partial message is.
			const reply = this.pending as unknown as Record<string, unknown>;
			(reply.content as unknown[]).push({ type: "text", text: `completed ${this.n++}` });
			reply.stopReason = "stop";
			this.pending = undefined;
		}
		const r = rng();
		if (r < 0.06 && this.messages.length > 6) {
			// Compaction: the first messages replaced by a summary (new objects).
			const callers = this.messages.flatMap((message, i) =>
				message.role === "assistant" && message.content.some((block) => block.type === "toolCall") ? [i] : [],
			);
			const keep =
				callers.length > 0 && chance(rng, 0.5)
					? this.messages.length - 1 - pick(rng, callers)
					: Math.floor(rng() * this.messages.length * 0.5);
			this.messages = [
				{ role: "user", content: `Summary ${this.n++}`, timestamp: this.n },
				...this.messages.slice(this.messages.length - keep),
			];
		} else if (r < 0.12 && this.messages.length > 4) {
			// A branch: back to an earlier point, the left branch kept to switch back to.
			this.branches.push(this.messages);
			this.messages = this.messages.slice(0, Math.floor(rng() * this.messages.length));
		} else if (r < 0.16 && this.branches.length > 0) {
			// Back to a branch left before: its message objects again.
			this.messages = this.branches.splice(Math.floor(rng() * this.branches.length), 1)[0];
		} else if (r < 0.26) {
			// A reply streaming in: sent while pending, then changed in place as it completes.
			const reply = this.assistant();
			(reply as { stopReason: string }).stopReason = "pending";
			this.pending = reply;
			this.messages.push(reply);
			return;
		} else if (r < 0.29 && this.messages.length > 0) {
			// The same message object twice.
			this.messages.push(pick(rng, this.messages));
		} else if (r < 0.33) {
			this.messages.push(this.system());
		} else if (r < 0.39 && this.messages.length > 0) {
			// A message replaced by an edited copy (a new object), as an extension's context edit makes.
			const assistants = this.messages.flatMap((message, i) => (message.role === "assistant" ? [i] : []));
			const index =
				assistants.length > 0 && chance(rng, 0.5)
					? pick(rng, assistants)
					: Math.floor(rng() * this.messages.length);
			const copy = structuredClone(this.messages[index]) as unknown as Record<string, unknown>;
			const content = copy.content;
			if (copy.role === "assistant" && chance(rng, 0.6)) {
				// Another sender, or its tool calls renamed: the tool results after it convert differently.
				if (chance(rng, 0.5)) Object.assign(copy, this.sender());
				for (const block of content as Record<string, unknown>[]) {
					if (block.type === "toolCall" && chance(rng, 0.5)) block.id = `${block.id}|fc_r${this.n++}`;
				}
			} else if (Array.isArray(content) && content.length > 0) {
				const block = content[content.length - 1] as Record<string, unknown>;
				if (typeof block.text === "string") block.text = `${block.text} edited`;
				else content.push({ type: "text", text: `edited ${this.n++}` });
			} else if (typeof content === "string") {
				copy.content = `${content} edited`;
			}
			this.messages[index] = copy as unknown as Message;
		}
		if (chance(rng, 0.25)) this.messages.push(this.user());
		const turns = 1 + Math.floor(rng() * 3);
		for (let i = 0; i < turns; i++) {
			this.messages.push(this.assistant());
			this.messages.push(...this.toolResults());
		}
		if (chance(rng, 0.3)) this.messages.push(this.user());
	}
}

type HookMode = "none" | "in-place" | "replace";

/** A before_provider_request hook: the same function of the payload and request number on both paths. */
function hook(mode: HookMode, request: number): ((payload: unknown) => unknown) | undefined {
	if (mode === "none") return undefined;
	return (payload) => {
		const body = payload as Record<string, unknown>;
		const items = (body.messages ?? body.input ?? body.contents) as Record<string, unknown>[] | undefined;
		if (!Array.isArray(items) || items.length === 0) return undefined;
		if (mode === "replace") {
			const copy = JSON.parse(JSON.stringify(body)) as Record<string, unknown>;
			const copied = (copy.messages ?? copy.input ?? copy.contents) as Record<string, unknown>[];
			copied[request % copied.length].hooked = request;
			return copy;
		}
		const item = items[(request * 7) % items.length];
		item.hooked = request;
		const content = item.content ?? item.parts;
		if (Array.isArray(content) && content.length > 0 && typeof content[0] === "object") {
			(content[0] as Record<string, unknown>).hookedPart = request;
		}
		return undefined;
	};
}

async function sendBoth(
	adapter: Adapter,
	m: Model<Api>,
	context: TranscriptContext,
	options: Omit<SendOptions, "fetch">,
	/** A second request of the conversation sent at the same time, with its own hook (cache warming). */
	overlapping: Omit<SendOptions, "fetch"> | undefined,
): Promise<{ expected: string[]; actual: string[] }> {
	const expected: string[] = [];
	setRequestCacheEnabled(false);
	await sendWith(adapter, m, structuredClone(context), options, expected);
	if (overlapping) await sendWith(adapter, m, structuredClone(context), overlapping, expected);
	setRequestCacheEnabled(true);
	const actual: string[] = [];
	if (overlapping) {
		const first: string[] = [];
		const second: string[] = [];
		await Promise.all([
			sendWith(adapter, m, context, options, first),
			sendWith(adapter, m, context, overlapping, second),
		]);
		actual.push(...first, ...second);
	} else {
		await sendWith(adapter, m, context, options, actual);
	}
	return { expected, actual };
}

async function sendWith(
	adapter: Adapter,
	m: Model<Api>,
	context: TranscriptContext,
	options: Omit<SendOptions, "fetch">,
	bodies: string[],
): Promise<void> {
	const fetch = recordingFetch(bodies);
	if (adapter.name === "google-generative-ai") {
		// The Google SDK uses the global fetch.
		vi.stubGlobal("fetch", fetch);
	}
	await adapter.send(m, context, { ...options, fetch });
	if (adapter.name === "google-generative-ai") vi.unstubAllGlobals();
}

const SEEDS = 12;
const REQUESTS = 60;

afterEach(() => {
	setRequestCacheEnabled(true);
	vi.unstubAllGlobals();
});

describe("request cache: cached and uncached request bodies are byte-identical", () => {
	for (const adapter of ADAPTERS) {
		it(`random conversations (${adapter.name})`, async () => {
			const before = requestCacheStats();
			let requests = 0;
			for (let seed = 1; seed <= SEEDS; seed++) {
				const rng = mulberry32(seed * 7919 + ADAPTERS.indexOf(adapter) * 104729);
				const conversations = [new Conversation(rng, adapter), new Conversation(rng, adapter)];
				let m = pick(rng, adapter.models);
				let hookMode: HookMode = "none";
				let cacheRetention: SendOptions["cacheRetention"] = "short";
				let reasoning: SendOptions["reasoning"];
				for (let request = 0; request < REQUESTS; request++) {
					if (chance(rng, 0.08)) m = pick(rng, adapter.models);
					if (chance(rng, 0.1)) hookMode = pick(rng, ["none", "none", "in-place", "replace"] as const);
					if (chance(rng, 0.05)) cacheRetention = pick(rng, ["none", "short", "long"] as const);
					if (chance(rng, 0.08)) reasoning = pick(rng, ["low", "high", undefined] as const);
					const which = chance(rng, 0.15) ? 1 : 0;
					const conversation = conversations[which];
					conversation.step();
					const context = normalizeContext({
						systemPrompt: "You are a test.",
						tools: TOOLS,
						messages: [...conversation.messages],
					});
					const options = {
						sessionId: `session-${seed}-${which}`,
						onPayload: hook(hookMode, request),
						cacheRetention,
						reasoning,
					};
					const overlapping = chance(rng, 0.08)
						? { ...options, onPayload: hook(pick(rng, ["none", "none", "in-place"] as const), request + 1) }
						: undefined;
					const { expected, actual } = await sendBoth(adapter, m, context, options, overlapping);
					requests++;
					if (process.env.W3_DEBUG && request % 15 === 0)
						console.log(
							adapter.name,
							seed,
							request,
							expected.length,
							expected.map((b) => b.length),
							conversation.messages.length,
						);
					expect(actual.length, `seed ${seed} request ${request}`).toBe(expected.length);
					for (let i = 0; i < expected.length; i++) {
						if (actual[i] !== expected[i]) {
							expect.fail(
								`${adapter.name}, seed ${seed}, request ${request}: body differs\n${firstDifference(expected[i], actual[i])}`,
							);
						}
					}
				}
			}
			const after = requestCacheStats();
			// Not vacuous: most units were reused.
			if (process.env.W3_DEBUG)
				console.log(adapter.name, requests, after.units - before.units, after.derived - before.derived);
			expect(requests).toBe(SEEDS * REQUESTS);
			expect(after.units - before.units).toBeGreaterThan(requests * 3);
		}, 120_000);
	}
});

function firstDifference(expected: string, actual: string): string {
	let i = 0;
	while (i < expected.length && expected[i] === actual[i]) i++;
	return `at ${i}:\nexpected …${expected.slice(Math.max(0, i - 120), i + 120)}\nactual   …${actual.slice(Math.max(0, i - 120), i + 120)}`;
}

describe("request cache: what a reused unit depends on besides its messages", () => {
	for (const adapter of ADAPTERS) {
		it(`a tool result converts again when the call it answers is renamed or removed (${adapter.name})`, async () => {
			// Every model of the adapter that sends tool call ids, against a call from another API whose id it renames.
			for (const m of adapter.models) {
				const foreign: AssistantMessage = {
					role: "assistant",
					content: [{ type: "toolCall", id: "call_1|fc+1", name: "read", arguments: { path: "a.ts" } }],
					api: "openai-codex-responses",
					provider: "elsewhere",
					model: "other",
					usage: {
						input: 1,
						output: 1,
						cacheRead: 0,
						cacheWrite: 0,
						totalTokens: 2,
						cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
					},
					stopReason: "toolUse",
					timestamp: 1,
				};
				const result: ToolResultMessage = {
					role: "toolResult",
					toolCallId: "call_1|fc+1",
					toolName: "read",
					content: [{ type: "text", text: "contents" }],
					isError: false,
					timestamp: 2,
				};
				const question: Message = { role: "user", content: "read a.ts", timestamp: 0 };
				const variants: Message[][] = [
					[question, foreign, result],
					[question, foreign, result],
					[question, foreign, result],
					// The call now from the model itself: not renamed.
					[question, { ...foreign, api: m.api, provider: m.provider, model: m.id }, result],
					[question, foreign, result],
					// The call compacted away: the result keeps its id.
					[{ role: "user", content: "summary", timestamp: 3 }, result],
					[question, foreign, result],
				];
				for (const [request, messages] of variants.entries()) {
					const context = normalizeContext({ systemPrompt: "You are a test.", tools: TOOLS, messages });
					const options = {
						sessionId: `renamed-${m.id}-${m.provider}`,
						onPayload: undefined,
						cacheRetention: "short" as const,
						reasoning: undefined,
					};
					const { expected, actual } = await sendBoth(adapter, m, context, options, undefined);
					expect(actual, `${m.id} request ${request}`).toEqual(expected);
				}
			}
		});
	}
});

describe("request cache: a function response merged into an earlier content", () => {
	it("leaves the earlier content as it was converted (google-generative-ai)", async () => {
		const adapter = ADAPTERS.find((candidate) => candidate.name === "google-generative-ai")!;
		const m = adapter.models[1];
		const usage = {
			input: 1,
			output: 1,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 2,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		};
		const reply = (content: AssistantMessage["content"], stopReason: AssistantMessage["stopReason"]) =>
			({
				role: "assistant",
				content,
				api: m.api,
				provider: m.provider,
				model: m.id,
				usage,
				stopReason,
				timestamp: 1,
			}) as AssistantMessage;
		const result = (id: string): ToolResultMessage => ({
			role: "toolResult",
			toolCallId: id,
			toolName: "read",
			content: [{ type: "text", text: `result ${id}` }],
			isError: false,
			timestamp: 2,
		});
		// A reply that converts to nothing between two tool results: the second is merged into the first's content.
		const messages: Message[] = [
			{ role: "user", content: "go", timestamp: 0 },
			reply([{ type: "toolCall", id: "a", name: "read", arguments: { path: "a" } }], "toolUse"),
			result("a"),
			reply([{ type: "text", text: "  " }], "stop"),
			result("b"),
		];
		for (let request = 0; request < 4; request++) {
			const context = normalizeContext({ systemPrompt: "You are a test.", tools: TOOLS, messages });
			const options = {
				sessionId: "merge",
				onPayload: undefined,
				cacheRetention: "short" as const,
				reasoning: undefined,
			};
			const { expected, actual } = await sendBoth(adapter, m, context, options, undefined);
			expect(actual, `request ${request}`).toEqual(expected);
		}
	});
});

describe("request cache: off unless the caller says its messages are never changed in place", () => {
	it("sends a message edited in place as edited (openai-completions, no immutableMessages)", async () => {
		const m = ADAPTERS[0].models[0] as Model<"openai-completions">;
		const question: Message = { role: "user", content: "original", timestamp: 0 };
		const send = async (immutableMessages: boolean | undefined): Promise<string> => {
			const bodies: string[] = [];
			const context = normalizeContext({ systemPrompt: "You are a test.", messages: [question] });
			await streamCompletions(m, context, {
				apiKey: "k",
				maxRetries: 0,
				sessionId: `edited-${immutableMessages}`,
				immutableMessages,
				fetch: recordingFetch(bodies),
			}).result();
			return bodies[0];
		};
		// Enough requests for the cache to keep and reuse the message's conversion, had it been on.
		for (let request = 0; request < 3; request++) expect(await send(undefined)).toContain('"content":"original"');
		const before = requestCacheStats().units;
		(question as { content: string }).content = "edited";
		const body = await send(undefined);
		expect(body).toContain('"content":"edited"');
		expect(body).not.toContain("original");
		expect(requestCacheStats().units).toBe(before);
	});

	it("reuses conversions only for a caller that opts in", async () => {
		const m = ADAPTERS[0].models[0] as Model<"openai-completions">;
		const question: Message = { role: "user", content: "kept", timestamp: 0 };
		const before = requestCacheStats().units;
		for (let request = 0; request < 3; request++) {
			const bodies: string[] = [];
			await streamCompletions(m, normalizeContext({ messages: [question] }), {
				apiKey: "k",
				maxRetries: 0,
				sessionId: "opted-in",
				immutableMessages: true,
				fetch: recordingFetch(bodies),
			}).result();
			expect(bodies[0]).toContain('"content":"kept"');
		}
		expect(requestCacheStats().units).toBeGreaterThan(before);
	});
});
