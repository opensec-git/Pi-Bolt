// #346: a reasoning item the provider reported as removed for good stays out of requests after the session file is
// reopened. The report is kept in the assistant message's diagnostics, which the session file stores.
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AssistantMessage } from "@earendil-works/pi-ai";
import { completeSimple, getModel } from "@earendil-works/pi-ai/compat";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { convertToLlm } from "../src/core/messages.ts";
import { SessionManager } from "../src/core/session-manager.ts";

const model = getModel("openai", "gpt-6.2-sol");

function reply(n: number, removed: string[] = []): AssistantMessage {
	return {
		role: "assistant",
		content: [
			{
				type: "thinking",
				thinking: `thought ${n}`,
				thinkingSignature: JSON.stringify({
					type: "reasoning",
					id: `rs_${n}`,
					summary: [],
					encrypted_content: `enc_${n}`,
				}),
			},
			{ type: "text", text: `answer ${n}` },
		],
		api: model.api,
		provider: model.provider,
		model: model.id,
		usage: {
			input: 1,
			output: 1,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 2,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp: n,
		...(removed.length > 0
			? {
					diagnostics: [
						{
							type: "openai_input_transformations",
							timestamp: n,
							details: {
								transformations: removed.map((id) => ({
									type: "reasoning",
									id,
									action: "removed",
									reason: "prefix_mismatch",
								})),
							},
						},
					],
				}
			: {}),
	};
}

/** Sends `messages` to a fake OpenAI endpoint; returns the ids of the reasoning items the request replays. */
async function replayedReasoning(messages: Parameters<typeof convertToLlm>[0]): Promise<string[]> {
	let replayed: string[] = [];
	const fetch: typeof globalThis.fetch = async (input, init) => {
		const request = input instanceof Request ? input : new Request(input, init);
		const body = (await request.json()) as { input: Array<{ type?: string; id?: string }> };
		replayed = body.input.flatMap((item) => (item.type === "reasoning" && item.id ? [item.id] : []));
		const completed = {
			type: "response.completed",
			sequence_number: 0,
			response: { id: "resp", status: "completed", output: [] },
		};
		return new Response(`data: ${JSON.stringify(completed)}\n\n`, {
			headers: { "content-type": "text/event-stream" },
		});
	};
	const result = await completeSimple(
		model,
		{ systemPrompt: "Instructions.", messages: convertToLlm(messages) },
		{ apiKey: "test-key", reasoning: "high", fetch },
	);
	expect(result.stopReason, result.errorMessage).toBe("stop");
	return replayed;
}

describe("reasoning removed for good after a session reload (#346)", () => {
	let dir: string;

	beforeEach(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-reasoning-drop-"));
	});

	afterEach(() => {
		rmSync(dir, { recursive: true, force: true });
	});

	it("does not replay the removed item from a reopened session file", async () => {
		const session = SessionManager.create(dir, dir);
		session.appendMessage({ role: "user", content: "turn 1", timestamp: 1 });
		session.appendMessage(reply(1));
		session.appendMessage({ role: "user", content: "turn 2", timestamp: 2 });
		session.appendMessage(reply(2));
		session.appendMessage({ role: "user", content: "turn 3", timestamp: 3 });
		// The request of turn 3 replayed rs_1 and rs_2 under a changed head; the reply reports both.
		session.appendMessage(reply(3, ["rs_1", "rs_2"]));
		session.appendMessage({ role: "user", content: "turn 4", timestamp: 4 });
		const file = session.getSessionFile();
		if (!file) throw new Error("expected a session file");

		const reopened = SessionManager.open(file, dir);
		expect(await replayedReasoning(reopened.buildSessionContext().messages)).toEqual(["rs_3"]);
	});
});
