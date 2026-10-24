import { chmodSync, mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { runDoctor } from "../src/cli/doctor.ts";
import { ENV_AGENT_DIR } from "../src/config.ts";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import type * as PiBolt from "../src/pi-bolt.ts";
import { piBoltInstallMethod, piBoltJitSwitchCommand, piBoltMiseTool, piBoltUpdateCommand } from "../src/pi-bolt.ts";
import { runPiBoltCompile } from "../src/pi-bolt-compile.ts";
import type * as VersionCheck from "../src/utils/version-check.ts";

// A Pi-Bolt build (scripts/build-pi.sh defines PIBOLT_BUILD); the tests run as Pi otherwise. (vi.mock runs before the imports.)
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "1.0.0", variant: "arm64", jit: false },
}));
// A newer release, without going online.
vi.mock("../src/utils/version-check.ts", async (importOriginal) => ({
	...(await importOriginal<typeof VersionCheck>()),
	getLatestPiRelease: async () => ({ version: "1.0.1" }),
}));

const GITHUB_TOOL = "github:opensec-git/Pi-Bolt";
const realExecPath = process.execPath;
let dir: string;
let installs: string;

function runningFrom(execPath: string): void {
	Object.defineProperty(process, "execPath", { value: execPath, configurable: true });
}

/**
 * What `mise use github:opensec-git/Pi-Bolt` installs (with the options in docs: the release's archive, `pi` renamed to
 * `pi-bolt`, and only that on PATH): the archive's folder, unpacked into <data>/installs/<tool folder>/<version>, with links
 * for the version prefixes. Returns the folder; pi-bolt there starts pi-bin beside it.
 */
function miseInstall(data: string, toolFolder = "github-opensec-git-pi-bolt"): string {
	installs = join(data, "installs", toolFolder);
	const version = join(installs, "1.0.0");
	mkdirSync(join(version, ".mise-bins"), { recursive: true });
	for (const name of ["pi-bolt", "pi-bin"]) {
		writeFileSync(join(version, name), "#!/bin/sh\necho '1.4.0 (Pi-Bolt 1.0.0, darwin-arm64, JIT off)'\n");
		chmodSync(join(version, name), 0o755);
	}
	writeFileSync(join(version, "pi-bolt.txt"), "Pi-Bolt 1.0.0 (Pi 1.4.0), darwin-arm64, JIT off, built 2026-10-23\n");
	symlinkSync(join(version, "pi-bolt"), join(version, ".mise-bins", "pi-bolt"));
	symlinkSync(version, join(installs, "latest"));
	return version;
}

/** Every file in mise's folder for Pi-Bolt, to tell that nothing was changed. */
function filesIn(folder: string): string[] {
	return readdirSync(folder, { recursive: true, encoding: "utf8" }).sort();
}

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-mise-"));
	vi.stubEnv("PIBOLT_NPM", "");
	vi.stubEnv("MISE_DATA_DIR", "");
	vi.stubEnv(ENV_AGENT_DIR, join(dir, "agent"));
});

afterEach(() => {
	runningFrom(realExecPath);
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

describe.skipIf(process.platform === "win32")("a Pi-Bolt installed with mise", () => {
	it("is told by where the executable really is, in mise's installs folder", () => {
		const version = miseInstall(join(dir, ".local", "share", "mise"));
		runningFrom(join(version, "pi-bin"));
		expect(piBoltInstallMethod()).toBe("mise");
		expect(piBoltMiseTool()).toBe(GITHUB_TOOL);
		// Through the `latest` link: the real path decides.
		runningFrom(join(installs, "latest", "pi-bin"));
		expect(piBoltInstallMethod()).toBe("mise");
		// The registry's shorthand, and mise's folder on Windows, not there to resolve.
		runningFrom("/home/me/.local/share/mise/installs/pi-bolt/1.0.0/pi-bin");
		expect(piBoltInstallMethod()).toBe("mise");
		expect(piBoltMiseTool()).toBe("pi-bolt");
		runningFrom("C:\\Users\\me\\AppData\\Local\\mise\\installs\\github-opensec-git-pi-bolt\\1.0.0\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("mise");
		// Another tool of mise's, and the installer's folder, are not.
		runningFrom("/home/me/.local/share/mise/installs/node/24.0.0/bin/node");
		expect(piBoltInstallMethod()).toBe("installer");
		runningFrom("/Users/me/.pi-bolt/pi-bolt-darwin-arm64/pi-bin");
		expect(piBoltInstallMethod()).toBe("installer");
		runningFrom(join(version, "pi-bin"));
		expect(piBoltUpdateCommand("mise")).toBe(`mise upgrade --bump ${GITHUB_TOOL}`);
		expect(piBoltJitSwitchCommand("mise", "darwin", "arm64-jit")).toBe(
			`mise unuse -g ${GITHUB_TOOL} && curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=arm64-jit sh`,
		);
	});

	it("is told by $MISE_DATA_DIR, wherever it is", () => {
		const version = miseInstall(join(dir, "tools"), "pi-bolt");
		runningFrom(join(version, "pi-bin"));
		expect(piBoltInstallMethod()).toBe("installer");
		vi.stubEnv("MISE_DATA_DIR", join(dir, "tools"));
		expect(piBoltInstallMethod()).toBe("mise");
		expect(piBoltUpdateCommand()).toBe("mise upgrade --bump pi-bolt");
	});

	it("update says to run mise upgrade, and changes nothing", async () => {
		runningFrom(join(miseInstall(join(dir, "mise")), "pi-bin"));
		const before = filesIn(installs);
		const log = vi.spyOn(console, "log").mockImplementation(() => {});
		vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--self"])).toBe(true);
		const output = log.mock.calls.flat().join("\n");
		expect(output).toContain("This Pi-Bolt was installed with mise");
		expect(output).toContain(`mise upgrade --bump ${GITHUB_TOOL}`);
		expect(output).not.toContain("Updating Pi-Bolt");
		expect(process.exitCode).toBe(1);
		expect(filesIn(installs)).toEqual(before);
	});

	it("update --rollback says to switch versions with mise use", async () => {
		runningFrom(join(miseInstall(join(dir, "mise")), "pi-bin"));
		const before = filesIn(installs);
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--rollback"])).toBe(true);
		expect(process.exitCode).toBe(1);
		expect(error.mock.calls.flat().join("\n")).toContain("installed with mise");
		expect(error.mock.calls.flat().join("\n")).toContain(`mise use -g ${GITHUB_TOOL}@<version>`);
		expect(filesIn(installs)).toEqual(before);
	});

	it("compile refuses, naming mise", async () => {
		runningFrom(join(miseInstall(join(dir, "mise")), "pi-bin"));
		const before = filesIn(installs);
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		const installAgain = vi.fn(async () => true);
		expect(await runPiBoltCompile([], installAgain)).toBe(1);
		expect(error.mock.calls.flat().join("\n")).toContain(
			"this Pi-Bolt was installed with mise, whose folder mise owns",
		);
		expect(installAgain).not.toHaveBeenCalled();
		expect(filesIn(installs)).toEqual(before);
	});

	it("doctor reports it", async () => {
		const data = join(dir, "mise");
		const version = miseInstall(data);
		runningFrom(join(version, "pi-bin"));
		const home = join(dir, "home");
		const agentDir = join(home, ".pi", "agent");
		mkdirSync(agentDir, { recursive: true });
		vi.stubEnv("HOME", home);
		vi.stubEnv("XDG_CONFIG_HOME", "");
		vi.stubEnv("MISE_DATA_DIR", data);
		vi.stubEnv("PATH", join(version, ".mise-bins"));
		let output = "";
		vi.spyOn(process.stdout, "write").mockImplementation((chunk) => {
			output += String(chunk);
			return true;
		});
		expect(await runDoctor(["--offline"], { cwd: home, agentDir, extensionFactories: [] })).toBe(0);
		expect(output).toMatch(/installed +mise \(github:opensec-git\/Pi-Bolt\), run from .*1\.0\.0/);
	});
});
