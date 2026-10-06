/**
 * Pi-Bolt: Pi compiled ahead of time (https://github.com/opensec-git/Pi-Bolt).
 *
 * scripts/build-pi.sh defines PIBOLT_BUILD when it compiles this tree ("0.3.1 x64 jit-off": the Pi-Bolt version, the CPU
 * variant (x64, x64-baseline, x64-jit, arm64), and whether the JIT is on). In every other build it is undefined and nothing here changes what Pi does.
 */
import { homedir } from "node:os";
import { join } from "node:path";

declare const PIBOLT_BUILD: string | undefined;

export interface PiBoltBuild {
	version: string;
	variant: string;
	jit: boolean;
}

export const PIBOLT: PiBoltBuild | undefined = parse(typeof PIBOLT_BUILD === "string" ? PIBOLT_BUILD : undefined);

export const PIBOLT_INSTALL_URL =
	process.platform === "win32" ? "https://pi-bolt.opensec.in/install.ps1" : "https://pi-bolt.opensec.in/install.sh";
export const PIBOLT_INSTALL_COMMAND =
	process.platform === "win32"
		? `powershell -c "irm ${PIBOLT_INSTALL_URL} | iex"`
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
			args: ["-NoProfile", "-Command", `irm ${PIBOLT_INSTALL_URL} | iex; exit $LASTEXITCODE`],
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
 * How this Pi-Bolt was installed: with the npm package (whose launcher keeps the executable under $PIBOLT_HOME/npm/<version>),
 * or with the installer (anything else).
 */
export function piBoltInstallMethod(): "npm" | "installer" {
	if (process.env.PIBOLT_NPM === "1") return "npm";
	return /[\\/]npm[\\/]\d+\.\d+\.\d+[\\/]pi-bolt-(linux|darwin|win32)-/.test(process.execPath) ? "npm" : "installer";
}

/** The environment that makes the installer replace this installation with the latest release, keeping its variant and place. */
export function piBoltUpdateEnvironment(): NodeJS.ProcessEnv {
	// ~/.pi-bolt/pi-bolt-linux-x64/pi (or pi-bolt-darwin-arm64/pi, pi-bolt-win32-x64\pi-bolt.exe) -> ~/.pi-bolt
	const installDir = join(process.execPath, "..", "..");
	return {
		...process.env,
		PIBOLT_YES: "1",
		PIBOLT_NO_START: "1",
		PIBOLT_VARIANT: PIBOLT?.variant ?? "x64",
		PIBOLT_INSTALL:
			process.env.PIBOLT_INSTALL ?? (installDir.startsWith(homedir()) ? installDir : join(homedir(), ".pi-bolt")),
	};
}
