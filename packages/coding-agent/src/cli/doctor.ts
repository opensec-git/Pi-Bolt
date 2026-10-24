/**
 * `pi-bolt doctor`: a report of this Pi-Bolt and what it loads, in plain text to paste into an issue. It says which build this
 * is and how it was installed, whether its release's signature verifies, which extensions are compiled in, run at run time or
 * fail, how Pi's and Pi-Bolt's package lists differ, the Herdr and editor (ACP) setup, and what is known to be wrong.
 *
 * It installs nothing and changes no setting. It loads the extensions that a start would load (their factories run, as at any
 * start; no session starts), and it goes online for the release's signature and the latest version unless asked not to.
 * Nothing secret is printed: settings' values are not, environment variables only from a list of Pi-Bolt's own, credentials in
 * URLs and anything shaped like a key are masked, and the home folder is shown as ~.
 *
 * Only Pi-Bolt builds have the command (main.ts); the gathering here is split from the formatting, which tests check.
 */

import { verify } from "node:crypto";
import { existsSync, readdirSync, readFileSync, realpathSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import { arch, cpus, homedir, release, totalmem } from "node:os";
import { basename, delimiter, dirname, extname, isAbsolute, join, relative } from "node:path";
import { CONFIG_DIR_NAME, VERSION } from "../config.ts";
import { createEventBus } from "../core/event-bus.ts";
import { isJitRequiredMessage, JIT_REQUIRED_MESSAGE, jitHint } from "../core/extensions/jit-hint.ts";
import { createExtensionRuntime, JIT_ONLY_PACKAGES, loadExtensions } from "../core/extensions/loader.ts";
import type { InlineExtension } from "../core/extensions/types.ts";
import { DefaultPackageManager, type ResolvedResource } from "../core/package-manager.ts";
import { isBuiltinExtension } from "../core/resource-loader.ts";
import { SettingsManager } from "../core/settings-manager.ts";
import { BUILTIN_PATH_PREFIX } from "../core/source-info.ts";
import { hasTrustRequiringProjectResources, ProjectTrustStore } from "../core/trust-manager.ts";
import { isHerdrPiIntegration } from "../extensions/herdr/integration-file.ts";
import { herdrFromEnvironment } from "../extensions/herdr/transport.ts";
import {
	PIBOLT,
	PIBOLT_RELEASE_KEY,
	PIBOLT_RELEASES_URL,
	type PiBoltPackageManagerId,
	piBoltInstallDir,
	piBoltInstallMethod,
	piBoltJitSwitchCommand,
	piBoltPackageManager,
	piBoltUpdateCommand,
} from "../pi-bolt.ts";
import { findExecutableOnPath, spawnProcessSync } from "../utils/child-process.ts";
import { getLatestPiRelease, isNewerPackageVersion } from "../utils/version-check.ts";

export interface DoctorSection {
	title: string;
	rows: Array<[label: string, value: string]>;
}

export interface DoctorReport {
	sections: DoctorSection[];
	problems: string[];
	suggestions: string[];
}

export interface DoctorOptions {
	cwd: string;
	agentDir: string;
	/** The extensions compiled into this executable (Pi's built-in ones and any plugins). */
	extensionFactories: readonly InlineExtension[];
}

const USAGE = "pi-bolt doctor [--offline]";
/** An extension loaded at run time that takes this much CPU to load does heavy JavaScript work: the JIT build runs it faster. */
const HEAVY_EXTENSION_CPU_MS = 250;
const EXTENSION_LOAD_TIMEOUT_MS = 15_000;
const NETWORK_TIMEOUT_MS = 15_000;

export async function runDoctor(args: readonly string[], options: DoctorOptions): Promise<number> {
	if (args.includes("-h") || args.includes("--help")) {
		console.log(`Usage: ${USAGE}

Prints a report of this Pi-Bolt: its build and installation, whether its release's signature verifies, the extensions
it loads (compiled in, run at run time, failing), Pi's and Pi-Bolt's package lists, Herdr and editor (ACP) setup, and
problems found. Plain text without keys or tokens, to paste into an issue.

Options:
  --offline   Skip what goes online (the release's signature, the latest version)`);
		return 0;
	}
	const unknown = args.find((arg) => arg !== "--offline");
	if (unknown !== undefined) {
		console.error(`Unknown argument for doctor: ${unknown}\nUsage: ${USAGE}`);
		return 1;
	}
	const offline = args.includes("--offline") || Boolean(process.env.PI_OFFLINE);
	const report: DoctorReport = { sections: [], problems: [], suggestions: [] };
	// (Started first: they are answered while the extensions load.)
	const signature = offline ? Promise.resolve(undefined) : checkReleaseSignature();
	const latest = offline ? Promise.resolve(undefined) : latestRelease();

	const self = selfInfo();
	report.sections.push(buildSection(self, report));
	report.sections.push(await installSection(self, signature, latest, offline, report));
	report.sections.push(systemSection());
	const extensions = await extensionSections(options, self, report);
	report.sections.push(...extensions);
	const home = homedir();
	process.stdout.write(redactReport(formatDoctorReport(report), [home, realpathOr(home)]));
	return 0;
}

// --- This executable ------------------------------------------------------------------------------------------------

interface SelfInfo {
	/** The executable that runs (on macOS pi-bin, beside the launcher pi). */
	executable: string;
	/** What to start to run this Pi-Bolt again: the launcher on macOS (and in Termux), else the executable. */
	command: string;
	folder: string;
	method: "npm" | PiBoltPackageManagerId | "installer" | "manual" | "not installed";
	installDir: string | undefined;
}

function selfInfo(): SelfInfo {
	const executable = realpathOr(process.execPath);
	const folder = dirname(executable);
	const launcher = ["pi-termux", ...(process.platform === "darwin" ? ["pi"] : [])]
		.map((name) => join(folder, name))
		.find((path) => existsSync(path));
	const installDir = piBoltInstallDir();
	const installMethod = piBoltInstallMethod();
	let method: SelfInfo["method"];
	if (installMethod !== "installer") method = installMethod;
	else if (installDir === undefined) method = "not installed";
	else method = installedByInstaller(installDir, folder) ? "installer" : "manual";
	return { executable, command: launcher ?? executable, folder, method, installDir };
}

/**
 * Whether the installer put this installation where it is: its own folder (~/.pi-bolt, or PIBOLT_INSTALL), or a `pi-bolt`
 * on PATH that leads to it. Anything else is an archive unpacked by hand.
 */
function installedByInstaller(installDir: string, folder: string): boolean {
	const defaults = [join(homedir(), ".pi-bolt"), process.env.PIBOLT_INSTALL].filter((dir): dir is string => !!dir);
	if (defaults.some((dir) => realpathOr(dir) === realpathOr(installDir))) return true;
	return piBoltOnPath().some((entry) => dirname(entry.real) === folder);
}

function realpathOr(path: string): string {
	try {
		return realpathSync(path);
	} catch {
		return path;
	}
}

function firstLine(text: string | undefined): string {
	return (text ?? "").split(/\r?\n/).find((line) => line.trim() !== "") ?? "";
}

function run(command: string, args: string[], env: NodeJS.ProcessEnv = process.env, timeout = 10_000) {
	try {
		const result = spawnProcessSync(command, args, {
			encoding: "utf8",
			env,
			timeout,
			stdio: ["ignore", "pipe", "pipe"],
		});
		return { status: result.status, output: `${result.stdout ?? ""}${result.stderr ?? ""}`, error: result.error };
	} catch (error) {
		return { status: null, output: "", error: error instanceof Error ? error : new Error(String(error)) };
	}
}

function buildSection(self: SelfInfo, report: DoctorReport): DoctorSection {
	const build = PIBOLT;
	const rows: DoctorSection["rows"] = [];
	rows.push(["Pi-Bolt", build ? `${build.version} (Pi ${VERSION})` : `not a Pi-Bolt build (Pi ${VERSION})`]);
	rows.push(["build", `${process.platform}-${build?.variant ?? arch()}, JIT ${build?.jit ? "on" : "off"}`]);
	const revision = (globalThis as { Bun?: { revision?: string } }).Bun?.revision;
	rows.push([
		"engine",
		`Bun ${process.versions.bun ?? "-"}${revision ? ` (${revision.slice(0, 12)})` : ""}, runtime stamp ${build?.runtimeStamp ?? "not recorded"}`,
	]);
	// `BUN_STATIC_HEAP_VERBOSE=1 pi-bolt --version` (docs/TROUBLESHOOTING.md): the executable says whether its compiled code
	// is used.
	const check = run(self.command, ["--version"], { ...process.env, BUN_STATIC_HEAP_VERBOSE: "1" });
	const notUsed = /ahead-of-time compiled code is not used \(([^)]*)\)/.exec(check.output);
	if (/image registered: true/.test(check.output)) {
		rows.push(["compiled code", "used"]);
	} else if (notUsed) {
		rows.push(["compiled code", `not used: ${notUsed[1]}`]);
		report.problems.push(
			`The compiled code is not used (${notUsed[1]}): Pi-Bolt runs from bytecode, slower. See docs/TROUBLESHOOTING.md.`,
		);
	} else {
		rows.push(["compiled code", `unknown (${firstLine(check.error?.message ?? check.output) || "no answer"})`]);
	}
	if (process.env.BUN_AOT === "0") report.problems.push("BUN_AOT=0 is set: the compiled code is not used.");
	rows.push(["executable", self.executable]);
	return { title: "Pi-Bolt", rows };
}

// --- Installation ---------------------------------------------------------------------------------------------------

interface PathEntry {
	path: string;
	real: string;
}

/** Every `pi-bolt` on PATH, in PATH's order, with where each leads. */
function piBoltOnPath(env: NodeJS.ProcessEnv = process.env): PathEntry[] {
	const names = process.platform === "win32" ? ["pi-bolt.exe", "pi-bolt.cmd", "pi-bolt.ps1", "pi-bolt"] : ["pi-bolt"];
	const seen = new Set<string>();
	const entries: PathEntry[] = [];
	for (const dir of (env.PATH ?? env.Path ?? "").split(delimiter)) {
		if (!dir) continue;
		for (const name of names) {
			const path = join(dir, name);
			try {
				if (!statSync(path).isFile()) continue;
			} catch {
				continue;
			}
			const real = npmShimTarget(path) ?? realpathOr(path);
			if (seen.has(real)) continue;
			seen.add(real);
			entries.push({ path, real });
		}
	}
	return entries;
}

/**
 * What a shim npm made on Windows starts: pi-bolt.cmd, pi-bolt.ps1 and pi-bolt (for Git Bash) in npm's global folder are
 * scripts that run node_modules\pi-bolt\bin\pi-bolt.exe beside them, which no link leads to.
 */
function npmShimTarget(path: string): string | undefined {
	if (process.platform !== "win32" || /\.exe$/i.test(path)) return undefined;
	const target = join(dirname(path), "node_modules", "pi-bolt", "bin", "pi-bolt.exe");
	return existsSync(target) ? realpathOr(target) : undefined;
}

/** The npm package's launcher (npm/bin/pi-bolt): it starts the executable of its version from ~/.pi-bolt/npm. */
function isNpmLauncher(real: string): boolean {
	return /[\\/]node_modules[\\/]pi-bolt[\\/]bin[\\/]pi-bolt(\.exe)?$/i.test(real);
}

export interface PathAnalysis {
	/** The `pi-bolt` a shell runs, if any. */
	first: PathEntry | undefined;
	firstIsThis: boolean;
	/** Other `pi-bolt`s on PATH after the first. */
	others: PathEntry[];
}

/** Which `pi-bolt` on PATH a shell runs, and whether that is this one (`folder` is this executable's). */
export function analyzePiBoltOnPath(
	entries: readonly PathEntry[],
	folder: string,
	method: SelfInfo["method"],
): PathAnalysis {
	const isThis = (entry: PathEntry) =>
		dirname(entry.real) === folder || (method === "npm" && isNpmLauncher(entry.real));
	const [first, ...others] = entries;
	return { first, firstIsThis: first !== undefined && isThis(first), others };
}

/** What a `pi-bolt` that is not this one is: its --version, or the version an npm launcher starts (without starting it). */
function versionOf(entry: PathEntry): string {
	if (isNpmLauncher(entry.real)) {
		try {
			if (statSync(entry.real).size > 64 * 1024) throw new Error("not the launcher script");
			const version = /^VERSION=(\S+)$/m.exec(readFileSync(entry.real, "utf8"))?.[1];
			if (version) return `the npm package's launcher of Pi-Bolt ${version}`;
		} catch {
			// (A binary launcher, on Windows: it is run below.)
		}
	}
	return firstLine(run(entry.path, ["--version"]).output) || "does not answer --version";
}

/** The other builds in the installation's folder (the -jit build next to the default one, say), with their version. */
function otherVariants(installDir: string, folder: string): string[] {
	let names: string[];
	try {
		names = readdirSync(installDir);
	} catch {
		return [];
	}
	return names
		.filter((name) => /^pi-bolt-(linux|darwin|win32)-[a-z0-9-]+$/.test(name) && join(installDir, name) !== folder)
		.map((name) => {
			let about = "";
			try {
				about = firstLine(readFileSync(join(installDir, name, "pi-bolt.txt"), "utf8"));
			} catch {
				// (No pi-bolt.txt: an old or partial folder.)
			}
			return `${name}${about ? `: ${about}` : ""}`;
		});
}

interface SignatureResult {
	status: "verified" | "bad" | "not-released" | "error";
	detail: string;
}

/** The names a release's archive of this build has (one of them is in SHA256SUMS). */
function archiveNames(): string[] {
	const name = `pi-bolt-${process.platform}-${PIBOLT?.variant ?? "x64"}`;
	return process.platform === "win32" ? [`${name}.zip`] : [`${name}.tar.xz`, `${name}.tar.gz`];
}

/**
 * Whether `signature` is the release key's signature of `sums`, the SHA256SUMS of release `version`, and lists this build's
 * archive. (Releases sign SHA256SUMS with Ed25519, scripts/sign-release.sh; the installers check it the same way.)
 */
export function verifyReleaseSums(
	sums: Buffer,
	signature: Buffer,
	version: string,
	archives: readonly string[],
	key: string = PIBOLT_RELEASE_KEY,
): SignatureResult {
	let valid = false;
	try {
		valid = verify(null, sums, key, signature);
	} catch {
		valid = false;
	}
	if (!valid) return { status: "bad", detail: "the signature of SHA256SUMS does not verify" };
	const text = sums.toString("utf8");
	if (firstLine(text) !== `# pi-bolt ${version}`) {
		return { status: "bad", detail: `the signed SHA256SUMS is not release ${version}'s` };
	}
	const files = new Set(text.split(/\r?\n/).map((line) => line.split(/\s+[ *]?/)[1]));
	const listed = archives.find((archive) => files.has(archive));
	if (!listed) return { status: "bad", detail: `the signed SHA256SUMS lists no ${archives.join(" or ")}` };
	return { status: "verified", detail: `signature verified, ${listed} listed` };
}

async function fetchBytes(url: string): Promise<{ status: number; body?: Buffer }> {
	const response = await fetch(url, { signal: AbortSignal.timeout(NETWORK_TIMEOUT_MS), redirect: "follow" });
	if (!response.ok) return { status: response.status };
	return { status: response.status, body: Buffer.from(await response.arrayBuffer()) };
}

async function checkReleaseSignature(): Promise<SignatureResult | undefined> {
	if (!PIBOLT) return undefined;
	const base = `${PIBOLT_RELEASES_URL}/download/bolt-v${PIBOLT.version}`;
	try {
		const [sums, signature] = await Promise.all([
			fetchBytes(`${base}/SHA256SUMS`),
			fetchBytes(`${base}/SHA256SUMS.sig`),
		]);
		if (sums.status === 404) return { status: "not-released", detail: `there is no release bolt-v${PIBOLT.version}` };
		if (!sums.body) return { status: "error", detail: `SHA256SUMS: HTTP ${sums.status}` };
		if (!signature.body) return { status: "bad", detail: `the release has no signature (HTTP ${signature.status})` };
		return verifyReleaseSums(sums.body, signature.body, PIBOLT.version, archiveNames());
	} catch (error) {
		return { status: "error", detail: error instanceof Error ? error.message : String(error) };
	}
}

async function latestRelease(): Promise<string | undefined> {
	try {
		return (await getLatestPiRelease(PIBOLT?.version ?? VERSION, { timeoutMs: NETWORK_TIMEOUT_MS }))?.version;
	} catch {
		return undefined;
	}
}

async function installSection(
	self: SelfInfo,
	signature: Promise<SignatureResult | undefined>,
	latest: Promise<string | undefined>,
	offline: boolean,
	report: DoctorReport,
): Promise<DoctorSection> {
	const rows: DoctorSection["rows"] = [];
	const where = self.installDir ? `, in ${self.installDir}` : `, run from ${self.folder}`;
	const how = (method: SelfInfo["method"]): string => {
		switch (method) {
			case "npm":
				return "npm (the pi-bolt package)";
			case "installer":
				return "the installer";
			case "manual":
				return "by hand (an unpacked archive)";
			case "not installed":
				return "not installed (a build run from its folder)";
			default: {
				const manager = piBoltPackageManager(method);
				return manager ? `${manager.name} (${manager.source})` : method;
			}
		}
	};
	rows.push(["installed", `${how(self.method)}${where}`]);
	if (self.installDir) {
		const others = otherVariants(self.installDir, self.folder);
		if (others.length > 0) rows.push(["also there", others.join("\n")]);
	}

	const onPath = piBoltOnPath();
	const path = analyzePiBoltOnPath(onPath, self.folder, self.method);
	if (!path.first) {
		rows.push(["pi-bolt on PATH", "none"]);
		if (self.method !== "not installed") {
			report.problems.push(
				"There is no `pi-bolt` on PATH (see docs/TROUBLESHOOTING.md, pi-bolt: command not found).",
			);
		}
	} else {
		rows.push([
			"pi-bolt on PATH",
			onPath
				.map((entry) => `${entry.path}${entry.real !== entry.path ? ` -> ${entry.real}` : ""}`)
				.map((line, index) =>
					index === 0
						? `${line} (${path.firstIsThis ? "this Pi-Bolt" : "not this Pi-Bolt"})`
						: `${line} (not run: the one above comes first)`,
				)
				.join("\n"),
		]);
		if (!path.firstIsThis && self.method !== "not installed") {
			report.problems.push(
				`\`pi-bolt\` on PATH is ${path.first.path} (${versionOf(path.first)}), not this Pi-Bolt (${self.executable}). Remove it or put this one's folder earlier on PATH.`,
			);
		}
	}

	if (!PIBOLT) return { title: "Installation", rows };
	const result = await signature;
	if (offline) rows.push(["release", "not checked (offline)"]);
	else if (!result) rows.push(["release", "not checked"]);
	else {
		rows.push(["release", `bolt-v${PIBOLT.version}: ${result.detail}`]);
		if (result.status === "bad") {
			report.problems.push(
				`Release bolt-v${PIBOLT.version}: ${result.detail}. Reinstall from ${PIBOLT_RELEASES_URL} and report it.`,
			);
		}
	}
	const newest = await latest;
	if (newest !== undefined) {
		const newer = isNewerPackageVersion(newest, PIBOLT.version);
		const older = isNewerPackageVersion(PIBOLT.version, newest);
		rows.push(["latest release", `${newest}${newer ? "" : older ? " (this build is newer)" : " (this one)"}`]);
		if (newer) report.suggestions.push(`Pi-Bolt ${newest} is out: \`${piBoltUpdateCommand()}\`.`);
	}
	return { title: "Installation", rows };
}

// --- The system -----------------------------------------------------------------------------------------------------

/** The variables that change what Pi-Bolt does, whose values say nothing private. */
const REPORTED_VARIABLES = [
	"PIBOLT_VARIANT",
	"PIBOLT_HOME",
	"PIBOLT_INSTALL",
	"PIBOLT_NPM",
	"BUN_AOT",
	"PI_CODING_AGENT_DIR",
	"PI_OFFLINE",
	"PI_SKIP_VERSION_CHECK",
	"PI_TUI_SCROLL_ROWS",
	"JITI_FS_CACHE",
	"TERM",
	"TERM_PROGRAM",
];

function systemName(): string {
	if (process.platform === "darwin") {
		const version = firstLine(run("/usr/bin/sw_vers", ["-productVersion"]).output);
		return `macOS ${version || "?"} (Darwin ${release()})`;
	}
	if (process.platform === "win32") return `Windows ${release()}`;
	if (process.env.PREFIX?.includes("com.termux")) return `Android in Termux (Linux ${release()})`;
	let name = "Linux";
	try {
		name = /^PRETTY_NAME="?([^"\n]*)"?$/m.exec(readFileSync("/etc/os-release", "utf8"))?.[1] ?? name;
	} catch {
		// (No os-release.)
	}
	return `${name} (Linux ${release()})`;
}

function systemSection(): DoctorSection {
	const rows: DoctorSection["rows"] = [];
	rows.push(["system", systemName()]);
	const cpu = cpus();
	rows.push([
		"CPU",
		`${cpu[0]?.model.trim() ?? "?"}, ${cpu.length} threads, ${arch()}, ${Math.round(totalmem() / 2 ** 30)} GB memory`,
	]);
	const set = REPORTED_VARIABLES.filter((name) => process.env[name] !== undefined).map(
		(name) => `${name}=${process.env[name]}`,
	);
	const engineOptions = Object.keys(process.env).filter((name) => name.startsWith("BUN_JSC_"));
	if (engineOptions.length > 0) set.push(`${engineOptions.join(", ")} (set)`);
	if (process.env.TMUX) set.push("in tmux");
	rows.push(["environment", set.length > 0 ? set.join("\n") : "nothing of Pi-Bolt's"]);
	return { title: "System", rows };
}

// --- Extensions and packages ----------------------------------------------------------------------------------------

/**
 * Records calls to the bun:ffi functions that make native code (dlopen, cc, linkSymbols, CFunction, JSCallback) while
 * extensions load, and whether each threw "bun:ffi requires the JIT". An extension that makes such a call needs the JIT build,
 * unless it catches that error and goes on without bun:ffi, as an extension with a second backend does. Returns the function
 * that puts them back. (Doctor only: Pi's own starts do not load bun:ffi.)
 */
function recordFfiCalls(onCall: (jitRequired: boolean) => void): () => void {
	let ffi: Record<string, unknown>;
	try {
		ffi = createRequire(import.meta.url)("bun:ffi") as Record<string, unknown>;
	} catch {
		return () => {};
	}
	const recorded = <T>(call: () => T): T => {
		try {
			const result = call();
			onCall(false);
			return result;
		} catch (error) {
			onCall(error instanceof Error && error.message.includes(JIT_REQUIRED_MESSAGE));
			throw error;
		}
	};
	const originals = new Map<string, unknown>();
	for (const name of ["dlopen", "cc", "linkSymbols", "CFunction"]) {
		const original = ffi[name];
		if (typeof original !== "function") continue;
		originals.set(name, original);
		ffi[name] = function (this: unknown, ...args: unknown[]) {
			return recorded(() => Reflect.apply(original, this, args));
		};
	}
	const OriginalCallback = ffi.JSCallback;
	if (typeof OriginalCallback === "function") {
		originals.set("JSCallback", OriginalCallback);
		ffi.JSCallback = new Proxy(OriginalCallback, {
			construct(target, args, newTarget) {
				return recorded(() => Reflect.construct(target, args, newTarget));
			},
		});
	}
	return () => {
		for (const [name, original] of originals) ffi[name] = original;
	};
}

const SOURCE_EXTENSIONS = new Set([".js", ".mjs", ".cjs", ".ts", ".mts", ".cts"]);
const FFI_IMPORT = /(?:\bfrom\s*|\brequire\s*\(\s*|\bimport\s*\(\s*)["']bun:ffi["']/;

/**
 * The first file under `root` (a package, or one file) that imports bun:ffi, if any; the packages Pi-Bolt keeps from extensions
 * on the default build are skipped (their extensions use a Node.js alternative there). At most a few thousand files.
 */
export function findBunFfiImport(root: string): string | undefined {
	const skipped = JIT_ONLY_PACKAGES.map((name) => join("node_modules", ...name.split("/")));
	const queue = [root];
	let files = 0;
	while (queue.length > 0 && files < 5000) {
		const path = queue.shift()!;
		let stats: ReturnType<typeof statSync>;
		try {
			stats = statSync(path);
		} catch {
			continue;
		}
		if (stats.isDirectory()) {
			const relativePath = relative(root, path);
			if (basename(path) === ".git" || skipped.some((skip) => relativePath.endsWith(skip))) continue;
			try {
				for (const name of readdirSync(path)) queue.push(join(path, name));
			} catch {
				// (Unreadable: skipped.)
			}
			continue;
		}
		if (!SOURCE_EXTENSIONS.has(extname(path)) || stats.size > 4 * 2 ** 20) continue;
		files++;
		try {
			if (FFI_IMPORT.test(readFileSync(path, "utf8"))) return path;
		} catch {
			// (Unreadable: skipped.)
		}
	}
	return undefined;
}

export interface ExtensionReport {
	name: string;
	state: "loaded" | "failed" | "skipped" | "disabled";
	detail: string;
	/** Load CPU time in ms, for one loaded at run time. */
	cpuMs?: number;
	/** It uses bun:ffi's native code (called while loading, or a file of it imports bun:ffi). */
	ffi?: string;
	/** A bun:ffi call threw "bun:ffi requires the JIT" while it loaded (and, when it loaded all the same, it went on without). */
	ffiJitRequired?: boolean;
}

function sourceOf(entry: unknown): string | undefined {
	if (typeof entry === "string") return entry;
	if (typeof entry === "object" && entry !== null && typeof (entry as { source?: unknown }).source === "string") {
		return (entry as { source: string }).source;
	}
	return undefined;
}

function sourcesOf(list: unknown): string[] | undefined {
	return Array.isArray(list)
		? list.map(sourceOf).filter((source): source is string => source !== undefined)
		: undefined;
}

export interface PackageLists {
	pi: string[];
	/** piBolt.packages, or undefined when there is none (Pi-Bolt then loads `packages`). */
	piBolt: string[] | undefined;
}

/** The package lists of a settings.json: Pi's `packages` and Pi-Bolt's `piBolt.packages`. */
export function packageListsOf(settings: unknown): PackageLists {
	const object = (typeof settings === "object" && settings !== null ? settings : {}) as {
		packages?: unknown;
		piBolt?: { packages?: unknown };
	};
	return { pi: sourcesOf(object.packages) ?? [], piBolt: sourcesOf(object.piBolt?.packages) };
}

/** Rows comparing Pi's and Pi-Bolt's package lists. */
export function packageListRows(lists: PackageLists): DoctorSection["rows"] {
	const list = (sources: readonly string[]) => (sources.length > 0 ? sources.join("\n") : "none");
	const rows: DoctorSection["rows"] = [["Pi (packages)", list(lists.pi)]];
	if (lists.piBolt === undefined) {
		rows.push(["Pi-Bolt", "loads Pi's list (there is no piBolt.packages)"]);
		return rows;
	}
	rows.push(["Pi-Bolt (piBolt.packages)", list(lists.piBolt)]);
	const piBolt = new Set(lists.piBolt);
	const pi = new Set(lists.pi);
	const onlyPi = lists.pi.filter((source) => !piBolt.has(source));
	const onlyPiBolt = lists.piBolt.filter((source) => !pi.has(source));
	if (onlyPi.length === 0 && onlyPiBolt.length === 0) rows.push(["difference", "none: both load the same packages"]);
	if (onlyPi.length > 0) rows.push(["only Pi loads", onlyPi.join("\n")]);
	if (onlyPiBolt.length > 0) rows.push(["only Pi-Bolt loads", onlyPiBolt.join("\n")]);
	return rows;
}

function readJson(path: string): unknown {
	try {
		return JSON.parse(readFileSync(path, "utf8").replace(/^﻿/, ""));
	} catch {
		return undefined;
	}
}

/** An extension's name in the report: its package's source (with its file, when the package has several), or its file. */
function extensionName(resource: ResolvedResource, entriesOfPackage: number): string {
	const { metadata, path } = resource;
	if (metadata.origin === "package" && metadata.packageRoot) {
		return entriesOfPackage > 1 ? `${metadata.source} (${relative(metadata.packageRoot, path)})` : metadata.source;
	}
	return path;
}

async function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | "timeout"> {
	let timer: ReturnType<typeof setTimeout> | undefined;
	const timeout = new Promise<"timeout">((resolve) => {
		timer = setTimeout(() => resolve("timeout"), ms);
	});
	try {
		return await Promise.race([promise, timeout]);
	} finally {
		clearTimeout(timer);
	}
}

/**
 * What doctor says about an extension's use of bun:ffi on a build without the JIT (#62). Only what was seen is a problem. A
 * failure of "bun:ffi requires the JIT" says so itself (core/extensions/jit-hint.ts), and an extension that loaded although a
 * bun:ffi call threw it caught the error and went on without bun:ffi. One that only imports bun:ffi may call it later, when it
 * is used: that error then names the JIT build too, so it is a suggestion, not a problem. One that failed after a bun:ffi call
 * threw it (it rethrew something else) needs the JIT.
 */
export function jitFinding(
	entry: ExtensionReport,
	jit: boolean,
): { kind: "problem" | "suggestion"; text: string } | undefined {
	if (jit || entry.ffi === undefined || isJitRequiredMessage(entry.detail)) return undefined;
	if (entry.state === "loaded" && entry.ffiJitRequired) return undefined;
	if (entry.ffiJitRequired) {
		return {
			kind: "problem",
			text: `${jitHint(entry.name)} (a bun:ffi call threw "${JIT_REQUIRED_MESSAGE}" while it loaded).`,
		};
	}
	if (entry.state === "loaded" && entry.ffi !== "called while loading") {
		return {
			kind: "suggestion",
			text: `${jitHint(entry.name)}, if it fails with "${JIT_REQUIRED_MESSAGE}" when used (it imports bun:ffi, and did not call it while loading).`,
		};
	}
	return { kind: "problem", text: `${jitHint(entry.name)} (unless it has a fallback without bun:ffi).` };
}

/** Formats the extension rows: one line each, compiled-in ones together. */
export function extensionRows(
	builtins: { enabled: string[]; disabled: string[] },
	inline: number,
	extensions: readonly ExtensionReport[],
	jit: boolean,
): DoctorSection["rows"] {
	const rows: DoctorSection["rows"] = [];
	const compiledIn = [...builtins.enabled.map((name) => `${BUILTIN_PATH_PREFIX}${name}`)];
	if (inline > 0) compiledIn.push(`${inline} compiled in with --plugins`);
	rows.push(["compiled in", compiledIn.length > 0 ? compiledIn.join(", ") : "none"]);
	if (builtins.disabled.length > 0) {
		rows.push(["turned off", builtins.disabled.map((name) => `${BUILTIN_PATH_PREFIX}${name}`).join(", ")]);
	}
	const runTime = jit ? "JIT" : "interpreted";
	for (const extension of extensions) {
		// (A failure of "bun:ffi requires the JIT" says so already.)
		const fallback = extension.state === "loaded" && extension.ffiJitRequired ? "; went on without it" : "";
		const ffi =
			extension.ffi && !extension.detail.includes(JIT_REQUIRED_MESSAGE)
				? `; uses bun:ffi (${extension.ffi}${fallback})`
				: "";
		if (extension.state === "loaded")
			rows.push(["run time", `${extension.name}: ${runTime}, ${extension.detail}${ffi}`]);
		else rows.push([extension.state, `${extension.name}: ${extension.detail}${ffi}`]);
	}
	if (extensions.length === 0) rows.push(["run time", "none"]);
	return rows;
}

async function extensionSections(
	options: DoctorOptions,
	self: SelfInfo,
	report: DoctorReport,
): Promise<DoctorSection[]> {
	const { cwd, agentDir } = options;
	const jit = PIBOLT?.jit ?? true;
	// A project's own settings and extensions count only as a start would take them without asking: trusted before.
	const projectHasResources = hasTrustRequiringProjectResources(cwd);
	const projectTrusted = projectHasResources && new ProjectTrustStore(agentDir).get(cwd) === true;
	const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted });
	const settingsRows: DoctorSection["rows"] = [
		["agent folder", agentDir],
		[
			"this project",
			projectHasResources
				? `${cwd}: has its own settings or extensions, ${projectTrusted ? "trusted" : "not trusted (not loaded)"}`
				: `${cwd}: nothing of its own`,
		],
	];
	for (const { path, error } of settingsManager.drainErrors()) {
		report.problems.push(`Invalid settings file ${path ?? ""}: ${error.message}`);
	}

	const builtinNames = options.extensionFactories.filter(isBuiltinExtension).map((input) => input.name);
	const inline = options.extensionFactories.filter((input) => !isBuiltinExtension(input)).length;
	const missing: string[] = [];
	let resolved: ResolvedResource[] = [];
	try {
		const packageManager = new DefaultPackageManager({
			cwd,
			agentDir,
			settingsManager,
			builtinExtensions: builtinNames,
		});
		resolved = (
			await packageManager.resolve(async (source) => {
				missing.push(source);
				return "skip";
			})
		).extensions;
	} catch (error) {
		report.problems.push(
			`The packages could not be resolved: ${error instanceof Error ? error.message : String(error)}`,
		);
	}

	const builtins = { enabled: [] as string[], disabled: [] as string[] };
	for (const resource of resolved.filter((r) => r.path.startsWith(BUILTIN_PATH_PREFIX))) {
		(resource.enabled ? builtins.enabled : builtins.disabled).push(resource.path.slice(BUILTIN_PATH_PREFIX.length));
	}
	const runTime = resolved.filter((r) => !r.path.startsWith(BUILTIN_PATH_PREFIX));
	const entriesOfPackage = new Map<string, number>();
	for (const resource of runTime) {
		const root = resource.metadata.packageRoot ?? resource.path;
		entriesOfPackage.set(root, (entriesOfPackage.get(root) ?? 0) + 1);
	}

	const extensions: ExtensionReport[] = [];
	let loading: ExtensionReport | undefined;
	const restoreFfi = recordFfiCalls((jitRequired) => {
		if (!loading) return;
		loading.ffi = "called while loading";
		if (jitRequired) loading.ffiJitRequired = true;
	});
	const eventBus = createEventBus();
	const runtime = createExtensionRuntime();
	// (What extensions print while they load is theirs, not the report's.)
	const consoleMethods = ["log", "info", "warn", "error", "debug"] as const;
	const savedConsole = consoleMethods.map((name) => console[name]);
	try {
		for (const resource of runTime) {
			const name = extensionName(
				resource,
				entriesOfPackage.get(resource.metadata.packageRoot ?? resource.path) ?? 1,
			);
			const entry: ExtensionReport = { name, state: "loaded", detail: "" };
			extensions.push(entry);
			if (!resource.enabled) {
				entry.state = "disabled";
				entry.detail = "turned off in settings";
				continue;
			}
			if (!existsSync(resource.path)) {
				entry.state = "failed";
				entry.detail = "the file does not exist";
				continue;
			}
			if (PIBOLT && isHerdrPiIntegration(resource.path)) {
				entry.state = "skipped";
				entry.detail = "Herdr's Pi integration; Pi-Bolt's built-in Herdr support reports in its place";
				continue;
			}
			const scanRoot =
				resource.metadata.packageRoot ??
				(basename(resource.path).startsWith("index.") ? dirname(resource.path) : resource.path);
			const ffiFile = findBunFfiImport(scanRoot);
			loading = entry;
			for (const method of consoleMethods) console[method] = () => {};
			const started = performance.now();
			const cpu = process.cpuUsage();
			const result = await withTimeout(
				loadExtensions([resource.path], cwd, eventBus, runtime),
				EXTENSION_LOAD_TIMEOUT_MS,
			);
			const cpuUsed = process.cpuUsage(cpu);
			const wall = performance.now() - started;
			consoleMethods.forEach((method, index) => {
				console[method] = savedConsole[index];
			});
			loading = undefined;
			if (ffiFile && !entry.ffi)
				entry.ffi = `${relative(dirname(scanRoot), ffiFile) || basename(ffiFile)} imports it`;
			if (result === "timeout") {
				entry.state = "failed";
				entry.detail = `did not finish loading in ${EXTENSION_LOAD_TIMEOUT_MS / 1000} s`;
				continue;
			}
			const error = result.errors[0]?.error;
			if (error !== undefined) {
				entry.state = "failed";
				entry.detail = error.replace(/^Failed to load extension: /, "").replace(/\s+/g, " ");
				continue;
			}
			entry.cpuMs = Math.round((cpuUsed.user + cpuUsed.system) / 1000);
			entry.detail = `loaded in ${Math.round(wall)} ms (CPU ${entry.cpuMs} ms)`;
		}
	} finally {
		consoleMethods.forEach((method, index) => {
			console[method] = savedConsole[index];
		});
		restoreFfi();
	}

	for (const entry of extensions) {
		if (entry.state === "failed") report.problems.push(`Extension ${entry.name} fails to load: ${entry.detail}`);
		const finding = jitFinding(entry, jit);
		if (finding?.kind === "problem") report.problems.push(finding.text);
		if (finding?.kind === "suggestion") report.suggestions.push(finding.text);
		if (!jit && entry.state === "loaded" && (entry.cpuMs ?? 0) >= HEAVY_EXTENSION_CPU_MS) {
			report.suggestions.push(
				`${entry.name} takes ${entry.cpuMs} ms of CPU to load, interpreted: the JIT build runs heavy extensions faster (${piBoltJitSwitchCommand()}), or compile it in (docs/PLUGINS.md).`,
			);
		}
	}
	if (missing.length > 0) {
		report.problems.push(
			`Not installed (or not at the version settings name): ${missing.join(", ")}. Pi-Bolt installs ${missing.length === 1 ? "it" : "them"} at its next start.`,
		);
	}

	const lists = packageListsOf(readJson(join(agentDir, "settings.json")));
	const packageRows = packageListRows(lists);
	if (projectTrusted) {
		const project = sourcesOf(
			(readJson(join(cwd, CONFIG_DIR_NAME, "settings.json")) as { packages?: unknown })?.packages,
		);
		if (project && project.length > 0) packageRows.push(["this project", project.join("\n")]);
	}
	if (missing.length > 0) packageRows.push(["not installed", missing.join("\n")]);

	return [
		{ title: "Settings", rows: settingsRows },
		{ title: "Extensions", rows: extensionRows(builtins, inline, extensions, jit) },
		{ title: "Packages", rows: packageRows },
		herdrSection(agentDir, builtins, lists, report),
		acpSection(self, report),
	];
}

// --- Herdr and editors ----------------------------------------------------------------------------------------------

function herdrSection(
	agentDir: string,
	builtins: { enabled: string[]; disabled: string[] },
	lists: PackageLists,
	report: DoctorReport,
): DoctorSection {
	const rows: DoctorSection["rows"] = [];
	const pane = herdrFromEnvironment();
	rows.push([
		"pane",
		pane
			? `this terminal is a Herdr pane; built-in Herdr support ${builtins.disabled.includes("herdr") ? "turned off" : "on"}`
			: "not in a Herdr pane",
	]);
	const herdr = findExecutableOnPath("herdr");
	rows.push([
		"herdr",
		herdr ? `${herdr} (${firstLine(run(herdr, ["--version"], process.env, 5000).output) || "?"})` : "not on PATH",
	]);
	if (isHerdrPiIntegration(join(agentDir, "extensions", "herdr-agent-state.ts"))) {
		rows.push(["Pi integration", "installed; Pi-Bolt skips it and reports to Herdr itself"]);
	}
	const piHerdr = [...lists.pi, ...(lists.piBolt ?? [])].find((source) => /pi-herdr/.test(source));
	if (piHerdr) {
		const pi = findExecutableOnPath("pi");
		rows.push(["pi-herdr", `${piHerdr}; it starts agents with \`pi\`: ${pi ?? "not on PATH"}`]);
		if (!pi)
			report.problems.push("pi-herdr starts agents in Herdr panes with `pi` (stock Pi), which is not on PATH.");
	}
	return { title: "Herdr", rows };
}

/** JSON with comments and trailing commas (Zed's settings.json) as plain JSON. */
export function stripJsonComments(text: string): string {
	let out = "";
	let inString = false;
	for (let i = 0; i < text.length; i++) {
		const char = text[i];
		if (inString) {
			out += char;
			if (char === "\\") out += text[++i] ?? "";
			else if (char === '"') inString = false;
		} else if (char === '"') {
			inString = true;
			out += char;
		} else if (char === "/" && text[i + 1] === "/") {
			while (i < text.length && text[i] !== "\n") i++;
			out += "\n";
		} else if (char === "/" && text[i + 1] === "*") {
			i = text.indexOf("*/", i + 2);
			if (i === -1) break;
			i++;
		} else {
			out += char;
		}
	}
	return out.replace(/,(\s*[}\]])/g, "$1");
}

export interface AgentServer {
	name: string;
	command: string;
	args: string[];
}

/** The agent servers of an editor's settings that run Pi-Bolt (`pi-bolt`, or `pi ... --acp`). */
export function piBoltAgentServers(settings: unknown): AgentServer[] {
	const servers = (settings as { agent_servers?: unknown } | undefined)?.agent_servers;
	if (typeof servers !== "object" || servers === null) return [];
	const found: AgentServer[] = [];
	for (const [name, value] of Object.entries(servers)) {
		const server = value as { command?: unknown; args?: unknown };
		if (typeof server?.command !== "string") continue;
		const args = Array.isArray(server.args)
			? server.args.filter((arg): arg is string => typeof arg === "string")
			: [];
		const program = basename(server.command.replace(/\\/g, "/"));
		if (/pi-bolt/i.test(program) || (/^pi(\.exe)?$/i.test(program) && args.includes("--acp"))) {
			found.push({ name, command: server.command, args });
		}
	}
	return found;
}

function acpSection(self: SelfInfo, report: DoctorReport): DoctorSection {
	const rows: DoctorSection["rows"] = [["--acp", "built in (`pi-bolt --acp`, docs/ACP.md)"]];
	const home = homedir();
	const editors = [
		{
			name: "Zed",
			path:
				process.platform === "win32"
					? join(process.env.APPDATA || join(home, "AppData", "Roaming"), "Zed", "settings.json")
					: join(process.env.XDG_CONFIG_HOME || join(home, ".config"), "zed", "settings.json"),
		},
		{ name: "JetBrains", path: join(home, ".jetbrains", "acp.json") },
	];
	for (const editor of editors) {
		let settings: unknown;
		try {
			settings = JSON.parse(stripJsonComments(readFileSync(editor.path, "utf8")));
		} catch {
			continue;
		}
		for (const server of piBoltAgentServers(settings)) {
			const program = isAbsolute(server.command) ? server.command : findExecutableOnPath(server.command);
			let state: string;
			if (!program || !existsSync(program)) {
				state = isAbsolute(server.command) ? "does not exist" : "not on PATH";
				report.problems.push(
					`${editor.name}'s agent server "${server.name}" runs ${server.command}, which ${state}: give it the full path of pi-bolt.`,
				);
			} else {
				state =
					dirname(realpathOr(program)) === self.folder
						? "this Pi-Bolt"
						: `another Pi-Bolt: ${realpathOr(program)}`;
			}
			if (!server.args.includes("--acp")) {
				report.problems.push(`${editor.name}'s agent server "${server.name}" runs pi-bolt without --acp.`);
			}
			rows.push([editor.name, `"${server.name}": ${[server.command, ...server.args].join(" ")} (${state})`]);
		}
	}
	return { title: "Editors (ACP)", rows };
}

// --- Output ---------------------------------------------------------------------------------------------------------

/** The report as text: sections of aligned rows, then the problems and suggestions found. */
export function formatDoctorReport(report: DoctorReport): string {
	const width = Math.max(14, ...report.sections.flatMap((section) => section.rows.map(([label]) => label.length))) + 2;
	const lines = ["Pi-Bolt doctor", ""];
	for (const section of report.sections) {
		lines.push(section.title);
		for (const [label, value] of section.rows) {
			const [head, ...rest] = value.split("\n");
			lines.push(`  ${label.padEnd(width)}${head}`);
			for (const line of rest) lines.push(`  ${" ".repeat(width)}${line}`);
		}
		lines.push("");
	}
	lines.push("Problems");
	if (report.problems.length === 0) lines.push("  none found");
	for (const problem of report.problems) lines.push(`  - ${problem}`);
	if (report.suggestions.length > 0) {
		lines.push("", "Suggestions");
		for (const suggestion of report.suggestions) lines.push(`  - ${suggestion}`);
	}
	return `${lines.join("\n")}\n`;
}

/**
 * The report without what is private: the home folder as ~ (by each of `homes`, its path and its real path), credentials in
 * URLs (`https://user:token@host`) and query strings, and anything shaped like a key or token.
 */
export function redactReport(text: string, homes: readonly string[]): string {
	let result = text;
	// Longest first, and only where a path starts (not inside another path: /private/var/... holds /var/...).
	for (const home of [...new Set(homes)].filter((h) => h.length > 1).sort((a, b) => b.length - a.length)) {
		const escaped = home.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
		result = result.replace(new RegExp(`(^|[\\s"'(=:,>])${escaped}(?=[\\\\/]|$|[\\s"'),;:])`, "gm"), "$1~");
	}
	return result
		.replace(/\b([a-z][a-z0-9+.-]*:\/\/)[^\s/@]+@/gi, "$1***@")
		.replace(/\b(https?:\/\/[^\s?#]+)\?[^\s)]*/gi, "$1?***")
		.replace(
			/\b(sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{8,}|github_pat_[A-Za-z0-9_]{8,}|xox[abprs]-[A-Za-z0-9-]{8,}|AKIA[A-Z0-9]{12,}|AIza[A-Za-z0-9_-]{20,}|npm_[A-Za-z0-9]{20,})/g,
			"***",
		)
		.replace(/\b(Bearer\s+)[^\s"',;]{6,}/g, "$1***")
		.replace(/\b(token|apikey|api_key|api-key|password|secret)(\s*[=:]\s*"?)[^\s"',;]{6,}/gi, "$1$2***");
}
