// Preloaded into Node (NODE_OPTIONS=--require bench/gc_log.cjs) by bench/pauses.py --gc: writes the length of every garbage
// collection's pause to standard error, which the tool sends to a file, in the form JavaScriptCore's own GC log
// (BUN_JSC_logGC=1) has: "p=<ms>ms". Pi itself is not changed; the observer costs one short write per collection.
"use strict";
const { PerformanceObserver, constants } = require("node:perf_hooks");
const { writeSync } = require("node:fs");

const kinds = {
	[constants.NODE_PERFORMANCE_GC_MAJOR]: "major",
	[constants.NODE_PERFORMANCE_GC_MINOR]: "minor",
	[constants.NODE_PERFORMANCE_GC_INCREMENTAL]: "incremental",
	[constants.NODE_PERFORMANCE_GC_WEAKCB]: "weakcb",
};
new PerformanceObserver((list) => {
	let text = "";
	for (const entry of list.getEntries()) {
		text += `[GC node ${kinds[entry.detail?.kind] ?? "?"}: p=${entry.duration.toFixed(3)}ms]\n`;
	}
	try {
		writeSync(2, text);
	} catch {}
}).observe({ entryTypes: ["gc"] });
