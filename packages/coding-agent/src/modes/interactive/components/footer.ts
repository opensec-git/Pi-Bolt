import { isAbsolute, relative, resolve, sep } from "node:path";
import { type Component, truncateToWidth, visibleWidth } from "@earendil-works/pi-tui";
import type { AgentSession } from "../../../core/agent-session.ts";
import { areExperimentalFeaturesEnabled } from "../../../core/experimental.ts";
import type { ContextUsage } from "../../../core/extensions/types.ts";
import type { ReadonlyFooterDataProvider } from "../../../core/footer-data-provider.ts";
import { addUsageToTotals, createUsageTotals, type UsageTotals } from "../../../core/usage-totals.ts";
import { type Theme, theme } from "../theme/theme.ts";

/**
 * Sanitize text for display in a single-line status.
 * Removes newlines, tabs, carriage returns, and other control characters.
 */
function sanitizeStatusText(text: string): string {
	// Replace newlines, tabs, carriage returns with space, then collapse multiple spaces
	return text
		.replace(/[\r\n\t]/g, " ")
		.replace(/ +/g, " ")
		.trim();
}

/**
 * Format token counts for compact footer display.
 */
export function formatTokens(count: number): string {
	if (count < 1000) return count.toString();
	if (count < 10000) return `${(count / 1000).toFixed(1)}k`;
	if (count < 1000000) return `${Math.round(count / 1000)}k`;
	if (count < 10000000) return `${(count / 1000000).toFixed(1)}M`;
	return `${Math.round(count / 1000000)}M`;
}

export function formatCwdForFooter(cwd: string, home: string | undefined): string {
	if (!home) return cwd;

	const resolvedCwd = resolve(cwd);
	const resolvedHome = resolve(home);
	const relativeToHome = relative(resolvedHome, resolvedCwd);
	const isInsideHome =
		relativeToHome === "" ||
		(relativeToHome !== ".." && !relativeToHome.startsWith(`..${sep}`) && !isAbsolute(relativeToHome));

	if (!isInsideHome) return cwd;
	return relativeToHome === "" ? "~" : `~${sep}${relativeToHome}`;
}

/** What the footer's lines are drawn from (FooterComponent.renderInputs()). */
interface FooterInputs {
	width: number;
	theme: Theme;
	/** The working directory, with the git branch and the session name. */
	pwd: string;
	input: number;
	output: number;
	cacheRead: number;
	cacheWrite: number;
	cost: number;
	latestCacheHitRate: number | undefined;
	contextWindow: number;
	contextPercentValue: number;
	/** Whether the context usage's percentage is known (not null). */
	contextPercentKnown: boolean;
	usingSubscription: boolean;
	autoCompactEnabled: boolean;
	experimental: boolean;
	hasModel: boolean;
	modelId: string;
	provider: string | undefined;
	reasoning: boolean;
	thinkingLevel: string;
	routed: boolean;
	routedModelId: string | undefined;
	routedThinkingLevel: string | undefined;
	providerCount: number;
	/** The extension statuses, in the map's order. */
	extensionStatuses: Array<[string, string]>;
}

/** The inputs compared by value: all but the extension statuses. */
const FOOTER_VALUE_INPUTS = [
	"width",
	"theme",
	"pwd",
	"input",
	"output",
	"cacheRead",
	"cacheWrite",
	"cost",
	"latestCacheHitRate",
	"contextWindow",
	"contextPercentValue",
	"contextPercentKnown",
	"usingSubscription",
	"autoCompactEnabled",
	"experimental",
	"hasModel",
	"modelId",
	"provider",
	"reasoning",
	"thinkingLevel",
	"routed",
	"routedModelId",
	"routedThinkingLevel",
	"providerCount",
] as const satisfies ReadonlyArray<keyof FooterInputs>;

// (Every input is compared: this fails to type-check when one is added to FooterInputs and not to the list.)
const allFooterInputsCompared: Exclude<
	keyof FooterInputs,
	(typeof FOOTER_VALUE_INPUTS)[number] | "extensionStatuses"
> extends never
	? true
	: never = true;
void allFooterInputsCompared;

const NO_EXTENSION_STATUSES: Array<[string, string]> = [];

function sameFooterInputs(a: FooterInputs, b: FooterInputs): boolean {
	for (const key of FOOTER_VALUE_INPUTS) {
		if (!Object.is(a[key], b[key])) return false;
	}
	const statuses = a.extensionStatuses;
	if (statuses.length !== b.extensionStatuses.length) return false;
	for (let i = 0; i < statuses.length; i++) {
		const [key, text] = statuses[i]!;
		const [otherKey, otherText] = b.extensionStatuses[i]!;
		if (key !== otherKey || text !== otherText) return false;
	}
	return true;
}

/** The footer's lines: drawn from `inputs` alone. */
function renderFooterLines(inputs: FooterInputs): string[] {
	const { width, theme } = inputs;
	const contextWindow = inputs.contextWindow;
	const contextPercentValue = inputs.contextPercentValue;
	const contextPercent = inputs.contextPercentKnown ? contextPercentValue.toFixed(1) : "?";
	const pwd = inputs.pwd;

	// Build stats line
	const statsParts = [];
	if (inputs.input) statsParts.push(`↑${formatTokens(inputs.input)}`);
	if (inputs.output) statsParts.push(`↓${formatTokens(inputs.output)}`);
	if (inputs.cacheRead) statsParts.push(`R${formatTokens(inputs.cacheRead)}`);
	if (inputs.cacheWrite) statsParts.push(`W${formatTokens(inputs.cacheWrite)}`);
	if ((inputs.cacheRead > 0 || inputs.cacheWrite > 0) && inputs.latestCacheHitRate !== undefined) {
		statsParts.push(`CH${inputs.latestCacheHitRate.toFixed(1)}%`);
	}

	const usingSubscription = inputs.usingSubscription;
	if (inputs.cost || usingSubscription) {
		const costStr = `$${inputs.cost.toFixed(3)}${usingSubscription ? " (sub)" : ""}`;
		statsParts.push(costStr);
	}

	// Colorize context percentage based on usage
	let contextPercentStr: string;
	const autoIndicator = inputs.autoCompactEnabled ? " (auto)" : "";
	const contextPercentDisplay =
		contextPercent === "?"
			? `?/${formatTokens(contextWindow)}${autoIndicator}`
			: `${contextPercent}%/${formatTokens(contextWindow)}${autoIndicator}`;
	if (contextPercentValue > 90) {
		contextPercentStr = theme.fg("error", contextPercentDisplay);
	} else if (contextPercentValue > 70) {
		contextPercentStr = theme.fg("warning", contextPercentDisplay);
	} else {
		contextPercentStr = contextPercentDisplay;
	}
	statsParts.push(contextPercentStr);
	if (inputs.experimental) {
		statsParts.push(`${theme.fg("dim", "•")} ${theme.bold(theme.fg("warning", "xp"))}`);
	}

	let statsLeft = statsParts.join(" ");

	// Add model name on the right side, plus thinking level if model supports it
	const modelName = inputs.modelId;

	let statsLeftWidth = visibleWidth(statsLeft);

	// If statsLeft is too wide, truncate it
	if (statsLeftWidth > width) {
		statsLeft = truncateToWidth(statsLeft, width, "...");
		statsLeftWidth = visibleWidth(statsLeft);
	}

	// Calculate available space for padding (minimum 2 spaces between stats and model)
	const minPadding = 2;

	// Add thinking level indicator if model supports reasoning
	let rightSideWithoutProvider = modelName;
	if (inputs.reasoning) {
		const thinkingLevel = inputs.thinkingLevel;
		rightSideWithoutProvider =
			thinkingLevel === "off" ? `${modelName} • thinking off` : `${modelName} • ${thinkingLevel}`;
	}
	// A virtual model routes each request; show where the latest response went.
	if (inputs.routed) {
		const level = inputs.routedThinkingLevel ? ` • ${inputs.routedThinkingLevel}` : "";
		rightSideWithoutProvider += ` → ${inputs.routedModelId}${level}`;
	}

	// Prepend the provider in parentheses if there are multiple providers and there's enough room
	let rightSide = rightSideWithoutProvider;
	if (inputs.providerCount > 1 && inputs.hasModel) {
		rightSide = `(${inputs.provider}) ${rightSideWithoutProvider}`;
		if (statsLeftWidth + minPadding + visibleWidth(rightSide) > width) {
			// Too wide, fall back
			rightSide = rightSideWithoutProvider;
		}
	}

	const rightSideWidth = visibleWidth(rightSide);
	const totalNeeded = statsLeftWidth + minPadding + rightSideWidth;

	let statsLine: string;
	if (totalNeeded <= width) {
		// Both fit - add padding to right-align model
		const padding = " ".repeat(width - statsLeftWidth - rightSideWidth);
		statsLine = statsLeft + padding + rightSide;
	} else {
		// Need to truncate right side
		const availableForRight = width - statsLeftWidth - minPadding;
		if (availableForRight > 0) {
			const truncatedRight = truncateToWidth(rightSide, availableForRight, "");
			const truncatedRightWidth = visibleWidth(truncatedRight);
			const padding = " ".repeat(Math.max(0, width - statsLeftWidth - truncatedRightWidth));
			statsLine = statsLeft + padding + truncatedRight;
		} else {
			// Not enough space for right side at all
			statsLine = statsLeft;
		}
	}

	// Apply dim to each part separately. statsLeft may contain color codes (for context %)
	// that end with a reset, which would clear an outer dim wrapper. So we dim the parts
	// before and after the colored section independently.
	const dimStatsLeft = theme.fg("dim", statsLeft);
	const remainder = statsLine.slice(statsLeft.length); // padding + rightSide
	const dimRemainder = theme.fg("dim", remainder);

	const pwdLine = truncateToWidth(theme.fg("dim", pwd), width, theme.fg("dim", "..."));
	const lines = [pwdLine, dimStatsLeft + dimRemainder];

	// Add extension statuses on a single line, sorted by key alphabetically
	const extensionStatuses = inputs.extensionStatuses;
	if (extensionStatuses.length > 0) {
		const sortedStatuses = extensionStatuses
			.slice()
			.sort(([a], [b]) => a.localeCompare(b))
			.map(([, text]) => sanitizeStatusText(text));
		const statusLine = sortedStatuses.join(" ");
		// Truncate to terminal width with dim ellipsis for consistency with footer style
		lines.push(truncateToWidth(statusLine, width, theme.fg("dim", "...")));
	}

	return lines;
}

interface SessionStats {
	session: AgentSession;
	sessionId: string;
	leafId: string | null;
	entryCount: number;
	limitsModel: unknown;
	usageTotals: UsageTotals;
	latestCacheHitRate: number | undefined;
	contextUsage: ContextUsage | undefined;
}

/**
 * Footer component that shows pwd, token stats, and context usage.
 * Computes token/context stats from session, gets git branch and extension statuses from provider.
 */
export class FooterComponent implements Component {
	private autoCompactEnabled = true;
	private session: AgentSession;
	private footerData: ReadonlyFooterDataProvider;
	private sessionStats?: SessionStats;
	private formattedCwd?: { cwd: string; home: string | undefined; text: string };
	/** What render() drew last, and what it drew it from. */
	private rendered?: { inputs: FooterInputs; lines: readonly string[] };

	constructor(session: AgentSession, footerData: ReadonlyFooterDataProvider) {
		this.session = session;
		this.footerData = footerData;
	}

	setSession(session: AgentSession): void {
		this.session = session;
	}

	setAutoCompactEnabled(enabled: boolean): void {
		this.autoCompactEnabled = enabled;
	}

	/**
	 * Keeps the lines render() keeps: they are kept with everything they are drawn from (FooterInputs, the theme instance
	 * among them, whose colors do not change), and drawn again when any of it changes. Interactive mode invalidates the
	 * footer on every agent event, and while a reply streams in that is every chunk: the footer was drawn again for each.
	 * (The git branch is cached and invalidated by the provider.)
	 */
	invalidate(): void {}

	/**
	 * Clean up resources.
	 * Git watcher cleanup now handled by provider.
	 */
	dispose(): void {
		// Git watcher cleanup handled by provider
	}

	/**
	 * Usage totals and context usage scan the whole session, and the footer renders on every frame.
	 * Entries are append-only and every append moves the leaf, so the results only change with the
	 * session, leaf, entry count, or the model whose context window applies.
	 */
	private getSessionStats(): SessionStats {
		const sessionManager = this.session.sessionManager;
		const entryCount = sessionManager.getEntryCount();
		const sessionId = sessionManager.getSessionId();
		const leafId = sessionManager.getLeafId();
		const limitsModel = this.session.routedModel?.model ?? this.session.model;
		const cached = this.sessionStats;
		if (
			cached &&
			cached.session === this.session &&
			cached.sessionId === sessionId &&
			cached.leafId === leafId &&
			cached.entryCount === entryCount &&
			cached.limitsModel === limitsModel
		) {
			return cached;
		}

		// Calculate cumulative usage from ALL session entries (not just post-compaction messages)
		const usageTotals = createUsageTotals();
		let latestCacheHitRate: number | undefined;

		for (const entry of sessionManager.getEntries()) {
			if (entry.type === "usage") {
				addUsageToTotals(usageTotals, entry.usage);
			} else if (entry.type === "message" && entry.message.role === "assistant") {
				addUsageToTotals(usageTotals, entry.message.usage);

				const latestPromptTokens =
					entry.message.usage.input + entry.message.usage.cacheRead + entry.message.usage.cacheWrite;
				latestCacheHitRate =
					latestPromptTokens > 0 ? (entry.message.usage.cacheRead / latestPromptTokens) * 100 : undefined;
			} else if (entry.type === "message" && entry.message.role === "toolResult" && entry.message.usage) {
				addUsageToTotals(usageTotals, entry.message.usage);
			} else if ((entry.type === "branch_summary" || entry.type === "compaction") && entry.usage) {
				addUsageToTotals(usageTotals, entry.usage);
			}
		}

		// Calculate context usage from session (handles compaction correctly).
		// After compaction, tokens are unknown until the next LLM response.
		const contextUsage = this.session.getContextUsage();
		this.sessionStats = {
			session: this.session,
			sessionId,
			leafId,
			entryCount,
			limitsModel,
			usageTotals,
			latestCacheHitRate,
			contextUsage,
		};
		return this.sessionStats;
	}

	/**
	 * formatCwdForFooter(), kept while the directories are the same: it resolves both paths, on every frame. (Not kept for
	 * a relative path, which resolves against the process's working directory.)
	 */
	private formatCwd(cwd: string, home: string | undefined): string {
		const cached = this.formattedCwd;
		if (cached && cached.cwd === cwd && cached.home === home) return cached.text;
		const text = formatCwdForFooter(cwd, home);
		this.formattedCwd = isAbsolute(cwd) && (home === undefined || isAbsolute(home)) ? { cwd, home, text } : undefined;
		return text;
	}

	/**
	 * Everything the footer's lines are drawn from, read from the session, the footer data and the theme. While a reply
	 * streams in, the footer is drawn on every frame and none of this changes.
	 */
	private renderInputs(width: number): FooterInputs {
		const state = this.session.state;
		const model = state.model;
		const { usageTotals, latestCacheHitRate, contextUsage } = this.getSessionStats();

		// Replace home directory with ~
		let pwd = this.formatCwd(this.session.sessionManager.getCwd(), process.env.HOME || process.env.USERPROFILE);

		// Add git branch if available
		const branch = this.footerData.getGitBranch();
		if (branch) {
			pwd = `${pwd} (${branch})`;
		}

		// Add session name if set
		const sessionName = this.session.sessionManager.getSessionName();
		if (sessionName) {
			pwd = `${pwd} • ${sessionName}`;
		}

		const routed = this.session.routedModel;
		const statuses = this.footerData.getExtensionStatuses();
		return {
			width,
			theme,
			pwd,
			input: usageTotals.input,
			output: usageTotals.output,
			cacheRead: usageTotals.cacheRead,
			cacheWrite: usageTotals.cacheWrite,
			cost: usageTotals.cost,
			latestCacheHitRate,
			contextWindow: contextUsage?.contextWindow ?? model?.contextWindow ?? 0,
			contextPercentValue: contextUsage?.percent ?? 0,
			contextPercentKnown: contextUsage?.percent !== null,
			// Kimi Coding is subscription-backed despite using API-key authentication.
			usingSubscription: model
				? model.provider === "kimi-coding" || this.session.modelRuntime.isUsingSubscription(model.provider)
				: false,
			autoCompactEnabled: this.autoCompactEnabled,
			experimental: areExperimentalFeaturesEnabled(),
			hasModel: Boolean(model),
			modelId: model?.id || "no-model",
			provider: model?.provider,
			reasoning: Boolean(model?.reasoning),
			thinkingLevel: state.thinkingLevel || "off",
			routed: Boolean(routed),
			routedModelId: routed?.model.id,
			routedThinkingLevel: routed?.thinkingLevel,
			providerCount: this.footerData.getAvailableProviderCount(),
			extensionStatuses: statuses.size === 0 ? NO_EXTENSION_STATUSES : Array.from(statuses.entries()),
		};
	}

	render(width: number): string[] {
		const inputs = this.renderInputs(width);
		const known = this.rendered;
		if (known && sameFooterInputs(known.inputs, inputs)) {
			return known.lines.slice();
		}
		const lines = renderFooterLines(inputs);
		this.rendered = { inputs, lines: lines.slice() };
		return lines;
	}
}
