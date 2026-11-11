/**
 * The work check of growing-highlighter-work.test.ts, in a worker thread (so that a call that never returns can be
 * stopped): streams small adversarial texts (highlight-hang-cases.ts) into a GrowingHighlighter that follows texts of any
 * length, and counts, for each call and for a full highlight of the same text, the regular-expression calls made and the
 * characters handed to them (every call goes through RegExp.prototype.exec: `test`, `replace`, highlight.js's matchers).
 * Posts, per language, the largest ratio of the two and whether every result was the full highlight.
 */

import { parentPort, workerData } from "node:worker_threads";
import { GrowingHighlighter, highlight, loadAllHighlightLanguages } from "../src/utils/syntax-highlight.ts";
import { hangCases } from "./highlight-hang-cases.ts";

export interface WorkCheckInput {
	languages: string[];
	size: number;
	chunk: number;
	/** Every `stride`-th generated text, and every hand-written one. */
	stride: number;
}

export interface WorkCheckResult {
	language: string;
	texts: number;
	calls: number;
	/** The largest (characters handed in the call) / (characters handed in a full highlight of its text). */
	worstRatio: number;
	worstCase: string;
	/** The same for the regular-expression calls. */
	worstCallsRatio: number;
	/** Over all the calls of all the texts: characters, and calls. */
	totalRatio: number;
	totalCallsRatio: number;
	inexact: string[];
}

const input = workerData as WorkCheckInput;
const counted = { calls: 0, characters: 0 };
const exec = RegExp.prototype.exec;
RegExp.prototype.exec = function (this: RegExp, value: string): RegExpExecArray | null {
	const text = String(value);
	counted.calls++;
	counted.characters += text.length - (this.global || this.sticky ? Math.min(this.lastIndex, text.length) : 0);
	return exec.call(this, text);
};

function measure<T>(run: () => T): { value: T; calls: number; characters: number } {
	counted.calls = 0;
	counted.characters = 0;
	const value = run();
	return { value, calls: counted.calls, characters: counted.characters };
}

await loadAllHighlightLanguages();
const theme = {};
const results: WorkCheckResult[] = [];
for (const language of input.languages) {
	const result: WorkCheckResult = {
		language,
		texts: 0,
		calls: 0,
		worstRatio: 0,
		worstCase: "",
		worstCallsRatio: 0,
		totalRatio: 0,
		totalCallsRatio: 0,
		inexact: [],
	};
	// (Once per grammar: compiled, and its facts found, by a first text.)
	new GrowingHighlighter(language, theme, { minLength: 0 }).lines("x\ny\n");
	let incremental = 0;
	let full = 0;
	let incrementalCalls = 0;
	let fullCalls = 0;
	const cases = hangCases(language, input.size).filter(
		(hangCase, index) => hangCase.extra || index % input.stride === 0,
	);
	for (const hangCase of cases) {
		result.texts++;
		const highlighter = new GrowingHighlighter(language, theme, { minLength: 0 });
		const text = hangCase.text;
		// (highlight.js builds a matcher the first time a text needs it, then keeps it: built here, outside the counts.)
		for (let length = input.chunk; length < text.length + input.chunk; length += input.chunk) {
			highlight(text.slice(0, length), { language, ignoreIllegals: true, theme });
		}
		for (let length = Math.min(input.chunk, text.length); ; length = Math.min(length + input.chunk, text.length)) {
			const prefix = text.slice(0, length);
			const followed = measure(() => highlighter.lines(prefix).join("\n"));
			const whole = measure(() => highlight(prefix, { language, ignoreIllegals: true, theme }));
			result.calls++;
			incremental += followed.characters;
			full += whole.characters;
			incrementalCalls += followed.calls;
			fullCalls += whole.calls;
			// (A text of a few characters: a few calls more or less are not work. The floor is one line's worth.)
			const ratio = followed.characters / Math.max(whole.characters, 64);
			if (ratio > result.worstRatio) {
				result.worstRatio = ratio;
				result.worstCase = `${hangCase.name} at ${length}`;
			}
			result.worstCallsRatio = Math.max(result.worstCallsRatio, followed.calls / Math.max(whole.calls, 8));
			if (followed.value !== whole.value) result.inexact.push(`${hangCase.name} at ${length}`);
			if (length === text.length) break;
		}
	}
	result.totalRatio = incremental / Math.max(full, 1);
	result.totalCallsRatio = incrementalCalls / Math.max(fullCalls, 1);
	results.push(result);
}
RegExp.prototype.exec = exec;
parentPort?.postMessage(results);
