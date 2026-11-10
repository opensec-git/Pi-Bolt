/**
 * How far past a line break a regular expression of a highlight.js grammar can read, and an expression that finds where an
 * attempt of it reads the end of the input: what the incremental highlighter (syntax-highlight.ts) relies on to know that
 * what highlight.js found before a line cannot change when text is added after the end.
 */

/** A parsed regular expression (the syntax highlight.js grammars use: no u or v flag). */
export type RegexNode =
	| { kind: "sequence"; items: RegexNode[] }
	| { kind: "alternation"; branches: RegexNode[] }
	| { kind: "group"; capture: number; body: RegexNode }
	| { kind: "lookahead"; negative: boolean; body: RegexNode }
	| { kind: "repeat"; body: RegexNode; min: number; max: number; lazy: boolean; quantifier: string }
	| { kind: "atom"; source: string; reach: Reach }
	| { kind: "assertion"; source: string }
	| { kind: "backreference"; group: number };

/**
 * What one attempt of an expression at a position can read. `lines`: the most line breaks it can read across, each followed
 * by a line with something other than white space on it (a run of white space over blank lines counts once); Infinity when
 * there is no bound. `newline` and `other`: whether it can consume a line break, and a character that is not white space.
 */
interface Reach {
	lines: number;
	newline: boolean;
	other: boolean;
}

const NOTHING: Reach = { lines: 0, newline: false, other: false };
const OTHER: Reach = { lines: 0, newline: false, other: true };
const SPACE: Reach = { lines: 0, newline: false, other: false };
const NEWLINE: Reach = { lines: 1, newline: true, other: false };
const NEWLINE_OR_OTHER: Reach = { lines: 1, newline: true, other: true };
const UNBOUNDED: Reach = { lines: Number.POSITIVE_INFINITY, newline: true, other: true };

/** Thrown for syntax the analysis does not know: such an expression is not analysed. */
class Unsupported extends Error {}

/** White space as `\s` matches it. */
function isSpace(char: string): boolean {
	return /\s/.test(char);
}

function charReach(char: string): Reach {
	if (char === "\n") return NEWLINE;
	return isSpace(char) ? SPACE : OTHER;
}

class Parser {
	private readonly source: string;
	private position = 0;
	private groups = 0;
	/** The expression looks behind its position (lookbehind): it reads text before where it starts. */
	lookbehind = false;

	constructor(source: string) {
		this.source = source;
	}

	parse(): RegexNode {
		const node = this.alternation();
		if (this.position !== this.source.length) throw new Unsupported("unbalanced parenthesis");
		return node;
	}

	private alternation(): RegexNode {
		const branches = [this.sequence()];
		while (this.source[this.position] === "|") {
			this.position++;
			branches.push(this.sequence());
		}
		return branches.length === 1 ? branches[0]! : { kind: "alternation", branches };
	}

	private sequence(): RegexNode {
		const items: RegexNode[] = [];
		while (this.position < this.source.length) {
			const char = this.source[this.position]!;
			if (char === "|" || char === ")") break;
			items.push(this.quantified(this.atom()));
		}
		return { kind: "sequence", items };
	}

	private quantified(atom: RegexNode): RegexNode {
		const source = this.source;
		const start = this.position;
		const char = source[this.position];
		let min = 0;
		let max: number | undefined;
		if (char === "*" || char === "+") {
			min = char === "+" ? 1 : 0;
			max = Number.POSITIVE_INFINITY;
			this.position++;
		} else if (char === "?") {
			max = 1;
			this.position++;
		} else if (char === "{") {
			const quantifier = /^\{(\d+)(,(\d*))?\}/.exec(source.slice(this.position));
			// (Without the u flag a brace that does not make a quantifier is the character.)
			if (quantifier !== null) {
				min = Number(quantifier[1]);
				max =
					quantifier[2] === undefined
						? min
						: quantifier[3] === ""
							? Number.POSITIVE_INFINITY
							: Number(quantifier[3]);
				this.position += quantifier[0].length;
			}
		}
		if (max === undefined) return atom;
		if (atom.kind === "assertion" || atom.kind === "lookahead") throw new Unsupported("quantified assertion");
		let lazy = false;
		if (source[this.position] === "?") {
			lazy = true;
			this.position++;
		}
		return { kind: "repeat", body: atom, min, max, lazy, quantifier: source.slice(start, this.position) };
	}

	private atom(): RegexNode {
		const source = this.source;
		const char = source[this.position]!;
		switch (char) {
			case "(":
				return this.group();
			case "[":
				return this.characterClass();
			case "\\":
				return this.escape();
			case ".":
				this.position++;
				return { kind: "atom", source: ".", reach: OTHER };
			case "^":
			case "$":
				this.position++;
				return { kind: "assertion", source: char };
			case "*":
			case "+":
			case "?":
				throw new Unsupported("nothing to repeat");
			default:
				this.position++;
				return { kind: "atom", source: char, reach: charReach(char) };
		}
	}

	private group(): RegexNode {
		const source = this.source;
		this.position++;
		let capture = 0;
		let lookahead: boolean | undefined;
		if (source[this.position] === "?") {
			const kind = source.slice(this.position, this.position + 3);
			if (kind.startsWith("?:")) {
				this.position += 2;
			} else if (kind.startsWith("?=") || kind.startsWith("?!")) {
				lookahead = kind[1] === "!";
				this.position += 2;
			} else if (kind === "?<=" || kind === "?<!") {
				this.lookbehind = true;
				lookahead = kind[2] === "!";
				this.position += 3;
			} else {
				throw new Unsupported(`group ${kind}`);
			}
		} else {
			capture = ++this.groups;
		}
		const body = this.alternation();
		if (source[this.position] !== ")") throw new Unsupported("unclosed group");
		this.position++;
		if (lookahead !== undefined) return { kind: "lookahead", negative: lookahead, body };
		return { kind: "group", capture, body };
	}

	private characterClass(): RegexNode {
		const source = this.source;
		const start = this.position;
		let end = start + 1;
		if (source[end] === "^") end++;
		// (A `]` right after the bracket closes an empty class, as JavaScript reads it.)
		while (end < source.length && source[end] !== "]") {
			end += source[end] === "\\" ? 2 : 1;
		}
		if (end >= source.length) throw new Unsupported("unclosed class");
		this.position = end + 1;
		const text = source.slice(start, end + 1);
		const newline = new RegExp(text).test("\n");
		const negated = source[start + 1] === "^";
		const other = negated || classHasOther(source.slice(start + 1, end));
		return {
			kind: "atom",
			source: text,
			reach: newline ? { lines: 1, newline: true, other } : other ? OTHER : SPACE,
		};
	}

	private escape(): RegexNode {
		const source = this.source;
		const start = this.position;
		const next = source[start + 1];
		if (next === undefined) throw new Unsupported("trailing backslash");
		this.position += 2;
		const atom = (reach: Reach): RegexNode => ({ kind: "atom", source: source.slice(start, this.position), reach });
		switch (next) {
			case "s":
			case "n":
				return atom(NEWLINE);
			case "W":
			case "D":
				return atom(NEWLINE_OR_OTHER);
			case "S":
			case "w":
			case "d":
				return atom(OTHER);
			case "b":
			case "B":
				return { kind: "assertion", source: `\\${next}` };
			case "r":
			case "t":
			case "v":
			case "f":
				return atom(SPACE);
			case "c": {
				const letter = source[this.position];
				if (letter !== undefined && /[A-Za-z]/.test(letter)) {
					this.position++;
					return atom(charReach(String.fromCharCode(letter.charCodeAt(0) % 32)));
				}
				throw new Unsupported("\\c");
			}
			case "x":
			case "u": {
				const digits = next === "x" ? 2 : 4;
				const hex = new RegExp(`^[0-9A-Fa-f]{${digits}}`).exec(source.slice(this.position));
				if (hex === null) throw new Unsupported(`\\${next}`);
				this.position += digits;
				return atom(charReach(String.fromCharCode(Number.parseInt(hex[0], 16))));
			}
			case "k":
				throw new Unsupported("named backreference");
			case "0":
				if (/[0-9]/.test(source[this.position] ?? "")) throw new Unsupported("octal escape");
				return atom(SPACE);
			default:
				break;
		}
		if (/[1-9]/.test(next)) {
			const digits = /^\d*/.exec(source.slice(this.position))![0];
			this.position += digits.length;
			const group = Number(next + digits);
			// (A number above the count of groups would be an octal escape without the u flag: not in the grammars.)
			if (group > countCapturingGroups(source)) throw new Unsupported("octal escape");
			return { kind: "backreference", group };
		}
		if (/[A-Za-z]/.test(next)) throw new Unsupported(`\\${next}`);
		return atom(charReach(next));
	}
}

/** The number of capturing groups in an expression's source. */
function countCapturingGroups(source: string): number {
	let count = 0;
	let inClass = false;
	for (let i = 0; i < source.length; i++) {
		const char = source[i];
		if (char === "\\") {
			i++;
		} else if (inClass) {
			if (char === "]") inClass = false;
		} else if (char === "[") {
			inClass = true;
		} else if (char === "(" && source[i + 1] !== "?") {
			count++;
		}
	}
	return count;
}

/** Whether a class (its text between the brackets, not negated) has a member that is not white space. */
function classHasOther(body: string): boolean {
	for (let i = 0; i < body.length; i++) {
		let first: number;
		if (body[i] === "\\") {
			const next = body[i + 1];
			i++;
			if (next === "s" || next === "n" || next === "r" || next === "t" || next === "v" || next === "f") continue;
			const hex = /^(?:x([0-9A-Fa-f]{2})|u([0-9A-Fa-f]{4}))/.exec(body.slice(i));
			if (hex === null) return true;
			first = Number.parseInt(hex[1] ?? hex[2]!, 16);
			i += hex[0].length - 1;
		} else {
			first = body.charCodeAt(i);
		}
		let last = first;
		if (body[i + 1] === "-" && i + 2 < body.length) {
			// A range: its end as a character or a hexadecimal escape; any other escape there is taken to add text.
			if (body[i + 2] === "\\") {
				const hex = /^\\(?:x([0-9A-Fa-f]{2})|u([0-9A-Fa-f]{4}))/.exec(body.slice(i + 2));
				if (hex === null) return true;
				last = Number.parseInt(hex[1] ?? hex[2]!, 16);
				i += 1 + hex[0].length;
			} else {
				last = body.charCodeAt(i + 2);
				i += 2;
			}
		}
		if (last - first > 64) return true;
		for (let code = first; code <= last; code++) {
			if (!isSpace(String.fromCharCode(code))) return true;
		}
	}
	return false;
}

/** The reach of a node; `groups`: the reach of each capturing group seen so far, by number. */
function reachOf(node: RegexNode, groups: Map<number, Reach>): Reach {
	switch (node.kind) {
		case "atom":
			return node.reach;
		case "assertion":
			return NOTHING;
		case "backreference":
			// (What a group matched, again: no more than the group could read.)
			return groups.get(node.group) ?? UNBOUNDED;
		case "group": {
			const reach = reachOf(node.body, groups);
			if (node.capture > 0) groups.set(node.capture, reach);
			return reach;
		}
		case "lookahead":
			// It reads what its body would consume, and consumes nothing.
			return { lines: reachOf(node.body, groups).lines, newline: false, other: false };
		case "sequence": {
			let reach = NOTHING;
			for (const item of node.items) {
				const next = reachOf(item, groups);
				reach = {
					lines: reach.lines + next.lines,
					newline: reach.newline || next.newline,
					other: reach.other || next.other,
				};
			}
			return reach;
		}
		case "alternation": {
			let reach = NOTHING;
			for (const branch of node.branches) {
				const next = reachOf(branch, groups);
				reach = {
					lines: Math.max(reach.lines, next.lines),
					newline: reach.newline || next.newline,
					other: reach.other || next.other,
				};
			}
			return reach;
		}
		case "repeat": {
			const body = reachOf(node.body, groups);
			if (node.max === 0) return NOTHING;
			if (Number.isFinite(node.max)) return { ...body, lines: body.lines * node.max };
			// Without a bound, a body that never consumes a line break stays on its line (what it looks at past it is its
			// own reach); one that consumes only white space and line breaks crosses blank lines into one line with text.
			if (!body.newline) return body;
			if (!body.other && body.lines <= 1) return { lines: 1, newline: true, other: false };
			return UNBOUNDED;
		}
	}
}

/** What analyse() finds out about an expression. */
export interface RegexAnalysis {
	/**
	 * The most line breaks, each followed by a line with text, that one attempt of the expression at a position can read
	 * across (consuming or looking ahead); Infinity when that has no bound.
	 */
	lines: number;
	/**
	 * Source of an expression that matches (empty) at the positions where an attempt of this expression reads the end of
	 * the input, and possibly at others; undefined when none could be made.
	 */
	hitEnd?: string;
}

/** The parsed expression; undefined for syntax analyse() does not know, and for one that looks behind its position. */
export function parseRegex(source: string): RegexNode | undefined {
	const parser = new Parser(source);
	try {
		const node = parser.parse();
		return parser.lookbehind ? undefined : node;
	} catch (error) {
		if (error instanceof Unsupported) return undefined;
		throw error;
	}
}

/**
 * Analyses the source of an expression as highlight.js uses it (the flags m, g and i only). Undefined for syntax this
 * does not know, and for an expression that looks behind its position.
 */
export function analyse(source: string): RegexAnalysis | undefined {
	let node: RegexNode;
	const parser = new Parser(source);
	try {
		node = parser.parse();
	} catch (error) {
		if (error instanceof Unsupported) return undefined;
		throw error;
	}
	if (parser.lookbehind) return undefined;
	const lines = reachOf(node, new Map()).lines;
	if (lines === 0) return { lines };
	let hitEnd: string | undefined;
	try {
		hitEnd = new HitEnd().top(node);
	} catch (error) {
		if (!(error instanceof Unsupported)) throw error;
	}
	return hitEnd === undefined ? { lines } : { lines, hitEnd };
}

/** End of the input (not of a line: the m flag is set). */
const END = "(?![\\s\\S])";
/** Matches nowhere. */
const NEVER = "(?!)";

/** One term of an expression: a character, an escape, a class, or END or NEVER. */
const SINGLE_TERM = /^(?:\\.|\[(?:\\.|[^\]\\])*\]|[^\\[\]()|*+?{}^$]|\(\?!\[\\s\\S\]\)|\(\?!\))$/;

/**
 * `source` as one term, in a non-capturing group unless it is one already. (Needless groups are not only longer: the
 * interpreter of JavaScriptCore's expressions repeats a group much more slowly than a character class.)
 */
function term(source: string): string {
	return SINGLE_TERM.test(source) || isGroup(source) ? source : `(?:${source})`;
}

/** Whether `source` is one group: its first parenthesis closes at its end. */
function isGroup(source: string): boolean {
	if (source[0] !== "(") return false;
	let depth = 0;
	for (let i = 0; i < source.length; i++) {
		const char = source[i];
		if (char === "\\") {
			i++;
		} else if (char === "[") {
			while (i < source.length && source[i] !== "]") i += source[i] === "\\" ? 2 : 1;
		} else if (char === "(") {
			depth++;
		} else if (char === ")" && --depth === 0) {
			return i === source.length - 1;
		}
	}
	return false;
}

/**
 * Builds the "hit end" expression of an expression R: it matches at a position when some way R's attempt there can go
 * reads the end of the input. That is more than the ways the engine tries (it stops at the first that matches), so it
 * finds the positions to fear and maybe others, never fewer. For each node X, H(X) matches where an attempt of X reads
 * the end: an atom or an assertion that looks at the next character, at the end; a sequence X Y, H(X) or X then H(Y); an
 * alternation, the H of a branch; a repetition, some repetitions then H of the body; a lookahead, H of its body.
 *
 * Capturing groups keep their numbers: the groups are capturing only where the expression's own parts are written out in
 * order along the main sequence, every other copy has none. A lazy repetition of the main sequence is followed as the
 * engine follows it (it stops at the first count after which the rest matches), so that `"(.|\n)*?"` does not count as
 * reading to the end once its closing quote is there.
 */
class HitEnd {
	/** The capturing groups written out (capturing) so far, along the current way. */
	private readonly captured = new Set<number>();
	/** Each group's node, by number, for a backreference to a group not captured on the way. */
	private readonly groups = new Map<number, RegexNode>();

	top(node: RegexNode): string {
		collectGroups(node, this.groups);
		return this.hit(node, true);
	}

	/**
	 * H(node). `main`: the node is on the main sequence of the expression or of one of its top-level branches: what follows
	 * it is what follows in the expression, and its own parts are written out with their capturing groups.
	 */
	private hit(node: RegexNode, main: boolean): string {
		switch (node.kind) {
			case "atom":
				return END;
			case "assertion":
				// (`^` looks at the character before; `$`, `\b` and `\B` at the next one.)
				return node.source === "^" ? NEVER : END;
			case "backreference": {
				// The text the group matched is matched again: it reads to the end only where the rest of the input is
				// shorter than that text and starts it, which an attempt of the group itself also does.
				const group = this.groups.get(node.group);
				if (group === undefined) throw new Unsupported("backreference");
				return term(this.hit(group, false));
			}
			case "group":
				return term(this.hit(node.body, false));
			case "lookahead":
				return `(?=${this.hit(node.body, false)})`;
			case "alternation":
				return node.branches.map((branch) => this.hit(branch, main)).join("|");
			case "sequence":
				return this.sequence(node.items, 0, main);
			case "repeat": {
				if (!Number.isFinite(node.max) && endsInOptionalAfterRun(node.body)) {
					// `(?:a[^\n]+\n?)*` followed by an expression that fails: the engine tries every way of cutting the text
					// into repetitions, which grows exponentially with its length (a YAML block scalar hung on 400 characters).
					throw new Unsupported("repetition that can be cut up in many ways");
				}
				const body = this.plain(node.body);
				const before = Number.isFinite(node.max) ? `{0,${node.max - 1}}` : "*";
				return `${term(body)}${before}${term(this.hit(node.body, false))}`;
			}
		}
	}

	/** H of the sequence `items` from `index` on: H(first) | first H(rest). */
	private sequence(items: RegexNode[], index: number, main: boolean): string {
		if (index >= items.length) return NEVER;
		const item = items[index]!;
		if (main && index === items.length - 1 && item.kind === "repeat" && !Number.isFinite(item.max)) {
			const lines = this.lineRun(item.body);
			if (lines !== undefined) return lines;
		}
		if (main && item.kind === "repeat" && item.lazy && item.min === 0 && !Number.isFinite(item.max)) {
			return this.lazy(item, items, index);
		}
		const first = this.hit(item, false);
		if (index === items.length - 1) return first;
		const written = main ? this.original(item) : this.plain(item);
		return `${first}|${written}${term(this.sequence(items, index + 1, main))}`;
	}

	/**
	 * H of a lazy unbounded repetition X*? of the main sequence and what follows it: the engine tries the rest after 0, 1,
	 * 2... repetitions and stops at the first count where it matches. So: repetitions while the rest does not match there,
	 * then X or the rest reads the end. (In a negative lookahead the rest is written exactly, so a backreference in it must
	 * be to a group captured before.)
	 */
	private lazy(item: { body: RegexNode }, items: RegexNode[], index: number): string {
		const restItems = items.slice(index + 1);
		const rest = restItems.map((restItem) => this.plain(restItem, true)).join("");
		const body = this.original(item.body);
		const restHit = this.sequence(items, index + 1, true);
		return `(?:(?!${rest})${term(body)})*?(?:${this.hit(item.body, false)}|${restItems.length === 0 ? NEVER : restHit})`;
	}

	/**
	 * H of an unbounded repetition that ends the expression, when its body reads a line break only as an optional last part
	 * and starts with a character F that is not one (YAML's block scalar, `(\2[^\n]+\n?)*`): undefined otherwise. Every
	 * line such repetitions take whole starts with a repetition, so with F; the line where an attempt reads the end is any
	 * line. So `(?:F[^\n]*\n)*[^\n]*` to the end matches wherever some way of the repetitions reads the end, and (each line
	 * taken one way: F, the rest of the line, its line break) in one way only, where the repetition itself can cut a line
	 * into repetitions in many ways (exponential when what follows fails). Only at the end of the expression: a copy of the
	 * repetition written out before another part would keep those ways.
	 */
	private lineRun(body: RegexNode): string | undefined {
		const items = body.kind === "sequence" ? body.items : body.kind === "group" ? sequenceItems(body.body) : [body];
		const last = items[items.length - 1];
		if (
			items.length < 2 ||
			last?.kind !== "repeat" ||
			last.min !== 0 ||
			last.max !== 1 ||
			last.body.kind !== "atom" ||
			last.body.source !== "\\n"
		) {
			return undefined;
		}
		for (const item of items.slice(0, -1)) {
			const reach = item.kind === "backreference" ? this.groupReach(item.group) : reachOf(item, new Map());
			if (reach === undefined || reach.newline) return undefined;
		}
		const first = canBeEmpty(items[0]!) ? undefined : this.firstCharacter(items[0]!);
		if (first === undefined || new RegExp(first).test("\n")) return undefined;
		return `(?:${first}[^\\n]*\\n)*[^\\n]*${END}`;
	}

	private groupReach(group: number): Reach | undefined {
		const node = this.groups.get(group);
		return node === undefined ? undefined : reachOf(node, new Map());
	}

	/** The atom every match of `node` starts with (a match is never empty), or undefined. */
	private firstCharacter(node: RegexNode): string | undefined {
		switch (node.kind) {
			case "atom":
				return node.source;
			case "backreference": {
				const group = this.groups.get(node.group);
				return group === undefined ? undefined : this.firstCharacter(group);
			}
			case "group":
				return this.firstCharacter(node.body);
			case "sequence":
				return node.items.length > 0 && !canBeEmpty(node.items[0]!)
					? this.firstCharacter(node.items[0]!)
					: undefined;
			case "repeat":
				return node.min >= 1 && !canBeEmpty(node.body) ? this.firstCharacter(node.body) : undefined;
			default:
				return undefined;
		}
	}

	/** The node written out as it is, its capturing groups capturing (they are then captured for what follows). */
	private original(node: RegexNode): string {
		return this.write(node, true, false);
	}

	/**
	 * The node written out without capturing groups. A backreference to a group not captured on the way is replaced by the
	 * group's own expression (more than it matches) or, where that would match less (`exact`, as in a negative lookahead),
	 * not allowed.
	 */
	private plain(node: RegexNode, exact = false): string {
		return this.write(node, false, exact);
	}

	private write(node: RegexNode, capturing: boolean, exact: boolean): string {
		switch (node.kind) {
			case "atom":
				return node.source;
			case "assertion":
				return node.source;
			case "backreference":
				if (this.captured.has(node.group)) return `\\${node.group}`;
				if (exact) throw new Unsupported("backreference in an exact copy");
				return term(this.write(this.groups.get(node.group)!, false, false));
			case "group": {
				const body = this.write(node.body, capturing, exact);
				if (capturing && node.capture > 0) {
					this.captured.add(node.capture);
					return `(${body})`;
				}
				return term(body);
			}
			case "lookahead":
				// (A negative lookahead must not match more than the original: its body is written exactly.)
				return `(?${node.negative ? "!" : "="}${this.write(node.body, capturing, exact || node.negative)})`;
			case "alternation":
				return node.branches.map((branch) => this.write(branch, capturing, exact)).join("|");
			case "sequence":
				return node.items.map((item) => this.write(item, capturing, exact)).join("");
			case "repeat": {
				return `${term(this.write(node.body, capturing, exact))}${node.quantifier}`;
			}
		}
	}
}

/**
 * Whether the body of a repetition is, or ends with, an unbounded run of one atom followed only by parts that can match
 * nothing (`a[^\n]+\n?`): the repetition can then cut the same text into repetitions in many ways (the run can stop
 * anywhere and the next repetition start there), and an expression after it that fails makes the engine try each.
 */
function endsInOptionalAfterRun(body: RegexNode): boolean {
	const items = body.kind === "sequence" ? body.items : body.kind === "group" ? [body.body] : [body];
	if (
		items.length === 1 &&
		items[0]!.kind !== body.kind &&
		(items[0]!.kind === "group" || items[0]!.kind === "sequence")
	) {
		return endsInOptionalAfterRun(items[0]!);
	}
	let index = items.length - 1;
	while (index >= 0 && canBeEmpty(items[index]!)) index--;
	if (index < 0 || index === items.length - 1) return false;
	const last = items[index]!;
	return last.kind === "repeat" && !Number.isFinite(last.max) && last.body.kind === "atom";
}

/** Whether the node can match the empty string (conservatively: no for what is not one of these). */
function canBeEmpty(node: RegexNode): boolean {
	switch (node.kind) {
		case "repeat":
			return node.min === 0 || canBeEmpty(node.body);
		case "group":
			return canBeEmpty(node.body);
		case "sequence":
			return node.items.every(canBeEmpty);
		case "alternation":
			return node.branches.some(canBeEmpty);
		case "lookahead":
		case "assertion":
			return true;
		default:
			return false;
	}
}

function sequenceItems(node: RegexNode): RegexNode[] {
	return node.kind === "sequence" ? node.items : [node];
}

function collectGroups(node: RegexNode, groups: Map<number, RegexNode>): void {
	switch (node.kind) {
		case "group":
			if (node.capture > 0) groups.set(node.capture, node.body);
			collectGroups(node.body, groups);
			break;
		case "lookahead":
		case "repeat":
			collectGroups(node.body, groups);
			break;
		case "sequence":
			for (const item of node.items) collectGroups(item, groups);
			break;
		case "alternation":
			for (const branch of node.branches) collectGroups(branch, groups);
			break;
		default:
			break;
	}
}
