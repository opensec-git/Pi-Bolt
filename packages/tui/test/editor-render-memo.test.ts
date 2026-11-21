import assert from "node:assert";
import { describe, it } from "node:test";
import type { AutocompleteProvider } from "../src/autocomplete.ts";
import { Editor } from "../src/components/editor.ts";
import type { TUI } from "../src/tui.ts";
import { defaultEditorTheme } from "./test-themes.ts";

// While a reply streams in, the editor is drawn on every frame with nothing changed: render() keeps the text lines it drew
// with everything they were drawn from (text, cursor, scroll position, width, padding, focus, terminal height, paste IDs)
// and draws only the borders again. Whatever changes, what it draws must be what the editor draws with nothing kept.

interface Terminal {
	rows: number;
	columns: number;
}

function createTui(terminal: Terminal): TUI {
	return { requestRender: () => {}, terminal } as unknown as TUI;
}

/** Deterministic pseudo-random numbers. */
function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state * 1103515245 + 12345) % 2147483648;
		return state / 2147483648;
	};
}

const KEYS = [
	"a",
	"Z",
	" ",
	"é",
	"日",
	"😀",
	"\r", // Enter: submits (and clears) the text
	"\x1b\r", // Alt+Enter: new line
	"\x7f",
	"\x1b[D",
	"\x1b[C",
	"\x1b[A",
	"\x1b[B",
	"\x01",
	"\x05",
	"\x0b", // Ctrl+K: kill to end of line
	"\x19", // Ctrl+Y: yank
	"\x1b[45;5u", // undo
	"\x1b[5~", // Page Up
	"\x1b[6~", // Page Down
];
const TEXTS = [
	"",
	"one line",
	"two\nlines",
	"a long line that wraps ".repeat(12),
	"many\n".repeat(30),
	"日本語のテキスト ".repeat(20),
];

/** The steps: each changes one thing render() depends on, or nothing. */
type Step = (editor: Editor, terminal: Terminal, draw: (count: number) => number) => void;

const STEPS: Step[] = [
	() => {},
	() => {},
	(editor, _terminal, draw) => editor.handleInput(KEYS[draw(KEYS.length)]!),
	(editor, _terminal, draw) => editor.handleInput(KEYS[draw(KEYS.length)]!),
	(editor, _terminal, draw) => editor.handleInput(KEYS[draw(KEYS.length)]!),
	(editor, _terminal, draw) => editor.setText(TEXTS[draw(TEXTS.length)]!),
	(editor, _terminal, draw) => {
		// A paste large enough to be shown as a marker.
		editor.handleInput(`\x1b[200~${"pasted line\n".repeat(5 + draw(20))}\x1b[201~`);
	},
	(editor) => {
		editor.focused = !editor.focused;
	},
	(editor, _terminal, draw) => editor.setPaddingX(draw(4)),
	(_editor, terminal, draw) => {
		terminal.rows = [40, 12, 6, 3, 1, 60][draw(6)]!;
	},
	(_editor, terminal, draw) => {
		terminal.columns = [80, 120, 40, 20, 12, 9, 200][draw(7)]!;
	},
	(editor, _terminal, draw) => {
		const color = ["31", "32", "33"][draw(3)]!;
		editor.borderColor = (text) => `\x1b[${color}m${text}\x1b[39m`;
	},
	(editor) => {
		// Up through the history and back.
		editor.addToHistory(`history ${editor.getText().length}`);
		editor.handleInput("\x1b[A");
	},
];

function driveAndCompare(seed: number, paddingX: number): void {
	const terminal: Terminal = { rows: 40, columns: 80 };
	const live = new Editor(createTui(terminal), defaultEditorTheme, { paddingX });
	const reference = new Editor(createTui(terminal), defaultEditorTheme, { paddingX });
	live.focused = reference.focused = true;
	const next = random(seed);
	const log: string[] = [];
	for (let index = 0; index < 800; index++) {
		const stepIndex = Math.floor(next() * STEPS.length);
		const draws: number[] = [];
		STEPS[stepIndex]!(live, terminal, (count) => {
			const value = Math.floor(next() * count);
			draws.push(value);
			return value;
		});
		let replayed = 0;
		// (The terminal is shared: it changed for both.)
		if (stepIndex !== 9 && stepIndex !== 10) STEPS[stepIndex]!(reference, terminal, () => draws[replayed++]!);
		log.push(`${stepIndex}${draws.length > 0 ? `(${draws.join(",")})` : ""}`);
		const label = `seed ${seed}, padding ${paddingX}: ${log.slice(-12).join(" ")}`;

		const drawn = live.render(terminal.columns);
		// What a caller does with the lines it is given is not seen by the next render.
		const copy = drawn.slice();
		drawn.push("changed");
		drawn[0] = "changed";
		reference.invalidate();
		assert.deepStrictEqual(copy, reference.render(terminal.columns), label);
		assert.strictEqual(live.getText(), reference.getText(), label);
		assert.deepStrictEqual(live.getCursor(), reference.getCursor(), label);
	}
}

describe("Editor render memo", () => {
	for (const paddingX of [0, 1, 2]) {
		for (const seed of [1, 2, 3, 4]) {
			it(`draws what an editor that keeps nothing draws (padding ${paddingX}, seed ${seed})`, () => {
				driveAndCompare(seed * 7919 + paddingX, paddingX);
			});
		}
	}

	it("keeps the text lines and draws the borders on every render", () => {
		const terminal: Terminal = { rows: 40, columns: 60 };
		let tick = 0;
		const layouts: number[] = [];
		class TickingBorderEditor extends Editor {
			protected override renderTopBorder(width: number, hiddenLineCount: number): string {
				// A border that changes on every render, as an animated status does.
				return `${tick++}`.padEnd(width, "-") + (hiddenLineCount > 0 ? "^" : "");
			}
		}
		const editor = new TickingBorderEditor(createTui(terminal), defaultEditorTheme);
		const layoutText = (editor as unknown as { layoutText(width: number): unknown }).layoutText.bind(editor);
		(editor as unknown as { layoutText(width: number): unknown }).layoutText = (width: number) => {
			layouts.push(width);
			return layoutText(width);
		};
		editor.setText("hello\nworld");
		const first = editor.render(60);
		const second = editor.render(60);
		assert.strictEqual(layouts.length, 1, "the text is laid out once");
		assert.notStrictEqual(first, second, "each render returns an array of its own");
		assert.strictEqual(first[0], `0${"-".repeat(59)}`);
		assert.strictEqual(second[0], `1${"-".repeat(59)}`);
		assert.deepStrictEqual(first.slice(1), second.slice(1));
		editor.handleInput("!");
		assert.ok(editor.render(60)[2]!.includes("!"));
		assert.strictEqual(layouts.length, 2, "an edit lays the text out again");
	});

	it("applies the current border style to a reused rule", () => {
		const editor = new Editor(createTui({ rows: 40, columns: 20 }), defaultEditorTheme);
		let color = "\x1b[31m";
		editor.borderColor = (text) => `${color + text}\x1b[39m`;
		assert.strictEqual(editor.render(20)[0], `${color + "─".repeat(20)}\x1b[39m`);
		color = "\x1b[32m";
		const lines = editor.render(20);
		assert.strictEqual(lines[0], `${color + "─".repeat(20)}\x1b[39m`);
		assert.strictEqual(lines.at(-1), lines[0]);
		assert.strictEqual(editor.render(12)[0], `${color + "─".repeat(12)}\x1b[39m`);
	});

	it("lays the text out again when the paste IDs change and the text does not", () => {
		// A paste marker wraps as one unit while its paste is known; setText() forgets the pastes and keeps the marker's text.
		const terminal: Terminal = { rows: 40, columns: 24 };
		const editor = new Editor(createTui(terminal), defaultEditorTheme);
		const reference = new Editor(createTui(terminal), defaultEditorTheme);
		for (const key of "abcdef ") editor.handleInput(key);
		editor.handleInput(`\x1b[200~${"pasted line\n".repeat(20)}\x1b[201~`);
		const text = editor.getText();
		assert.match(text, /\[paste #1/);
		const withPaste = editor.render(24);
		editor.setText(text);
		reference.setText(text);
		const withoutPaste = editor.render(24);
		assert.deepStrictEqual(withoutPaste, reference.render(24));
		assert.notDeepStrictEqual(withoutPaste, withPaste);
	});

	it("does not keep lines while the autocomplete list is shown", async () => {
		const terminal: Terminal = { rows: 40, columns: 60 };
		const editor = new Editor(createTui(terminal), defaultEditorTheme);
		const provider: AutocompleteProvider = {
			getSuggestions: async (lines, _cursorLine, cursorCol) => {
				const prefix = (lines[0] ?? "").slice(0, cursorCol);
				return prefix === "di"
					? {
							items: [
								{ value: "dist/", label: "dist/" },
								{ value: "dir/", label: "dir/" },
								{ value: "diff/", label: "diff/" },
							],
							prefix: "di",
						}
					: null;
			},
			applyCompletion: (lines, cursorLine, cursorCol, item, prefix) => {
				const line = lines[cursorLine] ?? "";
				const updated = line.slice(0, cursorCol - prefix.length) + item.value + line.slice(cursorCol);
				return {
					lines: [...lines.slice(0, cursorLine), updated, ...lines.slice(cursorLine + 1)],
					cursorLine,
					cursorCol: cursorCol - prefix.length + item.value.length,
				};
			},
		};
		editor.setAutocompleteProvider(provider);
		editor.handleInput("d");
		editor.handleInput("i");
		const plain = editor.render(60);
		editor.handleInput("\t");
		await Promise.resolve();
		await new Promise((resolve) => setImmediate(resolve));
		assert.strictEqual(editor.isShowingAutocomplete(), true);
		const listed = editor.render(60);
		assert.ok(listed.length > plain.length, "the list is drawn");
		editor.handleInput("\x1b[B");
		const moved = editor.render(60);
		assert.notDeepStrictEqual(moved, listed, "the selection moved");
		editor.handleInput("\x1b");
		assert.strictEqual(editor.isShowingAutocomplete(), false);
		assert.deepStrictEqual(editor.render(60), plain);
	});
});
