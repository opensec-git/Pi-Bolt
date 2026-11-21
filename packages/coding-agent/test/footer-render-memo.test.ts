import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import type { AgentSession } from "../src/core/agent-session.ts";
import type { ReadonlyFooterDataProvider } from "../src/core/footer-data-provider.ts";
import type { SessionEntry } from "../src/core/session-manager.ts";
import { accumulateSessionStats } from "../src/core/usage-totals.ts";
import { FooterComponent } from "../src/modes/interactive/components/footer.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

// The footer is drawn on every frame and keeps its lines with everything they were drawn from. Each of those inputs,
// changed on its own, must change what it draws to what a footer that never drew before draws.

interface FooterState {
	model: { id: string; provider: string; contextWindow: number; reasoning: boolean } | undefined;
	thinkingLevel: string | undefined;
	sessionName: string | undefined;
	cwd: string;
	branch: string | null;
	providerCount: number;
	statuses: Map<string, string>;
	entries: Array<Record<string, unknown>>;
	contextUsage: { contextWindow: number; percent: number | null } | undefined;
	routed: { model: { id: string }; thinkingLevel?: string } | undefined;
	subscription: boolean;
}

function initialState(): FooterState {
	return {
		model: { id: "test-model", provider: "test", contextWindow: 200_000, reasoning: true },
		thinkingLevel: "medium",
		sessionName: undefined,
		cwd: "/tmp/project",
		branch: "main",
		providerCount: 1,
		statuses: new Map(),
		entries: [],
		contextUsage: { contextWindow: 200_000, percent: 12.3 },
		routed: undefined,
		subscription: false,
	};
}

function createSource(state: FooterState): { session: AgentSession; footerData: ReadonlyFooterDataProvider } {
	const session = {
		get state() {
			return { model: state.model, thinkingLevel: state.thinkingLevel };
		},
		get model() {
			return state.model;
		},
		sessionManager: {
			getEntries: () => state.entries,
			getEntryStats: () => accumulateSessionStats(state.entries as unknown as SessionEntry[]),
			getEntryCount: () => state.entries.length,
			getSessionId: () => "session",
			getLeafId: () => (state.entries.length === 0 ? null : `leaf-${state.entries.length}`),
			getSessionName: () => state.sessionName,
			getCwd: () => state.cwd,
		},
		getContextUsage: () => state.contextUsage,
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

function usage(input: number, output: number, cacheRead: number, cacheWrite: number, cost: number) {
	return { input, output, cacheRead, cacheWrite, cost: { total: cost } };
}

function assistantEntry(input: number, output: number, cacheRead: number, cacheWrite: number, cost: number) {
	return { type: "message", message: { role: "assistant", usage: usage(input, output, cacheRead, cacheWrite, cost) } };
}

/** Each input of the footer, changed on its own. */
const CHANGES: Array<{
	name: string;
	width?: number;
	setup?: (state: FooterState) => void;
	change: (state: FooterState, footer: FooterComponent) => void;
}> = [
	{ name: "width", width: 60, change: () => {} },
	{
		name: "theme",
		// (The context percentage over 90% is drawn in the theme's error color.)
		setup: (state) => {
			state.contextUsage = { contextWindow: 200_000, percent: 95 };
		},
		change: () => initTheme("light"),
	},
	{
		name: "cwd",
		change: (state) => {
			state.cwd = "/tmp/other";
		},
	},
	{ name: "home", change: () => vi.stubEnv("HOME", "/tmp") },
	{
		name: "branch",
		change: (state) => {
			state.branch = "feature/x";
		},
	},
	{
		name: "session name",
		change: (state) => {
			state.sessionName = "my session";
		},
	},
	{ name: "input tokens", change: (state) => state.entries.push({ type: "usage", usage: usage(1500, 0, 0, 0, 0) }) },
	{ name: "output tokens", change: (state) => state.entries.push(assistantEntry(0, 2500, 0, 0, 0)) },
	{ name: "cache read", change: (state) => state.entries.push({ type: "usage", usage: usage(0, 0, 9000, 0, 0) }) },
	{ name: "cache write", change: (state) => state.entries.push({ type: "usage", usage: usage(0, 0, 0, 700, 0) }) },
	{ name: "cost", change: (state) => state.entries.push({ type: "usage", usage: usage(0, 0, 0, 0, 1.25) }) },
	{
		name: "cache hit rate",
		setup: (state) => state.entries.push(assistantEntry(100, 0, 100, 0, 0)),
		// (A message without prompt tokens has no hit rate: the totals stay, the rate goes.)
		change: (state) => state.entries.push(assistantEntry(0, 0, 0, 0, 0)),
	},
	{
		name: "context percent",
		change: (state) => {
			state.contextUsage = { contextWindow: 200_000, percent: 95 };
			state.entries.push({ type: "custom" });
		},
	},
	{
		name: "context unknown",
		// (An unknown percentage counts as 0 for its color: only "known" changes.)
		setup: (state) => {
			state.contextUsage = { contextWindow: 200_000, percent: 0 };
		},
		change: (state) => {
			state.contextUsage = { contextWindow: 200_000, percent: null };
			state.entries.push({ type: "custom" });
		},
	},
	{
		name: "context window",
		change: (state) => {
			state.contextUsage = { contextWindow: 1_000_000, percent: 12.3 };
			state.entries.push({ type: "custom" });
		},
	},
	{
		name: "subscription",
		change: (state) => {
			state.subscription = true;
		},
	},
	{ name: "auto-compaction", change: (_state, footer) => footer.setAutoCompactEnabled(false) },
	{ name: "experimental", change: () => vi.stubEnv("PI_EXPERIMENTAL", "1") },
	{
		name: "no model",
		change: (state) => {
			state.model = undefined;
		},
	},
	{
		// (A model without an id or a provider draws as no model, but for the provider shown in front of it.)
		name: "model present",
		setup: (state) => {
			state.providerCount = 3;
			state.model = { id: "", provider: undefined as unknown as string, contextWindow: 1, reasoning: false };
		},
		change: (state) => {
			state.model = undefined;
		},
	},
	{
		name: "model id",
		change: (state) => {
			state.model = { ...state.model!, id: "other-model" };
		},
	},
	{
		name: "reasoning",
		change: (state) => {
			state.model = { ...state.model!, reasoning: false };
		},
	},
	{
		name: "thinking level",
		change: (state) => {
			state.thinkingLevel = "high";
		},
	},
	{
		name: "thinking off",
		change: (state) => {
			state.thinkingLevel = undefined;
		},
	},
	{
		name: "routed",
		change: (state) => {
			state.routed = { model: { id: "routed-model" } };
		},
	},
	{
		name: "routed model",
		setup: (state) => {
			state.routed = { model: { id: "routed-a" } };
		},
		change: (state) => {
			state.routed = { model: { id: "routed-b" } };
		},
	},
	{
		name: "routed without an id",
		change: (state) => {
			state.routed = { model: { id: undefined as unknown as string } };
		},
	},
	{
		name: "provider count",
		change: (state) => {
			state.providerCount = 3;
		},
	},
	{ name: "extension status", change: (state) => state.statuses.set("ext", "busy") },
];

describe("footer render memo", () => {
	beforeEach(() => initTheme("dark"));
	afterEach(() => initTheme("dark"));

	for (const { name, width, setup, change } of CHANGES) {
		test(`draws again when the ${name} changes`, () => {
			const state = initialState();
			state.statuses.set("a", "ready");
			setup?.(state);
			const source = createSource(state);
			const footer = new FooterComponent(source.session, source.footerData);
			const before = footer.render(100);
			expect(footer.render(100)).toEqual(before);
			change(state, footer);
			const fresh = new FooterComponent(source.session, source.footerData);
			if (name === "auto-compaction") fresh.setAutoCompactEnabled(false);
			const drawn = footer.render(width ?? 100);
			expect(drawn).toEqual(fresh.render(width ?? 100));
			expect(drawn).not.toEqual(before);
		});
	}

	test("draws again when the provider, the routed thinking level or an extension status's text changes", () => {
		const state = initialState();
		state.providerCount = 3;
		state.routed = { model: { id: "routed-model" } };
		state.statuses.set("ext", "ready");
		const source = createSource(state);
		const footer = new FooterComponent(source.session, source.footerData);
		const steps: Array<(state: FooterState) => void> = [
			(state) => {
				state.model = { ...state.model!, provider: "other" };
			},
			(state) => {
				state.routed = { model: { id: "routed-model" }, thinkingLevel: "low" };
			},
			(state) => state.statuses.set("ext", "done"),
			(state) => {
				state.statuses.delete("ext");
				state.statuses.set("ext2", "other");
			},
			(state) => {
				state.routed = { model: { id: "routed-model" }, thinkingLevel: "low" };
			},
		];
		let before = footer.render(100);
		for (const [index, step] of steps.entries()) {
			step(state);
			const drawn = footer.render(100);
			expect(drawn, `step ${index}`).toEqual(new FooterComponent(source.session, source.footerData).render(100));
			if (index < 4) expect(drawn, `step ${index}`).not.toEqual(before);
			before = drawn;
		}
	});

	test("returns lines of its own each time", () => {
		const source = createSource(initialState());
		const footer = new FooterComponent(source.session, source.footerData);
		const first = footer.render(100);
		const expected = first.slice();
		first.push("changed");
		first[0] = "changed";
		expect(footer.render(100)).toEqual(expected);
	});
});
