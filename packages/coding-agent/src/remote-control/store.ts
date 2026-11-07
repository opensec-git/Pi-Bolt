/**
 * Remote control's files in the agent directory: the host's static key (`remote-control/host-key`) and the paired phones
 * (`remote-control/devices.json`), both readable only by the user.
 */

import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { generateKeyPair, type KeyPair, keyPairFromPrivate } from "./noise.ts";

export interface PairedDevice {
	/** Static X25519 public key, base64url. Identifies the device. */
	publicKey: string;
	name: string;
	pairedAt: string;
	lastSeenAt?: string;
}

export function toBase64Url(data: Buffer): string {
	return data.toString("base64url");
}

export function fromBase64Url(text: string): Buffer {
	return Buffer.from(text, "base64url");
}

/** Writes `content` readable by the user only (mode 0600), replacing the file atomically. */
export function writePrivateFile(path: string, content: string): void {
	const temp = `${path}.${process.pid}.tmp`;
	writeFileSync(temp, content, { mode: 0o600 });
	chmodSync(temp, 0o600);
	renameSync(temp, path);
}

export class RemoteControlStore {
	readonly dir: string;

	constructor(agentDir: string) {
		this.dir = join(agentDir, "remote-control");
	}

	private ensureDir(): void {
		if (!existsSync(this.dir)) mkdirSync(this.dir, { recursive: true, mode: 0o700 });
	}

	/** The host's static key pair, made on first use. */
	hostKeyPair(): KeyPair {
		const path = join(this.dir, "host-key");
		if (existsSync(path)) {
			const privateKey = fromBase64Url(readFileSync(path, "utf8").trim());
			if (privateKey.length === 32) return keyPairFromPrivate(privateKey);
		}
		this.ensureDir();
		const keyPair = generateKeyPair();
		writePrivateFile(path, `${toBase64Url(keyPair.privateKey)}\n`);
		return keyPair;
	}

	devices(): PairedDevice[] {
		const path = join(this.dir, "devices.json");
		if (!existsSync(path)) return [];
		try {
			const parsed = JSON.parse(readFileSync(path, "utf8")) as { devices?: unknown };
			if (!Array.isArray(parsed.devices)) return [];
			return parsed.devices.filter(
				(device): device is PairedDevice =>
					typeof device === "object" &&
					device !== null &&
					typeof (device as PairedDevice).publicKey === "string" &&
					typeof (device as PairedDevice).name === "string",
			);
		} catch {
			return [];
		}
	}

	private saveDevices(devices: PairedDevice[]): void {
		this.ensureDir();
		writePrivateFile(join(this.dir, "devices.json"), `${JSON.stringify({ devices }, null, 2)}\n`);
	}

	findDevice(publicKey: Buffer): PairedDevice | undefined {
		const key = toBase64Url(publicKey);
		return this.devices().find((device) => device.publicKey === key);
	}

	/** Adds the device, or refreshes its name and last-seen time. */
	upsertDevice(publicKey: Buffer, name: string, now = new Date()): PairedDevice {
		const key = toBase64Url(publicKey);
		const devices = this.devices();
		const existing = devices.find((device) => device.publicKey === key);
		const device: PairedDevice = existing
			? { ...existing, name, lastSeenAt: now.toISOString() }
			: { publicKey: key, name, pairedAt: now.toISOString(), lastSeenAt: now.toISOString() };
		this.saveDevices([...devices.filter((entry) => entry.publicKey !== key), device]);
		return device;
	}

	removeDevice(publicKey: string): boolean {
		const devices = this.devices();
		const remaining = devices.filter((device) => device.publicKey !== publicKey);
		if (remaining.length === devices.length) return false;
		this.saveDevices(remaining);
		return true;
	}
}
