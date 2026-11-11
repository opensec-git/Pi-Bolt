/** A remote control client for tests: the phone's side of docs/remote-control.md. */

import WebSocket from "ws";
import { IkInitiator, type KeyPair, type TransportKeys } from "../../src/remote-control/noise.ts";
import {
	decodePairingUri,
	encryptMessage,
	MessageAssembler,
	NOISE_PROLOGUE,
	WEBSOCKET_SUBPROTOCOL,
} from "../../src/remote-control/protocol.ts";

export interface TestClient {
	/** The handshake reply payload. */
	welcome: { host: string; version: string; paired: boolean };
	received: Array<Record<string, unknown>>;
	send(message: unknown): void;
	/** Resolves with the first received message that matches (including ones already received). */
	waitFor(
		predicate: (message: Record<string, unknown>) => boolean,
		timeoutMs?: number,
	): Promise<Record<string, unknown>>;
	/** Sends a command with an id and resolves with its response. */
	request(command: Record<string, unknown>): Promise<Record<string, unknown>>;
	closed: Promise<number>;
	close(): void;
}

let nextId = 0;

/**
 * Connects with a pairing URI; resolves once `remote_hello` arrives (right after the handshake with `ready: false`),
 * rejects with the close code when refused.
 */
export function connectTestClient(
	pairingUri: string,
	clientKeyPair: KeyPair,
	options: {
		pair?: boolean;
		device?: string;
		endpointIndex?: number;
		secret?: string;
		rawHello?: string;
		/** false: skip `remote_ready` after the handshake (the host then never treats the client as connected). */
		ready?: boolean;
		/** Gets handshake message 1 as sent, for replay tests. */
		onMessage1?: (frame: Buffer) => void;
	} = {},
): Promise<TestClient> {
	const payload = decodePairingUri(pairingUri);
	const endpoint = payload.e[options.endpointIndex ?? 0];
	const socket = new WebSocket(endpoint, WEBSOCKET_SUBPROTOCOL);
	const initiator = new IkInitiator({
		prologue: NOISE_PROLOGUE,
		staticKeyPair: clientKeyPair,
		remoteStaticKey: Buffer.from(payload.k, "base64url"),
	});
	const received: Array<Record<string, unknown>> = [];
	const waiters: Array<{
		predicate: (message: Record<string, unknown>) => boolean;
		resolve: (m: Record<string, unknown>) => void;
	}> = [];
	const assembler = new MessageAssembler();
	let keys: TransportKeys | undefined;
	const closed = new Promise<number>((resolve) => socket.on("close", (code) => resolve(code)));

	return new Promise<TestClient>((resolve, reject) => {
		socket.on("error", reject);
		void closed.then((code) => reject(new Error(`closed ${code}`)));
		socket.on("open", () => {
			const hello = {
				device: options.device ?? "Test phone",
				app: "test",
				...(options.pair === false ? {} : { pair: options.secret ?? payload.s }),
			};
			const message1 = initiator.writeMessage1(Buffer.from(options.rawHello ?? JSON.stringify(hello), "utf8"));
			options.onMessage1?.(Buffer.from(message1));
			socket.send(message1);
		});
		socket.on("message", (data: Buffer) => {
			if (!keys) {
				const read = initiator.readMessage2(data);
				keys = read.keys;
				const transport = keys;
				const client: TestClient = {
					welcome: JSON.parse(read.payload.toString("utf8")),
					received,
					send: (message) => {
						for (const frame of encryptMessage(transport.send, message)) socket.send(frame);
					},
					waitFor: (predicate, timeoutMs = 5000) => {
						const found = received.find(predicate);
						if (found) return Promise.resolve(found);
						return new Promise((resolveWait, rejectWait) => {
							const timer = setTimeout(() => rejectWait(new Error("waitFor timed out")), timeoutMs);
							waiters.push({
								predicate,
								resolve: (message) => {
									clearTimeout(timer);
									resolveWait(message);
								},
							});
						});
					},
					request: (command) => {
						const id = `t${nextId++}`;
						client.send({ ...command, id });
						return client.waitFor((message) => message.type === "response" && message.id === id);
					},
					closed,
					close: () => socket.close(),
				};
				// Like the app: connected once the host answers remote_ready with remote_hello.
				if (options.ready === false) resolve(client);
				else {
					client.send({ type: "remote_ready" });
					client.waitFor((message) => message.type === "remote_hello").then(() => resolve(client), reject);
				}
				return;
			}
			const result = assembler.push(keys.receive, data);
			if (!result.complete) return;
			const message = result.message as Record<string, unknown>;
			received.push(message);
			for (const waiter of [...waiters]) {
				if (waiter.predicate(message)) {
					waiters.splice(waiters.indexOf(waiter), 1);
					waiter.resolve(message);
				}
			}
		});
	});
}
