import { type Component, Container, Text, type TUI, type VStack } from "@earendil-works/pi-tui";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { renderLayoutFrame } from "../../tui/src/layout.ts";
import type { AgentSession } from "../src/core/agent-session.ts";
import type { ReadonlyFooterDataProvider } from "../src/core/footer-data-provider.ts";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import type { FileEntry } from "../src/core/session-manager.ts";
import { accumulateSessionStats } from "../src/core/usage-totals.ts";
import { createChatViewport } from "../src/modes/interactive/chat-viewport.ts";
import { CustomEditor } from "../src/modes/interactive/components/custom-editor.ts";
import { FooterComponent } from "../src/modes/interactive/components/footer.ts";
import { type StatusIndicator, WorkingStatusIndicator } from "../src/modes/interactive/components/status-indicator.ts";
import { getEditorTheme, initTheme, theme } from "../src/modes/interactive/theme/theme.ts";

// While a reply or a tool call streams in, the fullscreen TUI draws a frame up to 60 times a second, and the input dock
// under the transcript (status, editor with the spinner in its border, footer) is drawn on each. The dock is rendered once
// a frame and keeps what it can from the frame before. Whatever changes (the spinner, the status text, the editor's text
// and cursor, the footer's data, the terminal's size, the theme), every frame must be what the same components draw with
// every cache cleared.

/** What the footer reads, changed by the steps. */
interface FooterState {
	modelId: string;
	provider: string;
	reasoning: boolean;
	thinkingLevel: string;
	sessionName: string | undefined;
	cwd: string;
	branch: string | null;
	providerCount: number;
	statuses: Map<string, string>;
	entries: Array<Record<string, unknown>>;
	contextPercent: number | null;
	routed: { model: { id: string }; thinkingLevel?: string } | undefined;
	subscription: boolean;
}

function createFooterSource(state: FooterState): { session: AgentSession; footerData: ReadonlyFooterDataProvider } {
	const session = {
		get state() {
			return {
				model: {
					id: state.modelId,
					provider: state.provider,
					contextWindow: 200_000,
					reasoning: state.reasoning,
				},
				thinkingLevel: state.thinkingLevel,
			};
		},
		sessionManager: {
			getEntries: () => state.entries,
			// The fixtures are partial session entries: only the fields the footer reads.
			getEntryStats: () => accumulateSessionStats(state.entries as unknown as FileEntry[]),
			getEntryCount: () => state.entries.length,
			getSessionId: () => "session",
			getLeafId: () => (state.entries.length === 0 ? null : `leaf-${state.entries.length}`),
			getSessionName: () => state.sessionName,
			getCwd: () => state.cwd,
		},
		getContextUsage: () => ({ contextWindow: 200_000, percent: state.contextPercent }),
		get routedModel() {
			return state.routed;
		},
		modelRuntime: { isUsingSubscription: () => state.subscription },
	};
	const footerData: ReadonlyFooterDataProvider = {
		getGitBranch: () => state.branch,
		getExtensionStatuses: () => state.statuses,
		getAvailableProviderCount: () => state.providerCount,
		onBranchChange: () => () => {},
	};
	return { session: session as unknown as AgentSession, footerData };
}

/** The input dock and transcript as InteractiveMode lays them out in the fullscreen TUI. */
class Dock {
	readonly terminal = { rows: 40, columns: 100 };
	readonly ui: TUI;
	readonly editor: CustomEditor;
	readonly footerContainer = new Container();
	readonly statusContainer = new Container();
	readonly pendingMessages = new Container();
	readonly widgetsAbove = new Container();
	readonly widgetsBelow = new Container();
	readonly transcript = new Container();
	readonly root: Component;
	readonly dock: VStack;
	indicator: StatusIndicator | undefined;
	footer: FooterComponent;
	private readonly source: { session: AgentSession; footerData: ReadonlyFooterDataProvider };
	private autoCompact = true;

	constructor(footerState: FooterState, paddingX: number) {
		this.ui = { requestRender: () => {}, terminal: this.terminal } as unknown as TUI;
		this.editor = new CustomEditor(this.ui, getEditorTheme(), KeybindingsManager.create(), {
			paddingX,
			embedWorkingStatus: true,
		});
		this.editor.focused = true;
		const editorContainer = new Container();
		editorContainer.addChild(this.editor);
		this.source = createFooterSource(footerState);
		this.footer = new FooterComponent(this.source.session, this.source.footerData);
		this.footerContainer.addChild(this.footer);
		const viewport = createChatViewport({
			document: this.transcript,
			pendingMessages: this.pendingMessages,
			status: this.statusContainer,
			widgetsAbove: this.widgetsAbove,
			editor: editorContainer,
			widgetsBelow: this.widgetsBelow,
			footer: this.footerContainer,
		});
		this.root = viewport.root;
		this.dock = (this.root as VStack).children[1] as VStack;
		this.showIndicator("Working...");
	}

	showIndicator(message: string): void {
		this.indicator?.dispose();
		this.indicator = new WorkingStatusIndicator(this.ui, message, undefined, (text) => this.editor.borderColor(text));
		this.editor.setWorkingStatusIndicator(this.indicator);
	}

	hideIndicator(): void {
		this.indicator?.dispose();
		this.indicator = undefined;
		this.editor.setWorkingStatusIndicator(undefined);
	}

	setAutoCompact(enabled: boolean): void {
		this.autoCompact = enabled;
		this.footer.setAutoCompactEnabled(enabled);
	}

	/** A footer built from nothing, in place of the one drawn so far. */
	replaceFooter(): void {
		this.footer = new FooterComponent(this.source.session, this.source.footerData);
		this.footer.setAutoCompactEnabled(this.autoCompact);
		this.footerContainer.clear();
		this.footerContainer.addChild(this.footer);
	}

	frame(): string[] {
		return renderLayoutFrame(this.root, this.terminal.columns, this.terminal.rows, () => {}).lines;
	}

	dispose(): void {
		this.indicator?.dispose();
	}
}

/** Deterministic pseudo-random numbers. */
function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state * 1103515245 + 12345) % 2147483648;
		return state / 2147483648;
	};
}

const KEYS = ["a", "Z", " ", "é", "日", "😀", "\x7f", "\x1b[D", "\x1b[C", "\x1b[A", "\x1b[B", "\x01", "\x05"];
const MESSAGES = [
	"Working...",
	"Reading files",
	"Thinking about a much longer status line ".repeat(4),
	"日本語の状態",
	"",
];
// (Not narrower: at a wrapping width of 1 the editor recurses without end on a wide character, before and after this.)
const WIDTHS = [100, 120, 80, 40, 20, 12, 7, 8, 9, 200];

/** Steps that change what both docks read (timers, the footer's data, the theme, the environment): applied once. */
const SHARED_STEPS = new Set(["spinner", "footer", "theme kept", "environment"]);

/** `draw(count)`: a choice from 0 to count - 1, the same for both docks. */
type Step = { name: string; apply: (dock: Dock, draw: (count: number) => number) => void };

function choose<T>(items: readonly T[], draw: (count: number) => number): T {
	return items[draw(items.length)]!;
}

function steps(state: FooterState): Step[] {
	const usage = (input: number, output: number, cacheRead: number, cost: number) => ({
		input,
		output,
		cacheRead,
		cacheWrite: 0,
		cost: { total: cost },
	});
	return [
		{ name: "spinner", apply: () => vi.advanceTimersByTime(80) },
		{ name: "spinner", apply: () => vi.advanceTimersByTime(80) },
		{ name: "spinner", apply: () => vi.advanceTimersByTime(80) },
		{ name: "nothing", apply: () => {} },
		{ name: "status text", apply: (dock, draw) => dock.indicator?.setMessage(choose(MESSAGES, draw)) },
		{ name: "key", apply: (dock, draw) => dock.editor.handleInput(choose(KEYS, draw)) },
		{ name: "key", apply: (dock, draw) => dock.editor.handleInput(choose(KEYS, draw)) },
		{
			name: "editor text",
			apply: (dock, draw) =>
				dock.editor.setText(
					choose(["", "one line", "two\nlines", "a long line that wraps ".repeat(12), "many\n".repeat(20)], draw),
				),
		},
		{
			name: "border color",
			apply: (dock, draw) => {
				dock.editor.borderColor = theme.getThinkingBorderColor(
					choose(["off", "minimal", "low", "medium", "high", "xhigh"] as const, draw),
				);
			},
		},
		{
			name: "indicator",
			apply: (dock, draw) => {
				if (draw(3) === 0) dock.hideIndicator();
				else dock.showIndicator(choose(MESSAGES, draw));
			},
		},
		{
			name: "footer",
			apply: (_dock, draw) => {
				switch (draw(12)) {
					case 0:
						state.branch = state.branch === "main" ? "feature/a-very-long-branch-name-for-the-footer" : "main";
						break;
					case 1:
						state.statuses.set(`ext${draw(3)}`, choose(["ready", "busy\twith\ntabs", "✓ done"], draw));
						break;
					case 2:
						state.statuses.clear();
						break;
					case 3:
						state.modelId = choose(["model-a", "a-model-with-a-rather-long-identifier", "m"], draw);
						state.reasoning = !state.reasoning;
						break;
					case 4:
						state.thinkingLevel = choose(["off", "low", "high"], draw);
						break;
					case 5:
						state.sessionName = choose([undefined, "my session", "セッション"], draw);
						break;
					case 6:
						state.entries.push({
							type: "message",
							message: {
								role: "assistant",
								usage: usage(draw(50_000), draw(5_000), draw(90_000), draw(1000) / 100),
							},
						});
						break;
					case 7:
						state.contextPercent = choose([null, 3.5, 75.2, 95], draw);
						state.entries.push({ type: "usage", usage: usage(1, 1, 0, 0) });
						break;
					case 8:
						state.providerCount = state.providerCount === 1 ? 3 : 1;
						break;
					case 9:
						state.routed = state.routed ? undefined : { model: { id: "routed-model" }, thinkingLevel: "low" };
						break;
					case 10:
						state.subscription = !state.subscription;
						break;
					default:
						state.cwd = choose(["/tmp/project", `${process.env.HOME ?? "/home/user"}/work/pi`, "/"], draw);
				}
			},
		},
		{ name: "auto-compact", apply: (dock, draw) => dock.setAutoCompact(draw(2) === 0) },
		{
			name: "width",
			apply: (dock, draw) => {
				dock.terminal.columns = choose(WIDTHS, draw);
			},
		},
		{
			name: "height",
			apply: (dock, draw) => {
				dock.terminal.rows = choose([40, 12, 6, 3, 1, 60], draw);
			},
		},
		{
			name: "pending message",
			apply: (dock, draw) => {
				const length = draw(4);
				if (length === 0) dock.pendingMessages.clear();
				else dock.pendingMessages.addChild(new Text(`Queued: ${"follow-up ".repeat(length * 3)}`, 1, 0));
			},
		},
		{
			name: "widget",
			apply: (dock, draw) => {
				const container = draw(2) === 0 ? dock.widgetsAbove : dock.widgetsBelow;
				if (container.children.length > 0) container.clear();
				else container.addChild(new Text("widget line one\nwidget line two", 0, 0));
			},
		},
		{
			name: "transcript",
			apply: (dock, draw) => dock.transcript.addChild(new Text(`Message ${draw(100)} `.repeat(10), 1, 1)),
		},
		{
			name: "theme",
			apply: (dock, draw) => {
				initTheme(draw(2) === 0 ? "light" : "dark");
				// (InteractiveMode invalidates the whole UI when the theme changes.)
				dock.root.invalidate();
			},
		},
		{
			// The theme changed with nothing invalidated: what the editor and the footer keep must not hide it. (The
			// spinner takes the new colors at its next tick.)
			name: "theme kept",
			apply: (_dock, draw) => {
				initTheme(draw(2) === 0 ? "light" : "dark");
				vi.advanceTimersByTime(80);
			},
		},
		{
			name: "focus",
			apply: (dock) => {
				dock.editor.focused = !dock.editor.focused;
			},
		},
		{ name: "padding", apply: (dock, draw) => dock.editor.setPaddingX(draw(4)) },
		{
			name: "environment",
			apply: (_dock, draw) => {
				if (draw(2) === 0) vi.stubEnv("PI_EXPERIMENTAL", process.env.PI_EXPERIMENTAL === "1" ? "0" : "1");
				else vi.stubEnv("HOME", choose(["/tmp", "/home/user", "/tmp/project"], draw));
			},
		},
	];
}

function initialFooterState(): FooterState {
	return {
		modelId: "test-model",
		provider: "test",
		reasoning: true,
		thinkingLevel: "medium",
		sessionName: undefined,
		cwd: "/tmp/project",
		branch: "main",
		providerCount: 1,
		statuses: new Map(),
		entries: [],
		contextPercent: 12.3,
		routed: undefined,
		subscription: false,
	};
}

describe("input dock frames", () => {
	beforeEach(() => {
		initTheme("dark");
		vi.useFakeTimers();
	});
	afterEach(() => {
		vi.useRealTimers();
		initTheme("dark");
	});

	for (const paddingX of [0, 1, 2]) {
		for (const seed of [1, 2, 3, 4]) {
			test(`every frame is what the dock draws from nothing (padding ${paddingX}, seed ${seed})`, () => {
				// The dock drawn frame after frame, and the same dock driven the same way, with every cache cleared and a new
				// footer before each frame.
				const state = initialFooterState();
				const live = new Dock(state, paddingX);
				const reference = new Dock(state, paddingX);
				const editorRender = vi.spyOn(live.editor, "render");
				const next = random(seed * 7919 + paddingX);
				const all = steps(state);
				const log: string[] = [];
				try {
					for (let index = 0; index < 600; index++) {
						const step = all[Math.floor(next() * all.length)]!;
						const draws: number[] = [];
						step.apply(live, (count) => {
							const value = Math.floor(next() * count);
							draws.push(value);
							return value;
						});
						// (The timers and the footer's data are shared: they changed for both.)
						let replayed = 0;
						if (!SHARED_STEPS.has(step.name)) step.apply(reference, () => draws[replayed++]!);
						log.push(`${step.name}${draws.length > 0 ? `(${draws.join(",")})` : ""}`);
						const label = log.slice(-12).join(" ");

						const rendersBefore = editorRender.mock.calls.length;
						const drawn = live.frame();
						// The editor is rendered once a frame, not once to measure the dock and again to lay it out.
						expect(editorRender.mock.calls.length - rendersBefore, label).toBe(1);

						reference.root.invalidate();
						// (The indicator is drawn by the editor, not a child of the dock, and invalidating it would rebuild its
						// text with the theme's colors, which the drawn one does at its next tick. Setting the background it
						// has drops what it rendered.)
						reference.indicator?.setCustomBgFn(undefined);
						reference.replaceFooter();
						expect(drawn, label).toEqual(reference.frame());

						// What the dock's own render() draws, when the dock fits under the transcript.
						const dockLines = reference.dock.render(reference.terminal.columns);
						if (dockLines.length < reference.terminal.rows) {
							expect(drawn.slice(drawn.length - dockLines.length), label).toEqual(dockLines);
						}
					}
				} finally {
					live.dispose();
					reference.dispose();
				}
			});
		}
	}
});
