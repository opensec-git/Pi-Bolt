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
 * <folder>.previous (`pi-bolt update --rollback` puts it back). <install>/compile.json keeps what was asked for, and
 * `pi-bolt update` compiles the same extensions into the release it installs.
 *
 * Each extension compiled in stands in for its installed copy while that is unchanged (core/extensions/compiled.ts).
 */
import { spawnSync } from "node:child_process";
import { createHash, verify } from "node:crypto";
import {
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
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import chalk from "chalk";
import { getAgentDir } from "./config.ts";
import { type CompiledExtensionSource, extensionFingerprint } from "./core/extensions/compiled.ts";
import { DefaultPackageManager } from "./core/package-manager.ts";
import { getPackageIdentity } from "./core/package-source.ts";
import { SettingsManager } from "./core/settings-manager.ts";
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
}

interface CompileRecord extends CompileSelection {
	/** The Pi-Bolt version they were compiled into, and the extensions that were. */
	version: string;
	extensions: string[];
}

interface CompileOptions extends CompileSelection {
	/** --no-exclude: none of the exclusions compile.json records. */
	noExclude?: boolean;
	/** `exclude` is compile.json's, applied because the command named none itself. */
	excludeRecorded?: boolean;
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
	return { packages, passedOver, removed };
}

/** The plan gen-plugin-manifest.mjs prints with --json. */
interface ManifestPlan {
	manifest: string;
	plugins: Array<{ name: string; entry: string; origin: string; stageDir: string }>;
	roots: Array<{ stageDir: string; root: string; origin: string }>;
	skipped: Array<{ path: string; reason: string }>;
	/** Those --only or --exclude left out. */
	excluded: Array<{ name: string; origin: string; entry: string; by: "only" | "exclude" }>;
}

/**
 * One line for each extension and package a compile finds: compiled in, passed over and why, or excluded and by what. None
 * goes unmentioned: one missing from the list looks like one that is not there.
 */
export function candidateLines(
	plan: Pick<ManifestPlan, "plugins" | "skipped" | "excluded">,
	packages: Pick<CompilePackages, "passedOver" | "removed">,
	excludeRecorded = false,
): string[] {
	const exclusion = {
		only: "not in --only",
		exclude: excludeRecorded ? "excluded (compile.json)" : "excluded (--exclude)",
	};
	return [
		...plan.plugins.map((plugin) => `${plugin.name} (${plugin.origin}): compiling`),
		...plan.excluded.map((plugin) => `${plugin.name} (${plugin.origin}): ${exclusion[plugin.by]}`),
		...[...packages.passedOver, ...plan.skipped].map((entry) => `${entry.path}: passed over: ${entry.reason}`),
		...packages.removed.map((source) => `${source}: excluded by piBolt.packages`),
	];
}

class CompileError extends Error {}

/** Where kit.json's paths say "the kit's folder" (scripts/make-kit.mjs). */
const KIT_FOLDER = "@KIT@";

const USAGE = `Usage: pi-bolt compile [options]

Compile the extensions installed on this machine into Pi-Bolt: they then run as machine code, like Pi itself, and cost
nothing to load. Each one compiled in is used while its installed copy is unchanged; a changed one is loaded from its files
again until it is compiled in again. \`pi-bolt update\` compiles the same extensions into the release it installs.

Options:
  --only a,b         Only these extensions (by the names this command prints)
  --exclude a,b      Every extension except these. They are recorded: a later \`pi-bolt compile\` without --only or
                     --exclude, and \`pi-bolt update\`, leave them out too
  --no-exclude       Leave out none of the extensions --exclude left out before
  --no-packages      Leave out the packages \`pi-bolt install\` installed
  --off              Go back to the release's executable, with no extensions compiled in
  --status           Show what is compiled in
  --kit FILE         Build with this compile kit instead of the release's (an archive or an unpacked folder)
  --runtime FILE     Build with this runtime instead of the release's (an archive, a folder, or the bun executable)
  --out DIR          Build into DIR and install nothing
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

/** The entry point: Pi's Bun entry (src/bun/cli.ts), with the extensions passed to main() as compiled in. */
const ENTRY = `// Generated by \`pi-bolt compile\`: Pi's Bun entry (packages/coding-agent/src/bun/cli.ts), with extensions compiled in.
import "../dist/bun/sandbox-env-setup.js";
// (\`pi-bolt --version\` answered before the rest of Pi, and the extensions, are loaded: as Pi's own entry does.)
import "../dist/bun/version-fast-path.js";
import "../dist/bun/runtime-setup.js";
import { setupCli } from "../dist/cli/setup.js";
import { main } from "../dist/main.js";
import plugins from "./src/plugins.ts";
import sources from "./sources.json" with { type: "json" };

setupCli();
main(process.argv.slice(2), {
	compiledExtensions: plugins.map((plugin) => ({ ...sources[plugin.name], factory: plugin.factory })),
});
`;

/** The build's environment for this variant: the kit's (the release's default build), with this build's JIT and CPU. */
function buildEnvironment(kit: Kit, kitDir: string): NodeJS.ProcessEnv {
	const build = PIBOLT!;
	const env: NodeJS.ProcessEnv = { ...process.env };
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

/** The compiler's "AOT: " lines that say why a build stops (the others report its progress). */
const AOT_STOP = /cannot be compiled ahead of time|could not be compiled ahead of time|maximumAOTCandidateBytecodeSize/;

/**
 * Why a build stopped, from what the compiler printed: each "AOT: " line that says so (a function too big to compile, or
 * one the compiler declined), and the last lines of the rest. The reason is often an "AOT: " line alone, and those lines
 * are otherwise mostly progress.
 */
export function buildFailureReason(output: string): string {
	const lines = output.split(/\r?\n/).filter((line) => line.trim());
	const indexes = (keep: (line: string) => boolean) => lines.flatMap((line, index) => (keep(line) ? [index] : []));
	const stops = indexes((line) => line.startsWith("AOT: ") && AOT_STOP.test(line)).slice(-22);
	const kept = new Set([...stops, ...indexes((line) => !line.startsWith("AOT: ")).slice(-8)]);
	const reason = lines.filter((_, index) => kept.has(index));
	if (reason.length === 0) return lines.slice(-8).join("\n") || "(the compiler printed nothing)";
	if (stops.some((index) => lines[index].includes("maximumAOTCandidateBytecodeSize"))) {
		reason.push(
			"Leave that extension out with `pi-bolt compile --exclude <name>`, or compile it anyway with " +
				"`BUN_JSC_maximumAOTCandidateBytecodeSize=<n> pi-bolt compile` (docs/TROUBLESHOOTING.md).",
		);
	}
	return reason.join("\n");
}

/**
 * Builds Pi-Bolt with the extensions of `plan` into `stage`, a folder like this build's. A failed build leaves all of
 * what the compiler printed in `log`, outside the work folder that is removed after it.
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
): void {
	const build = PIBOLT!;
	const agent = join(kitDir, kit.agent);
	const staging = join(agent, ".pibolt-plugins");
	const sources = Object.fromEntries(plan.plugins.map((plugin) => [plugin.name, sourceOf(plugin, plan.roots, kinds)]));
	writeFileSync(join(staging, "sources.json"), `${JSON.stringify(sources, null, "\t")}\n`);
	writeFileSync(join(staging, "entry.ts"), ENTRY);

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
	const result = run(bun, args, { cwd: agent, env: buildEnvironment(kit, kitDir) });
	if (result.status !== 0 || !existsSync(executable)) {
		const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
		writeFileSync(log, output);
		const how = result.error
			? `the compiler did not run: ${result.error.message}\n`
			: result.signal
				? `the compiler was stopped by ${result.signal}\n`
				: "";
		throw new CompileError(
			`the build stopped:\n${how}${buildFailureReason(output)}\n(all of what the compiler printed: ${log})`,
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

/**
 * The exclusions compile.json records that a compile takes: all of them after an update, which compiles what was asked for
 * before, and for a compile that chooses nothing itself (no --only, --exclude or --no-exclude). Otherwise none.
 */
export function recordedExclusions(
	options: CompileOptions,
	record: CompileSelection | undefined,
): string[] | undefined {
	if (!record?.exclude?.length) return undefined;
	if (options.afterUpdate) return record.exclude;
	return options.only?.length || options.exclude?.length || options.noExclude ? undefined : record.exclude;
}

/** Whether an update should compile extensions into the release it installed (`pi-bolt compile` was run here). */
export function piBoltCompileWanted(installDir: string): boolean {
	return existsSync(join(installDir, "compile.json"));
}

async function compile(options: CompileOptions): Promise<void> {
	const build = PIBOLT!;
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

		// The extensions installed here, copied into the kit's tree with a manifest (scripts/gen-plugin-manifest.mjs).
		const staging = join(kitDir, kit.agent, ".pibolt-plugins");
		rmSync(staging, { recursive: true, force: true });
		mkdirSync(staging, { recursive: true });
		const generatorArgs = [join(kitDir, "gen-plugin-manifest.mjs"), "--out", join(staging, "src"), "--json"];
		const agentDir = getAgentDir();
		generatorArgs.push("--agent-dir", agentDir);
		if (options.only?.length) generatorArgs.push("--only", options.only.join(","));
		if (options.exclude?.length) generatorArgs.push("--exclude", options.exclude.join(","));
		// The packages as Pi-Bolt loads them, not the `packages` setting the generator reads by itself.
		const packages: CompilePackages = options.noPackages
			? { packages: [], passedOver: [], removed: [] }
			: await resolveCompilePackages(agentDir);
		if (options.noPackages) generatorArgs.push("--no-packages");
		else {
			const list = join(work, "packages.json");
			writeFileSync(list, JSON.stringify(packages.packages));
			generatorArgs.push("--packages", list);
		}
		const kinds = new Map(packages.packages.map((pkg) => [resolve(pkg.root), pkg.kind]));
		const generatorEnv = { ...process.env };
		delete generatorEnv.BUN_BE_BUN;
		const generated = run(bun, generatorArgs, { env: generatorEnv });
		if (generated.status !== 0) throw new CompileError(generated.stderr.trim().replace(/^error: /, ""));
		const plan = JSON.parse(generated.stdout) as ManifestPlan;
		candidateLines(plan, packages, options.excludeRecorded).forEach((line, index) => {
			process.stderr.write(`  ${index < plan.plugins.length ? line : chalk.dim(line)}\n`);
		});

		const buildDir = where?.buildDir ?? dirname(process.execPath);
		const stage = join(work, basename(buildDir));
		buildExecutable(kit, kitDir, bun, plan, stage, buildDir, join(workParent, "compile.log"), kinds);

		const names = plan.plugins.map((plugin) => plugin.name);
		if (options.out) {
			rmSync(options.out, { recursive: true, force: true });
			renameSync(stage, options.out);
			console.log(
				chalk.green(`Built Pi-Bolt ${build.version} with ${names.join(", ")} compiled in: ${options.out}`),
			);
			return;
		}
		install(stage, where!.buildDir, !options.afterUpdate);
		const record: CompileRecord = {
			version: build.version,
			extensions: names,
			...(options.only?.length ? { only: options.only } : {}),
			...(options.exclude?.length ? { exclude: options.exclude } : {}),
			...(options.noPackages ? { noPackages: true } : {}),
		};
		writeFileSync(join(where!.installDir, "compile.json"), `${JSON.stringify(record, null, "\t")}\n`);
		console.log(chalk.green(`Compiled into Pi-Bolt ${build.version}: ${names.join(", ")}`));
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
	if (record && !line)
		console.log(chalk.dim(`pi-bolt update compiles in the extensions chosen before (${installDir}/compile.json).`));
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
					options[arg.slice(2) as "kit" | "runtime" | "out"] = value;
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
		if (!options.out && method === "npm") {
			throw new CompileError(
				"this Pi-Bolt was installed with the npm package, whose folder npm owns: install it with the installer to compile extensions in",
			);
		}
		const manager = piBoltPackageManager(method);
		if (!options.out && manager) {
			throw new CompileError(
				`this Pi-Bolt was installed with ${manager.name}, whose folder ${manager.command} owns: install it with the installer to compile extensions in`,
			);
		}
		if (mode === "status") {
			status();
			return 0;
		}
		if (mode === "off") return (await off(installAgain)) ? 0 : 1;
		if (options.exclude?.length && options.noExclude)
			throw new CompileError("--exclude and --no-exclude go against each other");
		const installDir = options.out ? piBoltInstallDir() : installation().installDir;
		const record = installDir ? readRecord(installDir) : undefined;
		if (options.afterUpdate && !record) return 0;
		const recorded = recordedExclusions(options, record);
		if (options.afterUpdate) Object.assign(options, { only: record!.only, noPackages: record!.noPackages });
		if (recorded) {
			options.exclude = recorded;
			options.excludeRecorded = true;
			process.stderr.write(
				chalk.dim(
					`leaving out what ${join(installDir!, "compile.json")} records as excluded: ${recorded.join(", ")}${options.afterUpdate ? "" : " (--no-exclude compiles them in)"}\n`,
				),
			);
		}
		await compile(options);
		return 0;
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		console.error(
			chalk.red(`error: ${error instanceof CompileError ? message : `pi-bolt compile failed: ${message}`}`),
		);
		return 1;
	}
}
