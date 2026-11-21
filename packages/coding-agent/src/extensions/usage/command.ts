/**
 * The `/usage` command as an extension: session tokens, cost and context, plus account balance or
 * quota where the provider exposes it (DeepSeek, OpenRouter, Codex, and, for a Claude subscription, Claude
 * Code's cached limits).
 */

import type { ExtensionCommandContext, ExtensionContext } from "../../core/extensions/types.ts";
import { addUsageToTotals, createUsageTotals } from "../../core/usage-totals.ts";
import {
	buildUsageOverview,
	type DeepSeekBalance,
	formatAge,
	type OpenRouterCredits,
	parseCodexUsage,
	readClaudeCachedUsage,
	type UsageBar,
	type UsageLine,
} from "./overview.ts";
import { UsagePopupComponent } from "./popup.ts";

const FETCH_TIMEOUT_MS = 10_000;

type Model = NonNullable<ExtensionContext["model"]>;

/**
 * Origin of the endpoint requests for `model` are sent to. A proxy or regional base URL
 * (`models.json`, provider auth) is honored so the key only goes where the user pointed it.
 */
async function resolveOrigin(
	ctx: ExtensionContext,
	model: Model,
): Promise<{ apiKey: string; origin: string } | undefined> {
	const auth = await ctx.modelRegistry.getApiKeyAndHeaders(model);
	if (!auth.ok || !auth.apiKey) return undefined;
	return { apiKey: auth.apiKey, origin: new URL(auth.baseUrl ?? model.baseUrl).origin };
}

async function fetchJson(
	url: string,
	apiKey: string,
	extraHeaders: Record<string, string> = {},
): Promise<{ ok: true; body: unknown } | { error: string }> {
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
	try {
		const res = await fetch(url, {
			headers: { Authorization: `Bearer ${apiKey}`, ...extraHeaders },
			signal: controller.signal,
		});
		if (!res.ok) return { error: `${new URL(url).host} returned HTTP ${res.status}.` };
		return { ok: true, body: await res.json() };
	} catch (err) {
		return { error: err instanceof Error ? err.message : String(err) };
	} finally {
		clearTimeout(timer);
	}
}

async function fetchDeepSeekBalance(ctx: ExtensionContext, model: Model): Promise<DeepSeekBalance | { error: string }> {
	const target = await resolveOrigin(ctx, model);
	if (!target) return { error: "No DeepSeek API key configured. Run /login deepseek." };
	const res = await fetchJson(`${target.origin}/user/balance`, target.apiKey);
	return "error" in res ? res : (res.body as DeepSeekBalance);
}

async function fetchOpenRouterCredits(
	ctx: ExtensionContext,
	model: Model,
): Promise<OpenRouterCredits | { error: string }> {
	const target = await resolveOrigin(ctx, model);
	if (!target) return { error: "No OpenRouter API key configured. Run /login openrouter." };
	const res = await fetchJson(`${target.origin}/api/v1/credits`, target.apiKey);
	if ("error" in res) return res;
	const data = (res.body as { data?: { total_credits?: unknown; total_usage?: unknown } }).data;
	const totalCredits = Number(data?.total_credits);
	const totalUsage = Number(data?.total_usage);
	return {
		total_credits: Number.isFinite(totalCredits) ? totalCredits : 0,
		total_usage: Number.isFinite(totalUsage) ? totalUsage : 0,
	};
}

/** The ChatGPT account id is a claim in the OAuth access token, which Codex sends as `chatgpt-account-id`. */
function chatGptAccountId(token: string): string | undefined {
	try {
		const payload = JSON.parse(Buffer.from(token.split(".")[1] ?? "", "base64url").toString("utf8"));
		const id = payload?.["https://api.openai.com/auth"]?.chatgpt_account_id;
		return typeof id === "string" && id.length > 0 ? id : undefined;
	} catch {
		return undefined;
	}
}

async function fetchCodexUsage(ctx: ExtensionContext, model: Model): Promise<UsageBar[] | { error: string }> {
	const auth = await ctx.modelRegistry.getApiKeyAndHeaders(model);
	if (!auth.ok || !auth.apiKey) return { error: "No Codex login found. Run /login." };
	const accountId = chatGptAccountId(auth.apiKey);
	if (!accountId) return { error: "Could not read the ChatGPT account id from the Codex login." };
	const base = (auth.baseUrl ?? model.baseUrl).replace(/\/+$/, "");
	const res = await fetchJson(`${base}/wham/usage`, auth.apiKey, { "chatgpt-account-id": accountId });
	return "error" in res ? res : parseCodexUsage(res.body);
}

export async function runUsageCommand(ctx: ExtensionCommandContext): Promise<void> {
	const model = ctx.model;
	if (!model) {
		ctx.ui.notify("No model selected.", "warning");
		return;
	}

	const totals = createUsageTotals();
	for (const entry of ctx.sessionManager.getEntries()) {
		if (entry.type === "usage") {
			addUsageToTotals(totals, entry.usage);
		} else if ((entry.type === "branch_summary" || entry.type === "compaction") && entry.usage) {
			addUsageToTotals(totals, entry.usage);
		} else if (entry.type === "message") {
			const message = entry.message;
			if (message.role === "assistant") addUsageToTotals(totals, message.usage);
			else if (message.role === "toolResult" && message.usage) addUsageToTotals(totals, message.usage);
		}
	}

	const providerId = model.provider;
	const subscriptionBacked =
		ctx.modelRegistry.isUsingOAuth(model) &&
		ctx.modelRegistry.getProvider(providerId)?.auth.oauth?.isSubscription === true;

	// Claude Code caches the Claude subscription's 5h / weekly usage in ~/.claude.json. Those limits
	// are Pi's only when Pi uses the same subscription; with an API key they do not apply.
	let accountBars: UsageBar[] | undefined;
	const extraLines: UsageLine[] = [];
	if (providerId === "anthropic" && subscriptionBacked) {
		const claude = await readClaudeCachedUsage();
		if ("error" in claude) {
			extraLines.push({ label: "limits", value: claude.error, tone: "warning" });
		} else {
			accountBars = claude.bars;
			const age =
				claude.fetchedAtMs === undefined ? "age unknown" : `from ${formatAge(Date.now() - claude.fetchedAtMs)} ago`;
			extraLines.push({ label: "limits", value: `Claude Code's cached numbers, ${age}` });
		}
	} else if (providerId === "openai-codex" && subscriptionBacked) {
		const codex = await fetchCodexUsage(ctx, model);
		if (Array.isArray(codex)) accountBars = codex;
		else extraLines.push({ label: "limits", value: codex.error, tone: "error" });
	}

	const header = ctx.sessionManager.getHeader();
	const overview = buildUsageOverview({
		providerId,
		modelId: model.id,
		stats: {
			tokens: { input: totals.input, output: totals.output },
			cost: totals.cost,
			contextUsage: ctx.getContextUsage(),
		},
		durationMs: header?.timestamp ? Date.now() - new Date(header.timestamp).getTime() : null,
		deepseekBalance: providerId === "deepseek" ? await fetchDeepSeekBalance(ctx, model) : null,
		openrouterCredits: providerId === "openrouter" ? await fetchOpenRouterCredits(ctx, model) : null,
		// Only warn about unexposed limits when we have nothing to show.
		subscriptionBacked: subscriptionBacked && (accountBars?.length ?? 0) === 0,
		accountBars,
	});

	overview.lines.push(...extraLines);

	await ctx.ui.custom<void>((_tui, theme, keybindings, done) => {
		return new UsagePopupComponent(theme, keybindings, overview, () => done());
	});
}
