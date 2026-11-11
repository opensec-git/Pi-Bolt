/**
 * Remote control wire format (docs/remote-control.md): the pairing QR payload and the chunking of JSON messages into
 * Noise transport messages.
 */

import { type CipherState, MAX_NOISE_PLAINTEXT } from "./noise.ts";
import { fromBase64Url, toBase64Url } from "./store.ts";

export const PROTOCOL_VERSION = 1;
export const WEBSOCKET_SUBPROTOCOL = "pibolt-rc.1";
export const WEBSOCKET_PATH = "/rc";
export const NOISE_PROLOGUE = Buffer.from("pibolt-rc/1", "ascii");
export const PAIRING_URI_PREFIX = "pibolt://pair#";

/** Data bytes per chunk; with the flag byte this stays under the Noise plaintext limit. */
export const CHUNK_DATA_SIZE = 65000;
/** The largest message a peer may send us, reassembled. */
export const MAX_MESSAGE_SIZE = 32 * 1024 * 1024;

const FLAG_FINAL = 0x00;
const FLAG_MORE = 0x01;

export const CloseCode = {
	BadMessage: 4400,
	NotAuthorized: 4403,
	HandshakeTimeout: 4408,
	TooManyAttempts: 4429,
	GoingAway: 1001,
	/**
	 * The background service handed its port to a terminal's `/remote-control` (or took it back). Clients treat it as a
	 * dropped connection and reconnect at once, to the same addresses.
	 */
	Handoff: 1012,
	/**
	 * The same device completed a newer handshake (it changed networks); its older connection is closed. The phone
	 * already uses the newer one and ignores the old socket's close. A plain close, not one clients retry: two installs
	 * sharing one device key (a restored backup) would otherwise take the connection from each other forever.
	 */
	Replaced: 1000,
} as const;

/**
 * Host to client, after `remote_hello` on connect and again whenever the list changes: every address the host listens on
 * now, in preference order (`e` of the pairing code). A quick tunnel's address changes when it restarts; a phone that
 * stores this list keeps reaching the host from anywhere without a new QR code. Older clients ignore it.
 */
export const ENDPOINTS_MESSAGE = "remote_endpoints";

export interface PairingPayload {
	v: number;
	/** Host display name. */
	n: string;
	/** Host static public key, base64url. */
	k: string;
	/** One-time pairing secret, base64url. */
	s: string;
	/** WebSocket endpoints in preference order. */
	e: string[];
	/** Expiry of the secret, Unix seconds. */
	x: number;
}

export function encodePairingUri(payload: PairingPayload): string {
	return `${PAIRING_URI_PREFIX}${toBase64Url(Buffer.from(JSON.stringify(payload), "utf8"))}`;
}

/**
 * A code that carries the host's current addresses but pairs nothing (`pi-bolt service status`): its secret is all zeros
 * and it expired at the epoch. A phone that knows the host takes the addresses; any other phone says it has expired.
 */
export function encodeReconnectUri(hostName: string, hostPublicKey: Buffer, endpoints: string[]): string {
	return encodePairingUri({
		v: PROTOCOL_VERSION,
		n: hostName,
		k: toBase64Url(hostPublicKey),
		s: toBase64Url(Buffer.alloc(32)),
		e: endpoints,
		x: 0,
	});
}

export function decodePairingUri(uri: string): PairingPayload {
	if (!uri.startsWith(PAIRING_URI_PREFIX)) throw new Error("Not a Pi-Bolt pairing code");
	const payload = JSON.parse(fromBase64Url(uri.slice(PAIRING_URI_PREFIX.length)).toString("utf8")) as PairingPayload;
	if (payload.v !== PROTOCOL_VERSION) throw new Error(`Unsupported pairing code version ${payload.v}`);
	return payload;
}

/** Splits a JSON message into encrypted transport frames. */
export function encryptMessage(cipher: CipherState, message: unknown): Buffer[] {
	const data = Buffer.from(JSON.stringify(message), "utf8");
	const frames: Buffer[] = [];
	let offset = 0;
	do {
		const end = Math.min(offset + CHUNK_DATA_SIZE, data.length);
		const flag = end < data.length ? FLAG_MORE : FLAG_FINAL;
		const plaintext = Buffer.concat([Buffer.from([flag]), data.subarray(offset, end)]);
		if (plaintext.length > MAX_NOISE_PLAINTEXT) throw new Error("Chunk too large");
		frames.push(cipher.encrypt(plaintext));
		offset = end;
	} while (offset < data.length);
	return frames;
}

/** Reassembles decrypted transport frames into JSON messages. */
export class MessageAssembler {
	private chunks: Buffer[] = [];
	private size = 0;

	/** Decrypts one frame. Returns the parsed message when the frame was a message's last chunk. */
	push(cipher: CipherState, frame: Buffer): { complete: true; message: unknown } | { complete: false } {
		const plaintext = cipher.decrypt(frame);
		if (plaintext.length === 0) throw new Error("Empty transport message");
		const flag = plaintext[0];
		if (flag !== FLAG_FINAL && flag !== FLAG_MORE) throw new Error("Bad chunk flag");
		const data = plaintext.subarray(1);
		this.size += data.length;
		if (this.size > MAX_MESSAGE_SIZE) throw new Error("Message too large");
		this.chunks.push(data);
		if (flag === FLAG_MORE) return { complete: false };
		const text = Buffer.concat(this.chunks).toString("utf8");
		this.chunks = [];
		this.size = 0;
		return { complete: true, message: JSON.parse(text) };
	}
}
