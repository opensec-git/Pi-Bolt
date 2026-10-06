import { afterEach, describe, expect, it } from "vitest";
import { piBoltInstallerProcess, piBoltInstallMethod } from "../src/pi-bolt.ts";

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
	});

	it("tells an executable the installer put in place", () => {
		delete process.env.PIBOLT_NPM;
		setExecPath("/home/me/.pi-bolt/pi-bolt-linux-x64/pi");
		expect(piBoltInstallMethod()).toBe("installer");
		setExecPath("C:\\Users\\me\\.pi-bolt\\pi-bolt-win32-x64\\pi-bolt.exe");
		expect(piBoltInstallMethod()).toBe("installer");
	});
});

describe("piBoltInstallerProcess", () => {
	it("runs the installer for this platform, with an exit status that says whether it installed", () => {
		const { command, args } = piBoltInstallerProcess();
		if (process.platform === "win32") {
			expect(command).toBe("powershell.exe");
			expect(args.at(-1)).toBe("irm https://pi-bolt.opensec.in/install.ps1 | iex; exit $LASTEXITCODE");
		} else {
			expect(command).toBe("sh");
			expect(args).toEqual(["-c", "curl -fsSL https://pi-bolt.opensec.in/install.sh | sh"]);
		}
	});
});
