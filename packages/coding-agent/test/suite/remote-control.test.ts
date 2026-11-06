import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { request } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { SettingsManager } from "../../src/core/settings-manager.ts";
import { RemoteControlController } from "../../src/remote-control/controller.ts";
import { generateKeyPair } from "../../src/remote-control/noise.ts";
import { CloseCode, decodePairingUri, encodePairingUri } from "../../src/remote-control/protocol.ts";
import { RemoteControlServer } from "../../src/remote-control/server.ts";
import { RemoteControlStore } from "../../src/remote-control/store.ts";
import { startTunnel } from "../../src/remote-control/tunnel.ts";
import { createHarness, type Harness } from "./harness.ts";
import { connectTestClient, type TestClient } from "./remote-control-client.ts";

describe("remote control", () => {
	const cleanups: Array<() => unknown> = [];

	afterEach(async () => {
		while (cleanups.length > 0) await cleanups.pop()?.();
	});

	function tempDir(): string {
		const dir = mkdtempSync(join(tmpdir(), "pi-remote-"));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		return dir;
	}

	async function setup(
		options: {
			tools?: AgentTool[];
			pairingTtlMs?: number;
			firstFrameTimeoutMs?: number;
			helloBudgetBytes?: number;
		} = {},
	) {
		const harness: Harness = await createHarness({ tools: options.tools });
		cleanups.push(() => harness.cleanup());
		const store = new RemoteControlStore(tempDir());
		const server = new RemoteControlServer({
			store,
			getSession: () => harness.session,
			hostName: "test-host",
			version: "9.9.9",
			bindAddress: "127.0.0.1",
			pairingTtlMs: options.pairingTtlMs,
			firstFrameTimeoutMs: options.firstFrameTimeoutMs,
			helloBudgetBytes: options.helloBudgetBytes,
		});
		const port = await server.start();
		cleanups.push(() => server.stop());
		const endpoint = `ws://127.0.0.1:${port}/rc`;
		const uri = () => server.pairingUri([endpoint]);
		return { harness, store, server, port, endpoint, uri };
	}

	function track(client: TestClient): TestClient {
		cleanups.push(() => client.close());
		return client;
	}

	it("pairs with the QR secret, then reconnects as a known device without it", async () => {
		const { store, uri } = await setup();
		const phone = generateKeyPair();
		const first = track(await connectTestClient(uri(), phone, { device: "Pixel" }));
		expect(first.welcome).toEqual({ host: "test-host", version: "9.9.9", paired: true });
		const hello = await first.waitFor((message) => message.type === "remote_hello");
		expect(hello.protocol).toBe(1);
		expect((hello.host as { name: string }).name).toBe("test-host");
		expect(store.devices().map((device) => device.name)).toEqual(["Pixel"]);
		first.close();
		await first.closed;

		const again = track(await connectTestClient(uri(), phone, { pair: false }));
		expect(again.welcome.paired).toBe(false);
		await again.waitFor((message) => message.type === "remote_hello");
	});

	it("refuses unknown devices, wrong, reused and expired secrets without sending anything", async () => {
		const { uri, server } = await setup();
		const pairing = decodePairingUri(uri());
		const wrongSecret = Buffer.alloc(32, 1).toString("base64url");
		await expect(connectTestClient(uri(), generateKeyPair(), { secret: wrongSecret })).rejects.toThrow(
			`closed ${CloseCode.NotAuthorized}`,
		);
		await expect(connectTestClient(uri(), generateKeyPair(), { pair: false })).rejects.toThrow(
			`closed ${CloseCode.NotAuthorized}`,
		);

		// The secret pairs one phone only.
		track(await connectTestClient(uri(), generateKeyPair()));
		await expect(
			connectTestClient(encodePairingUri(pairing), generateKeyPair(), { secret: pairing.s }),
		).rejects.toThrow(`closed ${CloseCode.NotAuthorized}`);

		// A QR code shown after the secret expired carries a fresh one.
		server.newPairing(Date.now() - 11 * 60_000);
		await expect(connectTestClient(server.pairingUri([pairing.e[0]]), generateKeyPair())).resolves.toBeDefined();
	});

	it("rejects a pairing secret after it expires", async () => {
		const { uri } = await setup({ pairingTtlMs: 1 });
		const code = uri();
		await new Promise((resolve) => setTimeout(resolve, 20));
		await expect(connectTestClient(code, generateKeyPair())).rejects.toThrow(`closed ${CloseCode.NotAuthorized}`);
	});

	it("strips control characters from the device name it shows", async () => {
		const { store, uri } = await setup();
		const phone = track(await connectTestClient(uri(), generateKeyPair(), { device: "\u001b[31mEvil\u0007 phone" }));
		await phone.waitFor((message) => message.type === "remote_hello");
		expect(store.devices()[0]?.name).toBe("[31mEvil phone");
	});

	function openIdle(endpoint: string): Promise<WebSocket> {
		const socket = new WebSocket(endpoint, "pibolt-rc.1");
		cleanups.push(() => socket.terminate());
		return new Promise((resolve, reject) => {
			socket.on("open", () => resolve(socket));
			socket.on("error", reject);
		});
	}

	it("closes a socket that sends no handshake within the first-frame deadline", async () => {
		const { endpoint } = await setup({ firstFrameTimeoutMs: 150 });
		const idle = await openIdle(endpoint);
		const code = await new Promise<number>((resolve) => idle.on("close", resolve));
		expect(code).toBe(CloseCode.HandshakeTimeout);
	});

	it("treats a replayed handshake message 1 as nothing: no hello, no events, closed at the deadline", async () => {
		const { server, endpoint, uri } = await setup({ firstFrameTimeoutMs: 300 });
		let recorded: Buffer | undefined;
		const phone = track(
			await connectTestClient(uri(), generateKeyPair(), {
				onMessage1: (frame) => {
					recorded = frame;
				},
			}),
		);
		await phone.waitFor((message) => message.type === "remote_hello");
		phone.close();
		await phone.closed;

		// Someone who recorded the phone's first frame sends it again: the host answers with message 2, which only the
		// phone can read, and waits for remote_ready under the session keys.
		const replay = await openIdle(endpoint);
		const frames: Buffer[] = [];
		replay.on("message", (data: Buffer) => frames.push(data));
		replay.send(recorded as Buffer);
		const closed = new Promise<number>((resolve) => replay.on("close", resolve));
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(server.clients()).toEqual([]);
		server.notify("for connected phones only");
		expect(await closed).toBe(CloseCode.HandshakeTimeout);
		expect(frames).toHaveLength(1);
	});

	it("closes a client whose first transport message is not remote_ready", async () => {
		const { server, uri } = await setup();
		const phone = await connectTestClient(uri(), generateKeyPair(), { ready: false });
		phone.send({ type: "get_state", id: "x" });
		expect(await phone.closed).toBe(CloseCode.BadMessage);
		expect(phone.received).toEqual([]);
		expect(server.clients()).toEqual([]);
	});

	it("caps pending handshakes per address, so idle sockets cannot lock a phone out for long", async () => {
		const { endpoint, uri } = await setup({ firstFrameTimeoutMs: 300 });
		const idle = await Promise.all([openIdle(endpoint), openIdle(endpoint), openIdle(endpoint)]);
		// A fourth socket from the same address is refused while three are pending.
		await expect(openIdle(endpoint)).rejects.toThrow();
		await Promise.all(idle.map((socket) => new Promise((resolve) => socket.on("close", resolve))));
		// Once they time out, a phone gets through again.
		await expect(connectTestClient(uri(), generateKeyPair())).resolves.toBeDefined();
	});

	it("refuses an address after repeated failed handshakes", async () => {
		const { uri } = await setup();
		for (let attempt = 0; attempt < 10; attempt++) {
			await expect(connectTestClient(uri(), generateKeyPair(), { pair: false })).rejects.toThrow();
		}
		// Now the upgrade itself is refused, before any handshake.
		await expect(connectTestClient(uri(), generateKeyPair())).rejects.toThrow();
	});

	it("answers plain HTTP with 404 and refuses other WebSocket paths", async () => {
		const { port } = await setup();
		const status = await new Promise<number | undefined>((resolve) => {
			request({ host: "127.0.0.1", port, path: "/rc" }, (response) => resolve(response.statusCode)).end();
		});
		expect(status).toBe(404);
		const socket = new WebSocket(`ws://127.0.0.1:${port}/other`, "pibolt-rc.1");
		await expect(
			new Promise((resolve, reject) => {
				socket.on("open", resolve);
				socket.on("error", reject);
			}),
		).rejects.toThrow();
	});

	it("refuses a Noise handshake whose payload is not a JSON object, and keeps serving", async () => {
		const { uri } = await setup();
		for (const rawHello of ["null", "[]", "42", "not json"]) {
			await expect(connectTestClient(uri(), generateKeyPair(), { rawHello })).rejects.toThrow(
				`closed ${CloseCode.BadMessage}`,
			);
		}
		await expect(connectTestClient(uri(), generateKeyPair())).resolves.toBeDefined();
	});

	it("closes on a handshake that is not Noise", async () => {
		const { endpoint } = await setup();
		const socket = new WebSocket(endpoint, "pibolt-rc.1");
		await new Promise((resolve) => socket.on("open", resolve));
		socket.send(Buffer.from("not a handshake, but long enough to be read as one: 0123456789abcdef0123456789"));
		const code = await new Promise<number>((resolve) => socket.on("close", resolve));
		expect(code).toBe(CloseCode.BadMessage);
	});

	it("mirrors a prompt's streamed turn to the phone", async () => {
		const { harness, uri } = await setup();
		harness.setResponses([fauxAssistantMessage("hello from pi")]);
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		const response = await phone.request({ type: "prompt", message: "hi" });
		expect(response.success).toBe(true);
		await phone.waitFor((message) => message.type === "agent_settled");
		const deltas = phone.received
			.filter((message) => message.type === "message_update")
			.map((message) => message.assistantMessageEvent as { type: string; delta?: string })
			.filter((event) => event.type === "text_delta")
			.map((event) => event.delta)
			.join("");
		expect(deltas).toBe("hello from pi");
		const userStart = phone.received.find(
			(message) => message.type === "message_start" && (message.message as { role: string }).role === "user",
		);
		expect(userStart).toBeDefined();

		const messages = await phone.request({ type: "get_messages" });
		expect(((messages.data as { messages: unknown[] }).messages ?? []).length).toBeGreaterThanOrEqual(2);
	});

	it("keeps remote_hello under its size budget by sending only the newest messages", async () => {
		const { harness, uri } = await setup({ helloBudgetBytes: 40_000 });
		const big = "x".repeat(10_000);
		for (let turn = 0; turn < 6; turn++) {
			harness.setResponses([fauxAssistantMessage(`${big} reply ${turn}`)]);
			await harness.session.prompt(`question ${turn}`);
		}
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		const hello = await phone.waitFor((message) => message.type === "remote_hello");
		const messages = hello.messages as Array<{ role: string }>;
		expect(hello.omittedMessages).toBe(harness.session.messages.length - messages.length);
		expect(hello.omittedMessages).toBeGreaterThan(0);
		expect(messages.length).toBeGreaterThan(0);
		// The newest ones: the last message sent is the session's last.
		expect(messages.at(-1)).toEqual(harness.session.messages.at(-1));
		expect(Buffer.byteLength(JSON.stringify(hello))).toBeLessThanOrEqual(40_000);
	});

	it("sends terminal-side prompts to the phone too", async () => {
		const { harness, uri } = await setup();
		harness.setResponses([fauxAssistantMessage("typed in the terminal")]);
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		await phone.waitFor((message) => message.type === "remote_hello");
		await harness.session.prompt("from the keyboard");
		const end = await phone.waitFor(
			(message) => message.type === "message_end" && (message.message as { role: string }).role === "assistant",
		);
		expect(JSON.stringify(end.message)).toContain("typed in the terminal");
	});

	it("steers and aborts a running turn from the phone", async () => {
		let release: (() => void) | undefined;
		const waitTool: AgentTool = {
			name: "wait",
			label: "Wait",
			description: "Waits until released or aborted",
			parameters: Type.Object({}),
			execute: async (_id, _params, signal) => {
				await new Promise<void>((resolve) => {
					release = resolve;
					signal?.addEventListener("abort", () => resolve(), { once: true });
				});
				return { content: [{ type: "text", text: "waited" }], details: {} };
			},
		};
		const { harness, uri } = await setup({ tools: [waitTool] });
		harness.setResponses([
			fauxAssistantMessage(fauxToolCall("wait", {}), { stopReason: "toolUse" }),
			fauxAssistantMessage("after steer"),
		]);
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		await phone.request({ type: "prompt", message: "start" });
		await phone.waitFor((message) => message.type === "tool_execution_start");

		const steer = await phone.request({ type: "steer", message: "change course" });
		expect(steer.success).toBe(true);
		const state = await phone.request({ type: "get_state" });
		expect((state.data as { isStreaming: boolean }).isStreaming).toBe(true);

		const abort = await phone.request({ type: "abort" });
		expect(abort.success).toBe(true);
		const settled = await phone.waitFor((message) => message.type === "agent_settled");
		expect(settled.aborted).toBe(true);
		release?.();
	});

	it("refuses commands that are not available remotely", async () => {
		const { uri } = await setup();
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		const response = await phone.request({ type: "bash", command: "echo hi" });
		expect(response).toMatchObject({ success: false, error: "Not available over remote control" });
	});

	it("carries messages larger than one Noise frame", async () => {
		const { harness, uri } = await setup();
		const long = "x".repeat(200_000);
		harness.setResponses([fauxAssistantMessage(long)]);
		await harness.session.prompt("go");
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		const hello = await phone.waitFor((message) => message.type === "remote_hello");
		expect(JSON.stringify(hello.messages)).toContain(long);
		const echoed = await phone.request({ type: "set_session_name", name: "y".repeat(100_000) });
		expect(echoed.success).toBe(true);
		expect(harness.session.sessionName).toBe("y".repeat(100_000));
	});

	it("routes a dialog to every phone; the first answer wins and the others close it", async () => {
		const { server, uri } = await setup();
		const first = track(await connectTestClient(uri(), generateKeyPair()));
		const second = track(await connectTestClient(uri(), generateKeyPair()));
		await second.waitFor((message) => message.type === "remote_hello");
		const answer = server.requestDialog({ method: "confirm", title: "Run it?", message: "rm -rf build" });
		const request = await first.waitFor((message) => message.type === "extension_ui_request");
		expect(request).toMatchObject({ method: "confirm", title: "Run it?" });
		first.send({ type: "extension_ui_response", id: request.id, confirmed: true });
		await expect(answer).resolves.toMatchObject({ confirmed: true });
		await second.waitFor((message) => message.type === "extension_ui_dismissed" && message.id === request.id);
	});

	it("shows a dialog opened before the phone connected, and closes it when the terminal answers", async () => {
		const { server, uri } = await setup();
		const abort = new AbortController();
		const answer = server.requestDialog({ method: "select", title: "Pick", options: ["a", "b"] }, abort.signal);
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		const hello = await phone.waitFor((message) => message.type === "remote_hello");
		const [open] = hello.dialogs as Array<{ id: string; title: string }>;
		expect(open.title).toBe("Pick");
		abort.abort();
		await expect(answer).resolves.toBeUndefined();
		await phone.waitFor((message) => message.type === "extension_ui_dismissed" && message.id === open.id);
	});

	it("disconnects and refuses a revoked phone", async () => {
		const { server, store, uri } = await setup();
		const key = generateKeyPair();
		const phone = await connectTestClient(uri(), key);
		const [device] = store.devices();
		expect(server.revoke(device.publicKey)).toBe(true);
		expect(await phone.closed).toBe(CloseCode.NotAuthorized);
		await expect(connectTestClient(uri(), key, { pair: false })).rejects.toThrow(`closed ${CloseCode.NotAuthorized}`);
	});

	it("runs nothing from a phone revoked elsewhere, even before its socket closes", async () => {
		const { harness, store, uri } = await setup();
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		await phone.waitFor((message) => message.type === "remote_hello");
		// Another Pi on this machine revokes the phone: only the devices file changes.
		store.removeDevice(store.devices()[0].publicKey);
		phone.send({ type: "set_session_name", name: "should not happen", id: "x" });
		expect(await phone.closed).toBe(CloseCode.NotAuthorized);
		expect(harness.session.sessionName).not.toBe("should not happen");
	});

	it("sends a new snapshot when the terminal switches session", async () => {
		const { harness, server, uri } = await setup();
		const phone = track(await connectTestClient(uri(), generateKeyPair()));
		await phone.waitFor((message) => message.type === "remote_hello");
		harness.setResponses([fauxAssistantMessage("second")]);
		server.sessionChanged();
		await phone.waitFor((message) => message.type === "remote_hello" && phone.received.indexOf(message) > 0);
		expect(phone.received.filter((message) => message.type === "remote_hello")).toHaveLength(2);
	});

	describe("controller", () => {
		async function controllerSetup() {
			const harness = await createHarness();
			cleanups.push(() => harness.cleanup());
			const lines: string[][] = [];
			const statuses: Array<string | undefined> = [];
			const controller = new RemoteControlController({
				getSession: () => harness.session,
				settingsManager: SettingsManager.inMemory({ remoteControl: { port: 0 } }),
				agentDir: tempDir(),
				version: "9.9.9",
				showStatus: () => {},
				showWarning: () => {},
				showError: (message) => {
					throw new Error(message);
				},
				showLines: (block) => lines.push(block),
				setStatus: (text) => statuses.push(text),
				select: async () => undefined,
				copyToClipboard: async () => {},
				columns: () => 200,
				rows: () => 60,
				bindAddress: "127.0.0.1",
			});
			cleanups.push(() => controller.stop());
			await controller.handleCommand("");
			const pairing = decodePairingUri(controller.pairingUri ?? "");
			const port = new URL(pairing.e[0] ?? "ws://127.0.0.1:1").port;
			const uri = encodePairingUri({ ...pairing, e: [`ws://127.0.0.1:${port}/rc`] });
			return { controller, lines, statuses, uri };
		}

		it("shows the QR code and footer status", async () => {
			const { lines, statuses } = await controllerSetup();
			expect(lines[0]?.[0]).toBe("Remote control: scan with the Pi-Bolt app");
			expect(lines[0]?.some((line) => line.includes("▀"))).toBe(true);
			expect(statuses.at(-1)).toBe("remote lan: waiting");
		});

		it("takes the phone's answer and closes the terminal dialog", async () => {
			const { controller, uri, statuses } = await controllerSetup();
			const phone = track(await connectTestClient(uri, generateKeyPair(), { device: "Pixel" }));
			await phone.waitFor((message) => message.type === "remote_hello");
			expect(statuses.at(-1)).toBe("remote lan: Pixel connected");
			let terminalSignal: AbortSignal | undefined;
			const result = controller.raceDialog(
				{ method: "select", title: "Model?", options: ["a", "b"] },
				undefined,
				(response) => ("value" in response ? response.value : undefined),
				(opts) => {
					terminalSignal = opts.signal;
					return new Promise<string | undefined>((resolve) =>
						opts.signal?.addEventListener("abort", () => resolve(undefined)),
					);
				},
			);
			const request = await phone.waitFor((message) => message.type === "extension_ui_request");
			phone.send({ type: "extension_ui_response", id: request.id, value: "b" });
			await expect(result).resolves.toBe("b");
			expect(terminalSignal?.aborted).toBe(true);
		});

		it("takes the terminal's answer and closes the phone's dialog", async () => {
			const { controller, uri } = await controllerSetup();
			const phone = track(await connectTestClient(uri, generateKeyPair()));
			let answer: (value: boolean) => void = () => {};
			const result = controller.raceDialog(
				{ method: "confirm", title: "Sure?", message: "" },
				undefined,
				(response) => "confirmed" in response && response.confirmed,
				() =>
					new Promise<boolean>((resolve) => {
						answer = resolve;
					}),
			);
			const request = await phone.waitFor((message) => message.type === "extension_ui_request");
			answer(true);
			await expect(result).resolves.toBe(true);
			await phone.waitFor((message) => message.type === "extension_ui_dismissed" && message.id === request.id);
		});

		it("runs the terminal dialog alone when remote control is off", async () => {
			const { controller } = await controllerSetup();
			await controller.stop();
			const result = await controller.raceDialog(
				{ method: "input", title: "Name?" },
				undefined,
				() => "phone",
				async () => "terminal",
			);
			expect(result).toBe("terminal");
		});
	});

	describe("tunnel", () => {
		function fakeCloudflared(script: string): string {
			const path = join(tempDir(), "cloudflared");
			writeFileSync(path, `#!/usr/bin/env node\n${script}\n`);
			chmodSync(path, 0o755);
			return path;
		}

		it("starts a quick tunnel and returns its wss endpoint", async () => {
			const path = fakeCloudflared(`
process.stderr.write("INF Requesting new quick Tunnel on trycloudflare.com...\\n");
process.stderr.write("INF |  https://quiet-fox-123.trycloudflare.com  |\\n");
setTimeout(() => process.stderr.write("INF Registered tunnel connection connIndex=0\\n"), 50);
setInterval(() => {}, 1000);`);
			const tunnel = await startTunnel(4567, "/rc", { cloudflared: path });
			cleanups.push(() => tunnel.stop());
			expect(tunnel.endpoint).toBe("wss://quiet-fox-123.trycloudflare.com/rc");
		});

		it("uses the named tunnel's hostname", async () => {
			const path = fakeCloudflared(`
if (!process.argv.includes("run") || process.argv.at(-1) !== "my-tunnel") process.exit(3);
process.stderr.write("INF Registered tunnel connection connIndex=0\\n");
setInterval(() => {}, 1000);`);
			const tunnel = await startTunnel(4567, "/rc", {
				cloudflared: path,
				tunnel: { name: "my-tunnel", hostname: "pi.example.com" },
			});
			cleanups.push(() => tunnel.stop());
			expect(tunnel.endpoint).toBe("wss://pi.example.com/rc");
		});

		it("reports a missing cloudflared with install advice", async () => {
			await expect(startTunnel(4567, "/rc", { cloudflared: join(tempDir(), "missing") })).rejects.toThrow(
				"cloudflared was not found",
			);
		});

		it("reports cloudflared exiting early", async () => {
			const path = fakeCloudflared(
				`process.stderr.write("ERR failed to request quick Tunnel\\n"); process.exit(1);`,
			);
			await expect(startTunnel(4567, "/rc", { cloudflared: path })).rejects.toThrow(/exited with code 1/);
		});

		it("tells when a running tunnel dies", async () => {
			const path = fakeCloudflared(`
process.stderr.write("INF https://a-b.trycloudflare.com\\nINF Registered tunnel connection\\n");
setTimeout(() => process.exit(2), 100);`);
			let exitMessage: string | undefined;
			const exited = new Promise<void>((resolve) => {
				void startTunnel(
					4567,
					"/rc",
					{ cloudflared: path },
					{
						onExit: (message) => {
							exitMessage = message;
							resolve();
						},
					},
				);
			});
			await exited;
			expect(exitMessage).toMatch(/exited with code 2/);
		});
	});
});
