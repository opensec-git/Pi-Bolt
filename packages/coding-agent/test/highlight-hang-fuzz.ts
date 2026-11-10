/**
 * The hang fuzz of the incremental highlighter, run by hand (not part of the test run: it takes about an hour, and times
 * calls): every text of highlight-hang-cases.ts for each language followed (or those named), at (size, chunk) = (1000, 1),
 * (4000, 7) and (15000, 64) characters times --scale, streamed into a GrowingHighlighter in child processes
 * (highlight-hang-child.ts), each call timed against a full highlight of the same text. A child that reports nothing for
 * --timeout seconds (it reports each text when done) is killed; that text is counted as a hang and the others go on.
 *
 *   node packages/coding-agent/test/highlight-hang-fuzz.ts [--runtime <node or bun>] [--jobs 4] [--timeout 120]
 *     [--scale 1] [--batch 12] [--out results.jsonl] [languages...]
 *
 * Prints, per language, the worst call (its time against a full highlight's, floored at 0.5 ms) and the slowest call. Fails
 * (exit code 1) on a hang, an error, or a result that is not the full highlight. Pi runs on JavaScriptCore: run it with
 * `--runtime` a bun as well as with node, whose regular expressions backtrack differently.
 */

import { spawn } from "node:child_process";
import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { INCREMENTAL_HIGHLIGHT_LANGUAGES } from "../src/utils/syntax-highlight.ts";
import { hangCases } from "./highlight-hang-cases.ts";

interface Run {
	language: string;
	index: number;
	name?: string;
	chunk: number;
	size: number;
	calls?: number;
	maxCallMs?: number;
	worst?: { ratio: number; incremental: number; full: number; length: number };
	exact?: boolean;
	truncated?: boolean;
	timeout?: boolean;
	error?: string;
}

const args = process.argv.slice(2);
function option(name: string, fallback: string): string {
	const at = args.indexOf(`--${name}`);
	if (at === -1) return fallback;
	const value = args[at + 1]!;
	args.splice(at, 2);
	return value;
}
const runtime = option("runtime", process.execPath);
const jobsAtOnce = Number(option("jobs", "4"));
const timeoutMs = Number(option("timeout", "120")) * 1000;
const scale = Number(option("scale", "1"));
const batch = Number(option("batch", "12"));
const out = option("out", "");
const languages = args.length > 0 ? args : [...INCREMENTAL_HIGHLIGHT_LANGUAGES];
const CHILD = join(import.meta.dirname, "highlight-hang-child.ts");
const PASSES: Array<[number, number]> = [
	[Math.round(1000 * scale), 1],
	[Math.round(4000 * scale), 7],
	[Math.round(15000 * scale), 64],
];

/** Runs texts `indices` of one language, size and chunk, in children, until each is reported or counted as a hang. */
async function runBatch(language: string, size: number, chunk: number, indices: number[]): Promise<Run[]> {
	const runs: Run[] = [];
	let pending = indices;
	while (pending.length > 0) {
		const reported = await new Promise<{ runs: Run[]; hung: boolean; error: string }>((resolve) => {
			const child = spawn(runtime, [CHILD, language, String(size), pending.join(","), String(chunk)], {
				stdio: ["ignore", "pipe", "pipe"],
			});
			const done: Run[] = [];
			let buffer = "";
			let stderr = "";
			let hung = false;
			let timer: NodeJS.Timeout | undefined;
			const arm = () => {
				clearTimeout(timer);
				timer = setTimeout(() => {
					hung = true;
					child.kill("SIGKILL");
				}, timeoutMs);
			};
			arm();
			child.stdout.on("data", (data: Buffer) => {
				buffer += data;
				arm();
				let end = buffer.indexOf("\n");
				for (; end !== -1; end = buffer.indexOf("\n")) {
					const line = buffer.slice(0, end);
					buffer = buffer.slice(end + 1);
					if (line.startsWith("{")) done.push({ ...(JSON.parse(line) as Run), size });
				}
			});
			child.stderr.on("data", (data: Buffer) => {
				stderr += data;
			});
			child.on("close", (code) => {
				clearTimeout(timer);
				resolve({ runs: done, hung, error: code === 0 || hung ? "" : stderr.slice(-1500) });
			});
		});
		runs.push(...reported.runs);
		let next = reported.runs.length;
		if (next < pending.length && (reported.hung || reported.error)) {
			const index = pending[next]!;
			runs.push(
				reported.hung
					? { language, index, chunk, size, timeout: true }
					: { language, index, chunk, size, error: reported.error },
			);
			next++;
		}
		pending = pending.slice(next);
		if (next === 0) break;
	}
	if (out) for (const run of runs) appendFileSync(out, `${JSON.stringify(run)}\n`);
	return runs;
}

const jobs: Array<[string, number, number, number[]]> = [];
for (const language of languages) {
	const count = hangCases(language, 100).length;
	for (const [size, chunk] of PASSES) {
		for (let first = 0; first < count; first += batch) {
			jobs.push([
				language,
				size,
				chunk,
				Array.from({ length: Math.min(batch, count - first) }, (_, i) => first + i),
			]);
		}
	}
}
console.log(
	`${jobs.length} batches of up to ${batch} texts, ${jobsAtOnce} at a time, ${runtime}, passes ${JSON.stringify(PASSES)}`,
);
const started = Date.now();
const all: Run[] = [];
await Promise.all(
	Array.from({ length: jobsAtOnce }, async () => {
		for (let job = jobs.shift(); job !== undefined; job = jobs.shift()) all.push(...(await runBatch(...job)));
	}),
);
const ok = all.filter((run) => run.worst !== undefined);
const hangs = all.filter((run) => run.timeout);
const errors = all.filter((run) => run.error !== undefined);
const inexact = ok.filter((run) => !run.exact);
console.log(
	`${Math.round((Date.now() - started) / 1000)} s: ${ok.length} texts streamed, ${ok.reduce((sum, run) => sum + run.calls!, 0)} calls; ` +
		`${hangs.length} hangs, ${errors.length} errors, ${inexact.length} not the full highlight, ` +
		`${ok.filter((run) => run.truncated).length} stopped at the time budget`,
);
for (const language of languages) {
	const runs = ok.filter((run) => run.language === language);
	if (runs.length === 0) continue;
	const worst = runs.reduce((a, b) => (b.worst!.ratio > a.worst!.ratio ? b : a));
	const slowest = runs.reduce((a, b) => (b.maxCallMs! > a.maxCallMs! ? b : a));
	console.log(
		`${language}: ${runs.length} texts; worst call ${worst.worst!.ratio.toFixed(2)}x (${worst.worst!.incremental.toFixed(1)} ms ` +
			`against ${worst.worst!.full.toFixed(1)} ms, ${worst.name}, chunk ${worst.chunk}); slowest call ${slowest.maxCallMs!.toFixed(0)} ms ` +
			`(${slowest.name}, chunk ${slowest.chunk})`,
	);
}
for (const run of [...hangs, ...errors, ...inexact]) console.log("FAIL", JSON.stringify(run));
process.exitCode = hangs.length + errors.length + inexact.length > 0 ? 1 : 0;
