import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { type Context, createContext, runInContext } from "node:vm";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { exportFromFile } from "../src/core/export-html/index.ts";
import { CURRENT_SESSION_VERSION, type SessionHeader, type SessionMessageEntry } from "../src/core/session-manager.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

/** The viewer script of an exported page, run up to its tree helpers, which it leaves in the context as `api`. */
function loadViewer(html: string): Context {
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
	context.api = runInContext(
		viewer.replace(
			"let treeNodeMap = null;",
			"let treeNodeMap = null; return { buildTree, buildActivePathIds, getPath, isLeadingSystemEntry, byId };",
		),
		context,
		{ timeout: 5000 },
	);
	return context;
}

// A damaged session whose parent links form a cycle: every walk in the viewer follows parent links, so before the cut a
// walk never ended. Calls run with a timeout so that such a loop fails the test instead of hanging it.
describe("HTML export of a session whose parent links form a cycle", () => {
	let tempDir: string;
	let viewer: Context;
	const run = (code: string) => JSON.parse(runInContext(`JSON.stringify(${code})`, viewer, { timeout: 2000 }));

	beforeAll(async () => {
		initTheme("dark");
		tempDir = mkdtempSync(join(tmpdir(), "pi-export-cycle-"));
		const header = {
			type: "session",
			version: CURRENT_SESSION_VERSION,
			id: "cycle-test",
			timestamp: new Date(0).toISOString(),
			cwd: tempDir,
		} satisfies SessionHeader;
		const message = (id: string, parentId: string | null, at: number): SessionMessageEntry => ({
			type: "message",
			id,
			parentId,
			timestamp: new Date(at * 1000).toISOString(),
			message: { role: "user", content: id, timestamp: at * 1000 },
		});
		// e1 and e2 name each other; e3 hangs below the cycle.
		const entries = [message("r", null, 0), message("e1", "e2", 1), message("e2", "e1", 2), message("e3", "e2", 3)];
		const sessionPath = join(tempDir, "session.jsonl");
		writeFileSync(sessionPath, `${[header, ...entries].map((entry) => JSON.stringify(entry)).join("\n")}\n`);
		viewer = loadViewer(readFileSync(await exportFromFile(sessionPath, join(tempDir, "out.html")), "utf8"));
	});

	afterAll(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	it("cuts the cycle where the session manager cuts it", () => {
		expect(run(`api.getPath("e3").map((entry) => entry.id)`)).toEqual(["e1", "e2", "e3"]);
		expect(run(`[...api.buildActivePathIds("e3")]`)).toEqual(["e3", "e2", "e1"]);
		expect(run(`api.isLeadingSystemEntry(api.byId.get("e3"))`)).toBe(true);
		const tree = `(function tree(nodes) { return nodes.map((node) => [node.entry.id, tree(node.children)]); })(api.buildTree())`;
		expect(run(tree)).toEqual([
			["r", []],
			["e1", [["e2", [["e3", []]]]]],
		]);
	});
});
