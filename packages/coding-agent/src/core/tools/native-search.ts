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
import { type Dirent, existsSync, readFileSync, statSync } from "node:fs";
import { readdir, readFile } from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";

// --- globs --------------------------------------------------------------------------------------------------------------

/** `*.{ts,tsx}` -> `*.ts`, `*.tsx` (nested braces too); a pattern without braces as it is. */
export function expandBraces(pattern: string): string[] {
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
				return parts.flatMap((part) => expandBraces(`${head}${part}${tail}`));
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
// - `globFrom` tries every place, and gives up after `FREE_STEPS` calls (each a pass over at most the pattern and the text).
//   Nearly every match ends well before that.
// - `globFromMarked` then starts again, and marks each place a `*` was tried from. A place tried before has failed (success
//   ends the match), so none is tried twice: the match takes time proportional to the pattern's length times the text's.
// They are two functions, not one with a flag: the marking code in the first made common matches up to 15% slower.

const FREE_STEPS = 1024;
let freeSteps = 0;

/** Whether `s` from `si` on matches `p` from `pi` on; false too when `freeSteps` runs out (it is below 0 then). */
function globFrom(p: string, pi: number, s: string, si: number): boolean {
	if (--freeSteps < 0) return false;
	while (pi < p.length) {
		const c = p[pi];
		if (c === "*") {
			if (p[pi + 1] === "*") {
				const next = pi + 2;
				if (p[next] === "/") {
					// `**/`: no folder, or any number of them.
					if (globFrom(p, next + 1, s, si)) return true;
					for (let k = si; k < s.length; k++) {
						if (s[k] === "/" && globFrom(p, next + 1, s, k + 1)) return true;
					}
					return false;
				}
				// `**` otherwise: anything, across folders.
				for (let k = s.length; k >= si; k--) {
					if (globFrom(p, next, s, k)) return true;
				}
				return false;
			}
			// `*`: anything within one name.
			for (let k = si; ; k++) {
				if (globFrom(p, pi + 1, s, k)) return true;
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
	freeSteps = FREE_STEPS;
	return globFrom(pattern, 0, text, 0) || (freeSteps < 0 && globMatchMarked(pattern, text));
}

const GLOB_META = /[*?[\\]/;

/**
 * `globMatch` for one pattern, as a function: the common shapes (a name, `*.ext`, `prefix*`, `**` then a name) without the general
 * matcher. Every entry of a walk is checked against every ignore rule, and on a build without the JIT this is most of its time.
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
	return (text) => globMatch(pattern, text);
}

// --- ignore files -------------------------------------------------------------------------------------------------------

interface IgnoreRule {
	/** The folder of the file it is from, relative to the walk's top folder ("" for the top). */
	base: string;
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
		rules.push({ base, pattern: line, matches: compileGlob(line), negate, dirOnly, anchored, git });
	}
	return rules;
}

/** Whether the rules ignore the entry at `rel` (relative to the top folder): the last rule that matches decides. */
function isIgnored(rules: readonly IgnoreRule[], rel: string, isDir: boolean): boolean {
	let ignored = false;
	const name = rel.slice(rel.lastIndexOf("/") + 1);
	for (const rule of rules) {
		if (rule.dirOnly && !isDir) continue;
		if (ignored !== rule.negate) continue; // (It could not change the outcome.)
		let below: string;
		if (rule.base === "") below = rel;
		else if (rel.startsWith(`${rule.base}/`)) below = rel.slice(rule.base.length + 1);
		else continue;
		if (rule.matches(rule.anchored ? below : name)) ignored = !rule.negate;
	}
	return ignored;
}

function readRules(file: string, base: string, git: boolean): IgnoreRule[] {
	try {
		return parseIgnoreFile(readFileSync(file, "utf8"), base, git);
	} catch {
		return [];
	}
}

/** git's global excludes file: core.excludesFile, or $XDG_CONFIG_HOME/git/ignore. */
function globalExcludesFile(): string {
	try {
		const config = readFileSync(path.join(homedir(), ".gitconfig"), "utf8");
		const match = /^\s*excludesfile\s*=\s*(.+?)\s*$/im.exec(config);
		if (match) {
			const value = match[1].replace(/^"(.*)"$/, "$1");
			return value.startsWith("~") ? path.join(homedir(), value.slice(1)) : value;
		}
	} catch {
		// (No ~/.gitconfig: git's default place.)
	}
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
		const gitDir = /^gitdir:\s*(.+?)\s*$/m.exec(readFileSync(dotGit, "utf8"))?.[1];
		if (!gitDir) return path.join(dotGit, "info", "exclude");
		const resolved = path.resolve(dir, gitDir);
		let common = resolved;
		try {
			common = path.resolve(resolved, readFileSync(path.join(resolved, "commondir"), "utf8").trim());
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
	signal?: AbortSignal;
}

interface DirTask {
	abs: string;
	rel: string;
	rules: IgnoreRule[];
	git: boolean;
}

/** Every entry below `root` that the ignore files leave in, in no particular order. */
export async function walk(options: WalkOptions): Promise<WalkEntry[]> {
	const root = path.resolve(options.root);
	const repository = repositoryOf(root);
	// Paths are matched relative to the top folder: the repository's, whose ignore files above the search folder apply too.
	const top = repository ?? root;
	const rootRel = toPosix(path.relative(top, root));
	const git = repository !== undefined || !options.requireGit;
	const rules: IgnoreRule[] = [];
	if (repository) {
		rules.push(...readRules(globalExcludesFile(), "", true));
		rules.push(...readRules(excludeFileOf(repository), "", true));
		// The ignore files of the folders from the repository's down to the search folder's parent.
		let dir = repository;
		for (const segment of rootRel ? rootRel.split("/") : []) {
			const base = toPosix(path.relative(top, dir));
			rules.push(...readRules(path.join(dir, ".gitignore"), base, true));
			rules.push(...readRules(path.join(dir, ".ignore"), base, false));
			dir = path.join(dir, segment);
		}
	}

	const entries: WalkEntry[] = [];
	const visit = async (task: DirTask): Promise<DirTask[]> => {
		let dirents: Dirent[];
		try {
			dirents = await readdir(task.abs, { withFileTypes: true });
		} catch {
			return []; // (Unreadable: passed over, as ripgrep and fd do.)
		}
		let { rules: taskRules, git: taskGit } = task;
		const names = new Set(dirents.map((d) => d.name));
		const topRel = (rel: string) => (rootRel ? (rel ? `${rootRel}/${rel}` : rootRel) : rel);
		const base = topRel(task.rel);
		if (task.rel !== "" && names.has(".git")) {
			// A repository of its own: the ignore rules of the one around it stop here.
			taskRules = taskRules.filter((rule) => !rule.git);
			taskRules.push(...readRules(excludeFileOf(task.abs), base, true));
			taskGit = true;
		}
		if (taskGit && names.has(".gitignore")) {
			taskRules = [...taskRules, ...readRules(path.join(task.abs, ".gitignore"), base, true)];
		}
		if (names.has(".ignore")) taskRules = [...taskRules, ...readRules(path.join(task.abs, ".ignore"), base, false)];

		const subdirs: DirTask[] = [];
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
			if (taskRules.length > 0 && isIgnored(taskRules, topRel(rel), kind === "dir")) {
				if (kind === "dir" || !options.override?.(rel)) continue;
			}
			const abs = path.join(task.abs, dirent.name);
			entries.push({ rel, abs, kind });
			if (kind === "dir") subdirs.push({ abs, rel, rules: taskRules, git: taskGit });
		}
		return subdirs;
	};

	// Several folders at once: reading a folder waits on the disk, not on the processor.
	const pending: DirTask[] = [{ abs: root, rel: "", rules, git }];
	while (pending.length > 0) {
		if (options.signal?.aborted) throw new Error("Operation aborted");
		const batch = pending.splice(0, 64);
		const found = await Promise.all(batch.map(visit));
		for (const subdirs of found) pending.push(...subdirs);
	}
	return entries;
}

const byPath = (a: WalkEntry, b: WalkEntry) => (a.rel < b.rel ? -1 : a.rel > b.rel ? 1 : 0);

// --- find ---------------------------------------------------------------------------------------------------------------

export interface NativeFindOptions {
	root: string;
	/** The glob, as fd takes it: matched against the name, or with a `/` against the path below the search folder. */
	pattern: string;
	limit: number;
	signal?: AbortSignal;
}

/** The entries that match, in path order, folders with a trailing `/`, at most `limit`. */
export async function nativeFind(options: NativeFindOptions): Promise<string[]> {
	let pattern = options.pattern;
	const fullPath = pattern.includes("/");
	if (pattern.startsWith("/")) pattern = pattern.slice(1);
	else if (fullPath && !pattern.startsWith("**/") && pattern !== "**") pattern = `**/${pattern}`;
	const patterns = expandBraces(pattern).map(compileGlob);
	const entries = await walk({ root: options.root, requireGit: false, signal: options.signal });
	const results: WalkEntry[] = [];
	for (const entry of entries) {
		const subject = fullPath ? entry.rel : entry.rel.slice(entry.rel.lastIndexOf("/") + 1);
		if (patterns.some((matches) => matches(subject))) results.push(entry);
	}
	return results
		.sort(byPath)
		.slice(0, Math.max(0, options.limit))
		.map((entry) => (entry.kind === "dir" ? `${entry.rel}/` : entry.rel));
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

/**
 * The longest run of plain characters every match of `source` contains, when it is easy to be sure of one (no alternation,
 * outside any group, not made optional): files without it need no regular expression run over them.
 */
/** Escapes that are one character of the pattern and match no particular text: classes, anchors, white space. */
const SIMPLE_ESCAPES = "dDwWsSbBAzZntrfv";

export function requiredLiteral(source: string): string | undefined {
	if (source.includes("|") || source.includes("(?")) return undefined;
	let best = "";
	let run = "";
	let depth = 0;
	const end = () => {
		if (run.length > best.length) best = run;
		run = "";
	};
	for (let i = 0; i < source.length; i++) {
		const c = source[i];
		if (c === "\\") {
			// A class or an anchor (`\d`, `\b`), or an escaped sign (`\.`): one character, and not a known one. An escape that
			// stands for a character or more (`\x66`, `\u{1F600}`, `\p{L}`, `\1`) has a length of its own: no literal then.
			const next = source[i + 1] ?? "";
			if (/[0-9A-Za-z]/.test(next) && !SIMPLE_ESCAPES.includes(next)) return undefined;
			end();
			i++;
			continue;
		}
		if (c === "(") {
			end();
			depth++;
			continue;
		}
		if (c === ")") {
			end();
			depth = Math.max(0, depth - 1);
			continue;
		}
		if (c === "[") {
			end();
			const close = source.indexOf("]", i + 2);
			if (close === -1) return undefined;
			i = close;
			continue;
		}
		if (c === "?" || c === "*" || c === "{") {
			// The character before is optional, or repeated an unknown number of times.
			run = run.slice(0, -1);
			end();
			if (c === "{") {
				const close = source.indexOf("}", i);
				if (close === -1) return undefined;
				i = close;
			}
			continue;
		}
		if (c === "+" || c === "." || c === "^" || c === "$") {
			end();
			continue;
		}
		if (depth === 0) run += c;
		else end();
	}
	end();
	return best.length >= 3 ? best : undefined;
}

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

interface Matcher {
	/** Bytes every matching file has (a fast check before anything else). */
	prefilter?: Buffer;
	search(buffer: Buffer, filePath: string, budget: number, keepChars: number, out: NativeGrepMatch[]): void;
}

const isAscii = (text: string) => /^[\x00-\x7f]*$/.test(text);

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

/**
 * The lines that `next` finds a candidate in (the start of an occurrence of what every match contains), and that `test`, when
 * given, takes. Only those lines are decoded and run through a regular expression.
 */
function searchLines(
	buffer: Buffer,
	next: (from: number) => number,
	test: ((line: string) => boolean) | undefined,
	match: { filePath: string; budget: number; keepChars: number; out: NativeGrepMatch[] },
): void {
	let line = 1;
	let counted = 0;
	let found = 0;
	for (let at = next(0); at !== -1 && found < match.budget; ) {
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
	}
}

/** A literal: its bytes, or with `ignoreCase` (ASCII) its letters in either case. */
function literalMatcher(pattern: string, ignoreCase: boolean): Matcher {
	if (ignoreCase) {
		const needle = Buffer.from(pattern.toLowerCase(), "utf8");
		return {
			search: (buffer, filePath, budget, keepChars, out) =>
				searchLines(buffer, (from) => indexOfIgnoringCase(buffer, needle, from), undefined, {
					filePath,
					budget,
					keepChars,
					out,
				}),
		};
	}
	const needle = Buffer.from(pattern, "utf8");
	return {
		prefilter: needle.length > 0 ? needle : undefined,
		search: (buffer, filePath, budget, keepChars, out) =>
			searchLines(
				buffer,
				(from) => (needle.length > 0 ? buffer.indexOf(needle, from) : from < buffer.length ? from : -1),
				undefined,
				{ filePath, budget, keepChars, out },
			),
	};
}

function regexMatcher(source: string, ignoreCase: boolean): Matcher {
	const flags = ignoreCase ? "i" : "";
	let all: RegExp;
	let one: RegExp;
	try {
		all = new RegExp(source, `${flags}gm`);
		one = new RegExp(source, `${flags}m`);
	} catch (error) {
		throw new Error(`regex parse error: ${error instanceof Error ? error.message : String(error)}`);
	}
	const test = (line: string) => one.test(line);
	// What every match contains: only the lines that have it are run through the expression.
	const literal = requiredLiteral(source);
	if (literal && !ignoreCase) {
		const needle = Buffer.from(literal, "utf8");
		return {
			prefilter: needle,
			search: (buffer, filePath, budget, keepChars, out) =>
				searchLines(buffer, (from) => buffer.indexOf(needle, from), test, { filePath, budget, keepChars, out }),
		};
	}
	if (literal && isAscii(literal)) {
		const needle = Buffer.from(literal.toLowerCase(), "utf8");
		return {
			search: (buffer, filePath, budget, keepChars, out) =>
				searchLines(buffer, (from) => indexOfIgnoringCase(buffer, needle, from), test, {
					filePath,
					budget,
					keepChars,
					out,
				}),
		};
	}
	return {
		search(buffer, filePath, budget, keepChars, out) {
			const text = buffer.toString("utf8");
			let line = 1;
			let counted = 0;
			let found = 0;
			all.lastIndex = 0;
			while (found < budget && all.lastIndex <= text.length) {
				const match = all.exec(text);
				if (!match) break;
				const at = match.index;
				// (An empty match after the last line break: there is no line there.)
				if (at === text.length && at > 0 && text[at - 1] === "\n") break;
				const start = at === 0 ? 0 : text.lastIndexOf("\n", at - 1) + 1;
				let end = text.indexOf("\n", at);
				if (end === -1) end = text.length;
				// Within one line, as ripgrep matches: a match that runs past it counts if the line alone has one.
				if (at + match[0].length > end || (at === end && end < text.length)) {
					if (!one.test(text.slice(start, end))) {
						all.lastIndex = at + 1;
						continue;
					}
				}
				line += countLinesIn(text, counted, start);
				counted = start;
				out.push({
					filePath,
					lineNumber: line,
					lineText: stripCr(text.slice(start, Math.min(end, start + keepChars))),
				});
				found++;
				all.lastIndex = end + 1;
			}
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

/** How many files are read at once. */
const READ_AHEAD = 32;

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

	let files: string[];
	if (options.isDirectory) {
		const { take, include } = globFilter(options.glob);
		const entries = await walk({ root: options.root, requireGit: true, override: include, signal: options.signal });
		files = entries
			.filter((entry) => entry.kind === "file" && take(entry.rel))
			.sort(byPath)
			.map((entry) => entry.abs);
	} else {
		files = [options.root];
	}

	const matches: NativeGrepMatch[] = [];
	const read = (file: string) => readFile(file).catch(() => undefined);
	const queue = files.slice(0, READ_AHEAD).map(read);
	for (let index = 0; index < files.length; index++) {
		if (options.signal?.aborted) throw new Error("Operation aborted");
		const buffer = await queue[index];
		if (index + READ_AHEAD < files.length) queue.push(read(files[index + READ_AHEAD]));
		queue[index] = Promise.resolve(undefined); // (Done with it: let it go.)
		if (!buffer || buffer.length === 0) continue;
		if (matcher.prefilter && buffer.indexOf(matcher.prefilter) === -1) continue;
		if (buffer.indexOf(0) !== -1) continue; // (Binary.)
		matcher.search(buffer, files[index], options.limit - matches.length, options.keepChars, matches);
		if (matches.length >= options.limit) return { matches, limitReached: true };
	}
	return { matches, limitReached: false };
}
