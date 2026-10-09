import { appendFileSync } from "node:fs";

/**
 * Startup timing trace, for measuring time to interactive. Off unless `PI_TTI_TRACE` is set:
 * - `PI_TTI_TRACE=1` writes to stderr (redirect it, e.g. `2>tti.log`: on the terminal it would mix with the UI);
 * - `PI_TTI_TRACE=<path>` appends to that file.
 * Each event is one line, `pi-tti <ms> +<delta> <event>[ <detail>]`, where `<ms>` is `performance.now()`: milliseconds
 * since the runtime started, and `<delta>` the milliseconds since the previous event. The first line,
 * `pi-tti 0.0 +0.0 runtime.start <epoch ms>`, is when the runtime started on the wall clock (`performance.timeOrigin`):
 * what came before it (process creation, the loader, the runtime's own setup) a tool that knows when the process was
 * created (GetProcessTimes, /proc/<pid>/stat) can put in front. The variable is read on the first event, not when the
 * module loads, so a module evaluated ahead of time (a prebuilt heap or snapshot) still follows the environment it
 * runs in. Unset, an event costs one comparison.
 */
let sink: ((line: string) => void) | null | undefined;
let last = 0;

function openSink(): ((line: string) => void) | null {
	const target = process.env.PI_TTI_TRACE;
	if (!target || target === "0") return null;
	const write =
		target === "1"
			? (line: string) => process.stderr.write(line)
			: (line: string) => {
					try {
						appendFileSync(target, line);
					} catch {
						// A trace must never break startup.
					}
				};
	write(`pi-tti 0.0 +0.0 runtime.start ${performance.timeOrigin.toFixed(3)}\n`);
	last = 0;
	return write;
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
	const now = performance.now();
	sink(`pi-tti ${now.toFixed(1)} +${(now - last).toFixed(1)} ${event}${detail ? ` ${detail}` : ""}\n`);
	last = now;
}

/** Forget the sink so the next event reads `PI_TTI_TRACE` again. For tests. */
export function resetTtiTrace(): void {
	sink = undefined;
}
