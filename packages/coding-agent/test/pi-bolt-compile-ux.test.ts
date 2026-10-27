// `pi-bolt compile` offline from its cache, what it says while it works, what it cleans up, and what an extension compiled in
// is fingerprinted by (pi-bolt-compile.ts, core/extensions/compiled.ts).
import { spawn, spawnSync } from "node:child_process";
import { createHash, generateKeyPairSync, sign } from "node:crypto";
import {
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	utimesSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { extensionFingerprint } from "../src/core/extensions/compiled.ts";
import {
	bundledFiles,
	compileLogPath,
	download,
	extensionFiles,
	projectExtensionLines,
	releaseArchives,
	removeOldLog,
	removeStaleWorkFolders,
	runBuild,
} from "../src/pi-bolt-compile.ts";

let dir: string;

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-compile-ux-"));
	vi.spyOn(process.stderr, "write").mockImplementation(() => true);
});

afterEach(() => {
	vi.restoreAllMocks();
	vi.unstubAllGlobals();
	rmSync(dir, { recursive: true, force: true });
});

const sha256 = (data: Buffer) => createHash("sha256").update(data).digest("hex");

describe("pi-bolt compile works offline once the release's files are cached (#6, #8)", () => {
	const { publicKey, privateKey } = generateKeyPairSync("ed25519");
	const key = publicKey.export({ type: "spki", format: "pem" }).toString();
	const runtime = Buffer.from("the runtime");
	const kit = Buffer.from("the kit");
	const sums = Buffer.from(
		`# pi-bolt 0.9.0\n${sha256(runtime)}  pi-bolt-runtime-linux-x64.tar.gz\n${sha256(kit)}  pi-bolt-kit-linux-x64.tar.gz\n`,
	);
	const base = "https://example.invalid/download/bolt-v0.9.0";

	let served: Map<string, Buffer>;
	let requested: string[];
	beforeEach(() => {
		served = new Map([
			[`${base}/SHA256SUMS`, sums],
			[`${base}/SHA256SUMS.sig`, sign(null, sums, privateKey)],
			[`${base}/pi-bolt-runtime-linux-x64.tar.gz`, runtime],
			[`${base}/pi-bolt-kit-linux-x64.tar.gz`, kit],
		]);
		requested = [];
		vi.stubGlobal("fetch", async (input: string | URL) => {
			const url = String(input);
			requested.push(url);
			const body = served.get(url);
			return body ? new Response(new Uint8Array(body)) : new Response("not found", { status: 404 });
		});
	});

	test("the first compile downloads, the next needs no network", async () => {
		const cache = join(dir, "cache");
		const first = await releaseArchives(base, "0.9.0", "linux-x64", cache, key);
		expect(readFileSync(first.runtime)).toEqual(runtime);
		expect(readFileSync(first.kit)).toEqual(kit);
		expect(readdirSync(cache).sort()).toEqual(
			[
				`${sha256(kit).slice(0, 16)}-pi-bolt-kit-linux-x64.tar.gz`,
				`${sha256(runtime).slice(0, 16)}-pi-bolt-runtime-linux-x64.tar.gz`,
				"SHA256SUMS",
				"SHA256SUMS.sig",
			].sort(),
		);

		vi.stubGlobal("fetch", async () => {
			throw new Error("offline");
		});
		expect(await releaseArchives(base, "0.9.0", "linux-x64", cache, key)).toEqual(first);
	});

	test("the cache is checked as a download is: a changed archive is downloaded again, changed checksums are refused", async () => {
		const cache = join(dir, "cache");
		const first = await releaseArchives(base, "0.9.0", "linux-x64", cache, key);
		writeFileSync(first.kit, "tampered");
		requested = [];
		await releaseArchives(base, "0.9.0", "linux-x64", cache, key);
		expect(requested).toEqual([`${base}/pi-bolt-kit-linux-x64.tar.gz`]);
		expect(readFileSync(first.kit)).toEqual(kit);

		// Checksums changed in the cache no longer verify: they are fetched again, and the release's own are what count.
		writeFileSync(join(cache, "SHA256SUMS"), sums.toString().replace(sha256(kit), "0".repeat(64)));
		requested = [];
		await releaseArchives(base, "0.9.0", "linux-x64", cache, key);
		expect(requested).toEqual([`${base}/SHA256SUMS`, `${base}/SHA256SUMS.sig`]);
		expect(readFileSync(join(cache, "SHA256SUMS"))).toEqual(sums);
	});

	test("another version's cached checksums are not this one's", async () => {
		const cache = join(dir, "cache");
		await releaseArchives(base, "0.9.0", "linux-x64", cache, key);
		vi.stubGlobal("fetch", async () => {
			throw new Error("offline");
		});
		await expect(releaseArchives(base, "0.9.1", "linux-x64", cache, key)).rejects.toThrow(
			`could not download ${base}/SHA256SUMS: offline`,
		);
	});

	test("a download that does not match the signed checksum is refused", async () => {
		served.set(`${base}/pi-bolt-kit-linux-x64.tar.gz`, Buffer.from("something else"));
		await expect(releaseArchives(base, "0.9.0", "linux-x64", join(dir, "cache"), key)).rejects.toThrow(
			"checksum mismatch",
		);
	});
});

describe("a download that fails says what it tried, and gives up instead of hanging (#6)", () => {
	test("the URL, why, and what to do", async () => {
		vi.stubGlobal("fetch", async () => {
			throw Object.assign(new TypeError("fetch failed"), { cause: new Error("connect ECONNREFUSED 127.0.0.1:9") });
		});
		await expect(download("http://127.0.0.1:9/SHA256SUMS")).rejects.toThrow(
			/could not download http:\/\/127\.0\.0\.1:9\/SHA256SUMS: fetch failed: connect ECONNREFUSED.*\n.*--kit and --runtime/,
		);
		vi.stubGlobal("fetch", async () => new Response("gone", { status: 404 }));
		await expect(download("https://example.invalid/x")).rejects.toThrow(
			"could not download https://example.invalid/x: the server answered 404",
		);
	});

	test("nothing received for the idle time: given up", async () => {
		vi.stubGlobal(
			"fetch",
			(_url: string, init: { signal: AbortSignal }) =>
				new Promise((_, reject) => init.signal.addEventListener("abort", () => reject(new Error("aborted")))),
		);
		await expect(download("http://10.255.255.1/x", { idleSeconds: 0.05 })).rejects.toThrow(
			"could not download http://10.255.255.1/x: nothing came for 0.05 s",
		);
	});

	test("the size is said before the body comes", async () => {
		vi.stubGlobal("fetch", async () => new Response(new Uint8Array(3), { headers: { "content-length": "3" } }));
		const sizes: Array<number | undefined> = [];
		expect((await download("https://example.invalid/x", { onSize: (bytes) => sizes.push(bytes) })).length).toBe(3);
		expect(sizes).toEqual([3]);
	});
});

describe("the build: its lines as they come, and a stop signal (#7, #12)", () => {
	test("each line is given as it comes, and all of it is kept", async () => {
		const lines: string[] = [];
		const result = await runBuild("sh", ["-c", "echo one; echo AOT: two >&2; printf three"], {}, (line) =>
			lines.push(line),
		);
		expect(result.status).toBe(0);
		expect(result.interrupted).toBeUndefined();
		expect(lines.sort()).toEqual(["AOT: two", "one"]);
		expect(result.output).toContain("three");
	});

	test("a build stopped by Ctrl-C is interrupted, not failed", async () => {
		const result = await runBuild("sh", ["-c", "kill -INT $$; sleep 5"], {});
		expect(result.interrupted).toBe("SIGINT");
	});

	test("SIGTERM to this process is passed on to the build, which this process waits for", async () => {
		const handlers = process.listenerCount("SIGTERM");
		const running = runBuild("sh", ["-c", "sleep 5"], {});
		expect(process.listenerCount("SIGTERM")).toBe(handlers + 1);
		await new Promise((done) => setTimeout(done, 50));
		process.emit("SIGTERM");
		expect((await running).interrupted).toBe("SIGTERM");
		expect(process.listenerCount("SIGTERM")).toBe(handlers);
	});
});

describe("pi-bolt compile cleans up after itself (#11)", () => {
	test("work folders of a process that no longer runs go; one in use, and other folders, stay", async () => {
		const gone = spawn("true");
		await new Promise((done) => gone.on("close", done));
		const folder = (name: string, pid?: number) => {
			mkdirSync(join(dir, name, "kit"), { recursive: true });
			if (pid !== undefined) writeFileSync(join(dir, name, "pid"), `${pid}\n`);
			return join(dir, name);
		};
		const killed = folder(".compile-Ab12Cd", gone.pid);
		folder(".compile-InUse1", process.pid);
		// (Pi-Bolt 1.0.4's name no process: by age, and only in an installation's folder.)
		const old = folder(".compile-Old123");
		utimesSync(old, 1_000_000, 1_000_000);
		folder(".compile-New123");
		folder(".compile-not-a-work-folder");

		expect(removeStaleWorkFolders(dir, false)).toEqual([killed]);
		expect(removeStaleWorkFolders(dir, true)).toEqual([old]);
		expect(readdirSync(dir).sort()).toEqual([".compile-InUse1", ".compile-New123", ".compile-not-a-work-folder"]);
	});

	test("a successful compile removes the old compile.log: in the installation always, beside --out only its own", () => {
		const log = join(dir, "compile.log");
		writeFileSync(log, "an old failure\n");
		removeOldLog(log, false);
		expect(existsSync(log)).toBe(true);
		removeOldLog(log, true);
		expect(existsSync(log)).toBe(false);
		writeFileSync(log, "# pi-bolt compile: all of what the compiler printed, 2026-10-27T00:00:00.000Z\nerror\n");
		removeOldLog(log, false);
		expect(existsSync(log)).toBe(false);
		removeOldLog(log, true);
	});

	test("with --out, the log is named after the output folder: two builds into one folder have their own", () => {
		expect(compileLogPath("/install", undefined)).toBe("/install/compile.log");
		expect(compileLogPath("/work", "/work/one")).toBe("/work/one.compile.log");
		expect(compileLogPath("/work", "/work/two/")).toBe("/work/two.compile.log");
	});

	test("the installer's uninstall removes compile.log and the work folders too", () => {
		const install = join(dir, "install");
		mkdirSync(join(install, "pi-bolt-linux-x64"), { recursive: true });
		mkdirSync(join(install, ".compile-Ab12Cd", "kit"), { recursive: true });
		mkdirSync(join(install, "cache"));
		for (const file of ["compile.json", "compile.log", "channel"]) writeFileSync(join(install, file), "");
		const installer = readFileSync(resolve(__dirname, "../../../install.sh"), "utf8");
		const uninstall = /^uninstall\(\) \{\n[\s\S]*?\n\}\n/m.exec(installer)?.[0];
		expect(uninstall).toBeDefined();
		const result = spawnSync("sh", ["-c", `${uninstall}\nuninstall`], {
			env: { ...process.env, INSTALL: install, BIN_DIR: join(dir, "bin") },
			encoding: "utf8",
		});
		expect(result.status, result.stderr).toBe(0);
		expect(existsSync(install)).toBe(false);
	});
});

describe("pi-bolt compile names the project's extensions it leaves out (#9)", () => {
	test("its .pi/extensions and those its settings name, each with why", () => {
		const project = join(dir, "project");
		mkdirSync(join(project, ".pi", "extensions", "folder-ext"), { recursive: true });
		writeFileSync(join(project, ".pi", "extensions", "file-ext.ts"), "export default () => {};\n");
		writeFileSync(join(project, ".pi", "extensions", "folder-ext", "index.ts"), "export default () => {};\n");
		writeFileSync(
			join(project, ".pi", "settings.json"),
			JSON.stringify({
				packages: ["npm:proj-pkg", { source: "git:github.com/a/b" }],
				extensions: ["-x.ts", "./y.ts"],
			}),
		);
		const why = "not compiled in: a project's extensions load from their files (docs/PLUGINS.md#pi-bolt-compile)";
		expect(projectExtensionLines(project, join(dir, "agent")).sort()).toEqual(
			[
				`.pi/extensions/file-ext.ts (project): ${why}`,
				`.pi/extensions/folder-ext/index.ts (project): ${why}`,
				`./y.ts (project .pi/settings.json extensions): ${why}`,
				`npm:proj-pkg (project .pi/settings.json packages): ${why}`,
				`git:github.com/a/b (project .pi/settings.json packages): ${why}`,
			].sort(),
		);
		expect(projectExtensionLines(join(dir, "nothing-here"), join(dir, "agent"))).toEqual([]);
		// (The agent directory is not a project.)
		expect(projectExtensionLines(project, join(project, ".pi"))).toEqual([]);
	});
});

describe("an extension compiled in is fingerprinted by the files it was compiled from (loader parity #10)", () => {
	function at(path: string, text: string, seconds: number): string {
		mkdirSync(resolve(path, ".."), { recursive: true });
		writeFileSync(path, text);
		utimesSync(path, seconds, seconds);
		return path;
	}

	test("state written into its folder is no change; an edit to any module it was compiled from is", () => {
		const root = join(dir, "node_modules", "pkg");
		const entry = at(join(root, "index.js"), 'export { v } from "./helper.js";\n', 1_000_000);
		const helper = at(join(root, "helper.js"), 'export const v = "v1";\n', 1_000_000);
		const dependency = at(join(dir, "node_modules", "dep", "index.js"), "export const d = 1;\n", 1_000_000);
		const source = { entry, scope: "package" as const, root, files: [dependency, entry, helper] };
		const before = extensionFingerprint(source);

		at(join(root, "state.json"), "{}", 2_000_000);
		expect(extensionFingerprint(source)).toBe(before);
		at(helper, 'export const v = "v2";\n', 2_000_000);
		expect(extensionFingerprint(source)).not.toBe(before);
		at(helper, 'export const v = "v1";\n', 1_000_000);
		expect(extensionFingerprint(source)).toBe(before);
		at(dependency, "export const d = 2;\n", 2_000_000);
		expect(extensionFingerprint(source)).not.toBe(before);
		rmSync(helper);
		expect(extensionFingerprint(source)).toBeUndefined();
	});

	test("the files each extension reaches in the bundler's graph, as installed; Pi's modules are not its own", () => {
		const src = "/kit/agent/.pibolt-plugins/src";
		const plan = {
			manifest: `${src}/plugins.ts`,
			plugins: [
				{
					name: "a",
					entry: "/agent/extensions/a.ts",
					origin: "global",
					stageDir: "global",
					importPath: "./global/a.ts",
					aliases: [],
				},
				{
					name: "pkg",
					entry: "/agent/npm/node_modules/pkg/index.ts",
					origin: "package",
					stageDir: "package-pkg",
					importPath: "./package-pkg/index.ts",
					aliases: [],
				},
			],
			roots: [
				{ stageDir: "global", root: "/agent/extensions", origin: "global" },
				{ stageDir: "package-pkg", root: "/agent/npm/node_modules/pkg", origin: "package" },
			],
			dependencies: [{ name: "dep", from: "/agent/npm/node_modules/dep" }],
		};
		const inputs = {
			".pibolt-plugins/src/plugins.ts": [
				".pibolt-plugins/src/global/a.ts",
				".pibolt-plugins/src/package-pkg/index.ts",
			],
			".pibolt-plugins/src/global/a.ts": [".pibolt-plugins/src/global/shared/util.ts", "src/core/pi-module.ts"],
			".pibolt-plugins/src/global/shared/util.ts": [],
			".pibolt-plugins/src/package-pkg/index.ts": [
				".pibolt-plugins/src/package-pkg/lib/helper.ts",
				".pibolt-plugins/src/node_modules/dep/index.js",
			],
			".pibolt-plugins/src/package-pkg/lib/helper.ts": [".pibolt-plugins/src/global/a.ts"],
			".pibolt-plugins/src/node_modules/dep/index.js": [],
			"src/core/pi-module.ts": [],
		};
		const files = bundledFiles(inputs, "/kit/agent", src, plan);
		expect(files.get("a")).toEqual(["/agent/extensions/a.ts", "/agent/extensions/shared/util.ts"]);
		expect(files.get("pkg")).toEqual([
			"/agent/extensions/a.ts",
			"/agent/extensions/shared/util.ts",
			"/agent/npm/node_modules/dep/index.js",
			"/agent/npm/node_modules/pkg/index.ts",
			"/agent/npm/node_modules/pkg/lib/helper.ts",
		]);
	});

	test("with the bundler: an extension's files are its modules and its dependencies', not the rest of its folder", (context) => {
		if (spawnSync("bun", ["--version"]).status !== 0) return context.skip();
		const installed = join(dir, "installed");
		at(
			join(installed, "ext", "index.ts"),
			'import { d } from "dep";\nimport { h } from "./helper.ts";\nexport default () => d + h;\n',
			1,
		);
		at(join(installed, "ext", "helper.ts"), "export const h = 1;\n", 1);
		at(join(installed, "ext", "state.json"), "{}\n", 1);
		// (Its package.json chooses modules too: an `imports` mapping changed is a change of its code.)
		at(join(installed, "ext", "package.json"), '{"imports":{"#impl":"./helper.ts"}}\n', 1);
		at(join(installed, "node_modules", "dep", "package.json"), '{"name":"dep","main":"index.js"}\n', 1);
		at(join(installed, "node_modules", "dep", "index.js"), "export const d = 1;\n", 1);
		// The copies, as the generator makes them.
		const agent = join(dir, "kit-agent");
		const src = join(agent, ".pibolt-plugins", "src");
		at(join(src, "global", "ext", "index.ts"), readFileSync(join(installed, "ext", "index.ts"), "utf8"), 1);
		at(join(src, "global", "ext", "helper.ts"), "export const h = 1;\n", 1);
		at(join(src, "node_modules", "dep", "package.json"), '{"name":"dep","main":"index.js"}\n', 1);
		at(join(src, "node_modules", "dep", "index.js"), "export const d = 1;\n", 1);
		const manifest = at(
			join(src, "plugins.ts"),
			'export default [(await import("./global/ext/index.ts")).default];\n',
			1,
		);
		const plan = {
			manifest,
			plugins: [
				{
					name: "ext",
					entry: join(installed, "ext", "index.ts"),
					origin: "global",
					stageDir: "global",
					importPath: "./global/ext/index.ts",
					aliases: [],
				},
			],
			roots: [{ stageDir: "global", root: installed, origin: "global" }],
			dependencies: [{ name: "dep", from: join(installed, "node_modules", "dep") }],
		};
		const work = mkdtempSync(join(tmpdir(), "pibolt-inputs-"));
		try {
			const real = (path: string) => spawnSync("realpath", [path], { encoding: "utf8" }).stdout.trim();
			expect(extensionFiles("bun", plan, agent, work).get("ext")).toEqual(
				[
					real(join(installed, "ext", "helper.ts")),
					real(join(installed, "ext", "index.ts")),
					real(join(installed, "ext", "package.json")),
					real(join(installed, "node_modules", "dep", "index.js")),
					real(join(installed, "node_modules", "dep", "package.json")),
				].sort(),
			);
		} finally {
			rmSync(work, { recursive: true, force: true });
		}
	});
});
