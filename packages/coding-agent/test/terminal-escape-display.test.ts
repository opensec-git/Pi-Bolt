import type { AssistantMessage } from "@earendil-works/pi-ai";
import {
	resetCapabilitiesCache,
	sanitizeTerminalText,
	setCapabilities,
	setKeybindings,
	type TUI,
} from "@earendil-works/pi-tui";
import { afterEach, beforeAll, beforeEach, describe, expect, test, vi } from "vitest";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import type * as OutputGuard from "../src/core/output-guard.ts";
import { createBashToolDefinition } from "../src/core/tools/bash.ts";
import { getTextOutput, str } from "../src/core/tools/render-utils.ts";
import { createWriteToolDefinition } from "../src/core/tools/write.ts";
import { createMcpToolRenderers } from "../src/extensions/mcp/tools.ts";
import { McpManagerView } from "../src/extensions/mcp/ui.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { CompactionSummaryMessageComponent } from "../src/modes/interactive/components/compaction-summary-message.ts";
import { LoginDialogComponent } from "../src/modes/interactive/components/login-dialog.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { initTheme, theme } from "../src/modes/interactive/theme/theme.ts";
import { runPrintMode } from "../src/modes/print-mode.ts";
import { stripAnsi } from "../src/utils/ansi.ts";

const { rawStdout } = vi.hoisted(() => ({ rawStdout: [] as string[] }));
vi.mock("../src/core/output-guard.ts", async (importOriginal) => ({
	...(await importOriginal<typeof OutputGuard>()),
	writeRawStdout: (text: string) => rawStdout.push(text),
	flushRawStdout: async () => {},
}));
vi.mock("../src/utils/open-browser.ts", () => ({ openBrowser: vi.fn() }));

// What a model, a tool or an MCP server can send that a terminal acts on: the clipboard (OSC 52), the window title, a
// hyperlink, a DCS string, line clearing and cursor movement, and the 8-bit (C1) forms of CSI and OSC.
const CLIPBOARD = "\x1b]52;c;ZWNobyBQV05FRA==\x07";
const TITLE = "\x1b]0;SPOOFED-TITLE\x07";
const LINK = "\x1b]8;;file:///Applications/Calculator.app\x07";
const DCS = "\x1bP+q544e\x1b\\";
const OVERWRITE = "\x1b[2K\x1b[1G";
const C1 = "\x9b2K\x9d0;SPOOFED\x07";
const PAYLOAD = `${CLIPBOARD}${TITLE}${LINK}${DCS}${OVERWRITE}${C1}`;

/** Escape sequences other than SGR styling (Pi's colors), and control characters but tab and line feed. */
const ACTIVE = /\x1b(?!\[[0-9;]*m)|[\x00-\x08\x0b-\x1a\x1c-\x1f\x7f-\x9f]/;

const tui = { requestRender: () => {} } as unknown as TUI;

function expectInert(output: string): void {
	expect(output).not.toMatch(ACTIVE);
}

function assistantMessage(overrides: Partial<AssistantMessage>): AssistantMessage {
	return {
		role: "assistant",
		content: [],
		api: "openai-completions",
		provider: "test",
		model: "test-model",
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
		...overrides,
	};
}

describe("terminal escape sequences from a model, a tool or an MCP server", () => {
	beforeAll(() => {
		initTheme("dark");
	});
	beforeEach(() => {
		// (No hyperlinks: Pi's own links to paths would be escape sequences of their own in the output.)
		setCapabilities({ images: null, trueColor: true, hyperlinks: false });
		setKeybindings(new KeybindingsManager());
		rawStdout.length = 0;
	});
	afterEach(() => {
		resetCapabilitiesCache();
		vi.restoreAllMocks();
	});

	test("a reply, its thinking and its error draw no escape sequences", () => {
		const component = new AssistantMessageComponent(
			assistantMessage({
				content: [
					{ type: "thinking", thinking: `thinking ${PAYLOAD} more` },
					{ type: "text", text: `reply ${PAYLOAD} more` },
				],
				stopReason: "error",
				errorMessage: `provider said ${PAYLOAD}`,
			}),
		);
		const output = component.render(100).join("\n");
		expectInert(output.replace(/\x1b\]133;[ABC]\x07/g, ""));
		expect(stripAnsi(output)).toContain("reply");
		expect(stripAnsi(output)).toContain("provider said");
	});

	test("a shell call cannot draw another command line over its own", () => {
		const bash = createBashToolDefinition(process.cwd(), { exposeSessionEnvironment: false });
		const component = new ToolExecutionComponent(
			"bash",
			"call-1",
			{ command: `echo REAL-COMMAND-RAN > ran.txt #${OVERWRITE}$ ls -la${CLIPBOARD}` },
			{},
			bash,
			tui,
			process.cwd(),
		);
		component.updateResult({ content: [{ type: "text", text: `out ${PAYLOAD}` }], isError: false });
		const output = component.render(100).join("\n");
		expectInert(output);
		expect(stripAnsi(output)).toContain("$ echo REAL-COMMAND-RAN > ran.txt #$ ls -la");
	});

	test("a written file's path and content draw no escape sequences", () => {
		const write = createWriteToolDefinition(process.cwd());
		for (const path of [`notes${TITLE}.txt`, `script${CLIPBOARD}.ts`]) {
			const component = new ToolExecutionComponent(
				"write",
				"call-2",
				{ path, content: `first line ${PAYLOAD}\nconst x = "${CLIPBOARD}";\n` },
				{},
				write,
				tui,
				process.cwd(),
			);
			component.setExpanded(true);
			component.updateResult({ content: [{ type: "text", text: `failed ${PAYLOAD}` }], isError: true });
			expectInert(component.render(100).join("\n"));
		}
	});

	test("a long argument streaming in is sanitised as a whole at every chunk", () => {
		// (Long enough for str() to sanitise from the text before; the sequences are cut at every point by the chunks.)
		const full = `${"const content = 1;\n".repeat(80)}${PAYLOAD}\r${PAYLOAD}\r\n${"tail\n".repeat(20)}${PAYLOAD}`;
		const other = `${"other file\n".repeat(120)}${TITLE}`;
		for (let end = 1000; end <= full.length; end += 3) {
			const text = full.slice(0, end);
			expect(str(text)).toBe(sanitizeTerminalText(text));
			// (Another argument shown in between, as a call's path or a second call's content.)
			expect(str(other.slice(0, end))).toBe(sanitizeTerminalText(other.slice(0, end)));
		}
	});

	test("a tool without renderers, and an MCP tool's label, arguments and result, draw no escape sequences", () => {
		const generic = new ToolExecutionComponent(
			"custom",
			"call-3",
			{ value: PAYLOAD },
			{},
			undefined,
			tui,
			process.cwd(),
		);
		generic.updateResult({ content: [{ type: "text", text: PAYLOAD }], isError: false });
		expectInert(generic.render(100).join("\n"));

		const renderers = createMcpToolRenderers(`server${TITLE}/tool${C1}`);
		for (const expanded of [false, true]) {
			const component = new ToolExecutionComponent(
				"mcp__server__tool",
				"call-4",
				{ text: `value ${PAYLOAD}`, [`key${CLIPBOARD}`]: 1 },
				{},
				renderers,
				tui,
				process.cwd(),
			);
			component.setExpanded(expanded);
			component.updateResult({ content: [{ type: "text", text: `result ${PAYLOAD}` }], isError: false });
			expectInert(component.render(100).join("\n"));
		}
	});

	test("tool output loses DEL and C1 controls", () => {
		expect(getTextOutput({ content: [{ type: "text", text: `a${C1}\x7fb` }] }, false)).toBe("a0;SPOOFEDb");
	});

	test("the MCP manager draws server-sent names, descriptions and errors without escape sequences", () => {
		const view = new McpManagerView(tui, theme, new KeybindingsManager());
		void view.menu(() => ({
			title: `Tools of server${TITLE}`,
			details: `details ${PAYLOAD}`,
			error: `failed: ${PAYLOAD}`,
			items: [{ value: "tool", label: `tool${CLIPBOARD}`, description: `does ${PAYLOAD}` }],
			confirmLabel: "back",
			cancelLabel: "back",
		}));
		expectInert(view.render(100).join("\n"));
		view.status(`Connecting ${TITLE}`, `stderr ${PAYLOAD}`);
		expectInert(view.render(100).join("\n"));
	});

	test("a compaction read from a session file shows its token count as a number", () => {
		const component = new CompactionSummaryMessageComponent({
			role: "compactionSummary",
			summary: `summary ${PAYLOAD}`,
			tokensBefore: PAYLOAD as unknown as number,
			timestamp: 0,
		});
		expectInert(component.render(100).join("\n"));
		component.setExpanded(true);
		expectInert(component.render(100).join("\n"));
	});

	test("print mode prints the reply and its error without escape sequences", async () => {
		const host = (message: AssistantMessage) => ({
			session: {
				sessionManager: { getHeader: () => undefined },
				agent: { waitForIdle: async () => {}, subscribe: vi.fn(() => () => {}) },
				state: { messages: [message] },
				extensionRunner: { hasHandlers: () => false, emit: vi.fn(async () => {}) },
				bindExtensions: vi.fn(async () => {}),
				subscribe: vi.fn(() => () => {}),
				prompt: vi.fn(async () => {}),
				reload: vi.fn(async () => {}),
			},
			newSession: vi.fn(),
			fork: vi.fn(),
			switchSession: vi.fn(),
			dispose: vi.fn(async () => {}),
			setRebindSession: vi.fn(),
		});

		await runPrintMode(
			host(assistantMessage({ content: [{ type: "text", text: `reply ${PAYLOAD} end` }] })) as never,
			{ mode: "text" },
		);
		// (What is left of the C1 sequences is text: their introducers and BEL are gone.)
		expect(rawStdout.join("")).toBe("reply 2K0;SPOOFED end\n");

		const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
		await runPrintMode(
			host(assistantMessage({ stopReason: "error", errorMessage: `provider said ${PAYLOAD}` })) as never,
			{ mode: "text" },
		);
		const printed = errorSpy.mock.calls.map((call) => String(call[0])).join("\n");
		expectInert(printed);
		expect(printed).toContain("provider said");
	});

	test("a sign-in link is a hyperlink only when it is a web link", () => {
		setCapabilities({ images: null, trueColor: true, hyperlinks: true });
		const linkTargets = (output: string) => [...output.matchAll(/\x1b\]8;;([^\x07\x1b]+)/g)].map((match) => match[1]);

		const web = new LoginDialogComponent(tui, "test", () => {});
		web.showDeviceCode({ userCode: "ABCD-1234", verificationUri: "https://example.invalid/device" });
		expect(linkTargets(web.render(100).join("\n"))).toContain("https://example.invalid/device");

		const local = new LoginDialogComponent(tui, "test", () => {});
		local.showDeviceCode({
			userCode: `ABCD${TITLE}`,
			verificationUri: "file:///System/Applications/Calculator.app",
		});
		const output = local.render(100).join("\n");
		expect(linkTargets(output)).toEqual([]);
		expect(stripAnsi(output)).toContain("file:///System/Applications/Calculator.app");
		expectInert(output);
	});
});
