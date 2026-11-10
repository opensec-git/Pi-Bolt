import assert from "node:assert";
import { afterEach, describe, it } from "node:test";
import { Markdown } from "../src/components/markdown.ts";
import { resetCapabilitiesCache, setCapabilities } from "../src/terminal-image.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { getOsc8LinkAtColumn, localFileLinkTarget, stripTerminalSequences } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// Issue #297: a Markdown link that is not drawn as a hyperlink (OSC 8) shows where it goes after its text, so the URL is
// never dropped; a link to a local file is a hyperlink, drawn with its URL. The same in the regular and the fullscreen
// screen, with hyperlinks on and off.

const IMAGE = "file:///C:/Temp/example.png";

interface LinkCase {
	source: string;
	label: string;
	/** The text drawn with hyperlinks on and off. */
	on: string;
	off: string;
	/** What a click opens with hyperlinks on (none with them off). */
	target?: string;
	/** Where the URL is drawn, to click on. */
	url?: string;
}

const CASES: LinkCase[] = [
	{
		source: `[Open image](${IMAGE})`,
		label: "Open image",
		on: `Open image (${IMAGE})`,
		off: `Open image (${IMAGE})`,
		target: IMAGE,
		url: IMAGE,
	},
	{
		source: "[Open local](file://localhost/C:/Temp/example.png)",
		label: "Open local",
		on: "Open local (file://localhost/C:/Temp/example.png)",
		off: "Open local (file://localhost/C:/Temp/example.png)",
		target: IMAGE,
		url: "file://localhost/C:/Temp/example.png",
	},
	{
		source: "[Screenshot](file:///tmp/x.png)",
		label: "Screenshot",
		on: "Screenshot (file:///tmp/x.png)",
		off: "Screenshot (file:///tmp/x.png)",
		target: "file:///tmp/x.png",
		url: "file:///tmp/x.png",
	},
	{
		source: "[GitHub](https://github.com)",
		label: "GitHub",
		on: "GitHub",
		off: "GitHub (https://github.com)",
		target: "https://github.com",
	},
	{
		source: "[Settings](x-apple.systempreferences:com.apple.preference.security)",
		label: "Settings",
		on: "Settings (x-apple.systempreferences:com.apple.preference.security)",
		off: "Settings (x-apple.systempreferences:com.apple.preference.security)",
		url: "x-apple.systempreferences:com.apple.preference.security",
	},
	{
		source: "[Run it](javascript:alert(1))",
		label: "Run it",
		on: "Run it (javascript:alert(1))",
		off: "Run it (javascript:alert(1))",
		url: "javascript:alert(1)",
	},
	{
		source: "[Share](file://server/share/x.png)",
		label: "Share",
		on: "Share (file://server/share/x.png)",
		off: "Share (file://server/share/x.png)",
		url: "file://server/share/x.png",
	},
];

const DOCUMENT = CASES.map((c) => c.source).join("\n\n");

/** The OSC 8 target at the column where `text` begins on the line that has it, among `lines`. */
function linkAt(lines: readonly string[], text: string): string | undefined {
	const line = lines.find((l) => stripTerminalSequences(l).includes(text));
	assert.ok(
		line !== undefined,
		`${JSON.stringify(text)} not drawn: ${JSON.stringify(lines.map(stripTerminalSequences))}`,
	);
	return getOsc8LinkAtColumn(line, stripTerminalSequences(line).indexOf(text));
}

describe("Markdown links and where they go", () => {
	afterEach(() => resetCapabilitiesCache());

	for (const hyperlinks of [true, false]) {
		it(`draws each link's URL, and links what may be clicked (hyperlinks ${hyperlinks ? "on" : "off"})`, () => {
			setCapabilities({ images: null, trueColor: false, hyperlinks });
			for (const c of CASES) {
				const lines = new Markdown(c.source, 0, 0, defaultMarkdownTheme).render(100);
				assert.strictEqual(stripTerminalSequences(lines[0]!).trimEnd(), hyperlinks ? c.on : c.off, c.source);
				const target = hyperlinks ? c.target : undefined;
				assert.strictEqual(linkAt(lines, c.label), target, c.source);
				if (c.url !== undefined) assert.strictEqual(linkAt(lines, c.url), target, c.source);
			}
		});
	}

	it("draws a link with the same text when it is its own URL", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: true });
		const lines = new Markdown("<file:///tmp/x.png>", 0, 0, defaultMarkdownTheme).render(100);
		assert.strictEqual(stripTerminalSequences(lines[0]!).trimEnd(), "file:///tmp/x.png");
		assert.strictEqual(linkAt(lines, "file:///tmp/x.png"), "file:///tmp/x.png");
	});

	it("keeps a long local file link a hyperlink on every line it wraps to", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: true });
		const url = `file:///C:/Temp/${"deep/".repeat(30)}example.png`;
		const lines = new Markdown(`[Open image](${url})`, 0, 0, defaultMarkdownTheme).render(40);
		assert.ok(lines.length > 3, JSON.stringify(lines));
		assert.strictEqual(lines.map(stripTerminalSequences).join("").replace(/\s+/g, ""), `Openimage(${url})`);
		for (const line of lines) {
			const plain = stripTerminalSequences(line);
			assert.strictEqual(getOsc8LinkAtColumn(line, plain.search(/\S/)), url, plain);
			assert.strictEqual(getOsc8LinkAtColumn(line, plain.trimEnd().length - 1), url, plain);
		}
	});

	it("draws a link too long to be a hyperlink with its URL only", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: true });
		const url = `file:///tmp/${"a".repeat(2100)}.png`;
		const lines = new Markdown(`[Open image](${url})`, 0, 0, defaultMarkdownTheme).render(200);
		assert.strictEqual(lines.map(stripTerminalSequences).join("").replace(/\s+/g, ""), `Openimage(${url})`);
		assert.ok(!lines.some((line) => line.includes("\x1b]8;;f")), "no hyperlink");
	});
});

describe("localFileLinkTarget", () => {
	it("takes a file: URL of an absolute path on this computer, as the URL to link to", () => {
		const accepted: Array<[string, string]> = [
			[IMAGE, IMAGE],
			["file://localhost/C:/Temp/example.png", IMAGE],
			["file://LOCALHOST/tmp/x.png", "file:///tmp/x.png"],
			["FILE:///tmp/x.png", "file:///tmp/x.png"],
			["file:///tmp/x.png", "file:///tmp/x.png"],
			["file:///C:/My Docs/x.png", "file:///C:/My%20Docs/x.png"],
			["file:///C:\\Temp\\example.png", IMAGE],
			["file:///tmp/%C3%A9.png", "file:///tmp/%C3%A9.png"],
			["file:///tmp/caf\u00e9.png", "file:///tmp/caf%C3%A9.png"],
		];
		for (const [url, target] of accepted) assert.strictEqual(localFileLinkTarget(url), target, url);
	});

	it("takes nothing else", () => {
		for (const url of [
			// Another computer, or a share Windows would sign in to.
			"file://server/share/x.png",
			"file://attacker.example/x.app",
			"file://127.0.0.1/tmp/x.png",
			"file://localhost.attacker.example/tmp/x.png",
			"file:////server/share/x.png",
			"file:///\\\\server\\share\\x.png",
			"file://localhost//server/share/x.png",
			"file://\\\\server\\share\\x.png",
			// Without "//".
			"file:C:/Temp/x.png",
			"file:/tmp/x.png",
			"file:tmp/x.png",
			// Encoded separators and control characters, raw control and format characters.
			"file:///tmp/a%2fb.png",
			"file:///C:/a%5Cb.png",
			"file:///tmp/a%1bb.png",
			"file:///tmp/a%00b.png",
			"file:///tmp/a%7Fb.png",
			"file:///tmp/a%C2%9Bb.png",
			"file:///tmp/a\x07b.png",
			"file:///tmp/\u202egnp.exe",
			"file:///tmp/a\u200bb.png",
			// Too long for terminals to keep.
			`file:///tmp/${"a".repeat(2100)}.png`,
			// Not a file.
			"https://example.com/x.png",
			"javascript:alert(1)",
			"vscode://file/etc/passwd",
			"/tmp/x.png",
			"-aTerminal",
			"",
		]) {
			assert.strictEqual(localFileLinkTarget(url), undefined, JSON.stringify(url));
		}
	});
});

class RecordingTerminal extends VirtualTerminal {
	written = "";

	override write(data: string): void {
		this.written += data;
		super.write(data);
	}
}

describe("Markdown links on the screen", () => {
	afterEach(() => resetCapabilitiesCache());

	for (const hyperlinks of [true, false]) {
		it(`regular screen: shows each URL, and writes hyperlinks only for what may be clicked (hyperlinks ${hyperlinks ? "on" : "off"})`, async () => {
			setCapabilities({ images: null, trueColor: false, hyperlinks });
			const terminal = new RecordingTerminal(100, 30);
			const tui = new TuiMainScreen(terminal);
			tui.addChild(new Markdown(DOCUMENT, 0, 0, defaultMarkdownTheme));
			tui.start();
			try {
				await terminal.waitForRender();
				const viewport = terminal.getViewport().map((line) => line.trimEnd());
				for (const c of CASES) assert.ok(viewport.includes(hyperlinks ? c.on : c.off), c.source);
				const targets = new Set([...terminal.written.matchAll(/\x1b\]8;;([^\x07\x1b]+)/g)].map((m) => m[1]));
				const expected = hyperlinks ? CASES.flatMap((c) => (c.target === undefined ? [] : [c.target])) : [];
				assert.deepStrictEqual([...targets].sort(), [...new Set(expected)].sort());
			} finally {
				tui.stop();
			}
		});

		it(`fullscreen: a click opens a local file or web link, and nothing else (hyperlinks ${hyperlinks ? "on" : "off"})`, async () => {
			setCapabilities({ images: null, trueColor: false, hyperlinks });
			const terminal = new VirtualTerminal(100, 30);
			const opened: string[] = [];
			const tui = new TuiAltScreen(terminal, undefined, undefined, { openUrl: (url) => opened.push(url) });
			tui.addChild(new Markdown(DOCUMENT, 0, 0, defaultMarkdownTheme));
			tui.start();
			try {
				await terminal.waitForRender();
				const viewport = terminal.getViewport();
				const click = async (text: string): Promise<string | undefined> => {
					const row = viewport.findIndex((line) => line.includes(text));
					assert.notStrictEqual(row, -1, `${JSON.stringify(text)} not on the screen: ${JSON.stringify(viewport)}`);
					const column = viewport[row]!.indexOf(text) + 1;
					opened.length = 0;
					terminal.sendInput(`\x1b[<0;${column + 1};${row + 1}M`);
					terminal.sendInput(`\x1b[<0;${column + 1};${row + 1}m`);
					await terminal.waitForRender();
					return opened[0];
				};
				for (const c of CASES) {
					assert.ok(
						viewport.some((line) => line.trimEnd() === (hyperlinks ? c.on : c.off)),
						`${c.source}: ${JSON.stringify(viewport)}`,
					);
					const target = hyperlinks ? c.target : undefined;
					assert.strictEqual(await click(c.label), target, c.source);
					if (c.url !== undefined) assert.strictEqual(await click(c.url), target, c.source);
				}
			} finally {
				tui.stop();
			}
		});
	}
});
