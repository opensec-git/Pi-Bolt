import assert from "node:assert";
import { describe, it } from "node:test";
import { KeybindingsManager, TUI_KEYBINDINGS } from "../src/keybindings.ts";
import { isKeyRelease, type KeyId, matchesKey, parseKey, setKittyProtocolActive } from "../src/keys.ts";

describe("parsed key cache", () => {
	it("follows remapped keybindings", () => {
		const keybindings = new KeybindingsManager(TUI_KEYBINDINGS);
		assert.strictEqual(keybindings.matches("\x01", "tui.editor.cursorLineStart"), true, "ctrl+a by default");
		assert.strictEqual(keybindings.matches("\x1b[97;5u", "tui.editor.cursorLineStart"), true);

		keybindings.setUserBindings({ "tui.editor.cursorLineStart": "ctrl+g" });
		assert.strictEqual(keybindings.matches("\x01", "tui.editor.cursorLineStart"), false, "ctrl+a no longer");
		assert.strictEqual(keybindings.matches("\x07", "tui.editor.cursorLineStart"), true, "ctrl+g now");
		assert.strictEqual(keybindings.matches("\x1b[103;5u", "tui.editor.cursorLineStart"), true);

		keybindings.setUserBindings({ "tui.editor.cursorLineStart": ["alt+shift+x", "f5"] });
		assert.strictEqual(keybindings.matches("\x07", "tui.editor.cursorLineStart"), false);
		assert.strictEqual(keybindings.matches("\x1b[120;4u", "tui.editor.cursorLineStart"), true, "alt+shift+x");
		assert.strictEqual(keybindings.matches("\x1b[15~", "tui.editor.cursorLineStart"), true, "f5");

		keybindings.setUserBindings({});
		assert.strictEqual(keybindings.matches("\x01", "tui.editor.cursorLineStart"), true, "defaults again");
	});

	it("matches the same input against many keys, and the next input afresh", () => {
		const sequence = "\x1b[99;5u"; // ctrl+c, Kitty
		for (let round = 0; round < 3; round++) {
			assert.strictEqual(matchesKey(sequence, "ctrl+c"), true);
			assert.strictEqual(matchesKey(sequence, "ctrl+d"), false);
			assert.strictEqual(matchesKey(sequence, "Ctrl+C" as KeyId), true);
			assert.strictEqual(matchesKey(sequence, "shift+ctrl+c"), false);
			assert.strictEqual(matchesKey("\x1b[100;5u", "ctrl+d"), true);
			assert.strictEqual(matchesKey("\x1b[27;5;99~", "ctrl+c"), true, "modifyOtherKeys");
			assert.strictEqual(matchesKey("\x1b[27;5;100~", "ctrl+c"), false);
			assert.strictEqual(parseKey(sequence), "ctrl+c");
		}
	});

	it("reports the event type of a repeated input each time it is parsed", () => {
		setKittyProtocolActive(true);
		try {
			const release = "\x1b[97;1:3u";
			assert.strictEqual(isKeyRelease(release), true);
			assert.strictEqual(matchesKey(release, "a"), true);
			assert.strictEqual(matchesKey(release, "a"), true);
			assert.strictEqual(isKeyRelease("\x1b[97;1:1u"), false);
		} finally {
			setKittyProtocolActive(false);
		}
	});

	it("depends on the Kitty protocol state at match time, not when the key was first parsed", () => {
		assert.strictEqual(matchesKey("\x1b\r", "alt+enter"), true, "legacy alt+enter");
		setKittyProtocolActive(true);
		try {
			assert.strictEqual(matchesKey("\x1b\r", "alt+enter"), false, "with Kitty, alt+enter is a CSI u sequence");
			assert.strictEqual(matchesKey("\x1b\r", "shift+enter"), true);
		} finally {
			setKittyProtocolActive(false);
		}
		assert.strictEqual(matchesKey("\x1b\r", "alt+enter"), true);
	});

	it("remembers typed input per key, and matches it again when the Kitty flag changes", () => {
		for (let round = 0; round < 3; round++) {
			assert.strictEqual(matchesKey("a", "a"), true);
			assert.strictEqual(matchesKey("A", "shift+a"), true);
			assert.strictEqual(matchesKey("a", "shift+a"), false);
			assert.strictEqual(matchesKey("\x01", "ctrl+a"), true);
		}
		// Legacy ctrl+space is NUL only without the Kitty protocol: typed input that depends on the flag.
		assert.strictEqual(matchesKey("\x00", "ctrl+space"), true);
		setKittyProtocolActive(true);
		try {
			assert.strictEqual(matchesKey("\x00", "ctrl+space"), false);
		} finally {
			setKittyProtocolActive(false);
		}
		assert.strictEqual(matchesKey("\x00", "ctrl+space"), true);
	});
});
