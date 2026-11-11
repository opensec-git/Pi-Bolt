import { Worker } from "node:worker_threads";
import { describe, expect, it } from "vitest";
import { INCREMENTAL_HIGHLIGHT_LANGUAGES } from "../src/utils/syntax-highlight.ts";
import type { WorkCheckInput, WorkCheckResult } from "./highlight-work-worker.ts";

// The work GrowingHighlighter does on adversarial texts made of each followed language's own delimiters
// (highlight-hang-cases.ts), against highlighting each text in full, counted (regular-expression calls and the characters
// handed to them), not timed: the result does not depend on the machine. It runs in a worker thread, stopped after
// HANG_LIMIT_MS: a kill switch for a call that never returns, not a measure.
//
// The heavy hang fuzz (all texts, thousands of characters, chunks of 1, 7 and 64, each text in a child process with a time
// limit, timed against a full highlight) is run by hand, not here:
//   node packages/coding-agent/test/highlight-hang-fuzz.ts [languages...]
// (highlight-hang-fuzz.ts's header has its options.)

const INPUT: WorkCheckInput = { languages: [...INCREMENTAL_HIGHLIGHT_LANGUAGES], size: 300, chunk: 13, stride: 3 };
/** Characters handed to expressions in one call, at most this multiple of a full highlight of its text's. */
const CALL_CHARACTERS = 6;
/** Expression calls in one call, at most this multiple of a full highlight's (one "hit end" attempt per position). */
const CALL_CALLS = 64;
/** Over all the calls of a language's texts, characters and calls, at most this multiple. */
const TOTAL = 1.5;
const HANG_LIMIT_MS = 120_000;

function runWorker(input: WorkCheckInput): Promise<WorkCheckResult[]> {
	return new Promise((resolve, reject) => {
		const worker = new Worker(new URL("./highlight-work-worker.ts", import.meta.url), { workerData: input });
		const timer = setTimeout(() => {
			void worker.terminate();
			reject(new Error(`no result after ${HANG_LIMIT_MS / 1000} s: a call does not return`));
		}, HANG_LIMIT_MS);
		worker.once("message", (results: WorkCheckResult[]) => {
			clearTimeout(timer);
			void worker.terminate();
			resolve(results);
		});
		worker.once("error", (error) => {
			clearTimeout(timer);
			reject(error);
		});
	});
}

describe("GrowingHighlighter: work on adversarial texts, against full highlights, counted", () => {
	it(
		"no call hands expressions much more than a full highlight does, and every result is the full highlight",
		async () => {
			const results = await runWorker(INPUT);
			expect(results.map((result) => result.language)).toEqual(INPUT.languages);
			for (const result of results) {
				expect(result.texts).toBeGreaterThan(10);
				expect({
					language: result.language,
					inexact: result.inexact,
					characters: result.worstRatio <= CALL_CHARACTERS,
					calls: result.worstCallsRatio <= CALL_CALLS,
					total: result.totalRatio <= TOTAL && result.totalCallsRatio <= TOTAL,
				}).toEqual({ language: result.language, inexact: [], characters: true, calls: true, total: true });
			}
		},
		HANG_LIMIT_MS + 10_000,
	);
});
