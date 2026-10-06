import assert from "node:assert";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, it } from "node:test";
import type { Component, TUI } from "../src/index.ts";
import { TuiMainScreen } from "../src/index.ts";
import { isTtiTraceEnabled, resetTtiTrace, ttiTrace } from "../src/tti-trace.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

const DA1 = "\x1b[?62;22c";

class Line implements Component {
	render(_width: number): string[] {
		return ["hello"];
	}

	invalidate(): void {}
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** The events of a trace file, without their times. */
function events(file: string): string[] {
	return readFileSync(file, "utf-8")
		.trim()
		.split("\n")
		.map((line) => {
			const match = /^pi-tti \d+\.\d (.+)$/.exec(line);
			assert.ok(match, `unexpected trace line: ${line}`);
			return match[1];
		});
}

describe("PI_TTI_TRACE", () => {
	const previous = process.env.PI_TTI_TRACE;
	let directory: string | undefined;

	afterEach(() => {
		if (previous === undefined) delete process.env.PI_TTI_TRACE;
		else process.env.PI_TTI_TRACE = previous;
		resetTtiTrace();
		if (directory) rmSync(directory, { recursive: true, force: true });
		directory = undefined;
	});

	it("is off when unset or 0", () => {
		delete process.env.PI_TTI_TRACE;
		resetTtiTrace();
		assert.strictEqual(isTtiTraceEnabled(), false);
		process.env.PI_TTI_TRACE = "0";
		resetTtiTrace();
		assert.strictEqual(isTtiTraceEnabled(), false);
		// Read once: setting it later does not turn tracing on without a reset.
		process.env.PI_TTI_TRACE = "1";
		assert.strictEqual(isTtiTraceEnabled(), false);
	});

	it("records the color query, its reply, and the first frames to a file", async () => {
		directory = mkdtempSync(join(tmpdir(), "pi-tti-"));
		const file = join(directory, "trace.log");
		process.env.PI_TTI_TRACE = file;
		resetTtiTrace();

		const terminal = new VirtualTerminal();
		const tui: TUI = new TuiMainScreen(terminal);
		tui.addChild(new Line());
		tui.start();
		try {
			const query = tui.queryTerminalColors({ timeoutMs: 1000 });
			await terminal.waitForRender();
			terminal.sendInput("\x1b]11;#000000\x07");
			terminal.sendInput(DA1);
			await query;
			ttiTrace("custom", "detail");

			assert.deepStrictEqual(events(file), [
				"tui.start regular",
				"colors.query-sent",
				"frame.written 1",
				"colors.reply replies=1",
				"custom detail",
			]);
		} finally {
			tui.stop();
		}
	});

	it("records a timeout and the late reply", async () => {
		directory = mkdtempSync(join(tmpdir(), "pi-tti-"));
		const file = join(directory, "trace.log");
		process.env.PI_TTI_TRACE = file;
		resetTtiTrace();

		const terminal = new VirtualTerminal();
		const tui: TUI = new TuiMainScreen(terminal);
		tui.start();
		try {
			const late: unknown[] = [];
			const query = tui.queryTerminalColors({ timeoutMs: 1, onLateReply: (colors) => late.push(colors) });
			await wait(5);
			await query;
			terminal.sendInput(DA1);
			assert.strictEqual(late.length, 1);
			const recorded = events(file).filter((event) => event.startsWith("colors."));
			assert.deepStrictEqual(recorded, [
				"colors.query-sent",
				"colors.timeout 1ms replies=0",
				"colors.late-reply replies=0",
			]);
		} finally {
			tui.stop();
		}
	});
});
