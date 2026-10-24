import type { AssistantMessage } from "@earendil-works/pi-ai";
import { type LiveLines, renderLiveLines } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, it } from "vitest";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { getMarkdownTheme, initTheme, setTheme } from "../src/modes/interactive/theme/theme.ts";

// While a reply streams in, a rebuild with the same plan as the last one (the same components but for the texts of the
// Markdown components) only gives each Markdown component its text. At every step, what the component renders must be what
// a component that builds everything from the message renders.

type Block = AssistantMessage["content"][number];

function message(
	content: Block[],
	stopReason: AssistantMessage["stopReason"],
	errorMessage?: string,
): AssistantMessage {
	return {
		role: "assistant",
		content,
		api: "openai-responses",
		provider: "openai",
		model: "gpt-4o-mini",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason,
		...(errorMessage === undefined ? {} : { errorMessage }),
		timestamp: 0,
	};
}

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

const PIECES = [
	"word ",
	"**bold** ",
	"`code` ",
	"\n",
	"\n\n",
	"- item\n",
	"1. one\n",
	"```ts\nconst a = 1;\n",
	"```\n",
	"# heading\n",
	"   ",
	"\t",
	"| a | b |\n|---|---|\n",
	"> quote\n",
	"naïve 日本語 ",
];

interface Settings {
	/** The Markdown theme both components are made with (made once: a theme change does not replace it). */
	markdown: ReturnType<typeof getMarkdownTheme>;
	hide: boolean;
	label: string;
	pad: number;
	/** Thinking runs shown or hidden by a click. */
	overrides: Map<number, boolean>;
}

function fresh(msg: AssistantMessage, streaming: boolean, settings: Settings, width: number): string[] {
	const component = new AssistantMessageComponent(
		undefined,
		settings.hide,
		settings.markdown,
		settings.label,
		settings.pad,
	);
	const overrides = (component as unknown as { thinkingVisibilityOverrides: Map<number, boolean> })
		.thinkingVisibilityOverrides;
	for (const [run, shown] of settings.overrides) overrides.set(run, shown);
	component.updateContent(msg, streaming);
	return component.render(width);
}

describe("assistant message plan", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (let seed = 1; seed <= 40; seed++) {
		it(`renders as a component built from the message, seed ${seed}`, () => {
			const next = random(seed);
			const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)]!;
			const settings: Settings = {
				markdown: getMarkdownTheme(),
				hide: next() < 0.3,
				label: "Thinking...",
				pad: 1,
				overrides: new Map(),
			};
			const component = new AssistantMessageComponent(
				undefined,
				settings.hide,
				settings.markdown,
				settings.label,
				settings.pad,
			);
			const content: Block[] = [];
			let stopReason: AssistantMessage["stopReason"] = "stop";
			let errorMessage: string | undefined;
			let fastBuilds = 0;
			// Counts the builds that make components (markdownFor() is not called by a rebuild with the same plan).
			const counted = component as unknown as { markdownFor: (...args: unknown[]) => unknown };
			const markdownFor = counted.markdownFor.bind(component);
			let made = 0;
			counted.markdownFor = (...args: unknown[]) => {
				made++;
				return markdownFor(...args);
			};
			const follower = { lines: [] as string[], source: undefined as LiveLines | undefined, revision: -1 };
			for (let step = 0; step < 160; step++) {
				const r = next();
				if (r < 0.6 && content.length > 0) {
					// The last block grows.
					const last = content[content.length - 1]!;
					if (last.type === "text") content[content.length - 1] = { ...last, text: last.text + pick(PIECES) };
					else if (last.type === "thinking")
						content[content.length - 1] = { ...last, thinking: last.thinking + pick(PIECES) };
					else content.push({ type: "text", text: pick(PIECES) });
				} else if (r < 0.75) {
					const kind = next();
					content.push(
						kind < 0.45
							? { type: "text", text: pick(PIECES) }
							: kind < 0.9
								? { type: "thinking", thinking: pick(PIECES) }
								: { type: "toolCall", id: `call-${step}`, name: "read", arguments: { path: "x" } },
					);
				} else if (r < 0.79) {
					settings.hide = !settings.hide;
					settings.overrides.clear();
					component.setHideThinkingBlock(settings.hide);
				} else if (r < 0.81) {
					settings.label = settings.label === "Thinking..." ? "Hidden" : "Thinking...";
					component.setHiddenThinkingLabel(settings.label);
				} else if (r < 0.83) {
					settings.pad = settings.pad === 1 ? 2 : 1;
					component.setOutputPad(settings.pad);
				} else if (r < 0.85) {
					component.invalidate();
				} else if (r < 0.865) {
					// A click on a thinking run toggles it (what its MouseRegion does): the run's own override, not a setter.
					const overrides = (component as unknown as { thinkingVisibilityOverrides: Map<number, boolean> })
						.thinkingVisibilityOverrides;
					const run = Math.floor(next() * 3);
					overrides.set(run, !(overrides.get(run) ?? settings.hide));
					settings.overrides.set(run, overrides.get(run)!);
				} else if (r < 0.87) {
					stopReason = pick(["stop", "length", "aborted", "error"] as const);
					errorMessage = next() < 0.5 ? undefined : "boom";
				}
				const msg = message(content.slice(), stopReason, errorMessage);
				const streaming = next() < 0.9;
				const before = made;
				component.updateContent(msg, streaming);
				const width = next() < 0.8 ? 80 : 37;
				let lines: string[];
				if (step % 2 === 0) {
					lines = component.render(width);
				} else {
					const live = renderLiveLines(component, width)!;
					const from = live === follower.source ? live.changedSince(follower.revision) : 0;
					if (from !== Number.POSITIVE_INFINITY) {
						follower.lines.length = Math.min(follower.lines.length, from);
						for (let i = from; i < live.lines.length; i++) follower.lines.push(live.lines[i]!);
					}
					follower.source = live;
					follower.revision = live.revision;
					lines = follower.lines.slice();
				}
				if (streaming && made === before && content.some((block) => block.type !== "toolCall")) fastBuilds++;
				expect(lines, `seed ${seed} step ${step}`).toEqual(fresh(msg, streaming, settings, width));
			}
			expect(fastBuilds).toBeGreaterThan(20);
		});
	}

	// (Theme changes are left out above: a Markdown component keeps the style prefix of its default text style, thinking's
	// color, from before a theme change, in either build. The hidden thinking label is a Text made with the theme.)
	for (const invalidate of [false, true]) {
		it(`draws the hidden thinking label in the theme in use after a theme change (invalidate: ${invalidate})`, () => {
			setTheme("dark");
			const settings: Settings = {
				markdown: getMarkdownTheme(),
				hide: true,
				label: "Thinking...",
				pad: 1,
				overrides: new Map(),
			};
			const component = new AssistantMessageComponent(undefined, true, settings.markdown, settings.label, 1);
			const content: Block[] = [
				{ type: "thinking", thinking: "a thought" },
				{ type: "text", text: "the answer" },
			];
			component.updateContent(message(content, "stop"), true);
			expect(component.render(60)).toEqual(fresh(message(content, "stop"), true, settings, 60));
			setTheme("light");
			if (invalidate) component.invalidate();
			content[1] = { type: "text", text: "the answer, longer" };
			component.updateContent(message(content, "stop"), true);
			expect(component.render(60)).toEqual(fresh(message(content, "stop"), true, settings, 60));
			setTheme("dark");
		});
	}
});
