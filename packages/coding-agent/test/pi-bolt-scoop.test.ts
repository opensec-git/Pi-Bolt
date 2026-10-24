import { mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { runDoctor } from "../src/cli/doctor.ts";
import { ENV_AGENT_DIR } from "../src/config.ts";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import type * as PiBolt from "../src/pi-bolt.ts";
import { piBoltInstallMethod, piBoltJitSwitchCommand, piBoltUpdateCommand } from "../src/pi-bolt.ts";
import type * as VersionCheck from "../src/utils/version-check.ts";

// A Pi-Bolt build (scripts/build-pi.sh defines PIBOLT_BUILD); the tests run as Pi otherwise. (vi.mock runs before the imports.)
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "1.0.0", variant: "x64", jit: false },
}));
// A newer release, without going online.
vi.mock("../src/utils/version-check.ts", async (importOriginal) => ({
	...(await importOriginal<typeof VersionCheck>()),
	getLatestPiRelease: async () => ({ version: "1.0.1" }),
}));

const realExecPath = process.execPath;
let dir: string;
let app: string;

function runningFrom(execPath: string): void {
	Object.defineProperty(process, "execPath", { value: execPath, configurable: true });
}

/**
 * What the manifest (opensec-git/scoop-bucket's bucket/pi-bolt.json) installs: the archive's pi-bolt-win32-x64 folder, as
 * <root>/apps/pi-bolt/<version>, with `current` linked to it (a junction on Windows); Scoop's shim starts current/pi-bolt.exe.
 */
function scoopInstall(root: string): string {
	app = join(root, "apps", "pi-bolt");
	const version = join(app, "1.0.0");
	mkdirSync(version, { recursive: true });
	writeFileSync(join(version, "pi-bolt.exe"), "");
	writeFileSync(join(version, "pi-bolt.txt"), "Pi-Bolt 1.0.0 (Pi 1.4.0), win32-x64, JIT off, built 2026-10-23\n");
	symlinkSync(version, join(app, "current"), "junction");
	return join(app, "current");
}

/** Every file in Scoop's folder for Pi-Bolt, to tell that nothing was changed. */
function filesIn(folder: string): string[] {
	return readdirSync(folder, { recursive: true, encoding: "utf8" }).sort();
}

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-scoop-"));
	vi.stubEnv("PIBOLT_NPM", "");
	vi.stubEnv("SCOOP", "");
	vi.stubEnv("SCOOP_GLOBAL", "");
	vi.stubEnv(ENV_AGENT_DIR, join(dir, "agent"));
});

afterEach(() => {
	runningFrom(realExecPath);
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

describe("a Pi-Bolt installed with Scoop", () => {
	it("is told by where the executable is, in Scoop's apps folder", () => {
		const current = scoopInstall(join(dir, "scoop"));
		runningFrom(join(current, "pi-bolt.exe"));
		expect(piBoltInstallMethod()).toBe("scoop");
		// The paths Scoop uses on Windows, not there to resolve: the user's, and a global install's.
		runningFrom("C:\\Users\\me\\scoop\\apps\\pi-bolt\\current\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("scoop");
		runningFrom("C:\\ProgramData\\scoop\\apps\\pi-bolt\\1.0.0\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("scoop");
		// Another app of Scoop's, and the installer's folder, are not.
		runningFrom("C:\\Users\\me\\scoop\\apps\\pi-bolt-other\\current\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("installer");
		runningFrom("C:\\Users\\me\\.pi-bolt\\pi-bolt-win32-x64\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("installer");
		expect(piBoltUpdateCommand("scoop")).toBe("scoop update pi-bolt");
		expect(piBoltJitSwitchCommand("scoop", "win32", "x64-jit")).toBe(
			'scoop uninstall pi-bolt; $env:PIBOLT_VARIANT = "x64-jit"; irm https://pi-bolt.opensec.in/install.ps1 | iex',
		);
	});

	it("is told by $SCOOP and $SCOOP_GLOBAL, wherever they are", () => {
		const current = scoopInstall(join(dir, "tools"));
		runningFrom(join(current, "pi-bolt.exe"));
		expect(piBoltInstallMethod()).toBe("installer");
		vi.stubEnv("SCOOP", join(dir, "tools"));
		expect(piBoltInstallMethod()).toBe("scoop");
		vi.stubEnv("SCOOP", "");
		vi.stubEnv("SCOOP_GLOBAL", join(dir, "tools"));
		expect(piBoltInstallMethod()).toBe("scoop");
	});

	it("update says to run scoop update, and changes nothing", async () => {
		runningFrom(join(scoopInstall(join(dir, "scoop")), "pi-bolt.exe"));
		const before = filesIn(app);
		const log = vi.spyOn(console, "log").mockImplementation(() => {});
		vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--self"])).toBe(true);
		const output = log.mock.calls.flat().join("\n");
		expect(output).toContain("This Pi-Bolt was installed with Scoop");
		expect(output).toContain("scoop update pi-bolt");
		expect(output).not.toContain("Updating Pi-Bolt");
		expect(process.exitCode).toBe(1);
		expect(filesIn(app)).toEqual(before);
	});

	it("update --rollback says to switch versions with scoop reset", async () => {
		runningFrom(join(scoopInstall(join(dir, "scoop")), "pi-bolt.exe"));
		const before = filesIn(app);
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--rollback"])).toBe(true);
		expect(process.exitCode).toBe(1);
		expect(error.mock.calls.flat().join("\n")).toContain("installed with Scoop");
		expect(error.mock.calls.flat().join("\n")).toContain("scoop reset pi-bolt@<version>");
		expect(filesIn(app)).toEqual(before);
	});

	it.skipIf(process.platform === "win32")("doctor reports it", async () => {
		const root = join(dir, "scoop");
		runningFrom(join(scoopInstall(root), "pi-bolt.exe"));
		const home = join(dir, "home");
		const agentDir = join(home, ".pi", "agent");
		mkdirSync(agentDir, { recursive: true });
		vi.stubEnv("HOME", home);
		vi.stubEnv("XDG_CONFIG_HOME", "");
		vi.stubEnv("PATH", join(root, "shims"));
		let output = "";
		vi.spyOn(process.stdout, "write").mockImplementation((chunk) => {
			output += String(chunk);
			return true;
		});
		expect(await runDoctor(["--offline"], { cwd: home, agentDir, extensionFactories: [] })).toBe(0);
		expect(output).toMatch(/installed +Scoop \(the opensec-git\/pi-bolt manifest\), run from .*1\.0\.0/);
	});
});
