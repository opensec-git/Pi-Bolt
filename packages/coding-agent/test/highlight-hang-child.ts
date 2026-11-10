/**
 * One child of the hang fuzz (highlight-hang-fuzz.ts, run by hand), a process of its own so that a call that never returns
 * can be killed: streams the texts of hangCases(language, size) whose index is in `cases` into a GrowingHighlighter, `chunk`
 * characters at a time, timing every call, then highlights in full the texts of the slowest calls and of every
 * `sample`-th call (replaying a slow one to rule out the machine). Prints one JSON line per text and chunk size.
 * usage: node highlight-hang-child.ts <language> <size> <cases, comma separated> <chunks, comma separated> [sample]
 */

import { GrowingHighlighter, highlight, loadAllHighlightLanguages } from "../src/utils/syntax-highlight.ts";
import { hangCases } from "./highlight-hang-cases.ts";

const [language, sizeArg, casesArg, chunksArg, sampleArg] = process.argv.slice(2) as [
	string,
	string,
	string,
	string,
	string?,
];
const size = Number(sizeArg);
const sample = Number(sampleArg ?? 50);
await loadAllHighlightLanguages();
const all = hangCases(language, size);
const theme = {};

/** The fastest of three full highlights of `text`, in milliseconds, and its lines. */
function timeFull(text: string): { ms: number; lines: string } {
	let best = Number.POSITIVE_INFINITY;
	let lines = "";
	for (let i = 0; i < 3; i++) {
		const start = performance.now();
		lines = highlight(text, { language, ignoreIllegals: true, theme });
		best = Math.min(best, performance.now() - start);
	}
	return { ms: best, lines };
}

/**
 * Calls are compared with a full highlight of the same text only above this (milliseconds): below, timings are noise (a
 * first call compiles code).
 */
const FLOOR_MS = 0.5;
/** A stream that has taken this long (milliseconds) stops: the texts where highlight.js itself is slow take minutes. */
const BUDGET_MS = Number(process.env.HANG_BUDGET_MS ?? 20_000);

/** A call slower than this multiple of a full highlight is timed again (replay()). */
const CONFIRM_RATIO = 3;

/** The time of call `index` of a stream of `lengths` of `text`, made again on a new highlighter. */
function replay(text: string, lengths: number[], index: number): number {
	const highlighter = new GrowingHighlighter(language, theme);
	for (let i = 0; i < index; i++) highlighter.lines(text.slice(0, lengths[i]));
	const start = performance.now();
	highlighter.lines(text.slice(0, lengths[index]));
	return performance.now() - start;
}

// (Warm: the grammar compiled, its facts found, the code compiled by the engine.)
{
	const warm = all[all.length - 1]!.text.slice(0, 2000);
	const highlighter = new GrowingHighlighter(language, theme);
	for (let length = 64; length < warm.length; length += 64) highlighter.lines(warm.slice(0, length));
	highlight(warm, { language, ignoreIllegals: true, theme });
}

for (const index of casesArg.split(",").map(Number)) {
	const hangCase = all[index]!;
	for (const chunk of chunksArg.split(",").map(Number)) {
		const highlighter = new GrowingHighlighter(language, theme);
		const lengths: number[] = [];
		const times: number[] = [];
		const text = hangCase.text;
		let spent = 0;
		let truncated = false;
		for (let length = Math.min(chunk, text.length); ; length = Math.min(length + chunk, text.length)) {
			const start = performance.now();
			highlighter.lines(text.slice(0, length));
			const ms = performance.now() - start;
			times.push(ms);
			lengths.push(length);
			spent += ms;
			if (length === text.length) break;
			if (spent > BUDGET_MS) {
				truncated = true;
				break;
			}
		}
		// The calls compared with a full highlight: the slowest, every sample-th, and the last.
		const order = times.map((_, i) => i).sort((a, b) => times[b]! - times[a]!);
		const compared = new Set<number>([...order.slice(0, 8), lengths.length - 1]);
		for (let i = 0; i < lengths.length; i += sample) compared.add(i);
		let worst = { ratio: 0, incremental: 0, full: 0, length: 0 };
		let incrementalSampled = 0;
		let fullSampled = 0;
		for (const i of compared) {
			const full = timeFull(text.slice(0, lengths[i]));
			let ratio = Math.max(times[i]!, FLOOR_MS) / Math.max(full.ms, FLOOR_MS);
			if (ratio > CONFIRM_RATIO) {
				// (A slow call can be the machine's doing: the stream up to it again, twice, on a new highlighter.)
				times[i] = Math.min(times[i]!, replay(text, lengths, i), replay(text, lengths, i));
				ratio = Math.max(times[i]!, FLOOR_MS) / Math.max(full.ms, FLOOR_MS);
			}
			if (i % sample === 0) {
				incrementalSampled += times[i]!;
				fullSampled += full.ms;
			}
			if (ratio > worst.ratio) worst = { ratio, incremental: times[i]!, full: full.ms, length: lengths[i]! };
		}
		// Exactness of the last text streamed, after whatever the highlighter went through.
		const last = text.slice(0, lengths[lengths.length - 1]);
		const exact = highlighter.lines(last).join("\n") === highlight(last, { language, ignoreIllegals: true, theme });
		console.log(
			JSON.stringify({
				language,
				index,
				name: hangCase.name,
				length: text.length,
				chunk,
				calls: lengths.length,
				truncated,
				maxCallMs: Math.max(...times),
				totalMs: spent,
				sampledRatio: fullSampled > 0 ? incrementalSampled / fullSampled : 0,
				worst,
				fellBack: highlighter.highlightsInFull,
				exact,
			}),
		);
	}
}
