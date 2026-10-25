import assert from "node:assert";
import { describe, it } from "node:test";
import { Box } from "../src/components/box.ts";
import { Markdown, type MarkdownTheme, setMarkdownCachingForTest } from "../src/components/markdown.ts";
import { type LiveLines, renderLiveLines } from "../src/tui.ts";
import { applyBackgroundAround, getActiveBackgroundAnsi, visibleWidth } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

// Shaded code blocks (MarkdownTheme.codeBlockBg): each code line is drawn on the code block's background to the full width,
// without fence lines, and wrapped lines keep the block's indent. A reply streams into one Markdown component, which
// keeps what it rendered (blocks, wraps, inline tokens, live lines) from one chunk to the next; whatever happens in between
// (a chunk, a resize, a theme change), what it shows must be what the same text renders to from nothing.

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

/** The colors the theme's functions read when called, as Pi's theme does: a theme change changes them in place. */
const palette = { fg: "\x1b[38;5;114m", bg: "\x1b[48;5;236m", outer: "\x1b[48;5;24m" };
const PALETTES = [
	{ fg: "\x1b[38;5;114m", bg: "\x1b[48;5;236m", outer: "\x1b[48;5;24m" },
	{ fg: "\x1b[38;2;80;90;100m", bg: "\x1b[48;2;235;235;240m", outer: "\x1b[48;2;220;225;250m" },
	{ fg: "\x1b[38;5;208m", bg: "\x1b[48;5;17m", outer: "\x1b[48;5;53m" },
];

const shadedTheme: MarkdownTheme = {
	...defaultMarkdownTheme,
	codeBlock: (text) => `${palette.fg}${text}\x1b[39m`,
	codeBlockBorder: (text) => `\x1b[2m${text}\x1b[22m`,
	codeBlockBg: (text) => `${palette.bg}${text}\x1b[49m`,
};

/** The same, with code highlighted into many colored spans per line as Pi's is. */
const highlightingTheme: MarkdownTheme = {
	...shadedTheme,
	highlightCode: (code) =>
		code.split("\n").map((line) => line.replace(/\w+|[^\w\s]+/g, (word) => `${palette.fg}\x1b[1m${word}\x1b[22;39m`)),
};

const outerBg = (text: string) => `${palette.outer}${text}\x1b[49m`;

const PIECES = [
	"```ts\nconst {w} = 1; // a comment that is long enough to be wrapped at the narrower widths\nlet b = `x`;\n```\n",
	"```\nplain {w}\n\n   indented after an empty line\n```\n",
	"```python\ndef f():\n\treturn {w}  # tab inside\n```\n",
	"```\nunclosed {w}\n",
	"```sh\necho {w} ",
	"\n``",
	"`\n",
	"Text {w} with `inline code` and **bold**.\n",
	"A long line of text {w} that has to be wrapped at the narrower widths this test renders the reply at.\n",
	"- item {w}\n\n  ```js\n  nested({w});\n  ```\n",
	"> quoted\n> ```\n> code {w}\n> ```\n",
	"\n",
	"\n\n",
	"# Heading {w}\n",
	"日本語のコード {w} 😀\n",
];

function randomDocument(next: () => number): string {
	let text = "";
	const pieces = 6 + Math.floor(next() * 20);
	for (let i = 0; i < pieces; i++) {
		text += PIECES[Math.floor(next() * PIECES.length)]!.replaceAll("{w}", String(Math.floor(next() * 50)));
	}
	return text;
}

/** What `text` renders to with nothing kept. */
function fresh(text: string, width: number, theme: MarkdownTheme, withBg: boolean): string[] {
	setMarkdownCachingForTest(false);
	try {
		return new Markdown(text, 1, 0, theme, withBg ? { bgColor: outerBg } : undefined).render(width);
	} finally {
		setMarkdownCachingForTest(true);
	}
}

/** A reader of LiveLines, as a container reads them: keeps a copy, brought up to date from changedSince(). */
class Follower {
	lines: string[] = [];
	private source?: LiveLines;
	private revision = -1;

	read(live: LiveLines): string[] {
		const from = live === this.source ? live.changedSince(this.revision) : 0;
		if (from !== Number.POSITIVE_INFINITY) {
			this.lines.length = Math.min(this.lines.length, from);
			for (let i = from; i < live.lines.length; i++) this.lines.push(live.lines[i]!);
		}
		this.source = live;
		this.revision = live.revision;
		return this.lines;
	}
}

describe("Shaded code blocks", () => {
	for (let seed = 1; seed <= 24; seed++) {
		it(`random reply ${seed}: streamed, resized and re-themed, renders as from nothing`, () => {
			const next = random(seed * 104729);
			Object.assign(palette, PALETTES[0]);
			const text = randomDocument(next);
			const theme = next() < 0.5 ? highlightingTheme : shadedTheme;
			const withBg = next() < 0.4;
			const component = new Markdown("", 1, 0, theme, withBg ? { bgColor: outerBg } : undefined);
			const follower = new Follower();
			let width = [24, 47, 100][Math.floor(next() * 3)]!;
			let length = 0;
			let step = 0;
			while (length < text.length) {
				const roll = next();
				if (roll < 0.15) {
					width = [12, 24, 33, 47, 80, 100][Math.floor(next() * 6)]!;
				} else if (roll < 0.25) {
					// A theme change: the theme's functions now give other colors, and the TUI invalidates everything.
					Object.assign(palette, PALETTES[Math.floor(next() * PALETTES.length)]);
					component.invalidate();
				} else {
					length = Math.min(text.length, length + 1 + Math.floor(next() * (next() < 0.5 ? 6 : 40)));
					component.setText(text.slice(0, length));
				}
				const expected = fresh(text.slice(0, length), width, theme, withBg);
				const label = `seed ${seed}, step ${step++}, width ${width}, length ${length}`;
				if (step % 2 === 0) {
					assert.deepStrictEqual(component.render(width), expected, label);
				} else {
					const live = renderLiveLines(component, width);
					assert.ok(live, "Markdown renders live lines");
					assert.deepStrictEqual(follower.read(live), expected, `${label} (live lines)`);
				}
			}
		});
	}

	it("draws no fence lines and shades each line to the full width", () => {
		Object.assign(palette, PALETTES[0]);
		const source = "```ts\nconst a = 1;\n\n    nested();\n```\n\nafter";
		const lines = new Markdown(source, 0, 0, shadedTheme).render(30);
		assert.ok(!lines.some((line) => line.includes("```")));
		const code = lines.filter((line) => line.includes(palette.bg));
		assert.strictEqual(code.length, 4); // the language and three code lines
		for (const line of code) {
			const end = line.indexOf("\x1b[49m");
			assert.ok(end > 0);
			// The shading covers the whole width, padding included, and nothing comes after it.
			assert.strictEqual(visibleWidth(line.slice(0, end)), 30);
			assert.strictEqual(line.slice(end + "\x1b[49m".length), "");
			assert.strictEqual(getActiveBackgroundAnsi(line), "");
		}
		assert.strictEqual(visibleWidth(code[2]!.slice(0, code[2]!.indexOf("\x1b[49m"))), 30); // the empty line too
	});

	it("keeps the shading after a full reset from the highlighter", () => {
		Object.assign(palette, PALETTES[0]);
		const theme: MarkdownTheme = {
			...shadedTheme,
			highlightCode: (code) => code.split("\n").map((line) => line.replace("const", "\x1b[1mconst\x1b[0m")),
		};
		const [line] = new Markdown("```\nconst a = 1;\n```", 0, 0, theme)
			.render(30)
			.filter((rendered) => rendered.includes("const"));
		// After the reset, the code block's background is open again until the end of the code.
		const afterReset = line!.slice(line!.indexOf("\x1b[0m") + "\x1b[0m".length);
		assert.strictEqual(getActiveBackgroundAnsi(afterReset.slice(0, afterReset.indexOf(" a = 1;"))), palette.bg);
	});

	it("keeps the shading after a background span from the highlighter", () => {
		Object.assign(palette, PALETTES[0]);
		const theme: MarkdownTheme = {
			...shadedTheme,
			highlightCode: (code) =>
				code.split("\n").map((line) => line.replace("const", "\x1b[48;2;1;2;3mconst\x1b[49m")),
		};
		const [line] = new Markdown("```\nconst a = 1;\n```", 0, 0, theme)
			.render(30)
			.filter((rendered) => rendered.includes("const"));
		const afterSpan = line!.slice(line!.indexOf("const\x1b[49m") + "const\x1b[49m".length);
		assert.strictEqual(getActiveBackgroundAnsi(afterSpan.slice(0, afterSpan.indexOf(" a = 1;"))), palette.bg);
	});

	it("keeps the shading after compound resets from the highlighter, but not over a span's own background", () => {
		Object.assign(palette, PALETTES[0]);
		const render = (styled: string) => {
			const theme: MarkdownTheme = {
				...shadedTheme,
				highlightCode: (code) => code.split("\n").map((line) => line.replace("const", styled)),
			};
			const [line] = new Markdown("```\nconst a = 1;\n```", 0, 0, theme)
				.render(30)
				.filter((rendered) => rendered.includes(" a = 1;"));
			return getActiveBackgroundAnsi(line!.slice(0, line!.indexOf(" a = 1;")));
		};
		assert.strictEqual(render("\x1b[1mconst\x1b[0;31m"), palette.bg);
		assert.strictEqual(render("\x1b[48;2;1;2;3mconst\x1b[39;49m"), palette.bg);
		// (A reset followed by a background of the span's own: that background stays.)
		assert.strictEqual(render("const\x1b[0;48;2;1;2;3m"), "\x1b[48;2;1;2;3m");
	});

	it("keeps the indent on wrapped lines", () => {
		Object.assign(palette, PALETTES[0]);
		const lines = new Markdown("```\nconst value = compute(first, second);\n```", 0, 0, shadedTheme)
			.render(16)
			.map((line) => line.replace(/\x1b\[[0-9;]*m/g, "").trimEnd());
		assert.deepStrictEqual(lines, ["  const value =", "  compute(first,", "  second);"]);
	});

	it("keeps the surrounding background after the shading, in a Markdown with a background and in a Box", () => {
		Object.assign(palette, PALETTES[0]);
		const source = "Before\n\n```\nshort\n```\n\nAfter";
		const own = new Markdown(source, 1, 0, shadedTheme, { bgColor: outerBg }).render(30);
		const box = new Box(1, 0, outerBg);
		box.addChild(new Markdown(source, 0, 0, shadedTheme));
		for (const lines of [own, box.render(30)]) {
			const shaded = lines.filter((line) => line.includes(palette.bg));
			assert.strictEqual(shaded.length, 1);
			const line = shaded[0]!;
			const afterText = line.indexOf("short") + "short".length;
			// The code's background over its text and the padding after it; the surrounding one around the block.
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, afterText)), palette.bg);
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, afterText + 5)), palette.bg);
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, line.lastIndexOf("\x1b[49m"))), palette.outer);
			assert.strictEqual(visibleWidth(line), 30);
		}
	});

	it("keeps a code block on the terminal's own background inside a panel", () => {
		// (A theme that sets the code block background to "": the terminal's default, SGR 49, opens and closes it.)
		const terminalBg: MarkdownTheme = { ...shadedTheme, codeBlockBg: (text) => `\x1b[49m${text}\x1b[49m` };
		const source = "```\nshort\n```";
		const own = new Markdown(source, 1, 0, terminalBg, { bgColor: outerBg }).render(30);
		const box = new Box(1, 0, outerBg);
		box.addChild(new Markdown(source, 0, 0, terminalBg));
		for (const lines of [own, box.render(30)]) {
			const line = lines.find((candidate) => candidate.includes("short"))!;
			const afterText = line.indexOf("short") + "short".length;
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, afterText)), "");
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, line.lastIndexOf("\x1b[49m"))), palette.outer);
		}
	});

	it("keeps a code block on the terminal's own background inside a panel after a reset from the highlighter", () => {
		const terminalBg: MarkdownTheme = {
			...shadedTheme,
			codeBlockBg: (text) => `\x1b[49m${text}\x1b[49m`,
			highlightCode: (code) => code.split("\n").map((line) => line.replace("short", "\x1b[1mshort\x1b[0m")),
		};
		const source = "```\nshort and long\n```";
		const box = new Box(1, 0, outerBg);
		box.addChild(new Markdown(source, 0, 0, terminalBg));
		const line = box.render(30).find((candidate) => candidate.includes("short"))!;
		const afterText = line.indexOf(" and long") + " and long".length;
		assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, afterText)), "");
		assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, line.lastIndexOf("\x1b[49m"))), palette.outer);
	});

	it("tells foreground colors from background codes", () => {
		// 38;5;48 and 38;2;40;41;100 are foreground colors: the reset after them opens a part on the terminal's background.
		for (const fg of ["\x1b[38;5;48m", "\x1b[38;2;40;41;100m"]) {
			const line = applyBackgroundAround(`${fg}g\x1b[39m\x1b[49mcode\x1b[49m tail`, outerBg);
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, line.indexOf("code") + 4)), "");
			assert.strictEqual(getActiveBackgroundAnsi(line.slice(0, line.indexOf("tail"))), palette.outer);
		}
	});

	it("wraps a long language label and shades each part to the full width", () => {
		Object.assign(palette, PALETTES[0]);
		const lines = new Markdown("```ts title=a-long-title\ncode\n```", 0, 0, shadedTheme).render(12);
		const labels = lines.slice(
			0,
			lines.findIndex((line) => line.includes("code")),
		);
		assert.ok(labels.length > 1);
		for (const line of labels) {
			const end = line.lastIndexOf("\x1b[49m");
			assert.strictEqual(line.slice(end + "\x1b[49m".length), "");
			assert.strictEqual(visibleWidth(line.slice(0, end)), 12, JSON.stringify(line));
		}
	});

	it("renders as before without codeBlockBg", () => {
		const source = "```ts\nconst a = 1;\n```\n\ntext";
		assert.deepStrictEqual(new Markdown(source, 1, 0, defaultMarkdownTheme).render(40).slice(0, 3), [
			` ${defaultMarkdownTheme.codeBlockBorder("```ts")}${" ".repeat(34)}`,
			`   ${defaultMarkdownTheme.codeBlock("const a = 1;")}${" ".repeat(25)}`,
			` ${defaultMarkdownTheme.codeBlockBorder("```")}${" ".repeat(36)}`,
		]);
	});
});
