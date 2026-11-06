import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { IkInitiator, IkResponder, keyPairFromPrivate, NOISE_PROTOCOL_NAME } from "../src/remote-control/noise.ts";

interface Vector {
	protocol_name: string;
	init_prologue: string;
	init_static: string;
	init_ephemeral: string;
	init_remote_static: string;
	resp_prologue: string;
	resp_static: string;
	resp_ephemeral: string;
	handshake_hash: string;
	messages: Array<{ payload: string; ciphertext: string }>;
}

const fixture = JSON.parse(
	readFileSync(join(import.meta.dirname, "fixtures", "remote-control", "noise-ik-vectors.json"), "utf8"),
) as { vectors: Vector[] };

const hex = (value: string) => Buffer.from(value, "hex");

describe("remote control Noise", () => {
	it("reproduces the cacophony Noise_IK_25519_AESGCM_SHA256 vector", () => {
		const vector = fixture.vectors.find((entry) => entry.protocol_name === NOISE_PROTOCOL_NAME);
		expect(vector).toBeDefined();
		if (!vector) return;
		const initiator = new IkInitiator({
			prologue: hex(vector.init_prologue),
			staticKeyPair: keyPairFromPrivate(hex(vector.init_static)),
			ephemeralKeyPair: keyPairFromPrivate(hex(vector.init_ephemeral)),
			remoteStaticKey: hex(vector.init_remote_static),
		});
		const responder = new IkResponder({
			prologue: hex(vector.resp_prologue),
			staticKeyPair: keyPairFromPrivate(hex(vector.resp_static)),
			ephemeralKeyPair: keyPairFromPrivate(hex(vector.resp_ephemeral)),
		});
		const [first, second, ...transport] = vector.messages;

		const message1 = initiator.writeMessage1(hex(first.payload));
		expect(message1.toString("hex")).toBe(first.ciphertext);
		const read1 = responder.readMessage1(message1);
		expect(read1.payload.toString("hex")).toBe(first.payload);
		expect(read1.remoteStaticKey).toEqual(keyPairFromPrivate(hex(vector.init_static)).publicKey);

		const written2 = responder.writeMessage2(hex(second.payload));
		expect(written2.message.toString("hex")).toBe(second.ciphertext);
		const read2 = initiator.readMessage2(written2.message);
		expect(read2.payload.toString("hex")).toBe(second.payload);
		expect(read2.keys.handshakeHash.toString("hex")).toBe(vector.handshake_hash);
		expect(written2.keys.handshakeHash.toString("hex")).toBe(vector.handshake_hash);

		transport.forEach((message, index) => {
			const [sender, receiver] = index % 2 === 0 ? [read2.keys, written2.keys] : [written2.keys, read2.keys];
			const ciphertext = sender.send.encrypt(hex(message.payload));
			expect(ciphertext.toString("hex")).toBe(message.ciphertext);
			expect(receiver.receive.decrypt(ciphertext).toString("hex")).toBe(message.payload);
		});
	});

	it("rejects a tampered transport message", () => {
		const host = keyPairFromPrivate(Buffer.alloc(32, 7));
		const initiator = new IkInitiator({
			prologue: Buffer.from("p"),
			staticKeyPair: keyPairFromPrivate(Buffer.alloc(32, 9)),
			remoteStaticKey: host.publicKey,
		});
		const responder = new IkResponder({ prologue: Buffer.from("p"), staticKeyPair: host });
		responder.readMessage1(initiator.writeMessage1(Buffer.from("hi")));
		const { message, keys } = responder.writeMessage2(Buffer.alloc(0));
		const client = initiator.readMessage2(message).keys;
		const ciphertext = client.send.encrypt(Buffer.from("hello"));
		ciphertext[0] ^= 1;
		expect(() => keys.receive.decrypt(ciphertext)).toThrow("Decryption failed");
	});

	it("rejects a handshake made with another prologue", () => {
		const host = keyPairFromPrivate(Buffer.alloc(32, 7));
		const initiator = new IkInitiator({
			prologue: Buffer.from("a"),
			staticKeyPair: keyPairFromPrivate(Buffer.alloc(32, 9)),
			remoteStaticKey: host.publicKey,
		});
		const responder = new IkResponder({ prologue: Buffer.from("b"), staticKeyPair: host });
		expect(() => responder.readMessage1(initiator.writeMessage1(Buffer.from("hi")))).toThrow();
	});
});
