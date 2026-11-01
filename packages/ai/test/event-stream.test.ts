import { describe, expect, it } from "vitest";
import { lazyStream } from "../src/api/lazy.ts";
import type { Api, AssistantMessage, AssistantMessageEvent, Model } from "../src/types.ts";
import { AssistantMessageEventStream, EventStream } from "../src/utils/event-stream.ts";

// Regression tests for https://github.com/earendil-works/pi/issues/9055
describe("EventStream", () => {
	it("drains buffered events in order and ignores events pushed after completion", async () => {
		const stream = new EventStream<number, number>(
			(event) => event === 3,
			(event) => event,
		);
		stream.push(1);
		stream.push(2);
		stream.push(3);
		stream.push(4);

		expect(await stream.result()).toBe(3);

		const events: number[] = [];
		for await (const event of stream) {
			events.push(event);
		}
		expect(events).toEqual([1, 2, 3]);
	});

	it("preserves order when events arrive after buffered draining starts", async () => {
		const stream = new EventStream<number, number>(
			() => false,
			(event) => event,
		);
		stream.push(1);
		stream.push(2);

		const iterator = stream[Symbol.asyncIterator]();
		expect(await iterator.next()).toEqual({ value: 1, done: false });

		stream.push(3);
		expect(await iterator.next()).toEqual({ value: 2, done: false });
		expect(await iterator.next()).toEqual({ value: 3, done: false });

		stream.end(3);
		expect(await iterator.next()).toEqual({ value: undefined, done: true });
	});

	it("delivers events to waiting consumers in registration order", async () => {
		const stream = new EventStream<number, number>(
			() => false,
			(event) => event,
		);
		const firstIterator = stream[Symbol.asyncIterator]();
		const secondIterator = stream[Symbol.asyncIterator]();
		const firstEvent = firstIterator.next();
		const secondEvent = secondIterator.next();

		stream.push(1);
		stream.push(2);

		expect(await firstEvent).toEqual({ value: 1, done: false });
		expect(await secondEvent).toEqual({ value: 2, done: false });
	});

	it("drains buffered events after end and resolves the explicit result", async () => {
		const stream = new EventStream<number, string>(
			() => false,
			(event) => String(event),
		);
		stream.push(1);
		stream.push(2);
		stream.end("complete");

		expect(await stream.result()).toBe("complete");

		const events: number[] = [];
		for await (const event of stream) {
			events.push(event);
		}
		expect(events).toEqual([1, 2]);
	});

	it("wakes all waiting consumers when ended without a result", async () => {
		const stream = new EventStream<number, number>(
			() => false,
			(event) => event,
		);
		const firstIterator = stream[Symbol.asyncIterator]();
		const secondIterator = stream[Symbol.asyncIterator]();
		const firstEvent = firstIterator.next();
		const secondEvent = secondIterator.next();

		stream.end();

		expect(await firstEvent).toEqual({ value: undefined, done: true });
		expect(await secondEvent).toEqual({ value: undefined, done: true });
	});
});

function message(timestamp: number, durationMs?: number): AssistantMessage {
	return {
		role: "assistant",
		content: [],
		api: "openai-responses",
		provider: "openai",
		model: "m",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp,
		...(durationMs === undefined ? {} : { durationMs }),
	};
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe("AssistantMessageEventStream timing", () => {
	it("sets durationMs on the final done or error message of a response it saw start", async () => {
		const done = new AssistantMessageEventStream();
		const answer = message(Date.now());
		await sleep(20);
		done.push({ type: "done", reason: "stop", message: answer });
		expect(answer.durationMs).toBeGreaterThanOrEqual(15);
		expect((await done.result()).durationMs).toBe(answer.durationMs);

		const failed = new AssistantMessageEventStream();
		const error = { ...message(Date.now()), stopReason: "error" as const };
		failed.push({ type: "error", reason: "error", error });
		expect(error.durationMs).toBeGreaterThanOrEqual(0);

		const ended = new AssistantMessageEventStream();
		const result = message(Date.now());
		ended.end(result);
		expect(result.durationMs).toBeGreaterThanOrEqual(0);
	});

	it("keeps an existing duration, so a forwarding stream keeps the inner measurement", async () => {
		const outer = new AssistantMessageEventStream();
		await sleep(20);
		const inner = new AssistantMessageEventStream();
		const answer = message(Date.now());
		inner.push({ type: "done", reason: "stop", message: answer });
		const measured = answer.durationMs;
		outer.push({ type: "done", reason: "stop", message: answer });
		expect(answer.durationMs).toBe(measured);
		expect(measured).toBeLessThan(20);

		const preset = message(Date.now(), 1234);
		new AssistantMessageEventStream().push({ type: "done", reason: "stop", message: preset });
		expect(preset.durationMs).toBe(1234);
	});

	it("leaves a message untimed when it started before the stream, such as a fetched deferred result", () => {
		const fetched = message(Date.now() - 60_000);
		new AssistantMessageEventStream().push({ type: "done", reason: "stop", message: fetched });
		expect(fetched.durationMs).toBeUndefined();
	});

	it("does not time a message pushed after the stream completed", () => {
		const stream = new AssistantMessageEventStream();
		stream.push({ type: "done", reason: "stop", message: message(Date.now()) });
		const late = message(Date.now());
		stream.push({ type: "done", reason: "stop", message: late });
		expect(late.durationMs).toBeUndefined();
	});
});

describe("EventStream.forwardTo and lazyStream", () => {
	const model = { id: "m", provider: "p", api: "openai-completions" } as Model<Api>;
	const text = (delta: string): AssistantMessageEvent => ({
		type: "text_delta",
		contentIndex: 0,
		delta,
		partial: message(Date.now()),
	});
	const deltas = (events: AssistantMessageEvent[]) =>
		events.map((event) => (event.type === "text_delta" ? event.delta : event.type));

	it("hands queued events to the sink in order, then each pushed one, and none after completion", async () => {
		const stream = new EventStream<number, number>(
			(event) => event === 3,
			(event) => event,
		);
		stream.push(1);
		const seen: number[] = [];
		stream.forwardTo((event) => seen.push(event));
		expect(seen).toEqual([1]);
		stream.push(2);
		stream.push(3);
		stream.push(4);
		expect(seen).toEqual([1, 2, 3]);
		expect(await stream.result()).toBe(3);
	});

	it("forwards an event stream's events, before and after setup returns it, and ends with its result", async () => {
		const inner = new AssistantMessageEventStream();
		inner.push(text("a"));
		const outer = lazyStream(model, async () => inner);
		const events: AssistantMessageEvent[] = [];
		const consumed = (async () => {
			for await (const event of outer) events.push(event);
		})();
		await sleep(0);
		inner.push(text("b"));
		const answer = message(Date.now());
		inner.push({ type: "done", reason: "stop", message: answer });
		await consumed;
		expect(deltas(events)).toEqual(["a", "b", "done"]);
		expect(await outer.result()).toBe(answer);
	});

	it("ends with the result an event stream ends with, without a final event", async () => {
		const inner = new AssistantMessageEventStream();
		const outer = lazyStream(model, async () => inner);
		const answer = message(Date.now());
		inner.push(text("a"));
		inner.end(answer);
		const events: AssistantMessageEvent[] = [];
		for await (const event of outer) events.push(event);
		expect(deltas(events)).toEqual(["a"]);
		expect(await outer.result()).toBe(answer);
	});

	// What lazyStream() did before forwardTo(): iterate the source, then end with its result once there is one.
	async function iterateAndForward(target: AssistantMessageEventStream, source: AssistantMessageEventStream) {
		for await (const event of source) target.push(event);
		target.end(await source.result());
	}

	const iterating = (inner: AssistantMessageEventStream) => {
		const outer = new AssistantMessageEventStream();
		void iterateAndForward(outer, inner);
		return outer;
	};

	type Ending = (inner: AssistantMessageEventStream, answer: AssistantMessage) => void;
	const endings: Record<string, Ending> = {
		"a final event": (inner, answer) => inner.push({ type: "done", reason: "stop", message: answer }),
		"end(result)": (inner, answer) => inner.end(answer),
		"end() without a result": (inner) => inner.end(),
		"a final event, then more events and end()": (inner, answer) => {
			inner.push({ type: "done", reason: "stop", message: answer });
			inner.push(text("late"));
			inner.end(message(Date.now()));
		},
	};

	/** The events the outer stream gives, whether its iteration and its result settle, and the result. */
	async function observe(wrap: (inner: AssistantMessageEventStream) => AssistantMessageEventStream, ending: Ending) {
		const inner = new AssistantMessageEventStream();
		const answer = message(Date.now());
		inner.push(text("a"));
		const outer = wrap(inner);
		const events: AssistantMessageEvent[] = [];
		let iterated = false;
		void (async () => {
			for await (const event of outer) events.push(event);
			iterated = true;
		})();
		let result: AssistantMessage | undefined;
		void outer.result().then((value) => {
			result = value;
		});
		await sleep(0);
		inner.push(text("b"));
		ending(inner, answer);
		await sleep(10);
		return { events: deltas(events), iterated, result: result === answer ? "answer" : result };
	}

	for (const [name, ending] of Object.entries(endings)) {
		it(`ends the outer stream as iterating the source did, for ${name}`, async () => {
			const forwarded = await observe((inner) => lazyStream(model, async () => inner), ending);
			const iterated = await observe(iterating, ending);
			expect(forwarded).toEqual(iterated);
		});
	}

	it("leaves the outer stream open, as iterating did, when the source ends without a result", async () => {
		const ended = await observe(iterating, endings["end() without a result"]);
		expect(ended).toEqual({ events: ["a", "b"], iterated: false, result: undefined });
	});

	it("still iterates a source that is not an event stream", async () => {
		const answer = message(Date.now());
		async function* source(): AsyncGenerator<AssistantMessageEvent> {
			yield text("a");
			yield { type: "done", reason: "stop", message: answer };
		}
		const outer = lazyStream(model, async () => source());
		const events: AssistantMessageEvent[] = [];
		for await (const event of outer) events.push(event);
		expect(deltas(events)).toEqual(["a", "done"]);
		expect(await outer.result()).toBe(answer);
	});

	// A provider extension can return the API's stream in a Proxy whose iterator changes the events, for example to map
	// tool names it renamed on the wire back. The Proxy passes `instanceof EventStream`, but its events must come from
	// its own iterator, not from the inner stream's forwardTo().
	it("iterates an event stream wrapped in a Proxy that changes its events", async () => {
		const inner = new AssistantMessageEventStream();
		const upper = (event: AssistantMessageEvent): AssistantMessageEvent =>
			event.type === "text_delta" ? { ...event, delta: event.delta.toUpperCase() } : event;
		const wrapped = new Proxy(inner, {
			get(target, key) {
				if (key === Symbol.asyncIterator) {
					return async function* () {
						for await (const event of target) yield upper(event);
					};
				}
				const value = Reflect.get(target, key, target);
				return typeof value === "function" ? value.bind(target) : value;
			},
		});
		const outer = lazyStream(model, async () => wrapped);
		const answer = message(Date.now());
		inner.push(text("a"));
		inner.push(text("b"));
		inner.push({ type: "done", reason: "stop", message: answer });
		const events: AssistantMessageEvent[] = [];
		for await (const event of outer) events.push(event);
		expect(deltas(events)).toEqual(["A", "B", "done"]);
		expect(await outer.result()).toBe(answer);
	});

	it("still forwards a plain event stream with forwardTo()", async () => {
		const inner = new AssistantMessageEventStream();
		let forwarded = 0;
		const forwardTo = inner.forwardTo.bind(inner);
		inner.forwardTo = (sink) => {
			forwarded++;
			forwardTo(sink);
		};
		const outer = lazyStream(model, async () => inner);
		inner.push(text("a"));
		inner.push({ type: "done", reason: "stop", message: message(Date.now()) });
		const events: AssistantMessageEvent[] = [];
		for await (const event of outer) events.push(event);
		expect(deltas(events)).toEqual(["a", "done"]);
		expect(forwarded).toBe(1);
	});
});
