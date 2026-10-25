import assert from "node:assert";
import { describe, it } from "node:test";
import { isRgiEmoji, visibleWidth } from "../src/utils.ts";

const rgiEmojiRegex = /^\p{RGI_Emoji}$/v;

describe("isRgiEmoji", () => {
	it("agrees with the RGI_Emoji regex on every single code point", () => {
		const differing: string[] = [];
		for (let cp = 0; cp <= 0x10ffff; cp++) {
			const segment = String.fromCodePoint(cp);
			if (isRgiEmoji(segment) !== rgiEmojiRegex.test(segment)) differing.push(cp.toString(16));
		}
		assert.deepStrictEqual(differing, []);
	});

	it("agrees with the RGI_Emoji regex on sequences", () => {
		const sequences = ["❤️", "☺️", "1️⃣", "🇨🇳", "👍🏽", "👨‍👩‍👧", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "✓︎", "á", "\uD83D", "\uDE00"];
		for (const segment of sequences) {
			assert.strictEqual(isRgiEmoji(segment), rgiEmojiRegex.test(segment), JSON.stringify(segment));
		}
	});

	it("keeps the widths of single code point symbols", () => {
		assert.strictEqual(visibleWidth("✓"), 1);
		assert.strictEqual(visibleWidth("✅"), 2);
		assert.strictEqual(visibleWidth("❯"), 1);
		assert.strictEqual(visibleWidth("⌚"), 2);
		assert.strictEqual(visibleWidth("😀"), 2);
		assert.strictEqual(visibleWidth("🇨"), 2);
	});
});
