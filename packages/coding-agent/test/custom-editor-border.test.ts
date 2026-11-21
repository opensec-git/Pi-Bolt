import { TuiMainScreen, visibleWidth } from "@earendil-works/pi-tui";
import { describe, expect, it } from "vitest";
import { defaultEditorTheme } from "../../tui/test/test-themes.ts";
import { VirtualTerminal } from "../../tui/test/virtual-terminal.ts";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import { CustomEditor } from "../src/modes/interactive/components/custom-editor.ts";
import { StatusIndicator } from "../src/modes/interactive/components/status-indicator.ts";

class BorderEditor extends CustomEditor {
	drawTopBorder(width: number, hiddenLineCount = 0): string {
		return this.renderTopBorder(width, hiddenLineCount);
	}
}

describe("CustomEditor status border", () => {
	it("uses the latest status and style with the same geometry", () => {
		const tui = new TuiMainScreen(new VirtualTerminal());
		const editor = new BorderEditor(tui, defaultEditorTheme, new KeybindingsManager(), { embedWorkingStatus: true });
		const indicator = new StatusIndicator(
			"working",
			tui,
			(text) => text,
			(text) => text,
			"",
			{ frames: [] },
		);
		let status = "wait";
		let color = "\x1b[31m";
		indicator.renderInBorder = () => status;
		editor.setWorkingStatusIndicator(indicator);
		editor.borderColor = (text) => `${color + text}\x1b[39m`;

		expect(editor.drawTopBorder(20)).toBe("\x1b[31m── \x1b[39mwait\x1b[31m ────────────\x1b[39m");
		status = "done";
		color = "\x1b[32m";
		expect(editor.drawTopBorder(20)).toBe("\x1b[32m── \x1b[39mdone\x1b[32m ────────────\x1b[39m");
		status = "done!";
		expect(editor.drawTopBorder(20)).toBe("\x1b[32m── \x1b[39mdone!\x1b[32m ───────────\x1b[39m");
		editor.setWorkingStatusIndicator(undefined);
		expect(editor.drawTopBorder(20)).toBe(`\x1b[32m${"─".repeat(20)}\x1b[39m`);
	});

	it("falls back to the current spinner when overflow takes up room", () => {
		const tui = new TuiMainScreen(new VirtualTerminal());
		const editor = new BorderEditor(tui, defaultEditorTheme, new KeybindingsManager(), { embedWorkingStatus: true });
		const indicator = new StatusIndicator(
			"working",
			tui,
			(text) => text,
			(text) => text,
			"",
			{ frames: [] },
		);
		indicator.renderInBorder = () => "waiting for upstream";
		let spinner = "s";
		indicator.renderSpinnerInBorder = () => spinner;
		editor.setWorkingStatusIndicator(indicator);
		editor.borderColor = (text) => text;

		const border = editor.drawTopBorder(40, 7);
		expect(border).toContain("── s ");
		expect(border).toContain(" ↑ 7 more ");
		expect(visibleWidth(border)).toBe(40);
		spinner = "t";
		expect(editor.drawTopBorder(8, 7)).toBe("── t ───");
		expect(editor.drawTopBorder(4)).toBe("───t");
	});
});
