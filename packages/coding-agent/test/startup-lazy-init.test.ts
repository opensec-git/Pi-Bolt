// What a start evaluates is kept to what it uses: these objects are made, and these packages evaluated, on first use, not
// while Pi's modules load. Importing main.ts evaluates the modules every start evaluates before main() runs.
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Markdown, Marked, visibleWidth } from "@earendil-works/pi-tui";
import type * as TypeboxCompile from "typebox/compile";
import { Compile } from "typebox/compile";
import { afterAll, expect, it, vi } from "vitest";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import { main } from "../src/main.ts";

const created = vi.hoisted(() => {
	const counts = { segmenters: [] as string[], packages: [] as string[] };
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

// A package an imported module still imports statically is evaluated through these. (Loaded on first use, a package is
// required from node_modules instead, past the mocks.)
vi.mock("marked", async (importOriginal) => {
	created.packages.push("marked");
	return importOriginal();
});
vi.mock("marked-cjk-friendly", async (importOriginal) => {
	created.packages.push("marked-cjk-friendly");
	return importOriginal();
});
vi.mock("semver", async (importOriginal) => {
	created.packages.push("semver");
	return importOriginal();
});
vi.mock("hosted-git-info", async (importOriginal) => {
	created.packages.push("hosted-git-info");
	return importOriginal();
});
vi.mock("chalk", async (importOriginal) => {
	created.packages.push("chalk");
	return importOriginal();
});
vi.mock("node:crypto", async (importOriginal) => {
	created.packages.push("node:crypto");
	return importOriginal();
});

const agentDir = mkdtempSync(join(tmpdir(), "pi-startup-lazy-"));
afterAll(() => rmSync(agentDir, { recursive: true, force: true }));

it("evaluates Pi's modules without making segmenters, the Markdown parser or the keybinding checker", () => {
	expect(main).toBeTypeOf("function");
	expect(created.segmenters).toEqual([]);
	expect(Compile).not.toHaveBeenCalled();
});

it("evaluates Pi's modules without marked, semver, hosted-git-info, chalk or node:crypto", () => {
	expect(main).toBeTypeOf("function");
	expect(created.packages).toEqual([]);
});

it("makes each of them on first use", () => {
	expect(visibleWidth("👨\u200d👩\u200d👧")).toBe(2);
	expect(created.segmenters).toEqual(["grapheme"]);

	// (Reading Marked's prototype evaluates marked: the parser is set up once, at the first render.)
	const setOptions = vi.spyOn(Marked.prototype, "setOptions");
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
	expect(setOptions).toHaveBeenCalledTimes(1);

	writeFileSync(
		join(agentDir, "keybindings.json"),
		JSON.stringify({ "app.exit": "ctrl+q", "app.clear": 42, "app.interrupt": ["escape", "ctrl+["] }),
	);
	const keybindings = KeybindingsManager.create(agentDir);
	// The checker is TypeBox's, generated at build time (config-checks.generated.ts): nothing is compiled at run time.
	expect(Compile).not.toHaveBeenCalled();
	expect(keybindings.getKeys("app.exit")).toEqual(["ctrl+q"]);
	expect(keybindings.getKeys("app.interrupt")).toEqual(["escape", "ctrl+["]);
	// A value that is not a key identifier is dropped, as the checker decides.
	expect(keybindings.getKeys("app.clear")).toEqual(["ctrl+c"]);
	KeybindingsManager.create(agentDir);
	expect(Compile).not.toHaveBeenCalled();
});
