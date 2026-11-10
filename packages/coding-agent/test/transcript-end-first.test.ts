import type { AssistantMessage } from "@earendil-works/pi-ai";
import { type Component, Container, ScrollView, Spacer, Text, VStack } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test } from "vitest";
import { renderLayoutFrame } from "../../tui/src/layout.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { TranscriptContainer } from "../src/modes/interactive/components/transcript-container.ts";
import { UserMessageComponent } from "../src/modes/interactive/components/user-message.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

// A resumed transcript in fullscreen renders only its end before the first frame (TranscriptContainer.renderEndFirst()),
// then the rest when idle (backfill()). Every frame must be the frame of the whole transcript, rendered at once.

const WIDTH = 80;
const ROWS = 24;

function assistant(index: number): AssistantMessage {
	return {
		role: "assistant",
		content: [
			{ type: "thinking", thinking: `thinking about step ${index} `.repeat(6) },
			{
				type: "text",
				text: `## Step ${index}\n\nSome **bold** text and \`code\` for step ${index}, long enough to wrap at eighty columns once or twice.\n\n- one\n- two\n\n\`\`\`ts\nconst step = ${index};\n\`\`\``,
			},
		],
		api: "openai-completions",
		provider: "fake",
		model: "fake-model",
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

/** Counts its renders. */
class CountedText extends Text {
	renders = 0;
	override render(width: number): string[] {
		this.renders++;
		return super.render(width);
	}
}

function entries(count: number): Component[] {
	const list: Component[] = [];
	for (let i = 0; i < count; i++) {
		if (i % 5 === 0) list.push(new Spacer(1), new UserMessageComponent(`Task ${i}: do the thing`, undefined, 1));
		list.push(new AssistantMessageComponent(assistant(i), false, undefined, "Thinking...", 1));
		list.push(new CountedText(`tool row ${i}\n${"output line\n".repeat(i % 4)}done`, 1, 0));
	}
	return list;
}

interface Screen {
	transcript: TranscriptContainer;
	root: VStack;
	scroll: ScrollView;
}

function screen(children: readonly Component[], scrollbar: "auto" | "always"): Screen {
	const transcript = new TranscriptContainer();
	for (const child of children) transcript.addChild(child);
	const document = new Container();
	document.addChild(new Text("header", 1, 0));
	document.addChild(transcript);
	const scroll = new ScrollView(document, { follow: "end", scrollbar });
	const root = new VStack([
		{ component: scroll, basis: 0, grow: 1, shrink: 1, minSize: 1 },
		{ component: new Text("editor\nfooter", 0, 0), basis: "auto", grow: 0, shrink: 0, minSize: 1 },
	]);
	return { transcript, root, scroll };
}

function frame(s: Screen, width = WIDTH, rows = ROWS): string[] {
	return renderLayoutFrame(s.root, width, rows, () => {}).lines;
}

/** The lines interactive mode asks for: the rows, and with the scrollbar always shown, enough for the smallest thumb. */
function linesShown(rows: number, scrollbar: "auto" | "always"): number {
	return (scrollbar === "always" ? Math.max(rows, Math.floor(0.4 * rows * rows)) : rows) + 1;
}

async function backfilled(transcript: TranscriptContainer): Promise<number> {
	let calls = 0;
	expect(transcript.backfill(() => calls++)).toBe(true);
	while (calls === 0) await new Promise((resolve) => setImmediate(resolve));
	return calls;
}

describe("TranscriptContainer.renderEndFirst()", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (const scrollbar of ["auto", "always"] as const) {
		test(`draws the first frame of the whole transcript from its end (scrollbar ${scrollbar})`, async () => {
			const ownEntries = entries(400);
			const whole = screen(entries(400), scrollbar);
			const endFirst = screen(ownEntries, scrollbar);
			endFirst.transcript.renderEndFirst(() => linesShown(ROWS, scrollbar));

			const expected = frame(whole);
			expect(frame(endFirst)).toEqual(expected);
			expect(endFirst.transcript.isHoldingBack).toBe(true);
			// (The first entries were not rendered for it.)
			expect((ownEntries[3] as CountedText).renders).toBe(0);

			// Frames while it is held back, then after backfill(): the same.
			expect(frame(endFirst)).toEqual(expected);
			expect(await backfilled(endFirst.transcript)).toBe(1);
			expect(endFirst.transcript.isHoldingBack).toBe(false);
			expect((ownEntries[3] as CountedText).renders).toBe(1);
			expect(frame(endFirst)).toEqual(expected);
			expect(endFirst.transcript.children).toEqual(ownEntries);

			// Scrolled to the top: the whole transcript is there.
			whole.scroll.scrollToStart();
			endFirst.scroll.scrollToStart();
			expect(frame(endFirst)).toEqual(frame(whole));
		});
	}

	test("renders everything as before when the transcript is shorter than what the frame shows", () => {
		const ownEntries = entries(1);
		const s = screen(ownEntries, "auto");
		s.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		expect(frame(s)).toEqual(frame(screen(entries(1), "auto")));
		expect(s.transcript.isHoldingBack).toBe(false);
		expect(s.transcript.backfill(() => {})).toBe(false);
	});

	test("puts the held entries back when `children` is read, at a render at another width, or when the end is too short", () => {
		const read = screen(entries(200), "auto");
		read.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		frame(read);
		expect(read.transcript.isHoldingBack).toBe(true);
		const all = read.transcript.children;
		expect(all.length).toBe(entries(200).length);
		expect(read.transcript.isHoldingBack).toBe(false);
		// (Put back since its last render: the screen must lay it out again before reading it.)
		expect(read.transcript.completeNow()).toBe(true);
		frame(read);
		expect(read.transcript.completeNow()).toBe(false);

		const resized = screen(entries(200), "auto");
		resized.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		frame(resized);
		expect(resized.transcript.isHoldingBack).toBe(true);
		expect(frame(resized, WIDTH - 30)).toEqual(frame(screen(entries(200), "auto"), WIDTH - 30));
		expect(resized.transcript.isHoldingBack).toBe(false);

		let rows = ROWS;
		const taller = screen(entries(200), "auto");
		taller.transcript.renderEndFirst(() => linesShown(rows, "auto"));
		frame(taller);
		expect(taller.transcript.isHoldingBack).toBe(true);
		rows = 400;
		expect(frame(taller, WIDTH, rows)).toEqual(frame(screen(entries(200), "auto"), WIDTH, rows));
		expect(taller.transcript.isHoldingBack).toBe(false);
	});

	test("invalidates the held entries too, and backfill() renders them again", async () => {
		const ownEntries = entries(200);
		const s = screen(ownEntries, "auto");
		s.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		frame(s);
		const first = ownEntries[3] as CountedText;
		let filtered = 0;
		s.transcript.invalidateOnly(
			(child) => {
				filtered++;
				return child !== first;
			},
			() => s.transcript.invalidate(),
		);
		expect(filtered).toBe(ownEntries.length);
		await backfilled(s.transcript);
		expect(first.renders).toBe(1);
		expect(frame(s)).toEqual(frame(screen(entries(200), "auto")));
	});

	test("clear() drops the held entries and ends backfill(), calling its callback", () => {
		const s = screen(entries(200), "auto");
		s.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		frame(s);
		let calls = 0;
		expect(s.transcript.backfill(() => calls++)).toBe(true);
		s.transcript.clear();
		expect(calls).toBe(1);
		expect(s.transcript.isHoldingBack).toBe(false);
		expect(s.transcript.children).toEqual([]);
	});

	test("stopBackfill() stops without putting the entries back or calling the callback", async () => {
		const s = screen(entries(200), "auto");
		s.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		frame(s);
		let calls = 0;
		s.transcript.backfill(() => calls++);
		s.transcript.stopBackfill();
		for (let i = 0; i < 50; i++) await new Promise((resolve) => setImmediate(resolve));
		expect(calls).toBe(0);
		expect(s.transcript.isHoldingBack).toBe(true);
	});

	test("removing a held entry puts the entries back first", () => {
		const ownEntries = entries(200);
		const s = screen(ownEntries, "auto");
		s.transcript.renderEndFirst(() => linesShown(ROWS, "auto"));
		frame(s);
		s.transcript.removeChild(ownEntries[1]!);
		expect(s.transcript.isHoldingBack).toBe(false);
		expect(s.transcript.children).toEqual(ownEntries.filter((_, index) => index !== 1));
	});
});
