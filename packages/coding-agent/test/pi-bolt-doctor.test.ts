import { generateKeyPairSync, sign } from "node:crypto";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
	analyzePiBoltOnPath,
	type DoctorReport,
	extensionRows,
	findBunFfiImport,
	formatDoctorReport,
	jitFinding,
	packageListRows,
	packageListsOf,
	piBoltAgentServers,
	redactReport,
	runDoctor,
	stripJsonComments,
	verifyReleaseSums,
} from "../src/cli/doctor.ts";
import type * as PiBoltModule from "../src/pi-bolt.ts";
import { PIBOLT_RELEASE_KEY, type PiBoltBuild } from "../src/pi-bolt.ts";

const build = vi.hoisted(() => ({ value: undefined as PiBoltBuild | undefined }));
vi.mock("../src/pi-bolt.ts", async (importOriginal) => {
	const original = await importOriginal<typeof PiBoltModule>();
	return {
		...original,
		get PIBOLT() {
			return build.value;
		},
	};
});

let tempDir: string;

beforeEach(() => {
	build.value = { version: "0.8.8", variant: "arm64", jit: false, runtimeStamp: "0f61517d28995399" };
	tempDir = mkdtempSync(join(tmpdir(), "pi-bolt-doctor-"));
});

afterEach(() => {
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	rmSync(tempDir, { recursive: true, force: true });
});

describe("the release signature", () => {
	const { publicKey, privateKey } = generateKeyPairSync("ed25519");
	const key = publicKey.export({ type: "spki", format: "pem" }).toString();
	const sums = Buffer.from(
		"# pi-bolt 0.8.8\nabc123  pi-bolt-darwin-arm64.tar.xz\ndef456  pi-bolt-darwin-arm64.tar.gz\n",
		"utf8",
	);
	const archives = ["pi-bolt-darwin-arm64.tar.xz", "pi-bolt-darwin-arm64.tar.gz"];

	it("verifies SHA256SUMS signed with the release key, for this release and build", () => {
		const result = verifyReleaseSums(sums, sign(null, sums, privateKey), "0.8.8", archives, key);
		expect(result).toEqual({ status: "verified", detail: "signature verified, pi-bolt-darwin-arm64.tar.xz listed" });
	});

	it("reads checksum lines of either form", () => {
		const binary = Buffer.from("# pi-bolt 0.8.8\nabc123 *pi-bolt-win32-x64.zip\n", "utf8");
		expect(
			verifyReleaseSums(binary, sign(null, binary, privateKey), "0.8.8", ["pi-bolt-win32-x64.zip"], key).status,
		).toBe("verified");
		// (A name is matched as it is, not as a pattern: "." is no wildcard.)
		expect(
			verifyReleaseSums(binary, sign(null, binary, privateKey), "0.8.8", ["pi-bolt-win32-x64Xzip"], key).status,
		).toBe("bad");
	});

	it("rejects another key's signature, another release's checksums, and checksums without this build", () => {
		const other = generateKeyPairSync("ed25519").privateKey;
		expect(verifyReleaseSums(sums, sign(null, sums, other), "0.8.8", archives, key).status).toBe("bad");
		expect(verifyReleaseSums(sums, Buffer.from("not a signature"), "0.8.8", archives, key).status).toBe("bad");
		expect(verifyReleaseSums(sums, sign(null, sums, privateKey), "0.8.7", archives, key).detail).toBe(
			"the signed SHA256SUMS is not release 0.8.7's",
		);
		expect(
			verifyReleaseSums(sums, sign(null, sums, privateKey), "0.8.8", ["pi-bolt-win32-x64.zip"], key).detail,
		).toBe("the signed SHA256SUMS lists no pi-bolt-win32-x64.zip");
	});

	it("checks with the key releases are signed with", () => {
		const repositoryKey = readFileSync(join(__dirname, "..", "..", "..", "keys", "release.pub"), "utf8");
		expect(PIBOLT_RELEASE_KEY.trim()).toBe(repositoryKey.trim());
	});
});

describe("pi-bolt on PATH", () => {
	it("tells whether the first is this one", () => {
		const entries = [
			{ path: "/usr/local/bin/pi-bolt", real: "/opt/old/pi-bolt-linux-x64/pi" },
			{ path: "/home/me/.local/bin/pi-bolt", real: "/home/me/.pi-bolt/pi-bolt-linux-x64/pi" },
		];
		const analysis = analyzePiBoltOnPath(entries, "/home/me/.pi-bolt/pi-bolt-linux-x64", "installer");
		expect(analysis.first?.path).toBe("/usr/local/bin/pi-bolt");
		expect(analysis.firstIsThis).toBe(false);
		expect(analysis.others).toHaveLength(1);
		expect(
			analyzePiBoltOnPath(entries.slice(1), "/home/me/.pi-bolt/pi-bolt-linux-x64", "installer").firstIsThis,
		).toBe(true);
		expect(analyzePiBoltOnPath([], "/x", "installer").first).toBeUndefined();
	});

	it("takes the npm package's launcher for this one when npm installed it", () => {
		const launcher = [{ path: "/usr/bin/pi-bolt", real: "/usr/lib/node_modules/pi-bolt/bin/pi-bolt.exe" }];
		const folder = "/home/me/.pi-bolt/npm/0.8.8/pi-bolt-linux-x64";
		expect(analyzePiBoltOnPath(launcher, folder, "npm").firstIsThis).toBe(true);
		expect(analyzePiBoltOnPath(launcher, folder, "installer").firstIsThis).toBe(false);
	});
});

describe("package lists", () => {
	it("shows what only Pi or only Pi-Bolt loads", () => {
		const lists = packageListsOf({
			packages: ["npm:a", { source: "npm:b", extensions: [] }],
			piBolt: { packages: ["npm:a", "npm:opensec-pi-todo"] },
		});
		expect(lists).toEqual({ pi: ["npm:a", "npm:b"], piBolt: ["npm:a", "npm:opensec-pi-todo"] });
		expect(packageListRows(lists)).toEqual([
			["Pi (packages)", "npm:a\nnpm:b"],
			["Pi-Bolt (piBolt.packages)", "npm:a\nnpm:opensec-pi-todo"],
			["only Pi loads", "npm:b"],
			["only Pi-Bolt loads", "npm:opensec-pi-todo"],
		]);
	});

	it("says when Pi-Bolt has no list of its own, or both are the same", () => {
		expect(packageListRows(packageListsOf({ packages: ["npm:a"] }))).toEqual([
			["Pi (packages)", "npm:a"],
			["Pi-Bolt", "loads Pi's list (there is no piBolt.packages)"],
		]);
		expect(packageListRows(packageListsOf({ packages: [], piBolt: { packages: [] } }))).toContainEqual([
			"difference",
			"none: both load the same packages",
		]);
		expect(packageListsOf(undefined)).toEqual({ pi: [], piBolt: undefined });
	});
});

describe("extension rows", () => {
	it("lists compiled-in, run-time and failing extensions", () => {
		const rows = extensionRows(
			{ enabled: ["codemode", "mcp"], disabled: ["llama.cpp"] },
			2,
			[
				{ name: "npm:pi-todo", state: "loaded", detail: "loaded in 40 ms (CPU 35 ms)" },
				{
					name: "~/.pi/agent/extensions/hello.ts",
					state: "compiled",
					detail: "by pi-bolt compile (doctor does not load it)",
				},
				{ name: "npm:pi-ffi", state: "failed", detail: "Cannot find module", ffi: "index.ts imports it" },
				{ name: "npm:pi-dlopen", state: "failed", detail: "bun:ffi requires the JIT. pi-dlopen needs ..." },
				// #62: dlopen threw "bun:ffi requires the JIT", the extension caught it and loaded with its other backend.
				{
					name: "npm:pi-fallback",
					state: "loaded",
					detail: "loaded in 30 ms (CPU 25 ms)",
					ffi: "called while loading",
					ffiJitRequired: true,
				},
			],
			false,
		);
		expect(rows).toEqual([
			["compiled in", "builtin:codemode, builtin:mcp, 2 compiled in with --plugins"],
			["turned off", "builtin:llama.cpp"],
			["run time", "npm:pi-todo: interpreted, loaded in 40 ms (CPU 35 ms)"],
			["compiled in", "~/.pi/agent/extensions/hello.ts: by pi-bolt compile (doctor does not load it)"],
			["failed", "npm:pi-ffi: Cannot find module; uses bun:ffi (index.ts imports it)"],
			["failed", "npm:pi-dlopen: bun:ffi requires the JIT. pi-dlopen needs ..."],
			[
				"run time",
				"npm:pi-fallback: interpreted, loaded in 30 ms (CPU 25 ms); uses bun:ffi (called while loading; went on without it)",
			],
		]);
	});
});

// #62
describe("jitFinding", () => {
	const loaded = { name: "npm:pi-x", state: "loaded" as const, detail: "loaded in 30 ms (CPU 25 ms)" };
	const failed = { name: "npm:pi-x", state: "failed" as const, detail: "native backend unavailable" };

	it("says nothing with the JIT, without bun:ffi, or when the extension went on without it", () => {
		expect(jitFinding({ ...loaded, ffi: "index.ts imports it" }, true)).toBeUndefined();
		expect(jitFinding(loaded, false)).toBeUndefined();
		expect(jitFinding({ ...loaded, ffi: "called while loading", ffiJitRequired: true }, false)).toBeUndefined();
	});

	it("suggests the JIT build for one that only imports bun:ffi and loaded", () => {
		const finding = jitFinding({ ...loaded, ffi: "index.ts imports it" }, false);
		expect(finding?.kind).toBe("suggestion");
		expect(finding?.text).toContain("did not call it while loading");
	});

	it("reports one that failed after a bun:ffi call threw the JIT error, though it rethrew another", () => {
		const finding = jitFinding({ ...failed, ffi: "called while loading", ffiJitRequired: true }, false);
		expect(finding?.kind).toBe("problem");
		expect(finding?.text).toContain("needs Pi-Bolt's JIT build");
		expect(finding?.text).toContain("threw");
	});

	it("reports one that imports bun:ffi and failed", () => {
		expect(jitFinding({ ...failed, ffi: "index.ts imports it" }, false)?.kind).toBe("problem");
	});
});

describe("findBunFfiImport", () => {
	it("finds a file that imports bun:ffi, but not in the packages Pi-Bolt keeps from extensions", () => {
		const root = join(tempDir, "pkg");
		mkdirSync(join(root, "node_modules", "@ff-labs", "fff-bun"), { recursive: true });
		mkdirSync(join(root, "node_modules", "native"), { recursive: true });
		writeFileSync(join(root, "index.ts"), 'import x from "./lib.js";\n// mentions bun:ffi in a comment\n');
		writeFileSync(
			join(root, "node_modules", "@ff-labs", "fff-bun", "index.js"),
			'import { dlopen } from "bun:ffi";\n',
		);
		expect(findBunFfiImport(root)).toBeUndefined();
		writeFileSync(join(root, "node_modules", "native", "index.js"), 'const { dlopen } = require("bun:ffi");\n');
		expect(findBunFfiImport(root)).toBe(join(root, "node_modules", "native", "index.js"));
		expect(findBunFfiImport(join(root, "index.ts"))).toBeUndefined();
	});
});

describe("editor settings", () => {
	it("reads Zed's settings, comments and trailing commas included", () => {
		const text = `{
	// Agents
	"agent_servers": {
		"Pi-Bolt": { "type": "custom", "command": "/opt/pi-bolt", "args": ["--acp"], }, /* ours */
		"Other": { "command": "claude-code-acp" },
		"Pi": { "command": "C:\\\\tools\\\\pi.exe", "args": ["--acp"] },
	},
	"url": "https://example.com/a//b",
}`;
		const servers = piBoltAgentServers(JSON.parse(stripJsonComments(text)));
		expect(servers).toEqual([
			{ name: "Pi-Bolt", command: "/opt/pi-bolt", args: ["--acp"] },
			{ name: "Pi", command: "C:\\tools\\pi.exe", args: ["--acp"] },
		]);
		expect(JSON.parse(stripJsonComments(text)).url).toBe("https://example.com/a//b");
	});
});

describe("the report", () => {
	it("aligns rows, and lists problems and suggestions", () => {
		const report: DoctorReport = {
			sections: [
				{
					title: "Pi-Bolt",
					rows: [
						["build", "darwin-arm64, JIT off"],
						["on PATH", "/a\n/b"],
					],
				},
			],
			problems: [],
			suggestions: ["Pi-Bolt 0.9.0 is out: `pi-bolt update`."],
		};
		expect(formatDoctorReport(report)).toBe(
			[
				"Pi-Bolt doctor",
				"",
				"Pi-Bolt",
				"  build           darwin-arm64, JIT off",
				"  on PATH         /a",
				"                  /b",
				"",
				"Problems",
				"  none found",
				"",
				"Suggestions",
				"  - Pi-Bolt 0.9.0 is out: `pi-bolt update`.",
				"",
			].join("\n"),
		);
	});

	it("keeps a Windows home folder out", () => {
		const text = "C:\\Users\\Alice\\.pi\\agent\nproject C:\\Users\\Alice: nothing\nD:\\C:\\Users\\Alice\\x";
		expect(redactReport(text, ["C:\\Users\\Alice"])).toBe(
			"~\\.pi\\agent\nproject ~: nothing\nD:\\C:\\Users\\Alice\\x",
		);
	});

	it("keeps the home folder, credentials and keys out", () => {
		const text = [
			"/home/me/.pi-bolt/pi-bolt-linux-x64/pi",
			"/data/home/me/x -> /home/me: the project",
			"git:https://me:ghp_abcdefghij1234567890@github.com/me/ext",
			"https://example.com/pkg.tgz?token=abc123456789",
			"failed: invalid key sk-ant-api03-abcdefghijklmnop, Authorization: Bearer abcdefghijkl",
			"api_key=supersecretvalue",
			"runtime stamp 0f61517d28995399",
		].join("\n");
		const redacted = redactReport(text, ["/home/me"]);
		expect(redacted).toBe(
			[
				"~/.pi-bolt/pi-bolt-linux-x64/pi",
				"/data/home/me/x -> ~: the project",
				"git:https://***@github.com/me/ext",
				"https://example.com/pkg.tgz?***",
				"failed: invalid key ***, Authorization: Bearer ***",
				"api_key=***",
				"runtime stamp 0f61517d28995399",
			].join("\n"),
		);
	});
});

describe("runDoctor", () => {
	it("reports a setup without anything private in it", async () => {
		const home = join(tempDir, "home");
		const agentDir = join(home, ".pi", "agent");
		const project = join(home, "project");
		const bin = join(home, "bin");
		for (const dir of [
			project,
			bin,
			join(agentDir, "extensions", "ffi-ext"),
			join(agentDir, "extensions", "ffi-lazy"),
			join(home, ".config", "zed"),
		]) {
			mkdirSync(dir, { recursive: true });
		}
		writeFileSync(join(agentDir, "extensions", "fine.ts"), "export default function () {}\n");
		writeFileSync(
			join(agentDir, "extensions", "broken.ts"),
			'export default function () { throw new Error("boom"); }\n',
		);
		writeFileSync(
			join(agentDir, "extensions", "ffi-ext", "index.ts"),
			'import { dlopen } from "bun:ffi";\nexport default function () { return dlopen; }\n',
		);
		// Imports bun:ffi only where it is used, not while it loads.
		writeFileSync(
			join(agentDir, "extensions", "ffi-lazy", "index.ts"),
			'export default function () { return () => require("bun:ffi"); }\n',
		);
		writeFileSync(
			join(agentDir, "settings.json"),
			JSON.stringify({
				packages: ["git:https://me:ghp_abcdefghij1234567890@github.com/me/private-ext"],
				piBolt: { packages: [] },
			}),
		);
		writeFileSync(
			join(home, ".config", "zed", "settings.json"),
			'{ "agent_servers": { "Pi-Bolt": { "command": "/no/such/pi-bolt", "args": ["--acp"] } } }',
		);
		writeFileSync(join(bin, "pi-bolt"), "#!/bin/sh\necho 1.0.0 (Pi-Bolt 0.1.0, linux-x64, JIT off)\n");
		chmodSync(join(bin, "pi-bolt"), 0o755);
		vi.stubEnv("HOME", home);
		vi.stubEnv("USERPROFILE", home);
		vi.stubEnv("XDG_CONFIG_HOME", "");
		vi.stubEnv("PATH", bin);
		vi.stubEnv("PIBOLT_VARIANT", "arm64");
		let output = "";
		vi.spyOn(process.stdout, "write").mockImplementation((chunk) => {
			output += String(chunk);
			return true;
		});

		const status = await runDoctor(["--offline"], { cwd: project, agentDir, extensionFactories: [] });

		expect(status).toBe(0);
		expect(output).toMatch(/^Pi-Bolt doctor\n/);
		expect(output).toMatch(/ {2}Pi-Bolt +0\.8\.8 \(Pi /);
		expect(output).toContain("JIT off");
		expect(output).toContain("runtime stamp 0f61517d28995399");
		expect(output).toContain("not checked (offline)");
		expect(output).toContain("PIBOLT_VARIANT=arm64");
		expect(output).toMatch(/run time +~\/\.pi\/agent\/extensions\/fine\.ts: interpreted, loaded in \d+ ms/);
		expect(output).toMatch(/failed +~\/\.pi\/agent\/extensions\/broken\.ts: boom/);
		expect(output).toMatch(/ffi-ext\/index\.ts: .*uses bun:ffi \(ffi-ext\/index\.ts imports it\)/);
		expect(output).toContain("needs Pi-Bolt's JIT build (bun:ffi); switch with: ");
		// #62: one that fails is a problem; one that only imports bun:ffi and loads is a suggestion.
		const problems = output.slice(output.indexOf("\nProblems\n"), output.indexOf("\nSuggestions\n"));
		const suggestions = output.slice(output.indexOf("\nSuggestions\n"));
		expect(problems).toContain("broken.ts");
		expect(problems).toContain("ffi-ext/index.ts needs Pi-Bolt's JIT build");
		expect(problems).not.toContain("ffi-lazy");
		expect(suggestions).toMatch(/ffi-lazy\/index\.ts needs Pi-Bolt's JIT build .*did not call it while loading/);
		expect(output).toContain("only Pi loads");
		expect(output).toContain("git:https://***@github.com/me/private-ext");
		expect(output).toContain('Zed\'s agent server "Pi-Bolt" runs /no/such/pi-bolt, which does not exist');
		expect(output).toContain("~/bin/pi-bolt (not this Pi-Bolt)");
		expect(output).not.toContain(home);
		expect(output).not.toContain("ghp_");
	});

	it("refuses unknown arguments", async () => {
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await runDoctor(["--fix"], { cwd: tempDir, agentDir: tempDir, extensionFactories: [] })).toBe(1);
		expect(error).toHaveBeenCalledWith(expect.stringContaining("Unknown argument for doctor: --fix"));
	});
});
