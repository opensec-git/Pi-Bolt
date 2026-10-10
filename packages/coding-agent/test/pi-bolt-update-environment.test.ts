import { homedir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, test } from "vitest";
import { piBoltUpdateEnvironment } from "../src/pi-bolt.ts";

const realExecPath = process.execPath;
const realInstall = process.env.PIBOLT_INSTALL;

function runningFrom(execPath: string): void {
	Object.defineProperty(process, "execPath", { value: execPath, configurable: true });
}

afterEach(() => {
	runningFrom(realExecPath);
	if (realInstall === undefined) delete process.env.PIBOLT_INSTALL;
	else process.env.PIBOLT_INSTALL = realInstall;
});

describe("pi-bolt update installs where Pi-Bolt is", () => {
	test("an installation in the home directory", () => {
		delete process.env.PIBOLT_INSTALL;
		runningFrom(join(homedir(), ".pi-bolt", "pi-bolt-linux-x64", "pi"));
		expect(piBoltUpdateEnvironment("0.8.0").PIBOLT_INSTALL).toBe(join(homedir(), ".pi-bolt"));
	});

	test("an installation outside it, such as /opt/pi-bolt", () => {
		delete process.env.PIBOLT_INSTALL;
		runningFrom("/opt/pi-bolt/pi-bolt-darwin-arm64/pi-bin");
		expect(piBoltUpdateEnvironment("0.8.0").PIBOLT_INSTALL).toBe("/opt/pi-bolt");
	});

	test("a build run from its output folder gets the default installation", () => {
		delete process.env.PIBOLT_INSTALL;
		runningFrom("/home/me/Pi-Bolt/out/pi-bolt/pi");
		expect(piBoltUpdateEnvironment("0.8.0").PIBOLT_INSTALL).toBe(join(homedir(), ".pi-bolt"));
	});

	// The installation that is running is the one updated: a PIBOLT_INSTALL left in the environment would put a second copy
	// elsewhere and leave this one as it was. It is used for an executable that is not in an installation.
	test("the running installation wins over PIBOLT_INSTALL", () => {
		process.env.PIBOLT_INSTALL = "/srv/tools/pi-bolt";
		runningFrom("/opt/pi-bolt/pi-bolt-linux-x64/pi");
		expect(piBoltUpdateEnvironment("0.8.0").PIBOLT_INSTALL).toBe("/opt/pi-bolt");
	});

	test("PIBOLT_INSTALL, for a build run from its output folder", () => {
		process.env.PIBOLT_INSTALL = "/srv/tools/pi-bolt";
		runningFrom("/home/me/Pi-Bolt/out/pi-bolt/pi");
		expect(piBoltUpdateEnvironment("0.8.0").PIBOLT_INSTALL).toBe("/srv/tools/pi-bolt");
	});
});
