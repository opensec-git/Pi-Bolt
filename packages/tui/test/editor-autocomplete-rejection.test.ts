// Upstream #12566 (claim T6): a provider whose completion rejects must not break autocomplete or leave a rejection
// unhandled. Requests are chained on the previous one, so one rejected request used to reject every later request
// before it reached the provider, and each of them was an unhandled rejection (the Bun runtime exits on those).
import assert from "node:assert";
import { describe, it } from "node:test";
import { type AutocompleteProvider, CombinedAutocompleteProvider } from "../src/autocomplete.ts";
import { Editor } from "../src/components/editor.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { defaultEditorTheme } from "./test-themes.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

async function flushAutocomplete(): Promise<void> {
	for (let i = 0; i < 5; i++) await new Promise((resolve) => setImmediate(resolve));
}

async function withUnhandledRejections(run: (unhandled: unknown[]) => Promise<void>): Promise<void> {
	const unhandled: unknown[] = [];
	const onUnhandled = (reason: unknown) => unhandled.push(reason);
	process.on("unhandledRejection", onUnhandled);
	try {
		await run(unhandled);
		// Unhandled rejections are reported after the microtask queue drains.
		await flushAutocomplete();
		assert.deepStrictEqual(unhandled, []);
	} finally {
		process.off("unhandledRejection", onUnhandled);
	}
}

function createEditor(): Editor {
	return new Editor(new TuiMainScreen(new VirtualTerminal(80, 24)), defaultEditorTheme);
}

describe("Editor autocomplete after a provider rejection (upstream #12566)", () => {
	it("asks the provider again and shows its suggestions", async () => {
		await withUnhandledRejections(async () => {
			const editor = createEditor();
			let calls = 0;
			const provider: AutocompleteProvider = {
				getSuggestions: async () => {
					calls++;
					if (calls === 1) throw new Error("provider failed once");
					return { items: [{ value: "/help", label: "help" }], prefix: "/" };
				},
				applyCompletion: (lines, cursorLine, cursorCol) => ({ lines, cursorLine, cursorCol }),
			};
			editor.setAutocompleteProvider(provider);

			editor.handleInput("/");
			await flushAutocomplete();
			assert.strictEqual(calls, 1);
			assert.strictEqual(editor.isShowingAutocomplete(), false);

			editor.handleInput("h");
			await flushAutocomplete();
			assert.strictEqual(calls, 2, "the provider is asked again after one rejection");
			assert.strictEqual(editor.isShowingAutocomplete(), true);
		});
	});

	it("closes an open list when a later request rejects", async () => {
		await withUnhandledRejections(async () => {
			const editor = createEditor();
			let calls = 0;
			const provider: AutocompleteProvider = {
				getSuggestions: async () => {
					calls++;
					if (calls === 2) throw new Error("provider failed on the second request");
					return { items: [{ value: "/help", label: "help" }], prefix: "/" };
				},
				applyCompletion: (lines, cursorLine, cursorCol) => ({ lines, cursorLine, cursorCol }),
			};
			editor.setAutocompleteProvider(provider);

			editor.handleInput("/");
			await flushAutocomplete();
			assert.strictEqual(editor.isShowingAutocomplete(), true);

			editor.handleInput("h");
			await flushAutocomplete();
			assert.strictEqual(calls, 2);
			assert.strictEqual(editor.isShowingAutocomplete(), false, "a rejection counts as no suggestions");
		});
	});

	it("survives a slash command whose getArgumentCompletions rejects", async () => {
		await withUnhandledRejections(async () => {
			const editor = createEditor();
			let calls = 0;
			editor.setAutocompleteProvider(
				new CombinedAutocompleteProvider(
					[
						{
							name: "boom",
							getArgumentCompletions: async (prefix) => {
								calls++;
								if (calls === 1) throw new Error("boom-from-completions");
								return [{ value: `${prefix}one`, label: "one" }];
							},
						},
					],
					process.cwd(),
				),
			);

			editor.setText("/boom ");
			editor.handleInput("a");
			await flushAutocomplete();
			assert.strictEqual(calls, 1);

			editor.handleInput("b");
			await flushAutocomplete();
			assert.strictEqual(calls, 2, "the next keystroke asks the command again");
			assert.strictEqual(editor.isShowingAutocomplete(), true);

			editor.handleInput("\t");
			assert.strictEqual(editor.getText(), "/boom abone");
		});
	});
});
