import assert from "node:assert";
import { afterEach, describe, it } from "node:test";
import { Box } from "../src/components/box.ts";
import { Markdown } from "../src/components/markdown.ts";
import { ScrollView } from "../src/components/scroll-view.ts";
import { Text } from "../src/components/text.ts";
import { isImageLine, resetCapabilitiesCache, setCapabilities } from "../src/terminal-image.ts";
import { type Component, Container, type TuiBase } from "../src/tui.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { visibleWidth } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// Markdown lines without a background are not padded with spaces to the full width: every row is cleared before it is
// written, so the cells after the text look the same either way. A screen streaming a reply must show, cell for cell
// (characters, colors and attributes, the scrollback included), what it shows with the lines padded as they were.

/** A Markdown component's lines padded to the width with spaces, as renderBlock() drew them before. */
class Padded implements Component {
	private readonly markdown: Markdown;
	constructor(markdown: Markdown) {
		this.markdown = markdown;
	}
	render(width: number): string[] {
		return this.markdown
			.render(width)
			.map((line) => (isImageLine(line) ? line : line + " ".repeat(Math.max(0, width - visibleWidth(line)))));
	}
	invalidate(): void {
		this.markdown.invalidate();
	}
}

const REPLY = `# A heading that is long enough to wrap at a narrow width

Some *italic*, **bold**, ~~struck~~ and \`code\` text, with a [link](https://example.com/a/long/path) that wraps, and
more words after it so that the paragraph runs over several rows of the screen.

- item one with \`code\` and **bold** words that wrap to the next row
- item two
  1. nested ordered
  2. nested again with a [second link](https://example.org)

> A quote with *style* in it, long enough to wrap over two rows of the screen at this width.

| name | value | note |
|---|---:|---|
| alpha | 1 | the first row |
| beta | 22 | a second row whose note is longer than the column |

\`\`\`ts
const value = compute(1, 2, 3); // a comment that is long enough to wrap
\`\`\`

---

Done, with a last line.
`;

interface Session {
	terminal: VirtualTerminal;
	screen: TuiBase;
	markdowns: Markdown[];
}

function mainSession(padded: boolean, width: number, height: number): Session {
	const terminal = new VirtualTerminal(width, height);
	const screen = new TuiMainScreen(terminal);
	const markdowns = sessionMarkdowns();
	const root = sessionRoot(markdowns, padded);
	screen.addChild(root);
	return { terminal, screen, markdowns };
}

function altSession(padded: boolean, width: number, height: number): Session {
	const terminal = new VirtualTerminal(width, height);
	const screen = new TuiAltScreen(terminal, undefined, undefined, { copyOnSelect: false });
	const markdowns = sessionMarkdowns();
	screen.setLayoutRoot(new ScrollView(sessionRoot(markdowns, padded), { follow: "end", primary: true }));
	screen.start();
	return { terminal, screen, markdowns };
}

/** A reply, a thinking block (a styled one) and a message in a box with a background. */
function sessionMarkdowns(): Markdown[] {
	return [
		new Markdown("", 1, 0, defaultMarkdownTheme),
		new Markdown("", 1, 1, defaultMarkdownTheme, { color: (text) => `\x1b[90m${text}\x1b[39m`, italic: true }),
		new Markdown("", 1, 0, defaultMarkdownTheme),
	];
}

function sessionRoot(markdowns: Markdown[], padded: boolean): Container {
	const root = new Container();
	const show = (markdown: Markdown): Component => (padded ? new Padded(markdown) : markdown);
	root.addChild(new Text("header", 0, 0));
	root.addChild(show(markdowns[0]!));
	root.addChild(show(markdowns[1]!));
	const box = new Box(1, 0, (text) => `\x1b[44m${text}\x1b[49m`);
	box.addChild(show(markdowns[2]!));
	root.addChild(box);
	root.addChild(new Text("status line", 0, 0));
	return root;
}

/**
 * What a cell looks like: its characters, colors and attributes, except those a blank cell does not show (the foreground
 * color, bold, italic and dim of a space that is not inverse). Padding spaces carry the style a line ends with; the cells
 * of a cleared row have none, and look the same.
 */
function visible(rows: string[][]): string[][] {
	return rows.map((cells) =>
		cells.map((cell) => {
			const [chars, , bg, flags] = cell.split("|") as [string, string, string, string];
			if (chars !== " " || flags[4] === "1") return cell;
			return `${chars}|-|${bg}|000${flags.slice(3)}`;
		}),
	);
}

/** Streams the reply into each Markdown of both sessions, a frame for each chunk, and compares the screens after each. */
async function streamBoth(
	a: Session,
	b: Session,
	read: (terminal: VirtualTerminal) => string[][],
	afterEach?: () => Promise<void>,
): Promise<number> {
	let frames = 0;
	for (let m = 0; m < 3; m++) {
		for (let length = 0; length < REPLY.length; ) {
			length = Math.min(REPLY.length, length + 1 + ((length * 7) % 23));
			for (const session of [a, b]) {
				session.markdowns[m]!.setText(REPLY.slice(0, length));
				session.screen.renderNow();
				await session.terminal.flush();
			}
			assert.deepStrictEqual(
				visible(read(a.terminal)),
				visible(read(b.terminal)),
				`markdown ${m} at length ${length}`,
			);
			await afterEach?.();
			frames++;
		}
	}
	return frames;
}

describe("Markdown lines without padding look as padded lines do", () => {
	afterEach(() => resetCapabilitiesCache());

	for (const hyperlinks of [false, true]) {
		it(`main screen, scrollback included (hyperlinks ${hyperlinks ? "on" : "off"})`, async () => {
			setCapabilities({ images: null, trueColor: true, hyperlinks });
			for (const [width, height] of [
				[50, 10],
				[23, 6],
			] as const) {
				const unpadded = mainSession(false, width, height);
				const padded = mainSession(true, width, height);
				const frames = await streamBoth(unpadded, padded, (terminal) => terminal.getStyledScrollBuffer());
				assert.ok(frames > 100, `only ${frames} frames`);
				// (A resize draws all of it again.)
				for (const session of [unpadded, padded]) {
					session.terminal.resize(width + 7, height);
					session.screen.renderNow();
					await session.terminal.flush();
				}
				assert.deepStrictEqual(
					visible(unpadded.terminal.getStyledScrollBuffer()),
					visible(padded.terminal.getStyledScrollBuffer()),
				);
			}
		});
	}

	it("fullscreen, with a selection over several rows", async () => {
		setCapabilities({ images: null, trueColor: true, hyperlinks: false });
		const unpadded = altSession(false, 44, 12);
		const padded = altSession(true, 44, 12);
		try {
			let step = 0;
			let selectedCells = 0;
			const frames = await streamBoth(
				unpadded,
				padded,
				(terminal) => terminal.getStyledViewport(),
				async () => {
					// Now and then a drag from the middle of one row to the middle of a row below it, and the release.
					if (step++ % 9 !== 0) return;
					for (const input of ["\x1b[<0;30;3M", "\x1b[<32;8;9M"]) {
						for (const session of [unpadded, padded]) {
							session.terminal.sendInput(input);
							session.screen.renderNow();
							await session.terminal.flush();
						}
						assert.deepStrictEqual(
							visible(unpadded.terminal.getStyledViewport()),
							visible(padded.terminal.getStyledViewport()),
							`selection at step ${step}`,
						);
					}
					const selected = unpadded.terminal
						.getStyledViewport()
						.flat()
						.filter((cell) => cell.split("|")[3]![4] === "1").length;
					assert.ok(selected > 0, "nothing drawn selected");
					selectedCells += selected;
					for (const session of [unpadded, padded]) session.terminal.sendInput("\x1b[<0;8;9m");
				},
			);
			assert.ok(frames > 100, `only ${frames} frames`);
			// (Rows past the text of a line are selected too, as they were when the lines were padded.)
			assert.ok(selectedCells > 2000, `only ${selectedCells} cells drawn selected`);
		} finally {
			unpadded.screen.stop();
			padded.screen.stop();
		}
	});
});
