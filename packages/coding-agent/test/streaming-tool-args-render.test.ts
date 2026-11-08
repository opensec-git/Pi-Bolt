import {
	type AssistantMessage,
	type AssistantMessageEvent,
	parseStreamingJson,
	parseStreamingJsonWhileStreaming,
	type ToolCall,
} from "@earendil-works/pi-ai";
import { Container, type TUI } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test } from "vitest";
import type { AgentSessionEvent } from "../src/core/agent-session.ts";
import { createWriteToolDefinition } from "../src/core/tools/write.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

// A tool call's arguments stream in as many small deltas as the model sends: a file passed to the write tool is thousands.
// The provider parses them again only when they have grown by an eighth (parseStreamingJsonWhileStreaming), and the TUI is
// asked for a frame only when a delta changes something it draws: until the arguments are parsed again, the tool call has
// the same arguments object and draws the same lines. At every delta, the arguments must be what parsing the text from
// nothing gives, and what the transcript renders to must be what a transcript built from nothing for that message renders
// to; when no frame was asked for, it must also be what the last frame drew.

const WIDTH = 120;
const TOOL_CALL_ID = "call_write";

const ESCAPES =
	'quote " backslash \\ slash / tab\tcarriage\r\nform\fbackspace\b control \u0001\u001f literal \\n and \\u0041\n';
const UNICODE = "Grüße 😀 🇮🇳 日本語テキスト العربية עברית é zero\u200bwidth \u2028 sep\u2029 end\n";
const SOURCE = Array.from(
	{ length: 120 },
	(_, n) =>
		`export function step${n}(input: number[]): number { return input.reduce((a, b) => a + b * ${n % 97}, ${n}); }\n` +
		(n % 17 === 3 ? `/* a comment\n   over lines ${n}\n*/\nconst s${n} = "a \\"quoted\\" \\\\ string";\n\n` : ""),
).join("");

/** `chunks`: the chunk lengths it is streamed in (0: pseudo-random lengths). */
const FILES: Array<{ name: string; path: string; content: string; chunks?: number[] }> = [
	{ name: "source with escapes and unicode", path: "big/source.ts", content: ESCAPES + SOURCE + UNICODE },
	{ name: "plain text with escapes", path: "notes/plain.txt", content: (ESCAPES + UNICODE).repeat(40) },
	{
		name: "one very long line",
		path: "data/line.json",
		content: `{"a": "${"0123456789abcdef".repeat(2500)}"}`,
		// (No escapes to split: one character at a time would take seconds and test nothing three do not.)
		chunks: [3, 16, 97, 0],
	},
	{ name: "empty lines and tabs", path: "run.py", content: "\n\n\tdef f():\n\t\treturn 1\n\n\n".repeat(60) },
];

/** `json` with every character outside ASCII as a \u escape, as some models send it. */
function asciiEscaped(json: string): string {
	return json.replace(/[^\x00-\x7f]/g, (char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`);
}

/** Chunk lengths: `fixed`, or pseudo-random lengths from 1 to 40 when 0. */
function chunksOf(text: string, fixed: number): string[] {
	const chunks: string[] = [];
	let seed = 7;
	for (let at = 0; at < text.length; ) {
		seed = (seed * 1103515245 + 12345) % 2147483648;
		const length = fixed > 0 ? fixed : 1 + (seed % 40);
		chunks.push(text.slice(at, at + length));
		at += length;
	}
	return chunks;
}

function assistantMessage(content: AssistantMessage["content"]): AssistantMessage {
	return {
		role: "assistant",
		content,
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
		stopReason: "toolUse",
		timestamp: 0,
	};
}

/** What InteractiveMode.handleEvent() uses of `this` for message_update events. */
interface StreamingHost {
	isInitialized: boolean;
	footer: { invalidate(): void };
	programStatus: { handleEvent(event: AgentSessionEvent): void };
	streamingComponent: AssistantMessageComponent;
	streamingMessage: AssistantMessage | undefined;
	pendingTools: Map<string, ToolExecutionComponent>;
	chatContainer: Container;
	settingsManager: { getShowImages(): boolean; getImageWidthCells(): number };
	outputPad: number;
	toolOutputExpanded: boolean;
	ui: TUI;
	sessionManager: { getCwd(): string };
	getRegisteredToolDefinition(name: string): ReturnType<typeof createWriteToolDefinition>;
	toolRowsCompactable(name: string): boolean;
}

const handleEvent = Reflect.get(InteractiveMode.prototype, "handleEvent") as (
	this: StreamingHost,
	event: AgentSessionEvent,
) => Promise<void>;

const fakeTui = { requestRender: () => {} } as unknown as TUI;

function createHost(expanded: boolean): { host: StreamingHost; renderRequests: () => number } {
	let requests = 0;
	const streamingComponent = new AssistantMessageComponent();
	const chatContainer = new Container();
	chatContainer.addChild(streamingComponent);
	const host: StreamingHost = {
		isInitialized: true,
		footer: { invalidate: () => {} },
		programStatus: { handleEvent: () => {} },
		streamingComponent,
		streamingMessage: undefined,
		pendingTools: new Map(),
		chatContainer,
		settingsManager: { getShowImages: () => true, getImageWidthCells: () => 60 },
		outputPad: 1,
		toolOutputExpanded: expanded,
		ui: {
			requestRender: () => {
				requests++;
			},
		} as unknown as TUI,
		sessionManager: { getCwd: () => process.cwd() },
		getRegisteredToolDefinition: () => createWriteToolDefinition(process.cwd()),
		// (A write row never compacts: it keeps what it drew while its arguments streamed in.)
		toolRowsCompactable: () => false,
	};
	return { host, renderRequests: () => requests };
}

/** The transcript of `message` built from nothing: its text, and its tool call with `args`. */
function renderFromNothing(message: AssistantMessage, args: ToolCall["arguments"], expanded: boolean): string[] {
	const container = new Container();
	const assistant = new AssistantMessageComponent();
	assistant.updateContent(message, true);
	container.addChild(assistant);
	const tool = new ToolExecutionComponent(
		"write",
		TOOL_CALL_ID,
		args,
		{ showImages: true, imageWidthCells: 60, outputPad: 1 },
		createWriteToolDefinition(process.cwd()),
		fakeTui,
		process.cwd(),
	);
	tool.setExpanded(expanded);
	container.addChild(tool);
	return container.render(WIDTH);
}

/**
 * Streams the write call's arguments `json` in `chunks` the way the providers do (openai-completions: the text appended to,
 * then parsed while streaming), handing each delta to InteractiveMode.handleEvent(), and checks every step.
 */
async function streamWriteCall(json: string, chunks: string[], expanded: boolean): Promise<{ frames: number }> {
	const { host, renderRequests } = createHost(expanded);
	const block: ToolCall & { partialArgs: string } = {
		type: "toolCall",
		id: TOOL_CALL_ID,
		name: "write",
		arguments: {},
		partialArgs: "",
	};
	const output = assistantMessage([{ type: "text", text: "Writing the file." }, block]);
	const update = async (assistantMessageEvent: AssistantMessageEvent) => {
		await handleEvent.call(host, { type: "message_update", message: { ...output }, assistantMessageEvent });
	};

	await update({ type: "toolcall_start", contentIndex: 1, partial: output });
	expect(renderRequests()).toBe(1);
	let drawn = host.chatContainer.render(WIDTH);
	expect(drawn).toEqual(renderFromNothing(output, block.arguments, expanded));

	let frames = 1;
	let parsedFrom = "";
	for (let index = 0; index < chunks.length; index++) {
		const chunk = chunks[index]!;
		const before = block.arguments;
		block.partialArgs += chunk;
		block.arguments = parseStreamingJsonWhileStreaming(block, block.partialArgs, block.arguments);
		const label = `after ${block.partialArgs.length} of ${json.length} characters (chunk ${index})`;
		if (block.arguments !== before) {
			parsedFrom = block.partialArgs;
			expect(block.arguments, label).toEqual(parseStreamingJson(block.partialArgs));
		} else {
			// The arguments parsed from the text as it was then: compared with parsing it from nothing when they were made.
			expect(json.startsWith(parsedFrom), label).toBe(true);
		}

		const requestsBefore = renderRequests();
		await update({ type: "toolcall_delta", contentIndex: 1, delta: chunk, partial: output });
		const requested = renderRequests() > requestsBefore;
		if (requested) frames++;
		// A frame is asked for exactly when the delta gave the tool call new arguments.
		expect(requested, label).toBe(block.arguments !== before);
		const lines = host.chatContainer.render(WIDTH);
		if (!requested) {
			// The frame that was not drawn would have drawn what the last one did.
			expect(lines, label).toEqual(drawn);
		}
		// (Built from nothing for every frame, and every 64th delta in between: building it for every delta of a large
		// file at one character a time takes minutes.)
		if (requested || index % 64 === 0 || index === chunks.length - 1) {
			expect(lines, label).toEqual(renderFromNothing(output, block.arguments, expanded));
		}
		drawn = lines;
	}

	expect(block.partialArgs).toBe(json);
	expect(parseStreamingJson(block.partialArgs)).toEqual(JSON.parse(json));

	// A text delta is always drawn.
	const requestsBefore = renderRequests();
	await update({ type: "text_delta", contentIndex: 0, delta: "", partial: output });
	expect(renderRequests()).toBe(requestsBefore + 1);
	return { frames };
}

describe("streaming a write call's arguments", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (const file of FILES) {
		const args = { path: file.path, content: file.content };
		for (const [encoding, json] of [
			["raw", JSON.stringify(args)],
			["ascii-escaped", asciiEscaped(JSON.stringify(args))],
		] as const) {
			for (const chunk of file.chunks ?? [1, 3, 16, 97, 0]) {
				for (const expanded of [false, true]) {
					test(`${file.name}, ${encoding}, ${chunk || "random"}-character chunks, ${expanded ? "expanded" : "collapsed"}`, async () => {
						const chunks = chunksOf(json, chunk);
						const { frames } = await streamWriteCall(json, chunks, expanded);
						// Past the first kilobyte, the arguments are parsed again (and drawn) only when they have grown by an
						// eighth.
						if (json.length > 8192 && chunk > 0) expect(frames).toBeLessThan(1024 / chunk + 60);
					}, 120_000);
				}
			}
		}
	}

	test("a delta of another tool call that did not change its arguments still draws the call that changed", async () => {
		const { host, renderRequests } = createHost(false);
		const first: ToolCall = { type: "toolCall", id: "first", name: "write", arguments: { path: "a.txt" } };
		const second: ToolCall = { type: "toolCall", id: "second", name: "write", arguments: { path: "b.txt" } };
		const output = assistantMessage([first, second]);
		const event = (): AgentSessionEvent => ({
			type: "message_update",
			message: { ...output },
			assistantMessageEvent: { type: "toolcall_delta", contentIndex: 1, delta: "x", partial: output },
		});
		await handleEvent.call(host, event());
		expect(renderRequests()).toBe(1);
		await handleEvent.call(host, event());
		expect(renderRequests()).toBe(1);
		second.arguments = { path: "b.txt", content: "x" };
		await handleEvent.call(host, event());
		expect(renderRequests()).toBe(2);
		expect(host.pendingTools.get("second")?.render(WIDTH).join("\n")).toContain("x");
	});
});
