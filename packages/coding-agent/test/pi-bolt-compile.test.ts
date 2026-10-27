import { spawnSync } from "node:child_process";
import { generateKeyPairSync, sign } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
	type CompiledExtension,
	compiledExtensionFor,
	compiledExtensionsOf,
	compiledStateLines,
	extensionFingerprint,
	setCompiledExtensions,
	startsWithoutCompiledExtensions,
} from "../src/core/extensions/compiled.ts";
import { loadExtensions } from "../src/core/extensions/loader.ts";
import type { ExtensionFactory } from "../src/core/extensions/types.ts";
import {
	buildFailureReason,
	bundleErrorReason,
	bundlePieces,
	bundlerErrorAdvice,
	bytecodeSizeLimit,
	candidateLines,
	canRaiseLimit,
	checkReleaseSums,
	chooseExtensions,
	extensionsOfModule,
	extensionsThatDoNotBundle,
	functionTooBig,
	importAtRunTime,
	jsStringLiteral,
	leaveUnresolvedImportsToRunTime,
	leftOutLine,
	measuredSizes,
	measurePieces,
	originalOfCopy,
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
		const extension = compiledIn({ entry, scope: "file", root: dir });
		setCompiledExtensions([extension]);
		expect(compiledExtensionFor(entry)).toEqual({ factory, extension });
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
		expect(compiledExtensionFor(entry)).toEqual({ factory, extension });
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
		expect(compiledExtensionFor(entry)).toEqual({ factory, extension });
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

// An extension whose top-level code threw in the build (it read a file beside its module: the staged copy, gone) stopped every
// start, doctor and `update --rollback` included: the entry imported every compiled-in module before main(). Each module is
// imported when Pi loads that extension now, and one that fails compiled in is loaded from its files.
describe("a compiled-in extension runs only when Pi loads it, and one that fails is loaded from its files", () => {
	test("each source gets the manifest's factory; none when the manifest was not imported", () => {
		const source = (name: string) => ({
			name,
			entry: join(dir, `${name}.ts`),
			scope: "file" as const,
			root: dir,
			fingerprint: "f",
		});
		const sources = { alpha: source("alpha"), gamma: source("gamma") };
		expect(
			compiledExtensionsOf(sources, {
				default: [
					{ name: "alpha", factory },
					{ name: "gamma", factory },
				],
			}),
		).toEqual([
			{ ...sources.alpha, factory },
			{ ...sources.gamma, factory },
		]);
		expect(compiledExtensionsOf(sources, undefined)).toEqual(Object.values(sources));
	});

	test("one that failed compiled in is loaded from its files from then on, unless it changed since", () => {
		const entry = join(dir, "versioned.ts");
		writeFileSync(entry, "export default () => {};\n");
		touch(entry, 1_000_000);
		const extension = compiledIn({ entry, scope: "file", root: dir });
		setCompiledExtensions([extension]);
		expect(compiledExtensionFor(entry)).toEqual({ factory, extension });
		extension.failed = "ENOENT";
		expect(compiledExtensionFor(entry)).toEqual({ failed: extension });
		touch(entry, 2_000_000);
		expect(compiledExtensionFor(entry)).toEqual({ changed: extension });
	});

	test("one without a factory (a command that loads no compiled-in extension) is not used compiled in", () => {
		const entry = join(dir, "hello.ts");
		writeFileSync(entry, "export default () => {};\n");
		const { factory: _, ...extension } = compiledIn({ entry, scope: "file", root: dir });
		setCompiledExtensions([extension]);
		expect(compiledExtensionFor(entry)).toBeUndefined();
	});

	test("the loader: a compiled factory that throws falls back to the files, with a warning; failing there too is Pi's error", async () => {
		const entry = join(dir, "versioned.ts");
		writeFileSync(
			entry,
			'export default (pi) => { pi.registerCommand("ver", { description: "v", handler: async () => {} }); };\n',
		);
		const ran: string[] = [];
		const throwing: ExtensionFactory = () => {
			ran.push("compiled");
			throw new Error(
				"ENOENT: no such file or directory, open '/stage/package.json'\n    at /$bunfs/root/x.js:6:34",
			);
		};
		const extension = { ...compiledIn({ entry, scope: "file", root: dir }), factory: throwing };
		setCompiledExtensions([extension]);
		const first = await loadExtensions([entry], dir);
		expect(first.errors).toEqual([]);
		expect(first.extensions.map((loaded) => [...loaded.commands.keys()])).toEqual([["ver"]]);
		expect(first.warnings).toEqual([
			{
				path: entry,
				warning:
					"it failed compiled in (ENOENT: no such file or directory, open '/stage/package.json'), so it was loaded from its files: `pi-bolt compile` builds without it",
			},
		]);
		expect(extension.failed).toBe("ENOENT: no such file or directory, open '/stage/package.json'");
		// (From then on, as on /reload, from its files without running the compiled copy again.)
		const second = await loadExtensions([entry], dir);
		expect(second.errors).toEqual([]);
		expect(ran).toEqual(["compiled"]);

		// Failing from its files too: Pi's own error, as without compiling it in, and it is not marked as failing compiled in.
		const broken = join(dir, "broken.ts");
		writeFileSync(broken, 'throw new Error("b9: missing config");\n');
		const other = { ...compiledIn({ entry: broken, scope: "file", root: dir }), factory: throwing };
		setCompiledExtensions([other]);
		const result = await loadExtensions([broken], dir);
		expect(result.extensions).toEqual([]);
		expect(result.errors[0]?.error).toContain("b9: missing config");
		expect(result.warnings ?? []).toEqual([]);
		expect(other.failed).toBeUndefined();
	});

	test("the loader: a compiled factory that works is used, and marked as loaded", async () => {
		const entry = join(dir, "hello.ts");
		writeFileSync(entry, 'throw new Error("not loaded from its files");\n');
		const extension = { ...compiledIn({ entry, scope: "file", root: dir }), factory: () => {} };
		setCompiledExtensions([extension]);
		const result = await loadExtensions([entry], dir);
		expect(result.errors).toEqual([]);
		expect(result.extensions.length).toBe(1);
		expect(extension.loaded).toBe(true);
	});

	test("doctor, compile and update --rollback start without the compiled-in extensions; everything else with them", () => {
		for (const args of [
			["doctor"],
			["doctor", "--offline"],
			["compile"],
			["compile", "--off"],
			["compile", "--status"],
			["update", "--rollback"],
		]) {
			expect(startsWithoutCompiledExtensions(args), args.join(" ")).toBe(true);
		}
		for (const args of [
			[],
			["-p", "hello"],
			["update"],
			["update", "--self"],
			["list"],
			["--mode", "rpc"],
			["--help"],
		]) {
			expect(startsWithoutCompiledExtensions(args), args.join(" ")).toBe(false);
		}
	});
});

// After the installer runs again, or a switch to or from the JIT build, compile.json records a compile the build no longer has.
describe("pi-bolt compile --status and doctor say what is not used as compiled in", () => {
	test("a build with none while compile.json records some, a changed one, a removed one", () => {
		expect(compiledStateLines([], undefined)).toEqual([]);
		expect(compiledStateLines([], { extensions: ["hello", "word-tools"] })).toEqual([
			"compile.json records extensions compiled in (hello, word-tools), but this build has none: the installer, or a switch to or from the JIT build, replaced the build they were compiled into. Run `pi-bolt compile` to compile them in again.",
		]);
		const kept = join(dir, "kept.ts");
		const changed = join(dir, "changed.ts");
		const removed = join(dir, "removed.ts");
		for (const file of [kept, changed, removed]) {
			writeFileSync(file, "export default () => {};\n");
			touch(file, 1_000_000);
		}
		const extensions = [kept, changed, removed].map((entry) => ({
			...compiledIn({ entry, scope: "file", root: dir }),
			name: entry.slice(dir.length + 1, -3),
		}));
		touch(changed, 2_000_000);
		rmSync(removed);
		expect(compiledStateLines(extensions, { extensions: ["kept", "changed", "removed"] })).toEqual([
			"changed: changed since it was compiled in, so it loads from its files: run `pi-bolt compile` to compile it in again",
			`removed: no longer installed (${removed}): not loaded`,
		]);
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

	// Loader-parity review #8: the extensions besides packages, as Pi finds them, for gen-plugin-manifest.mjs --enabled.
	test("gives the other extensions Pi loads: a relative path from the agent directory, less those turned off", async () => {
		const agentDir = join(dir, "agent");
		mkdirSync(join(agentDir, "extensions"), { recursive: true });
		mkdirSync(join(agentDir, "my-tools"), { recursive: true });
		writeFileSync(join(agentDir, "extensions", "on.ts"), "export default () => {};\n");
		writeFileSync(join(agentDir, "extensions", "off.ts"), "export default () => {};\n");
		writeFileSync(join(agentDir, "my-tools", "configured.ts"), "export default () => {};\n");
		writeFileSync(
			join(agentDir, "settings.json"),
			JSON.stringify({ extensions: ["my-tools/configured.ts", "-extensions/off.ts"] }),
		);

		const resolved = await resolveCompilePackages(agentDir, dir);
		expect([...resolved.enabled].sort()).toEqual(
			[join(agentDir, "my-tools", "configured.ts"), join(agentDir, "extensions", "on.ts")].sort(),
		);
	});
});

describe("pi-bolt compile leaves out what compile.json records as excluded", () => {
	const record = { exclude: ["pi-web-access"] };
	const found = [
		{ name: "pi-web-access", aliases: ["pi-web-access", "npm:pi-web-access@0.41.0", "npm:pi-web-access"] },
		{ name: "acme-pi-tools", aliases: ["@acme/pi-tools", "/agent/npm/node_modules/@acme/pi-tools"] },
		{ name: "word-count", aliases: ["/agent/extensions/word-count.ts"] },
	];
	const chosen = (
		options: Parameters<typeof chooseExtensions>[1],
		from: Parameters<typeof chooseExtensions>[2] = record,
	) => {
		const { only, exclude, recorded } = chooseExtensions(found, options, from);
		return { only, exclude, recorded };
	};

	test("a compile that names no extensions takes the recorded exclusions, as an update does (#108)", () => {
		for (const options of [{}, { noPackages: true }, { afterUpdate: true }]) {
			expect(chosen(options)).toEqual({ exclude: ["pi-web-access"], recorded: ["pi-web-access"] });
		}
	});

	test("--exclude adds to them, --include takes one back, --no-exclude and --only drop them (#174)", () => {
		expect(chosen({ exclude: ["word-count"] })).toEqual({
			exclude: ["pi-web-access", "word-count"],
			recorded: ["pi-web-access"],
		});
		expect(chosen({ include: ["pi-web-access"] })).toEqual({ exclude: [], recorded: [] });
		expect(chosen({ noExclude: true })).toEqual({ exclude: [], recorded: [] });
		expect(chosen({ noExclude: true, exclude: ["word-count"] })).toEqual({ exclude: ["word-count"], recorded: [] });
		expect(chosen({ only: ["pi-web-access"] })).toEqual({ only: ["pi-web-access"], exclude: [], recorded: [] });
		// (Named by this command too: the command's.)
		expect(chosen({ exclude: ["pi-web-access"] })).toEqual({ exclude: ["pi-web-access"], recorded: [] });
	});

	test("an extension is named by its name, its package's name or source, or its file; the plan's names are recorded (#174)", () => {
		expect(
			chosen({ exclude: ["@acme/pi-tools", "npm:pi-web-access", "/agent/extensions/word-count.ts"] }, {}),
		).toEqual({
			exclude: ["pi-web-access", "acme-pi-tools", "word-count"],
			recorded: [],
		});
	});

	test("a value that names no extension, and a recorded one no longer there, are told apart", () => {
		const selection = chooseExtensions(
			found,
			{ exclude: ["@mariozechner/pi-tui"], include: ["nope"] },
			{
				exclude: ["gone", "word-count"],
			},
		);
		expect(selection.unknown).toEqual(["@mariozechner/pi-tui", "nope"]);
		expect(selection.stale).toEqual(["gone"]);
		expect(selection.exclude).toEqual(["word-count"]);
	});

	test("an update's --only, compile.json's, leaves out a name no longer there; one given on the command line is an error", () => {
		const update = chooseExtensions(
			found,
			{ afterUpdate: true, only: ["word-count", "gone"] },
			{ only: ["word-count", "gone"] },
		);
		expect(update).toMatchObject({ only: ["word-count"], staleOnly: ["gone"], unknown: [] });
		expect(chooseExtensions(found, { only: ["word-count", "gone"] }, undefined).unknown).toEqual(["gone"]);
	});

	test("with nothing recorded, nothing is left out", () => {
		expect(chooseExtensions(found, {}, undefined).exclude).toEqual([]);
		expect(chosen({}, { only: ["a"] })).toEqual({ exclude: [], recorded: [] });
	});
});

describe("pi-bolt compile says what it does with each extension and package", () => {
	const plugin = (name: string, origin: string) => ({
		name,
		entry: `/x/${name}.ts`,
		origin,
		stageDir: origin,
		importPath: `./${origin}/${name}.ts`,
		aliases: [],
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
		expect(candidateLines(plan, packages, ["pi-web-access"])).toEqual([
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

	// The runtime's bundler (Bun 1.4.3) prints a CommonJS module's wrapper as a function expression: it was not found, so a
	// function too big to compile in one was not named (#13 of the loader-parity review).
	test("a CommonJS wrapper printed as a function expression is a piece too", () => {
		const printed = [
			"// .pibolt-plugins/src/global/big-ext/node_modules/big-cjs/index.js",
			"var require_big_cjs = __commonJS(function(exports, module) {",
			"  var t = {};",
			"  module.exports = t;",
			"});",
			"",
		].join("\n");
		const { pieces } = bundlePieces(printed, ".pibolt-plugins/src/");
		expect(pieces.filter((piece) => piece.kind === "commonjs").map((piece) => [piece.module, piece.code])).toEqual([
			[".pibolt-plugins/src/global/big-ext/node_modules/big-cjs/index.js", "  var t = {};\n  module.exports = t;"],
		]);
	});

	// A prebundled package keeps its own minified wrappers (@cursor/sdk: `var Io = E(() => {`), and a module may hold a line
	// that is `});` inside a template literal (typescript.js): the module's wrapper ends at the last one before the next module.
	test("a minified wrapper is a function, and a wrapper ends at a `});` that a statement follows", () => {
		const printed = [
			"// .pibolt-plugins/src/node_modules/@cursor/sdk/dist/bundled/index.js",
			"var Io = E(() => {",
			"  init();",
			"});",
			"var require_typescript = __commonJS(function(exports, module) {",
			"  var text = `",
			"});",
			"`;",
			"  module.exports = text;",
			"});",
			"// .pibolt-plugins/src/global/next.ts",
			"var next_default = 1;",
			"",
		].join("\n");
		const { pieces } = bundlePieces(printed, ".pibolt-plugins/src/");
		expect(
			pieces.filter((piece) => piece.kind !== "top-level").map((piece) => [piece.kind, piece.name, piece.code]),
		).toEqual([
			["function", "Io", "  init();"],
			["commonjs", undefined, "  var text = `\n});\n`;\n  module.exports = text;"],
		]);
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
			].map((plugin) => ({ ...plugin, entry: "", aliases: [] })),
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
// 3 of 56 published extensions have a function over the compiler's 262144 bytes (355 to 609 KB): with the limit raised they
// built in about a minute at 2.7 GB. A compile raises it by itself up to 1 MB, on a machine with 8 GB.
describe("pi-bolt compile raises the limit on the size of a function by itself, within bounds", () => {
	test("up to 1 MB of bytecode, with 8 GB of memory", () => {
		const gb = 2 ** 30;
		expect(canRaiseLimit(609_173, 16 * gb)).toBe(true);
		expect(canRaiseLimit(1 << 20, 8 * gb)).toBe(true);
		expect(canRaiseLimit((1 << 20) + 1, 16 * gb)).toBe(false);
		expect(canRaiseLimit(355_101, 7 * gb)).toBe(false);
	});
});

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
					aliases: [],
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

// One extension the bundler cannot build stopped the build of all of them, with a path in a deleted folder. Pi loads each on its
// own: the one that does not resolve fails alone. It is left out of the build now, and loads from its files.
describe("pi-bolt compile leaves out an extension the bundler cannot build", () => {
	test("its line names it, what does not resolve, and its installed file", () => {
		expect(
			leftOutLine({
				name: "broken",
				origin: "global",
				reason: bundleErrorReason(
					{
						entry: "/stage/src/plugins.ts",
						message: 'Could not resolve: "left-pad-not-installed-xyz". Maybe you need to "bun install"?',
						specifier: "left-pad-not-installed-xyz",
						file: "/stage/src/global/broken.ts",
						line: 2,
					},
					"/agent/extensions/broken.ts",
				),
			}),
		).toBe(
			'broken (global): not compiled in: "left-pad-not-installed-xyz" does not resolve (/agent/extensions/broken.ts:2): it loads from its files',
		);
		expect(
			bundleErrorReason(
				{ entry: "/e", message: "Unexpected }", file: "/stage/src/global/syntax.ts", line: 4 },
				"/agent/syntax.ts",
			),
		).toBe("Unexpected } (/agent/syntax.ts:4)");
	});

	test("with the bundler: each extension with a static import or require() that does not resolve, in its code or a dependency's", (context) => {
		if (spawnSync("bun", ["--version"]).status !== 0) return context.skip();
		const installed = join(dir, "installed");
		const src = join(dir, "stage", "src");
		const files: Record<string, string> = {
			"global/good.ts": 'import { two } from "./shared/two.ts";\nexport default () => two;\n',
			"global/shared/two.ts": "export const two = 2;\n",
			"global/broken.ts": 'import leftPad from "pibolt-left-pad-missing";\nexport default () => leftPad;\n',
			"global/needs/index.ts": 'const x = require("./gone.js");\nexport default () => x;\n',
			// (A package whose dependency, copied beside the manifest, imports what is not there: found by building each alone.)
			"package-uses-dep/index.ts": 'import { dep } from "pibolt-dep";\nexport default () => dep;\n',
			"node_modules/pibolt-dep/package.json": JSON.stringify({
				name: "pibolt-dep",
				type: "module",
				exports: "./index.js",
			}),
			"node_modules/pibolt-dep/index.js": 'export { dep } from "pibolt-dep-missing";\n',
		};
		for (const [file, text] of Object.entries(files)) {
			mkdirSync(join(src, file, ".."), { recursive: true });
			writeFileSync(join(src, file), text);
		}
		const plugins = [
			{ name: "good", origin: "global", stageDir: "global", importPath: "./global/good.ts" },
			{ name: "broken", origin: "global", stageDir: "global", importPath: "./global/broken.ts" },
			{ name: "needs", origin: "global", stageDir: "global", importPath: "./global/needs/index.ts" },
			{
				name: "uses-dep",
				origin: "package",
				stageDir: "package-uses-dep",
				importPath: "./package-uses-dep/index.ts",
			},
		].map((plugin) => ({ ...plugin, entry: join(installed, plugin.importPath), aliases: [] as string[] }));
		const manifest = join(src, "plugins.ts");
		writeFileSync(
			manifest,
			`${plugins.map((plugin) => `await import("${plugin.importPath}");`).join("\n")}\nexport default [];\n`,
		);
		const plan = {
			manifest,
			plugins,
			roots: [
				{ stageDir: "global", root: join(installed, "extensions"), origin: "global" },
				{ stageDir: "package-uses-dep", root: join(installed, "uses-dep"), origin: "package" },
			],
			dependencies: [{ name: "pibolt-dep", from: join(installed, "node_modules", "pibolt-dep") }],
		};
		const work = mkdtempSync(join(tmpdir(), "pibolt-bundle-errors-"));
		try {
			expect(extensionsThatDoNotBundle("bun", plan, dir, work).map(leftOutLine)).toEqual([
				`broken (global): not compiled in: "pibolt-left-pad-missing" does not resolve (${join(installed, "extensions", "broken.ts")}:1): it loads from its files`,
				`needs (global): not compiled in: "./gone.js" does not resolve (${join(installed, "extensions", "needs", "index.ts")}:1): it loads from its files`,
				`uses-dep (package): not compiled in: "pibolt-dep-missing" does not resolve (${join(installed, "node_modules", "pibolt-dep", "index.js")}:1): it loads from its files`,
			]);
			// (The rest builds: nothing is left out of a manifest of the good one alone.)
			writeFileSync(manifest, 'await import("./global/good.ts");\nexport default [];\n');
			expect(extensionsThatDoNotBundle("bun", plan, dir, work)).toEqual([]);
			// An import of a name a module does not export: reported once every import resolves, so on a later pass.
			writeFileSync(
				join(src, "global", "noexport.ts"),
				'import { three } from "./shared/two.ts";\nexport default () => three;\n',
			);
			const withNoExport = {
				...plan,
				plugins: [
					...plan.plugins,
					{
						name: "noexport",
						origin: "global",
						stageDir: "global",
						importPath: "./global/noexport.ts",
						entry: join(installed, "noexport.ts"),
						aliases: [],
					},
				],
			};
			writeFileSync(
				manifest,
				'await import("./global/good.ts");\nawait import("./global/noexport.ts");\nexport default [];\n',
			);
			expect(extensionsThatDoNotBundle("bun", withNoExport, dir, work).map((left) => left.name)).toEqual([
				"noexport",
			]);
		} finally {
			rmSync(work, { recursive: true, force: true });
		}
	});
});

// #174: pi-system-prompt imports "@mariozechner/pi-tui", a name Pi's loader gives its TUI module; the build could not resolve
// it, said only the bundler's error, and `--exclude "@mariozechner/pi-tui"` (a package, not an extension) left out nothing.
describe("pi-bolt compile and Pi's other names for its modules (#174)", () => {
	const plan = {
		plugins: [
			{
				name: "pi-system-prompt",
				entry: "/agent/npm/node_modules/pi-system-prompt/extensions/system-prompt.ts",
				origin: "package",
				stageDir: "package-pi-system-prompt",
				importPath: "./package-pi-system-prompt/extensions/system-prompt.ts",
				aliases: ["pi-system-prompt", "npm:pi-system-prompt"],
			},
			{
				name: "word-count",
				entry: "/agent/extensions/word-count.ts",
				origin: "global",
				stageDir: "global",
				importPath: "./global/word-count.ts",
				aliases: ["/agent/extensions/word-count.ts"],
			},
		],
		excluded: [{ name: "big-bundle", origin: "package", entry: "/x/b.js", by: "exclude" as const }],
	};
	const at = (file: string, line: number) =>
		`    at /home/u/.pi-bolt/.compile-BTqjF3/kit/pi-bolt-kit-linux-x64/tree/packages/coding-agent/.pibolt-plugins/src/${file}:${line}:60`;
	// What the compiler printed for the issue's pi-system-prompt.
	const stopped = [
		'6 | import { matchesKey, visibleWidth, wrapTextWithAnsi } from "@mariozechner/pi-tui";',
		"                                                               ^",
		'error: Could not resolve: "@mariozechner/pi-tui". Maybe you need to "bun install"?',
		at("package-pi-system-prompt/extensions/system-prompt.ts", 6),
		"",
	].join("\n");

	test("a build stopped in an extension names it, and the --exclude that leaves it out with those left out already", () => {
		expect(bundlerErrorAdvice(stopped, plan, { exclude: ["big-bundle"] })).toEqual([
			"That is in the extension pi-system-prompt (package-pi-system-prompt/extensions/system-prompt.ts:6). Leave it out with `pi-bolt compile --exclude big-bundle,pi-system-prompt`: it then loads from its files, as before it was compiled in.",
		]);
		const two = `${stopped}error: No matching export in "x.ts" for import "y"\n${at("global/word-count.ts", 2)}\n`;
		expect(bundlerErrorAdvice(two, plan, {})).toEqual([
			"Those are in the extensions pi-system-prompt, word-count (package-pi-system-prompt/extensions/system-prompt.ts:6, global/word-count.ts:2). Leave them out with `pi-bolt compile --exclude pi-system-prompt,word-count`: they then load from their files, as before they were compiled in.",
		]);
		// (With --only, the others of --only.)
		expect(bundlerErrorAdvice(two, plan, { only: ["pi-system-prompt", "word-count", "notes"] })[0]).toContain(
			"`pi-bolt compile --only notes`",
		);
	});

	test("a build stopped in a dependency, or outside the extensions, says so or nothing", () => {
		const dependency = `error: Could not resolve: "missing"\n${at("node_modules/helper/index.js", 3)}\n`;
		expect(bundlerErrorAdvice(dependency, plan, {})).toEqual([
			"That is in a package the extensions depend on (node_modules/helper/index.js:3). Leave out the extensions that use it with `pi-bolt compile --exclude <name>`, by the names listed above.",
		]);
		expect(bundlerErrorAdvice('error: Could not resolve: "./missing"\n    at plugins.ts:3:20\n', plan, {})).toEqual(
			[],
		);
	});

	test("with the bundler: the generator's copy of an extension that imports them builds, importing Pi's own names", (context) => {
		if (spawnSync("bun", ["--version"]).status !== 0) return context.skip();
		const agentDir = join(dir, "agent");
		mkdirSync(join(agentDir, "extensions"), { recursive: true });
		writeFileSync(join(agentDir, "settings.json"), "{}");
		const extension = [
			'import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";',
			'import { matchesKey } from "@mariozechner/pi-tui";',
			'import { getModel } from "@earendil-works/pi-ai";',
			'import { Type } from "@sinclair/typebox";',
			"export default async function (pi: ExtensionAPI) {",
			'\tconst { models } = await import("@mariozechner/pi-ai/models");',
			'\tconst core = require("@mariozechner/pi-agent-core");',
			"\treturn [pi, matchesKey, getModel, Type, models, core];",
			"}",
			"",
		].join("\n");
		writeFileSync(join(agentDir, "extensions", "old-names.ts"), extension);
		const generator = join(dirname(fileURLToPath(import.meta.url)), "../../../scripts/gen-plugin-manifest.mjs");
		const generated = spawnSync(
			process.execPath,
			[generator, "--out", join(dir, "src"), "--agent-dir", agentDir, "--no-packages", "--json"],
			{ encoding: "utf8" },
		);
		expect(generated.status, generated.stderr).toBe(0);
		// (Pi's modules are outside this folder: the kit's tree has them, here they are left to whoever runs the bundle.)
		const bundle = (entry: string) =>
			spawnSync(
				"bun",
				[
					"build",
					"--target=bun",
					"--format=esm",
					"--external=@earendil-works/*",
					"--external=typebox",
					entry,
					"--outdir",
					join(dir, "out"),
				],
				{ encoding: "utf8" },
			);
		expect(bundle(join(agentDir, "extensions", "old-names.ts")).stderr).toContain(
			'Could not resolve: "@mariozechner/pi-tui"',
		);
		const built = bundle(join(dir, "src", "global", "old-names.ts"));
		expect(built.status, built.stderr).toBe(0);
		const code = readFileSync(join(dir, "out", "old-names.js"), "utf8");
		for (const name of [
			"@earendil-works/pi-tui",
			"@earendil-works/pi-ai/compat",
			"typebox",
			"@earendil-works/pi-ai/models",
			"@earendil-works/pi-agent-core",
		]) {
			expect(code).toContain(`"${name}"`);
		}
		expect(code).not.toMatch(/@mariozechner|@sinclair|"@earendil-works\/pi-ai"/);
	});
});
