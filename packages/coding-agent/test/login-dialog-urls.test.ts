import type { TUI } from "@earendil-works/pi-tui";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { LoginDialogComponent } from "../src/modes/interactive/components/login-dialog.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";
import { stripAnsi } from "../src/utils/ansi.ts";
import { openBrowser } from "../src/utils/open-browser.ts";

vi.mock("../src/utils/clipboard.ts", () => ({ copyToClipboard: vi.fn() }));
vi.mock("../src/utils/open-browser.ts", () => ({ openBrowser: vi.fn() }));

const device = { userCode: "ABCD-EFGH", verificationUri: "https://example.com/device" };

function createDialog() {
	const onComplete = vi.fn();
	const dialog = new LoginDialogComponent({ requestRender: vi.fn() } as unknown as TUI, "custom-provider", onComplete);
	dialog.focused = true;
	return { dialog, onComplete };
}

function render(dialog: LoginDialogComponent): string {
	return stripAnsi(dialog.render(120).join("\n"));
}

describe("login URLs", () => {
	beforeAll(() => initTheme("dark"));
	beforeEach(() => {
		vi.resetAllMocks();
	});

	it("opens only http(s) sign-in URLs", () => {
		const { dialog } = createDialog();
		dialog.showAuth("file:///Users/victim/Downloads/payload.app");
		expect(openBrowser).not.toHaveBeenCalled();

		dialog.showAuth("https://example.com/authorize");
		expect(openBrowser).toHaveBeenCalledExactlyOnceWith("https://example.com/authorize");
	});

	it("keeps control characters in URLs from ending terminal hyperlinks", () => {
		const { dialog } = createDialog();
		dialog.showAuth("https://example.com/authorize\x07\x1b]52;c;Zm9v\x07");
		expect(dialog.render(120).join("\n")).not.toContain("\x1b]52");
		expect(render(dialog)).toContain("https://example.com/authorize%07%1B]52;c;Zm9v");

		dialog.showDeviceCode({ ...device, verificationUri: "https://example.com/device\x07\x1b]52;c;Zm9v\x07" });
		dialog.showInfo("Docs", [{ label: "Help", url: "https://example.com/help\x07\x1b]52;c;Zm9v\x07" }]);
		expect(dialog.render(120).join("\n")).not.toContain("\x1b]52");
	});

	it("removes control characters from URLs that are not web URLs", () => {
		const injected = "x\x07\x1b]52;c;Zm9v\x07";
		const { dialog } = createDialog();
		dialog.showAuth(`file:///tmp/${injected}`);
		expect(dialog.render(120).join("\n")).not.toContain("\x1b]52");

		dialog.showDeviceCode({ ...device, verificationUri: injected });
		expect(dialog.render(120).join("\n")).not.toContain("\x1b]52");

		dialog.showInfo("Docs", [{ label: "Help", url: injected }]);
		expect(dialog.render(120).join("\n")).not.toContain("\x1b]52");
		expect(render(dialog)).toContain("Help: x");
		expect(openBrowser).not.toHaveBeenCalled();
	});
});
