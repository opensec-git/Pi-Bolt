import type { StreamFn } from "@earendil-works/pi-agent-core";
import {
	createAssistantMessageEventStream,
	fauxAssistantMessage,
	getCurrentSystemMessage,
} from "@earendil-works/pi-ai";
import type { AssistantMessage, Model } from "@earendil-works/pi-ai/compat";
import { describe, expect, it } from "vitest";
import {
	autoTitleSourceText,
	generateSessionTitle,
	isPromptEchoTitle,
	sanitizeSessionTitle,
} from "../src/core/auto-title.ts";
import type { SessionEntry } from "../src/core/session-manager.ts";

const model = { api: "faux", provider: "faux", id: "faux-1" } as Model<"faux">;

function streamOnce(message: Partial<AssistantMessage> & { content: AssistantMessage["content"] }): StreamFn {
	return () => {
		const stream = createAssistantMessageEventStream();
		void Promise.resolve().then(() => {
			stream.push({
				type: "done",
				reason: message.stopReason === "length" ? "length" : "stop",
				message: {
					...fauxAssistantMessage(""),
					...message,
					api: model.api,
					provider: model.provider,
					model: model.id,
				} as AssistantMessage,
			});
		});
		return stream;
	};
}

const userEntry = (content: unknown): SessionEntry =>
	({ type: "message", id: "u1", timestamp: "t", message: { role: "user", content } }) as SessionEntry;

describe("autoTitleSourceText", () => {
	it("reads the first user message (string content)", () => {
		const entries = [
			{ type: "model_change", id: "m", timestamp: "t" },
			userEntry("fix the login loop"),
			userEntry("second message"),
		] as SessionEntry[];
		expect(autoTitleSourceText(entries)).toBe("fix the login loop");
	});

	it("joins text parts of array content, ignoring images", () => {
		const entries = [
			userEntry([
				{ type: "text", text: "look at " },
				{ type: "image", data: "x", mimeType: "image/png" },
				{ type: "text", text: "this screenshot" },
			]),
		];
		expect(autoTitleSourceText(entries)).toBe("look at   this screenshot"); // parts joined verbatim, sanitized later
	});

	it("returns empty when the branch has no user message", () => {
		expect(autoTitleSourceText([])).toBe("");
	});
});

describe("sanitizeSessionTitle", () => {
	it("strips quotes, bullets, label prefixes, and trailing punctuation", () => {
		expect(sanitizeSessionTitle('"Fix the login loop.!"')).toBe("Fix the login loop.!");
		expect(sanitizeSessionTitle('Title: "Fix login"')).toBe("Fix login");
		expect(sanitizeSessionTitle("- **Fix login**")).toBe("Fix login");
		expect(sanitizeSessionTitle("  Fix   login  '")).toBe("Fix login");
	});

	it("keeps only the first line and collapses whitespace", () => {
		expect(sanitizeSessionTitle("Fix login\nHere is why:\n...")).toBe("Fix login");
		expect(sanitizeSessionTitle("Fix\tthe\nlogin\nloop")).toBe("Fix the");
	});

	it("caps the length at the display bound", () => {
		const title = sanitizeSessionTitle("x".repeat(200));
		expect(title).not.toBeNull();
		expect(title!.length).toBeLessThanOrEqual(60);
	});

	it("returns null for output with nothing displayable", () => {
		expect(sanitizeSessionTitle("   \n  ")).toBeNull();
		expect(sanitizeSessionTitle('"""')).toBeNull();
	});
});

describe("isPromptEchoTitle", () => {
	it("detects a title that repeats the prompt opening", () => {
		expect(isPromptEchoTitle("Fix the login loop in dashboard", "Fix the login loop in dashboard now please")).toBe(
			true,
		);
	});

	it("accepts a title that merely shares a short prefix", () => {
		expect(isPromptEchoTitle("Fix login", "Fix the checkout totals")).toBe(false);
	});
});

describe("generateSessionTitle", () => {
	const signal = new AbortController().signal;

	it("returns the sanitized title text from a successful response", async () => {
		const title = await generateSessionTitle(
			model,
			streamOnce({ content: [{ type: "text", text: '"Fix Login Loop"\nExplanation…' }] }),
			"why does login redirect forever",
			signal,
		);
		expect(title).toBe("Fix Login Loop");
	});

	it("sends the titling system prompt and the source text (one-shot shape)", async () => {
		let seen: { system: string; user: string; maxTokens?: number } = { system: "", user: "" };
		const capture: StreamFn = (m, context, options) => {
			seen = {
				system: JSON.stringify(getCurrentSystemMessage(context.messages) ?? ""),
				user: JSON.stringify(context.messages),
				maxTokens: options?.maxTokens,
			};
			return streamOnce({ content: [{ type: "text", text: "Titled" }] })(m, context, options);
		};
		await generateSessionTitle(model, capture, "source prompt", signal);
		expect(seen.system).toContain("ONLY a title");
		expect(seen.user).toContain("source prompt");
		expect(seen.maxTokens).toBe(200);
	});

	it("returns null on an error or aborted response", async () => {
		const errored = await generateSessionTitle(
			model,
			streamOnce({ content: [], stopReason: "error" }),
			"prompt",
			signal,
		);
		expect(errored).toBeNull();
	});

	it("returns null when a reasoning model spends the budget on thinking (no text parts)", async () => {
		const title = await generateSessionTitle(
			model,
			streamOnce({
				content: [{ type: "thinking", thinking: "The user wants a title…", thinkingSignature: "sig" }],
				stopReason: "length",
			}),
			"prompt",
			signal,
		);
		expect(title).toBeNull();
	});
});
