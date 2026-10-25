/**
 * Pi-Bolt: Pi compiled ahead of time (https://github.com/opensec-git/Pi-Bolt).
 *
 * scripts/build-pi.sh defines PIBOLT_BUILD when it compiles this tree ("0.3.1 x64 jit-off e0f307e3d3f66944": the Pi-Bolt
 * version, the CPU variant (x64, x64-baseline, x64-jit, arm64), whether the JIT is on, and the runtime stamp of the engine it
 * was built for, scripts/runtime-stamp.sh, when known). In every other build it is undefined and nothing here changes what Pi
 * does.
 */
import { readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join } from "node:path";

declare const PIBOLT_BUILD: string | undefined;

export interface PiBoltBuild {
	version: string;
	variant: string;
	jit: boolean;
	runtimeStamp?: string;
}

export const PIBOLT: PiBoltBuild | undefined = parsePiBoltBuild(
	typeof PIBOLT_BUILD === "string" ? PIBOLT_BUILD : undefined,
);

export const PIBOLT_INSTALL_URL =
	process.platform === "win32" ? "https://pi-bolt.opensec.in/install.ps1" : "https://pi-bolt.opensec.in/install.sh";
// TLS 1.2 before the first download: Windows PowerShell 5.1 on an older Windows 10 offers only TLS 1.0 by default, which the
// site refuses, and the installer turns it on only once it is running. Only for the update's own process: the command shown to
// users is the plain one (README), which Windows' antivirus does not mistake for a downloader.
const WINDOWS_TLS12 =
	"[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072";
export const PIBOLT_INSTALL_COMMAND =
	process.platform === "win32"
		? `powershell -c "irm ${PIBOLT_INSTALL_URL} | iex"`
		: `curl -fsSL ${PIBOLT_INSTALL_URL} | sh`;
/**
 * The installer as a command to spawn (with piBoltUpdateEnvironment()): it exits non-zero if it did not install. With
 * `installerFile`, the release's installer already checked against its signed checksums (pi-bolt-installer.ts) is run from that
 * file; without it, the one the site serves is downloaded and run (a release up to 1.0.1 does not list its installer).
 */
export function piBoltInstallerProcess(installerFile?: string): { command: string; args: string[] } {
	if (process.platform === "win32") {
		// By its full path: a bare name may be looked up in the current folder first, where anything could be called that.
		const command = join(
			process.env.SystemRoot ?? "C:\\Windows",
			"System32",
			"WindowsPowerShell",
			"v1.0",
			"powershell.exe",
		);
		if (installerFile) {
			// Run as `irm ... | iex` runs it (install.ps1 is written for iex), from the file's text: ReadAllText reads it as UTF-8.
			const path = installerFile.replace(/'/g, "''");
			return {
				command,
				args: [
					"-NoProfile",
					"-Command",
					`${WINDOWS_TLS12}; iex ([IO.File]::ReadAllText('${path}')); exit $LASTEXITCODE`,
				],
			};
		}
		// (If the download fails, nothing was installed: that is an exit code too. `irm ... | iex; exit $LASTEXITCODE` exited 0,
		// as no program had run to set it.)
		return {
			command,
			args: [
				"-NoProfile",
				"-Command",
				`${WINDOWS_TLS12}; try { $installer = irm ${PIBOLT_INSTALL_URL} -ErrorAction Stop } catch { Write-Error $_; exit 1 }; iex $installer; exit $LASTEXITCODE`,
			],
		};
	}
	if (installerFile) return { command: "sh", args: [installerFile] };
	// With curl or wget, whichever there is (the installer itself works with either); a failed download is an exit code too.
	const url = PIBOLT_INSTALL_URL;
	return {
		command: "sh",
		args: [
			"-c",
			`if command -v curl >/dev/null 2>&1; then s=$(curl -fsSL ${url}) || exit 1; ` +
				`elif command -v wget >/dev/null 2>&1; then s=$(wget -qO- ${url}) || exit 1; ` +
				`else echo "curl or wget is needed to download ${url}" >&2; exit 1; fi; printf '%s\\n' "$s" | sh`,
		],
	};
}
export const PIBOLT_RELEASES_URL = "https://github.com/opensec-git/Pi-Bolt/releases";
/** The key releases' SHA256SUMS are signed with (keys/release.pub; the installers carry it too). */
export const PIBOLT_RELEASE_KEY = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAoLboJqtKaoISPqffk03vHZr+1sRBG3uIRIWeKOew+aY=
-----END PUBLIC KEY-----
`;
/** Redirects to the latest release's page (.../releases/tag/bolt-v1.0.2); not counted against GitHub's API limit. */
export const PIBOLT_LATEST_RELEASE_URL = `${PIBOLT_RELEASES_URL}/latest`;
/** The releases, pre-releases among them, as an Atom feed; not counted against GitHub's API limit either. */
export const PIBOLT_RELEASES_FEED = `${PIBOLT_RELEASES_URL}.atom`;
export const PIBOLT_LATEST_RELEASE_API = "https://api.github.com/repos/opensec-git/Pi-Bolt/releases/latest";
/** The releases, newest first, pre-releases among them (drafts are not shown to anyone without write access). */
export const PIBOLT_RELEASES_API = "https://api.github.com/repos/opensec-git/Pi-Bolt/releases?per_page=30";

/**
 * Which releases `pi-bolt update` takes: `stable`, the latest release; or `beta`, the newest one, pre-releases included (a
 * release is staged as a GitHub pre-release before it is made the latest). Kept in <install>/channel; PIBOLT_CHANNEL, as the
 * installers read it, overrides it.
 */
export type PiBoltChannel = "stable" | "beta";

export function isPiBoltChannel(value: unknown): value is PiBoltChannel {
	return value === "stable" || value === "beta";
}

export function piBoltChannel(): PiBoltChannel {
	const fromEnv = process.env.PIBOLT_CHANNEL?.trim();
	if (isPiBoltChannel(fromEnv)) return fromEnv;
	const installDir = piBoltInstallDir();
	if (!installDir) return "stable";
	try {
		const saved = readFileSync(join(installDir, "channel"), "utf8").trim();
		return isPiBoltChannel(saved) ? saved : "stable";
	} catch {
		return "stable";
	}
}

/** Saves the channel for this installation (stable is the default, kept as no file). False if it is not in one. */
export function setPiBoltChannel(channel: PiBoltChannel): boolean {
	const installDir = piBoltInstallDir();
	if (!installDir) return false;
	const file = join(installDir, "channel");
	if (channel === "stable") rmSync(file, { force: true });
	else writeFileSync(file, `${channel}\n`);
	return true;
}

export function parsePiBoltBuild(build: string | undefined): PiBoltBuild | undefined {
	if (!build) return undefined;
	const [version, variant = "x64", jit = "jit-off", runtimeStamp] = build.split(" ");
	return { version, variant, jit: jit === "jit-on", ...(runtimeStamp ? { runtimeStamp } : {}) };
}

/** "0.3.1" from the release tag "bolt-v0.3.1"; undefined for anything else. */
export function piBoltVersionOfTag(tag: unknown): string | undefined {
	if (typeof tag !== "string") return undefined;
	const match = /^bolt-v(\d+\.\d+\.\d+)$/.exec(tag.trim());
	return match ? match[1] : undefined;
}

export type PiBoltPackageManagerId = "homebrew" | "scoop" | "mise";
export type PiBoltInstallMethod = "npm" | PiBoltPackageManagerId | "installer";

/** The real path of `path` (through links, and on Windows junctions such as Scoop's `current`), or `path` if it is not there. */
function realPathOf(path: string): string {
	try {
		return realpathSync(path);
	} catch {
		return path;
	}
}

/**
 * `path` relative to `folder` with forward slashes, if it is inside it (or inside its real path), else undefined. Letter case
 * does not count on Windows.
 */
function pathInside(path: string, folder: string): string | undefined {
	const normalize = (p: string) => {
		const slashes = p.replace(/\\/g, "/").replace(/\/+$/, "");
		return process.platform === "win32" ? slashes.toLowerCase() : slashes;
	};
	const inner = normalize(path);
	for (const outer of [folder, realPathOf(folder)].map(normalize)) {
		if (inner.startsWith(`${outer}/`)) return path.replace(/\\/g, "/").slice(outer.length + 1);
	}
	return undefined;
}

/**
 * Whether `path` is in Scoop's folder for Pi-Bolt, <root>\apps\pi-bolt\<version> (with `current` linked to the version in use):
 * <root> is ~\scoop or $SCOOP, and for a global install %ProgramData%\scoop or $SCOOP_GLOBAL.
 */
function isScoopPath(path: string): boolean {
	if (/[\\/]scoop[\\/]apps[\\/]pi-bolt[\\/]/i.test(path)) return true;
	return [process.env.SCOOP, process.env.SCOOP_GLOBAL].some(
		(root) => !!root && pathInside(path, join(root, "apps", "pi-bolt")) !== undefined,
	);
}

/**
 * The tool a Pi-Bolt that mise installed goes by, from the folder mise put it in, <data>/installs/<folder>/<version>: <data> is
 * ~/.local/share/mise (%LOCALAPPDATA%\mise on Windows) or $MISE_DATA_DIR, and <folder> is `pi-bolt` for the registry's
 * shorthand or `github-opensec-git-pi-bolt` for `github:opensec-git/Pi-Bolt`. Undefined if mise did not install it.
 */
export function piBoltMiseTool(path: string = realPathOf(process.execPath)): string | undefined {
	let folder = /[\\/]mise[\\/]installs[\\/]([^\\/]+)[\\/]/.exec(path)?.[1];
	const dataDir = process.env.MISE_DATA_DIR;
	if (folder === undefined && dataDir) folder = pathInside(path, join(dataDir, "installs"))?.split("/")[0];
	if (!folder || !/pi-bolt/i.test(folder)) return undefined;
	return folder.startsWith("github-") ? "github:opensec-git/Pi-Bolt" : "pi-bolt";
}

/**
 * How this Pi-Bolt was installed: with the npm package (whose launcher keeps the executable under $PIBOLT_HOME/npm/<version>;
 * on Windows, whose install script puts it in the package's own bin folder, where npm's shims start it directly), with
 * Homebrew (the executable is in its Cellar, <prefix>/Cellar/pi-bolt/<version>/libexec, wherever a link to it is), with Scoop
 * (in <root>\apps\pi-bolt, started by Scoop's shim), with mise (in <data>/installs/<tool>), or with the installer (anything
 * else).
 */
export function piBoltInstallMethod(): PiBoltInstallMethod {
	if (process.env.PIBOLT_NPM === "1") return "npm";
	const execPath = process.execPath;
	if (/[\\/]npm[\\/]\d+\.\d+\.\d+[\\/]pi-bolt-(linux|darwin|win32)-/.test(execPath)) return "npm";
	if (/[\\/]node_modules[\\/]pi-bolt[\\/]bin[\\/]pi-bolt\.exe$/i.test(execPath)) return "npm";
	const realPath = realPathOf(execPath);
	if (/[\\/]Cellar[\\/]pi-bolt[\\/]/.test(realPath)) return "homebrew";
	if (isScoopPath(realPath)) return "scoop";
	if (piBoltMiseTool(realPath)) return "mise";
	return "installer";
}

/** A package manager that installed this Pi-Bolt. It owns the folder: Pi-Bolt neither updates nor changes it itself. */
export interface PiBoltPackageManager {
	/** Its name in messages: "Homebrew". */
	name: string;
	/** Its command: "brew". */
	command: string;
	/** What it installed Pi-Bolt from: "the opensec-git/tap/pi-bolt formula". */
	source: string;
	update: string;
	uninstall: string;
	/** The command that switches to another version; undefined for Homebrew, whose formula has the latest release only. */
	switchVersion?: string;
}

/** The package manager that installed this Pi-Bolt (or one installed as `method`); undefined for npm and the installer. */
export function piBoltPackageManager(
	method: PiBoltInstallMethod = piBoltInstallMethod(),
): PiBoltPackageManager | undefined {
	switch (method) {
		case "homebrew":
			return {
				name: "Homebrew",
				command: "brew",
				source: "the opensec-git/tap/pi-bolt formula",
				update: "brew upgrade pi-bolt",
				uninstall: "brew uninstall pi-bolt",
			};
		case "scoop":
			// (Scoop keeps the versions it updated from, until `scoop cleanup`: reset switches to one.)
			return {
				name: "Scoop",
				command: "scoop",
				source: "the opensec-git/pi-bolt manifest",
				update: "scoop update pi-bolt",
				uninstall: "scoop uninstall pi-bolt",
				switchVersion: "scoop reset pi-bolt@<version>",
			};
		case "mise": {
			// (--bump: a version pinned in mise's config, as `mise use tool@1.0.0` writes it, is otherwise kept.)
			const tool = piBoltMiseTool() ?? "pi-bolt";
			return {
				name: "mise",
				command: "mise",
				source: tool,
				update: `mise upgrade --bump ${tool}`,
				uninstall: `mise unuse -g ${tool}`,
				switchVersion: `mise use -g ${tool}@<version>`,
			};
		}
		default:
			return undefined;
	}
}

/** The command that updates this Pi-Bolt: its package manager's (`brew upgrade pi-bolt`), else `pi-bolt update`. */
export function piBoltUpdateCommand(method: PiBoltInstallMethod = piBoltInstallMethod()): string {
	return piBoltPackageManager(method)?.update ?? "pi-bolt update";
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
 * where the release comes from (a mirror, which may be unsigned; the npm launcher's mode; the source) is left out. PATH is
 * left as the user set it: the installer would otherwise add this folder without asking (and take other variants off).
 */
export function piBoltUpdateEnvironment(version: string): NodeJS.ProcessEnv {
	const env: NodeJS.ProcessEnv = {
		...process.env,
		PIBOLT_YES: "1",
		PIBOLT_NO_START: "1",
		PIBOLT_NO_PATH: "1",
		PIBOLT_VARIANT: PIBOLT?.variant ?? "x64",
		PIBOLT_VERSION: `bolt-v${version}`,
		PIBOLT_INSTALL: piBoltInstallDir() ?? process.env.PIBOLT_INSTALL ?? join(homedir(), ".pi-bolt"),
	};
	// (And what would make the installer only define its functions, install nothing, and exit 0; or install a release whose
	// signature it could not check.)
	for (const name of [
		"PIBOLT_DOWNLOAD_BASE",
		"PIBOLT_LAUNCHER",
		"PIBOLT_SOURCE",
		"PIBOLT_ALLOW_UNSIGNED",
		"PIBOLT_UNVERIFIED",
		"PIBOLT_INSTALLER_NO_MAIN",
	]) {
		delete env[name];
	}
	return env;
}

/** The JIT build for the CPU of `variant`: x64-jit for x64 and x64-baseline, arm64-jit for arm64. */
export function piBoltJitVariant(variant: string = PIBOLT?.variant ?? "x64"): string {
	if (variant.endsWith("-jit")) return variant;
	return variant.startsWith("arm64") ? "arm64-jit" : "x64-jit";
}

/**
 * The command that installs the JIT build in place of this one, as it was installed: the installer with PIBOLT_VARIANT (which
 * keeps the default build's folder, and links `pi-bolt` to the new one), or for npm, PIBOLT_VARIANT where the package reads it
 * (every run on macOS and Linux; when it is installed on Windows). One a package manager installed is uninstalled for the
 * installer's.
 */
export function piBoltJitSwitchCommand(
	method: PiBoltInstallMethod = piBoltInstallMethod(),
	platform: NodeJS.Platform = process.platform,
	variant: string = piBoltJitVariant(),
): string {
	if (method === "npm") {
		return platform === "win32"
			? `$env:PIBOLT_VARIANT = "${variant}"; npm install -g --allow-scripts=pi-bolt pi-bolt`
			: `export PIBOLT_VARIANT=${variant} (in your shell profile)`;
	}
	// (Homebrew, Scoop and mise install the default build only: the JIT build comes from the installer, in place of theirs.
	// Windows PowerShell 5.1 has no &&.)
	const manager = piBoltPackageManager(method);
	if (platform === "win32") {
		const installer = `$env:PIBOLT_VARIANT = "${variant}"; irm https://pi-bolt.opensec.in/install.ps1 | iex`;
		return manager ? `${manager.uninstall}; ${installer}` : installer;
	}
	const installer = `curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=${variant} sh`;
	return manager ? `${manager.uninstall} && ${installer}` : installer;
}

/** The executable the installer puts in `installDir` for this variant (on macOS, the launcher that starts pi-bin). */
export function piBoltExecutableIn(installDir: string): string {
	const folder = `pi-bolt-${process.platform}-${PIBOLT?.variant ?? "x64"}`;
	return join(installDir, folder, process.platform === "win32" ? "pi-bolt.exe" : "pi");
}
