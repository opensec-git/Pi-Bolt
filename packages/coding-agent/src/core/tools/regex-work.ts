/**
 * Pi-Bolt: an upper bound on the work of running a JavaScript regular expression over a text, so that the in-process grep
 * (native-search.ts) runs one only where it cannot take long.
 *
 * JavaScriptCore's engine (Yarr) backtracks: on a failed match it goes back and tries every other way the expression could
 * match, and one call cannot be interrupted. `Get.*Key.*Operation` over a 444 KB line with hundreds of "get" and "key" and no
 * "operation" tries every "get" with every "key" with every place after it, about 10^11 steps: minutes in one call, with the
 * screen, Escape and the abort signal all waiting. (Yarr's own step limit counts only the backtracking into groups, not into
 * `.*`, and when it is hit the call returns "no match", which is wrong.) ripgrep, which Pi runs, is linear in the text.
 *
 * The bound counts the steps of an exhaustive backtracking search: every place a match can start, every way each part of the
 * expression can match from there, and for each of those the work of the parts after it. It is computed from the expression
 * and the text, never by running the expression:
 * - from the length of the text alone (`maxLength`: the longest text whose bound is within the budget), which needs no look at
 *   the text and covers nearly every line;
 * - for a longer text, from what is in it: first how often each run of plain characters (`Get`) occurs, then, if that is not
 *   enough, the longest run of the characters each repeated class (`.`, `\s`, `[^"]`) takes. Every `Get` is tried with every
 *   place a `.*` after it can end, but only the places where `Key` occurs go on, so a long line with few of them is still
 *   cheap.
 */

/** One part of an expression. */
type Term =
	| { kind: "char"; char: string }
	| { kind: "set"; source: string }
	| { kind: "assert" }
	| { kind: "look"; alternatives: Term[][] }
	| { kind: "group"; alternatives: Term[][] }
	| { kind: "backref" }
	| { kind: "repeat"; term: Term; min: number; max: number };

/** What the bound knows about the text: its length, and (when it looks at the text) counts and runs within it. */
interface Facts {
	length: number;
	/** How often `literal` occurs (overlapping), or Infinity when unknown. */
	count(literal: string): number;
	/** How often `literal`'s first character occurs, or Infinity when not looked at. */
	starts(literal: string): number;
	/** The longest run of characters the one-character expression `source` matches, at most `length`. */
	run(source: string): number;
}

/**
 * Steps an exhaustive search may take, at most, in one call, before the screen and the abort signal have their turn again.
 * A step took 0.3 to 5 ns without the JIT on Apple M5 (the most for alternatives and case-insensitive matching): at most
 * about 50 ms there.
 */
export const REGEX_WORK_BUDGET = 10_000_000;

/** Steps a repeated group's iteration costs, against one of a repeated character: Yarr saves and restores its state. */
const GROUP_ITERATION_STEPS = 8;

/** Ways of matching the bound follows before it gives up (alternatives inside alternatives multiply them). */
const MAX_PATHS_FOLLOWED = 4096;

/** A repeat with a fixed count up to this many times is followed as that many copies. */
const MAX_UNROLLED = 16;

/** The bound for an expression (JavaScript syntax, without the u flag, as native-search.ts runs it). */
export interface RegexWork {
	/** The longest text that the expression is within the budget on whatever it holds; -1 for none. */
	maxLength: number;
	/** Whether running the expression over `text` (from any position) is within the budget. */
	fits(text: string): boolean;
	/**
	 * `fits`, from the length and the counts of plain characters only: false for some texts that fit, in a small part of the
	 * time (finding the runs of a class takes about as long as running the expression).
	 */
	fitsByCounts(text: string): boolean;
	/** The bound itself, in steps: from the length alone, or from what `text` holds. */
	steps(text: string | number): number;
	/**
	 * Whether a match within a part of a text is one in the text: no anchor, word boundary or lookaround, which could match
	 * at the part's ends and not in the text.
	 */
	local: boolean;
}

export function regexWork(source: string, ignoreCase: boolean): RegexWork {
	const alternatives = parse(source);
	const runExpressions = new Map<string, RegExp>();
	const runOf = (set: string, text: string): number => {
		let expression = runExpressions.get(set);
		if (!expression) {
			expression = new RegExp(`(?:${set})+`, ignoreCase ? "gi" : "g");
			runExpressions.set(set, expression);
		}
		// (`x+` with nothing after it matches greedily and never backtracks: linear in the text.)
		let longest = 0;
		expression.lastIndex = 0;
		for (let match = expression.exec(text); match; match = expression.exec(text)) {
			if (match[0].length > longest) longest = match[0].length;
		}
		return longest;
	};
	const disjoint = disjointClasses(ignoreCase);
	const stepsFor = (facts: Facts) => (alternatives ? bound(alternatives, facts, disjoint) : Number.POSITIVE_INFINITY);
	const unknown = () => Number.POSITIVE_INFINITY;
	const byLength = (length: number) => stepsFor({ length, count: unknown, starts: unknown, run: () => length });
	// What `text` holds: how often each literal occurs (a search for it, fast); and, only when the counts alone leave the bound
	// above the budget, how often their first characters occur and the runs of each repeated class (a run of the class's own
	// expression over the text), which take longer to find.
	const textFacts = (text: string): { counted: Facts; full: Facts } => {
		let lower: string | undefined;
		const counts = new Map<string, number>();
		const runs = new Map<string, number>();
		const count = (literal: string) => {
			if (ignoreCase && !isAscii(literal)) return Number.POSITIVE_INFINITY;
			const needle = ignoreCase ? literal.toLowerCase() : literal;
			let found = counts.get(needle);
			if (found === undefined) {
				lower ??= ignoreCase ? text.toLowerCase() : text;
				found = 0;
				for (let at = lower.indexOf(needle); at !== -1; at = lower.indexOf(needle, at + 1)) found++;
				counts.set(needle, found);
			}
			return found;
		};
		const run = (set: string) => {
			let longest = runs.get(set);
			if (longest === undefined) {
				longest = runOf(set, text);
				runs.set(set, longest);
			}
			return longest;
		};
		const starts = (literal: string) => count(literal[0]);
		return {
			counted: { length: text.length, count, starts: unknown, run: () => text.length },
			full: { length: text.length, count, starts, run },
		};
	};
	const maxLength = longestWithin(byLength);
	return {
		maxLength,
		local: alternatives?.every((terms) => terms.every(isLocal)) ?? false,
		fits(text) {
			if (text.length <= maxLength) return true;
			const facts = textFacts(text);
			return stepsFor(facts.counted) <= REGEX_WORK_BUDGET || stepsFor(facts.full) <= REGEX_WORK_BUDGET;
		},
		fitsByCounts: (text) => text.length <= maxLength || stepsFor(textFacts(text).counted) <= REGEX_WORK_BUDGET,
		steps: (text) => (typeof text === "number" ? byLength(text) : stepsFor(textFacts(text).full)),
	};
}

const isAscii = (text: string) => /^[\x00-\x7f]*$/.test(text);

/** The longest length whose bound is within the budget (the bound grows with the length), by bisection; -1 for none. */
function longestWithin(byLength: (length: number) => number): number {
	if (byLength(0) > REGEX_WORK_BUDGET) return -1;
	let low = 0;
	let high = 1;
	while (high < 2 ** 30 && byLength(high) <= REGEX_WORK_BUDGET) {
		low = high;
		high *= 2;
	}
	if (high >= 2 ** 30) return high;
	while (high - low > 1) {
		const middle = Math.floor((low + high) / 2);
		if (byLength(middle) <= REGEX_WORK_BUDGET) low = middle;
		else high = middle;
	}
	return low;
}

// --- the bound ----------------------------------------------------------------------------------------------------------

/**
 * Where a search is: `groups` sets of `size` places each, the places within a set all different. A match starts at every
 * place of the text (one set of length + 1); a part that can match in several ways turns each place into a set of the places
 * it can end at (those of one place are different, those of two places may be the same); a run of plain characters keeps only
 * the places it occurs at, at most as many in each set as it occurs in the text.
 */
interface Where {
	groups: number;
	size: number;
}

/** What following an expression needs: the facts, how many ways it has followed, and which classes share no character. */
interface Walk {
	facts: Facts;
	paths: number;
	disjoint: (a: string, b: string) => boolean;
}

/** The steps of an exhaustive search for `alternatives` from every place of the text (Infinity when beyond any budget). */
function bound(alternatives: Term[][], facts: Facts, disjoint: (a: string, b: string) => boolean): number {
	const walk: Walk = { facts, paths: 0, disjoint };
	let steps = facts.length + 1; // (Trying each place.)
	for (const terms of alternatives) {
		steps += follow(terms, 0, { groups: 1, size: facts.length + 1 }, walk, []).steps;
		if (steps > Number.MAX_SAFE_INTEGER) return Number.POSITIVE_INFINITY;
	}
	return steps;
}

/**
 * The steps of `terms[i..]` from `where`, and how many ways it ends (`ends`: places, counted with repeats). `trailing`: the
 * classes whose runs the places of each set of `where` are in, after a repeat (`\s+` in `\s+\w+`): every place of a set but
 * its last is followed by a character of one of them, so a part that matches none of their characters goes on from one
 * place of each set at most.
 */
function follow(
	terms: Term[],
	i: number,
	where: Where,
	walk: Walk,
	trailing: string[],
): { steps: number; ends: number } {
	if (++walk.paths > MAX_PATHS_FOLLOWED) return { steps: Number.POSITIVE_INFINITY, ends: Number.POSITIVE_INFINITY };
	let { groups, size } = where;
	let steps = 0;
	const { facts } = walk;
	const apart = (sets: string[], first: string) => sets.length > 0 && sets.every((set) => walk.disjoint(set, first));
	while (i < terms.length) {
		if (groups * size === 0) return { steps, ends: 0 };
		const term = terms[i];
		const before = trailing;
		trailing = [];
		const first = firstClass(term);
		if (first !== undefined && apart(before, first)) size = Math.min(size, 1);
		const entries = groups * size;
		switch (term.kind) {
			case "char": {
				let literal = "";
				while (i < terms.length && terms[i].kind === "char") literal += (terms[i++] as { char: string }).char;
				// A step at each place; more only where its first character is.
				steps += entries + groups * Math.min(size, facts.starts(literal)) * literal.length;
				size = Math.min(size, facts.count(literal));
				continue;
			}
			case "set":
			case "assert":
				steps += entries;
				break;
			case "backref":
				steps += entries * (facts.length + 1);
				groups = entries;
				size = 1;
				break;
			case "look": {
				let inside = 0;
				for (const alternative of term.alternatives) {
					inside += follow(alternative, 0, { groups: 1, size: 1 }, walk, []).steps;
				}
				steps += entries * (inside + 1);
				break;
			}
			case "group": {
				// Each alternative with what follows it, as backtracking tries them.
				const rest = terms.slice(i + 1);
				let ends = 0;
				for (const alternative of term.alternatives) {
					const result = follow([...alternative, ...rest], 0, { groups, size }, walk, before);
					steps += result.steps;
					ends += result.ends;
				}
				return { steps: steps + entries, ends };
			}
			case "repeat": {
				const inner = term.term;
				if (inner.kind === "char" || inner.kind === "set") {
					const set = inner.kind === "char" ? escapeChar(inner.char) : inner.source;
					const run = facts.run(set);
					const most = Math.min(term.max, run);
					if (term.min === 0 && size > 1 && apart(before, set)) {
						// (`\w+\s*`: of each set, only its last place starts a run of the class; the others match it empty.)
						steps += entries + groups * (most + 1);
						size += most;
						trailing = [...before, set];
						break;
					}
					steps += entries * (most + 1);
					if (most < term.min) return { steps, ends: 0 };
					const ways = most - term.min + 1;
					if (ways > 1) {
						groups = entries;
						size = ways;
						trailing = [set];
					}
					break;
				}
				if (term.max === term.min && term.min <= MAX_UNROLLED) {
					const copies: Term[] = Array(term.min).fill(inner);
					return addSteps(follow([...copies, ...terms.slice(i + 1)], 0, { groups, size }, walk, before), steps);
				}
				if (term.min === 0 && term.max === 1) {
					const rest = terms.slice(i + 1);
					const once = follow([inner, ...rest], 0, { groups, size }, walk, before);
					const none = follow(rest, 0, { groups, size }, walk, before);
					return { steps: steps + once.steps + none.steps, ends: once.ends + none.ends };
				}
				const repeat = repeatedGroup(inner, term.min, term.max, facts, walk);
				steps += entries * repeat.steps;
				if (repeat.ways === 0) return { steps, ends: 0 };
				if (repeat.ways > 1) {
					groups = entries;
					size = repeat.ways;
				}
				break;
			}
		}
		i++;
	}
	return { steps, ends: groups * size };
}

function addSteps(result: { steps: number; ends: number }, steps: number): { steps: number; ends: number } {
	return { steps: result.steps + steps, ends: result.ends };
}

/**
 * A group repeated an unknown number of times, from one place: its steps and the ways it can end. A group that matches only
 * one way and a fixed number of characters (`(?:ab)+`) ends after each count of iterations; any other (`(a|ab)*`, `(\w+\.)+`)
 * can split the same text into iterations in exponentially many ways.
 */
function repeatedGroup(
	inner: Term,
	min: number,
	max: number,
	facts: Facts,
	walk: Walk,
): { steps: number; ways: number } {
	const once = follow([inner], 0, { groups: 1, size: 1 }, walk, []);
	const [narrowest, widest] = width(inner);
	// Every character the iterations take is one the group's parts match: they are within a run of those characters.
	const parts = characters(inner);
	const run = parts ? facts.run(parts.join("|")) : facts.length;
	const iterations = Math.min(max, Math.floor(run / Math.max(1, narrowest)));
	if (iterations < min) return { steps: (iterations + 1) * (once.steps + 1) * GROUP_ITERATION_STEPS, ways: 0 };
	const perIteration = (once.steps + 1) * GROUP_ITERATION_STEPS;
	if (once.ends <= 1 && narrowest === widest && narrowest > 0) {
		return { steps: (iterations + 1) * perIteration, ways: iterations - min + 1 };
	}
	const ways = (once.ends + 1) ** iterations;
	return { steps: ways * perIteration, ways };
}

/**
 * The class `term`'s first character is in, when it has to have one (a character, a class, a repeat of one at least once);
 * undefined otherwise.
 */
function firstClass(term: Term): string | undefined {
	if (term.kind === "char") return escapeChar(term.char);
	if (term.kind === "set") return term.source;
	if (term.kind === "repeat" && term.min >= 1) {
		const inner = term.term;
		if (inner.kind === "char") return escapeChar(inner.char);
		if (inner.kind === "set") return inner.source;
	}
	return undefined;
}

/**
 * Whether two one-character expressions share no character (`\s` and `\w`, `\w` and `(`), cached: compared on the 128 ASCII
 * characters, and either may match a character past them unless it is known not to (`\d`, `\w`, a class of ASCII
 * characters only: without the u flag, case folding never maps such a character into ASCII).
 */
function disjointClasses(ignoreCase: boolean): (a: string, b: string) => boolean {
	const classes = new Map<string, { ascii: boolean[]; wide: boolean }>();
	const describe = (source: string) => {
		let described = classes.get(source);
		if (!described) {
			const expression = new RegExp(`^(?:${source})$`, ignoreCase ? "i" : "");
			const ascii = Array.from({ length: 128 }, (_, code) => expression.test(String.fromCharCode(code)));
			described = { ascii, wide: mayMatchPastAscii(source) };
			classes.set(source, described);
		}
		return described;
	};
	return (a, b) => {
		const first = describe(a);
		const second = describe(b);
		if (first.wide && second.wide) return false;
		for (let code = 0; code < 128; code++) if (first.ascii[code] && second.ascii[code]) return false;
		return true;
	};
}

/** Whether a one-character expression may match a character past ASCII (true when not sure). */
function mayMatchPastAscii(source: string): boolean {
	if (source === "\\d" || source === "\\w") return false;
	if (source.length === 1) return source.charCodeAt(0) > 127 || source === ".";
	if (source.length === 2 && source[0] === "\\")
		return !/^\\[\x21-\x7e]$/.test(source) || "DWSsbBpPu".includes(source[1]);
	if (!source.startsWith("[") || source.startsWith("[^")) return true;
	// A class of ASCII characters, ranges between them, and the escapes \d, \w and of signs only.
	for (let i = 1; i < source.length - 1; i++) {
		const c = source.charCodeAt(i);
		if (c > 127) return true;
		if (source[i] === "\\") {
			const next = source[i + 1];
			if (/[A-Za-z0-9]/.test(next) && next !== "d" && next !== "w") return true;
			i++;
		}
	}
	return false;
}

/** The expressions of the characters `term` can match (`a`, `\w`, `[^"]`); undefined when it has a part that refers back. */
function characters(term: Term): string[] | undefined {
	switch (term.kind) {
		case "char":
			return [escapeChar(term.char)];
		case "set":
			return [term.source];
		case "assert":
			return [];
		case "look":
		case "backref":
			return undefined;
		case "repeat":
			return characters(term.term);
		case "group": {
			const all: string[] = [];
			for (const alternative of term.alternatives) {
				for (const part of alternative) {
					const inner = characters(part);
					if (!inner) return undefined;
					all.push(...inner);
				}
			}
			return all;
		}
	}
}

/** Whether `term` has no anchor, word boundary or lookaround in it. */
function isLocal(term: Term): boolean {
	switch (term.kind) {
		case "assert":
		case "look":
			return false;
		case "repeat":
			return isLocal(term.term);
		case "group":
			return term.alternatives.every((terms) => terms.every(isLocal));
		default:
			return true;
	}
}

/** The fewest and the most characters `term` matches. */
function width(term: Term): [number, number] {
	switch (term.kind) {
		case "char":
		case "set":
			return [1, 1];
		case "assert":
		case "look":
			return [0, 0];
		case "backref":
			return [0, Number.POSITIVE_INFINITY];
		case "repeat": {
			const [narrowest, widest] = width(term.term);
			return [narrowest * term.min, term.max === 0 || widest === 0 ? 0 : widest * term.max];
		}
		case "group": {
			let narrowest = Number.POSITIVE_INFINITY;
			let widest = 0;
			for (const alternative of term.alternatives) {
				let low = 0;
				let high = 0;
				for (const part of alternative) {
					const [a, b] = width(part);
					low += a;
					high += b;
				}
				narrowest = Math.min(narrowest, low);
				widest = Math.max(widest, high);
			}
			return [narrowest, widest];
		}
	}
}

const escapeChar = (char: string) => (/[\\^$.*+?()[\]{}|/-]/.test(char) ? `\\${char}` : char);

// --- the expression -----------------------------------------------------------------------------------------------------

/**
 * The parts of `source`, an expression `new RegExp` already took (JavaScript without the u flag, with the web's extensions:
 * a `{` that is not a count is a character). Undefined for what the bound does not follow (an inline flag group): the
 * expression is then never within the budget.
 */
function parse(source: string): Term[][] | undefined {
	const parser = { source, at: 0, namedGroups: /\(\?<[^=!]/.test(source) };
	try {
		const alternatives = parseAlternatives(parser);
		return parser.at === source.length ? alternatives : undefined;
	} catch {
		return undefined;
	}
}

interface Parser {
	source: string;
	at: number;
	namedGroups: boolean;
}

function parseAlternatives(parser: Parser): Term[][] {
	const alternatives: Term[][] = [[]];
	const { source } = parser;
	while (parser.at < source.length && source[parser.at] !== ")") {
		if (source[parser.at] === "|") {
			parser.at++;
			alternatives.push([]);
			continue;
		}
		alternatives[alternatives.length - 1].push(parseTerm(parser));
	}
	return alternatives;
}

const QUANTIFIER = /^\{(\d+)(?:(,)(\d*))?\}/;

function parseTerm(parser: Parser): Term {
	const atom = parseAtom(parser);
	const { source } = parser;
	let min: number;
	let max: number;
	const c = source[parser.at];
	if (c === "*" || c === "+" || c === "?") {
		min = c === "+" ? 1 : 0;
		max = c === "?" ? 1 : Number.POSITIVE_INFINITY;
		parser.at++;
	} else if (c === "{" && QUANTIFIER.test(source.slice(parser.at))) {
		const match = QUANTIFIER.exec(source.slice(parser.at)) as RegExpExecArray;
		min = Number(match[1]);
		max = match[2] ? (match[3] ? Number(match[3]) : Number.POSITIVE_INFINITY) : min;
		parser.at += match[0].length;
	} else {
		return atom;
	}
	if (source[parser.at] === "?") parser.at++; // (Lazy: the same ways, in another order.)
	if (atom.kind === "assert") throw new Error("a repeated assertion");
	return { kind: "repeat", term: atom, min, max };
}

function parseAtom(parser: Parser): Term {
	const { source } = parser;
	const c = source[parser.at];
	if (c === "^" || c === "$") {
		parser.at++;
		return { kind: "assert" };
	}
	if (c === ".") {
		parser.at++;
		return { kind: "set", source: "." };
	}
	if (c === "[") {
		const start = parser.at;
		let at = start + 1;
		while (at < source.length && source[at] !== "]") at += source[at] === "\\" ? 2 : 1;
		if (at >= source.length) throw new Error("an unclosed class");
		parser.at = at + 1;
		return { kind: "set", source: source.slice(start, parser.at) };
	}
	if (c === "(") return parseGroup(parser);
	if (c === "\\") return parseEscape(parser);
	parser.at++;
	return { kind: "char", char: c };
}

function parseGroup(parser: Parser): Term {
	const { source } = parser;
	const rest = source.slice(parser.at);
	let look = false;
	if (rest.startsWith("(?:")) parser.at += 3;
	else if (rest.startsWith("(?=") || rest.startsWith("(?!")) {
		parser.at += 3;
		look = true;
	} else if (rest.startsWith("(?<=") || rest.startsWith("(?<!")) {
		parser.at += 4;
		look = true;
	} else if (rest.startsWith("(?<")) {
		const close = source.indexOf(">", parser.at);
		if (close === -1) throw new Error("an unclosed group name");
		parser.at = close + 1;
	} else if (rest.startsWith("(?")) {
		throw new Error("an inline flag group");
	} else {
		parser.at++;
	}
	const alternatives = parseAlternatives(parser);
	if (source[parser.at] !== ")") throw new Error("an unclosed group");
	parser.at++;
	return look ? { kind: "look", alternatives } : { kind: "group", alternatives };
}

const CONTROL_ESCAPES: Record<string, string> = { t: "\t", n: "\n", r: "\r", f: "\f", v: "\v" };

function parseEscape(parser: Parser): Term {
	const { source } = parser;
	const next = source[parser.at + 1];
	if (next === undefined) throw new Error("a trailing backslash");
	parser.at += 2;
	if (next === "b" || next === "B") return { kind: "assert" };
	if ("dDwWsS".includes(next)) return { kind: "set", source: `\\${next}` };
	if (next >= "1" && next <= "9") {
		while (/[0-9]/.test(source[parser.at] ?? "")) parser.at++;
		return { kind: "backref" };
	}
	if (next === "k" && parser.namedGroups) {
		const close = source.indexOf(">", parser.at);
		if (source[parser.at] !== "<" || close === -1) throw new Error("a bad group reference");
		parser.at = close + 1;
		return { kind: "backref" };
	}
	if (next in CONTROL_ESCAPES) return { kind: "char", char: CONTROL_ESCAPES[next] };
	const hex = (digits: number) => {
		const text = source.slice(parser.at, parser.at + digits);
		if (text.length !== digits || !/^[0-9A-Fa-f]+$/.test(text)) return undefined;
		parser.at += digits;
		return String.fromCharCode(Number.parseInt(text, 16));
	};
	if (next === "x") return { kind: "char", char: hex(2) ?? "x" };
	if (next === "u") return { kind: "char", char: hex(4) ?? "u" };
	if (next === "c" && /[A-Za-z]/.test(source[parser.at] ?? "")) {
		return { kind: "char", char: String.fromCharCode(source.charCodeAt(parser.at++) % 32) };
	}
	if (next === "0") {
		const octal = /^[0-7]{0,2}/.exec(source.slice(parser.at))?.[0] ?? "";
		parser.at += octal.length;
		return { kind: "char", char: String.fromCharCode(Number.parseInt(`0${octal}`, 8)) };
	}
	// `\c` without a letter is a backslash, and the `c` a character of its own.
	if (next === "c") {
		parser.at--;
		return { kind: "char", char: "\\" };
	}
	// Another letter or sign stands for itself (`\.`, `\(`, `\p` without the u flag).
	return { kind: "char", char: next };
}
