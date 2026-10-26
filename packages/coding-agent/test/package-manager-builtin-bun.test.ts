import { chmodSync, mkdirSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Run the package manager as it runs inside a compiled Bun executable (Pi-Bolt).
vi.mock("../src/config.ts", async (importOriginal) => {
	const actual = await importOriginal();
	return {
		...(actual as Record<string, unknown>),
		isBunBinary: true,
	};
});

import { DefaultPackageManager } from "../src/core/package-manager.ts";
import { SettingsManager } from "../src/core/settings-manager.ts";
import { findExecutableOnPath } from "../src/utils/child-process.ts";

const BUILTIN_ENV = { BUN_BE_BUN: "1" };

describe.skipIf(process.platform === "win32")("DefaultPackageManager in a compiled Bun executable", () => {
	let tempDir: string;
	let agentDir: string;
	let emptyBinDir: string;
	let npmBinDir: string;
	let previousPath: string | undefined;
	let previousOfflineEnv: string | undefined;

	function createPackageManager(settings: Parameters<typeof SettingsManager.inMemory>[0] = {}) {
		const settingsManager = SettingsManager.inMemory(settings);
		const packageManager = new DefaultPackageManager({ cwd: tempDir, agentDir, settingsManager });
		return { settingsManager, packageManager };
	}

	beforeEach(() => {
		previousPath = process.env.PATH;
		previousOfflineEnv = process.env.PI_OFFLINE;
		delete process.env.PI_OFFLINE;
		tempDir = join(tmpdir(), `pm-builtin-bun-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
		agentDir = join(tempDir, "agent");
		emptyBinDir = join(tempDir, "empty-bin");
		npmBinDir = join(tempDir, "npm-bin");
		mkdirSync(agentDir, { recursive: true });
		mkdirSync(emptyBinDir, { recursive: true });
		mkdirSync(npmBinDir, { recursive: true });
		writeFileSync(join(npmBinDir, "npm"), "#!/bin/sh\nexit 0\n");
		chmodSync(join(npmBinDir, "npm"), 0o755);
		process.env.PATH = emptyBinDir;
	});

	afterEach(() => {
		process.env.PATH = previousPath;
		if (previousOfflineEnv === undefined) {
			delete process.env.PI_OFFLINE;
		} else {
			process.env.PI_OFFLINE = previousOfflineEnv;
		}
		vi.restoreAllMocks();
		rmSync(tempDir, { recursive: true, force: true });
	});

	it("installs with the executable itself as bun when npm is not on PATH", async () => {
		const { packageManager } = createPackageManager();
		const runCommandSpy = vi.spyOn(packageManager as any, "runCommand").mockResolvedValue(undefined);

		await packageManager.install("npm:@scope/pkg@1.2.3");

		expect(runCommandSpy).toHaveBeenCalledWith(
			process.execPath,
			["install", "@scope/pkg@1.2.3", "--cwd", realpathSync(join(agentDir, "npm")), "--omit=peer"],
			{ env: BUILTIN_ENV },
		);
	});

	it("passes --ignore-scripts to bun when npm_config_ignore_scripts says so, as the installers set it", async () => {
		const previous = process.env.NPM_CONFIG_IGNORE_SCRIPTS;
		process.env.NPM_CONFIG_IGNORE_SCRIPTS = "true";
		try {
			const { packageManager } = createPackageManager();
			const runCommandSpy = vi.spyOn(packageManager as any, "runCommand").mockResolvedValue(undefined);

			await packageManager.install("npm:@scope/pkg@1.2.3");

			expect(runCommandSpy).toHaveBeenCalledWith(
				process.execPath,
				[
					"install",
					"@scope/pkg@1.2.3",
					"--cwd",
					realpathSync(join(agentDir, "npm")),
					"--omit=peer",
					"--ignore-scripts",
				],
				{ env: BUILTIN_ENV },
			);
		} finally {
			if (previous === undefined) delete process.env.NPM_CONFIG_IGNORE_SCRIPTS;
			else process.env.NPM_CONFIG_IGNORE_SCRIPTS = previous;
		}
	});

	it("keeps npm when npm is on PATH", async () => {
		process.env.PATH = [emptyBinDir, npmBinDir].join(":");
		const { packageManager } = createPackageManager();
		const runCommandSpy = vi.spyOn(packageManager as any, "runCommand").mockResolvedValue(undefined);

		await packageManager.install("npm:@scope/pkg");

		expect(runCommandSpy).toHaveBeenCalledWith(
			"npm",
			["install", "@scope/pkg", "--prefix", realpathSync(join(agentDir, "npm")), "--legacy-peer-deps"],
			undefined,
		);
	});

	it("uses a configured npmCommand even when npm is not on PATH", async () => {
		const { packageManager } = createPackageManager({ npmCommand: ["pnpm"] });
		const runCommandSpy = vi.spyOn(packageManager as any, "runCommand").mockResolvedValue(undefined);

		await packageManager.install("npm:@scope/pkg");

		expect(runCommandSpy).toHaveBeenCalledWith(
			"pnpm",
			[
				"install",
				"@scope/pkg",
				"--prefix",
				realpathSync(join(agentDir, "npm")),
				"--config.auto-install-peers=false",
				"--config.strict-peer-dependencies=false",
				"--config.strict-dep-builds=false",
			],
			undefined,
		);
	});

	it("uninstalls with bun arguments", async () => {
		mkdirSync(join(agentDir, "npm"), { recursive: true });
		const { packageManager } = createPackageManager();
		const runCommandSpy = vi.spyOn(packageManager as any, "runCommand").mockResolvedValue(undefined);

		await packageManager.remove("npm:@scope/pkg");

		expect(runCommandSpy).toHaveBeenCalledWith(
			process.execPath,
			["uninstall", "@scope/pkg", "--cwd", realpathSync(join(agentDir, "npm")), "--omit=peer"],
			{
				env: BUILTIN_ENV,
			},
		);
	});

	it("installs git package dependencies in the checkout", async () => {
		const targetDir = join(agentDir, "git", "github.com", "user", "repo");
		const { packageManager } = createPackageManager();
		const runCommandSpy = vi
			.spyOn(packageManager as any, "runCommand")
			.mockImplementation(async (...callArgs: unknown[]) => {
				const [command, args] = callArgs as [string, string[]];
				if (command === "git" && args[0] === "clone") {
					mkdirSync(targetDir, { recursive: true });
					writeFileSync(join(targetDir, "package.json"), JSON.stringify({ name: "repo", version: "1.0.0" }));
				}
			});

		await packageManager.install("git:github.com/user/repo");

		expect(runCommandSpy).toHaveBeenCalledWith(process.execPath, ["install", "--omit=dev", "--omit=peer"], {
			cwd: targetDir,
			env: BUILTIN_ENV,
		});
	});

	it("checks for npm updates with bun pm view in the install root", async () => {
		const installRoot = join(agentDir, "npm");
		const installedPath = join(installRoot, "node_modules", "example");
		mkdirSync(installedPath, { recursive: true });
		writeFileSync(join(installedPath, "package.json"), JSON.stringify({ name: "example", version: "1.0.0" }));
		const { settingsManager, packageManager } = createPackageManager();
		settingsManager.setPackages(["npm:example"]);
		const runCommandCaptureSpy = vi.spyOn(packageManager as any, "runCommandCapture").mockResolvedValue('"1.1.0"');
		const runCommandSpy = vi.spyOn(packageManager as any, "runCommand").mockResolvedValue(undefined);

		const updates = await packageManager.checkForAvailableUpdates();
		expect(updates.map((update) => update.source)).toEqual(["npm:example"]);
		await packageManager.update("npm:example");

		expect(runCommandCaptureSpy).toHaveBeenCalledWith(
			process.execPath,
			["pm", "view", "example", "version", "--json", "--cwd", installRoot],
			expect.objectContaining({ env: BUILTIN_ENV, timeoutMs: expect.any(Number) }),
		);
		expect(runCommandSpy).toHaveBeenCalledWith(
			process.execPath,
			["install", "example@latest", "--cwd", realpathSync(installRoot), "--omit=peer"],
			{ env: BUILTIN_ENV },
		);
	});

	it("uses bun pm view for a configured bun", async () => {
		const { packageManager } = createPackageManager({ npmCommand: ["bun"] });
		const runCommandCaptureSpy = vi.spyOn(packageManager as any, "runCommandCapture").mockResolvedValue('"2.0.0"');

		const latest = await (packageManager as any).getLatestNpmVersion("example@^2", "^2", "project");

		expect(latest).toBe("2.0.0");
		expect(runCommandCaptureSpy).toHaveBeenCalledWith(
			"bun",
			["pm", "view", "example@^2", "version", "--json", "--cwd", join(tempDir, ".pi", "npm")],
			expect.objectContaining({ cwd: join(tempDir, ".pi", "npm"), timeoutMs: expect.any(Number) }),
		);
	});

	it("does not look for legacy global installs with the built-in bun", () => {
		const { packageManager } = createPackageManager();
		const runCommandSyncSpy = vi.spyOn(packageManager as any, "runCommandSync");

		expect(packageManager.getInstalledPath("npm:example", "user")).toBeUndefined();
		expect(runCommandSyncSpy).not.toHaveBeenCalled();
	});
});

describe.skipIf(process.platform === "win32")("findExecutableOnPath", () => {
	let tempDir: string;

	beforeEach(() => {
		tempDir = join(tmpdir(), `find-executable-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
		mkdirSync(join(tempDir, "a"), { recursive: true });
		mkdirSync(join(tempDir, "b", "tool"), { recursive: true });
		mkdirSync(join(tempDir, "c"), { recursive: true });
		writeFileSync(join(tempDir, "a", "tool"), "not executable");
		chmodSync(join(tempDir, "a", "tool"), 0o644);
		writeFileSync(join(tempDir, "c", "tool"), "#!/bin/sh\n");
		chmodSync(join(tempDir, "c", "tool"), 0o755);
	});

	afterEach(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	it("skips non-executable files and directories", () => {
		const path = ["", join(tempDir, "a"), join(tempDir, "b"), join(tempDir, "c")].join(":");
		expect(findExecutableOnPath("tool", { PATH: path })).toBe(join(tempDir, "c", "tool"));
	});

	it("returns undefined when the command is missing", () => {
		expect(findExecutableOnPath("tool", { PATH: join(tempDir, "a") })).toBeUndefined();
		expect(findExecutableOnPath("tool", {})).toBeUndefined();
	});
});
