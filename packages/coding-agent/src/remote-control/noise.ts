/**
 * Noise_IK_25519_AESGCM_SHA256 (https://noiseprotocol.org/noise.html), the handshake and transport encryption of remote
 * control. Built on node:crypto only: X25519, AES-256-GCM, SHA-256 and HMAC (Bun has no ChaCha20-Poly1305).
 */

import {
	createCipheriv,
	createDecipheriv,
	createHash,
	createHmac,
	createPrivateKey,
	createPublicKey,
	diffieHellman,
	generateKeyPairSync,
	type KeyObject,
} from "node:crypto";

export const NOISE_PROTOCOL_NAME = "Noise_IK_25519_AESGCM_SHA256";
const KEY_LENGTH = 32;
const TAG_LENGTH = 16;
/** The largest Noise message (65535 bytes) minus the AES-GCM tag. */
export const MAX_NOISE_PLAINTEXT = 65535 - TAG_LENGTH;

const PKCS8_X25519_PREFIX = Buffer.from("302e020100300506032b656e04220420", "hex");
const SPKI_X25519_PREFIX = Buffer.from("302a300506032b656e032100", "hex");

export interface KeyPair {
	privateKey: Buffer;
	publicKey: Buffer;
}

function privateKeyObject(privateKey: Buffer): KeyObject {
	return createPrivateKey({ key: Buffer.concat([PKCS8_X25519_PREFIX, privateKey]), format: "der", type: "pkcs8" });
}

function publicKeyObject(publicKey: Buffer): KeyObject {
	return createPublicKey({ key: Buffer.concat([SPKI_X25519_PREFIX, publicKey]), format: "der", type: "spki" });
}

/** The key pair of a raw 32-byte X25519 private key. */
export function keyPairFromPrivate(privateKey: Buffer): KeyPair {
	const publicKey = createPublicKey(privateKeyObject(privateKey))
		.export({ format: "der", type: "spki" })
		.subarray(-32);
	return { privateKey: Buffer.from(privateKey), publicKey: Buffer.from(publicKey) };
}

export function generateKeyPair(): KeyPair {
	const { privateKey } = generateKeyPairSync("x25519");
	return keyPairFromPrivate(Buffer.from(privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32)));
}

function dh(keyPair: KeyPair, publicKey: Buffer): Buffer {
	if (publicKey.length !== KEY_LENGTH) throw new NoiseError("Bad public key length");
	return diffieHellman({ privateKey: privateKeyObject(keyPair.privateKey), publicKey: publicKeyObject(publicKey) });
}

function sha256(...parts: Buffer[]): Buffer {
	const hash = createHash("sha256");
	for (const part of parts) hash.update(part);
	return hash.digest();
}

function hmac(key: Buffer, ...parts: Buffer[]): Buffer {
	const mac = createHmac("sha256", key);
	for (const part of parts) mac.update(part);
	return mac.digest();
}

function hkdf2(chainingKey: Buffer, inputKeyMaterial: Buffer): [Buffer, Buffer] {
	const tempKey = hmac(chainingKey, inputKeyMaterial);
	const output1 = hmac(tempKey, Buffer.from([1]));
	const output2 = hmac(tempKey, output1, Buffer.from([2]));
	return [output1, output2];
}

export class NoiseError extends Error {}

/** A Noise CipherState: AES-256-GCM with a 64-bit big-endian counter nonce. */
export class CipherState {
	private key: Buffer | undefined;
	private nonce = 0n;

	constructor(key?: Buffer) {
		this.key = key;
	}

	hasKey(): boolean {
		return this.key !== undefined;
	}

	private nextNonce(): Buffer {
		if (this.nonce >= 0xffffffffffffffffn) throw new NoiseError("Nonce exhausted");
		const iv = Buffer.alloc(12);
		iv.writeBigUInt64BE(this.nonce, 4);
		this.nonce++;
		return iv;
	}

	encryptWithAd(ad: Buffer, plaintext: Buffer): Buffer {
		if (!this.key) return plaintext;
		const cipher = createCipheriv("aes-256-gcm", this.key, this.nextNonce(), { authTagLength: TAG_LENGTH });
		cipher.setAAD(ad);
		return Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
	}

	decryptWithAd(ad: Buffer, ciphertext: Buffer): Buffer {
		if (!this.key) return ciphertext;
		if (ciphertext.length < TAG_LENGTH) throw new NoiseError("Ciphertext too short");
		const iv = this.nextNonce();
		const decipher = createDecipheriv("aes-256-gcm", this.key, iv, { authTagLength: TAG_LENGTH });
		decipher.setAAD(ad);
		decipher.setAuthTag(ciphertext.subarray(ciphertext.length - TAG_LENGTH));
		try {
			return Buffer.concat([
				decipher.update(ciphertext.subarray(0, ciphertext.length - TAG_LENGTH)),
				decipher.final(),
			]);
		} catch {
			// A failed decryption must not consume the nonce, but the session is dead anyway: callers close.
			throw new NoiseError("Decryption failed");
		}
	}

	encrypt(plaintext: Buffer): Buffer {
		return this.encryptWithAd(Buffer.alloc(0), plaintext);
	}

	decrypt(ciphertext: Buffer): Buffer {
		return this.decryptWithAd(Buffer.alloc(0), ciphertext);
	}
}

class SymmetricState {
	private chainingKey: Buffer;
	private hash: Buffer;
	private cipher = new CipherState();

	constructor(protocolName: string) {
		const name = Buffer.from(protocolName, "ascii");
		this.hash = name.length <= 32 ? Buffer.concat([name, Buffer.alloc(32 - name.length)]) : sha256(name);
		this.chainingKey = this.hash;
	}

	mixKey(inputKeyMaterial: Buffer): void {
		const [chainingKey, tempKey] = hkdf2(this.chainingKey, inputKeyMaterial);
		this.chainingKey = chainingKey;
		this.cipher = new CipherState(tempKey);
	}

	mixHash(data: Buffer): void {
		this.hash = sha256(this.hash, data);
	}

	encryptAndHash(plaintext: Buffer): Buffer {
		const ciphertext = this.cipher.encryptWithAd(this.hash, plaintext);
		this.mixHash(ciphertext);
		return ciphertext;
	}

	decryptAndHash(ciphertext: Buffer): Buffer {
		const plaintext = this.cipher.decryptWithAd(this.hash, ciphertext);
		this.mixHash(ciphertext);
		return plaintext;
	}

	handshakeHash(): Buffer {
		return this.hash;
	}

	split(): [CipherState, CipherState] {
		const [key1, key2] = hkdf2(this.chainingKey, Buffer.alloc(0));
		return [new CipherState(key1), new CipherState(key2)];
	}
}

export interface TransportKeys {
	send: CipherState;
	receive: CipherState;
	handshakeHash: Buffer;
}

interface HandshakeOptions {
	prologue: Buffer;
	staticKeyPair: KeyPair;
	/** Fixed ephemeral key, for test vectors only. */
	ephemeralKeyPair?: KeyPair;
}

/** The initiator (the phone): knows the responder's static key in advance. */
export class IkInitiator {
	private readonly state = new SymmetricState(NOISE_PROTOCOL_NAME);
	private readonly s: KeyPair;
	private readonly e: KeyPair;
	private readonly rs: Buffer;
	private re: Buffer | undefined;

	constructor(options: HandshakeOptions & { remoteStaticKey: Buffer }) {
		this.s = options.staticKeyPair;
		this.e = options.ephemeralKeyPair ?? generateKeyPair();
		this.rs = options.remoteStaticKey;
		this.state.mixHash(options.prologue);
		this.state.mixHash(this.rs);
	}

	/** -> e, es, s, ss */
	writeMessage1(payload: Buffer): Buffer {
		this.state.mixHash(this.e.publicKey);
		this.state.mixKey(dh(this.e, this.rs));
		const encryptedStatic = this.state.encryptAndHash(this.s.publicKey);
		this.state.mixKey(dh(this.s, this.rs));
		const encryptedPayload = this.state.encryptAndHash(payload);
		return Buffer.concat([this.e.publicKey, encryptedStatic, encryptedPayload]);
	}

	/** <- e, ee, se */
	readMessage2(message: Buffer): { payload: Buffer; keys: TransportKeys } {
		if (message.length < KEY_LENGTH + TAG_LENGTH) throw new NoiseError("Handshake message 2 too short");
		this.re = message.subarray(0, KEY_LENGTH);
		this.state.mixHash(this.re);
		this.state.mixKey(dh(this.e, this.re));
		this.state.mixKey(dh(this.s, this.re));
		const payload = this.state.decryptAndHash(message.subarray(KEY_LENGTH));
		const [initiatorToResponder, responderToInitiator] = this.state.split();
		return {
			payload,
			keys: { send: initiatorToResponder, receive: responderToInitiator, handshakeHash: this.state.handshakeHash() },
		};
	}
}

/** The responder (Pi-Bolt). */
export class IkResponder {
	private readonly state = new SymmetricState(NOISE_PROTOCOL_NAME);
	private readonly s: KeyPair;
	private readonly e: KeyPair;
	private re: Buffer | undefined;
	private rs: Buffer | undefined;

	constructor(options: HandshakeOptions) {
		this.s = options.staticKeyPair;
		this.e = options.ephemeralKeyPair ?? generateKeyPair();
		this.state.mixHash(options.prologue);
		this.state.mixHash(this.s.publicKey);
	}

	/** -> e, es, s, ss. Returns the initiator's static key and the payload. */
	readMessage1(message: Buffer): { remoteStaticKey: Buffer; payload: Buffer } {
		if (message.length < KEY_LENGTH + KEY_LENGTH + TAG_LENGTH + TAG_LENGTH) {
			throw new NoiseError("Handshake message 1 too short");
		}
		const re = Buffer.from(message.subarray(0, KEY_LENGTH));
		this.re = re;
		this.state.mixHash(re);
		this.state.mixKey(dh(this.s, re));
		const rs = this.state.decryptAndHash(message.subarray(KEY_LENGTH, KEY_LENGTH * 2 + TAG_LENGTH));
		this.rs = Buffer.from(rs);
		this.state.mixKey(dh(this.s, this.rs));
		const payload = this.state.decryptAndHash(message.subarray(KEY_LENGTH * 2 + TAG_LENGTH));
		return { remoteStaticKey: this.rs, payload };
	}

	/** <- e, ee, se */
	writeMessage2(payload: Buffer): { message: Buffer; keys: TransportKeys } {
		if (!this.re || !this.rs) throw new NoiseError("Handshake message 1 not read");
		this.state.mixHash(this.e.publicKey);
		this.state.mixKey(dh(this.e, this.re));
		this.state.mixKey(dh(this.e, this.rs));
		const encryptedPayload = this.state.encryptAndHash(payload);
		const [initiatorToResponder, responderToInitiator] = this.state.split();
		return {
			message: Buffer.concat([this.e.publicKey, encryptedPayload]),
			keys: { send: responderToInitiator, receive: initiatorToResponder, handshakeHash: this.state.handshakeHash() },
		};
	}
}
