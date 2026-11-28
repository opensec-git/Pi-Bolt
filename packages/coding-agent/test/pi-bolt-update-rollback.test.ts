import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { DEFAULT_TOOL_NAMES, defaultToolNamesFor } from "../src/core/settings-manager.ts";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import { piBoltChannel, setPiBoltChannel } from "../src/pi-bolt.ts";
import { skipsRelease } from "../src/pi-bolt-auto-update.ts";
import { rollBackInstallation, swapFolderContents } from "../src/pi-bolt-rollback.ts";
import { newestPiBoltVersion } from "../src/utils/version-check.ts";

let dir: string;
const realExecPath = process.execPath;
const realChannel = process.env.PIBOLT_CHANNEL;

function runningFrom(execPath: string): void {
	Object.defineProperty(process, "execPath", { value: execPath, configurable: true });
}

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-rollback-"));
	delete process.env.PIBOLT_CHANNEL;
});

afterEach(() => {
	runningFrom(realExecPath);
	if (realChannel === undefined) delete process.env.PIBOLT_CHANNEL;
	else process.env.PIBOLT_CHANNEL = realChannel;
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

/** A build folder whose `pi` says it is Pi-Bolt `version`, as `pi --version` does. */
function build(folder: string, version: string, runs = true): void {
	mkdirSync(join(folder, "theme"), { recursive: true });
	writeFileSync(join(folder, "pi-bolt.txt"), `Pi-Bolt ${version} (Pi 1.2.0), linux-x64, JIT off, built 2026-10-15\n`);
	writeFileSync(join(folder, "theme", "dark.json"), `{"version":"${version}"}`);
	writeFileSync(
		join(folder, "pi"),
		`#!/bin/sh\n${runs ? `echo "1.2.0 (Pi-Bolt ${version}, linux-x64, JIT off)"` : "exit 1"}\n`,
	);
	chmodSync(join(folder, "pi"), 0o755);
}

const versionOf = (folder: string) => readFileSync(join(folder, "pi-bolt.txt"), "utf8").split(" ")[1];

describe.skipIf(process.platform === "win32")("pi-bolt update --rollback", () => {
	test("swaps the build with the one the last update replaced, and again goes forward", () => {
		const current = join(dir, "pi-bolt-linux-x64");
		build(current, "0.9.0");
		build(`${current}.previous`, "0.8.8");
		expect(rollBackInstallation(current, "linux")).toEqual({ from: "Pi-Bolt 0.9.0", to: "Pi-Bolt 0.8.8" });
		expect(versionOf(current)).toBe("0.8.8");
		expect(versionOf(`${current}.previous`)).toBe("0.9.0");
		rollBackInstallation(current, "linux");
		expect(versionOf(current)).toBe("0.9.0");
	});

	// hunt-install-9: an automatic update installed the release rolled back from again, the next day.
	test("keeps automatic updates from installing the release it went back from, until it goes forward again", () => {
		const current = join(dir, "pi-bolt-linux-x64");
		build(current, "0.9.0");
		build(`${current}.previous`, "0.8.8");
		rollBackInstallation(current, "linux");
		expect(readFileSync(join(dir, "auto-update.skip"), "utf8").trim()).toBe("0.9.0");
		expect(skipsRelease(dir, "0.9.0")).toBe(true);
		expect(skipsRelease(dir, "0.9.1")).toBe(false);
		rollBackInstallation(current, "linux");
		expect(existsSync(join(dir, "auto-update.skip"))).toBe(false);
		expect(skipsRelease(dir, "0.9.0")).toBe(false);
	});

	test("says what was compiled in", () => {
		const current = join(dir, "pi-bolt-linux-x64");
		build(current, "0.9.0");
		writeFileSync(
			join(current, "pi-bolt.txt"),
			"Pi-Bolt 0.9.0 (Pi 1.2.0), linux-x64\nExtensions compiled in (pi-bolt compile, 2026-10-15): word-count\n",
		);
		build(`${current}.previous`, "0.9.0");
		expect(rollBackInstallation(current, "linux").from).toBe("Pi-Bolt 0.9.0 with word-count compiled in");
	});

	test("with nothing kept, or a build that does not run, nothing changes", () => {
		const current = join(dir, "pi-bolt-linux-x64");
		build(current, "0.9.0");
		expect(() => rollBackInstallation(current, "linux")).toThrow("no build to go back to");
		build(`${current}.previous`, "0.8.8", false);
		expect(() => rollBackInstallation(current, "linux")).toThrow("does not run");
		expect(versionOf(current)).toBe("0.9.0");
		expect(versionOf(`${current}.previous`)).toBe("0.8.8");
	});

	// Windows' way: the running executable's folder cannot be renamed, so its files are swapped one by one.
	test("swaps the files of a folder that stays where it is", () => {
		const current = join(dir, "pi-bolt-win32-x64");
		build(current, "0.9.0");
		writeFileSync(join(current, "only-in-new.txt"), "new");
		writeFileSync(join(current, "pi.123.pibolt-old"), "left from an install");
		build(`${current}.previous`, "0.8.8");
		writeFileSync(join(`${current}.previous`, "only-in-old.txt"), "old");
		swapFolderContents(current, `${current}.previous`);
		expect(versionOf(current)).toBe("0.8.8");
		expect(readFileSync(join(current, "theme", "dark.json"), "utf8")).toContain("0.8.8");
		expect(existsSync(join(current, "only-in-old.txt"))).toBe(true);
		expect(existsSync(join(current, "only-in-new.txt"))).toBe(false);
		expect(versionOf(`${current}.previous`)).toBe("0.9.0");
		expect(existsSync(join(`${current}.previous`, "only-in-new.txt"))).toBe(true);
		expect(existsSync(join(`${current}.previous`, "pi.123.pibolt-old"))).toBe(false);
	});
});

describe("the release channel", () => {
	test("the newest release, pre-releases included, drafts and other tags not", () => {
		const releases = [
			{ tag_name: "bolt-v0.8.7", prerelease: false, draft: false },
			{ tag_name: "bolt-v0.9.0", prerelease: true, draft: false },
			{ tag_name: "bolt-v0.10.0", prerelease: false, draft: true },
			{ tag_name: "v1.2.0", prerelease: false, draft: false },
			{ tag_name: "bolt-v0.8.10", prerelease: false, draft: false },
		];
		expect(newestPiBoltVersion(releases)).toBe("0.9.0");
		expect(newestPiBoltVersion(releases.slice(0, 1))).toBe("0.8.7");
		expect(newestPiBoltVersion({ message: "API rate limit exceeded" })).toBeUndefined();
	});

	test("is kept in the installation, stable being no file; PIBOLT_CHANNEL overrides it", () => {
		const install = join(dir, ".pi-bolt");
		mkdirSync(join(install, "pi-bolt-linux-x64"), { recursive: true });
		runningFrom(join(install, "pi-bolt-linux-x64", "pi"));
		expect(piBoltChannel()).toBe("stable");
		expect(setPiBoltChannel("beta")).toBe(true);
		expect(readFileSync(join(install, "channel"), "utf8").trim()).toBe("beta");
		expect(piBoltChannel()).toBe("beta");
		process.env.PIBOLT_CHANNEL = "stable";
		expect(piBoltChannel()).toBe("stable");
		delete process.env.PIBOLT_CHANNEL;
		setPiBoltChannel("stable");
		expect(existsSync(join(install, "channel"))).toBe(false);
	});

	test("a build outside an installation has none to set", () => {
		runningFrom(join(dir, "out", "pi-bolt", "pi"));
		expect(setPiBoltChannel("beta")).toBe(false);
		expect(piBoltChannel()).toBe("stable");
	});
});

describe("Pi itself (not a Pi-Bolt build) is unchanged", () => {
	test("update --rollback and --channel are unknown options", async () => {
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		try {
			expect(await handlePackageCommand(["update", "--rollback"])).toBe(true);
			expect(process.exitCode).toBe(1);
			expect(error.mock.calls.flat().join("\n")).toContain("Unknown option --rollback");
			process.exitCode = undefined;
			expect(await handlePackageCommand(["update", "--channel", "beta"])).toBe(true);
			expect(error.mock.calls.flat().join("\n")).toContain("Unknown option --channel");
		} finally {
			error.mockRestore();
		}
	});

	test("the default tools are Pi's, FFF or not", () => {
		expect(DEFAULT_TOOL_NAMES).toEqual(["read", "bash", "edit", "write"]);
		expect(defaultToolNamesFor(["builtin:opensec-pi-fff"])).toEqual(["read", "bash", "edit", "write"]);
	});

	test("compile is not a command: it stays a message to the model", async () => {
		expect(await handlePackageCommand(["compile"])).toBe(false);
	});
});
