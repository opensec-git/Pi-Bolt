// Claim T5 (outside review): a running bash row starts a 1 s timer (toolTimerText()) that only a final result stops. A
// row dropped while its tool runs (the transcript rebuilt, or the run ended) never gets one, so the timer kept asking
// for a frame every second for the rest of the session. dispose() stops it, and a later render does not start it again.
import type { TUI } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test, vi } from "vitest";
import { createShellRenderers } from "../src/core/tools/renderers/bash.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

describe("claim T5: the elapsed timer of a running bash row", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	function runningRow(ui: TUI): ToolExecutionComponent {
		const row = new ToolExecutionComponent(
			"bash",
			"call-1",
			{ command: "sleep 60" },
			{},
			createShellRenderers("$"),
			ui,
			"/tmp",
		);
		row.markExecutionStarted();
		row.updateResult({ content: [{ type: "text", text: "partial" }], isError: false }, true);
		row.render(80);
		return row;
	}

	test("asks for a frame every second while the tool runs, and stops once the row is disposed", () => {
		vi.useFakeTimers();
		try {
			let frames = 0;
			const ui = { requestRender: () => frames++ } as unknown as TUI;
			const row = runningRow(ui);
			const started = frames;
			vi.advanceTimersByTime(2100);
			expect(frames - started).toBe(2);

			row.dispose();
			row.render(80);
			row.invalidate();
			row.render(120);
			const disposed = frames;
			vi.advanceTimersByTime(5000);
			expect(frames - disposed).toBe(0);
		} finally {
			vi.useRealTimers();
		}
	});
});
