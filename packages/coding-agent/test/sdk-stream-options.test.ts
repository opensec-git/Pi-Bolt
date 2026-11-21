import { mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
	type Api,
	type AssistantMessage,
	createAssistantMessageEventStream,
	type Model,
	normalizeContext,
	type SimpleStreamOptions,
} from "@earendil-works/pi-ai";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AuthStorage } from "../src/core/auth-storage.ts";
import type { ExtensionFactory, ExtensionMode, InlineExtension, ProviderConfig } from "../src/core/extensions/types.ts";
import { DefaultResourceLoader } from "../src/core/resource-loader.ts";
import { createAgentSession, enableCliStreamOptimization } from "../src/core/sdk.ts";
import { SessionManager } from "../src/core/session-manager.ts";
import { type Settings, SettingsManager } from "../src/core/settings-manager.ts";
import { createModelRegistry, getModelRuntime } from "./model-runtime-test-utils.ts";

describe("createAgentSession stream options", () => {
	let tempDir: string;
	let cwd: string;
	let agentDir: string;

	beforeEach(() => {
		tempDir = mkdtempSync(join(tmpdir(), "pi-sdk-stream-options-"));
		cwd = join(tempDir, "project");
		agentDir = join(tempDir, "agent");
		mkdirSync(cwd, { recursive: true });
		mkdirSync(agentDir, { recursive: true });
	});

	afterEach(() => {
		if (tempDir) {
			rmSync(tempDir, { recursive: true, force: true });
		}
	});

	function createModel(api: Api): Model<Api> {
		return {
			id: "capture-model",
			name: "Capture Model",
			api,
			provider: "capture-provider",
			baseUrl: "https://capture.invalid/v1",
			reasoning: false,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 128000,
			maxTokens: 4096,
			headers: { "x-model": "model" },
		};
	}

	function createDoneMessage(api: Api, promptTokens = 0): AssistantMessage {
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

	function createDoneStream(api: Api, promptTokens = 0) {
		const stream = createAssistantMessageEventStream();
		stream.end(createDoneMessage(api, promptTokens));
		return stream;
	}

	async function createStreamSession(
		api: Api,
		settings: Partial<Settings>,
		extensionFactory: InlineExtension | undefined,
		streamSimple: NonNullable<ProviderConfig["streamSimple"]>,
	) {
		const model = createModel(api);
		const settingsManager = SettingsManager.inMemory(settings);
		const resourceLoader = new DefaultResourceLoader({
			cwd,
			agentDir,
			settingsManager,
			extensionFactories: extensionFactory ? [extensionFactory] : [],
		});
		await resourceLoader.reload();

		const authStorage = AuthStorage.create(join(agentDir, "auth.json"));
		await authStorage.modify(model.provider, async () => ({ type: "api_key", key: "test-api-key" }));
		const modelRegistry = await createModelRegistry(authStorage, join(agentDir, "models.json"));
		modelRegistry.registerProvider(model.provider, { api, headers: { "x-provider": "provider" }, streamSimple });

		const modelRuntime = getModelRuntime(modelRegistry);
		const sessionManager = SessionManager.inMemory(cwd);
		const { session } = await createAgentSession({
			cwd,
			agentDir,
			model,
			modelRuntime,
			settingsManager,
			sessionManager,
			resourceLoader,
		});
		return {
			session,
			model,
			dispose: () => {
				session.dispose();
				modelRegistry.unregisterProvider(model.provider);
			},
		};
	}

	async function captureStreamOptions(
		api: Api,
		settings: Partial<Settings>,
		requestOptions: SimpleStreamOptions = {},
		extensionFactory?: ExtensionFactory,
		sendProviderEvents?: (emit: (event: unknown) => void | Promise<void>) => void | Promise<void>,
	): Promise<SimpleStreamOptions | undefined> {
		let capturedOptions: SimpleStreamOptions | undefined;
		let sentEvents: Promise<void> | undefined;
		const { model, session, dispose } = await createStreamSession(
			api,
			settings,
			extensionFactory,
			(requestModel, _context, providerOptions) => {
				capturedOptions = providerOptions;
				if (!sendProviderEvents) return createDoneStream(api);
				const stream = createAssistantMessageEventStream();
				sentEvents = Promise.resolve().then(() =>
					sendProviderEvents((event) => providerOptions?.onProviderStreamEvent?.(event, requestModel)),
				);
				const finish = () => stream.end(createDoneMessage(api));
				void sentEvents.then(finish, finish);
				return stream;
			},
		);
		try {
			if (!sendProviderEvents) {
				const stream = await session.agent.streamFunction(
					model,
					normalizeContext({ messages: [] }),
					requestOptions,
				);
				await stream.result();
			} else {
				await session.prompt("test");
				await sentEvents;
			}
			return capturedOptions;
		} finally {
			dispose();
		}
	}

	async function createCacheWarmingSession(populate?: (manager: SessionManager, model: Model<Api>) => void) {
		const model: Model<Api> = {
			...createModel("anthropic-messages"),
			cost: { input: 10, output: 50, cacheRead: 0.25, cacheWrite: 12.5 },
			promptCache: { short: 300 },
		};
		const authStorage = AuthStorage.create(join(agentDir, "auth.json"));
		await authStorage.modify(model.provider, async () => ({ type: "api_key", key: "test-api-key" }));
		const modelRegistry = await createModelRegistry(authStorage, join(agentDir, "models.json"));
		let providerCalls = 0;
		modelRegistry.registerProvider(model.provider, {
			api: model.api,
			streamSimple: () => {
				providerCalls++;
				return createDoneStream(model.api, 100_000);
			},
		});
		const sessionManager = SessionManager.inMemory(cwd);
		populate?.(sessionManager, model);
		const { session } = await createAgentSession({
			cwd,
			agentDir,
			model,
			modelRuntime: getModelRuntime(modelRegistry),
			settingsManager: SettingsManager.inMemory({ cacheWarming: "idle" }),
			sessionManager,
		});
		return {
			session,
			providerCalls: () => providerCalls,
			dispose: () => {
				session.dispose();
				modelRegistry.unregisterProvider(model.provider);
			},
		};
	}

	it("schedules cache warming after a completed session request", async () => {
		const fixture = await createCacheWarmingSession();
		try {
			await fixture.session.prompt("test");
			expect(fixture.session.cacheWarmingStatus?.nextWarmAt).toBeGreaterThan(Date.now());

			// Equivalent shallow copies remain current, but removing the request prefix does not.
			fixture.session.agent.state.messages = [...fixture.session.agent.state.messages];
			fixture.session.agent.state.model = { ...fixture.session.agent.state.model };
			expect(fixture.session.cacheWarmingStatus?.nextWarmAt).toBeGreaterThan(Date.now());
			fixture.session.agent.state.messages = fixture.session.agent.state.messages.slice(1);
			expect(fixture.session.cacheWarmingStatus?.reason).toBe("conversation context changed");
		} finally {
			fixture.dispose();
		}
	});

	it("waits for the next request instead of restoring cache warming", async () => {
		const fixture = await createCacheWarmingSession((manager, model) => {
			manager.appendModelChange(model.provider, model.id);
			manager.appendThinkingLevelChange("off");
			manager.appendMessage({ role: "user", content: "test", timestamp: Date.now() - 60_000 });
			const assistant = { ...createDoneMessage(model.api, 100_000), timestamp: Date.now() - 59_000 };
			manager.appendMessage(assistant);
			manager.appendUsage("cache_warm", model.provider, model.id, assistant.usage);
		});
		try {
			expect(fixture.providerCalls()).toBe(0);
			expect(fixture.session.cacheWarmingStatus).toEqual({
				state: "inactive",
				reason: "waiting for first request",
			});
		} finally {
			fixture.dispose();
		}
	});

	it("forwards httpIdleTimeoutMs as timeoutMs for OpenAI Codex", async () => {
		const options = await captureStreamOptions("openai-codex-responses", { httpIdleTimeoutMs: 1234 });

		expect(options?.timeoutMs).toBe(1234);
	});

	it("defaults timeoutMs from httpIdleTimeoutMs for all providers", async () => {
		const options = await captureStreamOptions("openai-completions", { httpIdleTimeoutMs: 1234 });

		expect(options?.timeoutMs).toBe(1234);
	});

	const builtin = { name: "usage", builtin: true, factory: () => {} };

	it.each<[string, ExtensionMode, boolean, InlineExtension]>([
		["an SDK session bound to print", "print", false, builtin],
		["a JSON session", "json", false, builtin],
		["an RPC session", "rpc", false, builtin],
		["a TUI session", "tui", false, builtin],
		["a print session with a third-party extension", "print", true, () => {}],
		[
			"a built-in message_start observer",
			"print",
			true,
			{
				...builtin,
				factory: (pi) => {
					pi.on("message_start", () => {});
				},
			},
		],
		[
			"a built-in message_update observer",
			"print",
			true,
			{
				...builtin,
				factory: (pi) => {
					pi.on("message_update", () => {});
				},
			},
		],
	])("keeps provider argument previews for %s", async (_name, mode, cliPrint, extension) => {
		let captured: SimpleStreamOptions | undefined;
		const { session, dispose } = await createStreamSession(
			"openai-completions",
			{},
			extension,
			(model, _context, options) => {
				captured = options;
				return createDoneStream(model.api);
			},
		);
		try {
			expect(session.extensionRunner.getExtensionPaths()).toHaveLength(1);
			if (cliPrint) enableCliStreamOptimization(session, "print");
			await session.bindExtensions({ mode });
			await session.prompt("test");
			expect(captured).toBeDefined();
			expect(captured?.skipToolArgumentPreviews).not.toBe(true);
			expect(captured?.skipTextDeltas).not.toBe(true);
		} finally {
			dispose();
		}
	});

	it.each(["print", "interactive"] as const)(
		"optimizes only the foreground request of a built-in-only CLI %s session",
		async (mode) => {
			const firstRequest = Promise.withResolvers<SimpleStreamOptions | undefined>();
			const foregroundStream = createAssistantMessageEventStream();
			const requests: Array<SimpleStreamOptions | undefined> = [];
			const { model, session, dispose } = await createStreamSession(
				"openai-completions",
				{},
				builtin,
				(model, _context, options) => {
					requests.push(options);
					if (requests.length === 1) {
						firstRequest.resolve(options);
						return foregroundStream;
					}
					return createDoneStream(model.api);
				},
			);
			try {
				expect(session.extensionRunner.getExtensionPaths()).toEqual(["builtin:usage"]);
				enableCliStreamOptimization(session, mode);
				await session.bindExtensions({ mode: mode === "interactive" ? "tui" : "print" });
				const prompting = session.prompt("test");
				const foreground = await firstRequest.promise;
				// A background request from the same session can overlap a foreground tool call.
				const background = await session.agent.streamFunction(model, normalizeContext({ messages: [] }), {
					sessionId: session.sessionId,
					signal: new AbortController().signal,
				});
				await background.result();
				foregroundStream.end(createDoneMessage(model.api));
				await prompting;

				expect(foreground?.skipToolArgumentPreviews === true).toBe(mode === "print");
				expect(foreground?.skipTextDeltas === true).toBe(mode === "print");
				expect(foreground?.coalesceTextDeltas === true).toBe(mode === "interactive");
				expect(requests[1]?.coalesceTextDeltas).not.toBe(true);
				expect(requests).toHaveLength(2);
				expect(requests[1]?.skipToolArgumentPreviews).not.toBe(true);
				expect(requests[1]?.skipTextDeltas).not.toBe(true);
			} finally {
				foregroundStream.end(createDoneMessage(model.api));
				dispose();
			}
		},
	);

	it("lets request timeoutMs override httpIdleTimeoutMs for OpenAI Codex", async () => {
		const options = await captureStreamOptions(
			"openai-codex-responses",
			{ httpIdleTimeoutMs: 1234 },
			{ timeoutMs: 0 },
		);

		expect(options?.timeoutMs).toBe(0);
	});

	it("forwards websocketConnectTimeoutMs from settings", async () => {
		const options = await captureStreamOptions("openai-codex-responses", { websocketConnectTimeoutMs: 1234 });

		expect(options?.websocketConnectTimeoutMs).toBe(1234);
	});

	it("lets request websocketConnectTimeoutMs override settings", async () => {
		const options = await captureStreamOptions(
			"openai-codex-responses",
			{ websocketConnectTimeoutMs: 1234 },
			{ websocketConnectTimeoutMs: 0 },
		);

		expect(options?.websocketConnectTimeoutMs).toBe(0);
	});

	it("forwards provider retry settings", async () => {
		const options = await captureStreamOptions("openai-completions", {
			retry: { provider: { maxRetries: 2, maxRetryDelayMs: 3000 } },
		});

		expect(options?.maxRetries).toBe(2);
		expect(options?.maxRetryDelayMs).toBe(3000);
	});

	// Regression test for #9784.
	it("forwards provider stream events to extensions", async () => {
		const providerEvent = { openrouter_metadata: { strategy: "direct" } };
		const extensionEvents: unknown[] = [];

		const options = await captureStreamOptions(
			"openai-completions",
			{},
			{},
			(pi) => {
				pi.on("provider_stream_event", (event) => {
					extensionEvents.push(event);
				});
			},
			(emit) => emit(providerEvent),
		);

		expect(options?.onProviderStreamEvent).toEqual(expect.any(Function));
		expect(extensionEvents).toEqual([
			{
				data: providerEvent,
				type: "provider_stream_event",
				provider: "capture-provider",
				api: "openai-completions",
				model: "capture-model",
			},
		]);
	});

	it("returns synchronously from the raw event callback in extensionless sessions", async () => {
		await captureStreamOptions("openai-completions", {}, {}, undefined, (emit) => {
			expect(emit({})).toBeUndefined();
		});
	});

	it("picks up raw event subscriptions and removals during the same stream", async () => {
		const received: string[] = [];
		let subscribe!: () => () => void;
		await captureStreamOptions(
			"openai-completions",
			{},
			{},
			(pi) => {
				subscribe = () => {
					const first = pi.on("provider_stream_event", async (event) => {
						await Promise.resolve();
						received.push(`first ${event.data}`);
					});
					const second = pi.on("provider_stream_event", (event) => {
						received.push(`second ${event.data}`);
					});
					return () => {
						first();
						second();
					};
				};
			},
			async (emit) => {
				expect(emit("before")).toBeUndefined();
				const unsubscribe = subscribe();
				await emit("one");
				await emit("two");
				unsubscribe();
				expect(emit("after")).toBeUndefined();
			},
		);
		expect(received).toEqual(["first one", "second one", "first two", "second two"]);
	});

	it("forwards events to an extension loaded while a foreground prompt is streaming", async () => {
		const model = createModel("openai-completions");
		const received: unknown[] = [];
		const settingsManager = SettingsManager.inMemory({});
		const reloadEntered = Promise.withResolvers<void>();
		const resumeReload = Promise.withResolvers<void>();
		let enableExtension = false;
		const resourceLoader = new DefaultResourceLoader({
			cwd,
			agentDir,
			settingsManager,
			extensionFactories: [
				async (pi) => {
					if (!enableExtension) return;
					reloadEntered.resolve();
					await resumeReload.promise;
					pi.on("provider_stream_event", (event) => {
						received.push(event.data);
					});
				},
			],
			extensionsOverride: (base) => (enableExtension ? base : { ...base, extensions: [] }),
		});
		await resourceLoader.reload();
		const authStorage = AuthStorage.create(join(agentDir, "auth.json"));
		await authStorage.modify(model.provider, async () => ({ type: "api_key", key: "test-api-key" }));
		const modelRegistry = await createModelRegistry(authStorage, join(agentDir, "models.json"));
		const { session } = await createAgentSession({
			cwd,
			agentDir,
			model,
			modelRuntime: getModelRuntime(modelRegistry),
			settingsManager,
			sessionManager: SessionManager.inMemory(cwd),
			resourceLoader,
		});

		const streamOptions = Promise.withResolvers<SimpleStreamOptions | undefined>();
		const stream = createAssistantMessageEventStream();
		try {
			expect(session.extensionRunner.getExtensionPaths()).toHaveLength(0);
			enableExtension = true;
			const reloading = session.reload();
			await reloadEntered.promise;
			modelRegistry.registerProvider(model.provider, {
				api: model.api,
				streamSimple: (_model, _context, options) => {
					streamOptions.resolve(options);
					return stream;
				},
			});
			const prompting = session.prompt("test");
			const options = await streamOptions.promise;
			await options?.onProviderStreamEvent?.("before reload", model);
			resumeReload.resolve();
			await reloading;
			expect(session.extensionRunner.hasHandlers("provider_stream_event")).toBe(true);
			await options?.onProviderStreamEvent?.("after reload", model);
			stream.end(createDoneMessage(model.api));
			await prompting;
			expect(received).toEqual(["after reload"]);
		} finally {
			resumeReload.resolve();
			stream.end(createDoneMessage(model.api));
			session.dispose();
			modelRegistry.unregisterProvider(model.provider);
		}
	});

	it("runs before_provider_headers on assembled headers without forwarding the transform", async () => {
		const options = await captureStreamOptions(
			"openai-completions",
			{},
			{ headers: { "x-explicit": "explicit" } },
			(pi) => {
				pi.on("before_provider_headers", (event) => {
					event.headers["x-hook"] = [
						event.headers["x-provider"],
						event.headers["x-model"],
						event.headers["x-explicit"],
					].join(":");
				});
			},
		);

		expect(options?.headers).toMatchObject({
			"x-provider": "provider",
			"x-model": "model",
			"x-explicit": "explicit",
			"x-hook": "provider:model:explicit",
		});
		expect(options).not.toHaveProperty("transformHeaders");
	});
});
