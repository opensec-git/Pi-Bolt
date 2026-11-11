/**
 * A static check that a regular expression cannot take more than linear time per attempt in a backtracking engine: what
 * growing-highlighter.test.ts requires of every expression the incremental highlighter derives (regex-reach.ts).
 *
 * The expression's consuming part is made a position automaton (Glushkov: one state per character class, a transition to
 * a state reads a character of its class), and the test looks for the two shapes that make a backtracking engine try more
 * than a linear number of ways when what follows fails:
 * - exponential: a state from which two different paths on the same text both come back to it (`(a|a)*`, `(a+)+`,
 *   `(\w+,?)*`: a repetition whose text can be divided into repetitions in more than one way);
 * - polynomial: two states p and q and a text that leads from p back to p, from p to q and from q back to q (`\s*\s*`,
 *   `a*[ab]*`: adjacent or nested unbounded repetitions over overlapping characters).
 * A back-reference inside a repetition is reported as such, except inside a lookahead (it then divides no text) to a group
 * captured outside the repetition. Lookaheads and assertions are taken to pass (the automaton has more paths than the
 * expression: anything ambiguous stays so), and each lookahead's body is checked as an expression of its own (it runs at
 * each place it is reached).
 */

import { parseRegex, type RegexNode } from "../src/utils/regex-reach.ts";

/** What was found: one description per shape, empty when the expression is linear. */
export function findBlowups(source: string, flags: string): string[] {
	const node = parseRegex(source);
	if (node === undefined) return [`not parsed: ${source}`];
	const found: string[] = [];
	const caseless = flags.includes("i");
	const bodies: RegexNode[] = [node];
	collectLookaheads(node, bodies);
	for (const body of bodies) {
		const automaton = new Automaton(body, caseless, groupsOf(node));
		found.push(...automaton.exponential(), ...automaton.polynomial());
	}
	found.push(...backreferencesInRepetitions(node));
	return found;
}

/** The characters the classes are compared on: every one an expression of a grammar is likely to name or exclude. */
const ALPHABET: string[] = (() => {
	const codes = new Set<number>();
	for (let code = 0; code <= 0x24f; code++) codes.add(code);
	for (const code of [
		0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff, 0x4e2d, 0x3042, 0xd83d, 0xde00, 0xfffd,
	]) {
		codes.add(code);
	}
	for (let code = 0x2000; code <= 0x200b; code++) codes.add(code);
	return [...codes].map((code) => String.fromCharCode(code));
})();

/** A set of characters of ALPHABET (bit i: ALPHABET[i]). */
type CharSet = Uint32Array;

const WORDS = Math.ceil(ALPHABET.length / 32);

function charSet(source: string, caseless: boolean): CharSet {
	const expression = new RegExp(`^(?:${source})$`, caseless ? "i" : "");
	const set = new Uint32Array(WORDS);
	for (let i = 0; i < ALPHABET.length; i++) {
		if (expression.test(ALPHABET[i]!)) set[i >>> 5]! |= 1 << (i & 31);
	}
	return set;
}

function intersects(...sets: CharSet[]): boolean {
	for (let word = 0; word < WORDS; word++) {
		let bits = 0xffffffff;
		for (const set of sets) bits &= set[word]!;
		if (bits !== 0) return true;
	}
	return false;
}

/** How many times a bounded repetition's body is written out at most. */
const MAX_COPIES = 64;

/** A position a part can start or end with, and what the character read next must be (an assertion between). */
interface Entry {
	position: number;
	only?: CharSet;
}

/** A part of the Glushkov automaton: whether it can match nothing (then what must come next), its first and last positions. */
interface Part {
	nullable: boolean;
	pass?: CharSet;
	first: Entry[];
	last: Entry[];
}

function meet(a: CharSet | undefined, b: CharSet | undefined): CharSet | undefined {
	if (a === undefined) return b;
	if (b === undefined) return a;
	const set = new Uint32Array(WORDS);
	for (let word = 0; word < WORDS; word++) set[word] = a[word]! & b[word]!;
	return set;
}

function join(a: CharSet | undefined, b: CharSet | undefined): CharSet | undefined {
	if (a === undefined || b === undefined) return undefined;
	const set = new Uint32Array(WORDS);
	for (let word = 0; word < WORDS; word++) set[word] = a[word]! | b[word]!;
	return set;
}

/** What `$` (the m flag) lets come next: a line terminator (or the end, where nothing is read). */
const LINE_END = charSet("[\\n\\r\\u2028\\u2029]", false);

const EMPTY: Part = { nullable: true, first: [], last: [] };

class Automaton {
	/** The class each position reads. */
	private readonly sets: CharSet[] = [];
	/** The source of each position (for the descriptions). */
	private readonly sources: string[] = [];
	/** The positions that can follow each position, and the characters read going there. */
	private readonly follow: Array<Map<number, CharSet>> = [];
	private readonly caseless: boolean;
	private readonly groups: Map<number, RegexNode>;
	/** Back-references being written out (a group that refers to itself is not written out again). */
	private readonly expanding = new Set<number>();

	constructor(node: RegexNode, caseless: boolean, groups: Map<number, RegexNode>) {
		this.caseless = caseless;
		this.groups = groups;
		this.build(node);
	}

	private position(source: string): Part {
		const index = this.sets.length;
		this.sets.push(charSet(source, this.caseless));
		this.sources.push(source);
		this.follow.push(new Map());
		return { nullable: false, first: [{ position: index }], last: [{ position: index }] };
	}

	private link(from: Entry[], to: Entry[]): void {
		for (const a of from) {
			for (const b of to) {
				const read = meet(this.sets[b.position], meet(a.only, b.only))!;
				const known = this.follow[a.position]!.get(b.position);
				this.follow[a.position]!.set(b.position, known === undefined ? read : join(known, read)!);
			}
		}
	}

	private concat(a: Part, b: Part): Part {
		this.link(a.last, b.first);
		return {
			nullable: a.nullable && b.nullable,
			pass: meet(a.pass, b.pass),
			first: a.nullable
				? [...a.first, ...b.first.map((entry) => ({ position: entry.position, only: meet(a.pass, entry.only) }))]
				: a.first,
			last: b.nullable
				? [...b.last, ...a.last.map((entry) => ({ position: entry.position, only: meet(entry.only, b.pass) }))]
				: b.last,
		};
	}

	private build(node: RegexNode): Part {
		switch (node.kind) {
			case "atom":
				return this.position(node.source);
			case "assertion":
				// (`^`, `\\b` and `\\B` also depend on the character before: taken to pass.)
				return node.source === "$" ? { nullable: true, pass: LINE_END, first: [], last: [] } : EMPTY;
			case "lookahead":
				// (A negative lookahead of nothing never passes: `(?!)`, what regex-reach writes for "nowhere".)
				if (node.negative && node.body.kind === "sequence" && node.body.items.length === 0) {
					return { nullable: false, first: [], last: [] };
				}
				return EMPTY;
			case "backreference": {
				const group = this.groups.get(node.group);
				if (group === undefined || this.expanding.has(node.group)) return EMPTY;
				this.expanding.add(node.group);
				const part = this.build(group);
				this.expanding.delete(node.group);
				return part;
			}
			case "group":
				return this.build(node.body);
			case "sequence": {
				let part: Part = EMPTY;
				for (const item of node.items) part = this.concat(part, this.build(item));
				return part;
			}
			case "alternation": {
				const part: Part = { nullable: false, first: [], last: [] };
				for (const branch of node.branches) {
					const next = this.build(branch);
					if (next.nullable) part.pass = part.nullable ? join(part.pass, next.pass) : next.pass;
					part.nullable ||= next.nullable;
					part.first.push(...next.first);
					part.last.push(...next.last);
				}
				return part;
			}
			case "repeat": {
				if (node.min > MAX_COPIES || (Number.isFinite(node.max) && node.max > MAX_COPIES)) {
					throw new Error(`repetition too long to write out: ${node.quantifier}`);
				}
				let part: Part = EMPTY;
				for (let i = 0; i < node.min; i++) part = this.concat(part, this.build(node.body));
				if (!Number.isFinite(node.max)) {
					const body = this.build(node.body);
					this.link(body.last, body.first);
					return this.concat(part, { nullable: true, first: body.first, last: body.last });
				}
				// x{0,3} as (x(x(x)?)?)?, not x?x?x? (which could divide a text into repetitions in many ways).
				let optional: Part | undefined;
				for (let i = node.min; i < node.max; i++) {
					const body = this.build(node.body);
					optional = optional === undefined ? body : this.concat(body, optional);
					optional = { nullable: true, first: optional.first, last: optional.last };
				}
				return optional === undefined ? part : this.concat(part, optional);
			}
		}
	}

	/** The strongly connected components of the positions (Tarjan), each as a set, by position. */
	private components(): Array<Set<number>> {
		const count = this.sets.length;
		const index = new Array<number>(count).fill(-1);
		const low = new Array<number>(count).fill(0);
		const onStack = new Array<boolean>(count).fill(false);
		const stack: number[] = [];
		const componentOf = new Array<Set<number>>(count);
		let next = 0;
		const visit = (v: number): void => {
			index[v] = low[v] = next++;
			stack.push(v);
			onStack[v] = true;
			for (const w of this.follow[v]!.keys()) {
				if (index[w] === -1) {
					visit(w);
					low[v] = Math.min(low[v]!, low[w]!);
				} else if (onStack[w]) {
					low[v] = Math.min(low[v]!, index[w]!);
				}
			}
			if (low[v] === index[v]) {
				const component = new Set<number>();
				let w: number;
				do {
					w = stack.pop()!;
					onStack[w] = false;
					component.add(w);
					componentOf[w] = component;
				} while (w !== v);
			}
		};
		for (let v = 0; v < count; v++) if (index[v] === -1) visit(v);
		return componentOf;
	}

	/** Positions in a cycle (a component with more than one position, or one that follows itself). */
	private looping(componentOf: Array<Set<number>>): number[] {
		const result: number[] = [];
		for (let v = 0; v < this.sets.length; v++) {
			if (componentOf[v]!.size > 1 || this.follow[v]!.has(v)) result.push(v);
		}
		return result;
	}

	/** Exponential: two different paths from a position back to it on the same text. */
	exponential(): string[] {
		const componentOf = this.components();
		const found: string[] = [];
		const done = new Set<Set<number>>();
		for (const p of this.looping(componentOf)) {
			const component = componentOf[p]!;
			if (done.has(component)) continue;
			done.add(component);
			// Pairs of positions of the component reached on the same text from a pair (q, q), and whether the two paths
			// have parted: a pair (r, r) reached after they parted means two paths from q to r on one text, and (r back to
			// q being a path in the component) two paths from q back to q.
			const members = [...component];
			const queue: Array<[number, number, boolean]> = [];
			for (const q of members) queue.push([q, q, false]);
			const seen = new Set<string>();
			while (queue.length > 0) {
				const [a, b, split] = queue.pop()!;
				for (const [c, readC] of this.follow[a]!) {
					if (!component.has(c)) continue;
					for (const [d, readD] of this.follow[b]!) {
						if (!component.has(d) || !intersects(readC, readD)) continue;
						const nowSplit = split || c !== d;
						if (nowSplit && c === d) {
							found.push(
								`exponential: two ways from ${this.describe(c)} back to it (repetition: ${members.map((m) => this.sources[m]).join(" ")})`,
							);
							return found;
						}
						const id = `${c},${d},${nowSplit}`;
						if (seen.has(id)) continue;
						seen.add(id);
						queue.push([c, d, nowSplit]);
					}
				}
			}
		}
		return found;
	}

	/** Polynomial: positions p and q (p before q) and a text that leads from p to p, from p to q and from q to q. */
	polynomial(): string[] {
		const componentOf = this.components();
		const looping = this.looping(componentOf);
		const count = this.sets.length;
		const reachable = (from: number): Set<number> => {
			const seen = new Set<number>();
			const stack = [from];
			while (stack.length > 0) {
				const v = stack.pop()!;
				for (const w of this.follow[v]!.keys()) {
					if (!seen.has(w)) {
						seen.add(w);
						stack.push(w);
					}
				}
			}
			return seen;
		};
		const reach = new Map<number, Set<number>>();
		for (let v = 0; v < count; v++) reach.set(v, reachable(v));
		for (const p of looping) {
			const loopP = componentOf[p]!;
			for (const q of looping) {
				if (q === p || componentOf[q] === loopP || !reach.get(p)!.has(q)) continue;
				const loopQ = componentOf[q]!;
				// The positions the middle path can go through: reached from p, and reaching q.
				const between = (v: number) => v === q || (reach.get(p)!.has(v) && reach.get(v)!.has(q));
				const start = `${p},${p},${q}`;
				const seen = new Set<string>([start]);
				const queue: Array<[number, number, number]> = [[p, p, q]];
				let found = false;
				while (queue.length > 0 && !found) {
					const [x, y, z] = queue.pop()!;
					for (const [x2, readX] of this.follow[x]!) {
						if (!loopP.has(x2)) continue;
						for (const [y2, readY] of this.follow[y]!) {
							if (!between(y2) || !intersects(readX, readY)) continue;
							for (const [z2, readZ] of this.follow[z]!) {
								if (!loopQ.has(z2) || !intersects(readX, readY, readZ)) continue;
								if (x2 === p && y2 === q && z2 === q) {
									found = true;
									break;
								}
								const id = `${x2},${y2},${z2}`;
								if (seen.has(id)) continue;
								seen.add(id);
								queue.push([x2, y2, z2]);
							}
							if (found) break;
						}
						if (found) break;
					}
				}
				if (found) {
					return [`polynomial: ${this.describe(p)} repeated, then ${this.describe(q)} repeated, on the same text`];
				}
			}
		}
		return [];
	}

	private describe(position: number): string {
		return `\`${this.sources[position]}\` (#${position})`;
	}
}

function groupsOf(node: RegexNode, groups = new Map<number, RegexNode>()): Map<number, RegexNode> {
	switch (node.kind) {
		case "group":
			if (node.capture > 0) groups.set(node.capture, node.body);
			groupsOf(node.body, groups);
			break;
		case "lookahead":
		case "repeat":
			groupsOf(node.body, groups);
			break;
		case "sequence":
			for (const item of node.items) groupsOf(item, groups);
			break;
		case "alternation":
			for (const branch of node.branches) groupsOf(branch, groups);
			break;
		default:
			break;
	}
	return groups;
}

function collectLookaheads(node: RegexNode, bodies: RegexNode[]): void {
	switch (node.kind) {
		case "lookahead":
			bodies.push(node.body);
			collectLookaheads(node.body, bodies);
			break;
		case "group":
		case "repeat":
			collectLookaheads(node.body, bodies);
			break;
		case "sequence":
			for (const item of node.items) collectLookaheads(item, bodies);
			break;
		case "alternation":
			for (const branch of node.branches) collectLookaheads(branch, bodies);
			break;
		default:
			break;
	}
}

/**
 * Back-references inside a repetition (of more than one), except in a lookahead to a group captured outside that
 * repetition: there it reads a text fixed before the repetition began, and divides none.
 */
function backreferencesInRepetitions(root: RegexNode): string[] {
	const found: string[] = [];
	const visit = (node: RegexNode, repeats: RegexNode[], inLookahead: boolean): void => {
		switch (node.kind) {
			case "backreference": {
				if (repeats.length === 0) return;
				const capturedInside = repeats.some((repeat) => capturesGroup(repeat, node.group));
				if (!inLookahead || capturedInside) {
					found.push(`back-reference \\${node.group} inside a repetition`);
				}
				return;
			}
			case "repeat":
				visit(node.body, node.max > 1 ? [...repeats, node] : repeats, inLookahead);
				return;
			case "lookahead":
				visit(node.body, repeats, true);
				return;
			case "group":
				visit(node.body, repeats, inLookahead);
				return;
			case "sequence":
				for (const item of node.items) visit(item, repeats, inLookahead);
				return;
			case "alternation":
				for (const branch of node.branches) visit(branch, repeats, inLookahead);
				return;
			default:
				return;
		}
	};
	visit(root, [], false);
	return found;
}

function capturesGroup(node: RegexNode, group: number): boolean {
	switch (node.kind) {
		case "group":
			return node.capture === group || capturesGroup(node.body, group);
		case "lookahead":
		case "repeat":
			return capturesGroup(node.body, group);
		case "sequence":
			return node.items.some((item) => capturesGroup(item, group));
		case "alternation":
			return node.branches.some((branch) => capturesGroup(branch, group));
		default:
			return false;
	}
}
