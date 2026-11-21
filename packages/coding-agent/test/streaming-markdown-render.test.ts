import type { AssistantMessage } from "@earendil-works/pi-ai";
import { type Component, Container, type LiveLines, renderLiveLines, Text } from "@earendil-works/pi-tui";
import { afterEach, beforeAll, describe, expect, test } from "vitest";
import { setMarkdownCachingForTest, setMarkdownFastPathsForTest } from "../../tui/src/components/markdown.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { UserMessageComponent } from "../src/modes/interactive/components/user-message.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

// A reply that streams in is rendered again for each chunk, from what was rendered for the chunk before: the lexed blocks
// it has in common with the shorter text, their lines, and the transcript's lines before it. At every step, what the
// transcript renders to must be what rendering everything from nothing gives.

const SECTION = `## Section {n}: what the function does

The \`parse()\` function reads **one token at a time** and keeps a *small* stack; see [the grammar](https://example.com/g/{n}).
It handles these cases:

- plain text, with \`inline code\` and a long line that has to be wrapped by the terminal because it does not fit in its width at all
- nested lists:
  1. first, with a number {n}
  2. second, with ~~strikethrough~~ and unicode: naïve café 日本語 😀

\`\`\`ts
export function parse{n}(input: string): Node[] {
	const out: Node[] = [];
	for (let i = 0; i < input.length; i++) {
		if (input.charCodeAt(i) === 0x5b) out.push({ kind: "open", at: i });
	}
	return out;
}
\`\`\`

| column | value | note |
|---|---|---|
| a{n} | 1 | first |
| b{n} | 22 | second, longer |

> A quote that closes the section, number {n}.

`;

const DOCUMENTS: Record<string, string> = {
	sections: [1, 2, 3].map((n) => SECTION.replaceAll("{n}", String(n))).join(""),
	everything: `# Title

Intro paragraph with **bold**, *italic*, \`code\`, ~~gone~~ and a [link](https://example.com).
A second line of the same paragraph, long enough to be wrapped at every width this test renders at, and then some more.

Setext heading
==============

- item one
- item two
  continued lazily
  - nested item
    1. deeper, numbered
    2. and another

- loose item after a blank line

1. first
2. second
   \`\`\`js
   const inList = 1;
   \`\`\`
3. third

- [ ] task open
- [x] task done

> quote line one
lazy continuation
> > nested quote
> - list in quote

\`\`\`python
def g():
    return "~~~ inside"
\`\`\`

| a | b |
|---|:-:|
| 1 | 2 |
| a cell with a long text that wraps | 4 |

***

An autolink https://example.org/x?y=1 and <mailto:a@b.c>, then a hard break
and the end.
`,
	unclosedFence: "Before the code:\n\n```sh\nls -la\necho one\necho two\n\n\nstill code\n``",
	listGrowth: "Steps:\n\n1. Install\n2. Configure\n3\n\n4. Run\n\nDone.\n\n- a\n- b\n  - c\n    - d\n\nend",
	tabsAndWide:
		"a\tb\n\n-\tx\ty\n\n```\n\tcode\twith\ttabs\n```\n\n| 列 | 值 |\n|---|---|\n| 一 | 二 |\n\n😀 wide 漢字 text\n",
	headings: "# a\n## b\n### c\n\n#### d\ntext\n# e\n\nparagraph\n---\n\n* * *\n",
};

const THINKING = "Let me think about **this**: first the list,\n\n- one\n- two\n\nthen `code`.";

function assistantMessage(text: string, thinking?: string): AssistantMessage {
	return {
		role: "assistant",
		content: [...(thinking === undefined ? [] : [{ type: "thinking" as const, thinking }]), { type: "text", text }],
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

/** A transcript as the interactive mode has it: a question, an earlier answer, the reply streaming in, and a line after. */
function transcript(earlier: AssistantMessage, reply: AssistantMessageComponent): Container {
	const container = new Container();
	const children: Component[] = [
		new UserMessageComponent("a question"),
		new AssistantMessageComponent(earlier),
		new UserMessageComponent("another question"),
		reply,
		new Text("after", 1, 0),
	];
	for (const child of children) container.addChild(child);
	return container;
}

/** What `message` renders to in such a transcript when nothing is kept from earlier renders. */
function renderFresh(
	earlier: AssistantMessage,
	message: AssistantMessage,
	streaming: boolean,
	width: number,
): readonly string[] {
	setMarkdownCachingForTest(false);
	try {
		const reply = new AssistantMessageComponent();
		reply.updateContent(message, streaming);
		return transcript(earlier, reply).render(width);
	} finally {
		setMarkdownCachingForTest(true);
	}
}

/**
 * Streams `text` (and `thinking` before it) into a transcript in chunks of `sizes()` characters, rendering it at
 * `widths()` after each, and compares every render with one from nothing.
 */
function streamAndCompare(text: string, sizes: () => number, widths: () => number, thinking?: string): void {
	// Text of its own: nothing kept from another test is taken up.
	const document = `<!-- ${Math.random()} -->\n\n${text}`;
	const earlier = assistantMessage(`Earlier answer with a list:\n\n- x\n- y\n\n${Math.random()}`);
	const reply = new AssistantMessageComponent();
	const container = transcript(earlier, reply);
	let steps = 0;
	// Every other step, the transcript is read as the fullscreen layout reads it: live lines, brought up to date from the
	// line they changed from.
	const follower = { lines: [] as string[], source: undefined as LiveLines | undefined, revision: -1 };
	const readLive = (width: number): string[] => {
		const live = renderLiveLines(container, width)!;
		const from = live === follower.source ? live.changedSince(follower.revision) : 0;
		if (from !== Number.POSITIVE_INFINITY) {
			follower.lines.length = Math.min(follower.lines.length, from);
			for (let i = from; i < live.lines.length; i++) follower.lines.push(live.lines[i]!);
		}
		follower.source = live;
		follower.revision = live.revision;
		return follower.lines.slice();
	};
	const check = (message: AssistantMessage, streaming: boolean, width: number, at: string): void => {
		reply.updateContent(message, streaming);
		const lines = steps % 2 === 0 ? container.render(width) : readLive(width);
		const fresh = renderFresh(earlier, message, streaming, width);
		if (JSON.stringify(lines) !== JSON.stringify(fresh)) {
			expect(lines, `${at}, width ${width}`).toEqual(fresh);
		}
		steps++;
	};
	const thinkingText = thinking ?? "";
	const total = thinkingText.length + document.length;
	for (let length = 1; ; length = Math.min(total, length + sizes())) {
		const message =
			length <= thinkingText.length && thinking !== undefined
				? assistantMessage("", thinkingText.slice(0, length))
				: assistantMessage(document.slice(0, length - thinkingText.length), thinking);
		check(
			message,
			true,
			widths(),
			`streaming, ${length} characters: ${JSON.stringify(document.slice(0, length).slice(-40))}`,
		);
		if (length >= total) break;
	}
	check(assistantMessage(document, thinking), false, widths(), "complete");
	expect(steps).toBeGreaterThan(1);
}

describe("a streaming reply renders as it does from nothing", () => {
	beforeAll(() => {
		initTheme("dark");
	});
	afterEach(() => {
		setMarkdownCachingForTest(true);
		setMarkdownFastPathsForTest(true);
	});

	for (const [name, text] of Object.entries(DOCUMENTS)) {
		for (const width of [24, 80, 160]) {
			test(`${name} at width ${width}, in chunks of 1 to 40 characters`, () => {
				const next = random(name.length * 1000 + width);
				streamAndCompare(
					text,
					() => 1 + Math.floor(next() * 40),
					() => width,
				);
			});
		}
		test(`${name}, one character at a time`, () => {
			streamAndCompare(
				text,
				() => 1,
				() => 72,
			);
		});
		test(`${name}, at a different width from one chunk to the next`, () => {
			const next = random(name.length + 7);
			const widths = [30, 30, 30, 101, 101, 57];
			streamAndCompare(
				text,
				() => 1 + Math.floor(next() * 24),
				() => widths[Math.floor(next() * widths.length)]!,
			);
		});
	}

	test("with thinking before the text", () => {
		const next = random(42);
		streamAndCompare(
			DOCUMENTS.everything!,
			() => 1 + Math.floor(next() * 16),
			() => 64,
			THINKING,
		);
	});

	test("a tool call that comes after the text, read live and rendered, with a thinking block before it", () => {
		const document = `${DOCUMENTS.everything!.slice(0, 400)} ${Math.random()}`;
		const earlier = assistantMessage("Earlier answer.");
		const reply = new AssistantMessageComponent();
		const container = transcript(earlier, reply);
		let follower: { source?: LiveLines; revision: number; lines: string[] } = { revision: -1, lines: [] };
		const read = (width: number): string[] => {
			const live = renderLiveLines(container, width)!;
			const from = live === follower.source ? live.changedSince(follower.revision) : 0;
			if (from !== Number.POSITIVE_INFINITY) {
				follower.lines.length = Math.min(follower.lines.length, from);
				for (let i = from; i < live.lines.length; i++) follower.lines.push(live.lines[i]!);
			}
			follower = { source: live, revision: live.revision, lines: follower.lines };
			return follower.lines.slice();
		};
		const withToolCall = (message: AssistantMessage): AssistantMessage => ({
			...message,
			content: [...message.content, { type: "toolCall", id: "call-1", name: "read", arguments: { path: "a.txt" } }],
			stopReason: "toolUse",
		});
		for (let length = 1; length <= document.length; length += 17) {
			let message = assistantMessage(document.slice(0, length), THINKING);
			if (length > document.length / 2) message = withToolCall(message);
			reply.updateContent(message, true);
			const expected = renderFresh(earlier, message, true, 70);
			expect(read(70), `${length}`).toEqual(expected);
			expect(container.render(70), `${length}`).toEqual(expected);
		}
		const complete = withToolCall(assistantMessage(document, THINKING));
		reply.updateContent(complete, false);
		expect(read(70)).toEqual(renderFresh(earlier, complete, false, 70));
	});

	test("chunks of 24 characters, as bench/long_answer.py streams them", () => {
		streamAndCompare(
			[1, 2, 3, 4, 5, 6, 7, 8].map((n) => SECTION.replaceAll("{n}", String(n))).join(""),
			() => 24,
			() => 160,
		);
	});
});

// Pieces of Markdown that end blocks, continue them or change what the block before them is (a "2" that becomes an item of
// the list before it, a setext underline, a table's delimiter row, lazy lines, unclosed fences), put together at random.
const PIECES = [
	"- item {w}\n",
	"  - nested {w}\n",
	"    1. deeper {w}\n",
	"1. one {w}\n",
	"2",
	"2. two\n",
	"3)\n",
	"   lazy {w}\n",
	"lazy {w}\n",
	"Text {w} with **bold**, *it*, `code` and [a link](https://example.com/{w}).\n",
	"A long line of text {w} that has to be wrapped at the narrower widths this test renders the reply at.\n",
	"\n",
	"\n\n",
	"```ts\nconst {w} = 1;\nlet b = `x`;\n```\n",
	"```\nunclosed {w}\n",
	"``",
	"~~~\ntilde {w}\n~~~\n",
	"> quote {w}\n",
	"> > deep {w}\n",
	"| a | b |\n",
	"|---|:-:|\n",
	"| {w} | 2 |\n",
	"# Heading {w}\n",
	"### Small {w}\n",
	"Setext {w}\n",
	"===\n",
	"---\n",
	"***\n",
	"    indented code {w}\n",
	"<div>\nhtml {w}\n</div>\n",
	"<!-- note {w} -->\n",
	"$$\nx^{w}\n$$\n",
	"\\[ y_{w} \\]\n",
	"- [ ] task {w}\n",
	"\t- tab {w}\n",
	"1. a\n   ```\n   code {w}\n   ```\n",
	"Term with trailing spaces  \n",
	"[ref {w}]\n",
	"  ---  \n",
	"   =\n",
	"\u00a0\n",
	"  \t \n",
	"under {w}\r\n--\r\n",
	"a\u2028b {w}\n",
];

/** Streams `text` into a reply component in chunks of `sizes` characters and returns what it renders to after each. */
function streamedRenders(text: string, sizes: number[], width: number): string[] {
	// Nothing lexed for an earlier render is taken up: the sources kept (lexMarkdown()) are pushed out by others.
	for (let i = 0; i < 16; i++)
		new AssistantMessageComponent(assistantMessage(`flush ${i} ${Math.random()}`)).render(width);
	const reply = new AssistantMessageComponent();
	const renders: string[] = [];
	let step = 0;
	for (let length = 1; ; length = Math.min(text.length, length + sizes[step++ % sizes.length]!)) {
		reply.updateContent(assistantMessage(text.slice(0, length)), true);
		renders.push(JSON.stringify(reply.render(width)));
		if (length >= text.length) break;
	}
	reply.updateContent(assistantMessage(text), false);
	renders.push(JSON.stringify(reply.render(width)));
	return renders;
}

describe("keeping the settled blocks of a streaming reply renders what lexing its last two blocks again renders", () => {
	beforeAll(() => {
		initTheme("dark");
	});
	afterEach(() => {
		setMarkdownFastPathsForTest(true);
	});

	for (let seed = 1; seed <= 40; seed++) {
		test(`random document ${seed}`, () => {
			const next = random(seed * 7919);
			for (let documentIndex = 0; documentIndex < 10; documentIndex++) {
				let text = "";
				const pieces = 8 + Math.floor(next() * 24);
				for (let i = 0; i < pieces; i++) {
					text += PIECES[Math.floor(next() * PIECES.length)]!.replaceAll("{w}", String(Math.floor(next() * 1000)));
				}
				const sizes = Array.from({ length: 64 }, () =>
					next() < 0.3 ? 1 : 1 + Math.floor(next() * (next() < 0.5 ? 8 : 40)),
				);
				const width = [20, 47, 160][Math.floor(next() * 3)]!;
				setMarkdownFastPathsForTest(false);
				const before = streamedRenders(text, sizes, width);
				setMarkdownFastPathsForTest(true);
				const after = streamedRenders(text, sizes, width);
				for (let i = 0; i < before.length; i++) {
					if (after[i] !== before[i]) {
						expect(JSON.parse(after[i]!), `${JSON.stringify(text)}, step ${i}, width ${width}`).toEqual(
							JSON.parse(before[i]!),
						);
					}
				}
				expect(after.length).toBe(before.length);
			}
		});
	}
});
