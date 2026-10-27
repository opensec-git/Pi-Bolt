// What a start evaluates is kept to what it uses: these objects are made on first use, not while Pi's modules load.
// Importing main.ts evaluates the modules every start evaluates before main() runs.
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Markdown, visibleWidth } from "@earendil-works/pi-tui";
import type * as MarkedModule from "marked";
import type * as TypeboxCompile from "typebox/compile";
import { Compile } from "typebox/compile";
import { afterAll, expect, it, vi } from "vitest";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import { main } from "../src/main.ts";

const created = vi.hoisted(() => {
	const counts = { segmenters: [] as string[], markedParsers: 0 };
	const counted = new Proxy(Intl.Segmenter, {
		construct(target, args, newTarget) {
			counts.segmenters.push(String((args[1] as Intl.SegmenterOptions | undefined)?.granularity));
			return Reflect.construct(target, args, newTarget);
		},
	});
	Object.defineProperty(Intl, "Segmenter", { value: counted, configurable: true, writable: true });
	return counts;
});

vi.mock("typebox/compile", async (importOriginal) => {
	const actual = await importOriginal<typeof TypeboxCompile>();
	return { ...actual, Compile: vi.fn(actual.Compile) };
});

vi.mock("marked", async (importOriginal) => {
	const actual = await importOriginal<typeof MarkedModule>();
	class CountedMarked extends actual.Marked {
		constructor(...extensions: MarkedModule.MarkedExtension<string, string>[]) {
			super(...extensions);
			created.markedParsers++;
		}
	}
	return { ...actual, Marked: CountedMarked };
});

const agentDir = mkdtempSync(join(tmpdir(), "pi-startup-lazy-"));
afterAll(() => rmSync(agentDir, { recursive: true, force: true }));

it("evaluates Pi's modules without making segmenters, the Markdown parser or the keybinding checker", () => {
	expect(main).toBeTypeOf("function");
	expect(created.segmenters).toEqual([]);
	expect(created.markedParsers).toBe(0);
	expect(Compile).not.toHaveBeenCalled();
});

it("makes each of them on first use", () => {
	expect(visibleWidth("👨\u200d👩\u200d👧")).toBe(2);
	expect(created.segmenters).toEqual(["grapheme"]);

	const markdown = new Markdown("**bold**", 0, 0, {
		heading: (text) => text,
		link: (text) => text,
		linkUrl: (text) => text,
		code: (text) => text,
		codeBlock: (text) => text,
		codeBlockBorder: (text) => text,
		quote: (text) => text,
		quoteBorder: (text) => text,
		hr: (text) => text,
		listBullet: (text) => text,
		bold: (text) => `<b>${text}</b>`,
		italic: (text) => text,
		strikethrough: (text) => text,
		underline: (text) => text,
	});
	expect(markdown.render(40).join("\n")).toContain("<b>bold</b>");
	expect(markdown.render(30).join("\n")).toContain("<b>bold</b>");
	expect(created.markedParsers).toBe(1);

	writeFileSync(
		join(agentDir, "keybindings.json"),
		JSON.stringify({ "app.exit": "ctrl+q", "app.clear": 42, "app.interrupt": ["escape", "ctrl+["] }),
	);
	const keybindings = KeybindingsManager.create(agentDir);
	expect(Compile).toHaveBeenCalledTimes(1);
	expect(keybindings.getKeys("app.exit")).toEqual(["ctrl+q"]);
	expect(keybindings.getKeys("app.interrupt")).toEqual(["escape", "ctrl+["]);
	// A value that is not a key identifier is dropped, as the checker decides.
	expect(keybindings.getKeys("app.clear")).toEqual(["ctrl+c"]);
	KeybindingsManager.create(agentDir);
	expect(Compile).toHaveBeenCalledTimes(1);
});
