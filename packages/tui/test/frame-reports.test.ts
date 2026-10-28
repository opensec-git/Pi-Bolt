import assert from "node:assert";
import { describe, it } from "node:test";
import { Box } from "../src/components/box.ts";
import { Markdown, setMarkdownCachingForTest } from "../src/components/markdown.ts";
import { MouseRegion } from "../src/components/mouse-region.ts";
import { ScrollView } from "../src/components/scroll-view.ts";
import { Spacer } from "../src/components/spacer.ts";
import { Text } from "../src/components/text.ts";
import { VStack } from "../src/components/v-stack.ts";
import { renderLayoutFrame } from "../src/layout.ts";
import { type Component, Container, CURSOR_MARKER, type LiveLines, renderLiveLines } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { visibleWidth } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// Containers render again only the children that reported a change (markReportsChanges()), those that do not report
// their changes, and those from where the children array changed; a frame then costs time in proportion to what changed.
// Whatever changes (streaming, edits to earlier entries as an extension makes them, expand/collapse, theme changes,
// resizes, scrolling, components that do not report or that change their arrays in place), every frame must be what the
// same tree renders from nothing, with no caches.

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

/** State that components read when they render, changed without telling them (as a theme, or an extension's own state). */
interface World {
	hue: number;
	/** What the extension components show; changed without reporting it. */
	extension: string[];
	/** The background of the boxes that do not declare their background reported; changed without invalidating. */
	extensionHue: number;
}

/** An extension's component that does not report its changes: it renders from the world's state. */
class ExtensionComponent implements Component {
	private readonly world: World;
	private readonly index: number;
	constructor(world: World, index: number) {
		this.world = world;
		this.index = index;
	}
	render(width: number): string[] {
		return (this.world.extension[this.index] ?? "").split("\n").map((line) => line.slice(0, width));
	}
	invalidate(): void {}
}

/** An extension's component that returns the same array each time, changed in place. */
class InPlaceComponent implements Component {
	private readonly world: World;
	private readonly index: number;
	private readonly lines: string[] = [];
	constructor(world: World, index: number) {
		this.world = world;
		this.index = index;
	}
	render(width: number): string[] {
		this.lines.length = 0;
		for (const line of `in place ${this.world.extension[this.index] ?? ""}`.split("\n"))
			this.lines.push(line.slice(0, width));
		return this.lines;
	}
	invalidate(): void {}
}

/** One of the two trees: the one rendered by the containers, or the reference, rendered from nothing. */
interface Tree {
	world: World;
	document: Container;
	transcript: Container;
	entries: Container[];
	streaming?: { markdown: Markdown; text: string };
	tools: Array<{ box: Box; result: Text; output: string; expanded: boolean }>;
	texts: Text[];
	spacers: Spacer[];
	scroll: ScrollView;
	root: VStack;
}

const WORDS = [
	"alpha",
	"beta",
	"gamma",
	"**bold**",
	"`code`",
	"delta",
	"a-long-word-that-wraps-somewhere",
	"\n",
	"- item\n",
];

function words(draw: (count: number) => number, count: number): string {
	let text = "word";
	for (let i = 0; i < count; i++) text += ` ${WORDS[draw(WORDS.length)]}`;
	return text;
}

function bg(world: World, which: "hue" | "extensionHue"): (text: string) => string {
	return (text) => `\x1b[48;5;${which === "hue" ? world.hue : world.extensionHue}m${text}\x1b[49m`;
}

function makeTree(): Tree {
	const world: World = { hue: 17, extension: [], extensionHue: 52 };
	const transcript = new Container();
	const document = new Container();
	document.addChild(new Text("header", 1, 0));
	document.addChild(transcript);
	const scroll = new ScrollView(document, { follow: "end", scrollbar: "always" });
	const root = new VStack([
		{ component: scroll, basis: 0, grow: 1, shrink: 1, minSize: 1 },
		{ component: new Text("footer", 0, 0), basis: "auto", grow: 0, shrink: 0, minSize: 1 },
	]);
	return { world, document, transcript, entries: [], tools: [], texts: [], spacers: [], scroll, root };
}

/** The reference tree's lines, rendered from nothing: containers and boxes by plain loops, Markdown without caches. */
function reference(component: Component, width: number): string[] {
	if (component instanceof Box) {
		const box = component as unknown as {
			paddingX: number;
			paddingY: number;
			bgFn?: (text: string) => string;
			children: Component[];
		};
		const contentWidth = Math.max(1, width - box.paddingX * 2);
		const lines = box.children.flatMap((child) => reference(child, contentWidth));
		if (lines.length === 0) return [];
		const applyBg = (line: string) => {
			const padded = line + " ".repeat(Math.max(0, width - visibleWidth(line)));
			return box.bgFn ? box.bgFn(padded) : padded;
		};
		const padding = Array.from({ length: box.paddingY }, () => applyBg(""));
		return [...padding, ...lines.map((line) => applyBg(" ".repeat(box.paddingX) + line)), ...padding];
	}
	if (component instanceof MouseRegion) return reference((component as unknown as { child: Component }).child, width);
	if (component instanceof Container) return component.children.flatMap((child) => reference(child, width));
	return component.render(width);
}

/** The reference document, as the layout reads it: its lines from nothing on every render. */
class ReferenceDocument implements Component {
	private readonly tree: Tree;
	constructor(tree: Tree) {
		this.tree = tree;
	}
	render(width: number): string[] {
		setMarkdownCachingForTest(false);
		try {
			return reference(this.tree.document, width);
		} finally {
			setMarkdownCachingForTest(true);
		}
	}
	invalidate(): void {}
}

function referenceRoot(tree: Tree): { scroll: ScrollView; root: VStack } {
	const scroll = new ScrollView(new ReferenceDocument(tree), { follow: "end", scrollbar: "always" });
	const root = new VStack([
		{ component: scroll, basis: 0, grow: 1, shrink: 1, minSize: 1 },
		{ component: new Text("footer", 0, 0), basis: "auto", grow: 0, shrink: 0, minSize: 1 },
	]);
	return { scroll, root };
}

type Draw = (count: number) => number;

function addEntry(tree: Tree, draw: Draw): void {
	const kind = draw(8);
	const entry = new Container();
	if (kind === 0) {
		const text = new Text(words(draw, 3 + draw(10)), 1, draw(2));
		tree.texts.push(text);
		entry.addChild(text);
	} else if (kind === 1) {
		// An assistant message: a spacer, a Markdown in a mouse region (as thinking is), the text.
		const spacer = new Spacer(1);
		tree.spacers.push(spacer);
		entry.addChild(spacer);
		entry.addChild(
			new MouseRegion(new Markdown(words(draw, 5 + draw(20)), 1, 0, defaultMarkdownTheme), () => undefined),
		);
		const markdown = new Markdown(words(draw, 5 + draw(30)), 1, 0, defaultMarkdownTheme);
		entry.addChild(markdown);
		if (draw(2) === 0) tree.streaming = { markdown, text: (markdown as unknown as { text: string }).text };
	} else if (kind === 2) {
		// A tool: its call and its result in a box whose background reads the theme (declared reported).
		const box = new Box(1, 1, bg(tree.world, "hue"));
		box.setBackgroundReported(true);
		const result = new Text("", 0, 0);
		const output = words(draw, 10 + draw(30));
		box.addChild(new MouseRegion(new Text(`$ ${words(draw, 2)}`, 0, 0), () => undefined));
		box.addChild(result);
		const tool = { box, result, output, expanded: false };
		result.setText(output.slice(0, 20));
		tree.tools.push(tool);
		entry.addChild(new Spacer(1));
		entry.addChild(box);
	} else if (kind === 3) {
		const index = tree.world.extension.length;
		tree.world.extension.push(words(draw, 2 + draw(6)));
		entry.addChild(
			draw(2) === 0 ? new ExtensionComponent(tree.world, index) : new InPlaceComponent(tree.world, index),
		);
	} else if (kind === 4) {
		// An extension's box with a background it changes without telling the box.
		const box = new Box(draw(2), draw(2), bg(tree.world, "extensionHue"));
		const text = new Text(words(draw, 3), 0, 0);
		tree.texts.push(text);
		box.addChild(text);
		entry.addChild(box);
	} else if (kind === 7) {
		// A line with the cursor marker, which the regular screen takes out when it is in the bottom rows.
		const text = new Text(`cursor ${CURSOR_MARKER}here ${words(draw, 2)}`, 0, 0);
		tree.texts.push(text);
		entry.addChild(text);
	} else if (kind === 5) {
		const spacer = new Spacer(draw(3));
		tree.spacers.push(spacer);
		entry.addChild(spacer);
	} else {
		// A container in a container.
		const inner = new Container();
		const text = new Text(words(draw, 4), 0, 0);
		tree.texts.push(text);
		inner.addChild(text);
		inner.addChild(new Markdown(words(draw, 6), 0, 0, defaultMarkdownTheme));
		entry.addChild(inner);
	}
	tree.entries.push(entry);
	tree.transcript.addChild(entry);
}

function pick<T>(items: T[], draw: Draw): T | undefined {
	return items.length === 0 ? undefined : items[draw(items.length)];
}

type Step = (tree: Tree, draw: Draw, scroll: ScrollView) => void;

const STEPS: Step[] = [
	(tree, draw) => addEntry(tree, draw),
	(tree, draw) => addEntry(tree, draw),
	// A reply streaming in.
	(tree, draw) => {
		const streaming = tree.streaming;
		if (!streaming) return;
		streaming.text += ` ${words(draw, 1 + draw(3))}`;
		streaming.markdown.setText(streaming.text);
	},
	(tree, draw) => {
		const streaming = tree.streaming;
		if (!streaming) return;
		streaming.text += ` ${words(draw, 1 + draw(3))}`;
		streaming.markdown.setText(streaming.text);
	},
	// A tool's output expanded or collapsed (ctrl+o, a click), and its background changed (its status).
	(tree, draw) => {
		const tool = pick(tree.tools, draw);
		if (!tool) return;
		tool.expanded = !tool.expanded;
		tool.result.setText(tool.expanded ? tool.output : tool.output.slice(0, 20));
	},
	(tree, draw) => {
		const tool = pick(tree.tools, draw);
		if (!tool) return;
		const hue = 20 + draw(5);
		tool.box.setBgFn((text) => `\x1b[48;5;${hue}m${text}\x1b[49m`);
	},
	// An earlier entry edited, as an extension may: its text, its children (by method or through the array).
	(tree, draw) => pick(tree.texts, draw)?.setText(words(draw, 1 + draw(8))),
	(tree, draw) => pick(tree.spacers, draw)?.setLines(draw(3)),
	(tree, draw) => pick(tree.entries, draw)?.addChild(new Text(words(draw, 2), 0, 0)),
	(tree, draw) => {
		const entry = pick(tree.entries, draw);
		if (!entry || entry.children.length === 0) return;
		const index = draw(entry.children.length);
		if (draw(2) === 0) entry.children.splice(index, 1);
		else entry.children[index] = new Spacer(1);
	},
	(tree, draw) => {
		// An entry put in the middle of the transcript (as a tool row before the streaming message), or one removed.
		if (tree.transcript.children.length === 0) return;
		const index = draw(tree.transcript.children.length);
		if (draw(2) === 0) {
			const entry = new Container();
			entry.addChild(new Text(words(draw, 3), 0, 0));
			tree.transcript.children.splice(index, 0, entry);
		} else {
			tree.transcript.removeChild(tree.transcript.children[index]!);
		}
	},
	// State the components read changed without telling them: an extension's, and an extension box's background.
	(tree, draw) => {
		if (tree.world.extension.length === 0) return;
		tree.world.extension[draw(tree.world.extension.length)] = words(draw, draw(5));
	},
	(tree, draw) => {
		tree.world.extensionHue = 52 + draw(4);
	},
	// A theme change: the theme's colors change, and the UI is invalidated.
	(tree, draw) => {
		tree.world.hue = 17 + draw(6);
		tree.document.invalidate();
	},
	// Some entries invalidated (TranscriptContainer.invalidateOnly()).
	(tree, draw) => {
		for (const entry of tree.entries) if (draw(3) === 0) entry.invalidate();
	},
	(_tree, draw, scroll) => {
		scroll.scrollBy(draw(2) === 0 ? -(1 + draw(10)) : 1 + draw(10));
	},
	(tree, draw) => {
		if (draw(8) === 0) {
			tree.transcript.clear();
			tree.entries = [];
			tree.tools = [];
			tree.texts = [];
			tree.spacers = [];
			tree.streaming = undefined;
		}
	},
	() => {},
];

const WIDTHS = [60, 60, 60, 60, 40, 90];

describe("frames from reported changes", () => {
	for (let seed = 1; seed <= 30; seed++) {
		it(`every frame is the tree rendered from nothing (seed ${seed})`, () => {
			const next = random(seed * 104729);
			const live = makeTree();
			const expected = makeTree();
			const reference = referenceRoot(expected);
			let source: LiveLines | undefined;
			let revision = -1;
			let followed: string[] = [];
			const log: string[] = [];
			for (let step = 0; step < 250; step++) {
				const stepIndex = Math.floor(next() * STEPS.length);
				const draws: number[] = [];
				STEPS[stepIndex]!(
					live,
					(count) => {
						const value = Math.floor(next() * count);
						draws.push(value);
						return value;
					},
					live.scroll,
				);
				let replayed = 0;
				STEPS[stepIndex]!(expected, () => draws[replayed++]!, reference.scroll);
				log.push(`${stepIndex}(${draws.join(",")})`);
				const label = `seed ${seed} step ${step}: ${log.slice(-12).join(" ")}`;
				const width = WIDTHS[Math.floor(next() * WIDTHS.length)]!;
				const height = 8 + Math.floor(next() * 10);

				const frame = renderLayoutFrame(live.root, width, height, () => {});
				const want = renderLayoutFrame(reference.root, width, height, () => {});
				assert.deepStrictEqual(frame.lines, want.lines, `${label}: the frame`);
				assert.strictEqual(live.scroll.scrollTop, reference.scroll.scrollTop, `${label}: the scroll position`);

				// The document's lines, read as a reader of live lines reads them, and as render() returns them.
				const contentWidth = live.scroll.getContentWidth(width);
				const all = new ReferenceDocument(expected).render(contentWidth);
				const lines = renderLiveLines(live.document, contentWidth)!;
				const from = lines === source ? lines.changedSince(revision) : 0;
				if (from !== Number.POSITIVE_INFINITY) {
					followed.length = Math.min(followed.length, from);
					for (let i = from; i < lines.lines.length; i++) followed.push(lines.lines[i]!);
				}
				source = lines;
				revision = lines.revision;
				assert.deepStrictEqual(followed, all, `${label}: the live lines`);
				if (next() < 0.2) {
					const copy = live.document.render(contentWidth);
					assert.deepStrictEqual(copy, all, `${label}: render()`);
					// (The caller's array is its own.)
					copy.push("changed by the caller");
					copy[0] = "changed";
				}
				if (step % 50 === 49) followed = [];
				if (step % 50 === 49) source = undefined;
			}
		});
	}

	// The regular screen takes the cursor marker out of the last line that has one in the bottom rows: the rows it takes
	// as they were the frame before must be above those rows in both frames.
	for (const change of ["the content shrinks", "the terminal gets shorter"]) {
		it(`takes out the cursor marker in the rows that come into view when ${change}`, async () => {
			const marker = change === "the content shrinks" ? 50 : 60;
			const lines = (count: number, from: number) =>
				Array.from(
					{ length: count },
					(_, i) => new Text(i + from === marker ? `line ${CURSOR_MARKER}cursor` : `line ${i + from}`, 0, 0),
				);
			const terminal = new VirtualTerminal(40, 12);
			const tui = new TuiMainScreen(terminal);
			const document = new Container();
			for (const text of lines(70, 0)) document.addChild(text);
			tui.addChild(document);
			tui.start();
			tui.renderNow();
			if (change === "the content shrinks") {
				// (Line 50 comes into view from above it, as the last 12 go.)
				for (let i = 0; i < 12; i++) document.removeChild(document.children[document.children.length - 1]!);
			} else {
				// (Line 60, in view and its marker taken out, goes out of view.)
				terminal.resize(40, 6);
			}
			tui.renderNow();
			const fresh = new TuiMainScreen(new VirtualTerminal(40, change === "the content shrinks" ? 12 : 6));
			const copy = new Container();
			for (const text of lines(change === "the content shrinks" ? 58 : 70, 0)) copy.addChild(text);
			fresh.addChild(copy);
			fresh.start();
			fresh.renderNow();
			const shown = (screen: TuiMainScreen) => screen.shownLinesForTest();
			assert.deepStrictEqual(shown(tui), shown(fresh));
			tui.stop();
			fresh.stop();
		});
	}

	it("frames of the regular screen are what a tree rendered from nothing shows", async () => {
		for (let seed = 1; seed <= 8; seed++) {
			const next = random(seed * 7);
			const live = makeTree();
			const expected = makeTree();
			const liveTerminal = new VirtualTerminal(60, 12);
			const referenceTerminal = new VirtualTerminal(60, 12);
			const liveTui = new TuiMainScreen(liveTerminal);
			const referenceTui = new TuiMainScreen(referenceTerminal);
			liveTui.addChild(live.document);
			referenceTui.addChild(new ReferenceDocument(expected));
			liveTui.start();
			referenceTui.start();
			const log: string[] = [];
			for (let step = 0; step < 80; step++) {
				let stepIndex = Math.floor(next() * STEPS.length);
				if (stepIndex === STEPS.length - 3) stepIndex = 0; // (No scrolling: the terminal scrolls.)
				const draws: number[] = [];
				STEPS[stepIndex]!(
					live,
					(count) => {
						const value = Math.floor(next() * count);
						draws.push(value);
						return value;
					},
					live.scroll,
				);
				let replayed = 0;
				STEPS[stepIndex]!(expected, () => draws[replayed++]!, expected.scroll);
				log.push(`${stepIndex}(${draws.join(",")})`);
				if (next() < 0.1) {
					const columns = WIDTHS[Math.floor(next() * WIDTHS.length)]!;
					const rows = 8 + Math.floor(next() * 8);
					liveTerminal.resize(columns, rows);
					referenceTerminal.resize(columns, rows);
				}
				liveTui.renderNow();
				referenceTui.renderNow();
				const label = `seed ${seed} step ${step}: ${log.slice(-12).join(" ")}`;
				// (The lines written, cursor markers included, and what the terminal shows.)
				const shown = (tui: TuiMainScreen) => tui.shownLinesForTest();
				assert.deepStrictEqual(shown(liveTui), shown(referenceTui), label);
				assert.deepStrictEqual(
					await liveTerminal.flushAndGetViewport(),
					await referenceTerminal.flushAndGetViewport(),
					label,
				);
				assert.deepStrictEqual(liveTerminal.getScrollBuffer(), referenceTerminal.getScrollBuffer(), label);
			}
			liveTui.stop();
			referenceTui.stop();
		}
	});
});
