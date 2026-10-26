// `pi-bolt update` runs the release's installer only once the release key vouches for it (pi-bolt-installer.ts).
import { createHash, generateKeyPairSync, type KeyObject, sign } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { piBoltInstallerProcess, piBoltUpdateEnvironment } from "../src/pi-bolt.ts";
import { fetchReleaseInstaller, runReleaseInstaller } from "../src/pi-bolt-installer.ts";
import { releaseDownloadBase } from "../src/pi-bolt-update-windows.ts";

const VERSION = "1.0.2";
const BASE = releaseDownloadBase(VERSION);

function keyPair(): { pem: string; privateKey: KeyObject } {
	const { publicKey, privateKey } = generateKeyPairSync("ed25519");
	return { pem: publicKey.export({ type: "spki", format: "pem" }).toString(), privateKey };
}

function sha(data: Buffer): string {
	return createHash("sha256").update(data).digest("hex");
}

/** SHA256SUMS of a release, listing `files`. */
function sumsOf(version: string, files: Record<string, Buffer>): Buffer {
	const lines = Object.entries(files).map(([name, data]) => `${sha(data)}  ${name}`);
	return Buffer.from(`# pi-bolt ${version}\n${lines.join("\n")}\n`);
}

let served: Map<string, Buffer>;
let requested: string[];

beforeEach(() => {
	served = new Map();
	requested = [];
	vi.stubGlobal("fetch", async (input: string | URL) => {
		const url = String(input);
		requested.push(url);
		const body = served.get(url);
		return body ? new Response(new Uint8Array(body)) : new Response("not found", { status: 404 });
	});
});

afterEach(() => {
	vi.unstubAllGlobals();
});

/** A release whose SHA256SUMS lists `listed` and is signed by `privateKey`; `installer` is what is served as install.sh. */
function serveRelease(options: {
	privateKey: KeyObject;
	listed: Record<string, Buffer>;
	installer?: Buffer;
	version?: string;
}): void {
	const sums = sumsOf(options.version ?? VERSION, options.listed);
	served.set(`${BASE}/SHA256SUMS`, sums);
	served.set(`${BASE}/SHA256SUMS.sig`, sign(null, sums, options.privateKey));
	if (options.installer) served.set(`${BASE}/install.sh`, options.installer);
}

describe("fetchReleaseInstaller", () => {
	const script = Buffer.from("#!/bin/sh\necho installing\n");
	const archive = Buffer.from("archive");

	test("returns the release's installer once its signed SHA256SUMS lists that checksum", async () => {
		const { pem, privateKey } = keyPair();
		serveRelease({
			privateKey,
			listed: { "pi-bolt-linux-x64.tar.xz": archive, "install.sh": script },
			installer: script,
		});
		const installer = await fetchReleaseInstaller(VERSION, "install.sh", pem);
		expect(installer.name).toBe("install.sh");
		expect(installer.script?.equals(script)).toBe(true);
		expect(requested).toEqual([`${BASE}/SHA256SUMS`, `${BASE}/SHA256SUMS.sig`, `${BASE}/install.sh`]);
	});

	test("refuses an installer that is not the one SHA256SUMS lists", async () => {
		const { pem, privateKey } = keyPair();
		serveRelease({
			privateKey,
			listed: { "install.sh": script },
			installer: Buffer.from("#!/bin/sh\necho something else\n"),
		});
		await expect(fetchReleaseInstaller(VERSION, "install.sh", pem)).rejects.toThrow(
			"install.sh is not the one the release's signed SHA256SUMS lists",
		);
	});

	test("refuses checksums signed by another key, before downloading the installer", async () => {
		const { pem } = keyPair();
		const other = keyPair();
		serveRelease({ privateKey: other.privateKey, listed: { "install.sh": script }, installer: script });
		await expect(fetchReleaseInstaller(VERSION, "install.sh", pem)).rejects.toThrow("signature does not verify");
		expect(requested).not.toContain(`${BASE}/install.sh`);
	});

	test("refuses checksums with no signature, or another release's", async () => {
		const { pem, privateKey } = keyPair();
		serveRelease({ privateKey, listed: { "install.sh": script }, installer: script });
		served.delete(`${BASE}/SHA256SUMS.sig`);
		await expect(fetchReleaseInstaller(VERSION, "install.sh", pem)).rejects.toThrow("has no signature");
		serveRelease({ privateKey, listed: { "install.sh": script }, installer: script, version: "1.0.1" });
		await expect(fetchReleaseInstaller(VERSION, "install.sh", pem)).rejects.toThrow(
			`the download is Pi-Bolt 1.0.1, not ${VERSION}`,
		);
	});

	test("a release that does not list its installer (up to 1.0.1) gives none, without downloading it", async () => {
		const { pem, privateKey } = keyPair();
		serveRelease({ privateKey, listed: { "pi-bolt-linux-x64.tar.xz": archive }, installer: script });
		const installer = await fetchReleaseInstaller(VERSION, "install.sh", pem);
		expect(installer).toEqual({ name: "install.sh" });
		expect(requested).not.toContain(`${BASE}/install.sh`);
		// Its signature is still checked.
		await expect(fetchReleaseInstaller(VERSION, "install.sh", keyPair().pem)).rejects.toThrow(
			"signature does not verify",
		);
	});

	test("checks install.ps1 the same way", async () => {
		const { pem, privateKey } = keyPair();
		const ps1 = Buffer.from("Write-Host installing\n");
		serveRelease({ privateKey, listed: { "install.ps1": ps1 } });
		served.set(`${BASE}/install.ps1`, ps1);
		expect((await fetchReleaseInstaller(VERSION, "install.ps1", pem)).script?.equals(ps1)).toBe(true);
		served.set(`${BASE}/install.ps1`, Buffer.from("Write-Host other\n"));
		await expect(fetchReleaseInstaller(VERSION, "install.ps1", pem)).rejects.toThrow("install.ps1 is not the one");
	});
});

describe.skipIf(process.platform === "win32")("runReleaseInstaller", () => {
	let dir: string;
	beforeEach(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-bolt-installer-test-"));
	});
	afterEach(() => {
		rmSync(dir, { recursive: true, force: true });
	});

	test("runs the verified bytes from a private file, removed after", async () => {
		const marker = join(dir, "ran");
		const script = Buffer.from(`printf '%s\\n%s\\n' "$0" "$PIBOLT_VERSION" > '${marker}'\nexit 3\n`);
		const status = await runReleaseInstaller(
			{ name: "install.sh", script },
			{ ...process.env, PIBOLT_VERSION: `bolt-v${VERSION}` },
		);
		expect(status).toBe(3);
		const [file, version] = readFileSync(marker, "utf8").trim().split("\n");
		expect(version).toBe(`bolt-v${VERSION}`);
		expect(file.endsWith("/install.sh")).toBe(true);
		expect(existsSync(dirname(file))).toBe(false);
		// Nothing was downloaded: the script is the one given.
		expect(requested).toEqual([]);
	});
});

describe("piBoltInstallerProcess with a verified installer", () => {
	const realPlatform = process.platform;
	afterEach(() => {
		Object.defineProperty(process, "platform", { value: realPlatform, configurable: true });
	});

	test("runs that file, and downloads nothing", () => {
		Object.defineProperty(process, "platform", { value: "linux", configurable: true });
		expect(piBoltInstallerProcess("/tmp/pi-bolt-installer-x/install.sh")).toEqual({
			command: "sh",
			args: ["/tmp/pi-bolt-installer-x/install.sh"],
		});
		Object.defineProperty(process, "platform", { value: "win32", configurable: true });
		// (The command's path is built with this platform's path module: only the PowerShell it runs is checked here.)
		const { args } = piBoltInstallerProcess("C:\\Users\\o'neil\\AppData\\Local\\Temp\\x\\install.ps1");
		expect(args.at(-1)).toContain(
			"iex ([IO.File]::ReadAllText('C:\\Users\\o''neil\\AppData\\Local\\Temp\\x\\install.ps1')); exit $LASTEXITCODE",
		);
		expect(args.at(-1)).not.toContain("irm");
	});
});

describe("piBoltUpdateEnvironment", () => {
	test("does not pass PIBOLT_UNVERIFIED to the installer", () => {
		const saved = process.env.PIBOLT_UNVERIFIED;
		process.env.PIBOLT_UNVERIFIED = "1";
		try {
			expect(piBoltUpdateEnvironment(VERSION).PIBOLT_UNVERIFIED).toBeUndefined();
			expect("PIBOLT_UNVERIFIED" in piBoltUpdateEnvironment(VERSION)).toBe(false);
		} finally {
			if (saved === undefined) delete process.env.PIBOLT_UNVERIFIED;
			else process.env.PIBOLT_UNVERIFIED = saved;
		}
	});

	// An opt-in left in the user's environment is not one for the update's download; nor is a variable that only a later
	// installer reads.
	test("passes on only the installers' variables it names, and everything else", () => {
		const set: Record<string, string> = {
			PIBOLT_ALLOW_UNSIGNED: "1",
			PIBOLT_UNVERIFIED: "0".repeat(64),
			PIBOLT_DOWNLOAD_BASE: "http://mirror.test",
			PIBOLT_SOURCE: "github",
			PIBOLT_LAUNCHER: "1",
			PIBOLT_INSTALLER_NO_MAIN: "1",
			PIBOLT_SOMETHING_LATER: "1",
			PIBOLT_CHANNEL: "beta",
			PIBOLT_NPM_REGISTRY: "https://registry.test",
			PIBOLT_CONNECTIONS: "2",
			PIBOLT_EXTENSIONS: "no",
			NOT_PIBOLT_VARIABLE: "kept",
		};
		const saved = Object.fromEntries(Object.keys(set).map((name) => [name, process.env[name]]));
		Object.assign(process.env, set);
		try {
			const env = piBoltUpdateEnvironment(VERSION);
			for (const name of [
				"PIBOLT_ALLOW_UNSIGNED",
				"PIBOLT_UNVERIFIED",
				"PIBOLT_DOWNLOAD_BASE",
				"PIBOLT_SOURCE",
				"PIBOLT_LAUNCHER",
				"PIBOLT_INSTALLER_NO_MAIN",
				"PIBOLT_SOMETHING_LATER",
			]) {
				expect(name in env, name).toBe(false);
			}
			expect(env.PIBOLT_CHANNEL).toBe("beta");
			expect(env.PIBOLT_NPM_REGISTRY).toBe("https://registry.test");
			expect(env.PIBOLT_CONNECTIONS).toBe("2");
			expect(env.PIBOLT_EXTENSIONS).toBe("no");
			expect(env.NOT_PIBOLT_VARIABLE).toBe("kept");
			expect(env.PATH).toBe(process.env.PATH);
			expect(env.PIBOLT_VERSION).toBe(`bolt-v${VERSION}`);
			expect(env.PIBOLT_YES).toBe("1");
		} finally {
			for (const [name, value] of Object.entries(saved)) {
				if (value === undefined) delete process.env[name];
				else process.env[name] = value;
			}
		}
	});
});
