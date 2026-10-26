import assert from "node:assert";
import { afterEach, describe, it } from "node:test";
import { Markdown } from "../src/components/markdown.ts";
import { ProcessTerminal } from "../src/terminal.ts";
import { resetCapabilitiesCache, setCapabilities } from "../src/terminal-image.ts";
import { getOsc8LinkAtColumn, sanitizeTerminalText } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

// Sequences a model's reply can carry that a terminal acts on: the clipboard (OSC 52), the window title (OSC 0/2), a
// hyperlink (OSC 8), the working directory (OSC 7), a DCS string, cursor movement and line clearing (CSI), C1 controls.
const ACTIVE_SEQUENCES = [
	"\x1b]52;c;ZWNobyBwd25lZA==\x07",
	"\x1b]0;SPOOFED-TITLE\x07",
	"\x1b]2;SPOOFED-TITLE\x1b\\",
	"\x1b]8;;file:///Applications/Calculator.app\x07",
	"\x1b]7;file:///tmp\x07",
	"\x1bP+q544e\x1b\\",
	"\x1b[2K",
	"\x1b[1G",
	"\x1b[3A",
	"\x1b[2J",
	"\x1b7",
	"\x9b2K",
	"\x9d0;SPOOFED\x07",
];

describe("sanitizeTerminalText", () => {
	it("removes escape sequences and control characters", () => {
		for (const sequence of ACTIVE_SEQUENCES) {
			const result = sanitizeTerminalText(`before${sequence}after`);
			assert.ok(
				!/[\x00-\x08\x0b-\x1f\x7f-\x9f]/.test(result),
				`${JSON.stringify(sequence)} -> ${JSON.stringify(result)}`,
			);
			assert.ok(result.startsWith("before") && result.endsWith("after"), JSON.stringify(result));
		}
		assert.strictEqual(sanitizeTerminalText("a\x1b[31mred\x1b[0m\x07\x7f\x00b"), "aredb");
		// An unfinished sequence loses its ESC; the rest is text.
		assert.strictEqual(sanitizeTerminalText("x\x1b]0;title"), "x]0;title");
		assert.strictEqual(sanitizeTerminalText("x\x1b[2"), "x[2");
	});

	it("keeps tabs and line breaks, and reads a carriage return alone as a line break", () => {
		const text = "line\tone\r\nline two\nend";
		assert.strictEqual(sanitizeTerminalText(text), text);
		assert.strictEqual(sanitizeTerminalText("safe\rFAKE"), "safe\nFAKE");
	});

	it("returns text without control characters as it is", () => {
		const text = "plain — ünïcödé 中文 🙂 text";
		assert.strictEqual(sanitizeTerminalText(text), text);
	});
});

describe("Markdown with terminal escape sequences in its text", () => {
	afterEach(() => resetCapabilitiesCache());

	it("draws them as nothing, with the text around them", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: false });
		for (const sequence of ACTIVE_SEQUENCES) {
			const markdown = new Markdown(`before${sequence}after`, 0, 0, defaultMarkdownTheme);
			const output = markdown.render(80).join("\n");
			assert.ok(!output.includes(sequence), JSON.stringify(output));
			assert.ok(!/\x1b(?!\[[0-9;]*m)|[\x00-\x08\x0b-\x1a\x1c-\x1f\x7f-\x9f]/.test(output), JSON.stringify(output));
			assert.ok(output.includes("before") && output.includes("after"), JSON.stringify(output));

			// The same once the text is set again (a reply streaming in).
			markdown.setText(`streamed ${sequence} text`);
			const streamed = markdown.render(80).join("\n");
			assert.ok(!streamed.includes(sequence), JSON.stringify(streamed));
		}
	});

	it("cannot draw over a line it does not own", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: false });
		const output = new Markdown("echo REAL > ran.txt #\x1b[2K\x1b[1G$ ls -la", 0, 0, defaultMarkdownTheme)
			.render(80)
			.join("\n");
		assert.ok(output.includes("echo REAL > ran.txt #$ ls -la"), JSON.stringify(output));
	});
});

describe("Markdown links drawn as terminal hyperlinks", () => {
	afterEach(() => resetCapabilitiesCache());

	function linkAt(source: string, text: string): string | undefined {
		const line = new Markdown(source, 0, 0, defaultMarkdownTheme).render(80)[0] ?? "";
		const plain = line.replace(/\x1b\[[0-9;]*m|\x1b\]8;[^\x07\x1b]*(?:\x07|\x1b\\)/g, "");
		return getOsc8LinkAtColumn(line, plain.indexOf(text));
	}

	it("makes web and mail links clickable", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: true });
		assert.strictEqual(linkAt("See [the docs](https://example.com/docs).", "docs"), "https://example.com/docs");
		assert.strictEqual(linkAt("Write to [us](mailto:team@example.com).", "us"), "mailto:team@example.com");
	});

	it("shows any other link as text only", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: true });
		for (const href of [
			"-aTerminal",
			"scripts/setup.command",
			"file:///System/Applications/Calculator.app",
			"/Applications/Calculator.app",
			"x-apple.systempreferences:com.apple.preference.security",
			"vscode://file/etc/passwd",
		]) {
			const source = `See [the release notes](${href}).`;
			assert.strictEqual(linkAt(source, "release"), undefined, href);
			const line = new Markdown(source, 0, 0, defaultMarkdownTheme).render(80)[0] ?? "";
			assert.ok(line.includes("the release notes"), JSON.stringify(line));
		}
	});
});

describe("the terminal title", () => {
	it("cannot end its sequence and start another", () => {
		const written: string[] = [];
		const write = process.stdout.write;
		process.stdout.write = ((chunk: string) => {
			written.push(chunk);
			return true;
		}) as typeof process.stdout.write;
		try {
			ProcessTerminal.prototype.setTitle.call(undefined, "π - name\x07\x1b]52;c;ZWNobyBwd25lZA==\x07 - repo");
		} finally {
			process.stdout.write = write;
		}
		assert.deepStrictEqual(written, ["\x1b]0;π - name]52;c;ZWNobyBwd25lZA== - repo\x07"]);
	});
});
