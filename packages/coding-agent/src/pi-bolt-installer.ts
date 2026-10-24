/**
 * The installer `pi-bolt update` runs (install.sh; on Windows, install.ps1 for an installation outside the user's profile), taken
 * from the release it updates to and checked before it runs: the release's SHA256SUMS must verify with the release key built
 * into this executable (pi-bolt-update-windows.ts, as for the Windows update), and the installer's SHA-256 must be the one it
 * lists. The bytes checked are the bytes run: they are saved to a private temporary folder and the installer is started from
 * there, not downloaded again.
 *
 * Releases up to 1.0.1 do not list their installers in SHA256SUMS. For those, the update runs the installer the site serves, as
 * it did before, with a notice.
 */
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { PIBOLT_RELEASE_KEY, piBoltInstallerProcess } from "./pi-bolt.ts";
import { download, fetchSignedSums, releaseDownloadBase, sha256, sumsEntry } from "./pi-bolt-update-windows.ts";
import { spawnProcess, waitForChildProcess } from "./utils/child-process.ts";

/** The installer of this platform, by its name in a release. */
export const PIBOLT_INSTALLER_NAME = process.platform === "win32" ? "install.ps1" : "install.sh";

/** Release `version`'s installer: its verified bytes, or no `script` when the release does not list one in SHA256SUMS. */
export interface ReleaseInstaller {
	name: string;
	script?: Buffer;
}

/**
 * Release `version`'s installer (`name`), checked against the release's signed SHA256SUMS. Throws, with nothing run, when the
 * checksums are not signed by `publicKeyPem` or not that release's, or when the installer is not the one they list.
 */
export async function fetchReleaseInstaller(
	version: string,
	name: string = PIBOLT_INSTALLER_NAME,
	publicKeyPem: string = PIBOLT_RELEASE_KEY,
): Promise<ReleaseInstaller> {
	const sums = await fetchSignedSums(version, publicKeyPem);
	const expected = sumsEntry(sums, name);
	if (!expected) return { name };
	const url = `${releaseDownloadBase(version)}/${name}`;
	const script = await download(url, undefined);
	if (!script) throw new Error(`download failed: ${url}. Nothing was installed.`);
	if (sha256(script) !== expected) {
		throw new Error(`${name} is not the one the release's signed SHA256SUMS lists. Nothing was installed.`);
	}
	return { name, script };
}

/**
 * Runs `installer` with `env` and resolves with its exit status: the verified script from a private temporary folder (removed
 * after), or, for a release that does not list its installer, the one the site serves.
 */
export async function runReleaseInstaller(installer: ReleaseInstaller, env: NodeJS.ProcessEnv): Promise<number | null> {
	let folder: string | undefined;
	try {
		let file: string | undefined;
		if (installer.script) {
			folder = mkdtempSync(join(tmpdir(), "pi-bolt-installer-"));
			file = join(folder, installer.name);
			writeFileSync(file, installer.script, { mode: 0o600 });
		}
		const { command, args } = piBoltInstallerProcess(file);
		// (From the home folder: the installer has nothing to do with the folder the update was asked for in.)
		return await waitForChildProcess(spawnProcess(command, args, { cwd: homedir(), stdio: "inherit", env }));
	} finally {
		if (folder) rmSync(folder, { recursive: true, force: true });
	}
}
