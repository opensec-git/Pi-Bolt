import type { AssistantMessage } from "@earendil-works/pi-ai";
import { renderLiveLines } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, it } from "vitest";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { getMarkdownTheme, initTheme } from "../src/modes/interactive/theme/theme.ts";

// While a reply streams in, the component is given the message for each delta and renders it now and then: the texts of its
// blocks are trimmed from what was trimmed before and what each delta added (BlockText). What it renders must be what a
// component built from the message renders, whatever the deltas are: white space, escape sequences, a block that does not
// grow from the text before.

type Block = AssistantMessage["content"][number];

function message(content: Block[]): AssistantMessage {
	return {
		role: "assistant",
		content,
		api: "openai-completions",
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
		stopReason: "stop",
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
	" ",
	"  ",
	"\n",
	"\n\n",
	"\t",
	" ",
	" ",
	"﻿",
	"　",
	"word ",
	"**bold**",
	"`code` ",
	"- item\n",
	"```ts\nconst a = 1;\n",
	"```\n",
	"x\ty",
	"\x1b[3",
	"1m",
	"\r",
	"\r\n",
	"naïve 日本語",
];

function fresh(msg: AssistantMessage, streaming: boolean, width: number, hide: boolean): string[] {
	const component = new AssistantMessageComponent(undefined, hide, getMarkdownTheme());
	component.updateContent(msg, streaming);
	return component.render(width);
}

describe("assistant message given each delta", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (let seed = 1; seed <= 30; seed++) {
		it(`renders as a component built from the message, seed ${seed}`, () => {
			const next = random(seed);
			const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)]!;
			const hide = seed % 5 === 0;
			const component = new AssistantMessageComponent(undefined, hide, getMarkdownTheme());
			const content: Block[] = [];
			for (let step = 0; step < 150; step++) {
				const r = next();
				if (r < 0.75 && content.length > 0) {
					// A delta added to a block: most often the last one, as a provider adds it (`text += delta`).
					const index = next() < 0.9 ? content.length - 1 : Math.floor(next() * content.length);
					const block = content[index]!;
					if (block.type === "text") block.text += pick(PIECES);
					else if (block.type === "thinking") block.thinking += pick(PIECES);
				} else if (r < 0.85) {
					const kind = next();
					content.push(
						kind < 0.5
							? { type: "text", text: pick(PIECES) }
							: kind < 0.9
								? { type: "thinking", thinking: pick(PIECES) }
								: { type: "toolCall", id: `call-${step}`, name: "read", arguments: { path: "x" } },
					);
				} else if (r < 0.9 && content.length > 0) {
					// A block whose text is not the one before with text added.
					const block = content[Math.floor(next() * content.length)]!;
					if (block.type === "text") block.text = block.text.slice(0, Math.floor(block.text.length / 2));
					else if (block.type === "thinking") block.thinking = `${pick(PIECES)}${block.thinking}`;
				}
				// (The agent gives a copy of the message for each delta, with the same blocks.)
				const msg = message(content.map((block) => ({ ...block })));
				component.updateContent(msg, true);
				// Several deltas can come between two renders.
				if (next() < 0.5) continue;
				const width = next() < 0.8 ? 72 : 31;
				const lines = step % 2 === 0 ? component.render(width) : renderLiveLines(component, width)!.lines.slice();
				const expected = fresh(msg, true, width, hide);
				expect(lines, `seed ${seed} step ${step}`).toEqual(expected);
			}
			// The reply ends: built in full from the final message.
			const final = message(content.map((block) => ({ ...block })));
			component.updateContent(final, false);
			expect(component.render(72)).toEqual(fresh(final, false, 72, hide));
		});
	}
});
