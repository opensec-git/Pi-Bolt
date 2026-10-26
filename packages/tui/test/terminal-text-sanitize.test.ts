import assert from "node:assert";
import { afterEach, describe, it } from "node:test";
import { Markdown } from "../src/components/markdown.ts";
import { ProcessTerminal } from "../src/terminal.ts";
import { resetCapabilitiesCache, setCapabilities } from "../src/terminal-image.ts";
import {
	getOsc8LinkAtColumn,
	type SanitizedTerminalText,
	sanitizeTerminalText,
	sanitizeTerminalTextFrom,
} from "../src/utils.ts";
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

/** A deterministic random number generator (mulberry32), so that a failure can be replayed. */
function random(seed: number): () => number {
	let state = seed >>> 0;
	return () => {
		state = (state + 0x6d2b79f5) >>> 0;
		let t = state;
		t = Math.imul(t ^ (t >>> 15), t | 1);
		t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

// Pieces of text that make escape sequences (complete, cut short, nested) when strung together at random.
const PIECES = [
	"\x1b",
	"\x1b[",
	"\x1b]",
	"\x1bP",
	"\x1b\\",
	"[",
	"]",
	"\\",
	"0;",
	"52;c;",
	"31",
	";",
	"m",
	"K",
	" ",
	"(",
	"B",
	"\x07",
	"\x9c",
	"\x9b",
	"\x9d",
	"\r",
	"\n",
	"\r\n",
	"\t",
	"\x00",
	"\x7f",
	"\x85",
	"text",
	"a",
	"é",
	"中",
	"🙂",
];

function randomText(next: () => number, pieces: number): string {
	let text = "";
	for (let i = 0; i < pieces; i++) {
		text += next() < 0.5 ? PIECES[Math.floor(next() * PIECES.length)] : "plain words ";
	}
	return text;
}

describe("sanitizeTerminalTextFrom", () => {
	it("gives what sanitizeTerminalText() gives for every chunk of a text streaming in", () => {
		for (let seed = 1; seed <= 400; seed++) {
			const next = random(seed);
			const full = randomText(next, 20 + Math.floor(next() * 120));
			let previous: SanitizedTerminalText | undefined;
			let end = 0;
			while (end < full.length) {
				// Chunks of 0 to 12 code units: sequences, surrogate pairs and CR LF are split at any point.
				end = Math.min(full.length, end + Math.floor(next() * 13));
				const text = full.slice(0, end);
				const before = previous;
				previous = sanitizeTerminalTextFrom(text, previous);
				assert.strictEqual(previous.raw, text);
				// (`added`, when set, is what the text adds to the one before.)
				if (previous.added !== undefined) assert.strictEqual((before?.text ?? "") + previous.added, previous.text);
				assert.strictEqual(
					previous.text,
					sanitizeTerminalText(text),
					`seed ${seed}, ${JSON.stringify(text)} after ${JSON.stringify(full.slice(0, end))}`,
				);
			}
		}
	});

	it("gives what sanitizeTerminalText() gives for a text that does not begin with the one before", () => {
		for (let seed = 1; seed <= 200; seed++) {
			const next = random(seed);
			let previous: SanitizedTerminalText | undefined;
			for (let step = 0; step < 20; step++) {
				const last = previous?.raw ?? "";
				// Longer, shorter, or another text.
				const choice = next();
				const text =
					choice < 0.6
						? last + randomText(next, Math.floor(next() * 4))
						: choice < 0.8
							? last.slice(0, Math.floor(next() * last.length))
							: randomText(next, Math.floor(next() * 30));
				const before = previous;
				previous = sanitizeTerminalTextFrom(text, previous);
				assert.strictEqual(previous.text, sanitizeTerminalText(text), `seed ${seed}, ${JSON.stringify(text)}`);
				if (previous.added !== undefined) assert.strictEqual((before?.text ?? "") + previous.added, previous.text);
			}
		}
	});

	it("returns text without control characters as it is", () => {
		const first = sanitizeTerminalTextFrom("plain ");
		const second = sanitizeTerminalTextFrom("plain text", first);
		assert.strictEqual(second.text, "plain text");
		assert.ok(second.unchanged);
		assert.strictEqual(second.added, "text");
		const same = sanitizeTerminalTextFrom("plain text", second);
		assert.strictEqual(same.text, "plain text");
		assert.strictEqual(same.added, "");
	});

	it("goes through a sequence again once more text completes it", () => {
		const cut = sanitizeTerminalTextFrom("a\x1b]0;tit");
		assert.strictEqual(cut.text, "a]0;tit");
		assert.strictEqual(sanitizeTerminalTextFrom("a\x1b]0;title\x1b", cut).text, "a]0;title");
		assert.strictEqual(sanitizeTerminalTextFrom("a\x1b]0;title\x1b\\b", cut).text, "ab");
		const cr = sanitizeTerminalTextFrom("line\r");
		assert.strictEqual(cr.text, "line\n");
		assert.strictEqual(sanitizeTerminalTextFrom("line\r\nnext", cr).text, "line\r\nnext");
	});
});

describe("Markdown streaming in text with escape sequences", () => {
	afterEach(() => resetCapabilitiesCache());

	it("renders each chunk as a Markdown made from that text", () => {
		setCapabilities({ images: null, trueColor: false, hyperlinks: false });
		for (let seed = 1; seed <= 40; seed++) {
			const next = random(seed);
			const full = randomText(next, 60);
			const streamed = new Markdown("", 0, 0, defaultMarkdownTheme);
			let end = 0;
			while (end < full.length) {
				end = Math.min(full.length, end + 1 + Math.floor(next() * 12));
				const text = full.slice(0, end);
				streamed.setText(text);
				assert.deepStrictEqual(
					streamed.render(60),
					new Markdown(text, 0, 0, defaultMarkdownTheme).render(60),
					`seed ${seed}, ${JSON.stringify(text)}`,
				);
			}
		}
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
