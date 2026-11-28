// Regression tests for the outside review's claims A4, A5, A8 and A10 (w132 verify-core), which are fixed now.
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
	type Api,
	type AssistantMessage,
	createAssistantMessageEventStream,
	fauxAssistantMessage,
	type Model,
} from "@earendil-works/pi-ai";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AgentSessionEvent } from "../src/core/agent-session.ts";
import { AuthStorage } from "../src/core/auth-storage.ts";
import { DefaultResourceLoader } from "../src/core/resource-loader.ts";
import { createAgentSession } from "../src/core/sdk.ts";
import { SessionManager } from "../src/core/session-manager.ts";
import { FileSettingsStorage, SettingsManager } from "../src/core/settings-manager.ts";
import { createModelRegistry, getModelRuntime } from "./model-runtime-test-utils.ts";
import { createHarness, type Harness } from "./suite/harness.ts";

const tempDirs: string[] = [];
const harnesses: Harness[] = [];

function tempDir(): string {
	const dir = mkdtempSync(join(tmpdir(), "pi-verify-core-"));
	tempDirs.push(dir);
	return dir;
}

afterEach(() => {
	vi.useRealTimers();
	while (harnesses.length > 0) harnesses.pop()?.cleanup();
	while (tempDirs.length > 0) rmSync(tempDirs.pop()!, { recursive: true, force: true });
});

function doneMessage(api: Api, promptTokens: number): AssistantMessage {
	return {
		role: "assistant",
		content: [{ type: "text", text: "ok" }],
		api,
		provider: "capture-provider",
		model: "capture-model",
		usage: {
			input: 0,
			output: 0,
			cacheRead: promptTokens,
			cacheWrite: 0,
			totalTokens: promptTokens,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp: Date.now(),
	};
}

describe("claim A4: cache warming after /reload", () => {
	// claim A4: buildRequestOptions() (sdk.ts:367) captures the extension runner of the moment in transformHeaders. The
	// cache warmer replays that request's options later, and reload() does not stop it, so a warm request sent after a
	// reload runs the old, invalidated runner's before_provider_headers handlers and not the reloaded ones.
	it("sends a warm request after a reload with the reloaded extension's headers", async () => {
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
		const dir = tempDir();
		const cwd = join(dir, "project");
		const agentDir = join(dir, "agent");
		mkdirSync(cwd, { recursive: true });
		mkdirSync(agentDir, { recursive: true });
		const model: Model<Api> = {
			id: "capture-model",
			name: "Capture Model",
			api: "anthropic-messages",
			provider: "capture-provider",
			baseUrl: "https://capture.invalid/v1",
			reasoning: false,
			input: ["text"],
			cost: { input: 10, output: 50, cacheRead: 0.25, cacheWrite: 12.5 },
			contextWindow: 128000,
			maxTokens: 4096,
			promptCache: { short: 300 },
		};
		let version = 1;
		const settingsManager = SettingsManager.inMemory({ cacheWarming: "idle" });
		const resourceLoader = new DefaultResourceLoader({
			cwd,
			agentDir,
			settingsManager,
			extensionFactories: [
				(pi) => {
					const registered = version;
					pi.on("before_provider_headers", (event) => {
						event.headers["x-extension"] = `v${registered}`;
					});
				},
			],
		});
		await resourceLoader.reload();
		const authStorage = AuthStorage.create(join(agentDir, "auth.json"));
		await authStorage.modify(model.provider, async () => ({ type: "api_key", key: "test-api-key" }));
		const modelRegistry = await createModelRegistry(authStorage, join(agentDir, "models.json"));
		const sent: { maxTokens?: number; header?: string | null }[] = [];
		modelRegistry.registerProvider(model.provider, {
			api: model.api,
			streamSimple: (_model, _context, options) => {
				sent.push({ maxTokens: options?.maxTokens, header: options?.headers?.["x-extension"] });
				const stream = createAssistantMessageEventStream();
				stream.end(doneMessage(model.api, 100_000));
				return stream;
			},
		});
		const { session } = await createAgentSession({
			cwd,
			agentDir,
			model,
			modelRuntime: getModelRuntime(modelRegistry),
			settingsManager,
			sessionManager: SessionManager.inMemory(cwd),
			resourceLoader,
		});
		try {
			await session.prompt("test");
			expect(session.cacheWarmingStatus?.state).toBe("scheduled");
			version = 2;
			await session.reload();
			await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
			const warm = sent.filter((request) => request.maxTokens === 1);
			expect(sent[0]).toMatchObject({ header: "v1" });
			expect(warm.length).toBeGreaterThan(0);
			expect(warm.map((request) => request.header)).toEqual(warm.map(() => "v2"));
		} finally {
			session.dispose();
			modelRegistry.unregisterProvider(model.provider);
		}
	});
});

describe("claim A5: a listener that unsubscribes itself", () => {
	// claim A5: _emit() iterates the listener array while unsubscribe() splices it, so the listener after one that
	// unsubscribes itself does not get that event.
	it("still delivers the event to the next listener", async () => {
		const harness = await createHarness();
		harnesses.push(harness);
		harness.setResponses([fauxAssistantMessage("hello")]);
		const unsubscribe = harness.session.subscribe(() => unsubscribe());
		const seen: AgentSessionEvent["type"][] = [];
		harness.session.subscribe((event) => seen.push(event.type));
		await harness.session.prompt("hi");
		expect(seen).toEqual(harness.events.map((event) => event.type));
	});
});

describe("claim A8: two prompts started together while idle", () => {
	// claim A8: _preparePrompt() checks isStreaming and then awaits checkAuth() when the provider is not in the
	// availability snapshot (credentials added since the last refresh). A second prompt checked in that gap also reaches
	// _startRun(): it runs before_agent_start, its agent.prompt() is refused, and its settle marks the session idle
	// while the first run is still going.
	it("rejects the second prompt and keeps the session busy during the first run", async () => {
		const harness = await createHarness();
		harnesses.push(harness);
		// The credential works, but the last availability refresh did not see it.
		vi.spyOn(harness.session.modelRuntime, "hasConfiguredAuth").mockReturnValue(false);
		harness.setResponses([fauxAssistantMessage("first answer"), fauxAssistantMessage("second answer")]);
		const streamingDuringFirstRun: boolean[] = [];
		harness.session.subscribe((event) => {
			if (event.type === "message_end" && event.message.role !== "system") {
				streamingDuringFirstRun.push(harness.session.isStreaming);
			}
		});
		const results = await Promise.allSettled([harness.session.prompt("one"), harness.session.prompt("two")]);
		expect(results[0].status).toBe("fulfilled");
		expect(results[1].status).toBe("rejected");
		expect(streamingDuringFirstRun).toEqual([true, true]);
		expect(String((results[1] as PromiseRejectedResult).reason)).toContain(
			"Specify streamingBehavior ('steer' or 'followUp')",
		);
	});
});

describe("claim A10: creating settings.json from two processes", () => {
	// claim A10: withLock() took no lock while settings.json did not exist; it read "no settings" and locked only to
	// write, so another process that created the file in between lost its write. The other process is simulated by a
	// second storage writing at that point.
	it("excludes the other writer while the file is being created", () => {
		const dir = tempDir();
		const mine = new FileSettingsStorage(dir, dir);
		const other = new FileSettingsStorage(dir, dir);
		let otherError: unknown;
		mine.withLock("global", (current) => {
			try {
				other.withLock("global", () => JSON.stringify({ theme: "dark" }));
			} catch (error) {
				otherError = error;
			}
			return JSON.stringify({ ...(current ? JSON.parse(current) : {}), defaultModel: "m" });
		});
		expect((otherError as NodeJS.ErrnoException | undefined)?.code).toBe("ELOCKED");
		expect(JSON.parse(readFileSync(join(dir, "settings.json"), "utf8"))).toEqual({ defaultModel: "m" });
	});

	it("keeps what another process wrote when it also created the directory", () => {
		const dir = join(tempDir(), "agent");
		const mine = new FileSettingsStorage(dir, dir);
		const other = new FileSettingsStorage(dir, dir);
		let otherWrote = false;
		mine.withLock("global", (current) => {
			if (!otherWrote) {
				otherWrote = true;
				other.withLock("global", () => JSON.stringify({ theme: "dark" }));
			}
			return JSON.stringify({ ...(current ? JSON.parse(current) : {}), defaultModel: "m" });
		});
		expect(JSON.parse(readFileSync(join(dir, "settings.json"), "utf8"))).toEqual({
			theme: "dark",
			defaultModel: "m",
		});
	});

	it("excludes the other writer while the file exists", () => {
		const dir = tempDir();
		writeFileSync(join(dir, "settings.json"), "{}");
		const mine = new FileSettingsStorage(dir, dir);
		const other = new FileSettingsStorage(dir, dir);
		let otherError: unknown;
		mine.withLock("global", () => {
			try {
				other.withLock("global", () => JSON.stringify({ theme: "dark" }));
			} catch (error) {
				otherError = error;
			}
			return JSON.stringify({ defaultModel: "m" });
		});
		expect((otherError as NodeJS.ErrnoException).code).toBe("ELOCKED");
	});
});

describe("abort while a prompt is still being checked", () => {
	// fix-modes "suspected": RPC abort and the interactive Esc during the prompt's checks (input handlers, credential
	// check) found no run to abort, so the turn then ran in full.
	it("cancels the pending prompt", async () => {
		const harness = await createHarness();
		harnesses.push(harness);
		const runtime = harness.session.modelRuntime;
		vi.spyOn(runtime, "hasConfiguredAuth").mockReturnValue(false);
		let finishCheck: () => void = () => {};
		const realCheck = runtime.checkAuth.bind(runtime);
		vi.spyOn(runtime, "checkAuth").mockImplementation(async (provider, options) => {
			await new Promise<void>((resolve) => {
				finishCheck = resolve;
			});
			return realCheck(provider, options);
		});
		harness.setResponses([fauxAssistantMessage("should not run")]);
		const dispositions: string[] = [];

		const prompt = harness.session.prompt("hi", { preflightResult: (disposition) => dispositions.push(disposition) });
		await vi.waitFor(() => expect(runtime.checkAuth).toHaveBeenCalled());
		await harness.session.abort();
		finishCheck();
		await prompt;

		expect(harness.faux.state.callCount).toBe(0);
		expect(harness.eventsOfType("agent_start")).toEqual([]);
		expect(dispositions).toEqual(["handled"]);
		expect(harness.session.isStreaming).toBe(false);
	});

	it("still runs a prompt sent after the abort", async () => {
		const harness = await createHarness();
		harnesses.push(harness);
		harness.setResponses([fauxAssistantMessage("answer")]);
		await harness.session.abort();
		await harness.session.prompt("hi");
		expect(harness.faux.state.callCount).toBe(1);
	});
});
