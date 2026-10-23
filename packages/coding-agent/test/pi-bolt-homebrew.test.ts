import { chmodSync, mkdirSync, mkdtempSync, readdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { runDoctor } from "../src/cli/doctor.ts";
import { ENV_AGENT_DIR } from "../src/config.ts";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import type * as PiBolt from "../src/pi-bolt.ts";
import { piBoltInstallMethod, piBoltJitSwitchCommand, piBoltUpdateCommand } from "../src/pi-bolt.ts";
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

const realExecPath = process.execPath;
let dir: string;
let keg: string;

function runningFrom(execPath: string): void {
	Object.defineProperty(process, "execPath", { value: execPath, configurable: true });
}

/** What the formula installs: the release's folder in <prefix>/Cellar/pi-bolt/<version>/libexec, linked from <prefix>/bin. */
function brewInstall(prefix: string): string {
	keg = join(prefix, "Cellar", "pi-bolt", "1.0.0");
	const libexec = join(keg, "libexec");
	mkdirSync(libexec, { recursive: true });
	for (const name of ["pi", "pi-bin"]) {
		writeFileSync(join(libexec, name), "#!/bin/sh\necho '1.4.0 (Pi-Bolt 1.0.0, darwin-arm64, JIT off)'\n");
		chmodSync(join(libexec, name), 0o755);
	}
	writeFileSync(join(libexec, "pi-bolt.txt"), "Pi-Bolt 1.0.0 (Pi 1.4.0), darwin-arm64, JIT off, built 2026-10-23\n");
	mkdirSync(join(prefix, "bin"));
	symlinkSync(join(libexec, "pi"), join(prefix, "bin", "pi-bolt"));
	mkdirSync(join(prefix, "opt"));
	symlinkSync(keg, join(prefix, "opt", "pi-bolt"));
	return libexec;
}

/** Every file in the keg, to tell that nothing was changed. */
function filesIn(folder: string): string[] {
	return readdirSync(folder, { recursive: true, encoding: "utf8" }).sort();
}

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-brew-"));
	vi.stubEnv("PIBOLT_NPM", "");
	vi.stubEnv(ENV_AGENT_DIR, join(dir, "agent"));
});

afterEach(() => {
	runningFrom(realExecPath);
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

describe.skipIf(process.platform === "win32")("a Pi-Bolt installed with Homebrew", () => {
	it("is told by where the executable really is, in the Cellar", () => {
		const libexec = brewInstall(join(dir, "homebrew"));
		runningFrom(join(libexec, "pi-bin"));
		expect(piBoltInstallMethod()).toBe("homebrew");
		// Through the opt link, as from Homebrew's bin: the real path decides.
		runningFrom(join(dir, "homebrew", "opt", "pi-bolt", "libexec", "pi-bin"));
		expect(piBoltInstallMethod()).toBe("homebrew");
		// Linuxbrew's prefix, not there to resolve.
		runningFrom("/home/linuxbrew/.linuxbrew/Cellar/pi-bolt/1.0.0/libexec/pi");
		expect(piBoltInstallMethod()).toBe("homebrew");
		// Another formula's Cellar, and the installer's folder, are not.
		runningFrom("/opt/homebrew/Cellar/pi-bolt-other/1.0.0/libexec/pi");
		expect(piBoltInstallMethod()).toBe("installer");
		runningFrom("/Users/me/.pi-bolt/pi-bolt-darwin-arm64/pi-bin");
		expect(piBoltInstallMethod()).toBe("installer");
		expect(piBoltUpdateCommand("homebrew")).toBe("brew upgrade pi-bolt");
		expect(piBoltUpdateCommand("installer")).toBe("pi-bolt update");
		expect(piBoltJitSwitchCommand("homebrew", "darwin", "arm64-jit")).toBe(
			"brew uninstall pi-bolt && curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=arm64-jit sh",
		);
	});

	it("update says to run brew upgrade, and changes nothing", async () => {
		const libexec = brewInstall(join(dir, "homebrew"));
		runningFrom(join(libexec, "pi-bin"));
		const before = filesIn(keg);
		const log = vi.spyOn(console, "log").mockImplementation(() => {});
		vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--self"])).toBe(true);
		const output = log.mock.calls.flat().join("\n");
		expect(output).toContain("This Pi-Bolt was installed with Homebrew");
		expect(output).toContain("brew upgrade pi-bolt");
		expect(output).not.toContain("Updating Pi-Bolt");
		expect(process.exitCode).toBe(1);
		expect(filesIn(keg)).toEqual(before);
	});

	it("update --rollback refuses, naming brew", async () => {
		const libexec = brewInstall(join(dir, "homebrew"));
		runningFrom(join(libexec, "pi-bin"));
		const before = filesIn(keg);
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--rollback"])).toBe(true);
		expect(process.exitCode).toBe(1);
		expect(error.mock.calls.flat().join("\n")).toContain("installed with Homebrew");
		expect(error.mock.calls.flat().join("\n")).toContain("brew uninstall pi-bolt");
		expect(filesIn(keg)).toEqual(before);
	});

	it("compile refuses, naming brew", async () => {
		const libexec = brewInstall(join(dir, "homebrew"));
		runningFrom(join(libexec, "pi-bin"));
		const before = filesIn(keg);
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		const installAgain = vi.fn(async () => true);
		expect(await runPiBoltCompile([], installAgain)).toBe(1);
		expect(error.mock.calls.flat().join("\n")).toContain(
			"this Pi-Bolt was installed with Homebrew, whose folder brew owns",
		);
		expect(installAgain).not.toHaveBeenCalled();
		expect(filesIn(keg)).toEqual(before);
	});

	it("doctor reports it", async () => {
		const prefix = join(dir, "homebrew");
		const libexec = brewInstall(prefix);
		runningFrom(join(libexec, "pi-bin"));
		const home = join(dir, "home");
		const agentDir = join(home, ".pi", "agent");
		mkdirSync(agentDir, { recursive: true });
		vi.stubEnv("HOME", home);
		vi.stubEnv("XDG_CONFIG_HOME", "");
		vi.stubEnv("PATH", join(prefix, "bin"));
		let output = "";
		vi.spyOn(process.stdout, "write").mockImplementation((chunk) => {
			output += String(chunk);
			return true;
		});
		expect(await runDoctor(["--offline"], { cwd: home, agentDir, extensionFactories: [] })).toBe(0);
		expect(output).toMatch(/installed +Homebrew \(the opensec-git\/tap\/pi-bolt formula\), run from .*libexec/);
		expect(output).toContain("(this Pi-Bolt)");
	});
});
