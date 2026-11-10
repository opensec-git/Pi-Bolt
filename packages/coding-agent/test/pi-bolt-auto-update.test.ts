// Automatic updates, opt-in (#208): who starts one, what it runs, the lock, and what a session shows (pi-bolt-auto-update.ts).
import { createHash, createPrivateKey, generateKeyPairSync, type KeyObject, sign } from "node:crypto";
import {
	chmodSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	utimesSync,
	writeFileSync,
} from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import lockfile from "proper-lockfile";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsManager } from "../src/core/settings-manager.ts";
import {
	type SettingsCallbacks,
	type SettingsConfig,
	SettingsSelectorComponent,
} from "../src/modes/interactive/components/settings-selector.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import type * as PiBolt from "../src/pi-bolt.ts";
import {
	AUTO_UPDATE_INTERVAL_MS,
	AUTO_UPDATE_LOCK_STALE_MS,
	type AutoUpdateLine,
	autoUpdateLine,
	autoUpdatePaths,
	planAutoUpdate,
	readAutoUpdateRecord,
	startAutoUpdate,
	writeAutoUpdateRecord,
} from "../src/pi-bolt-auto-update.ts";
import { allowNetwork } from "./test-network-env.ts";

// A Pi-Bolt 1.0.2 build whose release key is this test's: the fake release server's checksums are signed with it.
// (A key made for this test only. vi.mock runs before the imports, so the key is written out rather than generated.)
const keys = vi.hoisted(() => ({
	publicPem:
		"-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAE/DjxB4IY4fqkn4nUU7mHHb9DourHdRT213udqChdME=\n-----END PUBLIC KEY-----\n",
	privatePem:
		"-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIEBIGPfvEgfu/Qx5nuxevl3y8Oy3vs88OhnLA5xn/Fau\n-----END PRIVATE KEY-----\n",
}));
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "1.0.2", variant: "x64", jit: false },
	PIBOLT_RELEASE_KEY: keys.publicPem,
}));
const releaseKey = createPrivateKey(keys.privatePem);

const realExecPath = process.execPath;
let dir: string;
let installDir: string;
let buildDir: string;

beforeEach(() => {
	allowNetwork();
	dir = mkdtempSync(join(tmpdir(), "pibolt-auto-update-"));
	vi.stubEnv("HOME", dir);
	vi.stubEnv("PIBOLT_CHANNEL", "");
	vi.stubEnv("PIBOLT_NPM", "");
	vi.stubEnv("PIBOLT_NO_AUTO_UPDATE", "");
	vi.stubEnv("PI_SKIP_VERSION_CHECK", "");
	vi.stubEnv("SCOOP", "");
	vi.stubEnv("SCOOP_GLOBAL", "");
	vi.stubEnv("MISE_DATA_DIR", "");
	// An installation the installer made: ~/.pi-bolt/pi-bolt-<platform>-x64.
	installDir = join(dir, ".pi-bolt");
	buildDir = join(installDir, `pi-bolt-${process.platform}-x64`);
	mkdirSync(buildDir, { recursive: true });
	writeFileSync(join(buildDir, "pi"), "");
	Object.defineProperty(process, "execPath", { value: join(buildDir, "pi"), configurable: true });
});

afterEach(() => {
	Object.defineProperty(process, "execPath", { value: realExecPath, configurable: true });
	vi.unstubAllGlobals();
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

describe("the setting", () => {
	const write = (path: string, data: unknown) => {
		mkdirSync(resolve(path, ".."), { recursive: true });
		writeFileSync(path, JSON.stringify(data));
	};

	it("is off by default", () => {
		expect(SettingsManager.inMemory().getPiBoltAutoUpdate()).toBe(false);
		expect(planAutoUpdate(false, { installDir, byInstaller: true, method: "installer" })).toEqual({
			kind: "skip",
			reason: "piBolt.autoUpdate is off",
		});
	});

	it("is read from the global settings only: a trusted project's settings cannot turn it on", () => {
		const agentDir = join(dir, "agent");
		const project = join(dir, "project");
		mkdirSync(agentDir, { recursive: true });
		write(join(project, ".pi", "settings.json"), { piBolt: { autoUpdate: true } });
		expect(SettingsManager.create(project, agentDir, { projectTrusted: true }).getPiBoltAutoUpdate()).toBe(false);
		write(join(agentDir, "settings.json"), { piBolt: { autoUpdate: "true" } });
		expect(SettingsManager.create(project, agentDir, { projectTrusted: true }).getPiBoltAutoUpdate()).toBe(false);
		write(join(agentDir, "settings.json"), { piBolt: { autoUpdate: true } });
		expect(SettingsManager.create(project, agentDir, { projectTrusted: true }).getPiBoltAutoUpdate()).toBe(true);
	});

	it("is written to the global settings, beside piBolt.packages", () => {
		const settings = SettingsManager.inMemory({ piBolt: { packages: ["npm:a"] } });
		settings.setPiBoltAutoUpdate(true);
		expect(settings.getPiBoltAutoUpdate()).toBe(true);
		expect(settings.getGlobalSettings().piBolt).toMatchObject({ packages: ["npm:a"], autoUpdate: true });
	});

	it("has a toggle in /settings in a Pi-Bolt build, and none otherwise", () => {
		initTheme("dark");
		const onPiBoltAutoUpdateChange = vi.fn();
		const base = {
			warnings: {},
			defaultModel: "not set",
			availableDefaultModels: [],
			availableThinkingLevels: [],
			modelThinkingLevels: {},
			availableThemes: [],
		};
		const callbacks = { onPiBoltAutoUpdateChange } as unknown as SettingsCallbacks;
		const list = new SettingsSelectorComponent(
			{ ...base, piBoltAutoUpdate: false } as unknown as SettingsConfig,
			callbacks,
		).getSettingsList();
		list.selectItem("pibolt-auto-update");
		list.handleInput("\r");
		list.handleInput("\r");
		expect(onPiBoltAutoUpdateChange.mock.calls.flat()).toEqual([true, false]);
		const ids = (config: object) =>
			(
				new SettingsSelectorComponent(config as SettingsConfig, callbacks).getSettingsList() as unknown as {
					items: Array<{ id: string }>;
				}
			).items.map((item) => item.id);
		expect(ids({ ...base, piBoltAutoUpdate: false })).toContain("pibolt-auto-update");
		expect(ids(base)).not.toContain("pibolt-auto-update");
	});
});

describe("who starts an update", () => {
	const on = { method: "installer" as const, installDir: "", byInstaller: true };
	beforeEach(() => {
		on.installDir = installDir;
	});

	it("an installation the installer made, with the setting on, once a day", () => {
		expect(planAutoUpdate(true, on)).toEqual({ kind: "start", installDir });
		writeAutoUpdateRecord(installDir, { checkedAt: 1_000_000 });
		expect(planAutoUpdate(true, { ...on, now: 1_000_000 + AUTO_UPDATE_INTERVAL_MS - 1 })).toMatchObject({
			kind: "skip",
		});
		expect(planAutoUpdate(true, { ...on, now: 1_000_000 + AUTO_UPDATE_INTERVAL_MS })).toEqual({
			kind: "start",
			installDir,
		});
	});

	it("PIBOLT_NO_AUTO_UPDATE turns it off whatever the setting says", () => {
		for (const value of ["1", "true", "yes"]) {
			expect(planAutoUpdate(true, { ...on, env: { PIBOLT_NO_AUTO_UPDATE: value } })).toEqual({
				kind: "skip",
				reason: "PIBOLT_NO_AUTO_UPDATE is set",
			});
		}
		expect(planAutoUpdate(true, { ...on, env: { PIBOLT_NO_AUTO_UPDATE: "0" } }).kind).toBe("start");
	});

	it("never where the version check is off", () => {
		expect(planAutoUpdate(true, { ...on, env: { PI_SKIP_VERSION_CHECK: "1" } }).kind).toBe("skip");
		expect(planAutoUpdate(true, { ...on, env: { PI_OFFLINE: "1" } }).kind).toBe("skip");
	});

	it("never for an installation npm, Homebrew, Scoop or mise manage, a build run from elsewhere, or not Pi-Bolt", () => {
		for (const method of ["npm", "homebrew", "scoop", "mise"] as const) {
			expect(planAutoUpdate(true, { ...on, method })).toEqual({ kind: "skip", reason: `installed with ${method}` });
		}
		expect(planAutoUpdate(true, { ...on, installDir: undefined }).kind).toBe("skip");
		// (An archive unpacked by hand, as doctor tells it from the installer's: not in ~/.pi-bolt, no `pi-bolt` on PATH.)
		expect(planAutoUpdate(true, { ...on, byInstaller: false }).kind).toBe("skip");
		expect(planAutoUpdate(true, { ...on, build: undefined }).kind).toBe("skip");
	});

	it("tells an installation the installer made from an archive unpacked by hand, as doctor does", () => {
		expect(planAutoUpdate(true, { env: {} }).kind).toBe("start");
		const elsewhere = join(dir, "tools", `pi-bolt-${process.platform}-x64`);
		mkdirSync(elsewhere, { recursive: true });
		Object.defineProperty(process, "execPath", { value: join(elsewhere, "pi"), configurable: true });
		vi.stubEnv("PATH", "");
		expect(planAutoUpdate(true, { env: {} })).toEqual({
			kind: "skip",
			reason: "not an installation the installer made",
		});
	});

	it("only an interactive session starts one: -p, RPC, ACP and the service do not", () => {
		const src = resolve(__dirname, "../src");
		const callers: string[] = [];
		const walk = (folder: string) => {
			for (const entry of readdirSync(folder, { withFileTypes: true })) {
				const path = join(folder, entry.name);
				if (entry.isDirectory()) walk(path);
				else if (/\.ts$/.test(entry.name) && /\bstartAutoUpdate\(/.test(readFileSync(path, "utf8"))) {
					callers.push(path.slice(src.length + 1));
				}
			}
		};
		walk(src);
		expect(callers.sort()).toEqual(["modes/interactive/interactive-mode.ts", "pi-bolt-auto-update.ts"]);
	});
});

describe("an interactive session", () => {
	const start = (InteractiveMode.prototype as unknown as { startPiBoltAutoUpdate(this: object): boolean })
		.startPiBoltAutoUpdate;
	const session = (enabled: boolean) => ({
		settingsManager: SettingsManager.inMemory({ piBolt: { autoUpdate: enabled } }),
		isInitialized: true,
		showWarning: vi.fn(),
	});

	it("starts nothing with the setting off: the notice of a newer version is shown as before", () => {
		const fake = session(false);
		expect(start.call(fake)).toBe(false);
		expect(existsSync(autoUpdatePaths(installDir).log)).toBe(false);
	});

	it.skipIf(process.platform === "win32")(
		"with it on, starts the update (in place of the notice) and shows its line when it ends",
		async () => {
			// (The installation's executable here is an empty file: starting it fails, which the session reports.)
			const fake = session(true);
			expect(start.call(fake)).toBe(true);
			for (let i = 0; i < 200 && fake.showWarning.mock.calls.length === 0; i++) {
				await new Promise((r) => setTimeout(r, 10));
			}
			expect(String(fake.showWarning.mock.calls[0]?.[0])).toMatch(/^Pi-Bolt could not start its update: /);
		},
	);
});

describe("what a session shows", () => {
	it("one line when it is installed, one with the reason when it failed, nothing when there is no update", () => {
		expect(autoUpdateLine({ kind: "current", version: "1.0.2" }, "LOG")).toBeUndefined();
		expect(autoUpdateLine({ kind: "installed", version: "1.0.3" }, "LOG")).toEqual({
			kind: "info",
			text: "Pi-Bolt 1.0.3 is installed; it starts next time.",
		});
		expect(autoUpdateLine({ kind: "installed", version: "1.0.3", extensions: "compiled" }, "LOG")?.text).toContain(
			"compiled into it in the background",
		);
		expect(
			autoUpdateLine({ kind: "installed", version: "1.0.3", extensions: "not-compiled" }, "LOG")?.text,
		).toContain("load from their files until `pi-bolt compile` compiles them in");
		expect(autoUpdateLine({ kind: "failed", reason: "download failed", version: "1.0.3" }, "LOG")).toEqual({
			kind: "error",
			text: "Pi-Bolt could not update itself to 1.0.3: download failed. Run `pi-bolt update` to update it yourself (LOG).",
		});
	});

	/** Starts a fake update that runs `script` (sh), and resolves with what was reported once it has exited. */
	async function startFake(script: string): Promise<AutoUpdateLine[]> {
		const reported: AutoUpdateLine[] = [];
		const executable = join(dir, "fake-update.sh");
		writeFileSync(executable, `#!/bin/sh\n${script}\n`);
		chmodSync(executable, 0o755);
		startAutoUpdate(installDir, (line) => reported.push(line), { executable, args: [] });
		// (The record is read on exit; give the exit event its turn.)
		const done = join(dir, "done");
		for (let i = 0; i < 200 && !existsSync(done); i++) await new Promise((r) => setTimeout(r, 10));
		await new Promise((r) => setTimeout(r, 100));
		return reported;
	}
	const record = (result: string) =>
		// (A start time after the session's: `date` has whole seconds only.)
		`printf '%s' '{"checkedAt": ${Date.now() + 60_000}, "result": ${result}}' > "${join(installDir, "auto-update.json")}"; echo started; touch "${join(dir, "done")}"`;

	it.skipIf(process.platform === "win32")("reports the update it started, in a process of its own", async () => {
		const reported = await startFake(record('{"kind":"installed","version":"1.0.3"}'));
		expect(reported).toEqual([{ kind: "info", text: "Pi-Bolt 1.0.3 is installed; it starts next time." }]);
		expect(readFileSync(autoUpdatePaths(installDir).log, "utf8")).toBe("started\n");
	});

	it.skipIf(process.platform === "win32")("nothing when there is no update", async () => {
		expect(await startFake(record('{"kind":"current","version":"1.0.2"}'))).toEqual([]);
	});

	it.skipIf(process.platform === "win32")(
		"nothing when another Pi-Bolt was updating: an earlier record is not this update's",
		async () => {
			writeAutoUpdateRecord(installDir, { checkedAt: 1000, result: { kind: "failed", reason: "old" } });
			expect(await startFake(`touch "${join(dir, "done")}"`)).toEqual([]);
		},
	);
});

describe("the update a session starts (pi-bolt update --background)", () => {
	let server: Server;
	let base: string;
	let requests: string[];
	let releaseVersion: string;
	let signWith: KeyObject;
	let compileExit: number;
	const realFetch = globalThis.fetch;

	/** The installer the fake release serves: it puts a `pi` of `releaseVersion` in place, whose `compile` writes compile.json. */
	const installer = () => `#!/bin/sh
set -e
dir="$PIBOLT_INSTALL/pi-bolt-${process.platform}-$PIBOLT_VARIANT"
mkdir -p "$dir"
cat > "$dir/pi" <<'EOS'
#!/bin/sh
if [ "$1" = compile ]; then
	[ ${compileExit} = 0 ] || exit ${compileExit}
	printf '{"version":"${releaseVersion}","extensions":["a"]}\\n' > "$(dirname "$0")/../compile.json"
	exit 0
fi
echo "1.5.0 (Pi-Bolt ${releaseVersion}, test, JIT off)"
EOS
chmod +x "$dir/pi"
`;

	beforeEach(async () => {
		requests = [];
		releaseVersion = "1.0.3";
		signWith = releaseKey;
		compileExit = 0;
		server = createServer((request, response) => {
			const url = request.url ?? "";
			requests.push(url);
			const tag = `bolt-v${releaseVersion}`;
			if (url === "/opensec-git/Pi-Bolt/releases/latest") {
				response.writeHead(302, { location: `https://github.com/opensec-git/Pi-Bolt/releases/tag/${tag}` });
				response.end();
				return;
			}
			const script = Buffer.from(installer());
			const sha = (data: Buffer) => createHash("sha256").update(data).digest("hex");
			const sums = Buffer.from(`# pi-bolt ${releaseVersion}\n${sha(script)}  install.sh\n`);
			const files: Record<string, Buffer> = {
				[`/opensec-git/Pi-Bolt/releases/download/${tag}/SHA256SUMS`]: sums,
				[`/opensec-git/Pi-Bolt/releases/download/${tag}/SHA256SUMS.sig`]: sign(null, sums, signWith),
				[`/opensec-git/Pi-Bolt/releases/download/${tag}/install.sh`]: script,
			};
			const body = files[url];
			response.writeHead(body ? 200 : 404);
			response.end(body);
		});
		await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
		base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
		// github.com is this server: nothing is downloaded from GitHub.
		vi.stubGlobal("fetch", (input: string | URL | Request, init?: RequestInit) => {
			const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
			if (url.hostname !== "github.com" && url.hostname !== "api.github.com") {
				throw new Error(`unexpected request: ${url.href}`);
			}
			return realFetch(`${base}${url.pathname}`, init);
		});
		vi.spyOn(console, "log").mockImplementation(() => {});
		vi.spyOn(console, "error").mockImplementation(() => {});
	});

	afterEach(async () => {
		server.closeAllConnections();
		await new Promise((done) => server.close(done));
	});

	it.skipIf(process.platform === "win32")(
		"installs the latest release with the signed update, and records it",
		async () => {
			expect(await handlePackageCommand(["update", "--background"])).toBe(true);
			expect(process.exitCode).toBe(0);
			expect(readAutoUpdateRecord(installDir)?.result).toEqual({ kind: "installed", version: "1.0.3" });
			expect(readFileSync(join(buildDir, "pi"), "utf8")).toContain("Pi-Bolt 1.0.3");
			expect(requests).toContain("/opensec-git/Pi-Bolt/releases/download/bolt-v1.0.3/SHA256SUMS.sig");
			// The lock is released.
			expect(existsSync(`${autoUpdatePaths(installDir).lock}.lock`)).toBe(false);
		},
	);

	it.skipIf(process.platform === "win32")(
		"compiles the extensions compiled in before into it, in the background process, and says whether it could",
		async () => {
			writeFileSync(join(installDir, "compile.json"), JSON.stringify({ version: "1.0.2", extensions: ["a"] }));
			await handlePackageCommand(["update", "--background"]);
			expect(readAutoUpdateRecord(installDir)?.result).toEqual({
				kind: "installed",
				version: "1.0.3",
				extensions: "compiled",
			});
			releaseVersion = "1.0.4";
			compileExit = 1;
			await handlePackageCommand(["update", "--background"]);
			expect(readAutoUpdateRecord(installDir)?.result).toEqual({
				kind: "installed",
				version: "1.0.4",
				extensions: "not-compiled",
			});
		},
	);

	it.skipIf(process.platform === "win32")("checks the release's signature as pi-bolt update does", async () => {
		signWith = generateKeyPairSync("ed25519").privateKey;
		await handlePackageCommand(["update", "--background"]);
		const result = readAutoUpdateRecord(installDir)?.result;
		expect(result).toMatchObject({ kind: "failed", version: "1.0.3" });
		expect(result?.kind === "failed" && result.reason).toContain("signature does not verify");
		expect(readFileSync(join(buildDir, "pi"), "utf8")).toBe("");
		expect(requests).not.toContain("/opensec-git/Pi-Bolt/releases/download/bolt-v1.0.3/install.sh");
	});

	it("records an installation that is up to date, and installs nothing", async () => {
		releaseVersion = "1.0.2";
		await handlePackageCommand(["update", "--background"]);
		expect(readAutoUpdateRecord(installDir)?.result).toEqual({ kind: "current", version: "1.0.2" });
		expect(requests).toEqual(["/opensec-git/Pi-Bolt/releases/latest"]);
	});

	it("one at a time: an update another Pi-Bolt holds the lock of is not started again", async () => {
		const release = await lockfile.lock(autoUpdatePaths(installDir).lock, { realpath: false });
		try {
			expect(await handlePackageCommand(["update", "--background"])).toBe(true);
			expect(requests).toEqual([]);
			expect(readAutoUpdateRecord(installDir)).toBeUndefined();
		} finally {
			await release();
		}
	});

	it("a lock an update that crashed left does not hold for ever", async () => {
		const held = `${autoUpdatePaths(installDir).lock}.lock`;
		mkdirSync(held);
		const old = (Date.now() - AUTO_UPDATE_LOCK_STALE_MS - 60_000) / 1000;
		utimesSync(held, old, old);
		releaseVersion = "1.0.2";
		await handlePackageCommand(["update", "--background"]);
		expect(readAutoUpdateRecord(installDir)?.result).toEqual({ kind: "current", version: "1.0.2" });
		expect(existsSync(held)).toBe(false);
	});
});
