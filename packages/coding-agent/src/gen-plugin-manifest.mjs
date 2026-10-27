#!/usr/bin/env node
/**
 * Writes the plugin folder that `build-pi.sh --plugins` takes, from the extensions installed on this machine.
 *
 * docs/PLUGINS.md step 1 is four steps done by hand: repeat Pi's discovery rules, name each extension, copy its folder
 * where the bundler can reach it, and bring its dependencies. Every one of them fails quietly, because an extension left
 * out of the manifest still loads at run time. The session looks right and only the compiled-in list is short.
 *
 * The discovery rules below are Pi's, and Pi is the source of truth for them:
 * packages/coding-agent/src/core/package-manager.ts, `collectAutoExtensionEntries` and `resolveExtensionEntries`. They are
 * repeated here rather than imported because this script belongs to Pi-Bolt and those functions are Pi's, and private.
 * `assertMatchesPiRules` in scripts/gen-plugin-manifest.test.mjs covers each rule, so a change in Pi shows up as a failing
 * test rather than as a short manifest.
 *
 * It is in the coding agent's package, and its build copies it into dist, because `pi-bolt compile` imports it to plan a
 * compile before it downloads anything. scripts/gen-plugin-manifest.mjs runs it in a checkout; scripts/make-kit.mjs copies it
 * into the compile kit, where `pi-bolt compile` runs it by itself.
 *
 * Usage: scripts/gen-plugin-manifest.mjs --out DIR [options]
 *   --out DIR           where the plugin folder goes (required; replaced if a previous run wrote it)
 *   --agent-dir DIR     the agent directory (default: $PI_CODING_AGENT_DIR, else ~/.pi/agent)
 *   --cwd DIR           also take DIR/.pi/extensions, a project's own folder (default: no project)
 *   --only a,b          only these extensions, by the name this prints (repeatable)
 *   --exclude a,b       every extension except these (repeatable)
 *   --no-packages       leave out the packages `pi install` installed
 *   --packages FILE     the packages to compile in, instead of the `packages` setting's npm: packages: a JSON list of
 *                       { source, root, entries }, as `pi-bolt compile` resolves them (piBolt.packages applied)
 *   --enabled FILE      the extensions Pi loads, as `pi-bolt compile` resolves them (a JSON list of paths): any other
 *                       found is one the `extensions` setting turns off, and is passed over
 *   --no-jit            for the build without the JIT: package.json files without their "bun" conditions, and a failing
 *                       import in place of each package that needs bun:ffi, as Pi loads them from their files there
 *   --json              print the plan as JSON instead of a summary
 *
 * The agent directory's `settings.json` supplies the `extensions` paths and the `packages` list. A project's settings are
 * not read: Pi honours those only in a trusted project, and this script cannot tell whether a project is trusted.
 */

import {
	chmodSync,
	cpSync,
	existsSync,
	lstatSync,
	mkdirSync,
	readdirSync,
	readFileSync,
	realpathSync,
	rmSync,
	statSync,
	writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const CONFIG_DIR_NAME = ".pi";
const HERE = dirname(fileURLToPath(import.meta.url));

function die(message) {
	process.stderr.write(`error: ${message}\n`);
	process.exit(1);
}

function readJson(path) {
	try {
		const parsed = JSON.parse(readFileSync(path, "utf8").replace(/^﻿/, ""));
		return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : undefined;
	} catch {
		return undefined;
	}
}

// --- Pi's discovery rules (loader.ts) -------------------------------------------------------------------------------

const isExtensionFile = (name) => name.endsWith(".ts") || name.endsWith(".js");

/** Rule 3 then rule 2: a package.json with a "pi.extensions" list, else an index. Returns absolute paths, or undefined. */
export function resolveExtensionEntries(dir) {
	const manifest = readJson(join(dir, "package.json"));
	const declared = manifest?.pi?.extensions;
	if (Array.isArray(declared) && declared.length > 0) {
		const entries = declared.map((entry) => resolve(dir, entry)).filter((entry) => existsSync(entry));
		if (entries.length > 0) return entries;
	}
	for (const index of ["index.ts", "index.js"]) {
		if (existsSync(join(dir, index))) return [join(dir, index)];
	}
	return undefined;
}

/** The ignore files whose rules Pi applies to a folder's entries (package-manager.ts, `IGNORE_FILE_NAMES`). */
const IGNORE_FILE_NAMES = [".gitignore", ".ignore", ".fdignore"];

/** A gitignore glob of one path segment as a regular expression: `*`, `?`, `[...]` (`[!...]`), and `\` escapes. */
function segmentPattern(glob) {
	let source = "";
	for (let i = 0; i < glob.length; i++) {
		const char = glob[i];
		if (char === "\\" && i + 1 < glob.length) {
			source += glob[++i].replace(/[\\^$.*+?()[\]{}|/-]/g, "\\$&");
		} else if (char === "*") {
			source += "[^/]*";
			while (glob[i + 1] === "*") i++;
		} else if (char === "?") {
			source += "[^/]";
		} else if (char === "[" && glob.indexOf("]", i + 2) !== -1) {
			const end = glob.indexOf("]", i + 2);
			const body = glob.slice(i + 1, end);
			source += `[${body.startsWith("!") ? `^${body.slice(1)}` : body}]`;
			i = end;
		} else {
			source += char.replace(/[\\^$.*+?()[\]{}|/-]/g, "\\$&");
		}
	}
	return new RegExp(`^${source}$`);
}

/**
 * Whether a name directly in `dir` (`folder` when it is a folder) is ignored by the rules of `dir`'s own ignore files, as
 * Pi applies them when it looks for extensions in a folder (package-manager.ts, `addIgnoreRules` and the `ignore` package).
 * Only names directly in `dir` are tested, so a rule is the gitignore rule reduced to one path segment: one with a `/`
 * inside it never matches, `**` matches every name, and a trailing `/` matches folders only. The last rule that matches
 * decides, and a `!` rule takes a name back. scripts/gen-plugin-manifest.test.mjs checks this against the `ignore` package.
 */
export function ignoreRulesOf(dir) {
	const rules = [];
	for (const file of IGNORE_FILE_NAMES) {
		let text;
		try {
			text = readFileSync(join(dir, file), "utf8");
		} catch {
			continue;
		}
		for (const line of text.split(/\r?\n/)) {
			const trimmed = line.trim();
			if (!trimmed || (trimmed.startsWith("#") && !trimmed.startsWith("\\#"))) continue;
			let pattern = line;
			// (Pi strips the `\` of `\!` before the `ignore` package reads the rule, which then takes it for a `!` rule too.)
			const negated = /^\\?!/.test(pattern);
			pattern = pattern.replace(/^\\?!/, "");
			if (pattern.startsWith("/")) pattern = pattern.slice(1);
			// (Trailing spaces are not part of a rule unless escaped.)
			pattern = pattern.replace(/(?<!\\)\s+$/, "");
			const folderOnly = pattern.endsWith("/");
			if (folderOnly) pattern = pattern.slice(0, -1);
			while (pattern.startsWith("**/")) pattern = pattern.slice(3);
			if (!pattern) continue;
			const every = pattern === "**";
			const regex = every || pattern.includes("/") ? undefined : segmentPattern(pattern);
			rules.push({ negated, folderOnly, test: (name) => every || (regex?.test(name) ?? false) });
		}
	}
	return (name, folder) => {
		let ignored = false;
		for (const rule of rules) {
			if ((!rule.folderOnly || folder) && rule.test(name)) ignored = !rule.negated;
		}
		return ignored;
	};
}

/**
 * The extensions Pi finds in a folder it looks in by itself (package-manager.ts, `collectAutoExtensionEntries`): the agent
 * directory's extensions/, a project's .pi/extensions, a folder in the `extensions` setting, a folder a package's
 * "pi.extensions" names. The folder's own entries (rules 3 and 2) when it has them: then only those, its other files being
 * their helpers. Otherwise rules 1 to 3 for what is in it, less dot entries (an editor's `.#name.ts` lock file), node_modules,
 * a link that leads nowhere, and what the folder's .gitignore, .ignore and .fdignore leave out (#8 of the loader-parity
 * review: each was compiled in, or stopped the generator, where Pi passes it over).
 */
export function collectAutoExtensionEntries(dir) {
	if (!existsSync(dir)) return [];
	const own = resolveExtensionEntries(dir);
	if (own) return own;
	let entries;
	try {
		entries = readdirSync(dir, { withFileTypes: true });
	} catch {
		return [];
	}
	const ignored = ignoreRulesOf(dir);
	const found = [];
	for (const entry of entries) {
		if (entry.name.startsWith(".") || entry.name === "node_modules") continue;
		const path = join(dir, entry.name);
		let folder = entry.isDirectory();
		let file = entry.isFile();
		if (entry.isSymbolicLink()) {
			try {
				const stats = statSync(path);
				folder = stats.isDirectory();
				file = stats.isFile();
			} catch {
				continue;
			}
		}
		if (ignored(entry.name, folder)) continue;
		if (file && isExtensionFile(entry.name)) found.push(path);
		else if (folder) found.push(...(resolveExtensionEntries(path) ?? []));
	}
	return found;
}

/**
 * A package's "pi.extensions" entry may be a folder: pi-openrouter-session declares "./extensions", which holds
 * openrouter-session.ts and no index. Pi loads what it finds in that folder (collectAutoExtensionEntries). Imported as it
 * is, the folder does not resolve and the build stops.
 */
export function expandPackageEntry(entry) {
	return statSync(entry).isDirectory() ? collectAutoExtensionEntries(entry) : [entry];
}

// --- names ----------------------------------------------------------------------------------------------------------

/** A name the `extensions` setting can carry: it is one flat string, so a space would split it. */
function safeName(raw) {
	const cleaned = raw
		.replace(/[^A-Za-z0-9._-]+/g, "-")
		.replace(/-{2,}/g, "-")
		.replace(/^-+|-+$/g, "");
	return cleaned.length > 0 ? cleaned : "extension";
}

const withoutExtension = (file) => file.replace(/\.(ts|js)$/, "");

/** Unique within `taken`, by a suffix and then a number. Adds to `taken`. */
function unique(candidate, taken, suffix) {
	if (!taken.has(candidate)) return taken.add(candidate), candidate;
	if (suffix && !taken.has(`${candidate}-${suffix}`)) {
		return taken.add(`${candidate}-${suffix}`), `${candidate}-${suffix}`;
	}
	for (let n = 2; ; n++) {
		if (!taken.has(`${candidate}-${n}`)) return taken.add(`${candidate}-${n}`), `${candidate}-${n}`;
	}
}

/**
 * The name an extension gets. A direct file is its own name, and a folder is the folder's. A folder that declares several
 * entries names each one after its file, so they stay apart.
 *
 * `index` is never a name. A path in the `extensions` setting points at the entry itself, so discovery starts from the
 * extension's own folder and the entry is a bare `index.ts`: three of those would be `index`, `index-2`, `index-3`.
 */
function baseName(entryRelative, siblings, rootName) {
	const segments = entryRelative.split("/");
	if (segments.length === 1) {
		const file = withoutExtension(segments[0]);
		return file === "index" ? rootName : file;
	}
	return siblings <= 1 ? segments[0] : `${segments[0]}-${withoutExtension(segments.at(-1))}`;
}

// --- packages installed with `pi install` ---------------------------------------------------------------------------

/**
 * Pi's own packages: the build resolves these to Pi's modules, and a copy of one would be a second Pi. Only those Pi gives
 * extensions (VIRTUAL_MODULES, and make-kit.mjs's PROVIDED): another package of the @earendil-works scope, such as
 * @earendil-works/chord or pi-web-ui, is a dependency like any other, which the build finds only if it is copied.
 */
const PI_PACKAGES = new Set([
	"typebox",
	"@earendil-works/pi-coding-agent",
	"@earendil-works/pi-ai",
	"@earendil-works/pi-agent-core",
	"@earendil-works/pi-tui",
]);
const isPiPackage = (name) => PI_PACKAGES.has(name);
/** The packages Pi gives an extension at run time, by every name it gives them (VIRTUAL_MODULES). */
const isProvidedByPi = (name) =>
	isPiPackage(name) || name === "@sinclair/typebox" || /^@mariozechner\/pi-(agent-core|ai|coding-agent|tui)$/.test(name);

/**
 * The folder `pi install` put a package in. The `packages` setting records a source and not a path: `npm:pi-lens`,
 * `npm:opensec-pi-todo@2.13.0`, `npm:@scope/name`. A `git:` source is checked out under a hashed folder name that only
 * Pi's package manager can work out, so those are reported instead of guessed at.
 */
function managedPackageFolder(entry, agentDir) {
	if (!entry.startsWith("npm:")) {
		return { skip: `${entry}: only npm: packages can be compiled in (a git: checkout is resolved by Pi)` };
	}
	const spec = entry.slice(4);
	// A pinned version has a trailing @, which a scope's leading @ must not be mistaken for.
	const at = spec.lastIndexOf("@");
	const name = at > 0 ? spec.slice(0, at) : spec;
	const dir = join(agentDir, "npm", "node_modules", ...name.split("/"));
	return existsSync(dir) ? { name, dir } : { skip: `${name}: not installed at ${dir}` };
}

/**
 * Whether a package's entry is the Bun build that loads its real code at run time. Those ship `dist-bun/index.js` beside
 * a `dist-bun/core.cjs`, and the entry requires the core by a path it works out as it runs, which a compiled executable
 * has no file for. Compiling one in means unwrapping the core and handing it Pi's modules, which is a job per package:
 * plugins/opensec/ is the worked example.
 */
const needsVendoring = (entry) => existsSync(join(dirname(entry), "core.cjs"));

/**
 * A package.json's dependency names that a build has to resolve: the required ones, and the optional ones and peers
 * installed. A peer Pi gives extensions is Pi's (npm may install an old copy of it beside the package, which Pi never
 * loads); any other is installed beside the package by npm, and loaded from there by Pi.
 */
function dependencyNames(manifest) {
	const names = [];
	for (const field of ["dependencies", "optionalDependencies", "peerDependencies"]) {
		const value = manifest?.[field];
		if (!value || typeof value !== "object") continue;
		names.push(...Object.keys(value).filter((name) => field !== "peerDependencies" || !isProvidedByPi(name)));
	}
	return names;
}

/** Where Node finds package `name` required from `dir`: the nearest node_modules/<name> in `dir` or above it. */
function resolvePackageFrom(dir, name) {
	for (let at = resolve(dir); ; at = dirname(at)) {
		if (basename(at) !== "node_modules") {
			const candidate = join(at, "node_modules", ...name.split("/"));
			if (existsSync(candidate)) return candidate;
		}
		if (dirname(at) === at) return undefined;
	}
}

/**
 * Every package a package in `dir` needs, transitively, that is not copied with it: `{ name, from }`, `from` a real path.
 *
 * Its dependencies are found as Node finds them, from the real folder of each requiring package up through the folders
 * above it: `pi install` hoists into one flat node_modules, so an npm package's dependencies are its siblings; a local
 * package in a workspace may have its own hoisted to the workspace's node_modules; and pnpm links each dependency into
 * `.pnpm/<name>@<version>/node_modules`, with the dependencies it needs beside the link's target.
 *
 * The copies follow links, so a dependency is copied with the folder of the package that needs it only when it is in that
 * folder and nothing on the way is a link: then it is found from the copy as from the original. Any other is listed, to
 * be copied into the node_modules beside the manifest. `peerDependencies` are walked too, when installed: Pi's own are
 * Pi's to provide (isPiPackage), and npm installs any other beside the package that needs it.
 */
function dependencyClosure(dir) {
	const inside = (folder, path) => {
		const rel = relative(folder, path);
		return !rel.startsWith("..") && resolve(folder, rel) === path;
	};
	const root = realpathSync(dir);
	const needed = [];
	const listed = new Set();
	/**
	 * Each package to look at: its real folder, and the copied folder it is in. What it needs depends on both, so a package
	 * reached again from another copied folder is looked at again (once per pair, which also ends a cycle).
	 */
	const queue = [{ real: root, copied: root }];
	const visited = new Set([`${root}\0${root}`]);
	while (queue.length > 0) {
		const current = queue.shift();
		for (const name of dependencyNames(readJson(join(current.real, "package.json")))) {
			if (isPiPackage(name)) continue;
			// (Nested before hoisted: a package may carry its own copy of a version hoisted differently above it.)
			const found = resolvePackageFrom(current.real, name);
			if (!found) continue;
			const real = realpathSync(found);
			const travels = real === resolve(found) && inside(current.copied, real);
			// (Listed by the name it is required by: two names may lead to one folder, and each is imported by its own.)
			if (!travels && !listed.has(`${name}\0${real}`)) {
				listed.add(`${name}\0${real}`);
				needed.push({ name, from: real });
			}
			const next = { real, copied: travels ? current.copied : real };
			if (visited.has(`${next.real}\0${next.copied}`)) continue;
			visited.add(`${next.real}\0${next.copied}`);
			queue.push(next);
		}
	}
	return needed;
}

// --- the plan -------------------------------------------------------------------------------------------------------

/** Everything in a folder that discovery passed over, so the summary can say why. */
function describeSkipped(root, discovered) {
	let entries;
	try {
		entries = readdirSync(root, { withFileTypes: true });
	} catch {
		return [];
	}
	const skipped = [];
	for (const entry of entries) {
		const path = join(root, entry.name);
		if (discovered.has(resolve(path))) continue;
		let folder = entry.isDirectory();
		let file = entry.isFile();
		if (entry.isSymbolicLink()) {
			// (A link is what it leads to; one to nothing is passed over, as Pi passes it over.)
			try {
				const stats = statSync(path);
				folder = stats.isDirectory();
				file = stats.isFile();
			} catch {
				continue;
			}
		}
		if (folder) {
			if (resolveExtensionEntries(path)) continue;
			const empty = readdirSync(path).length === 0;
			skipped.push({
				path,
				reason: empty
					? "empty folder"
					: 'no index.ts, index.js, or package.json with a "pi.extensions" field (shared code, not an extension)',
			});
			continue;
		}
		if (file && !isExtensionFile(entry.name)) skipped.push({ path, reason: "not a .ts or .js file" });
	}
	return skipped;
}

export function planPlugins(options) {
	const agentDir = resolve(options.agentDir);
	const settings = readJson(join(agentDir, "settings.json")) ?? {};
	const configured = Array.isArray(settings.extensions) ? settings.extensions.filter((p) => typeof p === "string") : [];
	const packages =
		options.noPackages || !Array.isArray(settings.packages)
			? []
			: settings.packages.filter((p) => typeof p === "string");

	/** Each source folder, in the order Pi discovers them: the project, then the agent directory, then configured paths. */
	const sources = [];
	if (options.cwd) sources.push({ origin: "project", root: join(resolve(options.cwd), CONFIG_DIR_NAME, "extensions") });
	sources.push({ origin: "global", root: join(agentDir, "extensions") });

	const seen = new Set();
	const entries = [];
	const addEntry = (path, origin, root, single = false) => {
		if (seen.has(resolve(path))) return;
		seen.add(resolve(path));
		entries.push({ path, origin, root, single });
	};
	for (const source of sources) {
		for (const path of collectAutoExtensionEntries(source.root)) addEntry(path, source.origin, source.root);
	}
	const passedOver = [];
	for (const raw of configured) {
		// A pattern (`-path`, `!path`, `+path`, a glob) or `builtin:<name>` filters what Pi loads; it names no extension to copy.
		if (/^[!+-]/.test(raw) || /[*?]/.test(raw) || raw.startsWith("builtin:")) continue;
		// (As Pi takes it: trimmed, `~` for home, and a relative path from the agent directory, whose setting it is.)
		const trimmed = raw.trim();
		const path = resolve(
			agentDir,
			trimmed === "~" ? homedir() : trimmed.startsWith("~/") ? join(homedir(), trimmed.slice(2)) : trimmed,
		);
		let stats;
		try {
			stats = statSync(path);
		} catch {
			passedOver.push({ path, reason: "in the extensions setting, but not found" });
			continue;
		}
		if (stats.isDirectory()) {
			for (const entry of collectAutoExtensionEntries(path)) addEntry(entry, "configured", path);
			continue;
		}
		// (A file of its own: only it and what it imports are copied, not its whole folder, which may be the home folder.)
		addEntry(path, "configured", dirname(path), true);
	}
	// The extensions Pi loads, when `pi-bolt compile` gives them (--enabled): those the `extensions` setting turns off are
	// not loaded by Pi, so not compiled in, and one that would not build does not stop the build (#8 of the loader-parity
	// review).
	if (options.enabled) {
		const enabled = new Set(options.enabled.map((path) => resolve(path)));
		for (const entry of entries.filter((candidate) => !enabled.has(resolve(candidate.path)))) {
			passedOver.push({ path: entry.path, reason: "turned off in the extensions setting" });
			entries.splice(entries.indexOf(entry), 1);
		}
	}

	const takenStageDirs = new Set();
	const takenNames = new Set();
	const stageDirs = new Map();
	const roots = [];
	const plugins = [];

	const stageDirFor = (origin, root) =>
		origin === "project" || origin === "global"
			? unique(origin, takenStageDirs)
			: unique(`path-${safeName(basename(root))}`, takenStageDirs);

	// How many entries each top-level folder of a root holds, so a folder declaring several gets a name per entry.
	const perFolder = new Map();
	for (const entry of entries) {
		const key = `${resolve(entry.root)} ${relative(entry.root, entry.path).split(sep)[0]}`;
		perFolder.set(key, (perFolder.get(key) ?? 0) + 1);
	}

	/**
	 * How the manifest imports an entry at `rel` (from its root, with `/`), or why it cannot. A `"` or a `\` in a path is a
	 * problem for the bundler, which takes a `\` for a folder separator and names a split file after its module without
	 * escaping a `"` where it imports that file (#14 of the loader-parity review). An entry whose own name has one is imported
	 * through a copy beside it, under a name that has none; one in a folder whose name has one loads from its files.
	 */
	let entryCopies = 0;
	const importOf = (rel) => {
		const segments = rel.split("/");
		if (segments.slice(0, -1).some((segment) => /["\\]/.test(segment))) {
			return { why: "a `\"` or a `\\` in the name of a folder on its path, which the bundler cannot import: it loads from its files" };
		}
		if (!/["\\]/.test(segments.at(-1))) return { as: rel };
		const extension = segments.at(-1).match(/\.[^.]*$/)?.[0] ?? "";
		const as = [...segments.slice(0, -1), `.pibolt-entry-${++entryCopies}${extension}`].join("/");
		return { as, entryCopy: { from: rel, to: as } };
	};
	const passedOverEntries = [];
	for (const entry of entries) {
		const root = resolve(entry.root);
		const rel = relative(entry.root, entry.path).split(sep).join("/");
		const imported = importOf(rel);
		if (imported.why) {
			passedOverEntries.push({ path: entry.path, reason: imported.why });
			continue;
		}
		if (!stageDirs.has(root)) {
			const stageDir = stageDirFor(entry.origin, root);
			stageDirs.set(root, stageDir);
			roots.push({ stageDir, root, origin: entry.origin, ...(entry.single ? { files: [] } : {}) });
		}
		// (A folder is copied whole once any extension in it is a folder's.)
		const of = roots.find((candidate) => candidate.root === root);
		if (of.files && entry.single) of.files.push(entry.path);
		else delete of.files;
		const siblings = perFolder.get(`${root} ${rel.split("/")[0]}`) ?? 1;
		const name = unique(safeName(baseName(rel, siblings, basename(root))), takenNames, entry.origin);
		const segments = rel.split("/");
		plugins.push({
			name,
			entry: entry.path,
			origin: entry.origin,
			stageDir: stageDirs.get(root),
			importPath: `./${stageDirs.get(root)}/${imported.as}`,
			...(imported.entryCopy ? { entryCopy: imported.entryCopy } : {}),
			// (Its file, its folder, and a configured path as the setting gives it.)
			aliases: [
				resolve(entry.path),
				...(segments.length > 1 ? [join(root, segments[0])] : []),
				...(entry.origin === "configured" ? [root] : []),
			],
		});
	}

	const skipped = [...passedOver, ...passedOverEntries, ...roots.flatMap((root) => describeSkipped(root.root, seen))];

	// Each package: its folder, the name it is known by, and its entries. `pi-bolt compile` gives the packages Pi-Bolt loads
	// (`options.packages`: piBolt.packages applied, each with its entries as Pi resolves them); without it, the npm: packages
	// of the `packages` setting.
	const installed = [];
	/** Each package's entry in `installed`, by its stage folder. */
	const packageOf = new Map();
	if (options.packages && !options.noPackages) {
		for (const pkg of options.packages) {
			const name = readJson(join(pkg.root, "package.json"))?.name;
			installed.push({
				path: pkg.source,
				label: "",
				name: typeof name === "string" && name ? name : basename(pkg.root),
				dir: pkg.root,
				declared: pkg.entries,
			});
		}
	} else {
		for (const source of packages) {
			const resolved = managedPackageFolder(source, agentDir);
			if (resolved.skip) {
				skipped.push({ path: source, reason: resolved.skip });
				continue;
			}
			installed.push({
				path: resolved.dir,
				// (The folder alone does not say which package it is.)
				label: `${resolved.name}: `,
				name: resolved.name,
				dir: resolved.dir,
				declared: resolveExtensionEntries(resolved.dir)?.flatMap(expandPackageEntry),
			});
		}
	}

	for (const resolved of installed) {
		// (A file named both on its own and through its folder is one extension.)
		const declared =
			resolved.declared &&
			[...new Set(resolved.declared)].filter((entry) => {
				const { why } = importOf(relative(resolved.dir, entry).split(sep).join("/"));
				if (why) skipped.push({ path: entry, reason: `${resolved.label}${why}` });
				return !why;
			});
		if (!declared?.length) {
			skipped.push({ path: resolved.path, reason: `${resolved.label}no entry point and no "pi.extensions"` });
			continue;
		}
		if (declared.some(needsVendoring)) {
			skipped.push({
				path: resolved.path,
				reason: `${resolved.label}a Bun build that loads core.cjs at run time, which needs vendoring per package (plugins/opensec/)`,
			});
			continue;
		}
		const stageDir = unique(`package-${safeName(resolved.name)}`, takenStageDirs);
		roots.push({ stageDir, root: resolve(resolved.dir), origin: "package" });
		packageOf.set(stageDir, resolved);
		for (const entry of declared) {
			const imported = importOf(relative(resolved.dir, entry).split(sep).join("/"));
			const base = declared.length === 1 ? resolved.name : `${resolved.name}-${withoutExtension(basename(entry))}`;
			const name = unique(safeName(base), takenNames, "package");
			plugins.push({
				name,
				entry,
				origin: "package",
				stageDir,
				importPath: `./${stageDir}/${imported.as}`,
				...(imported.entryCopy ? { entryCopy: imported.entryCopy } : {}),
				// (The package's name, its source as the settings give it and without its version, its folder, and the file.)
				aliases: [
					resolved.name,
					resolved.path,
					...(/^npm:/.test(resolved.path) ? [`npm:${resolved.name}`] : []),
					resolve(resolved.dir),
					resolve(entry),
				],
			});
		}
	}

	const only = (options.only ?? []).filter(Boolean);
	const exclude = (options.exclude ?? []).filter(Boolean);
	const chosen = [];
	/** Those --only or --exclude leave out, so that each extension found is accounted for. */
	const excluded = [];
	/** Those both --only and --exclude name. */
	const contradicted = [];
	for (const plugin of plugins) {
		const inOnly = only.some((value) => namesPlugin(value, plugin));
		const inExclude = exclude.some((value) => namesPlugin(value, plugin));
		if (inOnly && inExclude) contradicted.push(plugin.name);
		const by = only.length > 0 && !inOnly ? "only" : inExclude ? "exclude" : "";
		if (by) excluded.push({ name: plugin.name, origin: plugin.origin, entry: plugin.entry, by });
		else chosen.push(plugin);
	}
	const unknown = (values) => values.filter((value) => !plugins.some((plugin) => namesPlugin(value, plugin)));

	// Only the folders something chosen comes from are copied, and only the dependencies of a chosen package. These share
	// one node_modules, which holds one folder per name: a name is shared only by packages that find it in the same (real)
	// folder. Two folders of the same version may still differ (their own node_modules, a patch), so a package that finds a
	// name elsewhere than one compiled in before it is passed over, and keeps running from its installed copy.
	const used = new Set(chosen.map((plugin) => plugin.stageDir));
	const dependencies = new Map();
	const conflicting = new Set();
	for (const root of roots) {
		if (!used.has(root.stageDir) || root.origin !== "package") continue;
		const pkg = packageOf.get(root.stageDir);
		const own = new Map();
		let conflict;
		for (const { name, from } of dependencyClosure(root.root)) {
			const before = own.get(name) ?? dependencies.get(name);
			if (before && before.from !== from) {
				const its = `${name}@${readJson(join(from, "package.json"))?.version ?? "?"} (${from})`;
				conflict =
					before.owner === pkg
						? `it needs two ${name} folders, ${before.from} and ${from}`
						: `its ${its} differs from the one ${before.owner.name} is compiled in with (${before.from})`;
				break;
			}
			if (!own.has(name)) own.set(name, { from, owner: pkg });
		}
		if (conflict) {
			conflicting.add(root.stageDir);
			skipped.push({ path: pkg.path, reason: `${pkg.label}${conflict}` });
			continue;
		}
		for (const [name, dependency] of own) if (!dependencies.has(name)) dependencies.set(name, dependency);
	}

	return {
		plugins: chosen.filter((plugin) => !conflicting.has(plugin.stageDir)),
		roots: roots.filter((root) => used.has(root.stageDir) && !conflicting.has(root.stageDir)),
		dependencies: [...dependencies].map(([name, { from }]) => ({ name, from })),
		skipped,
		excluded,
		found: plugins.map((plugin) => ({ name: plugin.name, aliases: plugin.aliases })),
		unknown: { only: unknown(only), exclude: unknown(exclude) },
		contradicted,
	};
}

/**
 * Whether `value`, given to --only or --exclude, names `plugin`: by the name this prints for it, or (#174) as the user knows
 * it: a package by its name (`@acme/pi-tools`, which this names acme-pi-tools), its source (`npm:pi-lens`, without its version
 * too) or its folder, and any extension by its file or its folder. A path is taken from the current folder, `~` for home.
 */
export function namesPlugin(value, plugin) {
	if (value === plugin.name || plugin.aliases.includes(value)) return true;
	return plugin.aliases.includes(resolve(value.startsWith("~") ? join(homedir(), value.slice(1)) : value));
}

/**
 * What is wrong with what --only and --exclude ask of `plan`, as an error message (#174): a value that names no extension,
 * which would otherwise leave out nothing and say nothing; an extension both name; nothing left to compile. Undefined when
 * nothing is wrong. `agentDir`: where the extensions were looked for.
 */
export function selectionProblem(plan, agentDir) {
	const unknown = [...plan.unknown.only, ...plan.unknown.exclude];
	if (unknown.length > 0) {
		return plan.found.length === 0
			? `no extension is named ${unknown.join(", ")}: there are no extensions to compile in (in ${agentDir})`
			: `no extension is named ${unknown.join(", ")}. The extensions here are ${plan.found.map((plugin) => plugin.name).join(", ")} (a package's also by its name or source, any one by its file or folder)`;
	}
	if (plan.contradicted.length > 0) return `${plan.contradicted.join(", ")}: both in --only and in --exclude`;
	if (plan.plugins.length > 0) return undefined;
	if (plan.found.length === 0) return `no extensions found to compile in (in ${agentDir})`;
	return plan.excluded.length === plan.found.length
		? `all ${plan.found.length} extensions found are left out (${[...new Set(plan.excluded.map((plugin) => `--${plugin.by}`))].join(", ")})`
		: "the extensions left are passed over (above): nothing to compile in";
}

// --- the names Pi gives its modules besides their own ---------------------------------------------------------------

/*
 * Pi's extension loader hands an extension its own modules under more names than the packages have: the names Pi's packages
 * had before they were @earendil-works' (@mariozechner/pi-*), @sinclair/typebox for typebox, and the root of pi-ai for its
 * compat entry, which keeps the old global API (packages/coding-agent/src/core/extensions/virtual-modules.ts, VIRTUAL_MODULES,
 * and loader.ts, getAliases). An extension that imports `matchesKey` from "@mariozechner/pi-tui" or `getModel` from
 * "@earendil-works/pi-ai" works loaded from its files. The build resolves names as Node does instead: it finds no
 * @mariozechner/pi-tui and stops (#174), and gives the pi-ai root's own entry, which has no `getModel`.
 *
 * So each copy has those names, where it imports a module by them, replaced with the name the build resolves to the module
 * Pi gives: the same module as loaded from its files. scripts/gen-plugin-manifest.test.mjs checks the table against
 * VIRTUAL_MODULES, so a name Pi adds shows up as a failing test.
 */

/** Each name Pi's loader resolves to a module of another name, and that name (VIRTUAL_MODULES). */
export const PI_MODULE_ALIASES = {
	"@earendil-works/pi-ai": "@earendil-works/pi-ai/compat",
	"@mariozechner/pi-agent-core": "@earendil-works/pi-agent-core",
	"@mariozechner/pi-ai": "@earendil-works/pi-ai/compat",
	"@mariozechner/pi-ai/compat": "@earendil-works/pi-ai/compat",
	"@mariozechner/pi-ai/models": "@earendil-works/pi-ai/models",
	"@mariozechner/pi-ai/oauth": "@earendil-works/pi-ai/oauth",
	"@mariozechner/pi-ai/providers/all": "@earendil-works/pi-ai/providers/all",
	"@mariozechner/pi-coding-agent": "@earendil-works/pi-coding-agent",
	"@mariozechner/pi-tui": "@earendil-works/pi-tui",
	"@sinclair/typebox": "typebox",
	"@sinclair/typebox/compile": "typebox/compile",
	"@sinclair/typebox/value": "typebox/value",
};

/** What a file holds when it may import one of those names: the start of each (a quick test before scanning it). */
const ALIASED_PREFIXES = ["@earendil-works/pi-ai", "@mariozechner/", "@sinclair/typebox"];

/** PI_MODULE_ALIASES, to look names up in (an object would also give "constructor" and the like). */
const ALIASES = new Map(Object.entries(PI_MODULE_ALIASES));

/**
 * Whether the string that ends before `end` in `source`, after the token `previous`, names the module something imports:
 * after `from` or `import`, or the only argument of `import(` or `require(` (followed by `)`, or by `,` and an import's
 * options).
 */
function importsByName(previous, source, end) {
	if (previous.kind === "word") return !previous.property && (previous.text === "from" || previous.text === "import");
	if (!(previous.kind === "punctuator" && previous.text === "(" && previous.call)) return false;
	let i = end;
	while (i < source.length && /\s/.test(source[i])) i++;
	return source[i] === ")" || source[i] === ",";
}

// --- import.meta in the copies --------------------------------------------------------------------------------------

/*
 * Bundling resolves `import.meta.url` once, to the output file, and so `import.meta.dir`, `dirname`, `filename`, `path` and
 * `file`. In the executable every compiled-in module then says it is `/$bunfs/root/pi-bin`, and an extension that finds its
 * `skills/`, `prompts/` or themes next to its own module, as `join(dirname(fileURLToPath(import.meta.url)), "..", "skills")`,
 * looks for `/$bunfs/skills`, which cannot exist. Pi passes over a path that does not exist, so those resources are lost
 * with a single warning.
 *
 * Each copy therefore has those expressions replaced with what they are in the file it was copied from: the installed
 * extension, by the path Pi loads it from when it loads it from its files. With `pi-bolt compile` that is right for as long as
 * the compiled-in extension runs: Pi-Bolt uses it only while its installed copy is unchanged (core/extensions/compiled.ts),
 * and loads the installed files instead once they change. A build from `build-pi.sh --plugins-from-installed` has no such
 * check, so its extensions read those files from where they are installed, and need to stay installed. Only the copies are
 * changed. A manifest written by hand (`--plugins`) is not this script's, and is built as it is: its folder may be anywhere,
 * and the executable may go to another machine.
 *
 * The same goes for what the bundler resolves per module against the copy, which `pi-bolt compile` deletes, or leaves to
 * resolve as the program runs from the executable's own file system, `/$bunfs` (#5 of the loader-parity review): `__dirname`
 * and `__filename` (a data file, or a native addon loaded as node-gyp-build and bindings load one), `require.resolve()`,
 * `import.meta.resolve()`, and a require() or an import() of anything but a string (a module chosen as it runs). Each is
 * replaced with what it is for the file the copy was made from, so it finds what the installed extension finds. A module
 * that declares `__dirname`, `__filename` or `require` itself keeps its own. `import.meta.main` and the like are left alone.
 */

/** The `import.meta` properties the bundler resolves to the output file, and what each is for a module at `file`. */
const IMPORT_META = {
	url: (file) => pathToFileURL(file).href,
	dir: (file) => dirname(file),
	dirname: (file) => dirname(file),
	filename: (file) => file,
	path: (file) => file,
	file: (file) => basename(file),
};
const IMPORT_META_AT = new RegExp(`import\\s*\\.\\s*meta\\s*\\.\\s*(${Object.keys(IMPORT_META).join("|")})(?![\\w$])`, "y");
const IMPORT_META_RESOLVE_AT = /import\s*\.\s*meta\s*\.\s*resolve(?![\w$])/y;
/** `.resolve` after `require`. */
const REQUIRE_RESOLVE_AT = /\s*\.\s*resolve(?![\w$])/y;
/** The `(` of a call whose first argument is not a string or a template literal, which the bundler resolves itself. */
const CALL_OF_EXPRESSION_AT = /\s*\((?=\s*[^\s"'`)])/y;

/** Pi's names for its own modules, which Pi gives an extension whatever it has installed (VIRTUAL_MODULES). */
const PI_MODULE = "/^(?:typebox|@sinclair\\/typebox|@(?:earendil-works|mariozechner)\\/pi-(?:agent-core|ai|coding-agent|tui))(?:\\/|$)/";

/**
 * `text` as a JavaScript string literal for code that is made here, with the characters that can end a script or a line
 * written as escapes (as pi-bolt-compile.ts's jsStringLiteral).
 */
const codeString = (text) =>
	JSON.stringify(text).replace(/[<>/\u2028\u2029]/g, (char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`);

/** Code for `require` as in the module at `file`: createRequire for that file. */
const requireFrom = (file) => `process.getBuiltinModule("node:module").createRequire(${codeString(file)})`;

/**
 * Code for a function that resolves an import()'s specifier as from the module at `file`: a path, or a package installed
 * there, to its file; Pi's own modules, a URL, a built-in, and what does not resolve, as they are (the import() then finds
 * those as it did).
 */
const resolveFrom = (file) =>
	`((s) => { if (typeof s !== "string" || ${PI_MODULE}.test(s) || /^[a-z][a-z0-9+.-]*:/i.test(s)) return s; try { return Bun.resolveSync(s, ${codeString(dirname(file))}); } catch { return s; } })`;

/** Code for `import.meta.resolve` of the module at `file`: the URL of what a specifier resolves to from there. */
const metaResolveFrom = (file) =>
	`((s) => { const p = Bun.resolveSync(s, ${codeString(dirname(file))}); return p.startsWith("/") ? Bun.pathToFileURL(p).href : p; })`;

/** Words after which a `/` begins a regular expression rather than dividing, and a `{` an object rather than a block. */
const BEFORE_EXPRESSION = new Set(
	"return typeof instanceof in of new delete void throw case do else yield await extends".split(" "),
);
/** Words whose `(...)` is followed by a statement, so a `/` after the `)` begins a regular expression. */
const BEFORE_CONDITION = new Set(["if", "while", "for", "with"]);
/** Punctuators after which a `{` is an object literal rather than a block. */
const BEFORE_OBJECT = new Set("( [ , = ? : ! & | + - * % ^ ~ < >".split(" "));
const isWordChar = (char) => /[\w$\u0080-￿]/.test(char);
const isWordStart = (char) => char !== undefined && isWordChar(char) && !/[0-9]/.test(char);

/**
 * `source`, the copy of a module at `file`, as the build is to have it: each `import.meta.<property>` above replaced by its
 * value for a module at `file`, as a string literal; `__dirname`, `__filename`, `require.resolve`, `import.meta.resolve` and
 * each require() or import() of an expression with code for what they are at `file` (above); and each of Pi's other names
 * for its modules (PI_MODULE_ALIASES) that it imports a module by replaced with the name the build resolves: the string of
 * an `import ... from`, `export ... from` or `import "..."`, or the only argument of an `import()` or a `require()`
 * (TypeScript's `import x = require()` too). A method named `import` or `require` is not a call.
 *
 * Only code is changed: the scan steps over comments, strings, the text of template literals (not what is inside `${}`),
 * regular expressions and, in a .jsx or .tsx file, JSX text and attribute strings (not what is inside `{}`). A comment or a
 * message that mentions `import.meta.url` therefore keeps it, and so does a string "@mariozechner/pi-tui" that imports
 * nothing. Line numbers do not change.
 *
 * It is a scanner, not a parser, and guesses where a parser would know:
 * - A `/` begins a regular expression or divides, by the token before it, as JavaScript tools commonly do. After `)` it
 *   divides, unless the `(` came after `if`, `while`, `for` or `with`; after `}` it divides when the `{` began an object
 *   literal (after `=`, `(`, `,`, `:`, `return` and the like), and otherwise begins a regular expression.
 * - In a .jsx or .tsx file, a `<` where an expression may begin, followed by a name or `>`, begins JSX, unless it reads
 *   `<T,` or `<T extends`, a generic arrow function. A generic function *type* after `:` (`const f: <T>(x: T) => T`) is
 *   taken for JSX. A .js file is not scanned for JSX: a `<` there is a comparison more often than a tag.
 * - A string or regular expression ends at the end of its line at the latest, so a wrong guess cannot hide the rest of
 *   the file.
 * A wrong guess either leaves an `import.meta.url` in code as it was, which is what bundling gave it before, or changes one
 * in text.
 */
export function rewriteCopy(source, file) {
	const jsx = /\.[jt]sx$/.test(file);
	const edits = [];
	/**
	 * Whether the `(` at `open`, after a name, begins a method's parameters rather than a call's arguments (`async
	 * import(url) {`, `require(id): Module {`): after a modifier or a `*`, or followed by a body (`{`) or, where a member
	 * begins, a return type (`:`). Undecided (a `(` without its `)` on the way) counts as a method.
	 */
	const isMethod = (open) => {
		if (previous.kind === "word" && /^(?:async|get|set|static|public|private|protected|override|readonly)$/.test(previous.text)) {
			return true;
		}
		if (previous.kind === "punctuator" && previous.text === "*") return true;
		let depth = 0;
		let j = open;
		for (; j < length; j++) {
			const char = source[j];
			if (char === "(") depth++;
			else if (char === ")" && --depth === 0) break;
			else if (char === '"' || char === "'" || char === "`") {
				for (j++; j < length && source[j] !== char; j++) if (source[j] === "\\") j++;
			}
		}
		if (j >= length) return true;
		const after = source.slice(j + 1).match(/^\s*(.)/s)?.[1];
		const member = previous.kind === "punctuator" && ["{", "}", ";", ","].includes(previous.text);
		return after === "{" || (after === ":" && member);
	};
	const declared = new Map();
	/** Whether the module declares `name` itself (then its own is left alone): a guess, which errs towards yes. */
	const declares = (name) => {
		if (!declared.has(name)) {
			declared.set(
				name,
				new RegExp(
					[
						`\\b(?:const|let|var|function|class)\\s+${name}\\b`,
						`(?<![.\\w$])${name}\\s*=(?![=>])`,
						`\\bimport\\s*(?:\\*\\s*as\\s+)?${name}\\b`,
						`\\bimport\\s*\\{[^}]*\\b${name}\\b`,
						`[(,]\\s*${name}\\s*(?:[,=][^()]*)?\\)\\s*(?::[^=;{]*)?(?:=>|\\{)`,
						// (A shorthand property, which may be a destructuring pattern that declares it.)
						`[{,]\\s*${name}\\s*[,}]`,
					].join("|"),
				).test(source),
			);
		}
		return declared.get(name);
	};
	/**
	 * The last token, for the guesses: a word (its text, and whether it is a `property`, after a `.`), a value, or a
	 * punctuator (its text). `expression` on a punctuator overrides whether an expression may begin after it (a `)` or a
	 * `}`); `call` on a `(` says it opens the arguments of an `import()` or a `require()`.
	 */
	let previous = { kind: "punctuator", text: "" };
	/** The open `(`: whether each came after `if`, `while`, `for` or `with`. */
	const parens = [];
	/** The open `{`, `${` and JSX `{`: "block", "object", "template", or the JSX element to go back to. */
	const braces = [];
	const length = source.length;
	let i = 0;

	const expressionMayBegin = () =>
		previous.kind === "word"
			? BEFORE_EXPRESSION.has(previous.text)
			: previous.kind === "punctuator" &&
				(previous.expression ?? !(previous.text === ")" || previous.text === "]" || previous.text === "}"));
	const skipQuoted = (quote) => {
		for (i++; i < length; i++) {
			const char = source[i];
			if (char === "\\") i++;
			else if (char === "\n") return;
			else if (char === quote) {
				i++;
				return;
			}
		}
	};
	/** The text of a template literal from `i`; true when it stops at a `${`. */
	const skipTemplateText = () => {
		for (; i < length; i++) {
			const char = source[i];
			if (char === "\\") i++;
			else if (char === "`") {
				i++;
				return false;
			} else if (char === "$" && source[i + 1] === "{") {
				i += 2;
				return true;
			}
		}
		return false;
	};
	/** A regular expression from the `/` at `i`; false (and `i` unmoved) when the line ends first. */
	const skipRegExp = () => {
		let j = i + 1;
		let inClass = false;
		for (; j < length; j++) {
			const char = source[j];
			if (char === "\n") return false;
			if (char === "\\") j++;
			else if (char === "[") inClass = true;
			else if (char === "]") inClass = false;
			else if (char === "/" && !inClass) break;
		}
		if (j >= length) return false;
		for (j++; j < length && isWordChar(source[j]); j++);
		i = j;
		return true;
	};
	/** Whether the `<` at `i` begins JSX: `<>`, or `<name` that is not `<T,` or `<T extends`. */
	const jsxBegins = () => {
		if (source[i + 1] === ">") return true;
		if (!isWordStart(source[i + 1])) return false;
		let j = i + 2;
		while (j < length && isWordChar(source[j])) j++;
		while (j < length && /\s/.test(source[j])) j++;
		return !(source[j] === "," || /^extends\b/.test(source.slice(j, j + 8)));
	};
	/**
	 * JSX from `i`, for `element` (how many tags are open, whether the one being read closes) in `mode`: inside a `tag`, or
	 * between tags (`children`). Stops when the outermost element ends (the JSX is then a value), or at a `{`, whose code the
	 * main loop reads; its `}` comes back here.
	 */
	const scanJsx = (element, mode) => {
		while (i < length) {
			const char = source[i];
			if (mode === "children") {
				if (char === "{") break;
				if (char === "<") {
					element.closing = source[i + 1] === "/";
					if (!element.closing) element.open++;
					i += element.closing ? 2 : 1;
					mode = "tag";
				} else {
					i++;
				}
			} else if (char === "{") {
				break;
			} else if (char === '"' || char === "'") {
				// (An attribute's string: no escapes, and it may run over lines.)
				const end = source.indexOf(char, i + 1);
				i = end === -1 ? length : end + 1;
			} else if (char === "/" && source[i + 1] === ">") {
				i += 2;
				if (--element.open === 0) break;
				mode = "children";
			} else if (char === ">") {
				i++;
				if (element.closing && --element.open === 0) break;
				mode = "children";
			} else {
				i++;
			}
		}
		if (i < length && source[i] === "{") {
			braces.push({ element, mode });
			previous = { kind: "punctuator", text: "{" };
			i++;
		} else {
			previous = { kind: "value" };
		}
	};

	if (source.startsWith("#!")) i = source.indexOf("\n") === -1 ? length : source.indexOf("\n");
	while (i < length) {
		const char = source[i];
		const next = source[i + 1];
		if (char === "/" && next === "/") {
			const end = source.indexOf("\n", i);
			i = end === -1 ? length : end;
		} else if (char === "/" && next === "*") {
			const end = source.indexOf("*/", i + 2);
			i = end === -1 ? length : end + 2;
		} else if (/\s/.test(char)) {
			i++;
		} else if (char === "'" || char === '"') {
			const start = i;
			skipQuoted(char);
			const alias = source[i - 1] === char ? ALIASES.get(source.slice(start + 1, i - 1)) : undefined;
			if (alias !== undefined && importsByName(previous, source, i)) {
				edits.push({ start, end: i, code: JSON.stringify(alias), breaks: "" });
			}
			previous = { kind: "value" };
		} else if (char === "`") {
			i++;
			if (skipTemplateText()) {
				braces.push("template");
				previous = { kind: "punctuator", text: "{" };
			} else {
				previous = { kind: "value" };
			}
		} else if (char === "(") {
			parens.push(previous.kind === "word" && BEFORE_CONDITION.has(previous.text));
			const call =
				previous.kind === "word" && !previous.property && (previous.text === "import" || previous.text === "require");
			previous = { kind: "punctuator", text: "(", call };
			i++;
		} else if (char === ")") {
			previous = { kind: "punctuator", text: ")", expression: parens.pop() === true };
			i++;
		} else if (char === "{") {
			const object =
				previous.kind === "word"
					? BEFORE_EXPRESSION.has(previous.text) && previous.text !== "else" && previous.text !== "do"
					: previous.kind === "punctuator" && BEFORE_OBJECT.has(previous.text);
			braces.push(object ? "object" : "block");
			previous = { kind: "punctuator", text: "{" };
			i++;
		} else if (char === "}") {
			i++;
			const brace = braces.pop();
			if (brace === "template") {
				if (skipTemplateText()) {
					braces.push("template");
					previous = { kind: "punctuator", text: "{" };
				} else {
					previous = { kind: "value" };
				}
			} else if (typeof brace === "object") {
				scanJsx(brace.element, brace.mode);
			} else {
				previous = { kind: "punctuator", text: "}", expression: brace !== "object" };
			}
		} else if (char === "/" && expressionMayBegin() && skipRegExp()) {
			previous = { kind: "value" };
		} else if (char === "<" && jsx && expressionMayBegin() && jsxBegins()) {
			scanJsx({ open: 0, closing: false }, "children");
		} else if (char === "=" && next === ">") {
			// (After `=>`, a `{` is a function's body.)
			previous = { kind: "punctuator", text: "=>" };
			i += 2;
		} else if (isWordStart(char)) {
			const start = i;
			for (i++; i < length && isWordChar(source[i]); i++);
			const word = source.slice(start, i);
			// (Not after a `.`: `x.import` is a property, not the keyword.)
			const property = previous.kind === "punctuator" && previous.text === ".";
			/** Replaces `source` from `start` to `end` with `code`, and goes on after it, `previous` being a value. */
			const replace = (end, code) => {
				// (Followed by the line breaks it spanned, if any, so that line numbers stay as they were.)
				const breaks = "\n".repeat(source.slice(start, end).split("\n").length - 1);
				edits.push({ start, end, code, breaks });
				i = end;
				previous = { kind: "value" };
			};
			if (word === "import" && !property) {
				IMPORT_META_AT.lastIndex = start;
				const match = IMPORT_META_AT.exec(source);
				if (match) {
					replace(start + match[0].length, JSON.stringify(IMPORT_META[match[1]](file)));
					continue;
				}
				IMPORT_META_RESOLVE_AT.lastIndex = start;
				const resolveMatch = IMPORT_META_RESOLVE_AT.exec(source);
				if (resolveMatch) {
					replace(start + resolveMatch[0].length, metaResolveFrom(file));
					continue;
				}
				// (An import() of anything but a string: from the installed file, not from the executable's file system.)
				CALL_OF_EXPRESSION_AT.lastIndex = i;
				const call = CALL_OF_EXPRESSION_AT.exec(source);
				if (call && !isMethod(i + call[0].length - 1)) {
					replace(i + call[0].length, `((s, o) => import(${resolveFrom(file)}(s), o))(`);
					continue;
				}
			}
			if ((word === "__dirname" || word === "__filename") && !property && !declares(word)) {
				// (Not a property's name, `{ __dirname: ... }` or a method's.)
				const after = source.slice(i).match(/^\s*(.)/s)?.[1];
				const key = previous.kind === "punctuator" && (previous.text === "{" || previous.text === ",");
				if (!(key && (after === ":" || after === "("))) {
					replace(i, JSON.stringify(word === "__dirname" ? dirname(file) : file));
					continue;
				}
			}
			if (word === "require" && !property && !declares(word)) {
				REQUIRE_RESOLVE_AT.lastIndex = i;
				const resolveMatch = REQUIRE_RESOLVE_AT.exec(source);
				if (resolveMatch) {
					replace(i + resolveMatch[0].length, `${requireFrom(file)}.resolve`);
					continue;
				}
				CALL_OF_EXPRESSION_AT.lastIndex = i;
				const call = CALL_OF_EXPRESSION_AT.exec(source);
				if (call && !isMethod(i + call[0].length - 1)) {
					replace(i + call[0].length, `${requireFrom(file)}(`);
					continue;
				}
			}
			previous = { kind: "word", text: word, property };
		} else if (/[0-9]/.test(char)) {
			for (i++; i < length && /[\w.]/.test(source[i]); i++);
			previous = { kind: "value" };
		} else {
			previous = { kind: "punctuator", text: char };
			i++;
		}
	}

	let rewritten = "";
	let at = 0;
	for (const edit of edits) {
		rewritten += `${source.slice(at, edit.start)}${edit.code}${edit.breaks}`;
		at = edit.end;
	}
	return rewritten + source.slice(at);
}

/** What a module holds when rewriteCopy may change it besides Pi's other names (a quick test before scanning it). */
const REWRITTEN = ["meta", "__dirname", "__filename", "require", "import"];

/** The scripts Bun bundles, less type declarations (which hold no values). */
const isScript = (name) => /\.(?:[cm]?[jt]s|[jt]sx)$/.test(name) && !/\.d\.[cm]?ts$/.test(name);

/**
 * Rewrites every script of `staged`, a copy of `original`, as the build is to have it (rewriteCopy): `import.meta` pinned
 * to the file it was copied from, and Pi's other names for its modules replaced. `original` is the path as Pi finds it, not
 * resolved through links: Pi loads an extension by that path, and so do the modules it imports.
 */
function rewriteCopies(staged, original) {
	for (const entry of readdirSync(staged, { withFileTypes: true })) {
		const path = join(staged, entry.name);
		if (entry.isDirectory()) {
			rewriteCopies(path, join(original, entry.name));
			continue;
		}
		if (!entry.isFile() || !isScript(entry.name)) continue;
		const bytes = readFileSync(path);
		if (!REWRITTEN.some((word) => bytes.includes(word)) && !ALIASED_PREFIXES.some((prefix) => bytes.includes(prefix))) {
			continue;
		}
		const source = bytes.toString("utf8");
		// (A file that is not UTF-8 is left as it is rather than rewritten with replacement characters.)
		if (!Buffer.from(source, "utf8").equals(bytes)) continue;
		const rewritten = rewriteCopy(source, join(original, entry.name));
		if (rewritten === source) continue;
		// (A copy keeps its file's mode, and an installed file may be read-only.)
		chmodSync(path, statSync(path).mode | 0o200);
		writeFileSync(path, rewritten);
	}
}

// --- output ---------------------------------------------------------------------------------------------------------

/**
 * The factory of an extension's module as Pi's loader takes it from its files: jiti's `default: true` interop, `default`
 * when the module has one, else the module itself. TypeScript's CommonJS output (`exports.__esModule = true; exports.default
 * = factory`) is imported by the bundler as `{ default: module.exports }`, so its factory is one `default` further down.
 * Anything but a function is no factory, which Pi reports as it does for a module loaded from its files.
 */
const FACTORY_OF = [
	"/** The factory of an extension's module, as Pi's loader takes it from its files (jiti's `default` interop). */",
	'function factoryOf(module: { default?: unknown }): InlineExtension["factory"] {',
	"\tlet value: unknown = module.default ?? module;",
	'\tif (typeof value !== "function" && value !== null && typeof value === "object" && "default" in value) {',
	"\t\tvalue = (value as { default?: unknown }).default;",
	"\t}",
	'\treturn (typeof value === "function" ? value : undefined) as InlineExtension["factory"];',
	"}",
];

/** `text` as a string literal in generated code: JSON's, with the characters that can end a script or a line as escapes. */
function codeLiteral(text) {
	return JSON.stringify(text).replace(
		/[<>\u2028\u2029]/g,
		(char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`,
	);
}

export function renderManifest(plan) {
	const lines = [
		"/**",
		" * Generated: the extensions found on this machine, compiled into the executable.",
		" *",
		" * Written by scripts/gen-plugin-manifest.mjs. Do not edit: the generator replaces this file, and the folders",
		" * beside it, every time it runs. The extensions came from",
	];
	// (A path is the user's: a `*/` or a line break in it would end the comment.)
	for (const root of plan.roots) lines.push(` *   ${root.stageDir}/ <- ${JSON.stringify(root.root).replaceAll("*/", "*\\/")}`);
	lines.push(
		" *",
		" * Each is named, so it is a built-in extension once compiled in: `-builtin:<name>` in the `extensions` setting",
		" * turns one off, and the same extension installed at run time replaces it instead of conflicting.",
		" *",
		" * Each is loaded with an import() of its own, when Pi loads it and not before: the build splits its code (--splitting),",
		" * so each extension, with the modules only it imports, is a file of its own, whose top-level code the compiler takes as",
		" * one function and refuses past 256 KB of bytecode (#129). Imported here, all the extensions' top-level code would be one",
		" * function. Pi loads its extensions one after the other, so each module runs, and one that waits at its top level",
		" * (`await`) finishes, before the next one starts (#142), as when Pi loads them from their files. One turned off is",
		" * never run, and one whose top-level code throws fails as it loads, as from its files, not every start.",
		" */",
		// A value import from the package would evaluate Pi's whole public API at every launch (docs/PLUGINS.md).
		'import type { InlineExtension } from "@earendil-works/pi-coding-agent";',
		"",
		...FACTORY_OF,
		"",
		"/** The factory of the extension `module` imports, imported when it is first called: one with none fails as from its files. */",
		'function lazy(module: () => Promise<{ default?: unknown }>): InlineExtension["factory"] {',
		"\treturn async (pi) => {",
		"\t\tconst factory = factoryOf(await module());",
		'\t\tif (!factory) throw new Error("Extension does not export a valid factory function");',
		"\t\treturn factory(pi);",
		"\t};",
		"}",
		"",
		"const plugins: InlineExtension[] = [",
	);
	// (Each string as a literal: a file name may hold a `"` or a `\`, which would end or change it.)
	for (const plugin of plan.plugins) {
		lines.push(
			`\t{ name: ${codeLiteral(plugin.name)}, builtin: true, replaceable: true, factory: lazy(() => import(${codeLiteral(plugin.importPath)})) },`,
		);
	}
	lines.push("];", "", "export default plugins;");
	return `${lines.join("\n")}\n`;
}

/** Pi's own packages (isPiPackage), which the build resolves to Pi's modules. A copy of one inside a plugin would be a second Pi. */
const isPiCopy = (source) => {
	const parts = source.split(sep);
	return parts.some(
		(part, i) =>
			part === "node_modules" &&
			(isPiPackage(parts[i + 1]) || (parts[i + 1]?.startsWith("@") && isPiPackage(`${parts[i + 1]}/${parts[i + 2]}`))),
	);
};

/**
 * Whether `source`, in the folder `from` being copied, is a link not to follow: one to a folder it is in, which followed
 * would be copied into itself without end (a pnpm workspace has these when two of its packages need each other, or one needs
 * itself); or one to nothing, such as an editor's `.#name.ts` lock file, which nothing can read (#8 of the loader-parity
 * review: copying it stopped the generator).
 */
function notToFollow(source, from) {
	let target;
	try {
		if (!lstatSync(source).isSymbolicLink()) return false;
	} catch {
		return false;
	}
	try {
		target = realpathSync(source);
	} catch {
		return true;
	}
	for (let at = dirname(source); ; at = dirname(at)) {
		try {
			if (realpathSync(at) === target) return true;
		} catch {
			// (Not there: it is not the target.)
		}
		if (at === from || dirname(at) === at) return false;
	}
}

/**
 * The files a copy leaves out besides .git: an extension's own TypeScript settings. The bundler takes the nearest
 * tsconfig.json (or jsconfig.json) for each module, and the kit tree's own is what resolves Pi's packages to the modules Pi
 * is built from. Under one of an extension's own, its imports of @earendil-works/pi-tui and pi-ai found the packages' dist
 * instead: a second copy of each, whose classes are not Pi's (`instanceof` fails) and whose state is not Pi's. Pi's loader
 * reads no tsconfig either, so the extension is built as it runs there.
 */
const NOT_COPIED = new Set([".git", "tsconfig.json", "jsconfig.json"]);

const LOADERS = { ".ts": "ts", ".mts": "ts", ".cts": "ts", ".tsx": "tsx", ".js": "js", ".mjs": "js", ".cjs": "js", ".jsx": "jsx" };

/**
 * The files of `root` (relative to it) that the modules `files` are, and import by a relative path, transitively, with the folder's
 * package.json (its `type` and `imports` say how they are read). Undefined when they cannot be known: without Bun, whose
 * transpiler reads the imports, or when a module does not parse, or imports a file outside `root` or one that does not
 * resolve.
 */
function importClosure(files, root) {
	if (typeof globalThis.Bun?.Transpiler !== "function") return undefined;
	// (Real paths throughout, as the resolver gives them: /var is /private/var on macOS.)
	const realRoot = realpathSync(root);
	const found = new Set(existsSync(join(realRoot, "package.json")) ? [join(realRoot, "package.json")] : []);
	const queue = files.map((file) => realpathSync(file));
	while (queue.length > 0) {
		const file = queue.pop();
		if (found.has(file)) continue;
		found.add(file);
		const loader = LOADERS[file.match(/\.[^./]*$/)?.[0] ?? ""];
		if (!loader) continue;
		let imports;
		try {
			imports = new globalThis.Bun.Transpiler({ loader }).scanImports(readFileSync(file, "utf8"));
		} catch {
			return undefined;
		}
		for (const { path } of imports) {
			// (A relative path, or a name the folder's package.json `imports` maps.)
			if (!/^(?:\.\.?(?:\/|$)|#)/.test(path)) continue;
			let resolved;
			try {
				resolved = globalThis.Bun.resolveSync(path, dirname(file));
			} catch {
				return undefined;
			}
			if (relative(realRoot, resolved).startsWith("..")) return undefined;
			queue.push(resolved);
		}
	}
	return [...found].map((file) => relative(realRoot, file));
}

function copyRoot(from, to) {
	const start = resolve(from);
	cpSync(start, to, {
		recursive: true,
		// An extension may be a symbolic link to somewhere else on this machine, which the executable has no file for.
		dereference: true,
		filter: (source) => !NOT_COPIED.has(basename(source)) && !isPiCopy(source) && !notToFollow(source, start),
	});
}

// --- the build without the JIT -------------------------------------------------------------------------------------

/*
 * Pi loads an extension's packages from their files with Node's conditions: of `"exports": { "bun": ..., "node": ... }` it
 * takes `node`. The bundler takes `bun` first, which in the build without the JIT may be code that cannot run there: a
 * package that calls native code through bun:ffi fails when it is called ("bun:ffi requires the JIT"), after its import
 * succeeded, so an extension that falls back to a Node package when the import fails never does (#11 of the loader-parity
 * review: pi-fff, @ff-labs/fff-bun then @ff-labs/fff-node). So, for that build (--no-jit), the copies of package.json files
 * lose their `bun` conditions, and each package Pi's loader refuses there (loader.ts, JIT_ONLY_PACKAGES) is replaced with
 * one whose import fails as it does from its files.
 */

/** Packages that work only through bun:ffi (core/extensions/loader.ts, JIT_ONLY_PACKAGES). */
const JIT_ONLY_PACKAGES = ["@ff-labs/fff-bun"];

/** `value` (a package.json's `exports` or `imports`) without its `bun` conditions, at any depth. */
export function withoutBunCondition(value) {
	if (Array.isArray(value)) return value.map(withoutBunCondition);
	if (!value || typeof value !== "object") return value;
	return Object.fromEntries(
		Object.entries(value)
			.filter(([key]) => key !== "bun")
			.map(([key, inner]) => [key, withoutBunCondition(inner)]),
	);
}

/**
 * Takes the `bun` conditions out of every package.json in `dir` (the plugin folder), and puts a failing import in each
 * JIT-only package.
 */
export function forBuildWithoutJit(dir) {
	const scope = basename(dir).startsWith("@") ? basename(dir) : undefined;
	const inNodeModules = basename(scope ? dirname(dir) : dir) === "node_modules";
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		const path = join(dir, entry.name);
		if (entry.isDirectory()) {
			const name = scope ? `${scope}/${entry.name}` : entry.name;
			if (inNodeModules && JIT_ONLY_PACKAGES.includes(name)) {
				rmSync(path, { recursive: true, force: true });
				mkdirSync(path);
				writeFileSync(join(path, "package.json"), `${JSON.stringify({ name, main: "index.js" })}\n`);
				writeFileSync(
					join(path, "index.js"),
					`throw new Error(${JSON.stringify(`${name} needs the JIT (bun:ffi), which this build of Pi-Bolt does not have: install the -jit build`)});\n`,
				);
				continue;
			}
			forBuildWithoutJit(path);
			continue;
		}
		if (entry.name !== "package.json" || !entry.isFile()) continue;
		const manifest = readJson(path);
		if (!manifest || (!manifest.exports && !manifest.imports)) continue;
		const changed = { ...manifest };
		if (manifest.exports) changed.exports = withoutBunCondition(manifest.exports);
		if (manifest.imports) changed.imports = withoutBunCondition(manifest.imports);
		if (JSON.stringify(changed) === JSON.stringify(manifest)) continue;
		chmodSync(path, statSync(path).mode | 0o200);
		writeFileSync(path, `${JSON.stringify(changed, null, "\t")}\n`);
	}
}

/**
 * Writes the folder `--plugins` takes. Returns the manifest's path. `out` is replaced when it holds a manifest from a
 * previous run, and refused when it holds anything else.
 */
export function writePluginFolder(plan, out) {
	const manifest = join(out, "plugins.ts");
	if (existsSync(out)) {
		if (readdirSync(out).length > 0 && !existsSync(manifest)) {
			die(`${out} is not empty and holds no generated plugins.ts: refusing to overwrite it`);
		}
		rmSync(out, { recursive: true, force: true });
	}
	mkdirSync(out, { recursive: true });
	for (const root of plan.roots) {
		const files = root.files && importClosure(root.files, resolve(root.root));
		if (files) {
			for (const file of files) cpSync(join(root.root, file), join(out, root.stageDir, file), { dereference: true });
		} else {
			copyRoot(root.root, join(out, root.stageDir));
		}
		rewriteCopies(join(out, root.stageDir), root.root);
	}
	// (An entry imported under another name: a copy of its copy, beside it, so that its imports resolve as they did.)
	for (const plugin of plan.plugins) {
		if (plugin.entryCopy) {
			cpSync(join(out, plugin.stageDir, plugin.entryCopy.from), join(out, plugin.stageDir, plugin.entryCopy.to));
		}
	}
	// One node_modules beside the manifest, as the hoisted root it came from has and as docs/PLUGINS.md expects: Node
	// resolution walks up from each staged folder, so every package finds its dependencies here.
	for (const dependency of plan.dependencies) {
		const staged = join(out, "node_modules", ...dependency.name.split("/"));
		copyRoot(dependency.from, staged);
		rewriteCopies(staged, dependency.from);
	}
	writeFileSync(manifest, renderManifest(plan));
	return manifest;
}

/** The file a copy in the plugin folder `out` (a real path) was made from, by the plan that made it. */
function originalOf(file, out, plan) {
	const rel = relative(out, file);
	if (rel.startsWith("..")) return undefined;
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

/**
 * Copies into the node_modules of the plugin folder `out` each package a copy imports that the build cannot resolve from
 * the copy but Pi finds from the file it was copied from (#7 of the loader-parity review): an extension in the agent
 * directory's extensions/ that imports a package installed in the agent directory's node_modules, a configured path in a
 * workspace whose packages are hoisted above it, a package that imports what it does not declare. Pi loads those from
 * their files and finds them, as Node resolution walks up from the file; the copy is elsewhere, and the build stopped.
 *
 * Found with the bundler (Bun.build, which only Bun has: under Node this does nothing), until it finds no more. Only a
 * static import or a require() is followed: an import() that does not resolve is left to run time (#160). Pi's own packages
 * are not: the build resolves those to Pi's. Adds what it copies to `plan.dependencies`. Returns what it copied.
 */
export async function copyDependenciesFound(plan, out) {
	if (typeof globalThis.Bun?.build !== "function") return [];
	const manifest = join(out, "plugins.ts");
	const realOut = realpathSync(out);
	const copied = [];
	for (let pass = 0; pass < 16; pass++) {
		const result = await globalThis.Bun.build({
			entrypoints: [manifest],
			target: "bun",
			format: "esm",
			splitting: true,
			throw: false,
		});
		let more = false;
		for (const log of result.logs) {
			const specifier = log.specifier;
			if (typeof specifier !== "string" || !log.position?.file) continue;
			if (log.importKind !== "import-statement" && log.importKind !== "require-call") continue;
			if (/^[./]/.test(specifier) || /^[a-z]+:/.test(specifier)) continue;
			const name = specifier.split("/").slice(0, specifier.startsWith("@") ? 2 : 1).join("/");
			if (isProvidedByPi(name) || plan.dependencies.some((dependency) => dependency.name === name)) continue;
			const original = originalOf(log.position.file, realOut, plan);
			const found = original && resolvePackageFrom(dirname(original), name);
			if (!found) continue;
			const from = realpathSync(found);
			for (const dependency of [{ name, from }, ...dependencyClosure(from)]) {
				if (plan.dependencies.some((known) => known.name === dependency.name)) continue;
				const staged = join(out, "node_modules", ...dependency.name.split("/"));
				copyRoot(dependency.from, staged);
				rewriteCopies(staged, dependency.from);
				plan.dependencies.push(dependency);
				copied.push(dependency);
				more = true;
			}
		}
		if (!more) break;
	}
	return copied;
}

// --- the command ----------------------------------------------------------------------------------------------------

const HELP = `Usage: scripts/gen-plugin-manifest.mjs --out DIR [options]

  --out DIR           where the plugin folder goes (required)
  --agent-dir DIR     the agent directory (default: $PI_CODING_AGENT_DIR, else ~/.pi/agent)
  --cwd DIR           also take DIR/${CONFIG_DIR_NAME}/extensions, a project's own folder
  --only a,b          only these extensions, by the name this prints (repeatable)
  --exclude a,b       every extension except these (repeatable)
  --no-packages       leave out the packages "pi install" installed
  --packages FILE     these packages (a JSON list of { source, root, entries }) instead of the "packages" setting's
  --enabled FILE      the extensions Pi loads (a JSON list of paths): any other found is turned off, and passed over
  --no-jit            for the build without the JIT: packages as Pi loads them there (no "bun" condition, no bun:ffi)
  --json              print the plan as JSON
`;

/** The list --packages names: each package's source, folder, and entry files. */
function readPackageList(file) {
	let list;
	try {
		list = JSON.parse(readFileSync(file, "utf8"));
	} catch (error) {
		die(`--packages ${file}: ${error instanceof Error ? error.message : String(error)}`);
	}
	const valid = (pkg) =>
		pkg &&
		typeof pkg.source === "string" &&
		typeof pkg.root === "string" &&
		Array.isArray(pkg.entries) &&
		pkg.entries.every((entry) => typeof entry === "string");
	if (!Array.isArray(list) || !list.every(valid)) die(`--packages ${file}: not a list of { source, root, entries }`);
	return list;
}

/** The list --enabled names: the paths of the extensions Pi loads. */
function readPathList(file) {
	let list;
	try {
		list = JSON.parse(readFileSync(file, "utf8"));
	} catch (error) {
		die(`--enabled ${file}: ${error instanceof Error ? error.message : String(error)}`);
	}
	if (!Array.isArray(list) || !list.every((path) => typeof path === "string")) die(`--enabled ${file}: not a list of paths`);
	return list;
}

function parseArgs(argv) {
	const options = {
		out: "",
		agentDir: "",
		cwd: "",
		only: [],
		exclude: [],
		noPackages: false,
		packages: undefined,
		enabled: undefined,
		noJit: false,
		json: false,
	};
	let index = 0;
	while (index < argv.length) {
		const arg = argv[index];
		const value = argv[index + 1];
		const needsValue = () => {
			if (value === undefined) die(`${arg} needs a value`);
			index += 1;
			return value;
		};
		index += 1;
		switch (arg) {
			case "--out": options.out = needsValue(); break;
			case "--agent-dir": options.agentDir = needsValue(); break;
			case "--cwd": options.cwd = needsValue(); break;
			case "--only": options.only.push(...needsValue().split(",").map((n) => n.trim()).filter(Boolean)); break;
			case "--exclude": options.exclude.push(...needsValue().split(",").map((n) => n.trim()).filter(Boolean)); break;
			case "--no-packages": options.noPackages = true; break;
			case "--packages": options.packages = readPackageList(needsValue()); break;
			case "--enabled": options.enabled = readPathList(needsValue()); break;
			case "--no-jit": options.noJit = true; break;
			case "--json": options.json = true; break;
			case "-h":
			case "--help": process.stdout.write(HELP); process.exit(0); break;
			default: die(`unknown option ${arg}`);
		}
	}
	return options;
}

/** The agent directory, as Pi resolves it (packages/coding-agent/src/config.ts, getAgentDir). */
export function defaultAgentDir(env = process.env) {
	const fromEnv = env.PI_CODING_AGENT_DIR;
	if (fromEnv) return fromEnv.startsWith("~") ? join(homedir(), fromEnv.slice(1)) : fromEnv;
	return join(homedir(), CONFIG_DIR_NAME, "agent");
}

/**
 * Whether this file is the command being run. Both sides through their links: `/var` is `/private/var` on macOS. (Its name
 * first: `pi-bolt compile` has this module in Pi-Bolt itself, which asks this at every start, without a file system call.)
 */
function isCommand() {
	if (basename(process.argv[1] ?? "") !== "gen-plugin-manifest.mjs") return false;
	try {
		return realpathSync(process.argv[1] ?? "") === realpathSync(join(HERE, "gen-plugin-manifest.mjs"));
	} catch {
		return false;
	}
}

/** The command: scripts/gen-plugin-manifest.mjs in a checkout, gen-plugin-manifest.mjs in a compile kit (this file). */
export async function main(argv) {
	const options = parseArgs(argv);
	if (!options.out) die("--out DIR is required");
	const agentDir = options.agentDir || defaultAgentDir();
	const plan = planPlugins({ ...options, agentDir });
	const problem = selectionProblem(plan, agentDir);
	if (problem) die(problem);

	const manifest = writePluginFolder(plan, resolve(options.out));
	const found = await copyDependenciesFound(plan, resolve(options.out));
	if (options.noJit) forBuildWithoutJit(resolve(options.out));

	if (options.json) {
		process.stdout.write(`${JSON.stringify({ manifest, ...plan }, null, 2)}\n`);
	} else {
		for (const plugin of plan.plugins) process.stderr.write(`  ${plugin.name} (${plugin.origin})\n`);
		if (plan.dependencies.length > 0) {
			process.stderr.write(`  with ${plan.dependencies.length} dependency package(s), copied to node_modules\n`);
		}
		for (const dependency of found) {
			process.stderr.write(`  ${dependency.name}: imported without being a dependency, copied from ${dependency.from}\n`);
		}
		for (const entry of plan.skipped) process.stderr.write(`  passed over: ${entry.path}\n    ${entry.reason}\n`);
		for (const entry of plan.excluded) process.stderr.write(`  left out: ${entry.name} (--${entry.by})\n`);
		// The manifest's path alone on stdout: build-pi.sh reads it with a command substitution.
		process.stdout.write(`${manifest}\n`);
	}
}

// Imported by the tests, by `pi-bolt compile` and by scripts/gen-plugin-manifest.mjs; run as a command in a compile kit.
if (isCommand()) await main(process.argv.slice(2));
