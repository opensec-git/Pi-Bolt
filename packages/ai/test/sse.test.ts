import { describe, expect, it } from "vitest";
import {
	createSseDecoder,
	decodeSseText,
	iterateSseMessages,
	type ServerSentEvent,
	type SseDecoder,
} from "../src/utils/sse.ts";

// The Anthropic provider's server-sent event parser moved to utils/sse.ts. Below is the parser as it was in
// anthropic-messages.ts; the moved one must give the same events for the same bytes, however they are split into reads.

interface SseDecoderState {
	event: string | null;
	data: string[];
	raw: string[];
}

function flushSseEvent(state: SseDecoderState): ServerSentEvent | null {
	if (!state.event && state.data.length === 0) {
		return null;
	}

	const event: ServerSentEvent = {
		event: state.event,
		data: state.data.join("\n"),
		// Handed over rather than copied: the state gets a new array below.
		raw: state.raw,
	};
	state.event = null;
	state.data = [];
	state.raw = [];
	return event;
}

function decodeSseLine(line: string, state: SseDecoderState): ServerSentEvent | null {
	if (line === "") {
		return flushSseEvent(state);
	}

	state.raw.push(line);
	if (line.startsWith(":")) {
		return null;
	}

	const delimiterIndex = line.indexOf(":");
	const fieldName = delimiterIndex === -1 ? line : line.slice(0, delimiterIndex);
	let value = delimiterIndex === -1 ? "" : line.slice(delimiterIndex + 1);
	if (value.startsWith(" ")) {
		value = value.slice(1);
	}

	if (fieldName === "event") {
		state.event = value;
	} else if (fieldName === "data") {
		state.data.push(value);
	}

	return null;
}

/**
 * Decodes the complete lines in `buffer` and returns what follows the last one. Lines end at "\r\n", "\r" or "\n".
 * The next "\r" and "\n" are each searched for once per occurrence: searching the rest of the buffer for both after
 * every line, and slicing that rest off, made decoding a read quadratic in the number of events it carried. A "\r" at
 * the end of `buffer` waits for the next read unless `final`, since it may be the start of a "\r\n".
 */
function* decodeSseLines(buffer: string, state: SseDecoderState, final: boolean): Generator<ServerSentEvent, string> {
	let lineStart = 0;
	let carriageReturn = buffer.indexOf("\r");
	let newline = buffer.indexOf("\n");
	while (carriageReturn !== -1 || newline !== -1) {
		const lineEnd =
			carriageReturn === -1 ? newline : newline === -1 ? carriageReturn : Math.min(carriageReturn, newline);
		let next = lineEnd + 1;
		if (lineEnd === carriageReturn) {
			if (next === buffer.length && !final) break;
			if (newline === next) next += 1;
		}
		const event = decodeSseLine(buffer.slice(lineStart, lineEnd), state);
		if (event) {
			yield event;
		}
		lineStart = next;
		if (carriageReturn !== -1 && carriageReturn < next) carriageReturn = buffer.indexOf("\r", next);
		if (newline !== -1 && newline < next) newline = buffer.indexOf("\n", next);
	}
	return lineStart === 0 ? buffer : buffer.slice(lineStart);
}

async function* referenceIterateSseMessages(
	body: ReadableStream<Uint8Array>,
	signal?: AbortSignal,
): AsyncGenerator<ServerSentEvent> {
	const reader = body.getReader();
	const decoder = new TextDecoder();
	const state: SseDecoderState = { event: null, data: [], raw: [] };
	let buffer = "";

	try {
		while (true) {
			if (signal?.aborted) {
				throw new Error("Request was aborted");
			}

			const { value, done } = await reader.read();
			if (done) {
				break;
			}

			buffer = yield* decodeSseLines(buffer + decoder.decode(value, { stream: true }), state, false);
		}

		buffer = yield* decodeSseLines(buffer + decoder.decode(), state, true);

		if (buffer.length > 0) {
			const event = decodeSseLine(buffer, state);
			if (event) {
				yield event;
			}
		}

		const trailingEvent = flushSseEvent(state);
		if (trailingEvent) {
			yield trailingEvent;
		}
	} finally {
		reader.releaseLock();
	}
}

const encoder = new TextEncoder();

function rng(seed: number): () => number {
	let state = seed >>> 0;
	return () => {
		state = (state * 1664525 + 1013904223) >>> 0;
		return state / 2 ** 32;
	};
}

const CORPUS = [
	'event: message_start\ndata: {"type":"message_start"}\n\nevent: content_block_delta\ndata: {"t":"h\u00e9 \u2713 \ud834\udd1e"}\n\n',
	"event: a\r\ndata: 1\r\n\r\nevent: b\r\ndata: 2\r\n\r\n",
	"event: a\rdata: 1\r\revent: b\rdata: 2\r\r",
	"event: a\r\ndata: 1\n\r\n: ping\r\rdata: 2\n\n",
	": comment\n\ndata: x\n: inside\ndata: y\n\n",
	"data:no-space\ndata:  two\nevent\ndata\nid: 1\n\n",
	'event: error\ndata: {"type":"error"}\n\n',
	"data: unterminated",
	"data: unterminated\n",
	"data: cr\r",
	"data: cr\n\r",
	"\ufeffdata: bom\n\n\ufeffdata: line\n\n",
	"",
	"\n\n\r\n\r\r",
];

function fuzzBody(random: () => number): string {
	const newlines = ["\n", "\r\n", "\r"];
	const nl = () => newlines[Math.floor(random() * newlines.length)];
	let body = "";
	const count = 1 + Math.floor(random() * 6);
	for (let i = 0; i < count; i++) {
		if (random() < 0.15) body += `: c${i}${nl()}`;
		if (random() < 0.7) body += `event: e${i}${nl()}`;
		body += `data: {"i":${i},"s":"${"\u00e9\u2211\ud834\udd1e".repeat(Math.floor(random() * 3))}"}${nl()}`;
		if (random() < 0.2) body += `data: more${nl()}`;
		if (random() < 0.9 || i < count - 1) body += nl();
	}
	return body;
}

function splitAt(bytes: Uint8Array, cuts: number[]): Uint8Array[] {
	const chunks: Uint8Array[] = [];
	let start = 0;
	for (const cut of [...cuts].sort((a, b) => a - b)) {
		chunks.push(bytes.slice(start, cut));
		start = cut;
	}
	chunks.push(bytes.slice(start));
	return chunks;
}

function bodyOf(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
	let index = 0;
	return new ReadableStream<Uint8Array>({
		pull(controller) {
			if (index >= chunks.length) controller.close();
			else controller.enqueue(chunks[index++]);
		},
	});
}

async function collect(iterable: AsyncIterable<ServerSentEvent>): Promise<unknown> {
	const events: ServerSentEvent[] = [];
	try {
		for await (const event of iterable) events.push(event);
	} catch (error) {
		return { events, error: (error as Error).message };
	}
	return { events };
}

async function expectSame(chunks: Uint8Array[]): Promise<void> {
	const expected = await collect(referenceIterateSseMessages(bodyOf(chunks)));
	const actual = await collect(iterateSseMessages(bodyOf(chunks)));
	expect(actual).toEqual(expected);
}

describe("iterateSseMessages matches the parser it replaced", () => {
	it("supports reading, replacing and restoring the public decoder state between reads", () => {
		const original = createSseDecoder();
		expect([...decodeSseText(original, "event: test\ndata: pa", false)]).toEqual([]);
		expect([...decodeSseText(original, "rt", false)]).toEqual([]);
		expect(original.rest).toBe("data: part");

		const restored: SseDecoder = JSON.parse(JSON.stringify(original));
		expect(restored).toEqual({
			event: "test",
			data: [],
			raw: ["event: test"],
			stripLineBom: false,
			rest: "data: part",
			skipLeadingNewline: false,
		});
		for (const state of [original, restored]) {
			expect([...decodeSseText(state, "ial", false)]).toEqual([]);
			expect(state.rest).toBe("data: partial");
			state.rest = "data: replacement\r\n\r\ndata: next";
			expect([...decodeSseText(state, "!", false)]).toEqual([
				{ event: "test", data: "replacement", raw: ["event: test", "data: replacement"] },
			]);
			expect(state.rest).toBe("data: next!");
			state.rest = "";
			expect([...decodeSseText(state, "", true)]).toEqual([]);
		}
	});

	it("delivers a fragmented 1 MiB line on the terminating CR and an unfinished second line at EOF", () => {
		const state = createSseDecoder();
		const read = (text: string, final = false) => Array.from(decodeSseText(state, text, final));
		const pieces = Array.from({ length: 1024 }, (_, i) => String(i).padEnd(1024, "x"));
		const payload = pieces.join("");

		expect(read("event: tool\r\ndata: ")).toEqual([]);
		for (const piece of pieces) expect(read(piece)).toEqual([]);
		expect(read("\r")).toEqual([]);
		expect(read("\n\r")).toEqual([{ event: "tool", data: payload, raw: ["event: tool", `data: ${payload}`] }]);

		expect(read("")).toEqual([]);
		expect(read("\ndata: ta")).toEqual([]);
		expect(read("il")).toEqual([]);
		expect(read("", true)).toEqual([{ event: null, data: "tail", raw: ["data: tail"] }]);
	});

	it("gives the same events for every split of each stream into two reads, and for one byte per read", async () => {
		for (const text of CORPUS) {
			const bytes = encoder.encode(text);
			await expectSame(
				splitAt(
					bytes,
					Array.from({ length: Math.max(0, bytes.length - 1) }, (_, i) => i + 1),
				),
			);
			for (let cut = 0; cut <= bytes.length; cut++) await expectSame(splitAt(bytes, [cut]));
		}
	});

	it("gives the same events for fuzzed streams split into random reads", async () => {
		const random = rng(42);
		for (let round = 0; round < 500; round++) {
			const bytes = encoder.encode(fuzzBody(random));
			const cuts = Array.from({ length: Math.floor(random() * 8) }, () => Math.floor(random() * (bytes.length + 1)));
			await expectSame(splitAt(bytes, cuts));
		}
	});

	it("stops with the same error once the signal is aborted", async () => {
		const controller = new AbortController();
		controller.abort();
		const chunks = [encoder.encode("data: 1\n\n")];
		const expected = await collect(referenceIterateSseMessages(bodyOf(chunks), controller.signal));
		const actual = await collect(iterateSseMessages(bodyOf(chunks), { signal: controller.signal }));
		expect(actual).toEqual(expected);
		expect(actual).toEqual({ events: [], error: "Request was aborted" });
	});
});
