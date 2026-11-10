import type { AssistantMessage } from "@earendil-works/pi-ai";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { AgentSessionEvent, AgentSessionEventListener } from "../src/core/agent-session.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";

// How InteractiveMode takes the session's events (subscribeToAgent()) and which frame the end of a turn asks for.

type DispatchHost = {
	isInitialized: boolean;
	session: { subscribe(listener: AgentSessionEventListener): () => void };
	unsubscribe?: () => void;
	init(): Promise<void>;
	handleEvent(event: AgentSessionEvent): Promise<void> | undefined;
};

const subscribeToAgent = Reflect.get(InteractiveMode.prototype, "subscribeToAgent") as (this: DispatchHost) => void;

function createDispatchHost(initialized: boolean): {
	host: DispatchHost;
	emit: (event: AgentSessionEvent) => unknown;
	handled: string[];
	finishInit: () => void;
} {
	let listener: AgentSessionEventListener | undefined;
	const handled: string[] = [];
	let finishInit = () => {};
	const host: DispatchHost = {
		isInitialized: initialized,
		session: {
			subscribe: (next) => {
				listener = next;
				return () => {};
			},
		},
		init: () =>
			new Promise<void>((resolve) => {
				host.isInitialized = true;
				finishInit = resolve;
			}),
		handleEvent: (event) => {
			handled.push(event.type);
			return undefined;
		},
	};
	subscribeToAgent.call(host);
	return { host, emit: (event) => listener?.(event), handled, finishInit: () => finishInit() };
}

function assistantMessage(content: AssistantMessage["content"]): AssistantMessage {
	return {
		role: "assistant",
		content,
		api: "openai-completions",
		provider: "test",
		model: "test",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp: 0,
	};
}

describe("InteractiveMode event dispatch", () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	test("handles events at once and in order once initialized, with no promise per event", () => {
		const { emit, handled } = createDispatchHost(true);
		const results = [emit({ type: "turn_start" }), emit({ type: "agent_end", messages: [], willRetry: false })];
		expect(handled).toEqual(["turn_start", "agent_end"]);
		expect(results).toEqual([undefined, undefined]);
	});

	test("handles an event that arrives before init() once init() finished", async () => {
		const { emit, handled, finishInit } = createDispatchHost(false);
		emit({ type: "turn_start" });
		await Promise.resolve();
		expect(handled).toEqual([]);
		finishInit();
		await vi.waitFor(() => expect(handled).toEqual(["turn_start"]));
	});

	test("does not throw a failure into the session: it becomes a rejected promise, as from an async listener", async () => {
		const { host, emit } = createDispatchHost(true);
		const failure = new Error("render failed");
		host.handleEvent = () => {
			throw failure;
		};
		const rejected: unknown[] = [];
		const reject = Promise.reject.bind(Promise);
		vi.spyOn(Promise, "reject").mockImplementation((reason?: unknown) => {
			rejected.push(reason);
			// (Handled here so that the test run does not report it.)
			const promise = reject(reason);
			promise.catch(() => {});
			return promise;
		});
		expect(() => emit({ type: "turn_start" })).not.toThrow();
		expect(rejected).toEqual([failure]);
	});
});

describe("InteractiveMode end-of-turn frames", () => {
	type FrameHost = {
		footer: { invalidate(): void };
		programStatus: { handleEvent(): void };
		connectionWarmer: undefined;
		streamingComponent: undefined;
		pendingTools: Map<string, unknown>;
		settingsManager: { getShowTerminalProgress(): boolean };
		clearStatusIndicator(): void;
		idleCollector: undefined;
		scheduleTranscriptCompaction(): void;
		ui: { requestRender: ReturnType<typeof vi.fn>; requestImmediateRender: ReturnType<typeof vi.fn> };
	};
	const handleEvent = Reflect.get(InteractiveMode.prototype, "handleEvent") as (
		this: FrameHost,
		event: AgentSessionEvent,
	) => Promise<void> | undefined;

	function createFrameHost(): FrameHost {
		return {
			footer: { invalidate: () => {} },
			programStatus: { handleEvent: () => {} },
			connectionWarmer: undefined,
			streamingComponent: undefined,
			pendingTools: new Map(),
			settingsManager: { getShowTerminalProgress: () => false },
			clearStatusIndicator: () => {},
			idleCollector: undefined,
			scheduleTranscriptCompaction: () => {},
			ui: { requestRender: vi.fn(), requestImmediateRender: vi.fn() },
		};
	}

	test("draws the end of an answer without tool calls at once", () => {
		const host = createFrameHost();
		handleEvent.call(host, { type: "message_end", message: assistantMessage([{ type: "text", text: "done" }]) });
		expect(host.ui.requestImmediateRender).toHaveBeenCalledTimes(1);
		expect(host.ui.requestRender).not.toHaveBeenCalled();
	});

	test("keeps the frame cap for an assistant message that calls tools", () => {
		const host = createFrameHost();
		handleEvent.call(host, {
			type: "message_end",
			message: assistantMessage([{ type: "toolCall", id: "call-1", name: "read", arguments: {} }]),
		});
		expect(host.ui.requestRender).toHaveBeenCalledTimes(1);
		expect(host.ui.requestImmediateRender).not.toHaveBeenCalled();
	});

	test("draws agent_end at once", () => {
		const host = createFrameHost();
		handleEvent.call(host, { type: "agent_end", messages: [], willRetry: false });
		expect(host.ui.requestImmediateRender).toHaveBeenCalledTimes(1);
		expect(host.ui.requestRender).not.toHaveBeenCalled();
	});
});
