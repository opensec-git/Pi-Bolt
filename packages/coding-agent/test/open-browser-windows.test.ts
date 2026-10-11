import { describe, expect, it, vi } from "vitest";

const spawn = vi.hoisted(() => vi.fn(() => ({ on: () => ({ unref: () => {} }) })));
vi.mock("node:child_process", () => ({ spawn }));

import { openBrowser } from "../src/utils/open-browser.ts";

// What a click on a link opens on Windows: FileProtocolHandler runs a file it is given, and signs in to a share it names.
describe.skipIf(process.platform !== "win32")("openBrowser on Windows", () => {
	function launched(target: string): string[] | undefined {
		spawn.mockClear();
		openBrowser(target);
		const call = spawn.mock.calls[0] as unknown as [string, string[]] | undefined;
		return call && [call[0].split("\\").pop() as string, ...call[1]];
	}

	it("opens web and mail links", () => {
		expect(launched("https://example.com/a?b=c&d")).toEqual([
			"rundll32.exe",
			"url.dll,FileProtocolHandler",
			"https://example.com/a?b=c&d",
		]);
		expect(launched("mailto:a@example.com")).toEqual([
			"rundll32.exe",
			"url.dll,FileProtocolHandler",
			"mailto:a@example.com",
		]);
	});

	it("opens a local file that is only viewed, and shows any other in Explorer instead of running it", () => {
		expect(launched("file:///C:/work/shot.png")).toEqual([
			"rundll32.exe",
			"url.dll,FileProtocolHandler",
			"C:\\work\\shot.png",
		]);
		expect(launched("file:///C:/work/setup.bat")).toEqual(["explorer.exe", '/select,"C:\\work\\setup.bat"']);
		expect(launched("C:\\work dir\\x.ps1")).toEqual(["explorer.exe", '/select,"C:\\work dir\\x.ps1"']);
		expect(launched("file:///C:/Users/me/Desktop/app.lnk")).toEqual([
			"explorer.exe",
			'/select,"C:\\Users\\me\\Desktop\\app.lnk"',
		]);
	});

	it("opens nothing on another computer, of another scheme, or relative", () => {
		for (const target of [
			"file://attacker.example/share/x.png",
			"\\\\attacker.example\\share\\x.png",
			"ms-settings:privacy",
			"search-ms:query=x&crumb=location:\\\\attacker.example\\s",
			"javascript:alert(1)",
			"setup.bat",
		]) {
			expect(launched(target), target).toBeUndefined();
		}
	});
});
