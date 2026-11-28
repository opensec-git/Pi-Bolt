// The problems interactive mode shows after startup, a reload or a session change (settings errors, extension
// warnings) also reach the phones of the terminal's /remote-control, as extensions' notices do.
import { describe, expect, it, vi } from "vitest";
import type { AgentSessionRuntimeDiagnostic } from "../src/core/agent-session-services.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";

type ShowThis = {
	remoteControl: { notify: (message: string, type?: "info" | "warning" | "error") => void } | undefined;
	showWarning: (message: string) => void;
	showError: (message: string) => void;
	showStatus: (message: string) => void;
};

const showRuntimeDiagnostics = (
	InteractiveMode.prototype as unknown as {
		showRuntimeDiagnostics(this: ShowThis, diagnostics: readonly AgentSessionRuntimeDiagnostic[]): void;
	}
).showRuntimeDiagnostics;

describe("interactive mode's runtime diagnostics", () => {
	it("go to the terminal and to the phones of its remote control", () => {
		const notify = vi.fn();
		const mode: ShowThis = {
			remoteControl: { notify },
			showWarning: vi.fn(),
			showError: vi.fn(),
			showStatus: vi.fn(),
		};
		showRuntimeDiagnostics.call(mode, [
			{ type: "warning", message: "Invalid settings file /x/settings.json: bad" },
			{ type: "error", message: "Failed to load extension" },
		]);
		expect(mode.showWarning).toHaveBeenCalledWith("Invalid settings file /x/settings.json: bad");
		expect(mode.showError).toHaveBeenCalledWith("Failed to load extension");
		expect(notify.mock.calls).toEqual([
			["Invalid settings file /x/settings.json: bad", "warning"],
			["Failed to load extension", "error"],
		]);
	});

	it("go to the terminal alone without remote control", () => {
		const mode: ShowThis = {
			remoteControl: undefined,
			showWarning: vi.fn(),
			showError: vi.fn(),
			showStatus: vi.fn(),
		};
		showRuntimeDiagnostics.call(mode, [{ type: "info", message: "note" }]);
		expect(mode.showStatus).toHaveBeenCalledWith("note");
	});
});
