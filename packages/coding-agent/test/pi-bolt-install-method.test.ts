import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
	installedByPiBoltInstaller,
	PIBOLT_INSTALLER_MARKER,
	piBoltInstallDir,
	piBoltInstallerProcess,
	piBoltInstallMethod,
	piBoltUpdateEnvironment,
} from "../src/pi-bolt.ts";

const originalExecPath = process.execPath;
const originalNpm = process.env.PIBOLT_NPM;

afterEach(() => {
	Object.defineProperty(process, "execPath", { configurable: true, writable: true, value: originalExecPath });
	if (originalNpm === undefined) delete process.env.PIBOLT_NPM;
	else process.env.PIBOLT_NPM = originalNpm;
});

function setExecPath(value: string): void {
	Object.defineProperty(process, "execPath", { configurable: true, writable: true, value });
}

describe("piBoltInstallMethod", () => {
	it("tells an executable the npm package downloaded, on every platform", () => {
		delete process.env.PIBOLT_NPM;
		setExecPath("/home/me/.pi-bolt/npm/0.7.0/pi-bolt-linux-x64/pi");
		expect(piBoltInstallMethod()).toBe("npm");
		setExecPath("/Users/me/.pi-bolt/npm/0.7.0/pi-bolt-darwin-arm64/pi");
		expect(piBoltInstallMethod()).toBe("npm");
		setExecPath("C:\\Users\\me\\.pi-bolt\\npm\\0.7.0\\pi-bolt-win32-x64\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("npm");
		// Windows: the package's install script puts the executable in its own bin folder (npm/install.cjs).
		setExecPath("C:\\Users\\me\\AppData\\Roaming\\npm\\node_modules\\pi-bolt\\bin\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("npm");
	});

	it("tells an executable the installer put in place", () => {
		delete process.env.PIBOLT_NPM;
		setExecPath("/home/me/.pi-bolt/pi-bolt-linux-x64/pi");
		expect(piBoltInstallMethod()).toBe("installer");
		setExecPath("C:\\Users\\me\\.pi-bolt\\pi-bolt-win32-x64\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("installer");
	});
});

describe("installer ownership marker", () => {
	it("does not treat an extracted native archive as installer-managed", () => {
		const installDir = mkdtempSync(join(tmpdir(), "pi-bolt-manual-"));
		try {
			const variant = join(installDir, "pi-bolt-linux-x64");
			mkdirSync(variant);
			writeFileSync(join(variant, "pi"), "archive executable");
			expect(installedByPiBoltInstaller(installDir)).toBe(false);
			writeFileSync(join(installDir, PIBOLT_INSTALLER_MARKER), "signed\n");
			expect(installedByPiBoltInstaller(installDir)).toBe(true);
		} finally {
			rmSync(installDir, { recursive: true, force: true });
		}
	});
});

describe("piBoltUpdateEnvironment", () => {
	const saved = { ...process.env };
	afterEach(() => {
		for (const name of ["PIBOLT_INSTALL", "PIBOLT_DOWNLOAD_BASE", "PIBOLT_SOURCE", "PIBOLT_VERSION"]) {
			if (saved[name] === undefined) delete process.env[name];
			else process.env[name] = saved[name];
		}
	});

	it("updates the installation where it is, to the version decided on, from the signed release", () => {
		const root = process.platform === "win32" ? "D:\\Tools\\Pi" : "/opt/tools/pi";
		setExecPath(join(root, `pi-bolt-${process.platform}-x64`, process.platform === "win32" ? "pi-bolt.exe" : "pi"));
		process.env.PIBOLT_INSTALL = join(root, "elsewhere");
		process.env.PIBOLT_DOWNLOAD_BASE = "https://mirror.example";
		process.env.PIBOLT_SOURCE = "github";
		process.env.PIBOLT_VERSION = "bolt-v0.1.0";
		process.env.PIBOLT_INSTALLER_NO_MAIN = "1";
		const env = piBoltUpdateEnvironment("0.8.0");
		delete process.env.PIBOLT_INSTALLER_NO_MAIN;
		expect(env.PIBOLT_INSTALLER_NO_MAIN).toBeUndefined();
		expect(env.PIBOLT_INSTALL).toBe(root);
		expect(env.PIBOLT_VERSION).toBe("bolt-v0.8.0");
		expect(env.PIBOLT_DOWNLOAD_BASE).toBeUndefined();
		expect(env.PIBOLT_SOURCE).toBeUndefined();
		// PATH stays as the user left it (on Windows the installer would add this folder without asking).
		expect(env.PIBOLT_NO_PATH).toBe("1");
	});

	it("falls back to PIBOLT_INSTALL, then ~/.pi-bolt, for an executable outside a variant's folder", () => {
		setExecPath(process.platform === "win32" ? "C:\\bin\\pi-bolt.exe" : "/usr/local/bin/pi");
		delete process.env.PIBOLT_INSTALL;
		expect(piBoltInstallDir()).toBeUndefined();
		expect(piBoltUpdateEnvironment("0.8.0").PIBOLT_INSTALL).toBe(join(homedir(), ".pi-bolt"));
	});
});

describe("piBoltInstallerProcess", () => {
	it("runs the installer for this platform, with an exit status that says whether it installed", () => {
		const { command, args } = piBoltInstallerProcess();
		if (process.platform === "win32") {
			expect(command.toLowerCase()).toMatch(/\\system32\\windowspowershell\\v1\.0\\powershell\.exe$/);
			expect(args.at(-1)).toBe(
				"[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072; try { $installer = irm https://pi-bolt.opensec.in/install.ps1 -ErrorAction Stop } catch { Write-Error $_; exit 1 }; iex $installer; exit $LASTEXITCODE",
			);
		} else {
			expect(command).toBe("sh");
			expect(args[0]).toBe("-c");
			expect(args[1]).toContain("curl -fsSL https://pi-bolt.opensec.in/install.sh");
			expect(args[1]).toContain("wget -qO- https://pi-bolt.opensec.in/install.sh");
		}
	});

	it.skipIf(process.platform === "win32")(
		"downloads with wget where there is no curl; a failed download fails",
		() => {
			const { args } = piBoltInstallerProcess();
			const bin = mkdtempSync(join(tmpdir(), "pi-bolt-installer-"));
			try {
				// A PATH with only sh and a wget that prints a tiny installer: no curl.
				symlinkSync("/bin/sh", join(bin, "sh"));
				const env = { PATH: bin };
				writeFileSync(join(bin, "wget"), "#!/bin/sh\nprintf 'echo installed-by-wget\\n'\n", { mode: 0o755 });
				const withWget = spawnSync("/bin/sh", args, { env, encoding: "utf8" });
				expect(withWget.status).toBe(0);
				expect(withWget.stdout.trim()).toBe("installed-by-wget");
				writeFileSync(join(bin, "wget"), "#!/bin/sh\nexit 4\n", { mode: 0o755 });
				expect(spawnSync("/bin/sh", args, { env, encoding: "utf8" }).status).toBe(1);
				rmSync(join(bin, "wget"));
				const neither = spawnSync("/bin/sh", args, { env, encoding: "utf8" });
				expect(neither.status).toBe(1);
				expect(neither.stderr).toContain("curl or wget is needed");
			} finally {
				rmSync(bin, { recursive: true, force: true });
			}
		},
	);
});
