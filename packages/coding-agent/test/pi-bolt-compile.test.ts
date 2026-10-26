import { spawnSync } from "node:child_process";
import { generateKeyPairSync, sign } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from "node:fs";
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
	bundlePieces,
	bytecodeSizeLimit,
	candidateLines,
	checkReleaseSums,
	extensionsOfModule,
	functionTooBig,
	importAtRunTime,
	jsStringLiteral,
	leaveUnresolvedImportsToRunTime,
	measuredSizes,
	measurePieces,
	originalOfCopy,
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
	const plugin = (name: string, origin: string) => ({
		name,
		entry: `/x/${name}.ts`,
		origin,
		stageDir: origin,
		importPath: `./${origin}/${name}.ts`,
	});
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

// Issue #129: a function too big to compile is one function of an extension, or the top-level code of a module: of one
// extension's file (#142), or of the whole program in a build that does not split its code. The message says which.
describe("pi-bolt compile says which extension a function too big to compile is of (#129)", () => {
	test("the compiler's line gives the size, and whether it is the top-level code", () => {
		expect(
			functionTooBig(
				"AOT: a function of 355112 bytes of bytecode (a function) is bigger than maximumAOTCandidateBytecodeSize (262144 bytes), ...",
			),
		).toEqual({ size: 355112, topLevel: false });
		expect(
			functionTooBig(
				"x\nAOT: a function of 437358 bytes of bytecode (the top-level code of a module) is bigger than maximumAOTCandidateBytecodeSize (262144 bytes)",
			),
		).toEqual({ size: 437358, topLevel: true });
		expect(functionTooBig("AOT: 1 function could not be compiled ahead of time")).toBeUndefined();
	});

	// A bundle as Bun prints one (--format=esm, not minified): Pi's modules, then the extensions' copies.
	const bundle = [
		"var __esm = (fn, res) => () => (fn && (res = fn(fn = 0)), res);",
		"// ../ai/src/index.ts",
		"var init_src = __esm(() => {",
		"  piOwn = 1;",
		"});",
		"// .pibolt-plugins/src/global/big/helper.ts",
		"var helperValue;",
		"var init_helper = __esm(() => {",
		"  helperValue = 1;",
		"});",
		"// .pibolt-plugins/src/global/big/index.ts",
		'import { writeFileSync as writeFileSync2 } from "fs";',
		"function big_default(pi) {",
		"  return table.length;",
		"}",
		"var table;",
		"var init_big = __esm(async () => {",
		"  init_helper();",
		"  table = [1, 2, 3];",
		"});",
		"// .pibolt-plugins/src/node_modules/left-pad/index.js",
		"var require_left_pad = __commonJS((exports, module) => {",
		"  module.exports = (s) => s;",
		"});",
		"// .pibolt-plugins/src/plugins.ts",
		"var plugins_default = [];",
		"class Registry {",
		"  add() {}",
		"}",
		"",
	].join("\n");

	test("a bundle's extension code in pieces: each wrapped module, each function, and what each adds to the top level", () => {
		const { pieces, names } = bundlePieces(bundle, ".pibolt-plugins/src/");
		const of = (kind: string) => pieces.filter((piece) => piece.kind === kind);
		expect(of("esm").map((piece) => [piece.module, piece.async, piece.code])).toEqual([
			[".pibolt-plugins/src/global/big/helper.ts", false, "  helperValue = 1;"],
			[".pibolt-plugins/src/global/big/index.ts", true, "  init_helper();\n  table = [1, 2, 3];"],
		]);
		expect(of("commonjs").map((piece) => piece.module)).toEqual([
			".pibolt-plugins/src/node_modules/left-pad/index.js",
		]);
		expect(of("function").map((piece) => [piece.name, piece.code])).toEqual([
			["big_default", "  return table.length;"],
		]);
		// (Pi's own modules are not pieces; a wrapper adds its creation, not its body, and declarations and imports add nothing.)
		expect(of("top-level").map((piece) => [piece.module, piece.code])).toEqual([
			[".pibolt-plugins/src/global/big/helper.ts", "init_helper = __esm(() => {\n});"],
			[".pibolt-plugins/src/global/big/index.ts", "init_big = __esm(async () => {\n});"],
			[
				".pibolt-plugins/src/node_modules/left-pad/index.js",
				"require_left_pad = __commonJS((exports, module) => {\n});",
			],
			[".pibolt-plugins/src/plugins.ts", "plugins_default = [];\nclass Registry {\n  add() {}\n}\n"],
		]);
		for (const name of ["__esm", "init_src", "helperValue", "big_default", "table", "writeFileSync2", "Registry"]) {
			expect(names).toContain(name);
		}
	});

	// #142: the manifest loads each extension with import(), and the build splits its code, so each extension's modules are a
	// file of their own, not wrapped: each module's statements are its share of that file's top-level code.
	test("a split build's file of the extensions: its imports and exports are nobody's code, and their names are variables", () => {
		const chunk = [
			"// @bun",
			"import {",
			"  visibleWidth,",
			"  truncateToWidth as truncate",
			'} from "./entry-6nbwg67y.js";',
			'import"./entry-fj682nbm.js";',
			"",
			"// .pibolt-plugins/src/global/aaa-first.ts",
			"(globalThis.__tlaOrder ??= []).push(visibleWidth);",
			"function aaa_first_default() {",
			"  return 1;",
			"}",
			"",
			"// .pibolt-plugins/src/global/tla-probe/index.ts",
			'var { greeting } = await import("./greeting-yv0h5cmh.js");',
			"order.push(truncate);",
			"",
			"// .pibolt-plugins/src/plugins.ts",
			"var plugins = [aaa_first_default];",
			"var plugins_default = plugins;",
			"export {",
			"  plugins_default as default",
			"};",
			"",
		].join("\n");
		const { pieces, names } = bundlePieces(chunk, ".pibolt-plugins/src/");
		expect(pieces.filter((piece) => piece.kind === "top-level").map((piece) => [piece.module, piece.code])).toEqual([
			[".pibolt-plugins/src/global/aaa-first.ts", "(globalThis.__tlaOrder ??= []).push(visibleWidth);\n"],
			[
				".pibolt-plugins/src/global/tla-probe/index.ts",
				'var { greeting } = await import("./greeting-yv0h5cmh.js");\norder.push(truncate);\n',
			],
			[".pibolt-plugins/src/plugins.ts", "plugins = [aaa_first_default];\nplugins_default = plugins;\n"],
		]);
		for (const name of ["visibleWidth", "truncate", "aaa_first_default", "plugins_default"])
			expect(names).toContain(name);
	});

	// #142: an extension may wait at its top level. Its top-level code is measured in an async function: in a plain one the
	// \`await\` does not parse, and the extension that passed the limit came out with nothing (PR #152 review).
	test("the top-level code of a module that waits at its top level is measured, large literal and all", (context) => {
		if (spawnSync("bun", ["--version"]).status !== 0) return context.skip();
		const chunk = [
			"// .pibolt-plugins/src/global/big/index.ts",
			'var { greeting } = await import("./greeting-1.js");',
			// (Not constants, which are one instruction: what each element is worked out from.)
			`var table = [${Array.from({ length: 3000 }, (_, i) => `${i} * greeting.length`).join(", ")}];`,
			"// .pibolt-plugins/src/global/small.ts",
			"var small = [1, 2, 3];",
			"",
		].join("\n");
		const { pieces, names } = bundlePieces(chunk, ".pibolt-plugins/src/");
		expect(pieces.map((piece) => [piece.module, piece.kind, piece.async])).toEqual([
			[".pibolt-plugins/src/global/big/index.ts", "top-level", false],
			[".pibolt-plugins/src/global/small.ts", "top-level", false],
		]);
		const work = mkdtempSync(join(tmpdir(), "pibolt-measure-"));
		try {
			const sizes = measurePieces("bun", pieces, names, work);
			expect(sizes.get(0)).toBeGreaterThan(10000);
			expect(sizes.get(1)).toBeGreaterThan(0);
			expect(sizes.get(0)!).toBeGreaterThan(sizes.get(1)! * 50);
		} finally {
			rmSync(work, { recursive: true, force: true });
		}
	});

	test("the sizes the engine prints, less what the empty function takes", () => {
		const printed = [
			"__pibolt_base#AbCdEf:[0x1->0x2, NoneFunctionCall, 52 (StrictMode)]: 9 instructions",
			"[   0] enter",
			"__pibolt_0#Gh1jKl:[0x3->0x4, NoneFunctionCall, 436256 (StrictMode)]: 36020 instructions",
			"__pibolt_1#MnOpQr:[0x5->0x6, NoneFunctionCall, 9984 (StrictMode)]: 900 instructions",
		].join("\n");
		expect([...measuredSizes(printed)]).toEqual([
			[0, 436204],
			[1, 9932],
		]);
	});

	test("a module is the extension's whose copy it is in; a dependency is nobody's own", () => {
		const plan = {
			plugins: [
				{ name: "big", origin: "global", stageDir: "global", importPath: "./global/big/index.ts" },
				{ name: "ban-sleep", origin: "global", stageDir: "global", importPath: "./global/ban-sleep.ts" },
				{ name: "web", origin: "package", stageDir: "package-web", importPath: "./package-web/dist/index.js" },
				{ name: "tools-a", origin: "configured", stageDir: "path-tools", importPath: "./path-tools/a/index.ts" },
			].map((plugin) => ({ ...plugin, entry: "" })),
		};
		const owner = (module: string) => extensionsOfModule(module, plan);
		expect(owner(".pibolt-plugins/src/global/big/lib/table.ts")).toEqual(["big"]);
		expect(owner(".pibolt-plugins/src/global/ban-sleep.ts")).toEqual(["ban-sleep"]);
		expect(owner(".pibolt-plugins/src/global/ban-sleep.ts.bak")).toEqual([]);
		expect(owner(".pibolt-plugins/src/package-web/dist/chunk-1.js")).toEqual(["web"]);
		expect(owner(".pibolt-plugins/src/path-tools/a/util.ts")).toEqual(["tools-a"]);
		expect(owner(".pibolt-plugins/src/node_modules/left-pad/index.js")).toEqual([]);
		expect(owner("../ai/src/index.ts")).toEqual([]);
	});

	test("what is found out is said in place of the general advice", () => {
		const log =
			"AOT: a function of 355112 bytes of bytecode (a function) is bigger than maximumAOTCandidateBytecodeSize (262144 bytes).\n";
		expect(buildFailureReason(log, ["That function is big's.", "Leave it out."]).split("\n").slice(1)).toEqual([
			"That function is big's.",
			"Leave it out.",
		]);
		expect(buildFailureReason(log)).toContain("compile.json keeps for `pi-bolt update`");
	});
});

// Issue #129: a raised limit was lost at the next `pi-bolt update`, which then installed the release without the extensions.
describe("pi-bolt compile keeps a raised limit on the size of a function (#129)", () => {
	test("the environment's limit, else the one compile.json records, else the compiler's own", () => {
		const variable = "BUN_JSC_maximumAOTCandidateBytecodeSize";
		expect(bytecodeSizeLimit({ [variable]: "400000" }, { maximumBytecodeSize: 300000 })).toBe(400000);
		expect(bytecodeSizeLimit({}, { maximumBytecodeSize: 300000 })).toBe(300000);
		expect(bytecodeSizeLimit({ [variable]: "" }, undefined)).toBeUndefined();
		expect(() => bytecodeSizeLimit({ [variable]: "lots" }, undefined)).toThrow(/not a number of bytes/);
		// (A compile.json edited by hand into something else is not a limit.)
		expect(bytecodeSizeLimit({}, { maximumBytecodeSize: "big" as unknown as number })).toBeUndefined();
	});
});

// Issue #160: @plannotator/pi-extension loads the Claude Agent SDK and the OpenCode SDK with import(), declares neither, and
// works without them when loaded from its files. The bundler stopped the whole build at those import()s.
describe("pi-bolt compile leaves an import() it cannot resolve to run time (#160)", () => {
	test("strings put into the generated code are escaped", () => {
		expect(jsStringLiteral("a/b</script>\u2028")).toBe('"a\\u002fb\\u003c\\u002fscript\\u003e\\u2028"');
		expect(new Function(`return ${jsStringLiteral('a/b</script>\u2028"\\')}`)()).toBe('a/b</script>\u2028"\\');
	});

	const resolvedFrom = (specifier: string, from: string) =>
		`(() => { try { return Bun.resolveSync(${jsStringLiteral(specifier)}, ${jsStringLiteral(from)}); } catch { return ${jsStringLiteral(specifier)}; } })()`;

	test("the import()'s string is resolved when it runs, from the installed file's folder", () => {
		const source = 'let sdk;\nasync function getSDK() {\n\tsdk = await import("@opencode-ai/sdk");\n}\n';
		const from = "/agent/npm/node_modules/@plannotator/pi-extension/generated/ai/providers";
		expect(importAtRunTime(source, { specifier: "@opencode-ai/sdk", line: 3, column: 21 }, from)).toBe(
			`let sdk;\nasync function getSDK() {\n\tsdk = await import(${resolvedFrom("@opencode-ai/sdk", from)});\n}\n`,
		);
	});

	test("the literal at the bundler's column when the line has the string twice; nothing when the line has none", () => {
		const line = "const a = 'sdk', b = import('sdk');";
		expect(importAtRunTime(line, { specifier: "sdk", line: 1, column: 29 }, "/x")).toBe(
			`const a = 'sdk', b = import(${resolvedFrom("sdk", "/x")});`,
		);
		expect(importAtRunTime("import(name);", { specifier: "sdk", line: 1, column: 8 }, "/x")).toBeUndefined();
		expect(importAtRunTime("x", { specifier: "sdk", line: 4, column: 1 }, "/x")).toBeUndefined();
	});

	test("a copy's module is the file of the extension or dependency it was copied from", () => {
		const plan = {
			roots: [
				{
					stageDir: "package-plannotator-pi-extension",
					root: "/agent/npm/node_modules/@plannotator/pi-extension",
					origin: "package",
				},
			],
			dependencies: [
				{ name: "@pierre/diffs", from: "/agent/npm/node_modules/@pierre/diffs" },
				{ name: "diff", from: "/agent/npm/node_modules/diff" },
			],
		};
		const of = (file: string) => originalOfCopy(join("/stage/src", file), "/stage/src", plan);
		expect(of("package-plannotator-pi-extension/generated/ai/index.ts")).toBe(
			"/agent/npm/node_modules/@plannotator/pi-extension/generated/ai/index.ts",
		);
		expect(of("node_modules/@pierre/diffs/dist/index.js")).toBe(
			"/agent/npm/node_modules/@pierre/diffs/dist/index.js",
		);
		expect(of("node_modules/diff/lib/index.mjs")).toBe("/agent/npm/node_modules/diff/lib/index.mjs");
		expect(of("plugins.ts")).toBeUndefined();
		expect(originalOfCopy("/elsewhere/x.ts", "/stage/src", plan)).toBeUndefined();
	});

	test("with the bundler: an optional import() no longer stops the build and fails or resolves when it runs, a static import still stops it", (context) => {
		if (spawnSync("bun", ["--version"]).status !== 0) return context.skip();
		// The installed extension, with a package installed beside it that it does not declare either.
		const installed = join(dir, "installed");
		mkdirSync(join(installed, "node_modules", "pibolt-present-160"), { recursive: true });
		writeFileSync(
			join(installed, "node_modules", "pibolt-present-160", "package.json"),
			JSON.stringify({ name: "pibolt-present-160", type: "module", exports: "./index.js" }),
		);
		writeFileSync(
			join(installed, "node_modules", "pibolt-present-160", "index.js"),
			'export const found = "present";\n',
		);
		const optional = [
			"async function load(name) {",
			'\tif (name === "missing") return await import("pibolt-missing-160");',
			'\treturn await import("pibolt-present-160");',
			"}",
			"export default async function () {",
			"\tconst said = [];",
			'\ttry { await load("missing"); said.push("loaded"); } catch (error) { said.push("caught " + error.code); }',
			'\tsaid.push((await load("present")).found);',
			"\treturn said;",
			"}",
			"",
		].join("\n");
		writeFileSync(join(installed, "optional.ts"), optional);
		// Its copy, as the generator makes it (the copy has no node_modules: neither package is declared).
		const src = join(dir, "stage", "src");
		mkdirSync(join(src, "global"), { recursive: true });
		writeFileSync(join(src, "global", "optional.ts"), optional);
		const manifest = join(src, "plugins.ts");
		writeFileSync(manifest, 'export default [(await import("./global/optional.ts")).default];\n');
		const plan = {
			manifest,
			plugins: [
				{
					name: "optional",
					entry: join(installed, "optional.ts"),
					origin: "global",
					stageDir: "global",
					importPath: "./global/optional.ts",
				},
			],
			roots: [{ stageDir: "global", root: installed, origin: "global" }],
			dependencies: [],
		};
		const work = mkdtempSync(join(tmpdir(), "pibolt-unresolved-"));
		const bundle = () =>
			spawnSync(
				"bun",
				["build", "--target=bun", "--format=esm", "--splitting", manifest, "--outdir", join(dir, "out")],
				{
					encoding: "utf8",
				},
			);
		try {
			expect(bundle().status).not.toBe(0);
			const said = leaveUnresolvedImportsToRunTime("bun", plan, dir, work);
			expect(said).toEqual([
				'optional: import("pibolt-missing-160") does not resolve: left to run time, as loaded from its files (global/optional.ts:2)',
				'optional: import("pibolt-present-160") does not resolve: left to run time, as loaded from its files (global/optional.ts:3)',
			]);
			// (Line for line as it was.)
			expect(readFileSync(join(src, "global", "optional.ts"), "utf8").split("\n").length).toBe(
				optional.split("\n").length,
			);
			const built = bundle();
			expect(built.status, built.stderr).toBe(0);
			writeFileSync(
				join(dir, "out", "run.mjs"),
				'const [f] = (await import("./plugins.js")).default;\nconsole.log(JSON.stringify(await f()));\n',
			);
			const ran = spawnSync("bun", [join(dir, "out", "run.mjs")], { encoding: "utf8", cwd: tmpdir() });
			expect(JSON.parse(ran.stdout)).toEqual(["caught ERR_MODULE_NOT_FOUND", "present"]);

			// A static import is the extension's own need: left as it is, and the build stops at it as before.
			const required = 'import { x } from "pibolt-missing-160";\nexport default () => x;\n';
			writeFileSync(join(src, "global", "required.ts"), required);
			writeFileSync(manifest, 'export default [(await import("./global/required.ts")).default];\n');
			expect(leaveUnresolvedImportsToRunTime("bun", plan, dir, work)).toEqual([]);
			expect(readFileSync(join(src, "global", "required.ts"), "utf8")).toBe(required);
			expect(bundle().stderr).toContain('Could not resolve: "pibolt-missing-160"');
		} finally {
			rmSync(work, { recursive: true, force: true });
		}
	});
});
