import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fauxAssistantMessage, registerFauxProvider } from "@earendil-works/pi-ai/compat";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
	type CreateAgentSessionRuntimeFactory,
	createAgentSessionFromServices,
	createAgentSessionServices,
} from "../../src/core/agent-session-runtime.ts";
import { SessionManager } from "../../src/core/session-manager.ts";
import { SettingsManager } from "../../src/core/settings-manager.ts";
import type { RemoteControlSettings } from "../../src/core/settings-schema.ts";
import type { ExtensionAPI } from "../../src/index.ts";
import { RemoteControlController } from "../../src/remote-control/controller.ts";
import { generateKeyPair, type KeyPair } from "../../src/remote-control/noise.ts";
import { CloseCode, decodePairingUri, ENDPOINTS_MESSAGE } from "../../src/remote-control/protocol.ts";
import { RemoteControlServer } from "../../src/remote-control/server.ts";
import { runServiceCommand } from "../../src/remote-control/service-cli.ts";
import { connectControl, controlPaths } from "../../src/remote-control/service-control.ts";
import {
	ServiceAlreadyRunningError,
	ServiceHost,
	type ServiceHostOptions,
} from "../../src/remote-control/service-host.ts";
import { ServiceLog } from "../../src/remote-control/service-log.ts";
import { RemoteControlStore } from "../../src/remote-control/store.ts";
import { TunnelError } from "../../src/remote-control/tunnel.ts";
import { assistantMsg, userMsg } from "../utilities.ts";
import { createHarness } from "./harness.ts";
import { connectTestClient, type TestClient } from "./remote-control-client.ts";

describe("remote control background service", () => {
	const cleanups: Array<() => unknown> = [];

	afterEach(async () => {
		while (cleanups.length > 0) await cleanups.pop()?.();
	});

	function tempDir(prefix: string): string {
		// (realpath: macOS's temp folder is reached through a /var -> /private/var link, and sessions store real paths.)
		const dir = realpathSync(mkdtempSync(join(tmpdir(), prefix)));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		return dir;
	}

	function freePort(): Promise<number> {
		return new Promise((resolve) => {
			const server = createServer();
			server.listen(0, "127.0.0.1", () => {
				const address = server.address();
				server.close(() => resolve(typeof address === "object" && address ? address.port : 0));
			});
		});
	}

	async function until(condition: () => boolean, what: string, timeoutMs = 5000): Promise<void> {
		const deadline = Date.now() + timeoutMs;
		while (!condition()) {
			if (Date.now() > deadline) throw new Error(`Timed out waiting for ${what}`);
			await new Promise((resolve) => setTimeout(resolve, 20));
		}
	}

	/** Remote control on (pi-bolt remote-control enable): `pi-bolt service pair` refuses otherwise. */
	function turnOn(agentDir: string): void {
		writeFileSync(join(agentDir, "settings.json"), JSON.stringify({ remoteControl: { enabled: true } }));
	}

	/** The faux provider and the runtime factory main.ts would pass, without real providers or keys. */
	async function runtimeFactory(agentDir: string): Promise<{
		createRuntime: CreateAgentSessionRuntimeFactory;
		faux: ReturnType<typeof registerFauxProvider>;
	}> {
		const faux = registerFauxProvider({ models: [{ id: "faux-1", reasoning: false }] });
		cleanups.push(() => faux.unregister());
		const createRuntime: CreateAgentSessionRuntimeFactory = async ({ cwd, sessionManager, sessionStartEvent }) => {
			const services = await createAgentSessionServices({
				cwd,
				agentDir,
				resourceLoaderOptions: {
					extensionFactories: [
						(pi: ExtensionAPI) => {
							const model = faux.getModel();
							pi.registerProvider(model.provider, {
								baseUrl: model.baseUrl,
								apiKey: "faux-key",
								api: faux.api,
								models: faux.models.map((registered) => ({
									id: registered.id,
									name: registered.name,
									api: registered.api,
									reasoning: registered.reasoning,
									input: registered.input,
									cost: registered.cost,
									contextWindow: registered.contextWindow,
									maxTokens: registered.maxTokens,
								})),
							});
						},
					],
					noSkills: true,
					noPromptTemplates: true,
					noThemes: true,
					noContextFiles: true,
				},
			});
			return {
				...(await createAgentSessionFromServices({
					services,
					sessionManager,
					sessionStartEvent,
					model: faux.getModel(),
				})),
				services,
				diagnostics: services.diagnostics,
			};
		};
		return { createRuntime, faux };
	}

	/** A saved session in `cwd`: one question and its answer. */
	async function seedSession(sessionDir: string, cwd: string, question: string): Promise<string> {
		const manager = SessionManager.create(cwd, sessionDir);
		manager.appendMessage(userMsg(question));
		manager.appendMessage(assistantMsg(`answer to ${question}`));
		// The newest session is the one with the latest entry.
		await new Promise((resolve) => setTimeout(resolve, 15));
		return manager.getSessionFile() as string;
	}

	async function setup(
		options: { settings?: RemoteControlSettings; host?: Partial<ServiceHostOptions>; start?: boolean } = {},
	) {
		const agentDir = tempDir("pi-svc-agent-");
		turnOn(agentDir);
		const sessionDir = join(agentDir, "sessions-test");
		mkdirSync(sessionDir, { recursive: true });
		const older = tempDir("pi-svc-older-");
		const newer = tempDir("pi-svc-newer-");
		const olderSession = await seedSession(sessionDir, older, "older question");
		const newerSession = await seedSession(sessionDir, newer, "newer question");
		const { createRuntime, faux } = await runtimeFactory(agentDir);
		const port = await freePort();
		const host = new ServiceHost({
			createRuntime,
			agentDir,
			sessionDir,
			settings: { port, tunnel: "off", ...options.settings },
			log: new ServiceLog(agentDir),
			version: "9.9.9",
			hostName: "test-host",
			bindAddress: "127.0.0.1",
			offline: true,
			endpoints: (listening) => [`ws://127.0.0.1:${listening}/rc`],
			retryMs: 50,
			firstFrameTimeoutMs: 1000,
			...options.host,
		});
		cleanups.push(() => host.stop());
		if (options.start !== false) await host.start();
		return { agentDir, sessionDir, older, newer, olderSession, newerSession, host, faux, port };
	}

	async function pairingUri(agentDir: string): Promise<string> {
		const client = await connectControl(controlPaths(agentDir));
		if (!client) throw new Error("No control channel");
		const reply = await client.request({ type: "pair" });
		// The connection stays open to hear `paired`; the test does not need it.
		cleanups.push(() => client.close());
		if (reply?.ok !== true) throw new Error(`pair failed: ${JSON.stringify(reply)}`);
		return String(reply.uri);
	}

	async function pairPhone(
		agentDir: string,
		device = "Pixel",
	): Promise<{ phone: TestClient; key: KeyPair; uri: string }> {
		const uri = await pairingUri(agentDir);
		const key = generateKeyPair();
		const phone = track(await connectTestClient(uri, key, { device }));
		return { phone, key, uri };
	}

	function track(client: TestClient): TestClient {
		cleanups.push(() => client.close());
		return client;
	}

	const helloOf = (client: TestClient, index = 0) =>
		client.received.filter((message) => message.type === "remote_hello")[index] as
			| { host: { cwd: string; service?: boolean }; messages: unknown[] }
			| undefined;

	it("serves a paired phone: opens the most recent session, says it is the service, and sends its addresses", async () => {
		const { agentDir, newer, port, faux } = await setup();
		const { phone } = await pairPhone(agentDir);
		const hello = helloOf(phone);
		expect(hello?.host).toMatchObject({ cwd: newer, service: true });
		expect(hello?.messages).toHaveLength(2);
		const endpoints = await phone.waitFor((message) => message.type === ENDPOINTS_MESSAGE);
		expect(endpoints).toEqual({ type: ENDPOINTS_MESSAGE, endpoints: [`ws://127.0.0.1:${port}/rc`] });

		faux.setResponses([fauxAssistantMessage("hello from the service")]);
		const response = await phone.request({ type: "prompt", message: "hi" });
		expect(response.success).toBe(true);
		await phone.waitFor((message) => message.type === "agent_end");
		const last = await phone.request({ type: "get_last_assistant_text" });
		expect((last.data as { text: string }).text).toBe("hello from the service");
	});

	it("lists projects and sessions, opens another session, and starts sessions only in projects used before", async () => {
		const { agentDir, older, newer, olderSession } = await setup();
		const { phone } = await pairPhone(agentDir);

		const projects = await phone.request({ type: "list_projects" });
		expect((projects.data as { projects: Array<{ cwd: string; sessions: number }> }).projects).toEqual([
			expect.objectContaining({ cwd: newer, sessions: 1 }),
			expect.objectContaining({ cwd: older, sessions: 1 }),
		]);
		const sessions = await phone.request({ type: "list_sessions", cwd: older });
		const listed = (sessions.data as { sessions: Array<{ path: string; preview: string; messageCount: number }> })
			.sessions;
		expect(listed).toEqual([expect.objectContaining({ path: olderSession, preview: "older question" })]);

		const switched = await phone.request({ type: "switch_session", sessionPath: olderSession });
		expect(switched).toMatchObject({ success: true, data: { cancelled: false } });
		await until(() => helloOf(phone, 1) !== undefined, "the second remote_hello");
		expect(helloOf(phone, 1)?.host.cwd).toBe(older);

		const fresh = await phone.request({ type: "new_session", cwd: newer });
		expect(fresh).toMatchObject({ success: true, data: { cancelled: false } });
		await until(() => helloOf(phone, 2) !== undefined, "the third remote_hello");
		expect(helloOf(phone, 2)).toMatchObject({ host: { cwd: newer }, messages: [] });

		const elsewhere = await phone.request({ type: "new_session", cwd: tmpdir() });
		expect(elsewhere.success).toBe(false);
		expect(String(elsewhere.error)).toContain("is not a project Pi-Bolt has worked in");
		const unknownFile = await phone.request({ type: "switch_session", sessionPath: "/etc/passwd" });
		expect(unknownFile).toMatchObject({ success: false, error: "No such session on this computer" });
	});

	it.each([false, true])(
		"serves the read-only review commands in the open session's folder (readSecretFiles: %s)",
		async (readSecretFiles) => {
			const { agentDir, newer } = await setup({ settings: { readSecretFiles } });
			writeFileSync(join(newer, "notes.md"), "# notes\n");
			writeFileSync(join(newer, ".env"), "TOKEN=1\n");
			const { phone } = await pairPhone(agentDir);
			expect(helloOf(phone)?.host).toMatchObject({ cwd: newer, service: true, review: true });
			const listing = await phone.request({ type: "rc_list_dir" });
			const names = (listing.data as { entries: Array<{ name: string }> }).entries.map((entry) => entry.name);
			expect(names).toEqual(expect.arrayContaining([".env", "notes.md"]));
			const notes = await phone.request({ type: "rc_read_file", path: "notes.md" });
			expect(notes).toMatchObject({ success: true, data: { content: "# notes\n" } });
			const secret = await phone.request({ type: "rc_read_file", path: ".env" });
			expect(secret.success).toBe(readSecretFiles);
		},
	);

	describe("several sessions at once", () => {
		/** A faux response that waits for `release()`: a turn that runs until the test lets it end. */
		function gated(text: string) {
			let release = () => {};
			const gate = new Promise<void>((resolve) => {
				release = resolve;
			});
			const respond = async () => {
				await gate;
				return fauxAssistantMessage(text);
			};
			return { respond, release };
		}

		type Listed = { path: string; open?: boolean; running?: boolean };
		async function listed(phone: TestClient, cwd: string): Promise<Listed[]> {
			const response = await phone.request({ type: "list_sessions", cwd });
			return (response.data as { sessions: Listed[] }).sessions;
		}

		const helloCount = (client: TestClient) => client.received.filter((m) => m.type === "remote_hello").length;

		it("says so in remote_hello", async () => {
			const { agentDir } = await setup();
			const { phone } = await pairPhone(agentDir);
			expect(helloOf(phone)?.host).toMatchObject({ service: true, parallelSessions: true });
		});

		it("keeps a running turn going when the phone opens another session, and shows it again as it is", async () => {
			const { agentDir, older, newer, olderSession, newerSession, faux } = await setup();
			const { phone } = await pairPhone(agentDir);
			const long = gated("long task done");
			faux.setResponses([long.respond]);
			expect((await phone.request({ type: "prompt", message: "long task" })).success).toBe(true);
			await phone.waitFor((message) => message.type === "agent_start");

			const switched = await phone.request({ type: "switch_session", sessionPath: olderSession });
			expect(switched).toMatchObject({ success: true, data: { cancelled: false } });
			await until(() => helloOf(phone, 1) !== undefined, "the second remote_hello");
			expect(helloOf(phone, 1)?.host.cwd).toBe(older);
			expect(await listed(phone, newer)).toEqual([
				expect.objectContaining({ path: newerSession, open: true, running: true }),
			]);
			expect((await listed(phone, older))[0]).toMatchObject({ path: olderSession, open: true });
			expect((await listed(phone, older))[0]?.running).toBeUndefined();

			// A new session in the running one's project leaves it running too.
			expect((await phone.request({ type: "new_session", cwd: newer })).success).toBe(true);
			await until(() => helloOf(phone, 2) !== undefined, "the third remote_hello");

			long.release();
			await until(() => readFileSync(newerSession, "utf8").includes("long task done"), "the turn to finish");
			expect(readFileSync(newerSession, "utf8")).not.toContain('"stopReason":"aborted"');
			const before = helloCount(phone);
			expect((await phone.request({ type: "switch_session", sessionPath: newerSession })).success).toBe(true);
			await until(() => helloCount(phone) > before, "the session's remote_hello");
			const back = phone.received.filter((m) => m.type === "remote_hello").at(-1) as {
				host: { cwd: string };
				messages: Array<{ role: string }>;
			};
			expect(back.host.cwd).toBe(newer);
			expect(back.messages.filter((message) => message.role === "user")).toHaveLength(2);
			// The same runtime, shown again: the first session, the older one and the new one were opened, nothing more.
			const log = readFileSync(join(agentDir, "remote-control", "service.log"), "utf8");
			expect(log.match(/Opened a session in /g)).toHaveLength(3);
			expect(log).toContain(`Opened a session in ${older} (the one in ${newer} keeps running)`);
		});

		it("opens at most maxOpenSessions: an idle background session makes room, running ones are never closed", async () => {
			const { agentDir, older, newer, olderSession, newerSession, faux } = await setup({
				host: { maxOpenSessions: 2 },
			});
			const { phone } = await pairPhone(agentDir);
			const first = gated("first done");
			const second = gated("second done");
			faux.setResponses([first.respond, second.respond]);
			await phone.request({ type: "prompt", message: "first" });
			await phone.waitFor((message) => message.type === "agent_start");
			await phone.request({ type: "switch_session", sessionPath: olderSession });
			await until(() => helloOf(phone, 1) !== undefined, "the second remote_hello");
			await phone.request({ type: "prompt", message: "second" });
			await phone.waitFor((message) => message.type === "agent_start");

			const refused = await phone.request({ type: "new_session", cwd: newer });
			expect(refused).toMatchObject({
				success: false,
				error: "2 sessions are running on this computer. Stop one, or wait.",
			});
			const refusedSwitch = await phone.request({ type: "new_session", cwd: older });
			expect(refusedSwitch.success).toBe(false);

			first.release();
			await until(() => readFileSync(newerSession, "utf8").includes("first done"), "the first turn to finish");
			const fresh = await phone.request({ type: "new_session", cwd: newer });
			expect(fresh).toMatchObject({ success: true, data: { cancelled: false } });
			// The idle one closed for it; the running one is still open and running.
			expect((await listed(phone, newer)).find((s) => s.path === newerSession)?.open).toBeUndefined();
			expect((await listed(phone, older))[0]).toMatchObject({ path: olderSession, open: true, running: true });

			second.release();
			await until(() => readFileSync(olderSession, "utf8").includes("second done"), "the second turn to finish");
			expect(readFileSync(olderSession, "utf8")).not.toContain('"stopReason":"aborted"');
		});

		it("closes a background session once it has been idle for idleDisposeMs, not while it runs", async () => {
			const { agentDir, newer, olderSession, newerSession, faux } = await setup({
				host: { idleDisposeMs: 100, idleCheckMs: 20 },
			});
			const { phone } = await pairPhone(agentDir);
			const long = gated("done");
			faux.setResponses([long.respond]);
			await phone.request({ type: "prompt", message: "long" });
			await phone.waitFor((message) => message.type === "agent_start");
			await phone.request({ type: "switch_session", sessionPath: olderSession });
			await until(() => helloOf(phone, 1) !== undefined, "the second remote_hello");
			await new Promise((resolve) => setTimeout(resolve, 300));
			expect((await listed(phone, newer))[0]).toMatchObject({ path: newerSession, open: true, running: true });

			long.release();
			await until(() => readFileSync(newerSession, "utf8").includes("done"), "the turn to finish");
			const deadline = Date.now() + 5000;
			while ((await listed(phone, newer))[0]?.open) {
				if (Date.now() > deadline) throw new Error("The idle background session stayed open");
				await new Promise((resolve) => setTimeout(resolve, 50));
			}
		});
	});

	it("refuses phones that are not paired and drops one revoked meanwhile, even while it sends nothing", async () => {
		const { agentDir } = await setup({ host: { pollMs: 50 } });
		const { phone, key, uri } = await pairPhone(agentDir);
		await expect(connectTestClient(uri, generateKeyPair(), { pair: false })).rejects.toThrow(
			`closed ${CloseCode.NotAuthorized}`,
		);
		// `/remote-control devices` in a terminal edits the same store.
		new RemoteControlStore(agentDir).removeDevice(key.publicKey.toString("base64url"));
		expect(await phone.closed).toBe(CloseCode.NotAuthorized);
		await expect(connectTestClient(uri, key, { pair: false })).rejects.toThrow(`closed ${CloseCode.NotAuthorized}`);
	});

	it("pushes a new tunnel address to connected phones; a client that does not know the message ignores it", async () => {
		let started = 0;
		const exits: Array<(message: string) => void> = [];
		const { agentDir, port, faux } = await setup({
			settings: { tunnel: "quick" },
			host: {
				startTunnel: async (_port, _path, _settings, opts) => {
					started++;
					if (opts?.onExit) exits.push(opts.onExit);
					return { endpoint: `wss://tunnel-${started}.trycloudflare.com/rc`, stop: () => {} };
				},
			},
		});
		const { phone } = await pairPhone(agentDir);
		const first = await phone.waitFor(
			(message) => message.type === ENDPOINTS_MESSAGE && JSON.stringify(message).includes("tunnel-1"),
		);
		expect(first.endpoints).toEqual([`ws://127.0.0.1:${port}/rc`, "wss://tunnel-1.trycloudflare.com/rc"]);
		// It is no RPC response and carries no id: a client that handles only known types skips it.
		expect(first.id).toBeUndefined();

		// The quick tunnel dies; the service starts a new one at a new address and tells the phone.
		exits[0]?.("cloudflared exited with code 1");
		const second = await phone.waitFor(
			(message) => message.type === ENDPOINTS_MESSAGE && JSON.stringify(message).includes("tunnel-2"),
		);
		expect(second.endpoints).toEqual([`ws://127.0.0.1:${port}/rc`, "wss://tunnel-2.trycloudflare.com/rc"]);

		// An older app: everything it knows still works around the new message.
		const known = new Set(["remote_hello", "response", "agent_start", "agent_end", "message_start", "message_end"]);
		const unknown = phone.received.filter((message) => !known.has(String(message.type)));
		expect(unknown.some((message) => message.type === ENDPOINTS_MESSAGE)).toBe(true);
		faux.setResponses([fauxAssistantMessage("still fine")]);
		expect((await phone.request({ type: "prompt", message: "ok?" })).success).toBe(true);
		await phone.waitFor((message) => message.type === "agent_end");
	});

	it("serves the local network only when cloudflared is missing, and never asks", async () => {
		const { agentDir, host, port } = await setup({
			settings: { tunnel: "quick" },
			host: {
				startTunnel: async () => {
					throw new TunnelError("cloudflared was not found.", true);
				},
			},
		});
		await until(() => host.status().tunnel.state === "missing", "the missing tunnel");
		expect(host.status().endpoints).toEqual([`ws://127.0.0.1:${port}/rc`]);
		expect(readFileSync(join(agentDir, "remote-control", "service.log"), "utf8")).toContain(
			"cloudflared was not found: serving the local network and Tailscale only",
		);
	});

	describe("with a terminal's /remote-control", () => {
		async function terminal(agentDir: string, settings: RemoteControlSettings, startTunnel?: () => Promise<never>) {
			const harness = await createHarness();
			cleanups.push(() => harness.cleanup());
			const lines: string[][] = [];
			const warnings: string[] = [];
			let tunnelsStarted = 0;
			const controller = new RemoteControlController(
				{
					getSession: () => harness.session,
					settingsManager: SettingsManager.inMemory({ remoteControl: { enabled: true, port: 0, ...settings } }),
					agentDir,
					version: "9.9.9",
					showStatus: () => {},
					showWarning: (message) => warnings.push(message),
					showError: (message) => {
						throw new Error(message);
					},
					showLines: (block) => lines.push(block),
					setStatus: () => {},
					select: async () => undefined,
					copyToClipboard: async () => {},
					columns: () => 200,
					rows: () => 80,
					bindAddress: "127.0.0.1",
				},
				{
					startTunnel:
						startTunnel ??
						(async () => {
							tunnelsStarted++;
							throw new TunnelError("cloudflared was not found.", true);
						}),
					installCloudflared: async () => "not in tests",
					platform: "darwin",
				},
			);
			cleanups.push(() => controller.stop());
			return { controller, lines, warnings, tunnelsStarted: () => tunnelsStarted };
		}

		it("takes the service's port and tunnel, then hands them back", async () => {
			const stopped: string[] = [];
			let started = 0;
			const { agentDir, host, port } = await setup({
				settings: { tunnel: "quick" },
				host: {
					startTunnel: async () => {
						started++;
						const endpoint = `wss://service-${started}.trycloudflare.com/rc`;
						return { endpoint, stop: () => stopped.push(endpoint) };
					},
				},
			});
			await until(() => host.status().tunnel.state === "up", "the service's tunnel");
			const { phone, key, uri } = await pairPhone(agentDir);
			expect(helloOf(phone)?.host.service).toBe(true);

			const { controller, tunnelsStarted } = await terminal(agentDir, { tunnel: "quick" });
			await controller.handleCommand("");
			// Phones are told to come back at once (1012), to the same port, where the terminal now listens.
			expect(await phone.closed).toBe(CloseCode.Handoff);
			expect(host.state).toBe("yielded");
			const terminalCode = decodePairingUri(controller.pairingUri ?? "");
			expect(terminalCode.e.at(-1)).toBe("wss://service-1.trycloudflare.com/rc");
			expect(terminalCode.e[0]).toMatch(new RegExp(`:${port}/rc$`));
			expect(tunnelsStarted()).toBe(0);
			const again = track(await connectTestClient(uri, key, { pair: false }));
			expect(helloOf(again)?.host.service).toBeUndefined();

			// A pairing code from the service makes no sense now: the terminal shows its own.
			const client = await connectControl(controlPaths(agentDir));
			const refused = await client?.request({ type: "pair" });
			client?.close();
			expect(refused).toMatchObject({ ok: false });

			// Local only in the terminal: the service's tunnel stops too.
			await controller.handleCommand("lan");
			await until(() => stopped.length === 1, "the service's tunnel to stop");

			await controller.stop();
			await until(() => host.state === "serving", "the service to listen again");
			await until(() => host.status().tunnel.state === "up", "a new tunnel");
			expect(started).toBe(2);
			const back = track(await connectTestClient(uri, key, { pair: false }));
			expect(helloOf(back)?.host.service).toBe(true);
		});

		it("keeps the service's tunnel when the terminal stops, so its address does not change", async () => {
			let started = 0;
			const { agentDir, host } = await setup({
				settings: { tunnel: "quick" },
				host: {
					startTunnel: async () => {
						started++;
						return { endpoint: `wss://stable-${started}.trycloudflare.com/rc`, stop: () => {} };
					},
				},
			});
			await until(() => host.status().tunnel.state === "up", "the service's tunnel");
			const { controller } = await terminal(agentDir, { tunnel: "quick" });
			await controller.handleCommand("");
			expect(host.state).toBe("yielded");
			await controller.stop();
			await until(() => host.state === "serving", "the service to listen again");
			expect(host.status().tunnel).toEqual({ state: "up", endpoint: "wss://stable-1.trycloudflare.com/rc" });
			expect(started).toBe(1);
		});

		it("waits while a terminal already serves remote control, then serves when it stops", async () => {
			const { agentDir, host } = await setup({ start: false });
			const { controller } = await terminal(agentDir, { tunnel: "off" });
			await controller.handleCommand("");
			const starting = host.start();
			await until(() => host.state === "waiting", "the service to wait");
			const client = await connectControl(controlPaths(agentDir));
			const reply = await client?.request({ type: "status" });
			client?.close();
			expect(reply).toMatchObject({ ok: true, role: "terminal", pid: process.pid });
			await controller.stop();
			await starting;
			expect(host.state).toBe("serving");
		});

		describe("turned off while it does not serve: it never listens nor starts a tunnel again", () => {
			/** A host whose setting the test turns off; counts its listens (after `watch()`) and its tunnels. */
			async function offSetup(start: boolean) {
				let enabled = true;
				let tunnels = 0;
				const turnedOff = vi.fn();
				const { agentDir, host, port } = await setup({
					start,
					settings: { tunnel: "quick" },
					host: {
						pollMs: 20,
						isEnabled: () => enabled,
						onTurnedOff: turnedOff,
						startTunnel: async () => {
							tunnels++;
							return { endpoint: `wss://t-${tunnels}.trycloudflare.com/rc`, stop: () => {} };
						},
					},
				});
				let listens: ReturnType<typeof vi.spyOn> | undefined;
				cleanups.push(() => listens?.mockRestore());
				return {
					agentDir,
					host,
					port,
					turnedOff,
					tunnels: () => tunnels,
					listens: () => listens?.mock.calls.length ?? 0,
					watch: () => {
						listens = vi.spyOn(RemoteControlServer.prototype, "start");
					},
					disable: () => {
						enabled = false;
					},
					turnOff: async () => {
						enabled = false;
						await until(() => turnedOff.mock.calls.length > 0, "the host to see the setting off");
					},
				};
			}

			/** True when nothing listens on the port (the test can take it). */
			function portFree(port: number): Promise<boolean> {
				return new Promise((resolve) => {
					const server = createServer();
					server.once("error", () => resolve(false));
					server.listen(port, "127.0.0.1", () => server.close(() => resolve(true)));
				});
			}

			it("with its port handed to a terminal's /remote-control", async () => {
				const off = await offSetup(true);
				await until(() => off.host.status().tunnel.state === "up", "the service's tunnel");
				const { controller } = await terminal(off.agentDir, { tunnel: "quick" });
				await controller.handleCommand("");
				expect(off.host.state).toBe("yielded");
				off.watch();
				await off.turnOff();
				await controller.stop();
				await new Promise((resolve) => setTimeout(resolve, 300));
				expect(off.listens()).toBe(0);
				expect(off.tunnels()).toBe(1);
				expect(off.host.state).not.toBe("serving");
				expect(await portFree(off.port)).toBe(true);
				expect(off.turnedOff).toHaveBeenCalledOnce();
			});

			it("while it waits for a terminal that served when it started", async () => {
				const off = await offSetup(false);
				const { controller } = await terminal(off.agentDir, { tunnel: "off" });
				await controller.handleCommand("");
				off.watch();
				const starting = off.host.start();
				await until(() => off.host.state === "waiting", "the service to wait");
				await off.turnOff();
				await controller.stop();
				await starting;
				await new Promise((resolve) => setTimeout(resolve, 200));
				expect(off.listens()).toBe(0);
				expect(off.tunnels()).toBe(0);
				expect(off.host.state).not.toBe("serving");
				expect(await portFree(off.port)).toBe(true);
			});

			it("while its port is busy", async () => {
				const off = await offSetup(false);
				const blocker = createServer();
				await new Promise<void>((resolve) => blocker.listen(off.port, "127.0.0.1", () => resolve()));
				off.watch();
				const starting = off.host.start();
				await until(() => off.listens() >= 2, "the service to try the busy port");
				await off.turnOff();
				const tries = off.listens();
				await new Promise<void>((resolve) => blocker.close(() => resolve()));
				await starting;
				await new Promise((resolve) => setTimeout(resolve, 200));
				expect(off.listens()).toBe(tries);
				expect(off.tunnels()).toBe(0);
				expect(off.host.state).not.toBe("serving");
				expect(await portFree(off.port)).toBe(true);
			});

			it("already off when it starts", async () => {
				const off = await offSetup(false);
				off.watch();
				off.disable();
				await off.host.start();
				expect(off.listens()).toBe(0);
				expect(off.tunnels()).toBe(0);
				expect(off.turnedOff).toHaveBeenCalledOnce();
			});
		});
	});

	it("does not run twice for one agent directory", async () => {
		const { agentDir, host } = await setup();
		const { createRuntime } = await runtimeFactory(agentDir);
		const second = new ServiceHost({
			createRuntime,
			agentDir,
			settings: { port: host.port, tunnel: "off" },
			log: new ServiceLog(agentDir),
			retryMs: 50,
		});
		cleanups.push(() => second.stop());
		await expect(second.start()).rejects.toBeInstanceOf(ServiceAlreadyRunningError);
	});

	describe("pi-bolt service status and pair", () => {
		const system = (home: string, isTTY: boolean) => ({
			platform: "darwin" as const,
			home,
			uid: 501,
			isTTY,
			run: async () => ({ code: 0, stdout: "state = running", stderr: "" }),
			sleep: (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, Math.min(ms, 20))),
		});

		it("prints what the service does and a code that gives paired phones its addresses without pairing", async () => {
			const { agentDir, port, host } = await setup();
			await pairPhone(agentDir, "Pixel 9");
			const home = tempDir("pi-svc-home-");
			const lines: string[] = [];
			expect(
				await runServiceCommand(["status"], {
					agentDir,
					out: (line) => lines.push(line),
					system: system(home, false),
				}),
			).toBe(0);
			expect(lines).toContain("Not installed (pi service install starts it at every login).");
			expect(
				lines.some((line) =>
					/^Remote control service: serving paired phones \(pid \d+, Pi-Bolt 9\.9\.9\)$/.test(line),
				),
			).toBe(true);
			expect(lines).toContain(`  local      ws://127.0.0.1:${port}/rc`);
			expect(lines).toContain("  Tunnel: off (remoteControl.service.tunnel)");
			expect(lines).toContain("  Phones: 1 paired, connected: Pixel 9");
			const code = decodePairingUri(lines.at(-1) ?? "");
			expect(code).toMatchObject({ n: "test-host", e: [`ws://127.0.0.1:${port}/rc`], x: 0 });
			expect(code.k).toBe(host.status().hostKey);
			expect(Buffer.from(code.s, "base64url").equals(Buffer.alloc(32))).toBe(true);

			const tty: string[] = [];
			await runServiceCommand(["status"], { agentDir, out: (line) => tty.push(line), system: system(home, true) });
			expect(tty.filter((line) => line.includes("▀")).length).toBeGreaterThan(10);
			expect(tty.at(-1)).toContain("Scan with a paired phone to give it these addresses; it pairs nothing.");
		});

		it("lists a phone that reconnected from another network once, not once per socket", async () => {
			const { agentDir } = await setup();
			const { phone: lan, key, uri } = await pairPhone(agentDir, "motorola edge 60 pro");
			const tunnel = track(await connectTestClient(uri, key, { pair: false, device: "motorola edge 60 pro" }));
			expect(await lan.closed).toBe(CloseCode.Replaced);
			const lines: string[] = [];
			await runServiceCommand(["status"], {
				agentDir,
				out: (line) => lines.push(line),
				system: system(tempDir("pi-svc-home-"), false),
			});
			expect(lines).toContain("  Phones: 1 paired, connected: motorola edge 60 pro");
			expect((await tunnel.request({ type: "get_state" })).success).toBe(true);
			const log = () => readFileSync(join(agentDir, "remote-control", "service.log"), "utf8");
			await until(() => log().includes("Disconnected motorola edge 60 pro"), "the old socket's disconnect");
		});

		it("pairs a new phone with the running service from a terminal", async () => {
			const { agentDir } = await setup();
			const lines: string[] = [];
			const pairing = runServiceCommand(["pair", "--link"], {
				agentDir,
				out: (line) => lines.push(line),
				system: system(tempDir("pi-svc-home-"), true),
			});
			await until(() => lines.some((line) => line.startsWith("pibolt://pair#")), "the pairing link");
			expect(lines[0]).toBe("Pair a phone: scan with the Pi-Bolt app");
			expect(lines.filter((line) => line.includes("▀")).length).toBeGreaterThan(10);
			const uri = lines.find((line) => line.startsWith("pibolt://pair#")) as string;
			track(await connectTestClient(uri, generateKeyPair(), { device: "New phone" }));
			expect(await pairing).toBe(0);
			expect(lines.at(-1)).toBe("Paired New phone.");
			expect(new RemoteControlStore(agentDir).devices().map((device) => device.name)).toEqual(["New phone"]);
			// The log says what happened, never the secret.
			const log = readFileSync(join(agentDir, "remote-control", "service.log"), "utf8");
			expect(log).toContain("Paired New phone");
			expect(log).not.toContain(decodePairingUri(uri).s);
		});

		it("says the service is not running when nothing answers", async () => {
			const agentDir = tempDir("pi-svc-none-");
			turnOn(agentDir);
			const lines: string[] = [];
			expect(await runServiceCommand(["pair"], { agentDir, out: (line) => lines.push(line) })).toBe(1);
			expect(lines[0]).toContain("The service is not running.");
		});
	});
});
