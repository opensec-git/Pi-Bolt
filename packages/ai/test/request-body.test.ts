import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSimple as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { streamSimple as streamOpenAICompletions } from "../src/api/openai-completions.ts";
import { streamSimple as streamOpenAIResponses } from "../src/api/openai-responses.ts";
import type { Api, FetchFunction, Message, Model, TranscriptContext } from "../src/types.ts";
import { cacheJsonSerialization } from "../src/utils/json-body.ts";
import { jsonBodyParts, RUNTIME_FETCH_WRAPPER } from "../src/utils/request-body.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

const long = (seed: string, length = 3000) => seed.repeat(Math.ceil(length / seed.length)).slice(0, length);

class Point {
	x = 1;
	y = 2;
}

/** Items JSON.stringify treats in every special way it has; none of them may change the bytes. */
function awkward(): unknown[] {
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
		{ nested: { deeper: { list: [long("x"), undefined, null, 1, true, () => 1, Symbol("s")] } } },
		{ date: new Date(0), point: new Point(), boxed: new String("boxed"), map: new Map([[1, 2]]) },
		JSON.parse('{"__proto__": "kept", "text": "short"}'),
		Object.assign(Object.create(null), { nullProto: long("null prototype ") }),
		{ raw: (JSON as unknown as { rawJSON: (text: string) => unknown }).rawJSON('"raw"') },
		[long("array in array"), [long("deeper array")]],
		// biome-ignore lint/suspicious/noSparseArray: a hole serializes as null
		[1, , 3],
		long("bare string item"),
		42,
		null,
		undefined,
		{ 2: "integer keys", 1: long("first"), b: "b", a: "a" },
	];
}

/** The text of jsonBodyParts(...), or undefined. */
async function encode(
	body: Record<string, unknown>,
	key: string,
	conversation: string,
	allowedToJSON?: unknown,
): Promise<string | undefined> {
	const parts = jsonBodyParts(body, key, conversation, allowedToJSON);
	return parts && new Blob(parts).text();
}

async function expectSame(body: Record<string, unknown>, key: string, conversation = "c"): Promise<void> {
	const expected = JSON.stringify(body);
	expect(await encode(body, key, conversation)).toBe(expected);
}

afterEach(() => {
	vi.restoreAllMocks();
	vi.unstubAllGlobals();
});

describe("jsonBodyParts", () => {
	it("encodes JSON.stringify of the body as UTF-8, first serialized and then reused", async () => {
		const messages = awkward();
		for (let request = 0; request < 3; request++) {
			await expectSame({ model: "m", messages: [...messages], stream: true, tools: [{ name: "é" }] }, "messages");
		}
	});

	it("shares large tool text across call IDs while preserving every byte on edits and reordering", async () => {
		const text = long('line\n "\ud800 é', 12000);
		const first = { role: "tool", content: text, tool_call_id: "first" };
		const second = { role: "tool", content: text, tool_call_id: 'id with "quotes"' };
		const body = { messages: [first, second] };
		const parts = jsonBodyParts(body, "messages", "repeated-tool")!;
		expect(await new Blob(parts).text()).toBe(JSON.stringify(body));
		const payloads = parts.filter((p) => p.size > 4096);
		expect(payloads).toHaveLength(2);
		expect(payloads[0]).toBe(payloads[1]);
		first.content += "changed";
		body.messages.reverse();
		await expectSame(body, "messages", "repeated-tool");
		await expectSame(
			{
				messages: [
					{ ...second, name: "read" },
					{ content: text, role: "tool", tool_call_id: "other" },
				],
			},
			"messages",
		);
	});

	it("reuses matching byte ranges and sends exact JSON across edits, insertions and eviction", async () => {
		const original = `${"a".repeat(4094)}🪴${long('text "漢\\\n\ud800', 24000)}`;
		const tool = (content: string, id: string) => ({ role: "tool", content, tool_call_id: id });
		const messages = [tool(original, "base")];
		const first = jsonBodyParts({ messages }, "messages", "edited-tool")!;
		const baseBytes = first.find((part) => part.size > 16384)!;
		const slice = vi.spyOn(baseBytes, "slice");
		const position = 13001;
		for (const edited of [
			`x${original.slice(1)}`,
			`${original.slice(0, position)}追加${original.slice(position)}`,
			original.slice(0, position) + original.slice(position + 731),
			`${original.slice(0, -6)}🪴`,
		]) {
			slice.mockClear();
			messages.push(tool(edited, 'quoted "id"'));
			await expectSame({ messages }, "messages", "edited-tool");
			expect(slice).toHaveBeenCalled();
		}
		for (let i = 0; i < 10; i++) messages.push(tool(String.fromCharCode(65 + i).repeat(24000), String(i)));
		messages.push(tool("\u0000".repeat(40000), "escaped"), tool(original, "revisit"));
		await expectSame({ messages }, "messages", "edited-tool");
		messages.reverse();
		await expectSame({ messages }, "messages", "edited-tool");
	});

	it("preserves exact JSON across variable-width header edits and split surrogate pairs", async () => {
		const rest = long('漢字\n"\\\ud800', 23000);
		for (const boundary of ["x", "\ud800", "\udc00", "🪴"]) {
			const prefix = `${"a".repeat(255)}\ud800`;
			const suffix = boundary + rest;
			const messages = [{ role: "tool", content: prefix + suffix, tool_call_id: "read" }];
			const conversation = `header-edit-${boundary}`;
			await expectSame({ messages }, "messages", conversation);
			for (const head of ["", "short💡", `\u0000"\\\n${prefix}`, `${prefix}x`, `${prefix}\udc02`]) {
				messages[0]!.content = head + suffix;
				await expectSame({ messages }, "messages", conversation);
			}
			messages[0]!.content = `${prefix}${boundary}changed${rest.slice(7)}`;
			await expectSame({ messages }, "messages", conversation);
		}
	});

	it("encodes large and edited tool results when the runtime has no global Buffer", async () => {
		const message = { role: "tool", content: long('λ "\n', 32000), tool_call_id: "id" };
		const body = { messages: [message, { ...message, content: `${message.content}次` }] };
		vi.stubGlobal("Buffer", undefined);
		const parts = jsonBodyParts(body, "messages", "browser-tools");
		vi.unstubAllGlobals();
		expect(await new Blob(parts).text()).toBe(JSON.stringify(body));
	});

	it("serializes only the items that are new or changed", async () => {
		const messages: Record<string, unknown>[] = [];
		for (let i = 0; i < 20; i++) messages.push({ role: "user", content: [{ type: "text", text: long(`m${i} `) }] });
		await expectSame({ model: "m", messages: [...messages] }, "messages", "counted");
		const stringify = vi.spyOn(JSON, "stringify");
		messages.push({ role: "assistant", content: "new" });
		await expectSame({ model: "m", messages: [...messages] }, "messages", "counted");
		// Only the new message is serialized on its own.
		const itemsSerialized = stringify.mock.calls.filter(([value]) => value === messages[20]).length;
		expect(itemsSerialized).toBe(1);
		expect(stringify.mock.calls.filter(([value]) => messages.slice(0, 20).includes(value as never)).length).toBe(0);
	});

	it("serializes again what changed in place, was replaced, or moved", async () => {
		const messages: Record<string, unknown>[] = [];
		const body = () => ({ model: "m", messages: [...messages], stream: true });
		for (let i = 0; i < 10; i++) {
			messages.push({ role: i % 2 ? "assistant" : "user", content: [{ type: "text", text: long(`turn ${i} `) }] });
		}
		await expectSame(body(), "messages");
		// A cache marker added to an earlier message's block, in place.
		const block = (messages[3].content as Record<string, unknown>[])[0];
		block.cache_control = { type: "ephemeral" };
		await expectSame(body(), "messages");
		// ...and removed again, the next message marked instead.
		delete block.cache_control;
		(messages[4].content as Record<string, unknown>[])[0].cache_control = { type: "ephemeral" };
		await expectSame(body(), "messages");
		// The same keys in another order, a string changed by one character, a number by sign.
		messages[1] = { content: messages[1].content, role: messages[1].role };
		(messages[2].content as Record<string, unknown>[])[0].text = `${long("turn 2 ").slice(0, -1)}!`;
		messages[5].n = 0;
		await expectSame(body(), "messages");
		messages[5].n = -0;
		await expectSame(body(), "messages");
		// Compaction: earlier messages replaced by a summary, everything after moves.
		messages.splice(0, 6, { role: "user", content: "summary" });
		await expectSame(body(), "messages");
		// An item that becomes a class instance or gets an inherited toJSON, a nested Date.
		messages[1] = Object.assign(new Point(), messages[1]);
		await expectSame(body(), "messages");
		(messages[2].content as Record<string, unknown>[])[0].when = new Date(5);
		await expectSame(body(), "messages");
		await expectSame(body(), "messages");
		// Fields added or removed, undefined values, an array grown.
		messages[0].extra = undefined;
		await expectSame(body(), "messages");
		messages[0].extra = [1, 2];
		await expectSame(body(), "messages");
		(messages[0].extra as number[]).push(3);
		await expectSame(body(), "messages");
		delete messages[0].extra;
		await expectSame(body(), "messages");
		// The rest of the body changes.
		await expectSame({ ...body(), stream: false, tools: [{ name: "t" }] }, "messages");
		await expectSame({ tools: [], messages: [...messages] }, "messages");
	});

	it("stays byte-identical through random edits of a growing conversation", async () => {
		let seed = 7;
		const random = (n: number) => {
			seed = (seed * 1103515245 + 12345) % 2147483648;
			return seed % n;
		};
		const pool = awkward();
		const messages: unknown[] = [];
		for (let request = 0; request < 300; request++) {
			const action = random(10);
			if (action < 5 || messages.length === 0) {
				messages.push(structuredClone({ role: "user", content: [{ type: "text", text: long(`r${request} é `) }] }));
			} else if (action === 5) {
				messages.push(pool[random(pool.length)]);
			} else if (action === 6) {
				const target = messages[random(messages.length)];
				if (
					typeof target === "object" &&
					target !== null &&
					!Array.isArray(target) &&
					Object.isExtensible(target)
				) {
					(target as Record<string, unknown>).marker = random(3) === 0 ? undefined : { n: random(5) };
				}
			} else if (action === 7) {
				messages.splice(random(messages.length), random(3));
			} else if (action === 8) {
				messages[random(messages.length)] = { role: "assistant", content: `x${random(4)}` };
			} else {
				messages.reverse();
			}
			await expectSame({ model: "m", messages: [...messages], n: random(3) }, "messages", `fuzz${random(2)}`);
		}
	});

	it("keeps conversations apart", async () => {
		const a = [{ role: "user", content: long("a ") }];
		const b = [{ role: "user", content: long("b ") }];
		for (let request = 0; request < 5; request++) {
			a.push({ role: "user", content: `a${request}` });
			b.push({ role: "user", content: `b${request}` });
			await expectSame({ messages: [...a] }, "messages", "a");
			await expectSame({ messages: [...b] }, "messages", "b");
			// The same key used by both: still correct, only not reused.
			await expectSame({ messages: [...a] }, "messages", "shared");
			await expectSame({ messages: [...b] }, "messages", "shared");
		}
	});

	it("leaves to JSON.stringify what it cannot reproduce exactly", async () => {
		const items = [{ role: "user", content: "x" }];
		// An item's own toJSON gets its index as the key.
		expect(await encode({ messages: [{ toJSON: (key: string) => key }] }, "messages", "c")).toBeUndefined();
		expect(await encode({ messages: [new Date(0)] }, "messages", "c")).toBeUndefined();
		const callable = Object.assign(() => 0, { toJSON: (key: string) => key });
		expect(await encode({ messages: [null, callable] }, "messages", "c")).toBeUndefined();
		// A toJSON on the array or the body, an array that is not plain, no array.
		const withToJSON = Object.assign([...items], { toJSON: () => [] });
		expect(await encode({ messages: withToJSON }, "messages", "c")).toBeUndefined();
		expect(await encode({ messages: items, toJSON: () => ({}) }, "messages", "c")).toBeUndefined();
		class Items extends Array {}
		expect(await encode({ messages: Items.from(items) }, "messages", "c")).toBeUndefined();
		expect(await encode({ messages: "x" }, "messages", "c")).toBeUndefined();
		expect(
			await encode(
				Object.assign(new Point(), { messages: items }) as unknown as Record<string, unknown>,
				"messages",
				"c",
			),
		).toBeUndefined();
		// A BigInt makes JSON.stringify throw: the SDK's own serialization throws it.
		await expect(encode({ messages: [{ n: 1n }] }, "messages", "c")).rejects.toThrow(TypeError);
	});

	it("reads the array by index, as JSON.stringify does, never through an iterator of its own", async () => {
		let iterated = 0;
		const withIterator = <T>(items: T[]): T[] =>
			Object.defineProperty(items, Symbol.iterator, {
				value: () => {
					iterated++;
					// Skips everything, and changes the array: neither may affect the body.
					items.push({ role: "user", content: "added by the iterator" } as T);
					return [][Symbol.iterator]();
				},
			});
		// An item with its own toJSON that the iterator would hide: still left to JSON.stringify.
		expect(
			await encode(
				{ messages: withIterator([{ role: "user" }, { toJSON: (key: string) => key }]) },
				"messages",
				"c",
			),
		).toBeUndefined();
		const plain = withIterator([
			{ role: "user", content: long("a ") },
			{ role: "assistant", content: "b" },
		]);
		for (let request = 0; request < 2; request++) {
			await expectSame({ model: "m", messages: plain }, "messages", "iterator");
		}
		expect(iterated).toBe(0);
		expect(plain).toHaveLength(2);
	});

	it("accepts the array toJSON of json-body.ts, which serializes the array as it is", async () => {
		const items = awkward();
		cacheJsonSerialization(items);
		const toJSON = (items as unknown as { toJSON: unknown }).toJSON;
		expect(await encode({ messages: items }, "messages", "c")).toBeUndefined();
		expect(await encode({ messages: items }, "messages", "c", toJSON)).toBe(JSON.stringify({ messages: items }));
	});
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

function turn(i: number): Message[] {
	return [
		{ role: "user", content: long(`Question ${i}: read the file. `), timestamp: i * 3 },
		{
			role: "assistant",
			content: [
				{ type: "text", text: long(`Reading ${i}. `) },
				{ type: "toolCall", id: `call_${i}`, name: "read", arguments: { path: `${i}.ts` } },
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
			timestamp: i * 3 + 1,
		},
		{
			role: "toolResult",
			toolCallId: `call_${i}`,
			toolName: "read",
			content: [{ type: "text", text: long(`export const v${i} = "\\u00e9";\n`) }],
			isError: false,
			timestamp: i * 3 + 2,
		},
	];
}

/**
 * A fetch the body encoding takes for the runtime's own (Bun's): a bound function, which prints as native code, named
 * "fetch". Any other fetch gets the SDK's string (json-body.test.ts).
 */
function runtimeLikeFetch(spy: FetchFunction): FetchFunction {
	return Object.defineProperty(spy.bind(undefined), "name", { value: "fetch" });
}

type Run = (
	context: TranscriptContext,
	fetch: FetchFunction,
	onPayload: (payload: unknown) => unknown,
) => Promise<unknown>;

describe("request bodies sent as bytes", () => {
	const options = { apiKey: "k", maxRetries: 0, sessionId: "session-1" };
	const adapters: [string, Run][] = [
		[
			"openai-completions",
			(context, fetch, onPayload) =>
				streamOpenAICompletions(createModel("openai-completions"), context, {
					...options,
					fetch,
					onPayload,
				}).result(),
		],
		[
			"openai-responses",
			(context, fetch, onPayload) =>
				streamOpenAIResponses(createModel("openai-responses"), context, { ...options, fetch, onPayload }).result(),
		],
		[
			"anthropic-messages",
			(context, fetch, onPayload) =>
				streamAnthropic(createModel("anthropic-messages"), context, { ...options, fetch, onPayload }).result(),
		],
	];

	it("leaves the body to the SDK for a fetch that is not the runtime's own", async () => {
		let sent: unknown;
		const fetch = vi.fn<FetchFunction>(async (_url, init) => {
			sent = init?.body;
			return new Response("{}", { status: 400 });
		});
		await adapters[0][1](normalizeContext({ messages: turn(0) }), fetch, () => undefined);
		expect(typeof sent).toBe("string");
	});

	// The HTTP idle timeout fetch Pi installs on Bun (#206) wraps the runtime's fetch and names it.
	it("sends bytes through a wrapper that names the runtime's fetch, and the string through one that names another", async () => {
		const sent: unknown[] = [];
		const inner = vi.fn<FetchFunction>(async (_url, init) => {
			sent.push(init?.body);
			return new Response("{}", { status: 400 });
		});
		const wrap = (wrapped: FetchFunction): FetchFunction => {
			const wrapper: FetchFunction = (input, init) => wrapped(input, init);
			return Object.defineProperty(wrapper, RUNTIME_FETCH_WRAPPER, { value: wrapped });
		};
		const context = normalizeContext({ messages: turn(0) });
		await adapters[0][1](context, wrap(runtimeLikeFetch(inner)), () => undefined);
		await adapters[0][1](context, wrap(inner), () => undefined);
		// A wrapper that names itself is not the runtime's fetch either.
		const self: FetchFunction = (input, init) => inner(input, init);
		await adapters[0][1](
			context,
			Object.defineProperty(self, RUNTIME_FETCH_WRAPPER, { value: self }),
			() => undefined,
		);
		expect(sent[0]).toBeInstanceOf(Blob);
		expect(await (sent[0] as Blob).text()).toBe(sent[1]);
		expect(typeof sent[1]).toBe("string");
		expect(typeof sent[2]).toBe("string");
	});

	for (const [name, run] of adapters) {
		it(`sends JSON.stringify of the payload as a conversation grows and changes (${name})`, async () => {
			const messages: Message[] = [];
			for (let request = 0; request < 8; request++) {
				messages.push(...turn(request));
				// Compaction halfway: the first turns replaced by a summary.
				if (request === 4)
					messages.splice(0, 9, { role: "user", content: "Summary of earlier turns.", timestamp: 0 });
				let expected: string | undefined;
				let sent: unknown;
				let contentType: string | null = null;
				const spy = vi.fn<FetchFunction>(async (_url, init) => {
					sent = init?.body;
					contentType = new Headers(init?.headers).get("content-type");
					return new Response(JSON.stringify({ error: { message: "rejected" } }), {
						status: 400,
						headers: { "content-type": "application/json" },
					});
				});
				await run(normalizeContext({ messages: [...messages] }), runtimeLikeFetch(spy), (payload) => {
					const { betas: _betas, ...body } = payload as Record<string, unknown>;
					expected = JSON.stringify(body);
					return undefined;
				});
				expect(spy).toHaveBeenCalledOnce();
				expect(sent).toBeInstanceOf(Blob);
				expect(await (sent as Blob).text()).toBe(expected);
				expect(contentType).toBe("application/json");
			}
		});
	}
});
