/**
 * `pi-bolt compile`: compiles the extensions installed on this machine into this Pi-Bolt, as scripts/build-pi.sh
 * --plugins-from-installed does in a checkout of the repository, with nothing but what the release has.
 *
 * What a build needs besides the extensions: the runtime (pi-bolt-runtime-<platform>.tar.gz, the Bun with the compiler) and the
 * compile kit (pi-bolt-kit-<platform>.tar.gz: the files of Pi the release was built from, the training profile and the build's
 * command; scripts/make-kit.mjs). Both are this build's own release's, checked as the installers check a release: the
 * signature of its SHA256SUMS by the release key, the version its first line names, then each file's SHA-256. They are kept
 * in <install>/cache for the next compile.
 *
 * The new executable takes the place of this one, in the same folder; the one it replaces is kept beside it as
 * <folder>.previous (`pi-bolt update --rollback` puts it back). <install>/compile.json keeps what was asked for (and a raised
 * limit on the size of a function), and `pi-bolt update` compiles the same extensions into the release it installs.
 *
 * Each extension compiled in stands in for its installed copy while that is unchanged (core/extensions/compiled.ts).
 */
import { spawnSync } from "node:child_process";
import { createHash, verify } from "node:crypto";
import {
	chmodSync,
	cpSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	realpathSync,
	renameSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import { totalmem } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import chalk from "chalk";
import { getAgentDir } from "./config.ts";
import {
	type CompiledExtensionSource,
	compiledExtensionList,
	compiledStateLines,
	extensionFingerprint,
} from "./core/extensions/compiled.ts";
import { DefaultPackageManager } from "./core/package-manager.ts";
import { getPackageIdentity } from "./core/package-source.ts";
import { SettingsManager } from "./core/settings-manager.ts";
import { namesPlugin, type PluginPlan, planPlugins, selectionProblem } from "./gen-plugin-manifest.mjs";
import {
	PIBOLT,
	PIBOLT_RELEASE_KEY,
	PIBOLT_RELEASES_URL,
	piBoltInstallDir,
	piBoltInstallMethod,
	piBoltPackageManager,
} from "./pi-bolt.ts";

/** What `pi-bolt compile` was asked for, kept in <install>/compile.json for the next update. */
export interface CompileSelection {
	only?: string[];
	exclude?: string[];
	noPackages?: boolean;
	/**
	 * The compiler's limit on the bytecode of one function, when a compile raised it (BUN_JSC_maximumAOTCandidateBytecodeSize):
	 * the next compile, and the update's, use it too.
	 */
	maximumBytecodeSize?: number;
}

interface CompileRecord extends CompileSelection {
	/** The Pi-Bolt version they were compiled into, and the extensions that were. */
	version: string;
	extensions: string[];
}

interface CompileOptions extends CompileSelection {
	/** --no-exclude: none of the exclusions compile.json records. */
	noExclude?: boolean;
	/** --include: these compiled in again, which compile.json records as excluded. */
	include?: string[];
	/** --list: the extensions a compile takes, and nothing downloaded or built. */
	list?: boolean;
	/** Files to build with instead of the release's: a kit and a runtime (archives or unpacked folders). */
	kit?: string;
	runtime?: string;
	/** Build into this folder and install nothing. */
	out?: string;
	/** The update's run: the saved selection; the build it replaces is not kept (the update kept the one before it). */
	afterUpdate?: boolean;
}

interface Kit {
	piBolt: string;
	pi: string;
	platform: string;
	agent: string;
	entries: string[];
	args: string[];
	env: Record<string, string>;
}

/**
 * A package Pi-Bolt loads, with the extension files Pi loads from it: what gen-plugin-manifest.mjs takes with --packages.
 * An npm package is fingerprinted by its version; a local folder or a git checkout, which can change without one, by its
 * files; a local file by itself (core/extensions/compiled.ts).
 */
export interface CompilePackage {
	source: string;
	kind: "npm" | "git" | "folder" | "file";
	root: string;
	entries: string[];
}

/** The packages a compile takes, as Pi-Bolt loads them (piBolt.packages applied to `packages`). */
export interface CompilePackages {
	packages: CompilePackage[];
	/** Packages Pi-Bolt loads that have nothing to compile in, and why. */
	passedOver: Array<{ path: string; reason: string }>;
	/** Packages of Pi's `packages` list that piBolt.packages removes. */
	removed: string[];
	/**
	 * The other extensions Pi loads from the agent directory: those of its extensions/ and of the `extensions` setting, less
	 * those the setting turns off (gen-plugin-manifest.mjs --enabled).
	 */
	enabled: string[];
}

/**
 * The packages in the agent directory's settings, resolved as `pi-bolt list` and `pi-bolt doctor` resolve them: the
 * settings manager applies piBolt.packages (additions, `-` removals, replacements) to `packages`, and the package manager
 * finds each one's folder and the extension files Pi loads from it. A project's settings are not read: what is compiled in
 * is for every project, as gen-plugin-manifest.mjs reads only the agent directory too. Nothing is installed.
 */
export async function resolveCompilePackages(agentDir: string, cwd = process.cwd()): Promise<CompilePackages> {
	const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
	const packageManager = new DefaultPackageManager({ cwd, agentDir, settingsManager });
	const sourceOfEntry = (entry: string | { source: string }) => (typeof entry === "string" ? entry : entry.source);
	const loaded = settingsManager.getResolvedPackages("global", agentDir).map(sourceOfEntry);
	const loadedIdentities = new Set(loaded.map((source) => getPackageIdentity(source, agentDir)));
	const removed = (settingsManager.getGlobalSettings().packages ?? [])
		.map(sourceOfEntry)
		.filter((source) => !loadedIdentities.has(getPackageIdentity(source, agentDir)));

	const missing = new Set<string>();
	const { extensions } = await packageManager.resolve(async (source) => {
		missing.add(source);
		return "skip";
	});
	const packages: CompilePackage[] = [];
	const passedOver: CompilePackages["passedOver"] = [];
	for (const source of loaded) {
		const identity = getPackageIdentity(source, agentDir);
		const own = extensions.filter(
			(resource) =>
				resource.metadata.origin === "package" &&
				resource.metadata.scope === "user" &&
				resource.metadata.source === source,
		);
		const entries = own.filter((resource) => resource.enabled).map((resource) => resource.path);
		if (entries.length === 0) {
			const installed = !missing.has(source) && packageManager.getInstalledPath(source, "user") !== undefined;
			passedOver.push({
				path: source,
				reason: !installed
					? identity.startsWith("local:")
						? "not found"
						: "not installed, or not the version the settings name"
					: own.length > 0
						? "its extensions are turned off in settings"
						: "no extensions in it",
			});
			continue;
		}
		const packageRoot = own[0].metadata.packageRoot;
		const kind = identity.startsWith("npm:")
			? "npm"
			: identity.startsWith("git:")
				? "git"
				: packageRoot
					? "folder"
					: "file";
		packages.push({ source, kind, root: packageRoot ?? dirname(entries[0]), entries });
	}
	const enabled = extensions
		.filter(
			(resource) =>
				resource.enabled &&
				resource.metadata.scope === "user" &&
				resource.metadata.origin === "top-level" &&
				(resource.metadata.source === "auto" || resource.metadata.source === "local"),
		)
		.map((resource) => resource.path);
	return { packages, passedOver, removed, enabled };
}

/** The plan gen-plugin-manifest.mjs prints with --json: planPlugins's, and where it wrote the manifest. */
type ManifestPlan = PluginPlan & { manifest: string };

/**
 * One line for each extension and package a compile finds: compiled in, passed over and why, or excluded and by what. None
 * goes unmentioned: one missing from the list looks like one that is not there. `recorded`: the exclusions that are
 * compile.json's rather than this command's.
 */
export function candidateLines(
	plan: Pick<ManifestPlan, "plugins" | "skipped" | "excluded">,
	packages: Pick<CompilePackages, "passedOver" | "removed">,
	recorded: string[] = [],
): string[] {
	const exclusion = (plugin: ManifestPlan["excluded"][number]) =>
		plugin.by === "only"
			? "not in --only"
			: recorded.includes(plugin.name)
				? "excluded (compile.json)"
				: "excluded (--exclude)";
	return [
		...plan.plugins.map((plugin) => `${plugin.name} (${plugin.origin}): compiling`),
		...plan.excluded.map((plugin) => `${plugin.name} (${plugin.origin}): ${exclusion(plugin)}`),
		...[...packages.passedOver, ...plan.skipped].map((entry) => `${entry.path}: passed over: ${entry.reason}`),
		...packages.removed.map((source) => `${source}: excluded by piBolt.packages`),
	];
}

class CompileError extends Error {}

/**
 * A build stopped at a function too big to compile, of `size` bytes of bytecode: of the extensions `names`, or (none) to be
 * built again with the limit raised to its size (canRaiseLimit).
 */
class TooBigToCompile extends Error {
	names: string[];
	size: number;
	constructor(names: string[], size: number) {
		super(`a function of ${size} bytes of bytecode is too big to compile`);
		this.names = names;
		this.size = size;
	}
}

/**
 * The limit a compile raises by itself, to compile a function too big for the compiler's own (262144 bytes of bytecode) without
 * being asked, and the memory that takes. Measured on 56 published extensions: the 3 that passed 262144 had functions of 355 to
 * 609 KB, and built with the limit raised in 54 to 105 s, at about 2.7 GB at the most (the compiler's "minutes and tens of GB"
 * is for much bigger ones).
 */
const RAISED_LIMIT_CAP = 1 << 20;
const RAISED_LIMIT_MEMORY = 8 * 2 ** 30;

/** Whether a compile raises the limit to `size` by itself: up to RAISED_LIMIT_CAP, on a machine with RAISED_LIMIT_MEMORY. */
export function canRaiseLimit(size: number, memory = totalmem()): boolean {
	return size <= RAISED_LIMIT_CAP && memory >= RAISED_LIMIT_MEMORY;
}

/** Where kit.json's paths say "the kit's folder" (scripts/make-kit.mjs). */
const KIT_FOLDER = "@KIT@";

const USAGE = `Usage: pi-bolt compile [options]

Compile the extensions installed on this machine into Pi-Bolt: they then run as machine code, like Pi itself, and cost
nothing to load. Each one compiled in is used while its installed copy is unchanged; a changed one is loaded from its files
again until it is compiled in again. \`pi-bolt update\` compiles the same extensions into the release it installs.

Options:
  --list             List the extensions a compile takes, by the names it gives them, and stop: nothing is downloaded
  --only a,b         Only these extensions
  --exclude a,b      Every extension except these. They are recorded: later compiles without --only, and
                     \`pi-bolt update\`, leave them out too, and a later --exclude adds to them
  --include a,b      Compile these in again, which --exclude left out before
  --no-exclude       Leave out none of the extensions --exclude left out before (with --exclude: only those it names)
  --no-packages      Leave out the packages \`pi-bolt install\` installed
  --off              Go back to the release's executable, with no extensions compiled in
  --status           Show what is compiled in
  --kit FILE         Build with this compile kit instead of the release's (an archive or an unpacked folder)
  --runtime FILE     Build with this runtime instead of the release's (an archive, a folder, or the bun executable)
  --out DIR          Build into DIR and install nothing

--only, --exclude and --include take an extension by the name --list gives it, a package also by its name or source
(@acme/pi-tools, npm:pi-lens) or its folder, and any extension by its file or folder.

Environment:
  BUN_JSC_maximumAOTCandidateBytecodeSize=N
                     Compile functions of up to N bytes of bytecode (default 262144). compile.json keeps it: a later
                     \`pi-bolt compile\` without it, and \`pi-bolt update\`, use it too; N=262144 takes it back
`;

export function piBoltCompileUsage(): string {
	return USAGE;
}

function parseList(value: string | undefined, flag: string): string[] {
	if (!value || value.startsWith("--")) throw new CompileError(`${flag} needs a list of names`);
	return value
		.split(",")
		.map((name) => name.trim())
		.filter(Boolean);
}

/** The platform as release files name it: linux-x64, darwin-arm64. */
function platformName(): string {
	return `${process.platform}-${process.arch}`;
}

/** Where this installation is: <install> and the build's folder in it. */
function installation(): { installDir: string; buildDir: string } {
	const installDir = piBoltInstallDir();
	if (!installDir) {
		throw new CompileError(
			`this Pi-Bolt is not in an installation (${dirname(process.execPath)}): use --out DIR, or install it with the installer`,
		);
	}
	return { installDir, buildDir: dirname(process.execPath) };
}

function sha256File(path: string): string {
	return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function run(command: string, args: string[], options: { cwd?: string; env?: NodeJS.ProcessEnv } = {}) {
	return spawnSync(command, args, {
		encoding: "utf8",
		stdio: ["ignore", "pipe", "pipe"],
		maxBuffer: 256 << 20,
		...options,
	});
}

/** Unpacks a .tar.gz into `into` (tar is on every Linux and macOS). */
function untar(archive: string, into: string): void {
	mkdirSync(into, { recursive: true });
	const result = run("tar", ["-xzf", archive, "-C", into]);
	if (result.status !== 0) throw new CompileError(`could not unpack ${archive}: ${result.stderr.trim()}`);
}

async function download(url: string): Promise<Buffer> {
	const response = await fetch(url, { redirect: "follow" });
	if (!response.ok) throw new CompileError(`download failed (${response.status}): ${url}`);
	return Buffer.from(await response.arrayBuffer());
}

/**
 * The release's checksums, if they are Pi-Bolt `version`'s: signed by the release key (keys/release.pub), and naming that
 * version on their first line. As the installers check them; there is no way round either check.
 */
export function checkReleaseSums(
	sums: Buffer,
	signature: Buffer,
	version: string,
	key = PIBOLT_RELEASE_KEY,
): Map<string, string> {
	let signed = false;
	try {
		signed = verify(null, sums, key, signature);
	} catch {
		signed = false;
	}
	if (!signed) throw new CompileError("the release's signature does not verify: its files are not Pi-Bolt's");
	const lines = sums.toString("utf8").split(/\r?\n/);
	if (lines[0] !== `# pi-bolt ${version}`) {
		throw new CompileError(`the release's checksums are not Pi-Bolt ${version}'s (they begin "${lines[0]}")`);
	}
	const sha = new Map<string, string>();
	for (const line of lines.slice(1)) {
		const match = /^([0-9a-f]{64}) [ *]?(.+)$/.exec(line);
		if (match) sha.set(match[2], match[1]);
	}
	return sha;
}

/** The release's file `name`: from the cache when it is there with the signed checksum, else downloaded into it. */
async function releaseFile(base: string, name: string, sha: Map<string, string>, cacheDir: string): Promise<string> {
	const expected = sha.get(name);
	if (!expected)
		throw new CompileError(`the release has no ${name}: it cannot compile extensions in on ${platformName()}`);
	const cached = join(cacheDir, `${expected.slice(0, 16)}-${name}`);
	if (existsSync(cached) && sha256File(cached) === expected) return cached;
	process.stderr.write(chalk.dim(`downloading ${name}\n`));
	const data = await download(`${base}/${name}`);
	if (createHash("sha256").update(data).digest("hex") !== expected) {
		throw new CompileError(`checksum mismatch: the download of ${name} is corrupt or incomplete`);
	}
	mkdirSync(cacheDir, { recursive: true });
	const partial = `${cached}.part`;
	writeFileSync(partial, data);
	renameSync(partial, cached);
	return cached;
}

/** Keeps only `keep` in the cache: a compile needs the files of one release. */
function pruneCache(cacheDir: string, keep: string[]): void {
	if (!existsSync(cacheDir)) return;
	for (const name of readdirSync(cacheDir)) {
		if (!keep.includes(join(cacheDir, name))) rmSync(join(cacheDir, name), { recursive: true, force: true });
	}
}

/** A kit or runtime given on the command line: an unpacked folder as it is, an archive unpacked into `work`. */
function localFile(path: string, work: string, folder: string): string {
	if (!existsSync(path)) throw new CompileError(`${path} not found`);
	if (statSync(path).isDirectory() || !/\.(tar\.gz|tgz)$/.test(path)) return path;
	untar(path, join(work, folder));
	const [top] = readdirSync(join(work, folder));
	return join(work, folder, top);
}

/** The runtime's bun in an unpacked runtime archive (pi-bolt-runtime-<platform>/bun), or the path given. */
function runtimeBun(path: string): string {
	const bun = statSync(path).isDirectory() ? join(path, "bun") : path;
	if (!existsSync(bun)) throw new CompileError(`no bun in ${path}`);
	return bun;
}

/**
 * How each extension is fingerprinted (core/extensions/compiled.ts), from where the generator found it. `kinds`: each
 * package's kind by its folder.
 */
function sourceOf(
	plugin: ManifestPlan["plugins"][number],
	roots: ManifestPlan["roots"],
	kinds: ReadonlyMap<string, CompilePackage["kind"]>,
): CompiledExtensionSource {
	const root = roots.find((r) => r.stageDir === plugin.stageDir)?.root ?? dirname(plugin.entry);
	const entry = realpathSync(plugin.entry);
	let source: Omit<CompiledExtensionSource, "fingerprint">;
	if (plugin.origin === "package") {
		const kind = kinds.get(root) ?? "npm";
		const scope = kind === "npm" ? "package" : kind === "file" ? "file" : "folder";
		source = { name: plugin.name, entry, scope, root };
	} else {
		const segments = relative(root, plugin.entry).split(sep);
		if (segments.length > 1) {
			source = { name: plugin.name, entry, scope: "folder", root: join(root, segments[0]) };
		} else if (
			plugin.origin === "configured" &&
			(existsSync(join(root, "package.json")) || /^index\.(ts|js)$/.test(basename(plugin.entry)))
		) {
			source = { name: plugin.name, entry, scope: "folder", root };
		} else {
			source = { name: plugin.name, entry, scope: "file", root };
		}
	}
	const fingerprint = extensionFingerprint(source);
	if (!fingerprint) throw new CompileError(`${plugin.entry}: could not read it`);
	return { ...source, fingerprint };
}

/**
 * The entry point: Pi's Bun entry (src/bun/cli.ts), with the extensions passed to main() as compiled in.
 *
 * The manifest is loaded with import(), neither imported with the entry nor required:
 * - Imported, the top-level code of every extension joined Pi's, about 200 KB of bytecode, in one function, and the compiler
 *   refuses a function of more than 256 KB (maximumAOTCandidateBytecodeSize): a few extensions together stopped the build (#129).
 * - Required, an extension with an `await` at its top level stopped the bundler: require() cannot wait for a module (#142).
 * The build splits its code (--splitting, the kit's arguments), and the generated manifest loads each extension with import()
 * too (gen-plugin-manifest.mjs): each extension, with the modules only it imports, is a file of its own, a module whose
 * top-level code the compiler takes as one function, so the limit is one extension's. They run once each, in the manifest's
 * order, an extension that waits at its top level before the next one starts, as when the manifest imported them. Modules
 * extensions share are a file of their own too, and Pi's own modules that an extension imports stay where Pi has them.
 *
 * Each extension's module is imported when Pi loads it (the manifest's `lazy`), not before main(): one turned off never runs,
 * and one that throws fails as it loads and is loaded from its files instead (core/extensions/compiled.ts). The commands that
 * report on or repair an installation do not import the manifest at all (startsWithoutCompiledExtensions).
 * PIBOLT_COMPILE_CHECK=<file>: the compile's check of a new build (checkCompiledStart), which writes there as it exits which
 * compiled-in extensions loaded, and what failed.
 */
const ENTRY = `// Generated by \`pi-bolt compile\`: Pi's Bun entry (packages/coding-agent/src/bun/cli.ts), with extensions compiled in.
import "../dist/bun/sandbox-env-setup.js";
// (\`pi-bolt --version\` answered before the rest of Pi, and the extensions, are loaded: as Pi's own entry does.)
import "../dist/bun/version-fast-path.js";
import "../dist/bun/runtime-setup.js";
import { writeFileSync } from "node:fs";
import { setupCli } from "../dist/cli/setup.js";
import {
	compiledExtensionList,
	compiledExtensionsOf,
	startsWithoutCompiledExtensions,
} from "../dist/core/extensions/compiled.js";
import { main } from "../dist/main.js";
import sources from "./sources.json" with { type: "json" };

const args = process.argv.slice(2);
// (Loaded with import(), not imported with this module nor required: ENTRY in pi-bolt-compile.ts.)
const manifest = startsWithoutCompiledExtensions(args) ? undefined : await import("./src/plugins.ts");
const check = process.env.PIBOLT_COMPILE_CHECK;
if (check) {
	process.on("exit", () => {
		const report = compiledExtensionList().map(({ name, loaded, failed }) => ({ name, loaded: loaded === true, failed }));
		writeFileSync(check, JSON.stringify(report));
	});
}

setupCli();
main(args, { compiledExtensions: compiledExtensionsOf(sources, manifest) });
`;

/**
 * The build's environment for this variant: the kit's (the release's default build), with this build's JIT and CPU, and the
 * limit on the bytecode of one function if one is set.
 */
function buildEnvironment(kit: Kit, kitDir: string, maximumBytecodeSize: number | undefined): NodeJS.ProcessEnv {
	const build = PIBOLT!;
	const env: NodeJS.ProcessEnv = { ...process.env };
	if (maximumBytecodeSize !== undefined) env[SIZE_LIMIT] = String(maximumBytecodeSize);
	for (const [name, value] of Object.entries(kit.env)) env[name] = value.split(KIT_FOLDER).join(kitDir);
	delete env.BUN_AOT_JIT;
	delete env.BUN_AOT_CPU;
	if (!build.jit) env.BUN_AOT_JIT = "0";
	// (As build-pi.sh: x64-baseline is the build for any x86-64 CPU, and every Linux ARM64 build is the one for any ARMv8.0 CPU.)
	if (build.variant === "x64-baseline" || (process.platform === "linux" && build.variant.startsWith("arm64"))) {
		env.BUN_AOT_CPU = "baseline";
	}
	// (Nothing from a Pi-Bolt that started this one: the build is a Bun of its own.)
	delete env.BUN_BE_BUN;
	delete env.BUN_INTERNAL_SPAWN_HELPER;
	return env;
}

/** The variable that sets the compiler's limit on the bytecode of one function, and its default (WebKit's OptionsList.h). */
const SIZE_LIMIT = "BUN_JSC_maximumAOTCandidateBytecodeSize";
const DEFAULT_SIZE_LIMIT = 262144;

/** The compiler's "AOT: " lines that say why a build stops (the others report its progress). */
const AOT_STOP = /cannot be compiled ahead of time|could not be compiled ahead of time|maximumAOTCandidateBytecodeSize/;

/**
 * Why a build stopped, from what the compiler printed: each "AOT: " line that says so (a function too big to compile, or
 * one the compiler declined), and the last lines of the rest. The reason is often an "AOT: " line alone, and those lines
 * are otherwise mostly progress. `tooBig`: what explainTooBig found out about a function too big to compile, said in place of
 * the general advice.
 */
export function buildFailureReason(output: string, tooBig: string[] = []): string {
	const lines = output.split(/\r?\n/).filter((line) => line.trim());
	const indexes = (keep: (line: string) => boolean) => lines.flatMap((line, index) => (keep(line) ? [index] : []));
	const stops = indexes((line) => line.startsWith("AOT: ") && AOT_STOP.test(line)).slice(-22);
	const kept = new Set([...stops, ...indexes((line) => !line.startsWith("AOT: ")).slice(-8)]);
	const reason = lines.filter((_, index) => kept.has(index));
	if (reason.length === 0) return lines.slice(-8).join("\n") || "(the compiler printed nothing)";
	if (stops.some((index) => lines[index].includes("maximumAOTCandidateBytecodeSize"))) {
		reason.push(
			...(tooBig.length > 0
				? tooBig
				: [
						`Leave that extension out with \`pi-bolt compile --exclude <name>\`, or compile it anyway with \`${SIZE_LIMIT}=<n> pi-bolt compile\`, which compile.json keeps for \`pi-bolt update\` (docs/TROUBLESHOOTING.md).`,
					]),
		);
	}
	return reason.join("\n");
}

// --- a function too big to compile -----------------------------------------------------------------------------------

/**
 * The limit a compile builds with: the one the environment sets, else the one compile.json keeps (a compile that raised it
 * records it, so that `pi-bolt update` compiles the same extensions again). Undefined: the compiler's own.
 */
export function bytecodeSizeLimit(env: NodeJS.ProcessEnv, record: { maximumBytecodeSize?: number } | undefined) {
	const set = env[SIZE_LIMIT];
	if (set === undefined || set === "") {
		const kept = record?.maximumBytecodeSize;
		return Number.isSafeInteger(kept) && kept! > 0 ? kept : undefined;
	}
	if (!/^\d+$/.test(set.trim())) throw new CompileError(`${SIZE_LIMIT}=${set}: not a number of bytes`);
	return Number(set.trim());
}

/** The compiler's line for a function too big to compile (the WebKit patch, AOTCompiler.cpp). */
const TOO_BIG =
	/AOT: a function of (\d+) bytes of bytecode \((the top-level code of a module|a function)\) is bigger than maximumAOTCandidateBytecodeSize/;

/** The function the compiler refused for its size, from what it printed: how big, and whether it is a module's top level. */
export function functionTooBig(output: string): { size: number; topLevel: boolean } | undefined {
	const match = TOO_BIG.exec(output);
	return match ? { size: Number(match[1]), topLevel: match[2] !== "a function" } : undefined;
}

/**
 * A piece of the extensions' code in a bundle: what becomes one function, or (`top-level`) a module's share of the top-level
 * code of the whole program. `code` is what it would be as the body of a function.
 */
export interface BundlePiece {
	module: string;
	kind: "esm" | "commonjs" | "function" | "top-level";
	/** A function's name. */
	name?: string;
	async: boolean;
	code: string;
}

/**
 * A module's wrapper: `__commonJS((exports, module) => {`, or as the runtime's bundler prints it, `__commonJS(function(exports,
 * module) {`; or a prebundled package's own, minified (`var Io = E(() => {`), a function too.
 */
const WRAPPER = /^var ([\w$]+) = (?:__(esm|commonJS)|[\w$]+)\((async )?(?:\([^)]*\) =>|function ?\([^)]*\)) \{$/;
/** A line that can follow the end of a wrapper (`});`): a statement at the top level, a module's start, or nothing. */
const AFTER_WRAPPER = /^(?:$|\/\/ |var |let |const |function|async function|class |export |import |[\w$.]+\(.*\);$)/;
const FUNCTION = /^(async )?function\*? ?([\w$]+)\(.*\) \{$/;
/** `var a, b;` with nothing assigned: declarations only. */
const DECLARATIONS = /^(?:var|let|const) ([\w$]+(?:, [\w$]+)*);$/;

/** The names a line at the program's top level declares: `var a, b;`, `var a = ...`, a function, a class, an import's. */
function declaredNames(line: string): string[] {
	const listed = DECLARATIONS.exec(line);
	if (listed) return listed[1].split(", ");
	const one = /^(?:(?:var|let|const) ([\w$]+) =|(?:async )?function\*? ?([\w$]+)\(|class ([\w$]+))/.exec(line);
	if (one) return [one[1] ?? one[2] ?? one[3]];
	const imported = /^import (.+) from /.exec(line);
	return imported ? [...imported[1].matchAll(/([\w$]+)\s*(?:,|}|$)/g)].map((match) => match[1]) : [];
}

/**
 * The pieces of the code of the modules under `folder` in a bundle as Bun prints it (`--format=esm`, not minified): each module
 * in a function of its own (an `__esm` or `__commonJS` wrapper's body), each function it declares at the top level, and the
 * rest of what it has at the top level, with the wrappers' bodies left out, which is what it adds to the program's top level.
 * Also the names declared at the top level of the program: in the module those are its variables.
 *
 * It reads the layout Bun prints, not JavaScript: a module begins at a `// <path>` line, and what a line at the start of a line
 * opens ends at the next `});` or `}` there. Good enough to tell which piece is big, which is what it is for.
 */
export function bundlePieces(bundle: string, folder: string): { pieces: BundlePiece[]; names: string[] } {
	const lines = bundle.split("\n");
	const names = new Set<string>();
	const pieces: BundlePiece[] = [];
	const topLevel = new Map<string, string[]>();
	let module = "";
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i];
		for (const name of declaredNames(line)) names.add(name);
		if (line.startsWith("// ")) {
			module = line.slice(3);
			continue;
		}
		// (A file of a split build begins with the names it imports from the others, and ends with those it exports, one a line.)
		if (/^(?:import|export) \{$/.test(line)) {
			let end = i + 1;
			for (; end < lines.length && !lines[end].startsWith("}"); end++) {
				const imported = line.startsWith("import") ? /([\w$]+),?$/.exec(lines[end]) : null;
				if (imported) names.add(imported[1]);
			}
			i = end;
			continue;
		}
		const wrapper = WRAPPER.exec(line);
		const fn = wrapper ? undefined : FUNCTION.exec(line);
		let end = i;
		if (fn) {
			end = lines.indexOf("}", i + 1);
			if (end === -1) end = lines.length;
		} else if (wrapper) {
			// (The first `});` that a statement follows: a template literal in the module may hold a line that is `});` too.)
			end = i + 1;
			while (end < lines.length && !(lines[end] === "});" && AFTER_WRAPPER.test(lines[end + 1] ?? ""))) end++;
		}
		if (module.startsWith(folder)) {
			const body = lines.slice(i + 1, end).join("\n");
			if (wrapper) {
				const kind = wrapper[2] === "esm" ? "esm" : wrapper[2] === "commonJS" ? "commonjs" : "function";
				pieces.push({
					module,
					kind,
					...(kind === "function" ? { name: wrapper[1] } : {}),
					async: !!wrapper[3],
					code: body,
				});
			} else if (fn) {
				pieces.push({ module, kind: "function", name: fn[2], async: !!fn[1], code: body });
			}
			// What the module adds to the program's top level: its statements, with a wrapper's body left out, and without the
			// declarations of the module's variables and functions and its imports, which the module has before it runs.
			const own = topLevel.get(module) ?? [];
			// (Not `var { a } = ...`: without its `var`, a line that begins with `{` is a block, and does not parse.)
			const assigned = line.replace(/^(?:var|let|const) (?!\{)/, "");
			if (wrapper) own.push(assigned, "});");
			else if (!fn && !/^(?:import|export) /.test(line) && !DECLARATIONS.test(line)) own.push(assigned);
			topLevel.set(module, own);
		}
		i = end;
	}
	for (const [module, code] of topLevel) {
		if (code.length > 0) pieces.push({ module, kind: "top-level", async: false, code: code.join("\n") });
	}
	names.delete("eval");
	names.delete("arguments");
	return { pieces, names: [...names] };
}

/**
 * Measures the bytecode of each piece with the runtime's engine, which prints the size of each function as it makes its
 * bytecode (BUN_JSC_dumpGeneratedBytecodes): each piece is the body of a function that does nothing, its code being behind a
 * test that fails, and the bundle's top-level names are variables around it, as the module's are. So nothing of it runs.
 * The compiler's own figure can be smaller (it encodes some instructions shorter: a fifth less for a module of 3,000
 * statements), so the pieces are compared with each other rather than matched to it.
 */
const MEASURE = `import { readFileSync } from "node:fs";
const { pieces, names } = JSON.parse(readFileSync(process.argv[2], "utf8"));
const declared = new Set(names);
const make = (id, code, isAsync) => {
	const own = [...new Set(code.match(/[A-Za-z_$][\\w$]*/g) ?? [])].filter((name) => declared.has(name));
	const source = \`(function () { "use strict"; \${own.length ? \`var \${own.join(", ")};\` : ""} return \${isAsync ? "async " : ""}function __pibolt_\${id}() { if (globalThis.__piboltNever) {\\n\${code}\\n} }; })\`;
	try {
		(0, eval)(source)()();
	} catch {
		// (The top-level code of a module that waits at its top level: an \`await\` there is only valid in an async function.)
		if (!isAsync) make(id, code, true);
	}
};
make("base", "", false);
pieces.forEach((piece, index) => make(index, piece.code, piece.async));
`;

/** The bytecode size of each of `pieces` by its index, measured with `bun`'s engine (MEASURE), its files in `work`. */
export function measurePieces(
	bun: string,
	pieces: BundlePiece[],
	names: string[],
	work: string,
	env: NodeJS.ProcessEnv = process.env,
): Map<number, number> {
	writeFileSync(join(work, "measure.mjs"), MEASURE);
	writeFileSync(join(work, "pieces.json"), JSON.stringify({ pieces, names }));
	const measured = run(bun, [join(work, "measure.mjs"), join(work, "pieces.json")], {
		env: { ...env, BUN_JSC_dumpGeneratedBytecodes: "1" },
	});
	return measuredSizes(`${measured.stdout ?? ""}\n${measured.stderr ?? ""}`);
}

/** The size of each piece, by its index, from what the engine printed (BUN_JSC_dumpGeneratedBytecodes). */
export function measuredSizes(output: string): Map<number, number> {
	const sizes = new Map<number, number>();
	let base = 0;
	for (const match of output.matchAll(/^__pibolt_(\w+)#[^:\n]*:\[[^,\n]*, \w+, (\d+)/gm)) {
		if (match[1] === "base") base = Number(match[2]);
		// (An async function is two: the function, then its body, which holds the code.)
		else sizes.set(Number(match[1]), Math.max(sizes.get(Number(match[1])) ?? 0, Number(match[2])));
	}
	for (const [index, size] of sizes) sizes.set(index, Math.max(0, size - base));
	return sizes;
}

/** Where the bundle has the generator's folder: its modules' paths are relative to the Pi tree's coding agent. */
const PLUGIN_SOURCES = ".pibolt-plugins/src/";

/** Which extensions of `plan` a module of the bundle belongs to, by where the generator copied each (none: a dependency). */
export function extensionsOfModule(module: string, plan: Pick<ManifestPlan, "plugins">): string[] {
	if (!module.startsWith(PLUGIN_SOURCES)) return [];
	const path = module.slice(PLUGIN_SOURCES.length);
	return plan.plugins
		.filter((plugin) => {
			const rel = plugin.importPath.replace(/^\.\//, "");
			const inStage = rel.slice(plugin.stageDir.length + 1).split("/");
			// (A package's folder is all its own; in the agent directory's folder, an extension is a file or a folder of it.)
			const own =
				plugin.origin === "package" || inStage.length === 1
					? `${plugin.stageDir}/${plugin.origin === "package" ? "" : inStage[0]}`
					: `${plugin.stageDir}/${inStage[0]}/`;
			return own.endsWith("/") ? path.startsWith(own) : path === own;
		})
		.map((plugin) => plugin.name);
}

/** Whose a module of the bundle is, as a message says it: the extensions', the manifest's, or a shared dependency's. */
function ownerOfModule(module: string, plan: Pick<ManifestPlan, "plugins">): string {
	const of = extensionsOfModule(module, plan);
	if (of.length > 0) return of.join(", ");
	return module === `${PLUGIN_SOURCES}plugins.ts` ? "the list of extensions" : "dependencies the extensions share";
}

/** A piece as a message names it: what it is, the file, and the extension. */
function describePiece(piece: BundlePiece, plan: Pick<ManifestPlan, "plugins">, owners: string[] = []): string {
	const file = piece.module.slice(PLUGIN_SOURCES.length);
	if (owners.length > 0 && extensionsOfModule(piece.module, plan).length === 0) {
		const what = piece.kind === "function" ? `the function \`${piece.name}\` of ${file}` : `${file}`;
		return `${what}, a dependency of the extension${owners.length > 1 ? "s" : ""} ${owners.join(", ")}`;
	}
	const owner = extensionsOfModule(piece.module, plan).length > 0 ? "the extension " : "";
	const what =
		piece.kind === "function"
			? `the function \`${piece.name}\` of ${file}`
			: piece.kind === "commonjs"
				? `${file} (a CommonJS module, all one function)`
				: `the top-level code of ${file}`;
	return `${what}, in ${owner}${ownerOfModule(piece.module, plan)}`;
}

/**
 * Says which of the extensions is behind a function too big to compile, by measuring their code (bundlePieces, MEASURE):
 * the largest of their functions when it is a function; when it is the top-level code of a module, the extension with the
 * largest when the build splits its code (`split`: each extension is a file of its own), else each extension's share of the
 * top-level code of the whole program, which Pi and every extension add to. Built with the build's bundler, split as the build is, but neither compiled nor run. Nothing (the
 * general advice instead) when that fails or finds nothing big enough. `names`: the extensions it is of, when it is one's (or a
 * dependency's that only they import), which a compile then leaves out.
 */
function explainTooBig(
	tooBig: { size: number; topLevel: boolean },
	plan: ManifestPlan,
	bun: string,
	agent: string,
	work: string,
	split: boolean,
): { lines: string[]; names: string[] } {
	const nothing = { lines: [], names: [] };
	const env: NodeJS.ProcessEnv = { ...process.env };
	delete env.BUN_BE_BUN;
	delete env.BUN_INTERNAL_SPAWN_HELPER;
	const out = join(work, "measure");
	const bundled = run(
		bun,
		[
			"build",
			"--target=bun",
			"--format=esm",
			...(split ? ["--splitting"] : []),
			"./.pibolt-plugins/entry.ts",
			"--outdir",
			out,
		],
		{ cwd: agent, env },
	);
	if (bundled.status !== 0 || !existsSync(join(out, "entry.js"))) return nothing;
	const files = readdirSync(out).filter((name) => name.endsWith(".js"));
	const bundle = files.map((name) => readFileSync(join(out, name), "utf8")).join("\n");
	const { pieces: all, names } = bundlePieces(bundle, PLUGIN_SOURCES);
	// (Only the largest functions are measured, by the length of their code: the engine prints each function's bytecode.)
	const pieces = tooBig.topLevel
		? all.filter((piece) => piece.kind === "top-level")
		: all
				.filter((piece) => piece.kind !== "top-level")
				.sort((a, b) => b.code.length - a.code.length)
				.slice(0, 12);
	if (pieces.length === 0) return nothing;
	const sizes = measurePieces(bun, pieces, names, work, env);
	const kb = (bytes: number) => (bytes < 10240 ? `${bytes} bytes` : `${Math.round(bytes / 1024)} KB`);
	const raise = `compile it anyway with \`${SIZE_LIMIT}=${tooBig.size} pi-bolt compile\`, which compile.json keeps for \`pi-bolt update\` (docs/TROUBLESHOOTING.md).`;

	if (!tooBig.topLevel) {
		const ranked = pieces
			.map((piece, index) => ({ piece, size: sizes.get(index) ?? 0 }))
			.sort((a, b) => b.size - a.size);
		const [largest, next] = ranked;
		// (Not one of theirs if theirs are all much smaller than the compiler's figure.)
		if (!largest || largest.size < tooBig.size / 2) return nothing;
		const names = extensionsOfPiece(largest.piece, plan, bun, agent, work);
		const lines = [
			`That function is ${describePiece(largest.piece, plan, names)}: the largest of the extensions' functions, about ${kb(largest.size)} of bytecode as measured outside the compiler (which encodes it shorter)${next ? `, where the next largest is ${kb(next.size)}` : ""}.`,
			names.length > 0
				? `Leave it out with \`pi-bolt compile --exclude ${names.join(",")}\`, or ${raise}`
				: `Leave out the extensions that use it (\`pi-bolt compile --exclude <name>\`), or ${raise}`,
		];
		return { lines, names };
	}

	const shares = new Map<string, number>();
	pieces.forEach((piece, index) => {
		const owner = ownerOfModule(piece.module, plan);
		shares.set(owner, (shares.get(owner) ?? 0) + (sizes.get(index) ?? 0));
	});
	const theirs = [...shares.values()].reduce((sum, size) => sum + size, 0);
	if (theirs === 0) return nothing;
	const ranked = [...shares].sort((a, b) => b[1] - a[1]);
	if (split) {
		// (Each extension is a file of its own, with the modules only it imports: the function is the largest one's.)
		const [[owner, size], next] = ranked;
		const piece = pieces.find((candidate) => ownerOfModule(candidate.module, plan) === owner)!;
		const names = extensionsOfPiece(piece, plan, bun, agent, work);
		const lines = [
			`That is the top-level code of ${names.length > 0 ? `the extension ${names.join(", ")}` : owner}, a file of its own: about ${kb(size)} of bytecode as measured outside the compiler (which encodes it shorter)${next ? `, where the next largest is ${kb(next[1])} (${next[0]})` : ""}.`,
			names.length > 0
				? `Leave it out with \`pi-bolt compile --exclude ${names.join(",")}\`, or ${raise}`
				: `Leave out the extensions that use it (\`pi-bolt compile --exclude <name>\`), or ${raise}`,
		];
		return { lines, names };
	}
	const listed = ranked.slice(0, 12).map(([owner, size]) => `${owner} ${kb(size)}`);
	const more = shares.size - listed.length;
	const lines = [
		`That is the top-level code of the whole program, not of one extension: Pi's own (about ${kb(Math.max(0, tooBig.size - theirs))}) and what each extension adds to it, measured outside the compiler: ${listed.join(", ")}${more > 0 ? ` and ${more} more` : ""}.`,
		`Leave out the extensions with the largest shares (\`pi-bolt compile --exclude <name>\`), or ${raise}`,
	];
	return { lines, names: [] };
}

/** Prints, as JSON, which of the entry points `process.argv[3..]` bundle the module whose path ends with `process.argv[2]`. */
const USES_MODULE = `const [path, ...entries] = process.argv.slice(2);
const found = [];
for (const entry of entries) {
	const result = await Bun.build({ entrypoints: [entry], target: "bun", format: "esm", throw: false });
	const text = result.success ? (await Promise.all(result.outputs.map((output) => output.text()))).join("\\n") : "";
	if (text.split("\\n").some((line) => line.startsWith("// ") && line.endsWith(path))) found.push(entry);
}
process.stdout.write(JSON.stringify(found));
`;

/**
 * The extensions a piece of the bundle is of: the one whose files it is in, or, for a dependency's, each that imports it
 * (each extension bundled on its own).
 */
function extensionsOfPiece(piece: BundlePiece, plan: ManifestPlan, bun: string, agent: string, work: string): string[] {
	const own = extensionsOfModule(piece.module, plan);
	if (own.length > 0 || !piece.module.startsWith(PLUGIN_SOURCES)) return own;
	const env: NodeJS.ProcessEnv = { ...process.env };
	delete env.BUN_BE_BUN;
	delete env.BUN_INTERNAL_SPAWN_HELPER;
	writeFileSync(join(work, "uses-module.mjs"), USES_MODULE);
	const src = dirname(plan.manifest);
	const entries = plan.plugins.map((plugin) => join(src, plugin.importPath));
	const result = run(bun, [join(work, "uses-module.mjs"), piece.module.slice(PLUGIN_SOURCES.length), ...entries], {
		cwd: agent,
		env,
	});
	try {
		const found = JSON.parse(result.stdout ?? "") as string[];
		return plan.plugins.filter((_, index) => found.includes(entries[index])).map((plugin) => plugin.name);
	} catch {
		return [];
	}
}

// --- a bundler error in an extension (#174) --------------------------------------------------------------------------

/** An error of the bundler and where it is: `error: <message>`, then `    at <file>:<line>:<column>` (absolute). */
const BUNDLER_ERROR = /^error: .+\n[ \t]+at (.+):(\d+):\d+$/gm;

/**
 * Which extensions the bundler's errors in `output` are in, and how to leave them out: each then loads from its files, as
 * before it was compiled in. `chosen`: what this compile was asked for (the extensions' names), which the command given
 * replaces. Nothing when no error is in a copy the generator made.
 */
export function bundlerErrorAdvice(
	output: string,
	plan: Pick<ManifestPlan, "plugins">,
	chosen: { only?: string[]; exclude?: string[] },
): string[] {
	const names = new Set<string>();
	const inExtensions: string[] = [];
	const inDependencies: string[] = [];
	for (const match of output.matchAll(BUNDLER_ERROR)) {
		const at = match[1].indexOf(`/${PLUGIN_SOURCES}`);
		if (at === -1) continue;
		const module = match[1].slice(at + 1);
		const file = `${module.slice(PLUGIN_SOURCES.length)}:${match[2]}`;
		const of = extensionsOfModule(module, plan);
		for (const name of of) names.add(name);
		const list = of.length > 0 ? inExtensions : inDependencies;
		if (!list.includes(file)) list.push(file);
	}
	const advice: string[] = [];
	if (names.size > 0) {
		const one = names.size === 1;
		const rest = chosen.only?.filter((name) => !names.has(name));
		const command = rest
			? rest.length > 0 && `pi-bolt compile --only ${rest.join(",")}`
			: `pi-bolt compile --exclude ${[...new Set([...(chosen.exclude ?? []), ...names])].join(",")}`;
		advice.push(
			`${one ? "That is in the extension" : "Those are in the extensions"} ${[...names].join(", ")} (${inExtensions.join(", ")}).` +
				(command
					? ` Leave ${one ? "it" : "them"} out with \`${command}\`: ${one ? "it then loads from its files, as before it was" : "they then load from their files, as before they were"} compiled in.`
					: ""),
		);
	}
	if (inDependencies.length > 0) {
		advice.push(
			`${inDependencies.length === 1 ? "That is in a package" : "Those are in packages"} the extensions depend on (${inDependencies.join(", ")}). Leave out the extensions that use ${inDependencies.length === 1 ? "it" : "them"} with \`pi-bolt compile --exclude <name>\`, by the names listed above.`,
		);
	}
	return advice;
}

// --- an import() the bundler cannot resolve (#160) --------------------------------------------------------------------

/*
 * An extension may load a package it does not depend on with import(), for something it does only when that package is
 * there: @plannotator/pi-extension's providers for the Claude Agent SDK and the OpenCode SDK, whose packages it neither
 * declares nor installs. Loaded from its files, the import() fails when it runs, the extension catches that, and the rest of
 * it works. The bundler instead stops the whole build at an import() it cannot resolve, unless the import() is in a `try`
 * block of its own (which it leaves to run time without a word).
 *
 * So before the build, the bundler is run on the extensions alone (UNRESOLVED: a fraction of a second, nothing written), and
 * each import() of a string it cannot resolve has that string, in the copy, replaced with code that resolves it when it
 * runs, from the file the copy was made from: the import() then finds what it finds loaded from its files (a package
 * installed since, too), and fails as it fails there otherwise. Only import() of a literal is changed: a static `import` or
 * a require() that does not resolve is something the extension needs, and still stops the build with the bundler's error.
 */

/** Prints, as JSON, each import() the bundler could not resolve in a build of the module `process.argv[2]`. */
const UNRESOLVED = `const result = await Bun.build({ entrypoints: [process.argv[2]], target: "bun", format: "esm", splitting: true, throw: false });
const found = [];
for (const log of result.logs) {
	if (log.importKind !== "dynamic-import" || typeof log.specifier !== "string" || !log.position) continue;
	found.push({ specifier: log.specifier, file: log.position.file, line: log.position.line, column: log.position.column });
}
process.stdout.write(JSON.stringify(found));
`;

/** An import() the bundler could not resolve: the string it imports, and where (`line` and `column` count from 1). */
export interface UnresolvedImport {
	specifier: string;
	file: string;
	line: number;
	column: number;
}

/** The file a module of the generator's folder `src` was copied from: an extension's folder, or a dependency's. */
export function originalOfCopy(
	file: string,
	src: string,
	plan: Pick<ManifestPlan, "roots" | "dependencies">,
): string | undefined {
	const rel = relative(src, file);
	if (rel.startsWith("..") || isAbsolute(rel)) return undefined;
	const segments = rel.split(sep);
	if (segments[0] === "node_modules") {
		const length = segments[1]?.startsWith("@") ? 2 : 1;
		const name = segments.slice(1, 1 + length).join("/");
		const dependency = plan.dependencies.find((candidate) => candidate.name === name);
		return dependency && join(dependency.from, ...segments.slice(1 + length));
	}
	const root = plan.roots.find((candidate) => candidate.stageDir === segments[0]);
	return root && join(root.root, ...segments.slice(1));
}

/** `text` as a JavaScript string literal, with the characters that can end a script or a line written as escapes. */
export function jsStringLiteral(text: string): string {
	return JSON.stringify(text).replace(
		/[<>/\u2028\u2029]/g,
		(char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`,
	);
}

/**
 * `source` with the import() of `at` resolving its string when it runs, from the folder `from`, as Bun resolves it for a
 * module there; when it does not resolve, the import() is of the string as it was, and fails as it does in a module loaded
 * from its files. Nothing throws before the import(), which returns its promise as before. Undefined when the string is not
 * found on its line as a literal (the build then stops at it as before). The line keeps its number.
 */
export function importAtRunTime(source: string, at: Omit<UnresolvedImport, "file">, from: string): string | undefined {
	const lines = source.split("\n");
	const text = lines[at.line - 1];
	if (text === undefined) return undefined;
	// (The literal at the column the bundler gives, or the nearest: a line may hold the same string more than once.)
	let found: { index: number; length: number } | undefined;
	for (const quote of ['"', "'", "`"]) {
		const literal = `${quote}${at.specifier}${quote}`;
		for (let index = text.indexOf(literal); index !== -1; index = text.indexOf(literal, index + 1)) {
			if (!found || Math.abs(index - (at.column - 1)) < Math.abs(found.index - (at.column - 1))) {
				found = { index, length: literal.length };
			}
		}
	}
	if (!found) return undefined;
	const specifier = jsStringLiteral(at.specifier);
	const resolved = `(() => { try { return Bun.resolveSync(${specifier}, ${jsStringLiteral(from)}); } catch { return ${specifier}; } })()`;
	lines[at.line - 1] = `${text.slice(0, found.index)}${resolved}${text.slice(found.index + found.length)}`;
	return lines.join("\n");
}

/**
 * Leaves each import() of the extensions' copies that the bundler cannot resolve to run time (above), the extensions'
 * manifest being `plan.manifest`. Returns a line for each, to print. What the bundler stops at otherwise is left for the
 * build to report.
 */
export function leaveUnresolvedImportsToRunTime(
	bun: string,
	plan: Pick<ManifestPlan, "manifest" | "plugins" | "roots" | "dependencies">,
	cwd: string,
	work: string,
): string[] {
	const env: NodeJS.ProcessEnv = { ...process.env };
	delete env.BUN_BE_BUN;
	delete env.BUN_INTERNAL_SPAWN_HELPER;
	writeFileSync(join(work, "unresolved.mjs"), UNRESOLVED);
	// (Through its links, as the bundler names the files: /var is /private/var on macOS.)
	const src = realpathSync(dirname(plan.manifest));
	const left: Array<UnresolvedImport & { module: string }> = [];
	const done = new Set<string>();
	// (Again after a change, in case the bundler reports what it finds after the first it cannot resolve only then.)
	for (let pass = 0; pass < 8; pass++) {
		const result = run(bun, [join(work, "unresolved.mjs"), plan.manifest], { cwd, env });
		let found: unknown;
		try {
			found = JSON.parse(result.stdout ?? "");
		} catch {
			break;
		}
		if (!Array.isArray(found)) break;
		// (Last first: a change to a line then leaves the columns of those before it on that line as they were.)
		const fresh = (found as UnresolvedImport[])
			.filter((at) => !done.has(`${at.file}:${at.line}:${at.column}:${at.specifier}`))
			.sort((a, b) => a.file.localeCompare(b.file) || b.line - a.line || b.column - a.column);
		let changed = false;
		for (const at of fresh) {
			done.add(`${at.file}:${at.line}:${at.column}:${at.specifier}`);
			const original = originalOfCopy(at.file, src, plan);
			if (!original) continue;
			const changedSource = importAtRunTime(
				readFileSync(at.file, "utf8"),
				at,
				dirname(existsSync(original) ? realpathSync(original) : original),
			);
			if (changedSource === undefined) continue;
			// (A copy keeps its file's mode, and an installed file may be read-only.)
			chmodSync(at.file, statSync(at.file).mode | 0o200);
			writeFileSync(at.file, changedSource);
			changed = true;
			left.push({ ...at, module: `${PLUGIN_SOURCES}${relative(src, at.file).split(sep).join("/")}` });
		}
		if (!changed) break;
	}
	return left
		.sort((a, b) => a.module.localeCompare(b.module) || a.line - b.line || a.column - b.column)
		.map(
			(at) =>
				`${ownerOfModule(at.module, plan)}: import("${at.specifier}") does not resolve: left to run time, as loaded from its files (${at.module.slice(PLUGIN_SOURCES.length)}:${at.line})`,
		);
}

// --- an extension the bundler cannot build, or whose module throws compiled in -------------------------------------------

/*
 * Pi loads each extension on its own: one that does not load fails alone, with Pi's error, and the others load. A build of
 * them all stopped at the first one the bundler could not build (a static import of a package that is not installed, say),
 * and an extension whose top-level code threw in the build (it read the staged copy, which is gone) stopped every start of
 * it. So each such extension is left out of the build instead, with a line that says
 * why, and loads from its files as Pi loads it: before the build, each the bundler reports an error in (BUNDLE_ERRORS); after
 * it, each whose module throws in the new executable (checkCompiledStart). They are found each time, not recorded.
 */

/**
 * Prints, as JSON, the errors the bundler reports for each of `process.argv[2..]` (an import() that does not resolve is
 * #160's, above). Each entry point is built on its own when there are several: an error in a dependency the extensions
 * share is then found under each that imports it.
 */
const BUNDLE_ERRORS = `const found = [];
for (const entry of process.argv.slice(2)) {
	const result = await Bun.build({ entrypoints: [entry], target: "bun", format: "esm", splitting: true, throw: false });
	for (const log of result.logs) {
		if (log.level !== "error" || log.importKind === "dynamic-import") continue;
		const at = log.position;
		found.push({ entry, message: String(log.message).split("\\n")[0], specifier: typeof log.specifier === "string" ? log.specifier : undefined, file: at?.file, line: at?.line });
	}
}
process.stdout.write(JSON.stringify(found));
`;

/** An error the bundler reports in an extension's code: what it says, and where (`line` counts from 1). */
export interface BundleError {
	entry: string;
	message: string;
	/** The string an import or require() that does not resolve imports. */
	specifier?: string;
	file?: string;
	line?: number;
}

/** An extension left out of the build, and why: it loads from its files. */
export interface LeftOut {
	name: string;
	origin: string;
	reason: string;
}

/** The line for an extension left out of the build. */
export function leftOutLine(left: LeftOut): string {
	return `${left.name} (${left.origin}): not compiled in: ${left.reason}: it loads from its files`;
}

/** Why an extension is left out for a bundler error: what does not resolve, or the error, and where in its installed files. */
export function bundleErrorReason(error: BundleError, original: string | undefined): string {
	const where = original ? ` (${original}${error.line ? `:${error.line}` : ""})` : "";
	return error.specifier !== undefined && /^Could not resolve/.test(error.message)
		? `"${error.specifier}" does not resolve${where}`
		: `${error.message.replace(/\s*Maybe you need to "bun install"\?/, "")}${where}`;
}

/**
 * The extensions of `plan` the bundler cannot build, each with the first error it reports in it (the installed file named);
 * none when it builds. An error in a module of one extension is that extension's; one elsewhere (a dependency the extensions
 * share) is found by building each extension on its own.
 */
export function extensionsThatDoNotBundle(
	bun: string,
	plan: Pick<ManifestPlan, "manifest" | "plugins" | "roots" | "dependencies">,
	cwd: string,
	work: string,
): LeftOut[] {
	const env: NodeJS.ProcessEnv = { ...process.env };
	delete env.BUN_BE_BUN;
	delete env.BUN_INTERNAL_SPAWN_HELPER;
	writeFileSync(join(work, "bundle-errors.mjs"), BUNDLE_ERRORS);
	const src = realpathSync(dirname(plan.manifest));
	const errorsOf = (entries: string[]): BundleError[] => {
		const result = run(bun, [join(work, "bundle-errors.mjs"), ...entries], { cwd, env });
		try {
			const found = JSON.parse(result.stdout ?? "");
			return Array.isArray(found) ? (found as BundleError[]) : [];
		} catch {
			return [];
		}
	};
	const moduleOf = (file: string | undefined) => {
		const rel = file ? relative(src, file) : "..";
		return rel.startsWith("..") || isAbsolute(rel) ? undefined : `${PLUGIN_SOURCES}${rel.split(sep).join("/")}`;
	};
	const reasons = new Map<string, string>();
	const reasonOf = (error: BundleError) =>
		bundleErrorReason(error, error.file ? (originalOfCopy(error.file, src, plan) ?? error.file) : undefined);
	let unplaced = false;
	for (const error of errorsOf([plan.manifest])) {
		const module = moduleOf(error.file);
		const names = module ? extensionsOfModule(module, plan) : [];
		if (names.length === 0) unplaced = true;
		for (const name of names) if (!reasons.has(name)) reasons.set(name, reasonOf(error));
	}
	if (unplaced) {
		const rest = plan.plugins.filter((plugin) => !reasons.has(plugin.name));
		const entries = rest.map((plugin) => join(src, plugin.importPath));
		for (const error of errorsOf(entries)) {
			const plugin = rest[entries.indexOf(error.entry)];
			if (plugin && !reasons.has(plugin.name)) reasons.set(plugin.name, reasonOf(error));
		}
	}
	return plan.plugins
		.filter((plugin) => reasons.has(plugin.name))
		.map((plugin) => ({ name: plugin.name, origin: plugin.origin, reason: reasons.get(plugin.name)! }));
}

/** Writes the manifest of `plan` again (the kit's generator's renderManifest), for the extensions it has now. */
const RENDER = `import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
const { renderManifest } = await import(pathToFileURL(process.argv[2]).href);
const plan = JSON.parse(readFileSync(process.argv[3], "utf8"));
writeFileSync(plan.manifest, renderManifest(plan));
`;

/** `plan` without the extensions `leftOut` names, its manifest written again with the kit's generator. */
function leaveOut(
	plan: ManifestPlan,
	leftOut: readonly LeftOut[],
	bun: string,
	kitDir: string,
	work: string,
): ManifestPlan {
	const names = new Set(leftOut.map((left) => left.name));
	const rest = { ...plan, plugins: plan.plugins.filter((plugin) => !names.has(plugin.name)) };
	writeFileSync(join(work, "render.mjs"), RENDER);
	writeFileSync(join(work, "plan.json"), JSON.stringify(rest));
	const env: NodeJS.ProcessEnv = { ...process.env };
	delete env.BUN_BE_BUN;
	const rendered = run(
		bun,
		[join(work, "render.mjs"), join(kitDir, "gen-plugin-manifest.mjs"), join(work, "plan.json")],
		{
			env,
		},
	);
	if (rendered.status !== 0)
		throw new CompileError(`could not write the list of extensions: ${rendered.stderr.trim()}`);
	return rest;
}

/** How long the check of a new build may take to start with its extensions. */
const CHECK_SECONDS = 120;

/**
 * Starts the new executable as a start does, without a session or a model (`--help` loads the extensions, for their flags):
 * every extension is loaded, the compiled-in ones from the build. Returns the compiled-in extensions that failed there and
 * loaded from their files instead, with why (the ENTRY's PIBOLT_COMPILE_CHECK report). Throws when the start itself fails:
 * the build is not installed.
 */
function checkCompiledStart(executable: string, work: string): Array<{ name: string; failed: string }> {
	const report = join(work, "compile-check.json");
	rmSync(report, { force: true });
	const started = spawnSync(executable, ["--offline", "--help"], {
		cwd: work,
		encoding: "utf8",
		stdio: ["ignore", "pipe", "pipe"],
		maxBuffer: 64 << 20,
		timeout: CHECK_SECONDS * 1000,
		env: { ...process.env, PIBOLT_COMPILE_CHECK: report },
	});
	const said = `${started.stdout ?? ""}${started.stderr ?? ""}`.trim().split("\n").slice(-12).join("\n");
	if (started.error || started.signal || started.status !== 0 || !existsSync(report)) {
		const how = started.error
			? `it did not finish in ${CHECK_SECONDS} s`
			: started.signal
				? `it was stopped by ${started.signal}`
				: !existsSync(report)
					? "it stopped while it loaded its extensions"
					: `it exited with ${started.status}`;
		throw new CompileError(
			`the new executable does not start with its extensions (\`pi --help\`: ${how}), so it is not installed:\n${said || "(it printed nothing)"}`,
		);
	}
	const extensions = JSON.parse(readFileSync(report, "utf8")) as Array<{
		name: string;
		loaded: boolean;
		failed?: string;
	}>;
	return extensions.flatMap(({ name, failed }) => (failed === undefined ? [] : [{ name, failed }]));
}

/**
 * Builds Pi-Bolt with the extensions of `plan` into `stage`, a folder like this build's. A failed build leaves all of
 * what the compiler printed in `log`, outside the work folder that is removed after it. `chosen`: what the compile was asked
 * for, for the advice on leaving out an extension the bundler stopped at.
 */
function buildExecutable(
	kit: Kit,
	kitDir: string,
	bun: string,
	plan: ManifestPlan,
	stage: string,
	buildDir: string,
	log: string,
	kinds: ReadonlyMap<string, CompilePackage["kind"]>,
	maximumBytecodeSize: number | undefined,
	chosen: { only?: string[]; exclude?: string[] },
): void {
	const build = PIBOLT!;
	const agent = join(kitDir, kit.agent);
	const staging = join(agent, ".pibolt-plugins");
	const sources = Object.fromEntries(plan.plugins.map((plugin) => [plugin.name, sourceOf(plugin, plan.roots, kinds)]));
	writeFileSync(join(staging, "sources.json"), `${JSON.stringify(sources, null, "\t")}\n`);
	writeFileSync(join(staging, "entry.ts"), ENTRY);

	rmSync(stage, { recursive: true, force: true });
	mkdirSync(stage, { recursive: true });
	const darwin = process.platform === "darwin";
	const executable = join(stage, darwin ? "pi-bin" : "pi");
	const args = [
		...kit.args.map((arg) => arg.split(KIT_FOLDER).join(kitDir)),
		"--define",
		// (The release's runtime, so its runtime stamp too.)
		`PIBOLT_BUILD="${build.version} ${build.variant} jit-${build.jit ? "on" : "off"}${build.runtimeStamp ? ` ${build.runtimeStamp}` : ""}"`,
		"./.pibolt-plugins/entry.ts",
		...kit.entries.slice(1),
		"--outfile",
		executable,
	];
	process.stderr.write(chalk.dim(`compiling Pi ${kit.pi} with ${plan.plugins.length} extension(s)\n`));
	const result = run(bun, args, { cwd: agent, env: buildEnvironment(kit, kitDir, maximumBytecodeSize) });
	if (result.status !== 0 || !existsSync(executable)) {
		const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
		writeFileSync(log, output);
		const how = result.error
			? `the compiler did not run: ${result.error.message}\n`
			: result.signal
				? `the compiler was stopped by ${result.signal}\n`
				: "";
		const tooBig = functionTooBig(output);
		// (Not over a limit the command was given.)
		if (tooBig && !process.env[SIZE_LIMIT] && canRaiseLimit(tooBig.size)) throw new TooBigToCompile([], tooBig.size);
		const explained = tooBig
			? explainTooBig(tooBig, plan, bun, agent, dirname(stage), kit.args.includes("--splitting"))
			: { lines: [], names: [] };
		// (A function of these extensions is too big: they are left out, and the build goes on without them.)
		if (tooBig && explained.names.length > 0 && explained.names.length < plan.plugins.length) {
			throw new TooBigToCompile(explained.names, tooBig.size);
		}
		const advice = bundlerErrorAdvice(output, plan, chosen).map((line) => `\n${line}`);
		throw new CompileError(
			`the build stopped:\n${how}${buildFailureReason(output, explained.lines)}${advice.join("")}\n(all of what the compiler printed: ${log})`,
		);
	}
	// Everything else is this build's: the macOS launcher and pi-spawn, Pi's documentation, themes and native modules.
	const own = new Set(darwin ? ["pi-bin", "pi-bin.hot"] : ["pi"]);
	for (const name of readdirSync(buildDir)) {
		if (!own.has(name)) cpSync(join(buildDir, name), join(stage, name), { recursive: true, verbatimSymlinks: true });
	}
	const about = existsSync(join(buildDir, "pi-bolt.txt"))
		? readFileSync(join(buildDir, "pi-bolt.txt"), "utf8").split("\n")[0]
		: `Pi-Bolt ${build.version}`;
	writeFileSync(
		join(stage, "pi-bolt.txt"),
		`${about}\nExtensions compiled in (pi-bolt compile, ${new Date().toISOString().slice(0, 10)}): ${plan.plugins.map((p) => p.name).join(", ")}\n`,
	);
	// The check build-pi.sh makes: that the executable runs, as this version, from its compiled code.
	const check = run(join(stage, "pi"), ["--version"], { env: { ...process.env, BUN_STATIC_HEAP_VERBOSE: "1" } });
	const said = `${check.stdout}${check.stderr}`;
	if (check.status !== 0 || !said.includes("image registered: true") || !said.includes(`Pi-Bolt ${build.version},`)) {
		throw new CompileError(`the new executable does not run as it should:\n${said.trim()}`);
	}
}

/** Puts `stage` in place of `current`; the build there is kept as <current>.previous, or (after an update) removed. */
function install(stage: string, current: string, keepPrevious: boolean): void {
	const previous = `${current}.previous`;
	if (keepPrevious) {
		rmSync(previous, { recursive: true, force: true });
		renameSync(current, previous);
		renameSync(stage, current);
		return;
	}
	const replaced = `${current}.replaced-${process.pid}`;
	renameSync(current, replaced);
	renameSync(stage, current);
	rmSync(replaced, { recursive: true, force: true });
}

function readRecord(installDir: string): CompileRecord | undefined {
	try {
		return JSON.parse(readFileSync(join(installDir, "compile.json"), "utf8")) as CompileRecord;
	} catch {
		return undefined;
	}
}

/** What a compile takes: the extensions --only names and those it leaves out, by the names the plan gives them. */
export interface Selection {
	only?: string[];
	exclude: string[];
	/** Those of `exclude` compile.json records (and this command does not name). */
	recorded: string[];
	/** Those compile.json records as excluded that no extension here is named any more. */
	stale: string[];
	/** Those of an update's --only, compile.json's, that no extension here is named any more: left out. */
	staleOnly: string[];
	/** The values given that name no extension found. */
	unknown: string[];
}

/**
 * What a compile takes of the extensions `found` (#174). An update compiles what was asked for before: compile.json's
 * --only and exclusions. Otherwise compile.json's exclusions stand, less those --include names, and --exclude adds to
 * them; --no-exclude drops them, and --only chooses without them. The values given may name an extension by any of its
 * names (namesPlugin); what is recorded is the names the plan gives.
 */
export function chooseExtensions(
	found: PluginPlan["found"],
	options: Pick<CompileOptions, "only" | "exclude" | "include" | "noExclude" | "afterUpdate">,
	record: CompileSelection | undefined,
): Selection {
	const known = (value: string) => found.some((plugin) => namesPlugin(value, plugin));
	// (An update's --only is compile.json's: one of those removed since is left out, not an error, or no update would
	// compile anything in again.)
	const savedOnly = options.afterUpdate ? (options.only ?? []) : [];
	const given = [
		...(options.afterUpdate ? [] : (options.only ?? [])),
		...(options.exclude ?? []),
		...(options.include ?? []),
	];
	const unknown = given.filter((value) => !known(value));
	const named = (values: string[] = []) =>
		found.filter((plugin) => values.some((value) => namesPlugin(value, plugin))).map((plugin) => plugin.name);
	const kept = options.afterUpdate || !(options.noExclude || options.only?.length) ? (record?.exclude ?? []) : [];
	const included = named(options.include);
	const stale = kept.filter((name) => !found.some((plugin) => plugin.name === name));
	const recorded = kept.filter((name) => !stale.includes(name) && !included.includes(name));
	const own = named(options.exclude);
	return {
		...(options.only?.length ? { only: named(options.only) } : {}),
		exclude: [...new Set([...recorded, ...own])],
		recorded: recorded.filter((name) => !own.includes(name)),
		stale,
		staleOnly: savedOnly.filter((value) => !known(value)),
		unknown,
	};
}

/** Whether an update should compile extensions into the release it installed (`pi-bolt compile` was run here). */
export function piBoltCompileWanted(installDir: string): boolean {
	return existsSync(join(installDir, "compile.json"));
}

/**
 * The extensions a compile takes, planned with the generator's own planPlugins before anything is downloaded (#174): each
 * printed with the name it gets, the names given checked against them, and a compile that would take none stopped.
 * `record`: compile.json, if there is one.
 */
async function planCompile(
	options: CompileOptions,
	record: CompileSelection | undefined,
): Promise<{ agentDir: string; packages: CompilePackages; plan: PluginPlan; selection: Selection }> {
	const agentDir = getAgentDir();
	// The packages as Pi-Bolt loads them, not the `packages` setting the generator reads by itself; and the other
	// extensions, with those the `extensions` setting turns off left out, as Pi leaves them out.
	const resolved = await resolveCompilePackages(agentDir);
	const packages: CompilePackages = options.noPackages
		? { packages: [], passedOver: [], removed: [], enabled: resolved.enabled }
		: resolved;
	const planOptions = {
		agentDir,
		packages: packages.packages,
		noPackages: options.noPackages,
		enabled: packages.enabled,
	};
	const selection = chooseExtensions(planPlugins(planOptions).found, options, record);
	if (selection.recorded.length > 0) {
		const how = options.afterUpdate ? "" : ` (--include ${selection.recorded.join(",")} compiles them in)`;
		process.stderr.write(
			chalk.dim(`leaving out what compile.json records as excluded: ${selection.recorded.join(", ")}${how}\n`),
		);
	}
	if (selection.staleOnly.length > 0) {
		process.stderr.write(
			chalk.yellow(
				`compile.json names in --only ${selection.staleOnly.join(", ")}, which no extension here is named\n`,
			),
		);
		if (selection.only?.length === 0) {
			throw new CompileError(
				`none of the extensions compile.json names in --only is here any more: run pi-bolt compile to choose again`,
			);
		}
	}
	if (selection.stale.length > 0) {
		process.stderr.write(
			chalk.dim(
				`compile.json records as excluded ${selection.stale.join(", ")}, which no extension here is named\n`,
			),
		);
	}
	const plan = planPlugins({ ...planOptions, only: selection.only, exclude: selection.exclude });
	candidateLines(plan, packages, selection.recorded).forEach((line, index) => {
		process.stderr.write(`  ${index < plan.plugins.length ? line : chalk.dim(line)}\n`);
	});
	// (The values as given: the plan was made with the names they gave.)
	const problem = selectionProblem({ ...plan, unknown: { only: [], exclude: selection.unknown } }, agentDir);
	if (problem) throw new CompileError(problem);
	return { agentDir, packages, plan, selection };
}

async function compile(options: CompileOptions, record: CompileSelection | undefined): Promise<void> {
	const build = PIBOLT!;
	const { agentDir, packages, plan: planned, selection } = await planCompile(options, record);
	if (options.list) return;
	const where = options.out ? undefined : installation();
	const platform = platformName();
	const workParent = where?.installDir ?? dirname(options.out!);
	mkdirSync(workParent, { recursive: true });
	// (Beside the installation: the new build is then moved into place on the same file system.)
	const work = mkdtempSync(join(workParent, ".compile-"));
	try {
		let kitDir: string;
		let bun: string;
		if (options.kit && options.runtime) {
			kitDir = localFile(options.kit, work, "kit");
			bun = runtimeBun(localFile(options.runtime, work, "runtime"));
		} else {
			if (options.kit || options.runtime) throw new CompileError("--kit and --runtime go together");
			const base =
				process.env.PIBOLT_DOWNLOAD_BASE?.replace(/\/+$/, "") ??
				`${PIBOLT_RELEASES_URL}/download/bolt-v${build.version}`;
			const sums = await download(`${base}/SHA256SUMS`);
			const signature = await download(`${base}/SHA256SUMS.sig`);
			const sha = checkReleaseSums(sums, signature, build.version);
			const cacheDir = join(where?.installDir ?? work, "cache");
			const runtimeArchive = await releaseFile(base, `pi-bolt-runtime-${platform}.tar.gz`, sha, cacheDir);
			const kitArchive = await releaseFile(base, `pi-bolt-kit-${platform}.tar.gz`, sha, cacheDir);
			pruneCache(cacheDir, [runtimeArchive, kitArchive]);
			untar(runtimeArchive, join(work, "runtime"));
			untar(kitArchive, join(work, "kit"));
			bun = runtimeBun(join(work, "runtime", `pi-bolt-runtime-${platform}`));
			kitDir = join(work, "kit", `pi-bolt-kit-${platform}`);
		}
		kitDir = realpathSync(kitDir);
		const kit = JSON.parse(readFileSync(join(kitDir, "kit.json"), "utf8")) as Kit;
		if (kit.piBolt !== build.version || kit.platform !== platform) {
			throw new CompileError(
				`the kit is for Pi-Bolt ${kit.piBolt} on ${kit.platform}, and this is Pi-Bolt ${build.version} on ${platform}`,
			);
		}

		// The extensions installed here, copied into the kit's tree with a manifest (the kit's gen-plugin-manifest.mjs, this release's).
		const staging = join(kitDir, kit.agent, ".pibolt-plugins");
		rmSync(staging, { recursive: true, force: true });
		mkdirSync(staging, { recursive: true });
		const generatorArgs = [join(kitDir, "gen-plugin-manifest.mjs"), "--out", join(staging, "src"), "--json"];
		generatorArgs.push("--agent-dir", agentDir);
		if (selection.only?.length) generatorArgs.push("--only", selection.only.join(","));
		if (selection.exclude.length > 0) generatorArgs.push("--exclude", selection.exclude.join(","));
		if (options.noPackages) generatorArgs.push("--no-packages");
		else {
			const list = join(work, "packages.json");
			writeFileSync(list, JSON.stringify(packages.packages));
			generatorArgs.push("--packages", list);
		}
		writeFileSync(join(work, "enabled.json"), JSON.stringify(packages.enabled));
		generatorArgs.push("--enabled", join(work, "enabled.json"));
		// (Without the JIT, a package's Bun build is not what Pi loads from its files: --no-jit in gen-plugin-manifest.mjs.)
		if (!build.jit) generatorArgs.push("--no-jit");
		const kinds = new Map(packages.packages.map((pkg) => [resolve(pkg.root), pkg.kind]));
		const generatorEnv = { ...process.env };
		delete generatorEnv.BUN_BE_BUN;
		const generated = run(bun, generatorArgs, { env: generatorEnv });
		if (generated.status !== 0) throw new CompileError(generated.stderr.trim().replace(/^error: /, ""));
		let plan = JSON.parse(generated.stdout) as ManifestPlan;
		// (The kit's generator is this release's, as planPlugins is: it finds the same.)
		if (
			plan.plugins.map((plugin) => plugin.name).join("\n") !==
			planned.plugins.map((plugin) => plugin.name).join("\n")
		) {
			throw new CompileError(
				`the kit's generator found other extensions to compile in: ${plan.plugins.map((plugin) => plugin.name).join(", ")}`,
			);
		}
		// Each extension it cannot compile in is left out, named as --exclude takes it, and loads from its files.
		const leftOut: LeftOut[] = [];
		const leave = (left: LeftOut[]) => {
			for (const one of left) process.stderr.write(`  ${chalk.yellow(leftOutLine(one))}\n`);
			leftOut.push(...left);
			if (left.length === plan.plugins.length) {
				throw new CompileError("none of the extensions can be compiled in (above): they load from their files");
			}
			plan = leaveOut(plan, left, bun, kitDir, work);
		};
		const agent = join(kitDir, kit.agent);
		for (const line of leaveUnresolvedImportsToRunTime(bun, plan, agent, work)) {
			process.stderr.write(`  ${chalk.dim(line)}\n`);
		}
		// (Again until none is left: the bundler reports an import of a name a module does not export only once every import
		// resolves.)
		for (let pass = 0; pass < 8; pass++) {
			const doNotBundle = extensionsThatDoNotBundle(bun, plan, agent, work);
			if (doNotBundle.length === 0) break;
			leave(doNotBundle);
		}

		const buildDir = where?.buildDir ?? dirname(process.execPath);
		const stage = join(work, basename(buildDir));
		// (Built again without the extensions whose module throws in the build, until none does: once, as a rule.)
		for (let attempt = 0; ; attempt++) {
			try {
				buildExecutable(
					kit,
					kitDir,
					bun,
					plan,
					stage,
					buildDir,
					join(workParent, "compile.log"),
					kinds,
					options.maximumBytecodeSize,
					selection,
				);
			} catch (error) {
				if (!(error instanceof TooBigToCompile) || attempt >= 4) throw error;
				const limit = options.maximumBytecodeSize ?? DEFAULT_SIZE_LIMIT;
				if (error.names.length === 0) {
					// (Kept in compile.json, as one the command was given: `pi-bolt update` then builds with it at once.)
					options.maximumBytecodeSize = error.size;
					process.stderr.write(
						chalk.dim(
							`a function is ${error.size} bytes of bytecode, over the compiler's limit of ${limit}: building again with ${SIZE_LIMIT}=${error.size} (a minute or two more)\n`,
						),
					);
					continue;
				}
				leave(
					error.names.map((name) => ({
						name,
						origin: plan.plugins.find((plugin) => plugin.name === name)?.origin ?? "?",
						reason: `a function of it is too big for the compiler (${error.size} bytes of bytecode, over ${limit}; \`${SIZE_LIMIT}=${error.size} pi-bolt compile\` compiles it in)`,
					})),
				);
				continue;
			}
			// (Without the copies the build was made from, which are gone once a compile ends: an extension that reads one then
			// fails here as it would at every start.)
			const aside = `${staging}.built`;
			renameSync(staging, aside);
			let failed: ReturnType<typeof checkCompiledStart>;
			try {
				failed = checkCompiledStart(join(stage, "pi"), work);
			} finally {
				renameSync(aside, staging);
			}
			if (failed.length === 0) break;
			const left = failed.map(({ name, failed: error }) => ({
				name,
				origin: plan.plugins.find((plugin) => plugin.name === name)?.origin ?? "?",
				reason: `it fails compiled in (${error})`,
			}));
			if (attempt >= 2) {
				throw new CompileError(
					`the new executable's extensions still fail to load:\n${left.map(leftOutLine).join("\n")}\nLeave them out with \`pi-bolt compile --exclude <name>\`.`,
				);
			}
			leave(left);
		}

		const names = plan.plugins.map((plugin) => plugin.name);
		const leftNote = leftOut.length > 0 ? ` (${leftOut.length} left to load from their files, above)` : "";
		if (options.out) {
			rmSync(options.out, { recursive: true, force: true });
			renameSync(stage, options.out);
			console.log(
				chalk.green(
					`Built Pi-Bolt ${build.version} with ${names.join(", ")} compiled in${leftNote}: ${options.out}`,
				),
			);
			return;
		}
		install(stage, where!.buildDir, !options.afterUpdate);
		const record: CompileRecord = {
			version: build.version,
			extensions: names,
			...(selection.only?.length ? { only: selection.only } : {}),
			...(selection.exclude.length > 0 ? { exclude: selection.exclude } : {}),
			...(options.noPackages ? { noPackages: true } : {}),
			// (The compiler's own limit is not recorded: setting it so is how a raised one is taken back.)
			...(options.maximumBytecodeSize !== undefined && options.maximumBytecodeSize !== DEFAULT_SIZE_LIMIT
				? { maximumBytecodeSize: options.maximumBytecodeSize }
				: {}),
		};
		writeFileSync(join(where!.installDir, "compile.json"), `${JSON.stringify(record, null, "\t")}\n`);
		console.log(chalk.green(`Compiled into Pi-Bolt ${build.version}: ${names.join(", ")}${leftNote}`));
		if (!options.afterUpdate) {
			console.log(chalk.dim(`The build before is kept: pi-bolt update --rollback puts it back.`));
		}
	} finally {
		rmSync(work, { recursive: true, force: true });
	}
}

function status(): void {
	const { installDir, buildDir } = installation();
	const record = readRecord(installDir);
	const about = existsSync(join(buildDir, "pi-bolt.txt")) ? readFileSync(join(buildDir, "pi-bolt.txt"), "utf8") : "";
	const line = about.split("\n").find((l) => l.startsWith("Extensions compiled in"));
	console.log(line ?? "No extensions are compiled into this Pi-Bolt.");
	for (const state of compiledStateLines(compiledExtensionList(), record)) console.log(chalk.yellow(state));
}

/**
 * `pi-bolt compile --off`: the release's executable back (from the installer, which does not compile anything in for it), and no
 * extensions compiled in by later updates. The record goes only once the release is installed: a failed reinstall leaves the
 * compiled build running, and the next update compiling the same extensions into it.
 */
async function off(installAgain: () => Promise<boolean>): Promise<boolean> {
	const { installDir } = installation();
	if (!(await installAgain())) return false;
	rmSync(join(installDir, "compile.json"), { force: true });
	return true;
}

/**
 * `pi-bolt compile [options]`. Returns the exit status. `installAgain` installs this version's release again, without compiling
 * extensions into it.
 */
export async function runPiBoltCompile(args: string[], installAgain: () => Promise<boolean>): Promise<number> {
	const options: CompileOptions = {};
	let mode: "compile" | "status" | "off" = "compile";
	try {
		for (let index = 0; index < args.length; index++) {
			const arg = args[index];
			switch (arg) {
				case "-h":
				case "--help":
					console.log(USAGE);
					return 0;
				case "--only":
					options.only = parseList(args[++index], arg);
					break;
				case "--exclude":
					options.exclude = parseList(args[++index], arg);
					break;
				case "--include":
					options.include = parseList(args[++index], arg);
					break;
				case "--list":
					options.list = true;
					break;
				case "--no-exclude":
					options.noExclude = true;
					break;
				case "--no-packages":
					options.noPackages = true;
					break;
				case "--kit":
				case "--runtime":
				case "--out": {
					const value = args[++index];
					if (!value) throw new CompileError(`${arg} needs a path`);
					// (Absolute: the build runs the runtime in the kit's folder.)
					options[arg.slice(2) as "kit" | "runtime" | "out"] = resolve(value);
					break;
				}
				case "--after-update":
					options.afterUpdate = true;
					break;
				case "--status":
					mode = "status";
					break;
				case "--off":
					mode = "off";
					break;
				default:
					throw new CompileError(`unknown option ${arg}\n\n${USAGE}`);
			}
		}
		if (!PIBOLT) throw new CompileError("compiling extensions in is for Pi-Bolt builds");
		if (process.platform === "win32")
			throw new CompileError("compiling extensions in is not available on Windows yet");
		if (process.env.PREFIX?.includes("com.termux"))
			throw new CompileError("compiling extensions in is not available in Termux");
		const method = piBoltInstallMethod();
		// (--out and --list install nothing.)
		const installs = !options.out && !options.list;
		if (installs && method === "npm") {
			throw new CompileError(
				"this Pi-Bolt was installed with the npm package, whose folder npm owns: install it with the installer to compile extensions in",
			);
		}
		const manager = piBoltPackageManager(method);
		if (installs && manager) {
			throw new CompileError(
				`this Pi-Bolt was installed with ${manager.name}, whose folder ${manager.command} owns: install it with the installer to compile extensions in`,
			);
		}
		if (mode === "status") {
			status();
			return 0;
		}
		if (mode === "off") return (await off(installAgain)) ? 0 : 1;
		const installDir = installs ? installation().installDir : piBoltInstallDir();
		const record = installDir ? readRecord(installDir) : undefined;
		if (options.afterUpdate && !record) return 0;
		if (options.afterUpdate) Object.assign(options, { only: record!.only, noPackages: record!.noPackages });
		options.maximumBytecodeSize = bytecodeSizeLimit(process.env, record);
		if (options.maximumBytecodeSize !== undefined && !process.env[SIZE_LIMIT]) {
			process.stderr.write(
				chalk.dim(
					`compiling with ${SIZE_LIMIT}=${options.maximumBytecodeSize}, as ${join(installDir!, "compile.json")} records (${SIZE_LIMIT}=${DEFAULT_SIZE_LIMIT} takes it back)\n`,
				),
			);
		}
		await compile(options, record);
		return 0;
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		console.error(
			chalk.red(`error: ${error instanceof CompileError ? message : `pi-bolt compile failed: ${message}`}`),
		);
		return 1;
	}
}
