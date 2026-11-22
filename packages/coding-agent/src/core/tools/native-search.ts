/**
 * Pi-Bolt: the search behind the grep and find tools, in-process (grep.ts and find.ts use it in Pi-Bolt builds; Pi runs
 * ripgrep and fd, which it downloads when they are not installed). Compiled ahead of time with the rest of Pi, it starts no
 * process and needs nothing installed.
 *
 * It follows ripgrep's and fd's rules for what is searched: the ignore files (.gitignore in a git repository, or anywhere for
 * find, as Pi runs fd with --no-require-git outside one; .ignore; .git/info/exclude; git's global excludes file), the parent
 * folders' up to the repository's root, a nested repository starting afresh; hidden files searched; symbolic links not
 * followed; binary files (with a NUL byte) skipped. Unlike them it never looks inside .git, and it returns results in path
 * order. Regular expressions are JavaScript's, with ripgrep's (?i) and (?P<name>...) understood; like ripgrep, a match is
 * within one line.
 */
import {
	type BigIntStats,
	close,
	closeSync,
	constants,
	type Dirent,
	existsSync,
	fstat,
	fstatSync,
	open as openFile,
	openSync,
	read,
	readSync,
	statSync,
} from "node:fs";
import { type FileHandle, open, readdir, stat } from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";

// --- globs --------------------------------------------------------------------------------------------------------------

/**
 * The most patterns braces expand to. Each group multiplies them: `{a,b}` twenty times over is a million patterns, and every
 * entry of a walk is matched against each.
 */
const MAX_BRACE_EXPANSIONS = 1024;

/**
 * `*.{ts,tsx}` -> `*.ts`, `*.tsx` (nested braces too); a pattern without braces as it is. Throws when that is more than
 * `limit` patterns.
 */
export function expandBraces(pattern: string, limit = MAX_BRACE_EXPANSIONS): string[] {
	if (limit < 1) throw new Error(`The glob's braces expand to too many patterns (at most ${MAX_BRACE_EXPANSIONS})`);
	let depth = 0;
	let open = -1;
	for (let i = 0; i < pattern.length; i++) {
		const c = pattern[i];
		if (c === "\\") {
			i++;
		} else if (c === "{") {
			if (depth === 0) open = i;
			depth++;
		} else if (c === "}" && depth > 0) {
			depth--;
			if (depth === 0) {
				const parts: string[] = [];
				let start = open + 1;
				let inner = 0;
				for (let j = open + 1; j < i; j++) {
					const d = pattern[j];
					if (d === "\\") j++;
					else if (d === "{") inner++;
					else if (d === "}") inner--;
					else if (d === "," && inner === 0) {
						parts.push(pattern.slice(start, j));
						start = j + 1;
					}
				}
				parts.push(pattern.slice(start, i));
				if (parts.length < 2) continue;
				const head = pattern.slice(0, open);
				const tail = pattern.slice(i + 1);
				const expanded: string[] = [];
				for (const part of parts) {
					expanded.push(...expandBraces(`${head}${part}${tail}`, limit - expanded.length));
				}
				return expanded;
			}
		}
	}
	return [pattern];
}

/** Whether `c` is in the class that starts at `p[start]` (`[`); and where the class ends. */
function matchClass(p: string, start: number, c: string): { matched: boolean; end: number } | undefined {
	let i = start + 1;
	const negated = p[i] === "!" || p[i] === "^";
	if (negated) i++;
	let matched = false;
	let first = true;
	while (i < p.length && (p[i] !== "]" || first)) {
		first = false;
		let low = p[i];
		if (low === "\\" && i + 1 < p.length) low = p[++i];
		let high = low;
		if (p[i + 1] === "-" && i + 2 < p.length && p[i + 2] !== "]") {
			high = p[i + 2] === "\\" && i + 3 < p.length ? p[i + 3] : p[i + 2];
			i += p[i + 2] === "\\" ? 3 : 2;
		}
		if (c >= low && c <= high) matched = true;
		i++;
	}
	if (i >= p.length) return undefined; // (No closing bracket: the `[` is a plain character.)
	return { matched: matched !== negated, end: i + 1 };
}

// Each `*` tries every place it could end, and the parts after it again from each of those. On some patterns that takes
// exponential time: `*a*a*a*a*a*a*a*a*a*a*b` against 40 `a`s took 28 seconds. So a match runs in up to two ways:
// - `globFrom` tries every place. Its own `*` loop (one at most: it returns after it) makes at most one call per place in the
//   text; the parts after it run in `globFromCounted`, which pays for each `*` loop it starts out of `freeSteps`. When that
//   runs out, `outOfSteps` decides the match. Nearly every match ends long before that.
// - `globFromMarked`, used by `outOfSteps`, starts again and marks each place a `*` was tried from. A place tried before has
//   failed (success ends the match), so none is tried twice: time proportional to the pattern's length times the text's.
// The budget is not reset for each match, and the first `*` loop is not paid for: on a build without the JIT, the reset and
// the count in every call cost a search over a large tree 3% more instructions. The loops below are paid for at their
// start, with at least half the calls they can make, rather than call by call, for the same reason. They are separate
// functions, not one with a flag: the marking code in the first made common matches up to 15% slower.

/** What `globFromCounted` may spend before `outOfSteps` decides the match: about that many calls, twice that at most. */
const FREE_STEPS = 65536;
let freeSteps = FREE_STEPS;

/** Whether `s` from `si` on matches `p` from `pi` on: the start of a match (see above). */
function globFrom(p: string, pi: number, s: string, si: number): boolean {
	while (pi < p.length) {
		const c = p[pi];
		if (c === "*") {
			if (p[pi + 1] === "*") {
				const next = pi + 2;
				if (p[next] === "/") {
					// `**/`: no folder, or any number of them.
					if (globFromCounted(p, next + 1, s, si)) return true;
					for (let k = si; k < s.length; k++) {
						if (s[k] === "/" && globFromCounted(p, next + 1, s, k + 1)) return true;
					}
					return false;
				}
				// `**` otherwise: anything, across folders.
				for (let k = s.length; k >= si; k--) {
					if (globFromCounted(p, next, s, k)) return true;
				}
				return false;
			}
			// `*`: anything within one name.
			for (let k = si; ; k++) {
				if (globFromCounted(p, pi + 1, s, k)) return true;
				if (k >= s.length || s[k] === "/") return false;
			}
		}
		if (si >= s.length) return false;
		if (c === "?") {
			if (s[si] === "/") return false;
			pi++;
			si++;
			continue;
		}
		if (c === "[") {
			const result = s[si] === "/" ? undefined : matchClass(p, pi, s[si]);
			if (result) {
				if (!result.matched) return false;
				pi = result.end;
				si++;
				continue;
			}
			if (s[si] === "/") return false;
		}
		const literal = c === "\\" && pi + 1 < p.length ? p[++pi] : c;
		if (s[si] !== literal) return false;
		pi++;
		si++;
	}
	return si === s.length;
}

/** `globFrom` for the parts after a `*`, paying for each `*` loop it starts; `outOfSteps` decides when it runs out. */
function globFromCounted(p: string, pi: number, s: string, si: number): boolean {
	while (pi < p.length) {
		const c = p[pi];
		if (c === "*") {
			freeSteps -= s.length;
			if (freeSteps < 0) return outOfSteps(p, s);
			if (p[pi + 1] === "*") {
				const next = pi + 2;
				if (p[next] === "/") {
					// `**/`: no folder, or any number of them.
					if (globFromCounted(p, next + 1, s, si)) return true;
					for (let k = si; k < s.length; k++) {
						if (s[k] === "/" && globFromCounted(p, next + 1, s, k + 1)) return true;
					}
					return false;
				}
				// `**` otherwise: anything, across folders.
				for (let k = s.length; k >= si; k--) {
					if (globFromCounted(p, next, s, k)) return true;
				}
				return false;
			}
			// `*`: anything within one name.
			for (let k = si; ; k++) {
				if (globFromCounted(p, pi + 1, s, k)) return true;
				if (k >= s.length || s[k] === "/") return false;
			}
		}
		if (si >= s.length) return false;
		if (c === "?") {
			if (s[si] === "/") return false;
			pi++;
			si++;
			continue;
		}
		if (c === "[") {
			const result = s[si] === "/" ? undefined : matchClass(p, pi, s[si]);
			if (result) {
				if (!result.matched) return false;
				pi = result.end;
				si++;
				continue;
			}
			if (s[si] === "/") return false;
		}
		const literal = c === "\\" && pi + 1 < p.length ? p[++pi] : c;
		if (s[si] !== literal) return false;
		pi++;
		si++;
	}
	return si === s.length;
}

/** The last match `outOfSteps` found no match for: the loops of that match still running end at once. */
const noMatch = { pattern: "", text: "" };

/**
 * Whether `s` matches `p`, decided with `globMatchMarked` once `freeSteps` runs out; the budget starts again. The loops still
 * running in `globFrom` and `globFromCounted` then end: on a match, with it; otherwise each `*` they start gets false. (The
 * matches before this one may have used up the budget: a common match that runs out is decided the same way.)
 */
function outOfSteps(p: string, s: string): boolean {
	if (p === noMatch.pattern && s === noMatch.text) return false;
	freeSteps = FREE_STEPS;
	if (globMatchMarked(p, s)) return true;
	noMatch.pattern = p;
	noMatch.text = s;
	return false;
}

/**
 * The places each `*` has been tried from in the current marked match: `marks[pi * width + si] === stamp` once the `*` at
 * `p[pi]` has been tried from `s[si]` on (for a `**` followed by `/`, row `pi + 1` holds the slashes tried from). One table
 * serves every match (a match is synchronous and never starts another); a new stamp clears it.
 */
const tried = { marks: new Uint32Array(0), stamp: 0, width: 0 };

/** Whether the `*` at `p[pi]` was tried from `s[si]` on before in this match; marks it tried. */
function triedBefore(pi: number, si: number): boolean {
	const at = pi * tried.width + si;
	if (tried.marks[at] === tried.stamp) return true;
	tried.marks[at] = tried.stamp;
	return false;
}

/**
 * `globFrom` without the limit, never trying a `*` twice from the same place. (A `*` is reached only from the parts before it,
 * so while a later part runs, the one loop of this `*` still going is the caller's, which has not reached the places after
 * its own: a marked place is one whose loop ended, in failure.) Each `*` loop goes forward, so that a marked place stands for
 * every place after it too.
 */
function globFromMarked(p: string, pi: number, s: string, si: number): boolean {
	while (pi < p.length) {
		const c = p[pi];
		if (c === "*") {
			if (p[pi + 1] === "*") {
				const next = pi + 2;
				if (p[next] === "/") {
					// `**/`: no folder, or any number of them.
					if (triedBefore(pi, si)) return false;
					if (globFromMarked(p, next + 1, s, si)) return true;
					for (let k = si; k < s.length; k++) {
						if (s[k] !== "/") continue;
						if (triedBefore(pi + 1, k)) return false;
						if (globFromMarked(p, next + 1, s, k + 1)) return true;
					}
					return false;
				}
				// `**` otherwise: anything, across folders.
				if (next === p.length) return true;
				for (let k = si; k <= s.length; k++) {
					if (triedBefore(pi, k)) return false;
					if (globFromMarked(p, next, s, k)) return true;
				}
				return false;
			}
			// `*`: anything within one name.
			for (let k = si; ; k++) {
				if (triedBefore(pi, k)) return false;
				if (globFromMarked(p, pi + 1, s, k)) return true;
				if (k >= s.length || s[k] === "/") return false;
			}
		}
		if (si >= s.length) return false;
		if (c === "?") {
			if (s[si] === "/") return false;
			pi++;
			si++;
			continue;
		}
		if (c === "[") {
			const result = s[si] === "/" ? undefined : matchClass(p, pi, s[si]);
			if (result) {
				if (!result.matched) return false;
				pi = result.end;
				si++;
				continue;
			}
			if (s[si] === "/") return false;
		}
		const literal = c === "\\" && pi + 1 < p.length ? p[++pi] : c;
		if (s[si] !== literal) return false;
		pi++;
		si++;
	}
	return si === s.length;
}

/** `globFromMarked` from the start, with the table made ready. */
function globMatchMarked(pattern: string, text: string): boolean {
	tried.width = text.length + 1;
	const size = (pattern.length + 1) * tried.width;
	if (tried.marks.length < size) {
		tried.marks = new Uint32Array(Math.max(size, 2 * tried.marks.length));
		tried.stamp = 0;
	} else if (tried.stamp === 0xffffffff) {
		tried.marks.fill(0);
		tried.stamp = 0;
	}
	tried.stamp++;
	return globFromMarked(pattern, 0, text, 0);
}

/** Whether `text` (a path with `/`) matches the glob `pattern`: `*`, `?` and `[...]` within a name, `**` across them. */
export function globMatch(pattern: string, text: string): boolean {
	return globFrom(pattern, 0, text, 0);
}

const GLOB_META = /[*?[\\]/;

/**
 * `globMatch` for one pattern, as a function: the common shapes (a name, `*.ext`, `prefix*`, `**` then a name) without the general
 * matcher. Every entry of a walk is checked against the ignore rules that `IgnoreSet` cannot look up, and against find's and
 * --glob's patterns.
 */
export function compileGlob(pattern: string): (text: string) => boolean {
	if (!GLOB_META.test(pattern)) return (text) => text === pattern;
	if (pattern.startsWith("*") && !GLOB_META.test(pattern.slice(1)) && !pattern.includes("/")) {
		const suffix = pattern.slice(1);
		return (text) => text.endsWith(suffix) && text.indexOf("/") === -1;
	}
	if (pattern.endsWith("*") && !pattern.endsWith("**") && !GLOB_META.test(pattern.slice(0, -1))) {
		const prefix = pattern.slice(0, -1);
		return (text) => text.startsWith(prefix) && text.indexOf("/", prefix.length) === -1;
	}
	if (pattern.startsWith("**/") && !pattern.slice(3).includes("/")) {
		const name = compileGlob(pattern.slice(3));
		return (text) => name(text.slice(text.lastIndexOf("/") + 1));
	}
	return (text) => globFrom(pattern, 0, text, 0);
}

// --- ignore files -------------------------------------------------------------------------------------------------------

interface IgnoreRule {
	/** The folder of the file it is from, relative to the walk's top folder ("" for the top). */
	base: string;
	/** `base` and a `/` ("" for the top): what the paths it applies to start with. */
	basePrefix: string;
	pattern: string;
	matches: (text: string) => boolean;
	negate: boolean;
	dirOnly: boolean;
	/** Matched against the path below `base`, not against the name alone. */
	anchored: boolean;
	/** From git's files (.gitignore, info/exclude, the excludes file), which a nested repository does not inherit. */
	git: boolean;
}

/** The rules of a .gitignore-style file (gitignore(5)). */
export function parseIgnoreFile(text: string, base: string, git: boolean): IgnoreRule[] {
	const rules: IgnoreRule[] = [];
	for (const raw of text.split(/\r?\n/)) {
		let line = raw.replace(/(?<!\\) +$/, "");
		if (!line || line.startsWith("#")) continue;
		let negate = false;
		if (line.startsWith("!")) {
			negate = true;
			line = line.slice(1);
		} else if (line.startsWith("\\!") || line.startsWith("\\#")) {
			line = line.slice(1);
		}
		const dirOnly = line.endsWith("/");
		if (dirOnly) line = line.slice(0, -1);
		if (!line) continue;
		const anchored = line.includes("/");
		if (line.startsWith("/")) line = line.slice(1);
		const basePrefix = base === "" ? "" : `${base}/`;
		rules.push({ base, basePrefix, pattern: line, matches: compileGlob(line), negate, dirOnly, anchored, git });
	}
	return rules;
}

/**
 * Ignore rules, looked up by what they match, as ripgrep does: the last rule that matches an entry decides, and the rules for
 * one name (`node_modules`), one extension (`*.log`) or one path (`/dist`) are found in a map instead of being matched one by
 * one. Every entry of a walk is checked, and on a build without the JIT matching each against every rule was most of the
 * walk's time.
 */
class IgnoreSet {
	readonly rules: readonly IgnoreRule[];
	/**
	 * The indexes, highest first, of the rules for one name, by that name; a rule for one path is there by the path's last name,
	 * the path (from the top folder) in `paths`.
	 */
	private readonly byName = new Map<string, number[]>();
	/** The indexes, highest first, of the rules for one extension (`.log`), by that extension. */
	private readonly byExtension = new Map<string, number[]>();
	private readonly paths: (string | undefined)[] = [];
	/** The indexes of the other rules, in order: matched one by one. */
	private readonly others: number[] = [];

	constructor(rules: readonly IgnoreRule[]) {
		this.rules = rules;
		for (let index = rules.length - 1; index >= 0; index--) {
			const key = lookupKey(rules[index]);
			if (!key) {
				this.others.push(index);
				continue;
			}
			let mapKey = key.key;
			if (key.by === "path") {
				this.paths[index] = key.key;
				mapKey = key.key.slice(key.key.lastIndexOf("/") + 1);
			}
			const map = key.by === "extension" ? this.byExtension : this.byName;
			const indexes = map.get(mapKey);
			if (indexes) indexes.push(index);
			else map.set(mapKey, [index]);
		}
		this.others.reverse();
	}

	/** Whether the entry at `rel` (relative to the top folder) is ignored: the last rule that matches it decides. */
	ignores(rel: string, isDir: boolean): boolean {
		const name = rel.slice(rel.lastIndexOf("/") + 1);
		let last = this.lastOf(this.byName.get(name), -1, rel, isDir);
		if (this.byExtension.size > 0) {
			const dot = name.lastIndexOf(".");
			if (dot !== -1) last = this.lastOf(this.byExtension.get(name.slice(dot)), last, rel, isDir);
		}
		// The other rules after the last one found: in order, each matched only if it could change the outcome (a rule that
		// ignores while the entry is not ignored, a negated one while it is). The negated rules a file ends with (`!a/**/*.x`)
		// are then not matched against every entry.
		let ignored = last !== -1 && !this.rules[last].negate;
		for (const index of this.others) {
			if (index <= last) continue;
			const rule = this.rules[index];
			if (rule.negate !== ignored || (rule.dirOnly && !isDir) || !rel.startsWith(rule.basePrefix)) continue;
			if (rule.matches(rule.anchored ? rel.slice(rule.basePrefix.length) : name)) ignored = !ignored;
		}
		return ignored;
	}

	/** The highest of `indexes` (rules a lookup found) above `above` whose rule matches the entry and applies to it; else `above`. */
	private lastOf(indexes: number[] | undefined, above: number, rel: string, isDir: boolean): number {
		if (!indexes) return above;
		for (const index of indexes) {
			if (index <= above) break;
			const rule = this.rules[index];
			const path = this.paths[index];
			if ((path === undefined || path === rel) && (!rule.dirOnly || isDir) && rel.startsWith(rule.basePrefix))
				return index;
		}
		return above;
	}
}

/**
 * Where `IgnoreSet` looks a rule up, when it is one of the shapes `compileGlob` matches without a glob: a name; `*.ext` (one dot);
 * and anchored, a path, or `**` then a name or `*.ext`. A rule is matched against the name or, anchored, the path below its base.
 */
function lookupKey(rule: IgnoreRule): { by: "name" | "extension" | "path"; key: string } | undefined {
	if (!rule.anchored) return nameKey(rule.pattern);
	if (!GLOB_META.test(rule.pattern)) return { by: "path", key: rule.basePrefix + rule.pattern };
	// (`**/x` matches the last name of the path below its base: the entry's name.)
	if (rule.pattern.startsWith("**/") && !rule.pattern.slice(3).includes("/")) return nameKey(rule.pattern.slice(3));
	return undefined;
}

/** `lookupKey` for a pattern matched against a name (it has no `/`). */
function nameKey(pattern: string): { by: "name" | "extension"; key: string } | undefined {
	if (!GLOB_META.test(pattern)) return { by: "name", key: pattern };
	// `*.log` matches the names that end with `.log`: those whose extension (from their last dot) is `.log`.
	const suffix = pattern.slice(1);
	if (pattern.startsWith("*") && suffix.startsWith(".") && !suffix.includes(".", 1) && !GLOB_META.test(suffix)) {
		return { by: "extension", key: suffix };
	}
	return undefined;
}

/** The most of an ignore file, git's config or a `.git` file that is read. */
const SMALL_FILE_BYTES = 4 * 1024 * 1024;

/**
 * The text of `file` if it is a regular file of at most SMALL_FILE_BYTES; otherwise (also a link to a device or a pipe, a
 * folder, a larger file) an error. Opened without blocking, so a pipe does not wait for a writer.
 */
function readSmallFile(file: string): string {
	const fd = openSync(file, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0));
	try {
		const stats = fstatSync(fd);
		if (!stats.isFile()) throw new Error(`${file} is not a regular file`);
		if (stats.size > SMALL_FILE_BYTES) throw new Error(`${file} is larger than ${SMALL_FILE_BYTES} bytes`);
		const buffer = Buffer.alloc(stats.size);
		let length = 0;
		while (length < buffer.length) {
			const read = readSync(fd, buffer, length, buffer.length - length, length);
			if (read === 0) break;
			length += read;
		}
		return buffer.toString("utf8", 0, length);
	} finally {
		closeSync(fd);
	}
}

/** What `readCached` made of a file, and the file's `stamp` then. */
interface Cached<T> {
	stamp: string;
	value: T;
}

/** The most files `readCached` remembers in each of its caches (the first remembered is let go first). */
const CACHED_FILES = 4096;

/**
 * A file changed this recently may change again without its stamp changing (on a file system whose times are as coarse as a
 * second), so `readCached` does not remember it: git's "racy" rule.
 */
const RACY_MS = 2500;

/**
 * What `parse` makes of a small file (as `readSmallFile` reads it), remembered across searches while the file's identity, size
 * and times stay the same: a search reads the same ignore files each time. Undefined when there is no such file to read.
 */
function readCached<T>(
	cache: Map<string, Cached<T>>,
	file: string,
	variant: string,
	parse: (text: string) => T,
): T | undefined {
	let stats: BigIntStats | undefined;
	try {
		stats = statSync(file, { bigint: true, throwIfNoEntry: false });
	} catch {
		return undefined;
	}
	// (A link to a device or a pipe, a folder, a larger file: not read, as `readSmallFile` refuses them.)
	if (!stats?.isFile() || stats.size > BigInt(SMALL_FILE_BYTES)) return undefined;
	const key = `${file}\0${variant}`;
	const stamp = `${stats.dev}:${stats.ino}:${stats.size}:${stats.mtimeNs}:${stats.ctimeNs}`;
	const cached = cache.get(key);
	if (cached?.stamp === stamp) return cached.value;
	cache.delete(key);
	let text: string;
	try {
		text = readSmallFile(file);
	} catch {
		return undefined;
	}
	const value = parse(text);
	// (Read after the stat: if it changed in between, the next search sees another stamp and reads it again.)
	if (Date.now() - Number(stats.mtimeMs > stats.ctimeMs ? stats.mtimeMs : stats.ctimeMs) > RACY_MS) {
		if (cache.size >= CACHED_FILES) {
			const first = cache.keys().next();
			if (!first.done) cache.delete(first.value);
		}
		cache.set(key, { stamp, value });
	}
	return value;
}

/** The rules of the ignore files read, by path and base. */
const ignoreFiles = new Map<string, Cached<IgnoreRule[]>>();

/** The rules of an ignore file; none if there is no such file. The rules may be shared: never changed. */
function readRules(file: string, base: string, git: boolean): readonly IgnoreRule[] {
	return readCached(ignoreFiles, file, `${base}\0${git}`, (text) => parseIgnoreFile(text, base, git)) ?? [];
}

/** ~/.gitconfig's core.excludesFile, as it is written. */
const gitConfigs = new Map<string, Cached<string | undefined>>();

/** git's global excludes file: core.excludesFile, or $XDG_CONFIG_HOME/git/ignore. */
function globalExcludesFile(): string {
	const value = readCached(gitConfigs, path.join(homedir(), ".gitconfig"), "", (config) => {
		const match = /^\s*excludesfile\s*=\s*(.+?)\s*$/im.exec(config);
		return match?.[1].replace(/^"(.*)"$/, "$1");
	});
	if (value !== undefined) return value.startsWith("~") ? path.join(homedir(), value.slice(1)) : value;
	// (No ~/.gitconfig, or no excludesFile in it: git's default place.)
	return path.join(process.env.XDG_CONFIG_HOME || path.join(homedir(), ".config"), "git", "ignore");
}

/**
 * The info/exclude file of the repository whose working folder is `dir`, as `git rev-parse --git-path info/exclude` finds it: in
 * `.git`, or, where `.git` is a file (a linked worktree, a submodule), in the folder it names, or in that one's common folder.
 */
function excludeFileOf(dir: string): string {
	const dotGit = path.join(dir, ".git");
	try {
		if (statSync(dotGit).isDirectory()) return path.join(dotGit, "info", "exclude");
		const gitDir = /^gitdir:\s*(.+?)\s*$/m.exec(readSmallFile(dotGit))?.[1];
		if (!gitDir) return path.join(dotGit, "info", "exclude");
		const resolved = path.resolve(dir, gitDir);
		let common = resolved;
		try {
			common = path.resolve(resolved, readSmallFile(path.join(resolved, "commondir")).trim());
		} catch {
			// (A submodule's folder, or an older layout: its own info/exclude.)
		}
		return path.join(common, "info", "exclude");
	} catch {
		return path.join(dotGit, "info", "exclude");
	}
}

/** The git repository folder `dir` is in (where .git is), or undefined. */
function repositoryOf(dir: string): string | undefined {
	for (let current = dir; ; ) {
		if (existsSync(path.join(current, ".git"))) return current;
		const parent = path.dirname(current);
		if (parent === current) return undefined;
		current = parent;
	}
}

const toPosix = (p: string) => (path.sep === "\\" ? p.split("\\").join("/") : p);

// --- the walk -----------------------------------------------------------------------------------------------------------

export interface WalkEntry {
	/** Relative to the search folder, with `/`. */
	rel: string;
	abs: string;
	kind: "file" | "dir" | "symlink";
}

export interface WalkOptions {
	/** The folder to search (absolute). */
	root: string;
	/** Use git's ignore files only in a git repository (ripgrep), or anywhere (fd --no-require-git). */
	requireGit: boolean;
	/** Files this takes are walked even when an ignore file leaves them out (ripgrep's --glob overrides them). */
	override?: (rel: string) => boolean;
	/**
	 * The entries returned (default: all). Folders are walked whether or not they are kept: what a search does not want is not
	 * held while the walk goes on (a walk through a whole disk finds millions of entries).
	 */
	keep?: (rel: string, kind: WalkEntry["kind"]) => boolean;
	signal?: AbortSignal;
}

interface DirTask {
	abs: string;
	rel: string;
	ignore: IgnoreSet;
	git: boolean;
}

/**
 * What the walk has still to give: an entry, or a folder's entries and folders in order (`children`, once its listing has been
 * asked for; undefined when it could not be read). `key` places it among its folder's: its name, and a folder's name and a `/`
 * (what is in a folder sorts as its path does).
 */
type Pending = { type: "entry"; key: string; entry: WalkEntry } | Folder;

interface Folder {
	type: "folder";
	key: string;
	task: DirTask;
	children: Promise<Pending[] | undefined> | undefined;
}

/**
 * How many folders are read at once at most, ahead of the walk. Reading a folder waits on the disk, not on the processor, so
 * several are read at once, the last found first: going depth first, the walk goes into one of them next. (Reading only the
 * next folders on the walk's own stack left it waiting for each new level in turn, idle half its time.)
 */
const FOLDERS_READING = 64;
/** How many folders are read ahead of the walk, and not yet gone into, at most (what a search that stops early wastes). */
const FOLDERS_AHEAD = 1024;

/** Every entry below `root` that the ignore files leave in (and `keep` takes), in path order. */
export async function walk(options: WalkOptions): Promise<WalkEntry[]> {
	const entries: WalkEntry[] = [];
	for await (const run of walkInOrder(options)) for (const entry of run) entries.push(entry);
	return entries;
}

/**
 * `walk`'s entries a run at a time, found as they are asked for: a search that has what it needs stops the walk there. They
 * come in path order, as strings sort (`a`, `a.b`, `a/b`): depth first, a folder's entries sorted by name, with what is in a
 * folder placed as if its name had a `/` after it.
 */
export async function* walkInOrder(options: WalkOptions): AsyncGenerator<WalkEntry[], void, undefined> {
	const root = path.resolve(options.root);
	const repository = repositoryOf(root);
	// Paths are matched relative to the top folder: the repository's, whose ignore files above the search folder apply too.
	const top = repository ?? root;
	const rootRel = toPosix(path.relative(top, root));
	const git = repository !== undefined || !options.requireGit;
	let rules: readonly IgnoreRule[] = [];
	if (repository) {
		rules = [...readRules(globalExcludesFile(), "", true), ...readRules(excludeFileOf(repository), "", true)];
		// The ignore files of the folders from the repository's down to the search folder's parent.
		let dir = repository;
		for (const segment of rootRel ? rootRel.split("/") : []) {
			const base = toPosix(path.relative(top, dir));
			rules = [
				...rules,
				...readRules(path.join(dir, ".gitignore"), base, true),
				...readRules(path.join(dir, ".ignore"), base, false),
			];
			dir = path.join(dir, segment);
		}
	}

	const topRel = (rel: string) => (rootRel ? (rel ? `${rootRel}/${rel}` : rootRel) : rel);

	/** A folder's entries and folders, in the order the walk gives them. */
	const visit = (task: DirTask, dirents: Dirent[]): Pending[] => {
		let hasGit = false;
		let hasGitignore = false;
		let hasIgnore = false;
		for (const dirent of dirents) {
			if (dirent.name === ".git") hasGit = true;
			else if (dirent.name === ".gitignore") hasGitignore = true;
			else if (dirent.name === ".ignore") hasIgnore = true;
		}
		const base = topRel(task.rel);
		let { ignore, git: taskGit } = task;
		let taskRules = ignore.rules;
		if (task.rel !== "" && hasGit) {
			// A repository of its own: the ignore rules of the one around it stop here.
			taskRules = [...taskRules.filter((rule) => !rule.git), ...readRules(excludeFileOf(task.abs), base, true)];
			taskGit = true;
		}
		if (taskGit && hasGitignore) {
			taskRules = withRules(taskRules, readRules(path.join(task.abs, ".gitignore"), base, true));
		}
		if (hasIgnore) taskRules = withRules(taskRules, readRules(path.join(task.abs, ".ignore"), base, false));
		if (taskRules !== ignore.rules) ignore = new IgnoreSet(taskRules);

		// (`path.join` without its normalizing, a large part of the walk's time: the folder's path is normalized already, and a
		// name has no separator, nor is it `.` or `..`.)
		const prefix = task.abs.endsWith(path.sep) ? task.abs : task.abs + path.sep;
		const items: Pending[] = [];
		for (const dirent of dirents) {
			if (dirent.name === ".git") continue;
			const kind = dirent.isDirectory()
				? "dir"
				: dirent.isSymbolicLink()
					? "symlink"
					: dirent.isFile()
						? "file"
						: undefined;
			if (!kind) continue;
			const rel = task.rel ? `${task.rel}/${dirent.name}` : dirent.name;
			if (ignore.rules.length > 0 && ignore.ignores(topRel(rel), kind === "dir")) {
				if (kind === "dir" || !options.override?.(rel)) continue;
			}
			const abs = prefix + dirent.name;
			if (!options.keep || options.keep(rel, kind)) {
				items.push({ type: "entry", key: dirent.name, entry: { rel, abs, kind } });
			}
			if (kind === "dir") {
				const task: DirTask = { abs, rel, ignore, git: taskGit };
				items.push({ type: "folder", key: `${dirent.name}/`, task, children: undefined });
			}
		}
		return items.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
	};

	// The folders found and not read yet, the next to read last (a folder's first subfolder is read before its others); how
	// many are being read, and how many are read or being read and not yet gone into. None is started once the walk stops.
	const unread: Folder[] = [];
	let reading = 0;
	let ahead = 0;
	let stopped = false;
	const readMore = () => {
		while (!stopped && reading < FOLDERS_READING && ahead < FOLDERS_AHEAD) {
			const folder = unread.pop();
			if (!folder) return;
			if (!folder.children) read(folder);
		}
	};
	const read = (folder: Folder) => {
		reading++;
		ahead++;
		folder.children = readdir(folder.task.abs, { withFileTypes: true }).then(
			(dirents) => {
				reading--;
				const children = visit(folder.task, dirents);
				for (let i = children.length - 1; i >= 0; i--) {
					const child = children[i];
					if (child.type === "folder") unread.push(child);
				}
				readMore();
				return children;
			},
			() => {
				reading--;
				readMore();
				return undefined; // (Unreadable: passed over, as ripgrep and fd do.)
			},
		);
		// (A walk stopped early leaves some unread: what `visit` throws then is not left unhandled.)
		folder.children.catch(() => {});
	};

	// What is left to give, the next last.
	const stack: Pending[] = [
		{ type: "folder", key: "", task: { abs: root, rel: "", ignore: new IgnoreSet(rules), git }, children: undefined },
	];
	let run: WalkEntry[] = [];
	try {
		for (let pending = stack.pop(); pending; pending = stack.pop()) {
			if (pending.type === "entry") {
				run.push(pending.entry);
				continue;
			}
			if (run.length > 0) {
				yield run;
				run = [];
			}
			if (options.signal?.aborted) throw new Error("Operation aborted");
			if (!pending.children) read(pending);
			const children = await pending.children;
			ahead--;
			if (children) for (let i = children.length - 1; i >= 0; i--) stack.push(children[i]);
			readMore();
		}
		if (run.length > 0) yield run;
	} finally {
		stopped = true;
	}
}

/** `rules` and `more` after them (`rules` itself when there are none more). */
function withRules(rules: readonly IgnoreRule[], more: readonly IgnoreRule[]): readonly IgnoreRule[] {
	return more.length > 0 ? [...rules, ...more] : rules;
}

// --- find ---------------------------------------------------------------------------------------------------------------

export interface NativeFindOptions {
	root: string;
	/**
	 * The glob, as the find tool takes it: without a `/` matched against the name; with one against the path below the
	 * search folder (anchored there, like grep's glob: #12066), or against the absolute path when it is absolute.
	 */
	pattern: string;
	limit: number;
	signal?: AbortSignal;
}

/** The entries that match, in path order, folders with a trailing `/`, at most `limit`. */
export async function nativeFind(options: NativeFindOptions): Promise<string[]> {
	// A leading "./" is the search folder itself (#12020).
	const pattern = options.pattern.replace(/^(?:\.\/)+(?=.)/, "");
	const fullPath = pattern.includes("/");
	const rootPrefix =
		fullPath && path.isAbsolute(pattern) ? `${options.root.split(path.sep).join("/").replace(/\/+$/, "")}/` : "";
	const patterns = expandBraces(pattern).map(compileGlob);
	const keep = (rel: string) => {
		const subject = fullPath ? rootPrefix + rel : rel.slice(rel.lastIndexOf("/") + 1);
		return patterns.some((matches) => matches(subject));
	};
	const found: string[] = [];
	if (options.limit <= 0) return found;
	// (The walk gives them in path order: it stops at the limit.)
	for await (const run of walkInOrder({ root: options.root, requireGit: false, keep, signal: options.signal })) {
		for (const entry of run) {
			found.push(entry.kind === "dir" ? `${entry.rel}/` : entry.rel);
			if (found.length >= options.limit) return found;
		}
	}
	return found;
}

// --- grep ---------------------------------------------------------------------------------------------------------------

export interface NativeGrepMatch {
	filePath: string;
	lineNumber: number;
	/** The matching line, without its line break, cut to `keepChars`. */
	lineText: string;
}

export interface NativeGrepOptions {
	/** A folder or a file (absolute). */
	root: string;
	isDirectory: boolean;
	pattern: string;
	ignoreCase?: boolean;
	literal?: boolean;
	/** A glob of the files to search, as ripgrep's --glob: against the name, or with a `/` the path; `!` excludes. */
	glob?: string;
	limit: number;
	/** Characters of a long line kept (the tool cuts them shorter still). */
	keepChars: number;
	signal?: AbortSignal;
	/** Files larger than this are read a piece at a time, of `pieceBytes` (defaults: WHOLE_FILE_BYTES, PIECE_BYTES). */
	wholeFileBytes?: number;
	pieceBytes?: number;
}

/**
 * A ripgrep (Rust) regular expression as JavaScript's: (?i), (?P<name>...) and \x{...}/\u{...} code points, the forms that
 * differ and are common.
 */
export function translatePattern(pattern: string): { source: string; ignoreCase: boolean } {
	let source = pattern;
	let ignoreCase = false;
	if (source.startsWith("(?i)")) {
		ignoreCase = true;
		source = source.slice(4);
	}
	source = source.replace(/\(\?P</g, "(?<");
	// `\x{1F600}` and `\u{65}`, a code point (in JavaScript without the u flag, `\u{65}` is 65 u's): as \uXXXX, or two for
	// a code point past U+FFFF. (An escaped backslash before it is left alone.)
	source = source.replace(/(?<!\\)((?:\\\\)*)\\[xu]\{([0-9A-Fa-f]{1,6})\}/g, (match, slashes: string, hex: string) => {
		const codePoint = Number.parseInt(hex, 16);
		if (codePoint > 0x10ffff) return match; // (Not a code point: left for the expression to refuse.)
		const units = String.fromCodePoint(codePoint);
		const escaped = [...Array(units.length).keys()]
			.map((k) => `\\u${units.charCodeAt(k).toString(16).padStart(4, "0")}`)
			.join("");
		return `${slashes}${units.length > 1 ? `(?:${escaped})` : escaped}`;
	});
	return { source, ignoreCase };
}

/** The most literals `requiredLiterals` gives: each is looked for in every file. */
const MAX_LITERALS = 32;

/**
 * Literals of which every match of `source` contains one, when it is easy to be sure of them: a file with none of them needs
 * no regular expression run over it, and of a file that has one, only the lines that have one. Of a sequence, its longest run
 * of plain characters (outside any group, not made optional) or a group's literals (a group not made optional), whichever
 * has the longer shortest literal; of an alternation, every alternative's. Each is at least 3 characters long. Undefined if
 * there are none such, or the expression has a part this does not know: a lookaround (it could see past a line), or an escape
 * that stands for a character (`\x66`) or more (`\p{L}`, `\1`).
 */
export function requiredLiterals(source: string): string[] | undefined {
	const scan = { source, at: 0 };
	let literals: string[] | undefined;
	try {
		literals = alternationLiterals(scan);
	} catch (error) {
		if (error === UNKNOWN_PART) return undefined;
		throw error;
	}
	if (!literals || scan.at < source.length) return undefined;
	// (A literal with another in it is found where that one is.)
	const fewest: string[] = [];
	for (const literal of [...new Set(literals)].sort((a, b) => a.length - b.length)) {
		if (!fewest.some((shorter) => literal.includes(shorter))) fewest.push(literal);
	}
	return fewest.length <= MAX_LITERALS ? fewest : undefined;
}

/** Thrown by the scan of an expression for a part `requiredLiterals` does not know. */
const UNKNOWN_PART = new Error("A part of the expression whose literals are not known");

/** Escapes that are one character of the pattern and match no particular text: classes, anchors, white space. */
const SIMPLE_ESCAPES = "dDwWsSbBAzZntrfv";

/** A quantifier that makes what is before it optional (at least none). */
const OPTIONAL = /[?*]|\{0+(?:,\d*)?\}/y;
/** A `{...}` quantifier (without the u flag, a `{` that does not start one is the character). */
const BRACES = /\{(\d+)(?:,\d*)?\}/y;

interface Scan {
	source: string;
	at: number;
}

/** `requiredLiterals` of the alternation at `scan.at`, which it leaves at the `)` that closes the alternation or the end. */
function alternationLiterals(scan: Scan): string[] | undefined {
	let union: string[] | undefined = [];
	for (;;) {
		const literals = sequenceLiterals(scan);
		union = union && literals ? [...union, ...literals] : undefined;
		if (scan.source[scan.at] !== "|") return union;
		scan.at++;
	}
}

/** `requiredLiterals` of the sequence at `scan.at`, which it leaves at the `|` or `)` that ends the sequence, or the end. */
function sequenceLiterals(scan: Scan): string[] | undefined {
	const { source } = scan;
	let best: string[] | undefined;
	const consider = (literals: string[]) => {
		const shortest = shortestLength(literals);
		if (
			!best ||
			shortest > shortestLength(best) ||
			(shortest === shortestLength(best) && literals.length < best.length)
		) {
			best = literals;
		}
	};
	let run = "";
	const end = () => {
		if (run.length >= 3) consider([run]);
		run = "";
	};
	// The character before a `?`, `*` or `{0...}` is optional: out of the run. (A run holds surrogates only in pairs.)
	const dropLast = () => {
		run = run.slice(0, isLowSurrogate(run.charCodeAt(run.length - 1)) ? -2 : -1);
	};
	while (scan.at < source.length) {
		const c = source[scan.at];
		if (c === "|" || c === ")") break;
		if (c === "\\") {
			const next = source[scan.at + 1] ?? "";
			if (/[0-9A-Za-z]/.test(next)) {
				// A class or an anchor (`\d`, `\b`): one character, not a known one. An escape that stands for a character or
				// more (`\x66`, `\u{1F600}`, `\p{L}`, `\1`) has a length of its own: not known.
				if (!SIMPLE_ESCAPES.includes(next)) throw UNKNOWN_PART;
				end();
			} else if (next !== "" && next < "\x80") {
				run += next; // (An escaped sign, `\.`: that character.)
			} else {
				end();
			}
			scan.at += 2;
			continue;
		}
		if (c === "(") {
			end();
			scan.at++;
			if (source[scan.at] === "?") {
				// A group that does not capture, or one with a name; any other `(?` (a lookaround) is not known.
				const kind = source[scan.at + 1];
				const after = source[scan.at + 2];
				if (kind === ":") scan.at += 2;
				else if (kind === "<" && after !== "=" && after !== "!") {
					const close = source.indexOf(">", scan.at);
					if (close === -1) throw UNKNOWN_PART;
					scan.at = close + 1;
				} else throw UNKNOWN_PART;
			}
			const inner = alternationLiterals(scan);
			if (source[scan.at] !== ")") throw UNKNOWN_PART;
			scan.at++;
			OPTIONAL.lastIndex = scan.at;
			if (inner && !OPTIONAL.test(source)) consider(inner);
			continue;
		}
		if (c === "[") {
			// Without the u flag a class ends at the first `]` not escaped, even right after the `[` (`[]` matches nothing).
			end();
			let i = scan.at + 1;
			if (source[i] === "^") i++;
			while (i < source.length && source[i] !== "]") i += source[i] === "\\" ? 2 : 1;
			if (i >= source.length) throw UNKNOWN_PART;
			scan.at = i + 1;
			continue;
		}
		if (c === "?" || c === "*") {
			dropLast();
			end();
			scan.at++;
			continue;
		}
		if (c === "{") {
			BRACES.lastIndex = scan.at;
			const quantifier = BRACES.exec(source);
			if (quantifier && Number(quantifier[1]) === 0) dropLast();
			end();
			scan.at += quantifier ? quantifier[0].length : 1;
			continue;
		}
		const code = source.charCodeAt(scan.at);
		if (code >= 0xd800 && code <= 0xdbff && isLowSurrogate(source.charCodeAt(scan.at + 1))) {
			run += source.slice(scan.at, scan.at + 2);
			scan.at += 2;
			continue;
		}
		// `+` (what is before it is there at least once), `.`, `^`, `$`; `]` and `}`, the characters; a lone surrogate or
		// U+FFFD, which text read from UTF-8 has where its bytes are not UTF-8: not plain characters of the run.
		if ("+.^$]}".includes(c) || (code >= 0xd800 && code <= 0xdfff) || code === 0xfffd) end();
		else run += c;
		scan.at++;
	}
	end();
	return best;
}

function shortestLength(literals: string[]): number {
	let shortest = Number.POSITIVE_INFINITY;
	for (const literal of literals) shortest = Math.min(shortest, literal.length);
	return shortest;
}

const isLowSurrogate = (code: number) => code >= 0xdc00 && code <= 0xdfff;

const NEWLINE = 10;

/** Counts the line breaks in `buffer` from `from` up to `to`. */
function countLines(buffer: Buffer, from: number, to: number): number {
	let count = 0;
	for (let at = buffer.indexOf(NEWLINE, from); at !== -1 && at < to; at = buffer.indexOf(NEWLINE, at + 1)) count++;
	return count;
}

function countLinesIn(text: string, from: number, to: number): number {
	let count = 0;
	for (let at = text.indexOf("\n", from); at !== -1 && at < to; at = text.indexOf("\n", at + 1)) count++;
	return count;
}

function stripCr(line: string): string {
	return line.endsWith("\r") ? line.slice(0, -1) : line;
}

/**
 * The rest of a search that ran past its turn's end (`deadline`, a `performance.now()` time): call it, in a later turn, to go
 * on where it stopped.
 */
type SearchRest = (deadline: number) => SearchRest | undefined;

interface Matcher {
	/** Whether `buffer` may have a match: false only when it has none (a fast check before anything else). */
	mayMatch?: (buffer: Buffer) => boolean;
	/**
	 * Searches whole lines: a file, or a part of one that ends with a line break; `firstLine` is the number of its first. A
	 * regular expression can take long on a line (the engine backtracks), so a search that has to run one checks the time
	 * after each line it tries and stops after `deadline`, returning what is left of it.
	 */
	search(
		buffer: Buffer,
		filePath: string,
		budget: number,
		keepChars: number,
		out: NativeGrepMatch[],
		firstLine: number,
		deadline: number,
	): SearchRest | undefined;
}

const isAscii = (text: string) => /^[\x00-\x7f]*$/.test(text);

/** Finds where any of some literals are in a buffer. */
interface LiteralFinder {
	/** Whether `buffer` has one of them. */
	has(buffer: Buffer): boolean;
	/** For `buffer`: from a place in it, where the next of them starts (-1: nowhere). Asked for places in increasing order. */
	in(buffer: Buffer): (from: number) => number;
}

/** A `LiteralFinder` for literals as they are: their UTF-8 bytes. */
function exactFinder(literals: readonly string[]): LiteralFinder {
	const finds = literals.map((literal) => rarestFirst(Buffer.from(literal, "utf8"), BUFFER_SEARCH));
	return {
		has: (buffer) => finds.some((find) => find(buffer, 0) !== -1),
		in: (buffer) =>
			finds.length === 1
				? (from) => finds[0](buffer, from)
				: nextOfAny(finds.length, (k, from) => finds[k](buffer, from)),
	};
}

/**
 * A `LiteralFinder` for ASCII literals, letters in either case. The buffer is read as Latin-1 (a character a byte) and put in
 * lower case, where an ASCII letter comes only from one (the other Latin-1 letters stay outside ASCII): the lower-case literals
 * are found there by the engine's own search. (A check of each byte in JavaScript is interpreted on a build without the JIT:
 * 5 to 10 times as long.) A buffer larger than LOWERED_BYTES (a piece of a file that ends inside a very long line) is searched
 * byte by byte instead.
 */
function caselessFinder(literals: readonly string[]): LiteralFinder {
	const needles = [...new Set(literals.map((literal) => literal.toLowerCase()))];
	const finds = needles.map((needle) => rarestFirst(needle, TEXT_SEARCH));
	const bytes = needles.map((needle) => Buffer.from(needle, "latin1"));
	// (The text of the last buffer: `has` and then `in` are asked about the same one.)
	let last: Buffer | undefined;
	let lastText = "";
	const findIn = (buffer: Buffer): ((k: number, from: number) => number) => {
		if (buffer.length > LOWERED_BYTES) return (k, from) => indexOfIgnoringCase(buffer, bytes[k], from);
		if (buffer !== last) {
			last = buffer;
			lastText = buffer.toString("latin1").toLowerCase();
		}
		const text = lastText;
		return (k, from) => finds[k](text, from);
	};
	return {
		has: (buffer) => {
			const find = findIn(buffer);
			return needles.some((_, k) => find(k, 0) !== -1);
		},
		in: (buffer) => {
			const find = findIn(buffer);
			return needles.length === 1 ? (from) => find(0, from) : nextOfAny(needles.length, find);
		},
	};
}

/** The largest buffer `caselessFinder` puts in lower case: a string that long is held besides it (and an engine has a limit). */
const LOWERED_BYTES = 32 * 1024 * 1024;

/** Where `needle` (lower case, ASCII) is in `buffer` from `from` on, letters in either case; -1 if nowhere. */
function indexOfIgnoringCase(buffer: Buffer, needle: Buffer, from: number): number {
	const first = needle[0];
	const firstUpper = first >= 97 && first <= 122 ? first - 32 : first;
	let lower = buffer.indexOf(first, from);
	let upper = firstUpper === first ? -1 : buffer.indexOf(firstUpper, from);
	while (lower !== -1 || upper !== -1) {
		const at = upper === -1 || (lower !== -1 && lower < upper) ? lower : upper;
		if (at + needle.length > buffer.length) return -1;
		let k = 1;
		for (; k < needle.length; k++) {
			let c = buffer[at + k];
			if (c >= 65 && c <= 90) c += 32;
			if (c !== needle[k]) break;
		}
		if (k === needle.length) return at;
		if (at === lower) lower = buffer.indexOf(first, at + 1);
		else upper = buffer.indexOf(firstUpper, at + 1);
	}
	return -1;
}

/** The engine's search in a buffer or a string, and what `rarestFirst` needs besides. */
interface Search<T extends Buffer | string> {
	indexOf(haystack: T, needle: T, from: number): number;
	/** Whether `part` is in `haystack` at `at`. */
	isAt(haystack: T, part: T, at: number): boolean;
	slice(needle: T, start: number, end: number): T;
	unit(needle: T, index: number): number;
}

const BUFFER_SEARCH: Search<Buffer> = {
	indexOf: (haystack, needle, from) => haystack.indexOf(needle, from),
	isAt: (haystack, part, at) => haystack.compare(part, 0, part.length, at, at + part.length) === 0,
	slice: (needle, start, end) => needle.subarray(start, end),
	unit: (needle, index) => needle[index],
};

const TEXT_SEARCH: Search<string> = {
	indexOf: (haystack, needle, from) => haystack.indexOf(needle, from),
	isAt: (haystack, part, at) => haystack.startsWith(part, at),
	slice: (needle, start, end) => needle.slice(start, end),
	unit: (needle, index) => needle.charCodeAt(index),
};

/** Characters of source text, the most common first: the others (upper-case letters, most digits and signs) are rarer. */
const COMMON_CHARACTERS = " etaoinsrlcdupmhfg.();=,yb_\"'/-:{}wvk0x1";

/**
 * Where `needle` is in a haystack from a place on (-1: nowhere), found by its part from its rarest character on, and its start
 * checked at each place that part is: the engine's search stops at each place the first character it looks for is, and a
 * rare one is at few places (`FileSync` was found 5 times as fast as `readFileSync`).
 */
function rarestFirst<T extends Buffer | string>(needle: T, search: Search<T>): (haystack: T, from: number) => number {
	let at = 0;
	let rarest = -1;
	for (let i = 0; i < needle.length; i++) {
		const rank = COMMON_CHARACTERS.indexOf(String.fromCharCode(search.unit(needle, i)));
		const rarity = rank === -1 ? COMMON_CHARACTERS.length : rank;
		if (rarity > rarest) {
			rarest = rarity;
			at = i;
		}
	}
	if (at === 0) return (haystack, from) => search.indexOf(haystack, needle, from);
	const head = search.slice(needle, 0, at);
	const tail = search.slice(needle, at, needle.length);
	return (haystack, from) => {
		for (
			let place = search.indexOf(haystack, tail, from + at);
			place !== -1;
			place = search.indexOf(haystack, tail, place + 1)
		) {
			if (search.isAt(haystack, head, place - at)) return place - at;
		}
		return -1;
	};
}

/**
 * The nearest of `count` searches' next places (`find(k, from)`, -1 for none), from places asked for in increasing order: a
 * search runs again only once the place passes the one it found last.
 */
function nextOfAny(count: number, find: (k: number, from: number) => number): (from: number) => number {
	const found = new Array<number>(count).fill(-2); // (-2: not run yet.)
	return (from) => {
		let nearest = -1;
		for (let k = 0; k < count; k++) {
			let at = found[k];
			if (at !== -1 && at < from) {
				at = find(k, from);
				found[k] = at;
			}
			if (at !== -1 && (nearest === -1 || at < nearest)) nearest = at;
		}
		return nearest;
	};
}

/**
 * The lines that `next` finds a candidate in (the start of an occurrence of what every match contains), and that `test`, when
 * given, takes. Only those lines are decoded and run through a regular expression.
 */
function searchLines(
	buffer: Buffer,
	next: (from: number) => number,
	test: ((line: string) => boolean) | undefined,
	match: { filePath: string; budget: number; keepChars: number; out: NativeGrepMatch[]; firstLine: number },
	deadline: number,
): SearchRest | undefined {
	let line = match.firstLine;
	let counted = 0;
	let found = 0;
	let at = next(0);
	const run = (deadline: number): SearchRest | undefined => {
		while (at !== -1 && found < match.budget) {
			const start = at === 0 ? 0 : buffer.lastIndexOf(NEWLINE, at - 1) + 1;
			let end = buffer.indexOf(NEWLINE, at);
			if (end === -1) end = buffer.length;
			// (A long line is decoded whole only when an expression has to see all of it.)
			const text = buffer.toString("utf8", start, test ? end : Math.min(end, start + match.keepChars * 4));
			if (!test || test(text)) {
				line += countLines(buffer, counted, start);
				counted = start;
				match.out.push({
					filePath: match.filePath,
					lineNumber: line,
					lineText: stripCr(text.slice(0, match.keepChars)),
				});
				found++;
			}
			if (end >= buffer.length) break;
			at = next(end + 1);
			if (test && performance.now() > deadline) return run;
		}
		return undefined;
	};
	return run(deadline);
}

/** A literal: its bytes, or with `ignoreCase` (ASCII) its letters in either case. */
function literalMatcher(pattern: string, ignoreCase: boolean): Matcher {
	if (pattern === "") {
		// (Every line has it.)
		return {
			search: (buffer, filePath, budget, keepChars, out, firstLine, deadline) =>
				searchLines(
					buffer,
					(from) => (from < buffer.length ? from : -1),
					undefined,
					{ filePath, budget, keepChars, out, firstLine },
					deadline,
				),
		};
	}
	const finder = ignoreCase ? caselessFinder([pattern]) : exactFinder([pattern]);
	return {
		mayMatch: finder.has,
		search: (buffer, filePath, budget, keepChars, out, firstLine, deadline) =>
			searchLines(buffer, finder.in(buffer), undefined, { filePath, budget, keepChars, out, firstLine }, deadline),
	};
}

function regexMatcher(source: string, ignoreCase: boolean): Matcher {
	const flags = ignoreCase ? "i" : "";
	let one: RegExp;
	try {
		one = new RegExp(source, `${flags}m`);
	} catch (error) {
		throw new Error(`regex parse error: ${error instanceof Error ? error.message : String(error)}`);
	}
	const test = (line: string) => one.test(line);
	// What every match contains one of: only the lines that have one are run through the expression. (With `ignoreCase`, only
	// ASCII literals: without the u flag an ASCII letter matches only ASCII letters, the same in either case.)
	const literals = requiredLiterals(source);
	if (literals && (!ignoreCase || literals.every(isAscii))) {
		const finder = ignoreCase ? caselessFinder(literals) : exactFinder(literals);
		return {
			mayMatch: finder.has,
			search: (buffer, filePath, budget, keepChars, out, firstLine, deadline) =>
				searchLines(buffer, finder.in(buffer), test, { filePath, budget, keepChars, out, firstLine }, deadline),
		};
	}
	// A window of lines at a time: an expression run over the whole text tries every place in it within one call, which a
	// backtracking expression can make take minutes with no chance to stop it. A match is within one line as before
	// (ripgrep's rule); only lookbehind and lookahead do not see past a window's first and last lines.
	const all = new RegExp(source, `${flags}gm`);
	return {
		search(buffer, filePath, budget, keepChars, out, firstLine, deadline) {
			let found = 0;
			// The window: its bytes `from` up to `to` (just past a line break, or the end), as text.
			let from = 0;
			let to = 0;
			let text = "";
			// Where the search of the window goes on (-1: the next window).
			let lastIndex = -1;
			// Line numbers, counted only up to the matches: `line` is that of `counted` in the window (-1: not counted yet),
			// `knownLine` that of the byte `knownAt`.
			let line = -1;
			let counted = 0;
			let knownLine = firstLine;
			let knownAt = 0;
			const run = (deadline: number): SearchRest | undefined => {
				while (found < budget) {
					if (lastIndex === -1) {
						if (to >= buffer.length) return undefined;
						from = to;
						const lastBreak =
							from + WINDOW_BYTES < buffer.length ? buffer.indexOf(NEWLINE, from + WINDOW_BYTES - 1) : -1;
						to = lastBreak === -1 ? buffer.length : lastBreak + 1;
						text = buffer.toString("utf8", from, to);
						lastIndex = 0;
						line = -1;
					}
					all.lastIndex = lastIndex;
					const match = all.lastIndex <= text.length ? all.exec(text) : null;
					// (An empty match after the window's last line break: that is the next window's first line.)
					if (!match || (match.index === text.length && match.index > 0 && text[match.index - 1] === "\n")) {
						lastIndex = -1;
					} else {
						const at = match.index;
						const start = at === 0 ? 0 : text.lastIndexOf("\n", at - 1) + 1;
						let end = text.indexOf("\n", at);
						if (end === -1) end = text.length;
						// Within one line, as ripgrep matches: a match that runs past it counts if the line alone has one.
						const pastLine = at + match[0].length > end || (at === end && end < text.length);
						if (pastLine && !one.test(text.slice(start, end))) {
							lastIndex = at + 1;
						} else {
							if (line === -1) {
								knownLine += countLines(buffer, knownAt, from);
								knownAt = from;
								line = knownLine;
								counted = 0;
							}
							line += countLinesIn(text, counted, start);
							counted = start;
							out.push({
								filePath,
								lineNumber: line,
								lineText: stripCr(text.slice(start, Math.min(end, start + keepChars))),
							});
							found++;
							lastIndex = end + 1;
						}
					}
					if (performance.now() > deadline) return run;
				}
				return undefined;
			};
			return run(deadline);
		},
	};
}

/**
 * The files a --glob takes: one glob, `!` before it to leave those out; against the name, or with a `/` the path. As in
 * ripgrep, a file it takes is searched even if an ignore file leaves it out (`include`).
 */
function globFilter(glob: string | undefined): { take: (rel: string) => boolean; include?: (rel: string) => boolean } {
	if (!glob) return { take: () => true };
	const exclude = glob.startsWith("!");
	const pattern = exclude ? glob.slice(1) : glob;
	const fullPath = pattern.includes("/");
	const patterns = expandBraces(pattern.startsWith("/") ? pattern.slice(1) : pattern).map(compileGlob);
	const matches = (rel: string) => {
		const subject = fullPath ? rel : rel.slice(rel.lastIndexOf("/") + 1);
		return patterns.some((matchesPattern) => matchesPattern(subject));
	};
	return { take: (rel) => matches(rel) !== exclude, include: exclude ? undefined : matches };
}

/** How much of a file an expression without a literal is run over at once (whole lines). */
const WINDOW_BYTES = 16 * 1024;

/**
 * How many files are read at once at most. They are asked for half of that at a time: a request on its own wakes a worker
 * thread, and its answer the main thread; a batch keeps them busy. (With a request per file and 32 files read at once, grep
 * over 20,000 small files took 10-20% longer.)
 */
const READ_AHEAD = 64;

/** How long grep searches before the rest of Pi (the screen, the Escape that aborts the search) has a turn. */
const TURN_MS = 50;

/** grep's turns: it searches for TURN_MS, then lets the event loop run; and stops when it is aborted. */
class Turns {
	/** When this turn ends (`performance.now()`). */
	deadline: number;
	private readonly signal: AbortSignal | undefined;

	constructor(signal: AbortSignal | undefined) {
		this.signal = signal;
		this.deadline = performance.now() + TURN_MS;
	}

	/** Whether `check` has something to do: this turn is over, or the search was aborted. */
	due(): boolean {
		return performance.now() > this.deadline || this.signal?.aborted === true;
	}

	/** Throws if the search was aborted; when this turn is over, lets the event loop run first. */
	async check(): Promise<void> {
		if (performance.now() > this.deadline) {
			await new Promise<void>((resolve) => setTimeout(resolve, 0));
			this.deadline = performance.now() + TURN_MS;
		}
		if (this.signal?.aborted) throw new Error("Operation aborted");
	}

	/** The rest of a search that ran past this turn's end, over as many turns as it takes. */
	async finish(rest: SearchRest): Promise<void> {
		for (let next: SearchRest | undefined = rest; next; next = next(this.deadline)) await this.check();
	}
}

/**
 * Files up to this size are read whole; a larger one a piece at a time (`searchInPieces`). Reading every file whole let a
 * search through large files (a virtual disk, a model's weights, a long log) hold all of them in memory at once: gigabytes,
 * read at the disk's speed. At most READ_AHEAD files of this size are in memory now (128 MB), and one piece of a larger one.
 */
const WHOLE_FILE_BYTES = 2 * 1024 * 1024;
/** What a larger file is read in. */
const PIECE_BYTES = 1024 * 1024;

/**
 * `matcher.search` over a file read a piece at a time, with the outcome of a search of the file read whole: each piece is
 * searched up to its last line break and the rest goes with the next, so a match is within a line as before; and a file with
 * a NUL byte anywhere (binary) has no matches, so it is read to its end, or to its first NUL byte. Undefined for a binary
 * file or one that could not be read. Memory: a piece, and the line a piece ends inside of.
 *
 * Its speed is that of a search of the file read whole: one buffer, read into again (the start of a line a piece ends inside
 * of is moved to its front); and line breaks are counted only up to the last piece that can have a match, as a file read
 * whole counts them only up to its matches: a piece the matcher's `mayMatch` rules out is not counted, and when a later piece
 * has it, the pieces since are read again and counted then.
 */
async function searchInPieces(
	file: string,
	matcher: Matcher,
	budget: number,
	keepChars: number,
	pieceBytes: number,
	turns: Turns,
): Promise<NativeGrepMatch[] | undefined> {
	let handle: FileHandle;
	try {
		// (Without blocking: what was a file when it was found may be a pipe by now.)
		handle = await open(file, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0));
	} catch {
		return undefined;
	}
	// The read of the next piece, started while this one is searched (-1: it failed).
	let next: Promise<number> | undefined;
	try {
		if (!(await handle.stat()).isFile()) return undefined;
		const found: NativeGrepMatch[] = [];
		// `buffer` holds the file from `bufferAt` on: up to `kept`, the start of a line no piece so far has ended; the next
		// piece is read after it, from `readAt`. The next piece is read into `spare` while this one is searched, after the
		// rest of the line it ends inside of, and the two change places.
		let buffer: Buffer = Buffer.allocUnsafe(pieceBytes);
		let spare: Buffer | undefined;
		let kept = 0;
		let bufferAt = 0;
		let readAt = 0;
		// The line at `countedAt` is number `line`.
		let countedAt = 0;
		let line = 1;
		let again: Buffer | undefined;
		for (;;) {
			if (turns.due()) await turns.check();
			let bytesRead: number;
			if (next) {
				bytesRead = await next;
				next = undefined;
				if (bytesRead === -1) return undefined;
			} else {
				if (buffer.length - kept < pieceBytes) {
					const larger = Buffer.allocUnsafe(Math.max(kept + pieceBytes, 2 * buffer.length));
					buffer.copy(larger, 0, 0, kept);
					buffer = larger;
				}
				try {
					({ bytesRead } = await handle.read(buffer, kept, pieceBytes, readAt));
				} catch {
					return undefined;
				}
			}
			readAt += bytesRead;
			const filled = kept + bytesRead;
			if (buffer.subarray(kept, filled).indexOf(0) !== -1) return undefined; // (Binary.)
			const end = bytesRead === 0;
			// The lines this piece ends: up to its last line break; at the end of the file, the last line, if it has no break.
			// (Looked for in the piece only: what was kept has none, and a long line would be searched again for each piece.)
			const lastBreak = end ? -1 : buffer.subarray(kept, filled).lastIndexOf(NEWLINE);
			const upTo = end ? filled : lastBreak === -1 ? 0 : kept + lastBreak + 1;
			if (!end && upTo === 0) {
				kept = filled;
				continue;
			}
			const lines = buffer.subarray(0, upTo);
			if (!end) {
				const rest = filled - upTo;
				if (!spare || spare.length < rest + pieceBytes) spare = Buffer.allocUnsafe(rest + pieceBytes + (rest >> 1));
				buffer.copy(spare, 0, upTo, filled);
				next = handle.read(spare, rest, pieceBytes, readAt).then(
					(result) => result.bytesRead,
					() => -1,
				);
				kept = rest;
			}
			// (Not the nothing after a last line break: as in a file read whole, there is no line there.)
			if (lines.length > 0 && found.length < budget && (!matcher.mayMatch || matcher.mayMatch(lines))) {
				while (countedAt < bufferAt) {
					if (turns.due()) await turns.check();
					again ??= Buffer.allocUnsafe(pieceBytes);
					let count: number;
					try {
						({ bytesRead: count } = await handle.read(
							again,
							0,
							Math.min(again.length, bufferAt - countedAt),
							countedAt,
						));
					} catch {
						return undefined;
					}
					if (count === 0) return undefined; // (Cut short since: as a file that could not be read.)
					line += countLines(again, 0, count);
					countedAt += count;
				}
				const rest = matcher.search(lines, file, budget - found.length, keepChars, found, line, turns.deadline);
				if (rest) await turns.finish(rest);
				if (!end && found.length < budget) {
					line += countLines(lines, 0, upTo);
					countedAt += upTo;
				}
			}
			if (end) return found;
			bufferAt += upTo;
			[buffer, spare] = [spare ?? buffer, buffer];
		}
	} finally {
		await next;
		await handle.close().catch(() => {});
	}
}
/** A file's contents; for a file too large to read whole, its size; undefined for one that is not searched. */
type Contents = Buffer | number | undefined;

/** The lines that match, file by file in path order, at most `limit`; and whether the limit stopped the search. */
export async function nativeGrep(
	options: NativeGrepOptions,
): Promise<{ matches: NativeGrepMatch[]; limitReached: boolean }> {
	let matcher: Matcher;
	if (options.literal) {
		matcher =
			options.ignoreCase && !isAscii(options.pattern)
				? regexMatcher(options.pattern.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&"), true)
				: literalMatcher(options.pattern, Boolean(options.ignoreCase));
	} else {
		const { source, ignoreCase } = translatePattern(options.pattern);
		matcher = regexMatcher(source, Boolean(options.ignoreCase) || ignoreCase);
	}

	const wholeFileBytes = options.wholeFileBytes ?? WHOLE_FILE_BYTES;
	// The files in path order, as far as the walk has gone: it goes on only as far as the search needs.
	const files: string[] = [];
	let walking: AsyncGenerator<WalkEntry[], void, undefined> | undefined;
	let read: (file: string) => Promise<Contents>;
	if (options.isDirectory) {
		const { take, include } = globFilter(options.glob);
		const keep = (rel: string, kind: WalkEntry["kind"]) => kind === "file" && take(rel);
		walking = walkInOrder({ root: options.root, requireGit: true, override: include, keep, signal: options.signal });
		read = (file) => readContents(file, wholeFileBytes);
	} else {
		files.push(options.root);
		// Only a regular file is opened: a device or a pipe (a search of one path names what it likes) is left alone.
		read = (file) =>
			stat(file).then(
				(stats) => (stats.isFile() ? readContents(file, wholeFileBytes) : undefined),
				() => undefined,
			);
	}

	const matches: NativeGrepMatch[] = [];
	// The files' contents: `reads[i]` for the files from `index` on, as far as they have been asked for.
	const reads: (Promise<Contents> | undefined)[] = [];
	let requested = 0;
	const turns = new Turns(options.signal);
	try {
		for (let index = 0; ; index++) {
			if (turns.due()) await turns.check();
			if (requested <= index + READ_AHEAD / 2) {
				while (walking && files.length < index + READ_AHEAD) {
					const run = await walking.next();
					if (run.done) walking = undefined;
					else for (const entry of run.value) files.push(entry.abs);
				}
				for (const end = Math.min(files.length, index + READ_AHEAD); requested < end; requested++) {
					reads[requested] = read(files[requested]);
				}
			}
			if (index >= files.length) return { matches, limitReached: false };
			const contents = await reads[index];
			reads[index] = undefined; // (Done with it: let it go.)
			if (typeof contents === "number") {
				const budget = options.limit - matches.length;
				const pieceBytes = options.pieceBytes ?? PIECE_BYTES;
				const found = await searchInPieces(files[index], matcher, budget, options.keepChars, pieceBytes, turns);
				if (found) matches.push(...found);
			} else {
				if (!contents || contents.length === 0) continue;
				if (matcher.mayMatch && !matcher.mayMatch(contents)) continue;
				if (contents.indexOf(0) !== -1) continue; // (Binary.)
				const budget = options.limit - matches.length;
				const rest = matcher.search(contents, files[index], budget, options.keepChars, matches, 1, turns.deadline);
				if (rest) await turns.finish(rest);
			}
			if (matches.length >= options.limit) return { matches, limitReached: true };
		}
	} finally {
		await walking?.return();
	}
}

/**
 * A file's `Contents`: opened (without blocking: what was a file when the walk found it may be a pipe by now), asked for its
 * size once, and read whole if it is a regular file of at most `wholeFileBytes`. A request each, without a stat before.
 */
function readContents(file: string, wholeFileBytes: number): Promise<Contents> {
	return new Promise((resolve) => {
		openFile(file, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0), (error, fd) => {
			if (error) {
				resolve(undefined);
				return;
			}
			const finish = (contents: Contents) => close(fd, () => resolve(contents));
			fstat(fd, (error, stats) => {
				if (error || !stats.isFile()) finish(undefined);
				else if (stats.size > wholeFileBytes) finish(stats.size);
				else readOpenFile(fd, stats.size, finish);
			});
		});
	});
}

/** What a file of unknown size (one whose size is 0, as in /proc) is read in. */
const UNKNOWN_SIZE_CHUNK = 64 * 1024;

/**
 * The bytes of the open file `fd`, as readFile reads them: up to `size` (fewer if it has shrunk since); for a size of 0, all
 * there is. Undefined if a read fails.
 */
function readOpenFile(fd: number, size: number, done: (contents: Buffer | undefined) => void): void {
	const chunks: Buffer[] = [];
	let buffer = Buffer.allocUnsafe(size === 0 ? UNKNOWN_SIZE_CHUNK : size);
	let filled = 0;
	let position = 0;
	const step = () => {
		read(fd, buffer, filled, buffer.length - filled, position, (error, bytesRead) => {
			if (error) {
				done(undefined);
				return;
			}
			filled += bytesRead;
			position += bytesRead;
			if (size > 0) {
				if (bytesRead === 0 || filled === size) done(filled < size ? buffer.subarray(0, filled) : buffer);
				else step();
				return;
			}
			if (bytesRead === 0) {
				chunks.push(buffer.subarray(0, filled));
				done(Buffer.concat(chunks));
				return;
			}
			if (filled === buffer.length) {
				chunks.push(buffer);
				buffer = Buffer.allocUnsafe(UNKNOWN_SIZE_CHUNK);
				filled = 0;
			}
			step();
		});
	};
	step();
}
