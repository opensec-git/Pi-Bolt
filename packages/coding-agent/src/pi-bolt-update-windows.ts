/**
 * `pi-bolt update` on Windows, without PowerShell.
 *
 * Updates used to start the installer as `powershell -Command "irm https://.../install.ps1 | iex"`. A program that starts
 * PowerShell to download a script and run it with iex is what a malware "download cradle" does, and behavior-based antivirus
 * stops it as such: Kaspersky's System Watcher reports PDM:Trojan.Win32.Generic for pi-bolt.exe during `pi-bolt update`
 * (#82). So Pi-Bolt updates itself here, doing what install.ps1 does for an update (PIBOLT_YES, PIBOLT_NO_PATH: no questions,
 * no PATH, no extensions): the signed SHA256SUMS, the release's .zip from npm or else GitHub, checked against them, unpacked
 * by Windows' own tar.exe, the build it replaces kept as <folder>.previous for `pi-bolt update --rollback`, and the new files
 * renamed into place the way install.ps1 does it, the executable last.
 *
 * An installation outside the user's profile still goes through the installer: install.ps1 checks and sets who may change
 * such a folder (Windows ACLs), which is not done here. That is the release's own install.ps1, checked against the same signed
 * SHA256SUMS before it runs (pi-bolt-installer.ts).
 */
import { spawnSync } from "node:child_process";
import { createHash, createPublicKey, verify } from "node:crypto";
import {
	copyFileSync,
	cpSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	renameSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, relative } from "node:path";
import { gunzipSync } from "node:zlib";
import chalk from "chalk";
import { PIBOLT_RELEASE_KEY } from "./pi-bolt.ts";

const REPO = "https://github.com/opensec-git/Pi-Bolt";
const ASIDE = /\.pibolt-(old|new)$/;

/** Whether `dir` is under the user's profile (%USERPROFILE% or %LOCALAPPDATA%), as install.ps1's Test-PiBoltInProfile. */
export function isInProfile(dir: string, env: NodeJS.ProcessEnv = process.env): boolean {
	const lower = dir.toLowerCase();
	return [env.USERPROFILE, env.LOCALAPPDATA].some(
		(root) => !!root && lower.startsWith(`${root.replace(/[\\/]+$/, "").toLowerCase()}\\`),
	);
}

/** Whether the signed checksums' signature verifies with the release key (an Ed25519 public key in PEM). */
export function sumsSignatureValid(
	sums: Buffer,
	signature: Buffer,
	publicKeyPem: string = PIBOLT_RELEASE_KEY,
): boolean {
	try {
		return verify(null, sums, createPublicKey(publicKeyPem), signature);
	} catch {
		return false;
	}
}

/** The release SHA256SUMS says it is ("# pi-bolt 0.9.6" on its first line), or undefined. */
export function sumsVersion(sums: string): string | undefined {
	return /^# pi-bolt (\d+\.\d+\.\d+)\r?$/.exec(sums.split("\n", 1)[0] ?? "")?.[1];
}

/** The SHA-256 SHA256SUMS lists for `name` (lowercase hex), or undefined. */
export function sumsEntry(sums: string, name: string): string | undefined {
	for (const line of sums.split(/\r?\n/)) {
		const match = /^([0-9a-fA-F]{64}) [ *]?(.+)$/.exec(line);
		if (match && match[2] === name) return match[1].toLowerCase();
	}
	return undefined;
}

export function sha256(data: Buffer): string {
	return createHash("sha256").update(data).digest("hex");
}

/**
 * One file, by its path in the archive, from a .tgz (what npm publishes); undefined if it is not there or the archive is not
 * one. Only what that file needs is read: the tarball itself is not verified, the file in it is (by the signed checksums).
 */
export function tgzEntry(tgz: Buffer, path: string): Buffer | undefined {
	let tar: Buffer;
	try {
		tar = gunzipSync(tgz);
	} catch {
		return undefined;
	}
	let offset = 0;
	let longName: string | undefined;
	while (offset + 512 <= tar.length) {
		const header = tar.subarray(offset, offset + 512);
		if (header.every((byte) => byte === 0)) return undefined;
		const field = (start: number, length: number) =>
			header
				.subarray(start, start + length)
				.toString("utf8")
				.replace(/\0.*$/s, "");
		const size = Number.parseInt(field(124, 12).trim() || "0", 8);
		const type = field(156, 1);
		const prefix = field(345, 155);
		const name = longName ?? (prefix ? `${prefix}/${field(0, 100)}` : field(0, 100));
		longName = undefined;
		const body = offset + 512;
		if (!Number.isFinite(size) || size < 0 || body + size > tar.length) return undefined;
		if (type === "L")
			longName = tar
				.subarray(body, body + size)
				.toString("utf8")
				.replace(/\0.*$/s, "");
		else if ((type === "0" || type === "") && name === path) return tar.subarray(body, body + size);
		offset = body + Math.ceil(size / 512) * 512;
	}
	return undefined;
}

/** Every file below `dir`, relative to it. */
function filesIn(dir: string): string[] {
	const files: string[] = [];
	const walk = (folder: string): void => {
		for (const entry of readdirSync(folder, { withFileTypes: true })) {
			const path = join(folder, entry.name);
			if (entry.isDirectory()) walk(path);
			else files.push(relative(dir, path));
		}
	};
	walk(dir);
	return files;
}

/** Moves a file, copying it when it is on another drive (as %TEMP% may be). */
function moveFile(from: string, to: string): void {
	try {
		renameSync(from, to);
	} catch (error) {
		if ((error as NodeJS.ErrnoException).code !== "EXDEV") throw error;
		copyFileSync(from, to);
		rmSync(from, { force: true });
	}
}

/**
 * Moves the new build in `from` into place over the one in `to`, as install.ps1's Install-PiBoltFiles: a running Pi-Bolt keeps
 * its executable and DLLs open, and Windows lets those be renamed but not replaced or deleted. Each new file is first moved
 * next to its target, then the old one renamed aside, then the new one renamed into place (if that fails, the old one is put
 * back); pi-bolt.exe last, so that until it is in place the one that runs is the old one. What was renamed aside is deleted
 * now if it can be, or by the next install.
 */
export function installFiles(from: string, to: string, stamp: number = Date.now()): void {
	if (existsSync(to)) {
		for (const file of filesIn(to)) if (ASIDE.test(file)) rmSync(join(to, file), { force: true });
	}
	mkdirSync(to, { recursive: true });
	const files = filesIn(from).sort(
		(a, b) => Number(/(^|[\\/])pi-bolt\.exe$/i.test(a)) - Number(/(^|[\\/])pi-bolt\.exe$/i.test(b)),
	);
	for (const file of files) {
		const target = join(to, file);
		const staged = `${target}.${stamp}.pibolt-new`;
		const aside = `${target}.${stamp}.pibolt-old`;
		try {
			mkdirSync(dirname(target), { recursive: true });
			moveFile(join(from, file), staged);
			if (existsSync(target)) renameSync(target, aside);
			try {
				renameSync(staged, target);
			} catch (error) {
				if (existsSync(aside) && !existsSync(target)) renameSync(aside, target);
				throw error;
			}
			try {
				rmSync(aside, { force: true });
			} catch {
				// Still running (this executable): the next install deletes it.
			}
		} catch (error) {
			rmSync(staged, { force: true });
			throw new Error(
				`could not put ${target} in place (${(error as Error).message}). Close Pi-Bolt and anything that has its files open, then run \`pi-bolt update\` again.`,
			);
		}
	}
}

/**
 * Keeps the build in `dir` as <dir>.previous for `pi-bolt update --rollback`, unless the incoming build is the same one (the same
 * pi-bolt.txt). Copied, since the running Pi-Bolt keeps its folder from being renamed. A note, not an error, if it cannot be.
 */
export function keepPrevious(dir: string, incoming: string, note: (text: string) => void = () => {}): void {
	if (!existsSync(join(dir, "pi-bolt.exe"))) return;
	const read = (folder: string) => {
		try {
			return readFileSync(join(folder, "pi-bolt.txt"), "utf8");
		} catch {
			return undefined;
		}
	};
	const current = read(dir);
	if (current !== undefined && current === read(incoming)) return;
	const previous = `${dir}.previous`;
	try {
		rmSync(previous, { recursive: true, force: true });
		cpSync(dir, previous, { recursive: true, filter: (source) => !ASIDE.test(source) });
	} catch (error) {
		note(
			`the build this replaces was not kept (${(error as Error).message}): pi-bolt update --rollback has none to go back to`,
		);
	}
}

/** A file over HTTPS, or undefined if it could not be downloaded whole; with `what`, a progress line on a terminal. */
export async function download(url: string, what: string | undefined): Promise<Buffer | undefined> {
	let response: Response;
	try {
		response = await fetch(url, { headers: { "user-agent": "pi-bolt-updater" }, redirect: "follow" });
	} catch {
		return undefined;
	}
	if (!response.ok || !response.body) return undefined;
	const total = Number(response.headers.get("content-length")) || 0;
	const chunks: Buffer[] = [];
	let got = 0;
	let shown = 0;
	const reader = response.body.getReader();
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		chunks.push(Buffer.from(value));
		got += value.length;
		if (what && process.stdout.isTTY && Date.now() - shown >= 100) {
			shown = Date.now();
			const mb = (n: number) => (n / 1048576).toFixed(1);
			process.stdout.write(`\r\x1b[K  ${chalk.dim(`${what} ${mb(got)}${total ? ` / ${mb(total)}` : ""} MB`)}`);
		}
	}
	if (what && process.stdout.isTTY) process.stdout.write("\r\x1b[K");
	if (total && got !== total) return undefined;
	return Buffer.concat(chunks);
}

/** Where release `version`'s files are downloaded from. */
export function releaseDownloadBase(version: string): string {
	return `${REPO}/releases/download/bolt-v${version}`;
}

/**
 * Release `version`'s SHA256SUMS, once its signature (SHA256SUMS.sig) verifies with the release key and its first line names
 * that version. Throws otherwise: nothing else is looked at in a release whose checksums are not Pi-Bolt's.
 */
export async function fetchSignedSums(version: string, publicKeyPem: string = PIBOLT_RELEASE_KEY): Promise<string> {
	const base = releaseDownloadBase(version);
	const sumsBytes = await download(`${base}/SHA256SUMS`, undefined);
	if (!sumsBytes) throw new Error(`download failed: ${base}/SHA256SUMS`);
	const signature = await download(`${base}/SHA256SUMS.sig`, undefined);
	if (!signature)
		throw new Error(
			"the release has no signature (SHA256SUMS.sig), and every release since 0.6.0 is signed. Nothing was installed.",
		);
	if (!sumsSignatureValid(sumsBytes, signature, publicKeyPem)) {
		throw new Error("the release's signature does not verify: the download is not Pi-Bolt's. Nothing was installed.");
	}
	// The first line says which release the signed files are: an older release (signed all the same) is not taken for a newer one.
	const sums = sumsBytes.toString("utf8");
	const signedVersion = sumsVersion(sums);
	if (signedVersion !== version) {
		throw new Error(
			`the download is Pi-Bolt ${signedVersion ?? "(unknown)"}, not ${version}. Nothing was installed.`,
		);
	}
	return sums;
}

/** Updates the installation in `installDir` (its `variant` folder) to release `version`. Throws with what went wrong. */
export async function updatePiBoltWindows(version: string, installDir: string, variant: string): Promise<void> {
	const name = `pi-bolt-win32-${variant}`;
	const archive = `${name}.zip`;
	const dir = join(installDir, name);
	const base = releaseDownloadBase(version);
	const sums = await fetchSignedSums(version);
	const expected = sumsEntry(sums, archive);
	if (!expected)
		throw new Error(`this release has no build for this installation (${archive} is not in its SHA256SUMS)`);
	// From npm (its package of this build is a .tgz with the release's .zip in it), else from GitHub, as install.ps1 does.
	let zip: Buffer | undefined;
	let from = "npm";
	const registry = (process.env.PIBOLT_NPM_REGISTRY || "https://registry.npmjs.org").replace(/\/+$/, "");
	const tgz = await download(`${registry}/@kushalkhemka/${name}/-/${name}-${version}.tgz`, "downloading");
	const fromNpm = tgz ? tgzEntry(tgz, `package/${archive}`) : undefined;
	if (fromNpm && sha256(fromNpm) === expected) zip = fromNpm;
	if (!zip) {
		console.log(chalk.dim("  not available from npm; downloading from GitHub instead"));
		from = "GitHub";
		zip = await download(`${base}/${archive}`, "downloading");
		if (!zip) throw new Error(`download failed: ${base}/${archive}`);
		if (sha256(zip) !== expected) throw new Error("checksum mismatch: the download is corrupt or incomplete");
	}
	const tmp = mkdtempSync(join(tmpdir(), "pi-bolt-update-"));
	try {
		const zipFile = join(tmp, archive);
		writeFileSync(zipFile, zip);
		const unpacked = join(tmp, "unpacked");
		mkdirSync(unpacked);
		// Windows' own tar (bsdtar, in System32 since Windows 10 1803) reads .zip.
		const tar = join(process.env.SystemRoot ?? "C:\\Windows", "System32", "tar.exe");
		const extracted = spawnSync(tar, ["-xf", zipFile, "-C", unpacked], { encoding: "utf8" });
		if (extracted.status !== 0)
			throw new Error(`could not unpack ${archive}: ${(extracted.stderr || String(extracted.error ?? "")).trim()}`);
		const build = join(unpacked, name);
		const exe = join(build, "pi-bolt.exe");
		if (!existsSync(exe)) throw new Error(`${archive} does not have ${name}\\pi-bolt.exe`);
		if (spawnSync(exe, ["--version"], { stdio: "ignore" }).status !== 0)
			throw new Error("the downloaded executable does not run on this system");
		keepPrevious(dir, build, (text) => console.log(chalk.dim(`  ${text}`)));
		installFiles(build, dir);
		console.log(
			`  ${chalk.green("ok")} install complete ${chalk.dim(`(win32-${variant}, ${(statSync(zipFile).size / 1048576).toFixed(1)} MB from ${from}, signature verified)`)}`,
		);
	} finally {
		rmSync(tmp, { recursive: true, force: true });
	}
	// Pi-Bolt keeps the packages only it runs out of Pi's list as it starts: once now, as install.ps1 does after an install.
	spawnSync(join(dir, "pi-bolt.exe"), ["list"], { cwd: dir, stdio: "ignore" });
}
