import { fauxAssistantMessage } from "@earendil-works/pi-ai";
import { afterEach, describe, expect, it } from "vitest";
import { createHarness, type Harness } from "./harness.ts";

// Auto-title: after the first agent run settles, an unnamed session is named
// by a one-shot titling request. The titling call goes through
// agent.streamFunction, so tests replace it with a scripted two-call stream:
// call 1 answers the agent turn, call 2 answers the titling request.
describe("AgentSession auto-title", () => {
	const harnesses: Harness[] = [];
	afterEach(() => {
		while (harnesses.length) harnesses.pop()?.cleanup();
	});

	function scriptTitles(harness: Harness, turnText: string, titleText: string | null): string[] {
		const titlingPrompts: string[] = [];
		let calls = 0;
		harness.session.agent.streamFunction = (model, context, options) => {
			calls += 1;
			const isTitling =
				JSON.stringify(context.messages[0] ?? {}).includes("ONLY a title") && context.messages.length === 2;
			const stream = (
				calls === 1
					? fauxAssistantMessage(turnText)
					: titleText === null
						? fauxAssistantMessage("", { stopReason: "error", errorMessage: "provider down" })
						: fauxAssistantMessage(titleText)
			) as never;
			if (isTitling) titlingPrompts.push(JSON.stringify(context.messages));
			const eventStream = {
				[Symbol.asyncIterator]: async function* () {
					yield { type: "done", reason: "stop", message: stream };
				},
				result: async () => stream,
			};
			void model;
			void options;
			return eventStream as never;
		};
		return titlingPrompts;
	}

	it("names an unnamed session after the first run settles", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1" }],
			tools: [],
			settings: { autoTitle: { enabled: true } },
		});
		harnesses.push(harness);
		const titlingPrompts = scriptTitles(harness, "done", "Fix Login Redirect Loop");

		await harness.session.prompt("Why does my login page redirect in a loop after upgrading?");

		expect(harness.sessionManager.getSessionName()).toBe("Fix Login Redirect Loop");
		expect(titlingPrompts).toHaveLength(1);
		// The titling request carries the user prompt, not the whole transcript.
		expect(titlingPrompts[0]).toContain("redirect in a loop");
		// Persisted as the same entry /name writes.
		const info = harness.sessionManager.getEntries().find((e) => e.type === "session_info");
		expect((info as { name?: string }).name).toBe("Fix Login Redirect Loop");
	});

	it("runs at most once per session runtime", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1" }],
			tools: [],
			settings: { autoTitle: { enabled: true } },
		});
		harnesses.push(harness);
		const titlingPrompts = scriptTitles(harness, "done", "First Title");

		await harness.session.prompt("Why does my login page redirect in a loop after upgrading?");
		await harness.session.prompt("And also fix the header color");

		expect(harness.sessionManager.getSessionName()).toBe("First Title");
		expect(titlingPrompts).toHaveLength(1);
	});

	it("never overwrites a name set before the run (--name, /name)", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1" }],
			tools: [],
			settings: { autoTitle: { enabled: true } },
		});
		harnesses.push(harness);
		const titlingPrompts = scriptTitles(harness, "done", "Should Not Appear");
		harness.session.setSessionName("Keep Me");

		await harness.session.prompt("Why does my login page redirect in a loop after upgrading?");

		expect(harness.sessionManager.getSessionName()).toBe("Keep Me");
		expect(titlingPrompts).toHaveLength(0);
	});

	it("skips titling when autoTitle.enabled is false", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1" }],
			tools: [],
			settings: { autoTitle: { enabled: false } },
		});
		harnesses.push(harness);
		const titlingPrompts = scriptTitles(harness, "done", "Should Not Appear");

		await harness.session.prompt("Why does my login page redirect in a loop after upgrading?");

		expect(harness.sessionManager.getSessionName()).toBeUndefined();
		expect(titlingPrompts).toHaveLength(0);
	});

	it("leaves the session unnamed when the titling request fails", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1" }],
			tools: [],
			settings: { autoTitle: { enabled: true } },
		});
		harnesses.push(harness);
		scriptTitles(harness, "done", null);

		await harness.session.prompt("Why does my login page redirect in a loop after upgrading?");

		expect(harness.sessionManager.getSessionName()).toBeUndefined();
	});

	it("rejects a title that just echoes the prompt opening", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1" }],
			tools: [],
			settings: { autoTitle: { enabled: true } },
		});
		harnesses.push(harness);
		scriptTitles(harness, "done", "Why does my login page redirect in a loop after upgrading today");

		await harness.session.prompt("Why does my login page redirect in a loop after upgrading?");

		expect(harness.sessionManager.getSessionName()).toBeUndefined();
	});
});
