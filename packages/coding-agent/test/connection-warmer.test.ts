import type { Api, Model } from "@earendil-works/pi-ai";
import { describe, expect, it, vi } from "vitest";
import {
	ConnectionWarmer,
	isModelActivity,
	modelPreconnectOrigin,
	preconnectAtStartup,
	preconnectOrigin,
	runtimePreconnect,
} from "../src/core/connection-warmer.ts";

function model(api: string, baseUrl: string): Model<Api> {
	return {
		id: "m",
		name: "M",
		api,
		provider: "p",
		baseUrl,
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 100_000,
		maxTokens: 1_000,
	} as Model<Api>;
}

const anthropic = model("anthropic-messages", "https://api.anthropic.com");

function setup() {
	let now = 1_000_000;
	const preconnect = vi.fn<(url: string) => void>();
	const warmer = new ConnectionWarmer({ preconnect, now: () => now, idleMs: 10_000 });
	return {
		warmer,
		preconnect,
		advance: (ms: number) => {
			now += ms;
		},
	};
}

describe("preconnectOrigin", () => {
	it("is the origin of the base URL requests used, not the catalog's", () => {
		expect(preconnectOrigin(anthropic, "https://enterprise.example.com/v1")).toBe("https://enterprise.example.com");
	});

	it("is the HTTPS origin of an API whose requests go to the model's baseUrl", () => {
		expect(preconnectOrigin(anthropic, anthropic.baseUrl)).toBe("https://api.anthropic.com");
		expect(
			preconnectOrigin(
				model("openai-completions", "https://api.example.com:8443/v1"),
				"https://api.example.com:8443/v1",
			),
		).toBe("https://api.example.com:8443");
		expect(
			preconnectOrigin(model("openai-responses", "https://api.openai.com/v1"), "https://api.openai.com/v1"),
		).toBe("https://api.openai.com");
	});

	it("is undefined for other APIs, plain HTTP, a bad URL, no model", () => {
		expect(
			preconnectOrigin(
				model("google-generative-ai", "https://generativelanguage.googleapis.com"),
				"https://generativelanguage.googleapis.com",
			),
		).toBeUndefined();
		expect(
			preconnectOrigin(
				model("openai-codex-responses", "https://chatgpt.com/backend-api"),
				"https://chatgpt.com/backend-api",
			),
		).toBeUndefined();
		expect(
			preconnectOrigin(model("openai-completions", "http://127.0.0.1:8080/v1"), "http://127.0.0.1:8080/v1"),
		).toBeUndefined();
		expect(preconnectOrigin(model("openai-completions", "not a url"), "not a url")).toBeUndefined();
		expect(preconnectOrigin(undefined, "https://api.anthropic.com")).toBeUndefined();
		// No request has resolved where this model's requests go yet.
		expect(preconnectOrigin(anthropic, undefined)).toBeUndefined();
	});
});

describe("isModelActivity", () => {
	it("counts assistant messages and compaction, not bookkeeping", () => {
		const assistant = { role: "assistant" };
		for (const type of ["message_start", "message_update", "message_end"]) {
			expect(isModelActivity({ type, message: assistant })).toBe(true);
			expect(isModelActivity({ type, message: { role: "user" } })).toBe(false);
			expect(isModelActivity({ type, message: { role: "toolResult" } })).toBe(false);
		}
		expect(isModelActivity({ type: "compaction_start" })).toBe(true);
		expect(isModelActivity({ type: "compaction_end" })).toBe(true);
		for (const type of [
			"thinking_level_changed",
			"session_info_changed",
			"entry_appended",
			"queue_update",
			"tool_execution_end",
			"agent_settled",
		]) {
			expect(isModelActivity({ type })).toBe(false);
		}
	});
});

describe("ConnectionWarmer", () => {
	it("preconnects when a prompt is started, then once per idle period", () => {
		const { warmer, preconnect, advance } = setup();
		warmer.onTyping("h", anthropic, anthropic.baseUrl);
		warmer.onTyping("he", anthropic, anthropic.baseUrl);
		expect(preconnect).toHaveBeenCalledExactlyOnceWith("https://api.anthropic.com");
		// The prompt is sent and answered: activity.
		warmer.markActivity();
		advance(9_000);
		warmer.onTyping("n", anthropic, anthropic.baseUrl);
		expect(preconnect).toHaveBeenCalledOnce();
		advance(1_000);
		warmer.onTyping("ne", anthropic, anthropic.baseUrl);
		expect(preconnect).toHaveBeenCalledTimes(2);
		// Still typing: the next only after another idle period.
		advance(5_000);
		warmer.onTyping("nex", anthropic, anthropic.baseUrl);
		expect(preconnect).toHaveBeenCalledTimes(2);
		advance(5_000);
		warmer.onTyping("next", anthropic, anthropic.baseUrl);
		expect(preconnect).toHaveBeenCalledTimes(3);
	});

	it("does not preconnect for an empty editor, a command, a shell command, or a model it cannot help", () => {
		const { warmer, preconnect } = setup();
		for (const text of ["", "   ", "/model", " /compact", "!ls", "!!ls"])
			warmer.onTyping(text, anthropic, anthropic.baseUrl);
		warmer.onTyping("hello", model("openai-completions", "http://localhost:11434/v1"), "http://localhost:11434/v1");
		warmer.onTyping("hello", anthropic, undefined);
		warmer.onTyping("hello", undefined, undefined);
		expect(preconnect).not.toHaveBeenCalled();
		// None of that used up the idle period.
		warmer.onTyping("hello", anthropic, anthropic.baseUrl);
		expect(preconnect).toHaveBeenCalledOnce();
	});

	it("does nothing without a runtime preconnect, and survives one that throws", () => {
		const none = new ConnectionWarmer({ preconnect: undefined });
		expect(() => none.onTyping("hello", anthropic, anthropic.baseUrl)).not.toThrow();
		const throwing = new ConnectionWarmer({
			preconnect: () => {
				throw new Error("no");
			},
		});
		expect(() => throwing.onTyping("hello", anthropic, anthropic.baseUrl)).not.toThrow();
	});

	it("uses fetch.preconnect where the runtime has it", () => {
		const preconnect = vi.fn();
		vi.stubGlobal(
			"fetch",
			Object.assign(() => undefined, { preconnect }),
		);
		try {
			runtimePreconnect()?.("https://api.anthropic.com");
			expect(preconnect).toHaveBeenCalledExactlyOnceWith("https://api.anthropic.com");
			vi.stubGlobal("fetch", () => undefined);
			expect(runtimePreconnect()).toBeUndefined();
		} finally {
			vi.unstubAllGlobals();
		}
	});
});

/** A model runtime that has made no request yet, signing in with OAuth or not. */
function runtime(options: { oauth?: boolean; lastBaseUrl?: string } = {}) {
	return {
		lastRequestBaseUrl: () => options.lastBaseUrl,
		isUsingOAuth: () => options.oauth === true,
	};
}

describe("startup preconnect", () => {
	it("uses the model's base URL before any request unless the provider signs in with OAuth", () => {
		expect(modelPreconnectOrigin(anthropic, runtime())).toBe("https://api.anthropic.com");
		expect(modelPreconnectOrigin(anthropic, runtime({ oauth: true }))).toBeUndefined();
		// A request has shown where they go.
		expect(
			modelPreconnectOrigin(anthropic, runtime({ oauth: true, lastBaseUrl: "https://copilot.example.com" })),
		).toBe("https://copilot.example.com");
		expect(modelPreconnectOrigin(undefined, runtime())).toBeUndefined();
	});

	it("preconnects once, and never throws", () => {
		const preconnect = vi.fn<(url: string) => void>();
		preconnectAtStartup(anthropic, runtime(), preconnect);
		expect(preconnect).toHaveBeenCalledExactlyOnceWith("https://api.anthropic.com");
		preconnectAtStartup(model("openai-completions", "http://localhost:11434/v1"), runtime(), preconnect);
		preconnectAtStartup(undefined, runtime(), preconnect);
		expect(preconnect).toHaveBeenCalledOnce();
		expect(() =>
			preconnectAtStartup(anthropic, runtime(), () => {
				throw new Error("no");
			}),
		).not.toThrow();
		expect(() => preconnectAtStartup(anthropic, runtime(), undefined)).not.toThrow();
	});
});

describe("ConnectionWarmer during a tool batch", () => {
	it("preconnects every idle period from the batch's first call to the end of its turn", () => {
		vi.useFakeTimers();
		try {
			const preconnect = vi.fn<(url: string) => void>();
			const warmer = new ConnectionWarmer({ preconnect, idleMs: 10_000, target: () => "https://api.anthropic.com" });
			warmer.onAgentEvent({ type: "tool_execution_start" });
			// The batch's other calls do not restart the period.
			vi.advanceTimersByTime(5_000);
			warmer.onAgentEvent({ type: "tool_execution_start" });
			vi.advanceTimersByTime(5_000);
			expect(preconnect).toHaveBeenCalledExactlyOnceWith("https://api.anthropic.com");
			vi.advanceTimersByTime(10_000);
			expect(preconnect).toHaveBeenCalledTimes(2);
			// The results are about to be sent: the request uses the connection.
			warmer.onAgentEvent({ type: "turn_end" });
			vi.advanceTimersByTime(60_000);
			expect(preconnect).toHaveBeenCalledTimes(2);
			// A short batch preconnects nothing; the end of the run and stop() end it too.
			warmer.onAgentEvent({ type: "tool_execution_start" });
			vi.advanceTimersByTime(9_000);
			warmer.onAgentEvent({ type: "agent_end" });
			warmer.onAgentEvent({ type: "tool_execution_start" });
			warmer.stop();
			vi.advanceTimersByTime(60_000);
			expect(preconnect).toHaveBeenCalledTimes(2);
		} finally {
			vi.useRealTimers();
		}
	});

	it("does nothing without a target or a runtime preconnect, and survives one that throws", () => {
		vi.useFakeTimers();
		try {
			const preconnect = vi.fn<(url: string) => void>();
			new ConnectionWarmer({ preconnect, idleMs: 10_000 }).onAgentEvent({ type: "tool_execution_start" });
			new ConnectionWarmer({
				preconnect: undefined,
				idleMs: 10_000,
				target: () => "https://a.example",
			}).onAgentEvent({ type: "tool_execution_start" });
			const throwing = new ConnectionWarmer({
				preconnect: () => {
					throw new Error("no");
				},
				idleMs: 10_000,
				target: () => "https://a.example",
			});
			throwing.onAgentEvent({ type: "tool_execution_start" });
			expect(() => vi.advanceTimersByTime(30_000)).not.toThrow();
			expect(preconnect).not.toHaveBeenCalled();
			throwing.stop();
		} finally {
			vi.useRealTimers();
		}
	});
});
