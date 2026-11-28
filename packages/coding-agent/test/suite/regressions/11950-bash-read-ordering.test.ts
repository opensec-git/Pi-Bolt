import { fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import { createHarness, getToolResult, type Harness } from "../harness.ts";

function textOf(result: { content: Array<{ type: string; text?: string }> }): string {
	return result.content.map((part) => part.text ?? "").join("");
}

// upstream #11950: bash, write and edit calls are barriers among the calls of one assistant message.
describe("issue #11950 tool calls after bash see its effects", () => {
	const harnesses: Harness[] = [];

	afterEach(() => {
		while (harnesses.length > 0) harnesses.pop()?.cleanup();
	});

	it("runs a read after the bash call of the same message", async () => {
		const harness = await createHarness({ initialActiveToolNames: ["bash", "read"] });
		harnesses.push(harness);
		harness.setResponses([
			fauxAssistantMessage(
				[
					fauxToolCall("bash", { command: "sleep 0.3 && echo done > build.log" }),
					fauxToolCall("read", { path: "build.log" }),
				],
				{ stopReason: "toolUse" },
			),
			fauxAssistantMessage("ok"),
		]);

		await harness.session.prompt("build, then read the log");

		const read = getToolResult(harness, "read");
		expect(read.isError).toBe(false);
		expect(textOf(read)).toContain("done");
	});

	it("runs a read after the write call of the same message", async () => {
		const harness = await createHarness({ initialActiveToolNames: ["write", "read"] });
		harnesses.push(harness);
		harness.setResponses([
			fauxAssistantMessage(
				[
					fauxToolCall("write", { path: "notes.txt", content: "written\n" }),
					fauxToolCall("read", { path: "notes.txt" }),
				],
				{ stopReason: "toolUse" },
			),
			fauxAssistantMessage("ok"),
		]);

		await harness.session.prompt("write, then read");

		const read = getToolResult(harness, "read");
		expect(read.isError).toBe(false);
		expect(textOf(read)).toContain("written");
	});

	it("keeps nested bash calls of one tool parallel", async () => {
		// Each command waits for the other's marker file, so they only both succeed when they overlap.
		const waitFor = (own: string, other: string) =>
			`touch ${own}; for i in $(seq 40); do [ -f ${other} ] && exit 0; sleep 0.05; done; exit 1`;
		const harness = await createHarness({
			initialActiveToolNames: ["bash", "run_two"],
			extensionFactories: [
				(pi) => {
					pi.registerTool({
						name: "run_two",
						label: "run_two",
						description: "Runs two commands in parallel.",
						parameters: Type.Object({}),
						execute: async (_id, _params, _signal, _onUpdate, ctx) => {
							const outcomes = await Promise.all([
								ctx.executeTool("bash", { command: waitFor("a", "b") }),
								ctx.executeTool("bash", { command: waitFor("b", "a") }),
							]);
							const text = outcomes.map((outcome) => (outcome.isError ? "error" : "ok")).join(",");
							return { content: [{ type: "text", text }], details: {} };
						},
					});
				},
			],
		});
		harnesses.push(harness);
		await harness.session.bindExtensions({});
		harness.setResponses([
			fauxAssistantMessage([fauxToolCall("run_two", {})], { stopReason: "toolUse" }),
			fauxAssistantMessage("ok"),
		]);

		await harness.session.prompt("run both");

		expect(textOf(getToolResult(harness, "run_two"))).toBe("ok,ok");
	});
});
