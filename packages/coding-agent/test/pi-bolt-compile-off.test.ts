import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type * as PiBolt from "../src/pi-bolt.ts";
import { runPiBoltCompile } from "../src/pi-bolt-compile.ts";

// A Pi-Bolt build (scripts/build-pi.sh defines PIBOLT_BUILD); the tests run as Pi otherwise. (vi.mock runs before the imports.)
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "0.9.1", variant: "arm64", jit: false },
}));

const realExecPath = process.execPath;
let install: string;

beforeEach(() => {
	install = mkdtempSync(join(tmpdir(), "pibolt-off-"));
	mkdirSync(join(install, "pi-bolt-linux-x64"));
	Object.defineProperty(process, "execPath", { value: join(install, "pi-bolt-linux-x64", "pi"), configurable: true });
	writeFileSync(join(install, "compile.json"), JSON.stringify({ version: "0.9.1", extensions: ["word-count"] }));
});

afterEach(() => {
	Object.defineProperty(process, "execPath", { value: realExecPath, configurable: true });
	rmSync(install, { recursive: true, force: true });
});

describe.skipIf(process.platform === "win32")("pi-bolt compile --off", () => {
	test("keeps what was compiled in when the release could not be installed again", async () => {
		expect(await runPiBoltCompile(["--off"], async () => false)).toBe(1);
		expect(existsSync(join(install, "compile.json"))).toBe(true);
	});

	test("forgets it once the release is installed again", async () => {
		expect(await runPiBoltCompile(["--off"], async () => true)).toBe(0);
		expect(existsSync(join(install, "compile.json"))).toBe(false);
		expect(existsSync(join(install, "compile.json.off"))).toBe(false);
	});

	test("the installer it runs finds no compile.json, so it does not say to compile the extensions in again", async () => {
		// (install.sh prints "Extensions were compiled into the build this replaced: run pi-bolt compile ..." when it finds one.)
		let seenByInstaller: boolean | undefined;
		expect(
			await runPiBoltCompile(["--off"], async () => {
				seenByInstaller = existsSync(join(install, "compile.json"));
				return true;
			}),
		).toBe(0);
		expect(seenByInstaller).toBe(false);
	});

	test("puts compile.json back when the installer throws", async () => {
		expect(
			await runPiBoltCompile(["--off"], async () => {
				throw new Error("the installer did not start");
			}),
		).toBe(1);
		expect(existsSync(join(install, "compile.json"))).toBe(true);
	});
});
