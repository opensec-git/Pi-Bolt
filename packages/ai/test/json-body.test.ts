import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSimple as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { streamSimple as streamOpenAICompletions } from "../src/api/openai-completions.ts";
import { streamSimple as streamOpenAIResponses } from "../src/api/openai-responses.ts";
import { cleanupSessionResources, clearSessionRequestCaches } from "../src/session-resources.ts";
import type { Api, FetchFunction, Model, TranscriptContext } from "../src/types.ts";
import { cachedArrayToJSON, cacheJsonSerialization } from "../src/utils/json-body.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

const long = (seed: string, length = 5000) => seed.repeat(Math.ceil(length / seed.length)).slice(0, length);

const { rawJSON } = JSON as unknown as { rawJSON: (text: string) => unknown };

class Point {
	x = 1;
	y = 2;
}

function sample(): unknown[] {
	const withProto = JSON.parse('{"__proto__": "kept", "text": "short"}') as unknown;
	return [
		{ role: "system", content: long("You are a helpful assistant. ") },
		{ role: "user", content: long('quotes " backslashes \\ newlines \n tabs \t controls \u0001 ') },
		{ role: "user", content: long("unicode é ü 漢字 → emoji 😀 ") },
		{ role: "user", content: long("lone surrogate \ud800 and \udc00 ") },
		{
			role: "tool",
			content: [
				{ type: "text", text: long("tool output\n") },
				{ type: "image", data: long("QUJD") },
			],
		},
		{ role: "assistant", content: "short", extra: undefined, fn: () => 1, nan: Number.NaN, inf: Infinity, neg: -0 },
		{ nested: { deeper: { list: [long("x"), undefined, null, 1, true, () => 1] } } },
		{ date: new Date(0), point: new Point(), boxed: new String(long("boxed ")), map: new Map([[1, 2]]) },
		{ toJSON: () => long("own toJSON ") },
		withProto,
		Object.assign(Object.create(null), { nullProto: long("null prototype ") }),
		[long("array in array"), [long("deeper array")]],
		// biome-ignore lint/suspicious/noSparseArray: a hole serializes as null
		[1, , 3],
		long("bare string item"),
		{ 2: "integer keys", 1: long("first"), b: "b", a: "a" },
	];
}

describe("cacheJsonSerialization", () => {
	it("keeps the encodings of surviving strings through compaction", () => {
		const items = [long("omitted"), long("retained")];
		cacheJsonSerialization(items, "chat");
		const before = cachedArrayToJSON.call(items) as unknown[];
		clearSessionRequestCaches("chat", () => [items[1]]);
		const after = cachedArrayToJSON.call(items) as unknown[];
		expect(after[0]).not.toBe(before[0]);
		expect(after[1]).toBe(before[1]);
		expect(JSON.stringify(after)).toBe(JSON.stringify([...items]));
	});

	it("releases both generations for the closed session and preserves other sessions' encoded text", async () => {
		const closed = [long("old tool output")];
		const live = [long("live tool output")];
		cacheJsonSerialization(closed, "closed");
		cacheJsonSerialization(live, "live");
		const first = (items: string[]) => (cachedArrayToJSON.call(items) as unknown[])[0];
		const oldValue = first(closed);
		const liveValue = first(live);
		await Promise.resolve();
		const current = [long("latest tool output")];
		cacheJsonSerialization(current, "closed");
		const currentValue = first(current);
		cleanupSessionResources("closed");
		expect(first(closed)).not.toBe(oldValue);
		expect(first(current)).not.toBe(currentValue);
		expect(first(live)).toBe(liveValue);
		const anonymous = [long("anonymous tool output")];
		cacheJsonSerialization(anonymous);
		const anonymousValue = first(anonymous);
		cleanupSessionResources();
		expect(first(live)).not.toBe(liveValue);
		expect(first(anonymous)).not.toBe(anonymousValue);
		expect(JSON.stringify(closed)).toBe(JSON.stringify([...closed]));
	});

	it("serializes byte-identically to JSON.stringify: first seen, then cached, then from the cache", () => {
		const plain = sample();
		const cached = sample();
		cacheJsonSerialization(cached);
		const expected = JSON.stringify({ model: "m", messages: plain, stream: true });
		for (let request = 0; request < 3; request++) {
			expect(JSON.stringify({ model: "m", messages: cached, stream: true })).toBe(expected);
		}
		expect(JSON.stringify(cached, null, 2)).toBe(JSON.stringify(plain, null, 2));
	});

	it("keeps working through the copies SDKs make of the body", () => {
		const messages = sample();
		cacheJsonSerialization(messages);
		const params = { model: "m", messages, betas: ["b"], stream: true };
		const { betas: _betas, ...body } = { ...params };
		expect(JSON.stringify(body)).toBe(JSON.stringify({ model: "m", messages: sample(), stream: true }));
	});

	it("follows changes made to the array and its messages after it was marked", () => {
		const messages = sample();
		cacheJsonSerialization(messages);
		JSON.stringify(messages);
		messages.push({ role: "user", content: long("added later ") });
		(messages[1] as { content: string }).content = long("replaced ");
		const expected = sample();
		expected.push({ role: "user", content: long("added later ") });
		(expected[1] as { content: string }).content = long("replaced ");
		expect(JSON.stringify(messages)).toBe(JSON.stringify(expected));
	});

	it("leaves raw JSON values already in the messages as they are", () => {
		const items = () => [
			rawJSON('"raw item"'),
			{ role: "user", content: rawJSON(JSON.stringify(long("raw content "))) },
			{ role: "tool", content: [{ type: "text", text: long("text ") }, rawJSON("12345678901234567890")] },
		];
		const messages = items();
		cacheJsonSerialization(messages);
		const expected = JSON.stringify(items());
		for (let request = 0; request < 2; request++) {
			expect(JSON.stringify(messages)).toBe(expected);
		}
	});

	it("does nothing to arrays it cannot extend", () => {
		for (const lock of [Object.freeze, Object.seal, Object.preventExtensions]) {
			const messages = lock(sample());
			expect(() => cacheJsonSerialization(messages)).not.toThrow();
			expect(Object.hasOwn(messages, "toJSON")).toBe(false);
			expect(JSON.stringify(messages)).toBe(JSON.stringify(sample()));
		}
	});

	it("serializes the array as it is when building the copy fails", () => {
		let reads = 0;
		const messages: unknown[] = [
			{ role: "user", content: long("before ") },
			{
				role: "user",
				get content() {
					reads++;
					if (reads === 1) throw new Error("first read fails");
					return long("after ");
				},
			},
		];
		cacheJsonSerialization(messages);
		expect(JSON.stringify(messages)).toBe(
			JSON.stringify([
				{ role: "user", content: long("before ") },
				{ role: "user", content: long("after ") },
			]),
		);
	});

	it("leaves the array's enumerable shape alone and does not replace an existing toJSON", () => {
		const messages = [{ role: "user", content: long("a") }];
		cacheJsonSerialization(messages);
		expect(Object.keys(messages)).toEqual(["0"]);
		expect(structuredClone(messages)).toEqual(messages);

		const own = Object.assign([1, 2], { toJSON: () => "own" });
		cacheJsonSerialization(own);
		expect(JSON.stringify(own)).toBe('"own"');

		const notArray = { messages: [] };
		cacheJsonSerialization(notArray);
		expect(Object.getOwnPropertyNames(notArray)).toEqual(["messages"]);
	});
});

const history: TranscriptContext = normalizeContext({
	messages: [
		{ role: "user", content: long("Read the file please. "), timestamp: 1 },
		{
			role: "assistant",
			content: [
				{ type: "text", text: long("Reading it now. ") },
				{ type: "toolCall", id: "call_1", name: "read", arguments: { path: "a.ts" } },
			],
			api: "openai-completions",
			provider: "test-provider",
			model: "test-model",
			usage: {
				input: 0,
				output: 0,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 0,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			},
			stopReason: "toolUse",
			timestamp: 2,
		},
		{
			role: "toolResult",
			toolCallId: "call_1",
			toolName: "read",
			content: [{ type: "text", text: long('export const a = "\\u00e9";\n') }],
			isError: false,
			timestamp: 3,
		},
		{ role: "user", content: long("And now explain it. "), timestamp: 4 },
	],
});

function createModel<TApi extends Api>(api: TApi): Model<TApi> {
	return {
		id: "test-model",
		name: "Test Model",
		api,
		provider: "test-provider",
		baseUrl: "https://upstream.test/v1",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 100_000,
		maxTokens: 1_000,
	};
}

afterEach(() => {
	vi.unstubAllGlobals();
});

describe("request bodies with the serialization cache", () => {
	const adapters: [string, (fetch: FetchFunction, onPayload: (payload: unknown) => unknown) => Promise<unknown>][] = [
		[
			"openai-completions",
			(fetch, onPayload) =>
				streamOpenAICompletions(createModel("openai-completions"), history, {
					apiKey: "k",
					fetch,
					onPayload,
					maxRetries: 0,
				}).result(),
		],
		[
			"openai-responses",
			(fetch, onPayload) =>
				streamOpenAIResponses(createModel("openai-responses"), history, {
					apiKey: "k",
					fetch,
					onPayload,
					maxRetries: 0,
				}).result(),
		],
		[
			"anthropic-messages",
			(fetch, onPayload) =>
				streamAnthropic(createModel("anthropic-messages"), history, {
					apiKey: "k",
					fetch,
					onPayload,
					maxRetries: 0,
				}).result(),
		],
	];

	for (const [name, run] of adapters) {
		it(`sends what JSON.stringify makes of the payload (${name})`, async () => {
			for (let request = 0; request < 3; request++) {
				let expected: string | undefined;
				let sent: unknown;
				let items: unknown;
				const fetch = vi.fn<FetchFunction>(async (_url, init) => {
					sent = init?.body;
					return new Response(JSON.stringify({ error: { message: "rejected" } }), {
						status: 400,
						headers: { "content-type": "application/json" },
					});
				});
				await run(fetch, (payload) => {
					// The SDKs send these as headers, not in the body.
					const { betas: _betas, ...body } = payload as Record<string, unknown>;
					expected = JSON.stringify(body);
					items = body.messages ?? body.input;
					return undefined;
				});
				expect(fetch).toHaveBeenCalledOnce();
				expect(sent).toBe(expected);
				expect(Object.hasOwn(items as object, "toJSON")).toBe(true);
			}
		});

		it(`sends a payload whose messages onPayload froze (${name})`, async () => {
			let expected: string | undefined;
			let sent: unknown;
			const fetch = vi.fn<FetchFunction>(async (_url, init) => {
				sent = init?.body;
				return new Response(JSON.stringify({ error: { message: "rejected" } }), {
					status: 400,
					headers: { "content-type": "application/json" },
				});
			});
			await run(fetch, (payload) => {
				const replaced = { ...(payload as Record<string, unknown>) };
				const key = "messages" in replaced ? "messages" : "input";
				replaced[key] = Object.freeze([...(replaced[key] as unknown[])]);
				const { betas: _betas, ...body } = replaced;
				expected = JSON.stringify(body);
				return replaced;
			});
			expect(fetch).toHaveBeenCalledOnce();
			expect(sent).toBe(expected);
		});
	}
});
