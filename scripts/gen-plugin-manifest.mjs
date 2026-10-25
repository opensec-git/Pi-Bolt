#!/usr/bin/env node
/**
 * Writes the plugin folder that `build-pi.sh --plugins` takes, from the extensions installed on this machine.
 *
 * docs/PLUGINS.md step 1 is four steps done by hand: repeat Pi's discovery rules, name each extension, copy its folder
 * where the bundler can reach it, and bring its dependencies. Every one of them fails quietly, because an extension left
 * out of the manifest still loads at run time. The session looks right and only the compiled-in list is short.
 *
 * The discovery rules below are Pi's, and Pi is the source of truth for them:
 * packages/coding-agent/src/core/extensions/loader.ts, `discoverExtensionsInDir` and `resolveExtensionEntries`. They are
 * repeated here rather than imported because this script belongs to Pi-Bolt and those functions are Pi's, and private.
 * `assertMatchesPiRules` in scripts/gen-plugin-manifest.test.mjs covers each rule, so a change in Pi shows up as a failing
 * test rather than as a short manifest.
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

/** Rule 1 and rules 2-3 for each subfolder. One level only, as Pi does. */
export function discoverExtensionsInDir(dir) {
	if (!existsSync(dir)) return [];
	let entries;
	try {
		entries = readdirSync(dir, { withFileTypes: true });
	} catch {
		return [];
	}
	const found = [];
	for (const entry of entries) {
		const path = join(dir, entry.name);
		if ((entry.isFile() || entry.isSymbolicLink()) && isExtensionFile(entry.name)) {
			found.push(path);
			continue;
		}
		if (entry.isDirectory() || entry.isSymbolicLink()) {
			found.push(...(resolveExtensionEntries(path) ?? []));
		}
	}
	return found;
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
 * A package's "pi.extensions" entry may be a folder: pi-openrouter-session declares "./extensions", which holds
 * openrouter-session.ts and no index. Pi loads that folder's own entries, else what discovery finds in it without dot
 * entries, node_modules, and what the folder's .gitignore, .ignore and .fdignore leave out (package-manager.ts,
 * `collectAutoExtensionEntries`). Imported as it is, the folder does not resolve and the build stops.
 */
export function expandPackageEntry(entry) {
	if (!statSync(entry).isDirectory()) return [entry];
	const ignored = ignoreRulesOf(entry);
	return (
		resolveExtensionEntries(entry) ??
		discoverExtensionsInDir(entry).filter((path) => {
			const first = relative(entry, path).split(sep)[0];
			const folder = join(entry, first) !== path;
			return !first.startsWith(".") && first !== "node_modules" && !ignored(first, folder);
		})
	);
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

/** Pi's own packages: the build resolves these to Pi's modules, and a copy of one would be a second Pi. */
const isPiPackage = (name) => name === "typebox" || name.startsWith("@earendil-works/pi-");

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

/** A package.json's dependency names that a build has to resolve: the required ones, and the optional ones installed. */
function dependencyNames(manifest) {
	const names = [];
	for (const field of ["dependencies", "optionalDependencies"]) {
		const value = manifest?.[field];
		if (value && typeof value === "object") names.push(...Object.keys(value));
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
 * be copied into the node_modules beside the manifest. `peerDependencies` are not walked -- those are the host's to
 * provide, and a missing one is a loud build error.
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
		if (entry.isDirectory() || entry.isSymbolicLink()) {
			if (resolveExtensionEntries(path)) continue;
			const empty = existsSync(path) && readdirSync(path).length === 0;
			skipped.push({
				path,
				reason: empty
					? "empty folder"
					: 'no index.ts, index.js, or package.json with a "pi.extensions" field (shared code, not an extension)',
			});
			continue;
		}
		if (entry.isFile() && !isExtensionFile(entry.name)) skipped.push({ path, reason: "not a .ts or .js file" });
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
	const addEntry = (path, origin, root) => {
		if (seen.has(resolve(path))) return;
		seen.add(resolve(path));
		entries.push({ path, origin, root });
	};
	for (const source of sources) {
		for (const path of discoverExtensionsInDir(source.root)) addEntry(path, source.origin, source.root);
	}
	const passedOver = [];
	for (const raw of configured) {
		// A pattern (`-path`, `!path`, `+path`, a glob) or `builtin:<name>` filters what Pi loads; it names no extension to copy.
		if (/^[!+-]/.test(raw) || /[*?]/.test(raw) || raw.startsWith("builtin:")) continue;
		const path = resolve(raw.startsWith("~") ? join(homedir(), raw.slice(1)) : raw);
		if (!existsSync(path)) {
			passedOver.push({ path, reason: "in the extensions setting, but not found" });
			continue;
		}
		if (statSync(path).isDirectory()) {
			const declared = resolveExtensionEntries(path);
			for (const entry of declared ?? discoverExtensionsInDir(path)) addEntry(entry, "configured", path);
			continue;
		}
		addEntry(path, "configured", dirname(path));
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

	for (const entry of entries) {
		const root = resolve(entry.root);
		if (!stageDirs.has(root)) {
			const stageDir = stageDirFor(entry.origin, root);
			stageDirs.set(root, stageDir);
			roots.push({ stageDir, root, origin: entry.origin });
		}
		const rel = relative(entry.root, entry.path).split(sep).join("/");
		const siblings = perFolder.get(`${root} ${rel.split("/")[0]}`) ?? 1;
		const name = unique(safeName(baseName(rel, siblings, basename(root))), takenNames, entry.origin);
		plugins.push({
			name,
			entry: entry.path,
			origin: entry.origin,
			stageDir: stageDirs.get(root),
			importPath: `./${stageDirs.get(root)}/${rel}`,
		});
	}

	const skipped = [...passedOver, ...roots.flatMap((root) => describeSkipped(root.root, seen))];

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
		const declared = resolved.declared && [...new Set(resolved.declared)];
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
			const rel = relative(resolved.dir, entry).split(sep).join("/");
			const base = declared.length === 1 ? resolved.name : `${resolved.name}-${withoutExtension(basename(entry))}`;
			const name = unique(safeName(base), takenNames, "package");
			plugins.push({
				name,
				entry,
				origin: "package",
				stageDir,
				importPath: `./${stageDir}/${rel}`,
			});
		}
	}

	const only = (options.only ?? []).filter(Boolean);
	const exclude = new Set(options.exclude ?? []);
	const chosen = [];
	/** Those --only or --exclude leave out, so that each extension found is accounted for. */
	const excluded = [];
	for (const plugin of plugins) {
		const by = only.length > 0 && !only.includes(plugin.name) ? "only" : exclude.has(plugin.name) ? "exclude" : "";
		if (by) excluded.push({ name: plugin.name, origin: plugin.origin, entry: plugin.entry, by });
		else chosen.push(plugin);
	}

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
	};
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
 * `import.meta.main`, `import.meta.resolve()` and the like are left alone, and so are `__dirname` and `__filename`, which
 * the bundler replaces itself, per module, with the path of the copy.
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
 * `source` with each `import.meta.<property>` above replaced by its value for a module at `file`, as a string literal.
 *
 * Only code is changed: the scan steps over comments, strings, the text of template literals (not what is inside `${}`),
 * regular expressions and, in a .jsx or .tsx file, JSX text and attribute strings (not what is inside `{}`). A comment or a
 * message that mentions `import.meta.url` therefore keeps it. Line numbers do not change.
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
export function pinImportMeta(source, file) {
	const jsx = /\.[jt]sx$/.test(file);
	const edits = [];
	/**
	 * The last token, for the guesses: a word (its text), a value, or a punctuator (its text). `expression` on a punctuator
	 * overrides whether an expression may begin after it (a `)` or a `}`).
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
			skipQuoted(char);
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
			previous = { kind: "punctuator", text: "(" };
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
			if (word === "import" && !(previous.kind === "punctuator" && previous.text === ".")) {
				IMPORT_META_AT.lastIndex = start;
				const match = IMPORT_META_AT.exec(source);
				if (match) {
					// (Followed by the line breaks it spanned, if any, so that line numbers stay as they were.)
					const breaks = "\n".repeat(match[0].split("\n").length - 1);
					edits.push({ start, end: start + match[0].length, value: IMPORT_META[match[1]](file), breaks });
					i = start + match[0].length;
					previous = { kind: "value" };
					continue;
				}
			}
			previous = { kind: "word", text: word };
		} else if (/[0-9]/.test(char)) {
			for (i++; i < length && /[\w.]/.test(source[i]); i++);
			previous = { kind: "value" };
		} else {
			previous = { kind: "punctuator", text: char };
			i++;
		}
	}

	let pinned = "";
	let at = 0;
	for (const edit of edits) {
		pinned += `${source.slice(at, edit.start)}${JSON.stringify(edit.value)}${edit.breaks}`;
		at = edit.end;
	}
	return pinned + source.slice(at);
}

/** The scripts Bun bundles, less type declarations (which hold no values). */
const isScript = (name) => /\.(?:[cm]?[jt]s|[jt]sx)$/.test(name) && !/\.d\.[cm]?ts$/.test(name);

/**
 * Pins `import.meta` (pinImportMeta) in every script of `staged`, a copy of `original`, to the file it was copied from.
 * `original` is the path as Pi finds it, not resolved through links: Pi loads an extension by that path, and so do the
 * modules it imports.
 */
function pinImportMetaInCopy(staged, original) {
	for (const entry of readdirSync(staged, { withFileTypes: true })) {
		const path = join(staged, entry.name);
		if (entry.isDirectory()) {
			pinImportMetaInCopy(path, join(original, entry.name));
			continue;
		}
		if (!entry.isFile() || !isScript(entry.name)) continue;
		const bytes = readFileSync(path);
		if (!bytes.includes("meta")) continue;
		const source = bytes.toString("utf8");
		// (A file that is not UTF-8 is left as it is rather than rewritten with replacement characters.)
		if (!Buffer.from(source, "utf8").equals(bytes)) continue;
		const pinned = pinImportMeta(source, join(original, entry.name));
		if (pinned === source) continue;
		// (A copy keeps its file's mode, and an installed file may be read-only.)
		chmodSync(path, statSync(path).mode | 0o200);
		writeFileSync(path, pinned);
	}
}

// --- output ---------------------------------------------------------------------------------------------------------

export function renderManifest(plan) {
	const lines = [
		"/**",
		" * Generated: the extensions found on this machine, compiled into the executable.",
		" *",
		" * Written by scripts/gen-plugin-manifest.mjs. Do not edit: the generator replaces this file, and the folders",
		" * beside it, every time it runs. The extensions came from",
	];
	for (const root of plan.roots) lines.push(` *   ${root.stageDir}/ <- ${root.root}`);
	lines.push(
		" *",
		" * Each is named, so it is a built-in extension once compiled in: `-builtin:<name>` in the `extensions` setting",
		" * turns one off, and the same extension installed at run time replaces it instead of conflicting.",
		" *",
		" * Each is loaded with import(), one after the other: the build splits its code (--splitting), so each extension, with",
		" * the modules only it imports, is a file of its own, whose top-level code the compiler takes as one function and refuses",
		" * past 256 KB of bytecode (#129). Imported here, all the extensions' top-level code would be one function. They still",
		" * run in this order, and one that waits at its top level (`await`) finishes before the next starts (#142).",
		" */",
		// A value import from the package would evaluate Pi's whole public API at every launch (docs/PLUGINS.md).
		'import type { InlineExtension } from "@earendil-works/pi-coding-agent";',
		"",
		"const plugins: InlineExtension[] = [",
	);
	for (const plugin of plan.plugins) {
		lines.push(
			`\t{ name: "${plugin.name}", builtin: true, replaceable: true, factory: (await import("${plugin.importPath}")).default },`,
		);
	}
	lines.push("];", "", "export default plugins;");
	return `${lines.join("\n")}\n`;
}

/** Pi's own packages, which the build resolves to Pi's modules. A copy of one inside a plugin would be a second Pi. */
const isPiCopy = (source) => {
	const parts = source.split(sep);
	return parts.some((part, i) => part === "node_modules" && (parts[i + 1] === "typebox" || parts[i + 1] === "@earendil-works"));
};

/**
 * Whether `source`, in the folder `from` being copied, is a link to a folder it is in: followed, it would be copied into
 * itself without end. A pnpm workspace has these when two of its packages need each other, or one needs itself.
 */
function linksBack(source, from) {
	let target;
	try {
		if (!lstatSync(source).isSymbolicLink()) return false;
		target = realpathSync(source);
	} catch {
		return false;
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

function copyRoot(from, to) {
	const start = resolve(from);
	cpSync(start, to, {
		recursive: true,
		// An extension may be a symbolic link to somewhere else on this machine, which the executable has no file for.
		dereference: true,
		filter: (source) => basename(source) !== ".git" && !isPiCopy(source) && !linksBack(source, start),
	});
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
		copyRoot(root.root, join(out, root.stageDir));
		pinImportMetaInCopy(join(out, root.stageDir), root.root);
	}
	// One node_modules beside the manifest, as the hoisted root it came from has and as docs/PLUGINS.md expects: Node
	// resolution walks up from each staged folder, so every package finds its dependencies here.
	for (const dependency of plan.dependencies) {
		const staged = join(out, "node_modules", ...dependency.name.split("/"));
		copyRoot(dependency.from, staged);
		pinImportMetaInCopy(staged, dependency.from);
	}
	writeFileSync(manifest, renderManifest(plan));
	return manifest;
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

function parseArgs(argv) {
	const options = {
		out: "",
		agentDir: "",
		cwd: "",
		only: [],
		exclude: [],
		noPackages: false,
		packages: undefined,
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

/** Whether this file is the command being run. Both sides through their links: `/var` is `/private/var` on macOS. */
function isCommand() {
	try {
		return realpathSync(process.argv[1] ?? "") === realpathSync(join(HERE, "gen-plugin-manifest.mjs"));
	} catch {
		return false;
	}
}

// Imported by the test; run only as a command.
if (isCommand()) {
	const options = parseArgs(process.argv.slice(2));
	if (!options.out) die("--out DIR is required");
	const plan = planPlugins({ ...options, agentDir: options.agentDir || defaultAgentDir() });

	const missing = options.only.filter((name) => !plan.plugins.some((plugin) => plugin.name === name));
	if (missing.length > 0) die(`no extension is named ${missing.join(", ")}`);
	if (plan.plugins.length === 0) die("no extensions found to compile in");

	const manifest = writePluginFolder(plan, resolve(options.out));

	if (options.json) {
		process.stdout.write(`${JSON.stringify({ manifest, ...plan }, null, 2)}\n`);
	} else {
		for (const plugin of plan.plugins) process.stderr.write(`  ${plugin.name} (${plugin.origin})\n`);
		if (plan.dependencies.length > 0) {
			process.stderr.write(`  with ${plan.dependencies.length} dependency package(s), copied to node_modules\n`);
		}
		for (const entry of plan.skipped) process.stderr.write(`  passed over: ${entry.path}\n    ${entry.reason}\n`);
		for (const entry of plan.excluded) process.stderr.write(`  left out: ${entry.name} (--${entry.by})\n`);
		// The manifest's path alone on stdout: build-pi.sh reads it with a command substitution.
		process.stdout.write(`${manifest}\n`);
	}
}
