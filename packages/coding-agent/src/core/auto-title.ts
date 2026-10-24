/**
 * Automatic session titling.
 *
 * When the first agent run settles a session the user never named, the
 * session asks a model for a 3-6 word title and persists it through
 * `SessionManager.appendSessionInfo()` — the same entry `/name` and
 * `--name` write, so the selector, footer, and embedding hosts pick it
 * up with no extra wiring.
 *
 * Best-effort by design: every failure path (no model, no auth, error,
 * abort, timeout, unusable response) leaves the session unnamed and the
 * session untouched. The generated text is sanitized before it is stored
 * as a display name: the transcript is untrusted input.
 */

import type { StreamFn } from "@earendil-works/pi-agent-core";
import { normalizeContext, uuidv7 } from "@earendil-works/pi-ai";
import type { Api, Model, SimpleStreamOptions, TranscriptContext } from "@earendil-works/pi-ai/compat";
import type { SessionEntry } from "./session-manager.ts";

export const AUTO_TITLE_SYSTEM_PROMPT =
	"You title conversations. Reply directly with ONLY a title of 3 to 6 words for the user's task — " +
	"no analysis, no preamble. Use the same language as the task. One line. " +
	"No quotes, no trailing punctuation, no prefix.";

/** Display-name bound shared with the session selector's rendering. */
export const MAX_AUTO_TITLE_CHARS = 60;
/** The titling request is one short answer; thinking shares the cap on some providers (#9512). */
export const AUTO_TITLE_MAX_TOKENS = 200;
/** Bound on the whole generation call; a hung provider must not stall settlement. */
export const AUTO_TITLE_TIMEOUT_MS = 20_000;
/** Prompts shorter than this (slash commands, "hi") do not carry enough signal to title. */
export const AUTO_TITLE_MIN_PROMPT_CHARS = 4;
/** Cap on the prompt text sent to the titling model — titles need the opening, not the essay. */
export const AUTO_TITLE_SOURCE_MAX_CHARS = 600;

/** The first user message's text on the active branch, empty when there is none. */
export function autoTitleSourceText(entries: readonly SessionEntry[]): string {
	for (const entry of entries) {
		if (entry.type !== "message") continue;
		const message = entry.message as { role?: string; content?: unknown };
		if (message.role !== "user") continue;
		const content = message.content;
		if (typeof content === "string") return content;
		if (Array.isArray(content)) {
			return content
				.map((part) =>
					(part as { type?: string; text?: string })?.type === "text"
						? ((part as { text?: string }).text ?? "")
						: "",
				)
				.join(" ");
		}
		return "";
	}
	return "";
}

/**
 * Turn raw model output into a safe single-line display name: first line
 * only, leading quotes/bullets/label prefixes and trailing quotes removed,
 * whitespace collapsed, length capped. Null when nothing displayable is left.
 */
export function sanitizeSessionTitle(raw: string): string | null {
	const line = raw.split(/\r?\n/)[0] ?? "";
	const title = line
		.replace(/^\s*(?:title|name|session name)\s*[:\uff1a]\s*/i, "") // label prefixes models emit
		.replace(/^[\s"'`*\-–—:>;]+/, "")
		.replace(/[\s"'`*]+$/, "")
		.replace(/\s+/g, " ")
		.trim();
	if (!title) return null;
	return title.length > MAX_AUTO_TITLE_CHARS ? title.slice(0, MAX_AUTO_TITLE_CHARS).trim() : title;
}

/** A title that merely repeats the prompt's opening carries no information. */
export function isPromptEchoTitle(title: string, sourceText: string): boolean {
	return sourceText.toLowerCase().replace(/\s+/g, " ").trim().startsWith(title.toLowerCase().slice(0, 25));
}

/**
 * One-shot title request. Returns null on every failure mode (including a
 * response with no text, e.g. a reasoning model that spent the whole budget
 * on thinking) and never throws for request-level failures.
 */
export async function generateSessionTitle(
	model: Model<Api>,
	streamFunction: StreamFn,
	sourceText: string,
	signal: AbortSignal,
): Promise<string | null> {
	const context: TranscriptContext = normalizeContext({
		systemPrompt: AUTO_TITLE_SYSTEM_PROMPT,
		messages: [
			{
				role: "user",
				content: [{ type: "text", text: sourceText }],
				timestamp: Date.now(),
			},
		],
	});
	// One-off request: no cache writes, no retries, fresh routing id (compaction idiom).
	const options: SimpleStreamOptions = {
		maxTokens: AUTO_TITLE_MAX_TOKENS,
		maxRetries: 0,
		cacheRetention: "none",
		sessionId: uuidv7(),
		signal,
	};
	const message = await (await streamFunction(model, context, options)).result();
	if (message.stopReason === "error" || message.stopReason === "aborted") return null;
	const text = message.content
		.filter((part): part is { type: "text"; text: string } => part.type === "text")
		.map((part) => part.text)
		.join("");
	return sanitizeSessionTitle(text);
}
