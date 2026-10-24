import { generateKeyPairSync, sign } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
	type CompiledExtension,
	compiledExtensionFor,
	extensionFingerprint,
	setCompiledExtensions,
} from "../src/core/extensions/compiled.ts";
import type { ExtensionFactory } from "../src/core/extensions/types.ts";
import {
	buildFailureReason,
	candidateLines,
	checkReleaseSums,
	recordedExclusions,
	resolveCompilePackages,
} from "../src/pi-bolt-compile.ts";

// (Pi-Bolt applies piBolt.packages to `packages` only in a Pi-Bolt build.)
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<object>()),
	PIBOLT: { version: "1.0.0", variant: "arm64", jit: true },
}));

let dir: string;

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-compiled-"));
});

afterEach(() => {
	setCompiledExtensions([]);
	rmSync(dir, { recursive: true, force: true });
});

const factory: ExtensionFactory = () => {};

function compiledIn(source: Omit<CompiledExtension, "fingerprint" | "factory" | "name">): CompiledExtension {
	const fingerprint = extensionFingerprint(source);
	if (!fingerprint) throw new Error(`no fingerprint for ${source.entry}`);
	return { name: "test", factory, fingerprint, ...source };
}

/** Sets a file's modification time `seconds` after the epoch, so that a change is seen whatever the file system's clock. */
function touch(path: string, seconds: number): void {
	utimesSync(path, seconds, seconds);
}

describe("an extension compiled in stands in for its installed copy", () => {
	test("while the file is unchanged, its compiled factory is used", () => {
		const entry = join(dir, "word-count.ts");
		writeFileSync(entry, "export default () => {};\n");
		setCompiledExtensions([compiledIn({ entry, scope: "file", root: dir })]);
		expect(compiledExtensionFor(entry)).toEqual({ factory });
	});

	test("a changed file is loaded from its files again", () => {
		const entry = join(dir, "word-count.ts");
		writeFileSync(entry, "export default () => {};\n");
		touch(entry, 1_000_000);
		const extension = compiledIn({ entry, scope: "file", root: dir });
		setCompiledExtensions([extension]);
		touch(entry, 2_000_000);
		expect(compiledExtensionFor(entry)).toEqual({ changed: extension });
	});

	test("a folder extension changes with any of its own files, and not with its node_modules", () => {
		const root = join(dir, "folder-ext");
		mkdirSync(join(root, "node_modules", "dep"), { recursive: true });
		const entry = join(root, "index.ts");
		writeFileSync(entry, 'import { greeting } from "./helper.ts";\n');
		writeFileSync(join(root, "helper.ts"), "export const greeting = 1;\n");
		writeFileSync(join(root, "node_modules", "dep", "index.js"), "module.exports = 1;\n");
		for (const file of [entry, join(root, "helper.ts")]) touch(file, 1_000_000);
		const extension = compiledIn({ entry, scope: "folder", root });
		setCompiledExtensions([extension]);
		writeFileSync(join(root, "node_modules", "dep", "index.js"), "module.exports = 2;\n");
		expect(compiledExtensionFor(entry)).toEqual({ factory });
		writeFileSync(join(root, "helper.ts"), "export const greeting = 2;\n");
		touch(join(root, "helper.ts"), 2_000_000);
		expect(compiledExtensionFor(entry)).toEqual({ changed: extension });
	});

	test("a package changes with its version", () => {
		const root = join(dir, "pi-lens");
		mkdirSync(root);
		const entry = join(root, "index.js");
		writeFileSync(entry, "export default () => {};\n");
		touch(entry, 1_000_000);
		writeFileSync(join(root, "package.json"), JSON.stringify({ version: "1.0.0" }));
		const extension = compiledIn({ entry, scope: "package", root });
		setCompiledExtensions([extension]);
		expect(compiledExtensionFor(entry)).toEqual({ factory });
		writeFileSync(join(root, "package.json"), JSON.stringify({ version: "1.0.1" }));
		expect(compiledExtensionFor(entry)).toEqual({ changed: extension });
	});

	test("one removed since has no fingerprint, and an extension not compiled in is loaded as always", () => {
		const entry = join(dir, "gone.ts");
		writeFileSync(entry, "export default () => {};\n");
		const extension = compiledIn({ entry, scope: "file", root: dir });
		setCompiledExtensions([extension]);
		rmSync(entry);
		expect(extensionFingerprint(extension)).toBeUndefined();
		expect(compiledExtensionFor(join(dir, "other.ts"))).toBeUndefined();
	});

	test("with nothing compiled in, nothing is looked up", () => {
		expect(compiledExtensionFor(join(dir, "anything.ts"))).toBeUndefined();
	});
});

describe("pi-bolt compile takes a release's files only with its signature", () => {
	const { publicKey, privateKey } = generateKeyPairSync("ed25519");
	const key = publicKey.export({ type: "spki", format: "pem" }).toString();
	const kitSha = "a".repeat(64);
	const sums = Buffer.from(`# pi-bolt 0.9.0\n${kitSha}  pi-bolt-kit-linux-x64.tar.gz\n`);

	test("signed checksums of the version asked for give each file's SHA-256", () => {
		const sha = checkReleaseSums(sums, sign(null, sums, privateKey), "0.9.0", key);
		expect(sha.get("pi-bolt-kit-linux-x64.tar.gz")).toBe(kitSha);
	});

	test("a signature by another key is refused", () => {
		const other = generateKeyPairSync("ed25519").privateKey;
		expect(() => checkReleaseSums(sums, sign(null, sums, other), "0.9.0", key)).toThrow("does not verify");
	});

	test("checksums changed after signing are refused", () => {
		const signature = sign(null, sums, privateKey);
		const changed = Buffer.from(sums.toString().replace(kitSha, "b".repeat(64)));
		expect(() => checkReleaseSums(changed, signature, "0.9.0", key)).toThrow("does not verify");
	});

	test("another release's checksums, signed all the same, are refused", () => {
		expect(() => checkReleaseSums(sums, sign(null, sums, privateKey), "0.9.1", key)).toThrow("not Pi-Bolt 0.9.1's");
	});

	test("the release key is the default", () => {
		expect(() => checkReleaseSums(sums, sign(null, sums, privateKey), "0.9.0")).toThrow("does not verify");
	});
});

describe("pi-bolt compile takes the packages Pi-Bolt loads", () => {
	/** A package folder: its package.json and the extension files it names. */
	function packageAt(path: string, name: string, version: string, files: string[]): string {
		mkdirSync(path, { recursive: true });
		writeFileSync(join(path, "package.json"), JSON.stringify({ name, version, pi: { extensions: files } }));
		for (const file of files) {
			mkdirSync(join(path, file, ".."), { recursive: true });
			writeFileSync(join(path, file), "export default () => {};\n");
		}
		return path;
	}

	test("piBolt.packages adds and removes packages, npm and local, as at run time (#108)", async () => {
		const agentDir = join(dir, "agent");
		const npm = (name: string) => join(agentDir, "npm", "node_modules", name);
		packageAt(npm("pi-codex-goal"), "pi-codex-goal", "1.0.0", ["index.ts"]);
		packageAt(npm("pi-web-access"), "pi-web-access", "0.41.0", ["dist/index.js"]);
		packageAt(npm("opensec-pi-fff"), "opensec-pi-fff", "0.11.0", ["src/index.ts"]);
		packageAt(join(agentDir, "plugins", "pi-todo"), "pi-todo", "1.0.0", ["index.ts"]);
		const bundle = packageAt(join(agentDir, "pi-bolt-bundles", "pi-bolt-web-access"), "pi-bolt-web-access", "1.0.0", [
			"extensions/a.ts",
			"extensions/b.ts",
		]);
		mkdirSync(join(agentDir, "pi-bolt-bundles", "skills-only", "skills"), { recursive: true });
		writeFileSync(
			join(agentDir, "settings.json"),
			JSON.stringify({
				packages: ["npm:pi-codex-goal", "npm:pi-web-access", "plugins/pi-todo"],
				piBolt: {
					packagesOverlay: true,
					packages: [
						"npm:opensec-pi-fff@0.11.0",
						"-plugins/pi-todo",
						"pi-bolt-bundles/pi-bolt-web-access",
						"-npm:pi-web-access",
						"pi-bolt-bundles/skills-only",
						"pi-bolt-bundles/not-there",
					],
				},
			}),
		);

		const resolved = await resolveCompilePackages(agentDir, dir);
		expect(resolved.packages).toEqual([
			{
				source: "npm:pi-codex-goal",
				kind: "npm",
				root: npm("pi-codex-goal"),
				entries: [join(npm("pi-codex-goal"), "index.ts")],
			},
			{
				source: "npm:opensec-pi-fff@0.11.0",
				kind: "npm",
				root: npm("opensec-pi-fff"),
				entries: [join(npm("opensec-pi-fff"), "src", "index.ts")],
			},
			{
				source: "pi-bolt-bundles/pi-bolt-web-access",
				kind: "folder",
				root: bundle,
				entries: [join(bundle, "extensions", "a.ts"), join(bundle, "extensions", "b.ts")],
			},
		]);
		expect(resolved.removed).toEqual(["npm:pi-web-access", "plugins/pi-todo"]);
		expect(resolved.passedOver).toEqual([
			{ path: "pi-bolt-bundles/skills-only", reason: "no extensions in it" },
			{ path: "pi-bolt-bundles/not-there", reason: "not found" },
		]);
	});

	test("without piBolt.packages, Pi's list is the one", async () => {
		const agentDir = join(dir, "agent");
		packageAt(join(agentDir, "npm", "node_modules", "pi-codex-goal"), "pi-codex-goal", "1.0.0", ["index.ts"]);
		writeFileSync(join(agentDir, "settings.json"), JSON.stringify({ packages: ["npm:pi-codex-goal"] }));

		const resolved = await resolveCompilePackages(agentDir, dir);
		expect(resolved.packages.map((pkg) => pkg.source)).toEqual(["npm:pi-codex-goal"]);
		expect(resolved.removed).toEqual([]);
	});
});

describe("pi-bolt compile leaves out what compile.json records as excluded", () => {
	const record = { exclude: ["pi-web-access"] };

	test("a compile that names no extensions takes the recorded exclusions, as an update does (#108)", () => {
		expect(recordedExclusions({}, record)).toEqual(["pi-web-access"]);
		expect(recordedExclusions({ noPackages: true }, record)).toEqual(["pi-web-access"]);
		expect(recordedExclusions({ afterUpdate: true }, record)).toEqual(["pi-web-access"]);
	});

	test("--exclude, --only and --no-exclude choose instead", () => {
		expect(recordedExclusions({ exclude: ["other"] }, record)).toBeUndefined();
		expect(recordedExclusions({ only: ["pi-web-access"] }, record)).toBeUndefined();
		expect(recordedExclusions({ noExclude: true }, record)).toBeUndefined();
	});

	test("with nothing recorded, nothing is left out", () => {
		expect(recordedExclusions({}, undefined)).toBeUndefined();
		expect(recordedExclusions({}, { only: ["a"] })).toBeUndefined();
	});
});

describe("pi-bolt compile says what it does with each extension and package", () => {
	const plugin = (name: string, origin: string) => ({ name, entry: `/x/${name}.ts`, origin, stageDir: origin });
	const plan = {
		plugins: [plugin("pi-codex-goal", "package"), plugin("word-count", "global")],
		excluded: [
			{ name: "pi-web-access", origin: "package", entry: "/x/w.js", by: "exclude" as const },
			{ name: "notes", origin: "global", entry: "/x/n.ts", by: "only" as const },
		],
		skipped: [{ path: "/agent/extensions/shared", reason: "empty folder" }],
	};
	const packages = {
		passedOver: [{ path: "pi-bolt-bundles/skills-only", reason: "no extensions in it" }],
		removed: ["npm:pi-web-access"],
	};

	test("one line each: compiled, excluded and by what, or passed over and why (#108)", () => {
		expect(candidateLines(plan, packages, true)).toEqual([
			"pi-codex-goal (package): compiling",
			"word-count (global): compiling",
			"pi-web-access (package): excluded (compile.json)",
			"notes (global): not in --only",
			"pi-bolt-bundles/skills-only: passed over: no extensions in it",
			"/agent/extensions/shared: passed over: empty folder",
			"npm:pi-web-access: excluded by piBolt.packages",
		]);
	});

	test("an exclusion given on the command line says so", () => {
		expect(candidateLines(plan, packages)).toContain("pi-web-access (package): excluded (--exclude)");
	});
});

describe("pi-bolt compile says why a build stopped", () => {
	// What the compiler printed for pi-web-access 0.41.0's bundled dist/ (#108), with progress lines around it.
	const tooBig =
		"AOT: a function of 413499 bytes of bytecode (the top-level code of a module) is bigger than " +
		"maximumAOTCandidateBytecodeSize (262144 bytes), and compiling one that big takes minutes and tens of GB. Split it up " +
		"(it is likely one big literal), or build with BUN_JSC_maximumAOTCandidateBytecodeSize=413499 to compile it anyway, " +
		"or with BUN_JSC_omitBytecodeFromStaticHeap=0 to run it from bytecode.";
	const log = [
		"[0.12ms] bundle 2104 modules",
		"AOT: compiling 48211 functions",
		"AOT: 0 methods are closed",
		tooBig,
		"",
	].join("\n");

	test("the AOT line that says why is shown, with the ways round it (#108)", () => {
		const reason = buildFailureReason(log);
		expect(reason.split("\n")).toEqual([
			"[0.12ms] bundle 2104 modules",
			tooBig,
			expect.stringContaining("pi-bolt compile --exclude <name>"),
		]);
		expect(reason).toContain("BUN_JSC_maximumAOTCandidateBytecodeSize=<n> pi-bolt compile");
		expect(reason).not.toContain("methods are closed");
	});

	test("a declined function is shown with the compiler's own summary", () => {
		const declined =
			"AOT: the function `parse` (module 3, at 120 of its source) cannot be compiled ahead of time: no reason given";
		const summary =
			"AOT: 1 function could not be compiled ahead of time, and in this executable ... the build stops.";
		expect(buildFailureReason(`AOT: 0 methods are closed\n${declined}\n${summary}\n`)).toBe(
			`${declined}\n${summary}`,
		);
	});

	test("an error of the bundler is shown as before", () => {
		const output = `AOT: 0 methods are closed\nerror: Could not resolve: "./missing"\n    at plugins.ts:3:20\n`;
		expect(buildFailureReason(output)).toBe('error: Could not resolve: "./missing"\n    at plugins.ts:3:20');
	});

	test("with only progress printed, the last of it; with nothing, says so", () => {
		expect(buildFailureReason("AOT: compiling 2 functions\nAOT: 0 methods are closed\n")).toBe(
			"AOT: compiling 2 functions\nAOT: 0 methods are closed",
		);
		expect(buildFailureReason("\n")).toBe("(the compiler printed nothing)");
	});
});
