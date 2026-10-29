import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type * as PiBolt from "../src/pi-bolt.ts";
import { autoUpdatePiBolt, shouldAutoUpdatePiBolt } from "../src/pi-bolt-auto-update.ts";
import type * as ChildProcessUtils from "../src/utils/child-process.ts";

const mocks = vi.hoisted(() => ({ spawnProcess: vi.fn(), installMethod: vi.fn(), installed: vi.fn() }));

vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "1.0.2", variant: "arm64", jit: false },
	piBoltInstallMethod: mocks.installMethod,
	installedByPiBoltInstaller: mocks.installed,
}));

vi.mock("../src/utils/child-process.ts", async (importOriginal) => ({
	...(await importOriginal<typeof ChildProcessUtils>()),
	spawnProcess: mocks.spawnProcess,
}));

function fakeChild(): EventEmitter & { stdout: PassThrough; stderr: PassThrough } {
	return Object.assign(new EventEmitter(), {
		stdout: new PassThrough(),
		stderr: new PassThrough(),
	});
}

beforeEach(() => {
	mocks.installMethod.mockReturnValue("installer");
	mocks.installed.mockReturnValue(true);
	mocks.spawnProcess.mockReset();
	vi.stubEnv("PIBOLT_AUTO_UPDATE", "");
});

afterEach(() => {
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
});

describe("Pi-Bolt automatic updates", () => {
	it("enables automatic updates for native installer installs by default", () => {
		expect(shouldAutoUpdatePiBolt()).toBe(true);
	});

	it("can be disabled with PIBOLT_AUTO_UPDATE=0", () => {
		vi.stubEnv("PIBOLT_AUTO_UPDATE", "0");
		expect(shouldAutoUpdatePiBolt()).toBe(false);
	});

	it("does not self-update an executable outside an installer-managed directory", () => {
		mocks.installed.mockReturnValue(false);
		expect(shouldAutoUpdatePiBolt()).toBe(false);
	});

	it.each(["npm", "homebrew", "scoop", "mise"])("leaves %s-managed installs to their package manager", (method) => {
		mocks.installMethod.mockReturnValue(method);
		expect(shouldAutoUpdatePiBolt()).toBe(false);
	});

	it("runs the native Pi-Bolt executable's signed self-update command", async () => {
		const child = fakeChild();
		mocks.spawnProcess.mockReturnValue(child);

		const result = autoUpdatePiBolt();
		expect(mocks.spawnProcess).toHaveBeenCalledWith(process.execPath, ["update", "--self"], {
			stdio: ["ignore", "pipe", "pipe"],
		});
		child.stdout?.write("Updating Pi-Bolt...\n");
		child.emit("close", 0, null);

		await expect(result).resolves.toEqual({ updated: true, output: "Updating Pi-Bolt...\n" });
	});

	it("reports updater failures for the interactive startup notice", async () => {
		const child = fakeChild();
		mocks.spawnProcess.mockReturnValue(child);

		const result = autoUpdatePiBolt();
		child.stderr?.write("signature did not verify\n");
		child.emit("close", 1, null);

		await expect(result).resolves.toMatchObject({
			updated: false,
			output: expect.stringContaining("signature did not verify"),
		});
	});
});
