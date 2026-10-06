import { appendFileSync } from "node:fs";

/**
 * Startup timing trace, for measuring time to interactive. Off unless `PI_TTI_TRACE` is set:
 * - `PI_TTI_TRACE=1` writes to stderr (redirect it, e.g. `2>tti.log`: on the terminal it would mix with the UI);
 * - `PI_TTI_TRACE=<path>` appends to that file.
 * Each event is one line, `pi-tti <ms> <event>[ <detail>]`, where `<ms>` is `performance.now()`: milliseconds
 * since the process started. The variable is read on the first event, not when the module loads, so a module
 * evaluated ahead of time (a prebuilt heap or snapshot) still follows the environment it runs in. Unset, an
 * event costs one comparison.
 */
let sink: ((line: string) => void) | null | undefined;

function openSink(): ((line: string) => void) | null {
	const target = process.env.PI_TTI_TRACE;
	if (!target || target === "0") return null;
	if (target === "1") return (line) => process.stderr.write(line);
	return (line) => {
		try {
			appendFileSync(target, line);
		} catch {
			// A trace must never break startup.
		}
	};
}

/** Whether `PI_TTI_TRACE` is set. */
export function isTtiTraceEnabled(): boolean {
	if (sink === undefined) sink = openSink();
	return sink !== null;
}

/** Record a startup event when `PI_TTI_TRACE` is set; does nothing otherwise. */
export function ttiTrace(event: string, detail?: string): void {
	if (sink === undefined) sink = openSink();
	if (sink === null) return;
	sink(`pi-tti ${performance.now().toFixed(1)} ${event}${detail ? ` ${detail}` : ""}\n`);
}

/** Forget the sink so the next event reads `PI_TTI_TRACE` again. For tests. */
export function resetTtiTrace(): void {
	sink = undefined;
}
