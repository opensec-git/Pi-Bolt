import assert from "node:assert";
import { describe, it } from "node:test";
import { Editor } from "../src/components/editor.ts";
import type { TUI } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { defaultEditorTheme } from "./test-themes.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

function createEditor(): Editor {
	const tui: TUI = new TuiMainScreen(new VirtualTerminal(120, 60));
	return new Editor(tui, defaultEditorTheme);
}

/** What an editor that never rendered before draws for the same text and cursor. */
function freshRender(text: string, width: number, keys: string[] = []): string[] {
	const editor = createEditor();
	editor.setText(text);
	// (Cursor movement uses the width of the last render, as in the editor under test.)
	editor.render(width);
	for (const key of keys) editor.handleInput(key);
	return editor.render(width);
}

const LONG = "The quick brown fox jumps over the lazy dog and keeps running far beyond the edge of the screen";
const TEXT = [LONG, "short line", `${LONG} again, with more words to wrap`, "", LONG].join("\n");

describe("Editor wrap cache", () => {
	it("draws what a fresh editor draws when the width changes back and forth", () => {
		const editor = createEditor();
		editor.setText(TEXT);
		for (const width of [40, 25, 60, 25, 40, 17, 40]) {
			assert.deepStrictEqual(editor.render(width), freshRender(TEXT, width), `width ${width}`);
		}
	});

	it("wraps an edited line again and keeps the others", () => {
		const editor = createEditor();
		editor.setText(TEXT);
		editor.render(30);
		editor.handleInput("x");
		editor.handleInput("y");
		assert.deepStrictEqual(editor.render(30), freshRender(TEXT, 30, ["x", "y"]));
		// Cursor movement across wrapped lines uses the same layout.
		editor.handleInput("\x1b[A");
		editor.handleInput("\x1b[A");
		editor.handleInput("z");
		assert.deepStrictEqual(editor.render(30), freshRender(TEXT, 30, ["x", "y", "\x1b[A", "\x1b[A", "z"]));
	});

	it("follows lines inserted and removed before cached lines", () => {
		const editor = createEditor();
		editor.setText(TEXT);
		editor.render(30);
		const shorter = [LONG, LONG].join("\n");
		editor.setText(shorter);
		assert.deepStrictEqual(editor.render(30), freshRender(shorter, 30));
		const longer = ["new first line", TEXT].join("\n");
		editor.setText(longer);
		assert.deepStrictEqual(editor.render(30), freshRender(longer, 30));
	});

	it("wraps paste markers as one unit only while the paste exists", () => {
		const pasted = Array.from({ length: 20 }, (_, i) => `pasted line ${i}`).join("\n");
		const typeWithPaste = (editor: Editor) => {
			editor.handleInput("some words before the pasted text ");
			editor.handleInput(`\x1b[200~${pasted}\x1b[201~`);
			editor.handleInput(" and words after the paste marker");
		};
		const editor = createEditor();
		typeWithPaste(editor);
		const reference = createEditor();
		typeWithPaste(reference);
		for (const width of [30, 21, 30]) {
			const lines = editor.render(width);
			assert.ok(
				lines.some((line) => line.includes("[paste #1")),
				"marker shown",
			);
			assert.deepStrictEqual(lines, createEditorWith(typeWithPaste).render(width), `width ${width}`);
		}
		assert.deepStrictEqual(editor.render(21), reference.render(21));

		// The same text with no paste behind the marker: the marker is ordinary text and wraps as words.
		const text = editor.getText();
		editor.setText(text);
		assert.deepStrictEqual(editor.render(21), freshRender(text, 21));
	});
});

function createEditorWith(type: (editor: Editor) => void): Editor {
	const editor = createEditor();
	type(editor);
	return editor;
}

// The editor keeps its layout between keystrokes and lays out again only the lines that changed (a keystroke on a long
// line wraps its last rows only), keeps the joined text until the text changes, and draws only the visible rows. Whatever
// the edits, it must draw, move and read what an editor that lays the whole text out from scratch does.

/** An editor that forgets its layout before every key and every render. */
class FromScratchEditor extends Editor {
	override handleInput(data: string): void {
		this.forgetLayout();
		super.handleInput(data);
	}

	override render(width: number): string[] {
		this.forgetLayout();
		this.invalidate();
		return super.render(width);
	}

	private forgetLayout(): void {
		(this as unknown as { layout?: unknown }).layout = undefined;
	}
}

interface Terminal {
	rows: number;
	columns: number;
}

function createPair(terminal: Terminal): { live: Editor; reference: Editor } {
	const tui = { requestRender: () => {}, terminal } as unknown as TUI;
	const live = new Editor(tui, defaultEditorTheme);
	const reference = new FromScratchEditor(tui, defaultEditorTheme);
	live.focused = reference.focused = true;
	return { live, reference };
}

/** Apply each step to both editors; after each, compare what they draw at `width`, their text and their cursor. */
function compareSteps(text: string, width: number, steps: Array<string | ((editor: Editor) => void)>): void {
	const terminal: Terminal = { rows: 40, columns: width };
	const { live, reference } = createPair(terminal);
	live.setText(text);
	reference.setText(text);
	assert.deepStrictEqual(live.render(width), reference.render(width), "initial render");
	steps.forEach((step, index) => {
		for (const editor of [live, reference]) {
			if (typeof step === "string") editor.handleInput(step);
			else step(editor);
		}
		const label = `step ${index}: ${JSON.stringify(typeof step === "string" ? step : step.toString())}`;
		assert.deepStrictEqual(live.render(width), reference.render(width), label);
		assert.strictEqual(live.getText(), live.getLines().join("\n"), label);
		assert.strictEqual(live.getText(), reference.getText(), label);
		assert.deepStrictEqual(live.getCursor(), reference.getCursor(), label);
	});
}

const UP = "\x1b[A";
const DOWN = "\x1b[B";
const LEFT = "\x1b[D";
const RIGHT = "\x1b[C";
const NEW_LINE = "\x1b\r";
const BACKSPACE = "\x7f";
const DELETE = "\x1b[3~";
const UNDO = "\x1b[45;5u";
const KILL_LINE = "\x0b";
const YANK = "\x19";
const DELETE_WORD = "\x17";
const PAGE_UP = "\x1b[5~";
const PAGE_DOWN = "\x1b[6~";
const LINE_START = "\x01";
const LINE_END = "\x05";

const MANY_LINES = Array.from({ length: 60 }, (_, i) => (i % 7 === 3 ? `${LONG} ${i}` : `line ${i}`)).join("\n");
const repeat = <T>(count: number, value: T): T[] => Array.from({ length: count }, () => value);

describe("Editor layout cache", () => {
	it("follows an edit in the middle of the text", () => {
		compareSteps(MANY_LINES, 30, [
			...repeat(25, UP),
			"x",
			"y",
			" ",
			"z",
			...repeat(3, UP),
			"w",
			...repeat(6, DOWN),
			"v",
		]);
	});

	it("follows new and deleted lines", () => {
		compareSteps(MANY_LINES, 30, [
			...repeat(20, UP),
			NEW_LINE,
			"new",
			NEW_LINE,
			NEW_LINE,
			BACKSPACE,
			BACKSPACE,
			LINE_START,
			BACKSPACE,
			LINE_END,
			DELETE,
			KILL_LINE,
			KILL_LINE,
			KILL_LINE,
			UP,
			YANK,
			DELETE_WORD,
			"\x15", // Ctrl+U: delete to line start
			...repeat(30, DOWN),
			BACKSPACE,
		]);
	});

	it("follows pastes, inline and as a marker", () => {
		const small = "pasted one\npasted two\npasted three";
		const large = Array.from({ length: 30 }, (_, i) => `pasted line ${i}`).join("\n");
		compareSteps(MANY_LINES, 30, [
			...repeat(10, UP),
			`\x1b[200~${small}\x1b[201~`,
			"a",
			`\x1b[200~${large}\x1b[201~`,
			" b",
			BACKSPACE,
			BACKSPACE,
			BACKSPACE, // deletes the marker and its paste
			`\x1b[200~${large}\x1b[201~`,
			UNDO,
			UNDO,
		]);
	});

	it("follows undo of typing, new lines and merged lines", () => {
		compareSteps(MANY_LINES, 30, [
			...repeat(15, UP),
			"a",
			"b",
			" ",
			"c",
			NEW_LINE,
			"d",
			LINE_START,
			BACKSPACE,
			BACKSPACE,
			UNDO,
			UNDO,
			UNDO,
			UNDO,
			UNDO,
			"e",
		]);
	});

	it("lays the text out again when the width changes", () => {
		const terminal: Terminal = { rows: 40, columns: 30 };
		const { live, reference } = createPair(terminal);
		for (const editor of [live, reference]) {
			editor.setText(MANY_LINES);
			for (const key of [...repeat(12, UP), "x"]) editor.handleInput(key);
		}
		for (const width of [30, 17, 50, 30, 8, 3, 30]) {
			assert.deepStrictEqual(live.render(width), reference.render(width), `width ${width}`);
			// Moving up and down uses the layout at the last width drawn.
			for (const key of [UP, UP, "q", DOWN, PAGE_UP, PAGE_DOWN]) {
				live.handleInput(key);
				reference.handleInput(key);
			}
			assert.deepStrictEqual(live.render(width), reference.render(width), `width ${width} after moving`);
		}
	});

	it("follows a change of the whole text to one with lines more or fewer", () => {
		const shifted = ["an added first line", ...MANY_LINES.split("\n")].join("\n");
		compareSteps(MANY_LINES, 30, [
			(editor) => editor.setText(shifted),
			(editor) => editor.setText(MANY_LINES),
			(editor) => editor.setText(MANY_LINES.split("\n").slice(5).join("\n")),
			(editor) => editor.setText(""),
			(editor) => editor.setText(MANY_LINES),
		]);
	});

	it("follows many changes without a render in between", () => {
		const terminal: Terminal = { rows: 40, columns: 30 };
		const { live, reference } = createPair(terminal);
		live.setText(MANY_LINES);
		reference.setText(MANY_LINES);
		assert.deepStrictEqual(live.render(30), reference.render(30));
		// More lines changed than the layout notes one by one.
		const keys = Array.from({ length: 100 }, (_, i) => (i % 2 === 0 ? UP : String.fromCharCode(97 + (i % 26))));
		for (const key of keys) {
			live.handleInput(key);
			reference.handleInput(key);
		}
		assert.deepStrictEqual(live.render(30), reference.render(30));
		assert.strictEqual(live.getText(), live.getLines().join("\n"));
	});

	it("wraps the end of a long line again when it is edited", () => {
		const long = Array.from({ length: 60 }, (_, i) => `word${i}`).join(" ");
		const cjk = "日本語のテキストと漢字".repeat(30);
		const mixed = Array.from({ length: 40 }, (_, i) => (i % 3 === 0 ? "😀 café" : `été${i}`)).join(" ");
		const pasted = Array.from({ length: 20 }, (_, i) => `pasted ${i}`).join("\n");
		for (const text of [long, cjk, mixed]) {
			for (const width of [20, 9, 2, 1]) {
				compareSteps(text, width, [
					"a",
					" ",
					"b",
					BACKSPACE,
					BACKSPACE,
					BACKSPACE,
					...repeat(7, LEFT),
					"c",
					" ",
					BACKSPACE,
					DELETE,
					...repeat(40, LEFT),
					"d",
					" ",
					`\x1b[200~${pasted}\x1b[201~`,
					"e",
					LINE_END,
					`\x1b[200~${pasted}\x1b[201~`,
					"f",
					UP,
					"g",
					RIGHT,
					BACKSPACE,
					UNDO,
				]);
			}
		}
	});

	it("draws what an editor that lays out from scratch draws, for random edits", () => {
		const keys = ["a", " ", "日", "😀", NEW_LINE, BACKSPACE, DELETE, UP, DOWN, LEFT, RIGHT, UNDO, KILL_LINE, YANK];
		const texts = [MANY_LINES, `${LONG} ${LONG} ${LONG}`, "short\n\n\nlines", ""];
		let state = 7;
		const next = (count: number) => {
			state = (state * 1103515245 + 12345) % 2147483648;
			return Math.floor((state / 2147483648) * count);
		};
		const terminal: Terminal = { rows: 30, columns: 40 };
		const { live, reference } = createPair(terminal);
		const log: string[] = [];
		for (let step = 0; step < 1500; step++) {
			const choice = next(20);
			let action: (editor: Editor) => void;
			if (choice < 14) {
				const key = keys[next(keys.length)]!;
				action = (editor) => editor.handleInput(key);
				log.push(JSON.stringify(key));
			} else if (choice < 15) {
				const text = texts[next(texts.length)]!;
				action = (editor) => editor.setText(text);
				log.push(`setText(${text.length})`);
			} else if (choice < 16) {
				const lines = next(15) + 1;
				const paste = Array.from({ length: lines }, (_, i) => `p${i}`).join("\n");
				action = (editor) => editor.handleInput(`\x1b[200~${paste}\x1b[201~`);
				log.push(`paste(${lines})`);
			} else if (choice < 17) {
				terminal.columns = [40, 25, 12, 60][next(4)]!;
				action = () => {};
				log.push(`width ${terminal.columns}`);
			} else {
				// No render: changes add up.
				const key = keys[next(keys.length)]!;
				live.handleInput(key);
				reference.handleInput(key);
				log.push(`${JSON.stringify(key)} (no render)`);
				continue;
			}
			action(live);
			action(reference);
			const label = log.slice(-10).join(" ");
			assert.deepStrictEqual(live.render(terminal.columns), reference.render(terminal.columns), label);
			assert.strictEqual(live.getText(), reference.getText(), label);
			assert.strictEqual(live.getText(), live.getLines().join("\n"), label);
			assert.deepStrictEqual(live.getCursor(), reference.getCursor(), label);
		}
	});

	it("wraps only the changed lines again", () => {
		const terminal: Terminal = { rows: 40, columns: 80 };
		const tui = { requestRender: () => {}, terminal } as unknown as TUI;
		const editor = new Editor(tui, defaultEditorTheme);
		const wrapped: string[] = [];
		const internals = editor as unknown as { wrapLine(line: string, width: number): unknown };
		const wrapLine = internals.wrapLine.bind(editor);
		internals.wrapLine = (line: string, width: number) => {
			wrapped.push(line);
			return wrapLine(line, width);
		};
		const lines = Array.from({ length: 2000 }, (_, i) => `line number ${i} with some words`);
		editor.setText(lines.join("\n"));
		editor.render(80);
		assert.strictEqual(wrapped.length, 2000);

		const step = (keys: string[]): string[] => {
			wrapped.length = 0;
			for (const key of keys) editor.handleInput(key);
			editor.render(80);
			return wrapped.slice();
		};
		assert.deepStrictEqual(step(repeat(1000, UP)), [], "moving the cursor lays nothing out");
		assert.deepStrictEqual(step(["x"]), ["line number 999 with some wordsx"]);
		assert.deepStrictEqual(step([LEFT, LEFT, NEW_LINE]), ["line number 999 with some word", "sx"]);
		assert.deepStrictEqual(step([BACKSPACE]), ["line number 999 with some wordsx"]);
		// Undo replaces all lines; those that are the same as before, also after the changed one, are kept.
		for (let undo = 0; undo < 3; undo++) {
			assert.ok(step([UNDO]).length <= 2, `undo ${undo}`);
		}
		assert.strictEqual(editor.getText(), lines.join("\n"));
	});
});
