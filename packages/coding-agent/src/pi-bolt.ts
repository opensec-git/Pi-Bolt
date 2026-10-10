/**
 * Pi-Bolt: Pi compiled ahead of time (https://github.com/opensec-git/Pi-Bolt).
 *
 * scripts/build-pi.sh defines PIBOLT_BUILD when it compiles this tree ("0.3.1 x64 jit-off": the Pi-Bolt version, the CPU
 * variant (x64, x64-baseline, x64-jit, arm64), and whether the JIT is on). In every other build it is undefined and nothing here changes what Pi does.
 */
import { homedir } from "node:os";
import { basename, dirname, join } from "node:path";

declare const PIBOLT_BUILD: string | undefined;

export interface PiBoltBuild {
	version: string;
	variant: string;
	jit: boolean;
}

export const PIBOLT: PiBoltBuild | undefined = parse(typeof PIBOLT_BUILD === "string" ? PIBOLT_BUILD : undefined);

export const PIBOLT_INSTALL_URL =
	process.platform === "win32" ? "https://pi-bolt.opensec.in/install.ps1" : "https://pi-bolt.opensec.in/install.sh";
// TLS 1.2 before the first download: Windows PowerShell 5.1 on an older Windows 10 offers only TLS 1.0 by default, which the
// site refuses, and the installer turns it on only once it is running.
const WINDOWS_TLS12 =
	"[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072";
export const PIBOLT_INSTALL_COMMAND =
	process.platform === "win32"
		? `powershell -c "${WINDOWS_TLS12}; irm ${PIBOLT_INSTALL_URL} | iex"`
		: `curl -fsSL ${PIBOLT_INSTALL_URL} | sh`;
/** The installer as a command to spawn (with piBoltUpdateEnvironment()): it exits non-zero if it did not install. */
export function piBoltInstallerProcess(): { command: string; args: string[] } {
	if (process.platform === "win32") {
		// By its full path: a bare name may be looked up in the current folder first, where anything could be called that.
		return {
			command: join(
				process.env.SystemRoot ?? "C:\\Windows",
				"System32",
				"WindowsPowerShell",
				"v1.0",
				"powershell.exe",
			),
			// (If the download fails, nothing was installed: that is an exit code too. `irm ... | iex; exit $LASTEXITCODE` exited 0,
			// as no program had run to set it.)
			args: [
				"-NoProfile",
				"-Command",
				`${WINDOWS_TLS12}; try { $installer = irm ${PIBOLT_INSTALL_URL} -ErrorAction Stop } catch { Write-Error $_; exit 1 }; iex $installer; exit $LASTEXITCODE`,
			],
		};
	}
	return { command: "sh", args: ["-c", PIBOLT_INSTALL_COMMAND] };
}
export const PIBOLT_RELEASES_URL = "https://github.com/opensec-git/Pi-Bolt/releases";
export const PIBOLT_LATEST_RELEASE_API = "https://api.github.com/repos/opensec-git/Pi-Bolt/releases/latest";

function parse(build: string | undefined): PiBoltBuild | undefined {
	if (!build) return undefined;
	const [version, variant = "x64", jit = "jit-off"] = build.split(" ");
	return { version, variant, jit: jit === "jit-on" };
}

/** "0.3.1" from the release tag "bolt-v0.3.1"; undefined for anything else. */
export function piBoltVersionOfTag(tag: unknown): string | undefined {
	if (typeof tag !== "string") return undefined;
	const match = /^bolt-v(\d+\.\d+\.\d+)$/.exec(tag.trim());
	return match ? match[1] : undefined;
}

/**
 * How this Pi-Bolt was installed: with the npm package (whose launcher keeps the executable under $PIBOLT_HOME/npm/<version>;
 * on Windows, whose install script puts it in the package's own bin folder, where npm's shims start it directly), or with the
 * installer (anything else).
 */
export function piBoltInstallMethod(): "npm" | "installer" {
	if (process.env.PIBOLT_NPM === "1") return "npm";
	const execPath = process.execPath;
	if (/[\\/]npm[\\/]\d+\.\d+\.\d+[\\/]pi-bolt-(linux|darwin|win32)-/.test(execPath)) return "npm";
	return /[\\/]node_modules[\\/]pi-bolt[\\/]bin[\\/]pi-bolt\.exe$/i.test(execPath) ? "npm" : "installer";
}

/**
 * Where this installation is: the folder that holds its variant's folder (~/.pi-bolt/pi-bolt-linux-x64/pi, or
 * pi-bolt-darwin-arm64/pi-bin, pi-bolt-win32-x64\pi-bolt.exe -> ~/.pi-bolt), wherever that is (~/.pi-bolt, /opt/pi-bolt);
 * undefined for an executable that is not in one (a build run from its output folder).
 */
export function piBoltInstallDir(): string | undefined {
	const variantDir = dirname(process.execPath);
	return /^pi-bolt-(linux|darwin|win32)-/i.test(basename(variantDir)) ? dirname(variantDir) : undefined;
}

/**
 * The environment that makes the installer replace this installation with release `version`, keeping its variant and place.
 * The version is the one the update was decided on (not "latest" again), and what in the user's environment would change
 * where the release comes from (a mirror, which may be unsigned; the npm launcher's mode; the source) is left out.
 */
export function piBoltUpdateEnvironment(version: string): NodeJS.ProcessEnv {
	const env: NodeJS.ProcessEnv = {
		...process.env,
		PIBOLT_YES: "1",
		PIBOLT_NO_START: "1",
		PIBOLT_VARIANT: PIBOLT?.variant ?? "x64",
		PIBOLT_VERSION: `bolt-v${version}`,
		PIBOLT_INSTALL: piBoltInstallDir() ?? process.env.PIBOLT_INSTALL ?? join(homedir(), ".pi-bolt"),
	};
	// (And what would make the installer only define its functions, install nothing, and exit 0.)
	for (const name of [
		"PIBOLT_DOWNLOAD_BASE",
		"PIBOLT_LAUNCHER",
		"PIBOLT_SOURCE",
		"PIBOLT_ALLOW_UNSIGNED",
		"PIBOLT_INSTALLER_NO_MAIN",
	]) {
		delete env[name];
	}
	return env;
}

/** The executable the installer puts in `installDir` for this variant (on macOS, the launcher that starts pi-bin). */
export function piBoltExecutableIn(installDir: string): string {
	const folder = `pi-bolt-${process.platform}-${PIBOLT?.variant ?? "x64"}`;
	return join(installDir, folder, process.platform === "win32" ? "pi-bolt.exe" : "pi");
}
