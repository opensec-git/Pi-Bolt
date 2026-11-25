import type Anthropic from "@anthropic-ai/sdk";
import { describe, expect, it } from "vitest";
import { stream as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { getModel, normalizeContext } from "../src/compat.ts";
import type { AssistantMessage, Message } from "../src/types.ts";
import { shortHash } from "../src/utils/hash.ts";

/**
 * Anthropic reports a dropped thinking block by its position in the request, never by an id, and the
 * block is replayed on every later request - so one mismatch is reported again on every remaining
 * turn. Dropping it takes a signature hash recorded at report time, because the reported position is
 * stale the moment the request prefix moves.
 */

const SIGNATURE = "signed-thinking-payload-alpha";
const model = getModel("anthropic", "claude-fable-5-1");

function sseResponse(events: Array<{ event: string; data: string }>): Response {
	const body = events.map(({ event, data }) => `event: ${event}\ndata: ${data}\n`).join("\n");
	return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
}

/** A reply that thinks (signed) and then answers. `transformations` is what the API reports it did to our input. */
function replyEvents(transformations?: unknown[]): Array<{ event: string; data: string }> {
	const messageStart: Record<string, unknown> = {
		type: "message_start",
		message: {
			id: "msg_drop_replay",
			usage: { input_tokens: 12, output_tokens: 0, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 },
			...(transformations ? { input_transformations: transformations } : {}),
		},
	};
	return [
		{ event: "message_start", data: JSON.stringify(messageStart) },
		{
			event: "content_block_start",
			data: JSON.stringify({
				type: "content_block_start",
				index: 0,
				content_block: { type: "thinking", thinking: "" },
			}),
		},
		{
			event: "content_block_delta",
			data: JSON.stringify({
				type: "content_block_delta",
				index: 0,
				delta: { type: "thinking_delta", thinking: "working" },
			}),
		},
		{
			event: "content_block_delta",
			data: JSON.stringify({
				type: "content_block_delta",
				index: 0,
				delta: { type: "signature_delta", signature: SIGNATURE },
			}),
		},
		{ event: "content_block_stop", data: JSON.stringify({ type: "content_block_stop", index: 0 }) },
		{
			event: "content_block_start",
			data: JSON.stringify({ type: "content_block_start", index: 1, content_block: { type: "text", text: "" } }),
		},
		{
			event: "content_block_delta",
			data: JSON.stringify({ type: "content_block_delta", index: 1, delta: { type: "text_delta", text: "Answer" } }),
		},
		{ event: "content_block_stop", data: JSON.stringify({ type: "content_block_stop", index: 1 }) },
		{
			event: "message_delta",
			data: JSON.stringify({
				type: "message_delta",
				delta: { stop_reason: "end_turn" },
				usage: {
					input_tokens: 12,
					output_tokens: 5,
					cache_read_input_tokens: 0,
					cache_creation_input_tokens: 0,
					output_tokens_details: { thinking_tokens: 4 },
				},
			}),
		},
		{ event: "message_stop", data: JSON.stringify({ type: "message_stop" }) },
	];
}

type SentBlock = { type: string; signature?: string; text?: string };

interface SentMessage {
	role: string;
	content: SentBlock[] | string;
}

const sentBlocks = (message: SentMessage): SentBlock[] => (Array.isArray(message.content) ? message.content : []);

async function request(messages: Message[], events: Array<{ event: string; data: string }>) {
	const sent: SentMessage[] = [];
	const client = {
		beta: {
			messages: {
				create: (body: { messages: SentMessage[] }) => {
					sent.push(...body.messages);
					return { asResponse: async () => sseResponse(events) };
				},
			},
		},
	} as unknown as Anthropic;
	const message: AssistantMessage = await streamAnthropic(model, normalizeContext({ systemPrompt: "s", messages }), {
		client,
		apiKey: "test-key",
	}).result();
	return { message, sent };
}

const userTurn = (text: string, timestamp: number): Message => ({ role: "user", content: text, timestamp });

/**
 * Where a signed thinking block actually landed in the request. The API names drops by request
 * position, and that position is not the transcript position: effort markers are inserted as their
 * own messages, so a test must not assume the assistant message sits where the transcript puts it.
 */
function thinkingPathOf(sent: SentMessage[], signature: string): string {
	for (let i = 0; i < sent.length; i++) {
		const blocks = sentBlocks(sent[i]);
		for (let j = 0; j < blocks.length; j++) {
			if (blocks[j].type === "thinking" && blocks[j].signature === signature) return `messages.${i}.content.${j}`;
		}
	}
	throw new Error("the signed thinking block was not replayed in the request");
}

function sentThinkingSignatures(sent: SentMessage[]): (string | undefined)[] {
	return sent.flatMap((message) =>
		sentBlocks(message)
			.filter((block) => block.type === "thinking")
			.map((block) => block.signature),
	);
}

describe("Anthropic replay of thinking reported as dropped", () => {
	it("records the dropped block by signature and stops replaying it", async () => {
		const first = await request([userTurn("a", 1)], replyEvents());
		// The reply mints the signed block; it can only be replayed, and so dropped, from the next request on.
		expect((first.message.content[0] as { thinkingSignature?: string }).thinkingSignature).toBe(SIGNATURE);
		const replayed = await request([userTurn("a", 1), first.message, userTurn("b", 2)], replyEvents());
		expect(sentThinkingSignatures(replayed.sent)).toContain(SIGNATURE);
		const path = thinkingPathOf(replayed.sent, SIGNATURE);

		// The API reports the block it dropped by request position; the reply now carries that report.
		const transcript: Message[] = [userTurn("a", 1), first.message, userTurn("b", 2)];
		const reported = await request(
			transcript,
			replyEvents([{ type: "thinking_dropped", path, reason: "prefix_binding_mismatch" }]),
		);

		const recorded = (reported.message.diagnostics ?? []).find(
			(diagnostic) => diagnostic.type === "anthropic_input_transformations",
		);
		expect(recorded?.details).toEqual({
			transformations: [
				{
					type: "thinking_dropped",
					path,
					reason: "prefix_binding_mismatch",
					signatureHash: shortHash(SIGNATURE),
				},
			],
		});

		// The report lands on the assistant message that is replayed, so the next request drops it.
		const withReport: Message[] = [...transcript, reported.message, userTurn("c", 3)];
		const after = await request(withReport, replyEvents());
		expect(sentThinkingSignatures(after.sent)).not.toContain(SIGNATURE);
		expect(after.sent.some((message) => sentBlocks(message).some((block) => block.text === "Answer"))).toBe(true);
	});

	it("keeps replaying thinking when the report names a position that is not a signed block", async () => {
		const first = await request([userTurn("a", 1)], replyEvents());
		const reported = await request(
			[userTurn("a", 1), first.message, userTurn("b", 2)],
			replyEvents([
				// A path outside the request names nothing we can identify, so nothing may be dropped.
				{ type: "thinking_dropped", path: "messages.404.content.0", reason: "prefix_binding_mismatch" },
			]),
		);
		expect(
			(reported.message.diagnostics ?? []).find((d) => d.type === "anthropic_input_transformations")?.details,
		).toEqual({
			transformations: [
				{ type: "thinking_dropped", path: "messages.404.content.0", reason: "prefix_binding_mismatch" },
			],
		});

		const after = await request(
			[userTurn("a", 1), first.message, userTurn("b", 2), reported.message, userTurn("c", 3)],
			replyEvents(),
		);
		expect(sentThinkingSignatures(after.sent)).toContain(SIGNATURE);
	});
});
