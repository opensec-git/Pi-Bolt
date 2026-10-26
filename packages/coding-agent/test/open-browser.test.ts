import { afterEach, describe, expect, it, vi } from "vitest";

const spawn = vi.hoisted(() => vi.fn(() => ({ on: () => ({ unref: () => {} }) })));
vi.mock("node:child_process", () => ({ spawn }));

import { openBrowser } from "../src/utils/open-browser.ts";

// What a click on a link opens on macOS and Linux. A link the model wrote is shown with its text only, so its target can be
// anything: a path that `open` reads as an option (`-a`), a script in the project, an app, another program's URL scheme.
// (Not on Windows: there node:path and fileURLToPath() read paths as Windows paths.)
describe.skipIf(process.platform === "win32").each(["darwin", "linux"] as const)("openBrowser on %s", (platform) => {
	const platformDescriptor = Object.getOwnPropertyDescriptor(process, "platform")!;
	const opener = platform === "darwin" ? "open" : "xdg-open";

	function launched(target: string): string[] | undefined {
		Object.defineProperty(process, "platform", { ...platformDescriptor, value: platform });
		try {
			spawn.mockClear();
			openBrowser(target);
		} finally {
			Object.defineProperty(process, "platform", platformDescriptor);
		}
		const call = spawn.mock.calls[0] as unknown as [string, string[]] | undefined;
		return call && [call[0], ...call[1]];
	}

	afterEach(() => {
		Object.defineProperty(process, "platform", platformDescriptor);
	});

	it("opens web and mail links", () => {
		expect(launched("https://example.com/a?b=c&d")).toEqual([opener, "https://example.com/a?b=c&d"]);
		expect(launched("http://localhost:8080/")).toEqual([opener, "http://localhost:8080/"]);
		expect(launched("mailto:a@example.com")).toEqual([opener, "mailto:a@example.com"]);
	});

	it("shows a local file in the file manager instead of opening it", () => {
		const shown = (path: string) =>
			platform === "darwin" ? ["open", "-R", path] : ["xdg-open", path.slice(0, path.lastIndexOf("/")) || "/"];
		expect(launched("file:///System/Applications/Calculator.app")).toEqual(
			shown("/System/Applications/Calculator.app"),
		);
		expect(launched("file:///work/repo/scripts/setup.command")).toEqual(shown("/work/repo/scripts/setup.command"));
		expect(launched("file://localhost/work/a%20b/run.sh")).toEqual(shown("/work/a b/run.sh"));
		expect(launched("/work/repo/install.desktop")).toEqual(shown("/work/repo/install.desktop"));
	});

	it("opens nothing that could be read as an option, is relative, on another computer, or of another scheme", () => {
		for (const target of [
			"-aTerminal",
			"-a Terminal",
			"--args",
			"scripts/setup.command",
			"./run.sh",
			"file://attacker.example/share/x.app",
			"file:///work/a%2fb",
			"x-apple.systempreferences:com.apple.preference.security",
			"vscode://file/etc/passwd",
			"smb://attacker.example/share",
			"javascript:alert(1)",
			"ssh://host",
		]) {
			expect(launched(target), target).toBeUndefined();
		}
	});
});
