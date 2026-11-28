import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import { createHarness, type Harness } from "../harness.ts";

// upstream #12399: under --tools, registering any tool re-activated every allowlisted tool, undoing a restriction an
// extension had set with setActiveTools() (read-only or plan mode).
describe("issue #12399 registering a tool keeps the active tool restriction", () => {
	const harnesses: Harness[] = [];

	afterEach(() => {
		while (harnesses.length > 0) harnesses.pop()?.cleanup();
	});

	it("does not re-enable allowlisted tools an extension turned off", async () => {
		const seen: Record<string, string[]> = {};
		const harness = await createHarness({
			initialActiveToolNames: ["read", "grep", "later"],
			allowedToolNames: ["read", "grep", "later"],
			extensionFactories: [
				(pi) => {
					pi.on("session_start", () => {
						pi.setActiveTools(["read"]);
						seen.before = pi.getActiveTools();
						pi.registerTool({
							name: "later",
							label: "later",
							description: "Registered at runtime.",
							parameters: Type.Object({}),
							execute: async () => ({ content: [], details: {} }),
						});
						seen.after = pi.getActiveTools();
					});
				},
			],
		});
		harnesses.push(harness);
		await harness.session.bindExtensions({});

		expect(seen.before).toEqual(["read"]);
		// The new tool is allowlisted and new, so it activates; grep stays off.
		expect(seen.after).toEqual(["read", "later"]);
		expect(harness.session.getActiveToolNames()).toEqual(["read", "later"]);
	});
});
