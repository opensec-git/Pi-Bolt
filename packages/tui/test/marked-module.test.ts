import assert from "node:assert";
import { describe, test } from "node:test";
import * as markedPackage from "marked";
import { Marked } from "../src/index.ts";
import { marked, markedCjkFriendly } from "../src/marked-module.ts";

describe("marked loaded on first use", () => {
	test("marked() is the marked package", () => {
		assert.strictEqual(marked().Marked, markedPackage.Marked);
		assert.strictEqual(marked().Lexer, markedPackage.Lexer);
	});

	test("markedCjkFriendly() is the extension the package makes", () => {
		assert.strictEqual(typeof markedCjkFriendly().tokenizer?.emStrong, "function");
	});

	test("pi-tui's Marked constructs marked's Marked", () => {
		const parser = new Marked();
		assert.ok(parser instanceof markedPackage.Marked);
		assert.ok(parser instanceof Marked);
		assert.strictEqual(Object.getPrototypeOf(parser), markedPackage.Marked.prototype);
		assert.strictEqual(parser.parse("**bold**"), new markedPackage.Marked().parse("**bold**"));
	});

	test("pi-tui's Marked can be extended and read like marked's", () => {
		class Counting extends Marked {
			lexed = 0;
			override lexer(...args: Parameters<markedPackage.Marked["lexer"]>): ReturnType<markedPackage.Marked["lexer"]> {
				this.lexed++;
				return super.lexer(...args);
			}
		}
		const parser = new Counting();
		assert.ok(parser instanceof markedPackage.Marked);
		assert.strictEqual(parser.lexer("# title")[0]?.type, "heading");
		assert.strictEqual(parser.lexed, 1);
		assert.strictEqual(Marked.prototype, markedPackage.Marked.prototype);
		assert.ok("prototype" in Marked);
		assert.throws(() => Reflect.apply(Marked, undefined, []), TypeError);
	});

	test("pi-tui's Marked has the statics of marked's: written, listed, described and deleted on the class itself", () => {
		const real = markedPackage.Marked as unknown as Record<string, unknown>;
		const exported = Marked as unknown as Record<string, unknown>;
		exported.piTuiStatic = 1;
		try {
			assert.strictEqual(real.piTuiStatic, 1);
			assert.strictEqual(exported.piTuiStatic, 1);
			assert.ok("piTuiStatic" in Marked);
			assert.deepStrictEqual(Object.keys(Marked), Object.keys(markedPackage.Marked));
			assert.ok(Object.keys(Marked).includes("piTuiStatic"));
			assert.deepStrictEqual(Reflect.ownKeys(Marked), Reflect.ownKeys(markedPackage.Marked));
			assert.deepStrictEqual(
				Object.getOwnPropertyDescriptor(Marked, "piTuiStatic"),
				Object.getOwnPropertyDescriptor(markedPackage.Marked, "piTuiStatic"),
			);
			assert.deepStrictEqual(
				Object.getOwnPropertyDescriptor(Marked, "name"),
				Object.getOwnPropertyDescriptor(markedPackage.Marked, "name"),
			);
			assert.strictEqual(
				Object.getOwnPropertyDescriptor(Marked, "prototype")?.value,
				markedPackage.Marked.prototype,
			);
			Object.defineProperty(Marked, "piTuiDefined", { value: 2, configurable: true });
			assert.strictEqual(real.piTuiDefined, 2);
			assert.ok(delete exported.piTuiDefined);
			assert.strictEqual(real.piTuiDefined, undefined);
		} finally {
			delete exported.piTuiStatic;
		}
		assert.ok(!("piTuiStatic" in markedPackage.Marked));
		assert.strictEqual(Object.getOwnPropertyDescriptor(Marked, "piTuiStatic"), undefined);
	});
});
