#!/usr/bin/env bun
/**
 * Writes the compile kit: what `pi-bolt compile` needs, besides the runtime, to build the executable a release has with
 * extensions compiled in, on a machine with no copy of this repository. scripts/build-pi.sh --kit runs it after a build.
 *
 * The kit is a folder:
 *   kit.json                 the build's compile command (its arguments and environment) and entry points
 *   tree/                    the files of the Pi tree that the build read, at the same paths: Pi's built packages and the
 *                            node_modules files they import, with every package.json and tsconfig on the way to them, and the
 *                            workspace links that make @earendil-works/pi-* resolve to Pi's packages
 *   profile/                 the training profile the build used
 *   gen-plugin-manifest.mjs  the manifest generator (packages/coding-agent/src/gen-plugin-manifest.mjs)
 *
 * Which files: those the bundler took for Pi's entry points (its metafile), and those it takes for every module a plugin may
 * import from Pi (the "exports" of @earendil-works/pi-coding-agent, pi-ai, pi-agent-core, pi-tui and typebox). A build in the
 * kit with no plugins is then the same as the release's.
 *
 * Usage: bun scripts/make-kit.mjs --bun BUN --pi DIR --agent DIR --record FILE --profile DIR --platform P --version V --out DIR
 *   --record FILE  JSON: { env, args, entries } of the build (build-pi.sh writes it)
 */

import { spawnSync } from "node:child_process";
import {
	cpSync,
	existsSync,
	lstatSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	readlinkSync,
	realpathSync,
	rmSync,
	statSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
/** The packages Pi provides to extensions (packages/coding-agent/src/gen-plugin-manifest.mjs, isPiPackage): a plugin imports these from Pi. */
const PROVIDED = [
	"@earendil-works/pi-coding-agent",
	"@earendil-works/pi-ai",
	"@earendil-works/pi-agent-core",
	"@earendil-works/pi-tui",
	"typebox",
];

function die(message) {
	process.stderr.write(`error: ${message}\n`);
	process.exit(1);
}

function parseArgs(argv) {
	const options = {};
	for (let index = 0; index < argv.length; index += 2) {
		const name = argv[index];
		if (!name.startsWith("--") || argv[index + 1] === undefined) die(`bad argument ${name}`);
		options[name.slice(2)] = argv[index + 1];
	}
	for (const name of ["bun", "pi", "agent", "record", "profile", "platform", "version", "out"]) {
		if (!options[name]) die(`--${name} is required`);
	}
	return options;
}

/** The module path an "exports" target names for the bundler (conditions bun, import, default, in that order). */
function exportTarget(value) {
	if (typeof value === "string") return value;
	if (Array.isArray(value)) return value.map(exportTarget).find(Boolean);
	if (value && typeof value === "object") {
		for (const condition of ["bun", "import", "default"]) {
			if (condition in value) return exportTarget(value[condition]);
		}
	}
	return undefined;
}

/** Every file below `dir` whose path relative to it matches `prefix` + anything + `suffix` (an "exports" pattern). */
function expandPattern(dir, prefix, suffix) {
	const base = join(dir, dirname(`${prefix}x`));
	const files = [];
	const walk = (folder) => {
		if (!existsSync(folder)) return;
		for (const entry of readdirSync(folder, { withFileTypes: true })) {
			const path = join(folder, entry.name);
			if (entry.isDirectory()) walk(path);
			else {
				const rel = relative(dir, path).split(sep).join("/");
				if (`./${rel}`.startsWith(prefix) && rel.endsWith(suffix) && !rel.endsWith(".d.ts")) files.push(path);
			}
		}
	};
	walk(base);
	return files;
}

/** The modules a plugin can import from a provided package: each "exports" entry, patterns expanded; or its main. */
function providedModules(packageDir) {
	const manifest = JSON.parse(readFileSync(join(packageDir, "package.json"), "utf8"));
	const exports = manifest.exports;
	if (!exports) return [join(packageDir, manifest.module ?? manifest.main ?? "index.js")];
	const map = typeof exports === "string" || exportTarget(exports) === exports ? { ".": exports } : exports;
	const files = [];
	for (const [key, value] of Object.entries(map)) {
		if (key === "./package.json") continue;
		const target = exportTarget(value);
		if (!target || target.endsWith(".json")) continue;
		if (target.includes("*")) {
			const [prefix, suffix] = target.split("*");
			files.push(...expandPattern(packageDir, prefix, suffix));
		} else {
			files.push(join(packageDir, target));
		}
	}
	return files.filter((file) => existsSync(file));
}

const options = parseArgs(process.argv.slice(2));
const root = realpathSync(options.pi);
const agent = realpathSync(options.agent);
const record = JSON.parse(readFileSync(options.record, "utf8"));
const out = resolve(options.out);

// --- what the bundler reads ------------------------------------------------------------------------------------------

const apiModules = PROVIDED.flatMap((name) => providedModules(realpathSync(join(root, "node_modules", ...name.split("/")))));
const work = mkdtempSync(join(tmpdir(), "pibolt-kit-"));
const metafile = join(work, "meta.json");
// (Without the build's --define of PIBOLT_BUILD: nothing is left out for it, so this reads what the build read, and more.)
const bundled = spawnSync(
	options.bun,
	[
		"build",
		"--target=bun", // (a platform is for --compile only)
		"--format=esm",
		`--metafile=${metafile}`,
		`--outdir=${join(work, "out")}`,
		...record.entries,
		...apiModules,
	],
	{ cwd: agent, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
);
if (bundled.status !== 0) die(`the bundler failed:\n${bundled.stderr}${bundled.stdout}`);
const inputs = Object.keys(JSON.parse(readFileSync(metafile, "utf8")).inputs).map((input) => resolve(agent, input));
rmSync(work, { recursive: true, force: true });

/**
 * The metafile has the modules the bundle kept, not every module the bundler resolved: one it then left out (an unused,
 * side-effect-free re-export, or a require() in code it dropped) still has to be there to be resolved. So the imports of every
 * module are followed too, with Bun's own scanner and resolver, from the same entry points.
 */
function importClosure(starts) {
	const scanners = { js: new Bun.Transpiler({ loader: "js" }), ts: new Bun.Transpiler({ loader: "ts" }), tsx: new Bun.Transpiler({ loader: "tsx" }) };
	const loaderOf = (path) => (/\.(m|c)?tsx$/.test(path) ? "tsx" : /\.(m|c)?ts$/.test(path) ? "ts" : /\.(m|c)?jsx?$/.test(path) ? "js" : undefined);
	const seen = new Set();
	const queue = [...starts];
	while (queue.length > 0) {
		const path = queue.pop();
		if (seen.has(path)) continue;
		seen.add(path);
		const loader = loaderOf(path);
		if (!loader) continue;
		let imports;
		try {
			imports = scanners[loader].scanImports(readFileSync(path, "utf8"));
		} catch {
			continue; // (Not parseable as itself, such as a .js file of another dialect: the metafile has what the bundle used.)
		}
		for (const { path: specifier } of imports) {
			if (/^(node:|bun:|bun$)/.test(specifier)) continue;
			let resolved;
			try {
				resolved = Bun.resolveSync(specifier, dirname(path));
			} catch {
				continue; // (A builtin module, or an optional one that is not installed: the bundler cannot have used it either.)
			}
			if (isAbsolute(resolved) && existsSync(resolved)) queue.push(realpathSync(resolved));
		}
	}
	return seen;
}
const reached = importClosure([...record.entries.map((entry) => realpathSync(resolve(agent, entry))), ...apiModules.map((m) => realpathSync(m))]);

const files = new Set();
const inTree = (path) => {
	const rel = relative(root, path);
	if (rel.startsWith("..") || isAbsolute(rel)) die(`${path} is outside the Pi tree ${root}`);
	return rel;
};
for (const path of new Set([...inputs, ...reached])) {
	// (An input may be the name of one of the bundler's own modules, which has no file.)
	if (!existsSync(path)) continue;
	files.add(inTree(path));
	// The package.json and tsconfig files from the module's folder up to the tree's root: resolution and the module type.
	for (let dir = dirname(path); ; dir = dirname(dir)) {
		for (const name of ["package.json", "tsconfig.json", "tsconfig.base.json"]) {
			if (existsSync(join(dir, name))) files.add(inTree(join(dir, name)));
		}
		if (dir === root || dir === dirname(dir)) break;
	}
}
// The entry points themselves, and the build's own staging place for plugins (the entry it writes imports these).
for (const entry of record.entries) files.add(inTree(resolve(agent, entry)));

// The links in node_modules folders that lead to a folder the kit has files of: the workspace's @earendil-works/pi-* and any
// other linked package, which resolution passes through by name.
const links = [];
const kitDirs = new Set([...files].map((file) => dirname(file)));
const hasFilesIn = (dir) => [...kitDirs].some((d) => d === dir || d.startsWith(`${dir}${sep}`));
const nodeModulesFolders = [join(root, "node_modules"), ...readdirSync(join(root, "packages")).map((p) => join(root, "packages", p, "node_modules"))];
for (const folder of nodeModulesFolders) {
	if (!existsSync(folder)) continue;
	for (const entry of readdirSync(folder)) {
		const candidates = entry.startsWith("@") ? readdirSync(join(folder, entry)).map((n) => join(folder, entry, n)) : [join(folder, entry)];
		for (const path of candidates) {
			if (!lstatSync(path).isSymbolicLink()) continue;
			let target;
			try {
				target = realpathSync(path);
			} catch {
				continue;
			}
			const rel = relative(root, target);
			if (!rel.startsWith("..") && hasFilesIn(rel)) links.push({ path: inTree(path), target: readlinkSync(path) });
		}
	}
}

// --- the kit ---------------------------------------------------------------------------------------------------------

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, "tree"), { recursive: true });
let bytes = 0;
for (const file of [...files].sort()) {
	const to = join(out, "tree", file);
	mkdirSync(dirname(to), { recursive: true });
	cpSync(join(root, file), to);
	bytes += statSync(to).size;
}
for (const link of links) {
	const to = join(out, "tree", link.path);
	mkdirSync(dirname(to), { recursive: true });
	symlinkSync(link.target, to);
}
const profile = resolve(options.profile);
const profileDir = join(out, "profile");
mkdirSync(profileDir, { recursive: true });
if (existsSync(profile)) cpSync(profile, profileDir, { recursive: true });
cpSync(join(HERE, "..", "packages", "coding-agent", "src", "gen-plugin-manifest.mjs"), join(out, "gen-plugin-manifest.mjs"));

// The compile command, with the profile's paths as @KIT@/profile/... (where `pi-bolt compile` unpacks the kit).
const kitPath = (value) => value.split(profile).join("@KIT@/profile");
const piVersion = JSON.parse(readFileSync(join(agent, "package.json"), "utf8")).version;
const kit = {
	piBolt: options.version,
	pi: piVersion,
	platform: options.platform,
	agent: `tree/${relative(root, agent).split(sep).join("/")}`,
	entries: record.entries,
	args: record.args.map(kitPath),
	env: Object.fromEntries(Object.entries(record.env).map(([name, value]) => [name, kitPath(value)])),
};
writeFileSync(join(out, "kit.json"), `${JSON.stringify(kit, null, "\t")}\n`);
process.stderr.write(
	`kit: ${files.size} files (${(bytes / 1048576).toFixed(1)} MB), ${links.length} links, profile ${existsSync(profile) ? "pi-" + piVersion : "none"} -> ${out}\n`,
);
