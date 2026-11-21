import assert from "node:assert/strict";
import { test } from "node:test";
import { ScrollView, type ScrollViewScrollbar } from "../src/components/scroll-view.ts";
import { Text } from "../src/components/text.ts";
import { Container } from "../src/tui.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { stripTerminalSequences } from "../src/utils.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

const history = Array.from({ length: 100 }, (_, index) => (index === 3 ? "old search result" : `message ${index}`));

function createScreen(initialCount: number, scrollbar: ScrollViewScrollbar = "hidden") {
	const terminal = new VirtualTerminal(64, 8);
	const copied: string[] = [];
	const screen = new TuiAltScreen(terminal, undefined, undefined, {
		copyOnSelect: false,
		copySelection: async (text) => {
			copied.push(text);
			return true;
		},
	});
	let count = initialCount;
	let fullReads = 0;
	const transcript = new Text(history.slice(-count).join("\n"), 0, 0);
	const document = new Container();
	document.addChild(new Text("session header", 0, 0));
	document.addChild(transcript);
	screen.setLayoutRoot(new ScrollView(document, { follow: "end", primary: true, scrollbar }));
	if (count < history.length) {
		const prepend = (requested: number): number => {
			const next = Math.min(history.length, Math.max(count, requested));
			const added = next - count;
			if (added > 0) transcript.setText(history.slice(-next).join("\n"));
			count = next;
			return added;
		};
		screen.beforeViewportScroll = (requested) => prepend(requested) || undefined;
		screen.beforeDocumentRead = () => {
			fullReads++;
			return prepend(history.length) > 0;
		};
	}
	screen.start();
	screen.renderNow();
	return { screen, terminal, transcript, copied, count: () => count, fullReads: () => fullReads };
}

test("paging and wheel navigation match the complete transcript while a selection stays anchored", async () => {
	const paged = createScreen(10);
	const full = createScreen(history.length);
	try {
		assert.deepEqual(paged.screen.getScreenLines(), full.screen.getScreenLines());
		// Select the row that reads "message 94". Moving the viewport must preserve both document endpoints.
		for (const input of ["\x1b[<35;20;4M", "\x1b[<0;1;3M", "\x1b[<32;10;3M", "\x1b[<0;10;3m"]) {
			paged.terminal.sendInput(input);
			full.terminal.sendInput(input);
		}
		assert.equal(paged.count(), 10);
		for (const input of ["\x1b[5~", "\x1b[5~", "\x1b[<64;3;4M", "\x1b[5~", "\x1b[6~", "\x1b[<65;3;4M"]) {
			paged.terminal.sendInput(input);
			full.terminal.sendInput(input);
			paged.screen.renderNow();
			full.screen.renderNow();
			assert.deepEqual(paged.screen.getScreenLines(), full.screen.getScreenLines());
		}
		assert.ok(paged.count() > 10 && paged.count() < history.length);
		assert.equal(await paged.screen.copyActiveSelectionToClipboard(), true);
		assert.deepEqual(paged.copied, ["message 94"]);
		assert.equal(paged.fullReads(), 0);
	} finally {
		paged.screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});

test("search includes an old match outside the loaded transcript", () => {
	const paged = createScreen(10);
	try {
		paged.terminal.sendInput("\x1b[102;6u");
		paged.terminal.sendInput("old search result");
		paged.screen.renderNow();
		assert.ok(paged.screen.getScreenLines().some((line) => line.includes("1/1")));
		assert.ok(paged.screen.viewportTop <= 4);
		assert.equal(paged.count(), history.length);
	} finally {
		paged.screen.stop({ preserveScreen: true });
	}
});

test("scrollbar presses and drags use the full document height", () => {
	const paged = createScreen(10, "always");
	const full = createScreen(history.length, "always");
	try {
		paged.terminal.sendInput("\x1b[<35;64;2M");
		assert.equal(paged.fullReads(), 0, "hovering does not need the full document");
		for (const input of ["\x1b[<0;64;2M", "\x1b[<32;64;5M", "\x1b[<0;64;5m"]) {
			paged.terminal.sendInput(input);
			full.terminal.sendInput(input);
			paged.screen.renderNow();
			full.screen.renderNow();
			assert.deepEqual(paged.screen.getScreenLines(), full.screen.getScreenLines());
		}
		assert.equal(paged.count(), history.length);
	} finally {
		paged.screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});

test("grabbing a displayed partial-history thumb preserves the viewport until the pointer moves", () => {
	const paged = createScreen(20, "always");
	const full = createScreen(history.length, "always");
	const content = () => paged.screen.getScreenLines().map((line) => stripTerminalSequences(line).slice(0, -1));
	const pointer = (button: number, row: number) => {
		paged.terminal.sendInput(`\x1b[<${button};${paged.terminal.columns};${row}M`);
		paged.screen.renderNow();
	};
	try {
		for (const { screen } of [paged, full]) {
			screen.scrollBy(-5);
			screen.renderNow();
		}
		const before = content();
		const top = full.screen.viewportTop;
		const row = paged.screen.getScreenLines().findIndex((line) => line.includes("┃")) + 1;
		assert.ok(row > 2 && row < paged.terminal.rows);
		assert.ok(!full.screen.getScreenLines()[row - 1]?.includes("┃"), "the full thumb occupies a different row");
		pointer(0, row);
		assert.equal(paged.count(), history.length);
		assert.deepEqual(content(), before);
		pointer(32, row);
		assert.equal(paged.screen.viewportTop, top);
		assert.deepEqual(content(), before);
		pointer(32, row - 1);
		assert.ok(paged.screen.viewportTop > 0 && paged.screen.viewportTop < top);
		pointer(32, 1);
		assert.equal(paged.screen.viewportTop, 0);
		pointer(32, paged.terminal.rows);
		assert.equal(paged.screen.viewportTop, history.length + 1 - paged.terminal.rows);
	} finally {
		paged.screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});

test("jumping to the start and exporting each include the entire document", () => {
	const paged = createScreen(10);
	const exportOnly = createScreen(10);
	const full = createScreen(history.length);
	try {
		paged.screen.scrollToTop();
		full.screen.scrollToTop();
		paged.screen.renderNow();
		full.screen.renderNow();
		assert.deepEqual(paged.screen.getScreenLines(), full.screen.getScreenLines());
		exportOnly.screen.scrollBy(-4);
		exportOnly.screen.renderNow();
		const before = exportOnly.screen.getScreenLines();
		assert.deepEqual(exportOnly.screen.render(64), full.screen.render(64));
		exportOnly.screen.renderNow();
		assert.deepEqual(exportOnly.screen.getScreenLines(), before, "export preserves the current viewport");
	} finally {
		paged.screen.stop({ preserveScreen: true });
		exportOnly.screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});

test("returning to the end waits for selection and search before releasing earlier pages", () => {
	const { screen, terminal } = createScreen(10);
	let releases = 0;
	screen.onViewportFollowEnd = () => releases++;
	try {
		screen.scrollBy(-4);
		screen.renderNow();
		for (const input of ["\x1b[<0;1;3M", "\x1b[<32;8;3M", "\x1b[<0;8;3m"]) terminal.sendInput(input);
		screen.scrollToBottom();
		screen.renderNow();
		assert.equal(releases, 0);
		assert.ok(screen.hasActiveSelection());
		screen.resetTextSelection();
		screen.renderNow();
		assert.equal(releases, 1);

		terminal.sendInput("\x1b[102;6u");
		screen.scrollBy(-4);
		screen.scrollBy(4);
		screen.renderNow();
		assert.equal(releases, 1);
		terminal.sendInput("\x1b[102;6u");
		screen.renderNow();
		assert.equal(releases, 2);
		screen.renderNow();
		assert.equal(releases, 2, "idle frames do not release pages a second time");
	} finally {
		screen.stop({ preserveScreen: true });
	}
});

test("resizing while detached reflows history at the same position as an eager transcript", () => {
	const paged = createScreen(10);
	const full = createScreen(history.length);
	try {
		for (const { screen, terminal } of [paged, full]) {
			screen.scrollBy(-7);
			screen.renderNow();
			terminal.resize(8, 6);
			screen.renderNow();
		}
		assert.deepEqual(paged.screen.getScreenLines(), full.screen.getScreenLines());
		assert.equal(paged.screen.isFollowingOutput, false);
	} finally {
		paged.screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});

test("preparing a global reflow preserves the eager document's absolute viewport", () => {
	const paged = new TuiAltScreen(new VirtualTerminal(64, 5));
	const full = new TuiAltScreen(new VirtualTerminal(64, 5));
	let expanded = false;
	let firstEntry = 7;
	const entries = Array.from({ length: 16 }, (_, index) => index);
	const renderEntries = (first: number) =>
		entries
			.slice(first)
			.flatMap((index) => {
				const height = 1 + (index % 3) + (expanded ? 1 + (index % 4) : 0);
				return Array.from({ length: height }, (_, row) => `entry ${index} row ${row}`);
			})
			.join("\n");
	const pagedText = new Text(renderEntries(firstEntry), 0, 0);
	const fullText = new Text(renderEntries(0), 0, 0);
	paged.addChild(pagedText);
	full.addChild(fullText);
	paged.beforeViewportScroll = () => undefined;
	paged.beforeDocumentRead = () => {
		if (firstEntry === 0) return false;
		firstEntry = 0;
		pagedText.setText(renderEntries(firstEntry));
		return true;
	};
	paged.start();
	full.start();
	try {
		for (const screen of [paged, full]) {
			screen.renderNow();
			screen.scrollBy(-3);
			screen.renderNow();
		}
		assert.deepEqual(paged.getScreenLines(), full.getScreenLines());
		assert.ok(paged.viewportTop < full.viewportTop, "the prefix is still omitted");
		paged.prepareDocumentReflow();
		expanded = true;
		pagedText.setText(renderEntries(firstEntry));
		fullText.setText(renderEntries(0));
		paged.renderNow();
		full.renderNow();
		assert.equal(paged.viewportTop, full.viewportTop);
		assert.deepEqual(paged.getScreenLines(), full.getScreenLines());
	} finally {
		paged.stop({ preserveScreen: true });
		full.stop({ preserveScreen: true });
	}
});

test("scrolling past the end leaves the loaded page alone while a taller viewport extends it", () => {
	const paged = createScreen(10);
	const full = createScreen(history.length);
	let pageRequests = 0;
	let tailRequests = 0;
	const load = paged.screen.beforeViewportScroll!;
	paged.screen.beforeViewportScroll = (fromEnd, height) => {
		pageRequests++;
		return load(fromEnd, height);
	};
	paged.screen.onViewportFollowEnd = () => {
		tailRequests++;
		load(paged.terminal.rows, paged.terminal.rows);
	};
	try {
		paged.terminal.sendInput("\x1b[<65;1;2M");
		paged.terminal.sendInput("\x1b[6~");
		paged.screen.renderNow();
		assert.equal(pageRequests, 0);
		assert.equal(tailRequests, 0);
		for (const { screen, terminal } of [paged, full]) {
			terminal.resize(64, 16);
			screen.renderNow();
		}
		assert.equal(tailRequests, 1);
		assert.deepEqual(paged.screen.getScreenLines(), full.screen.getScreenLines());
	} finally {
		paged.screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});

test("scrolling back down hydrates evicted rows even when document height stays constant", () => {
	const terminal = new VirtualTerminal(64, 8);
	const screen = new TuiAltScreen(terminal);
	const full = createScreen(history.length);
	const rows = ["session header", ...history];
	let startFromEnd = terminal.rows;
	let endFromEnd = 0;
	const renderWindow = () =>
		rows.map((line, index) =>
			endFromEnd < rows.length - index && rows.length - index <= startFromEnd ? line : "unloaded",
		);
	const document = new Text(renderWindow().join("\n"), 0, 0);
	screen.setLayoutRoot(new ScrollView(document, { follow: "end", primary: true }));
	screen.beforeViewportScroll = (fromEnd, height) => {
		const nextStart = Math.max(height, Math.min(rows.length, fromEnd));
		const nextEnd = Math.max(0, nextStart - height);
		if (nextStart === startFromEnd && nextEnd === endFromEnd) return undefined;
		startFromEnd = nextStart;
		endFromEnd = nextEnd;
		document.setText(renderWindow().join("\n"));
		return 0;
	};
	screen.start();
	try {
		screen.renderNow();
		for (const delta of [-12, -14, 10, 8, 10]) {
			screen.scrollBy(delta);
			full.screen.scrollBy(delta);
			screen.renderNow();
			full.screen.renderNow();
			assert.deepEqual(screen.getScreenLines(), full.screen.getScreenLines());
			assert.equal(startFromEnd - endFromEnd, terminal.rows);
			if (delta < 0) assert.ok(endFromEnd > 0, "the last rows have been evicted");
			if (delta === -14) {
				const before = screen.getScreenLines();
				rows.push("new message one", "new message two");
				document.setText(renderWindow().join("\n"));
				full.transcript.setText(rows.slice(1).join("\n"));
				screen.renderNow();
				full.screen.renderNow();
				assert.deepEqual(screen.getScreenLines(), before, "new output leaves the detached viewport anchored");
			}
		}
		assert.equal(endFromEnd, 0, "scrolling down restored the last page");
		screen.scrollBy(-20);
		screen.scrollToBottom();
		screen.renderNow();
		assert.deepEqual(screen.getScreenLines(), full.screen.getScreenLines(), "an absolute jump hydrates the tail");
	} finally {
		screen.stop({ preserveScreen: true });
		full.screen.stop({ preserveScreen: true });
	}
});
