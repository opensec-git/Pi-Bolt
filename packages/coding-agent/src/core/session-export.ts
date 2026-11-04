import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { resolvePath } from "../utils/paths.ts";
import { CURRENT_SESSION_VERSION, type SessionHeader, type SessionManager } from "./session-manager.ts";
import { redactJsonValue } from "./bug-report.ts";

type TrailingEntries = (parentId: string | null, timestamp: string) => readonly object[];

/**
 * Serialize the current branch and optional export-only entries as JSONL.
 *
 * Entries pass through the bug-report redactor (sensitive keys replaced,
 * credentials in URLs stripped) so shares and exports do not leak pasted
 * API keys or tokens from tool output. Secret-*looking* bare strings are
 * left alone: the redactor keys on field names and URLs, and broadening it
 * would corrupt legitimate transcript content.
 */
export function serializeSessionBranch(
	sessionManager: SessionManager,
	createTrailingEntries?: TrailingEntries,
): string {
	const timestamp = new Date().toISOString();
	const header: SessionHeader = {
		type: "session",
		version: CURRENT_SESSION_VERSION,
		id: sessionManager.getSessionId(),
		timestamp,
		cwd: sessionManager.getCwd(),
	};
	const entries: object[] = [header];
	let parentId: string | null = null;
	for (const entry of sessionManager.getBranch()) {
		entries.push({ ...entry, parentId });
		parentId = entry.id;
	}
	entries.push(...(createTrailingEntries?.(parentId, timestamp) ?? []));
	const redacted = entries.map((entry) => redactJsonValue(entry));
	return `${redacted.map((entry) => JSON.stringify(entry)).join("\n")}\n`;
}

/** Write the current session branch and optional export-only entries as JSONL. */
export function exportSessionToJsonl(
	sessionManager: SessionManager,
	outputPath?: string,
	createTrailingEntries?: TrailingEntries,
): string {
	const filePath = resolvePath(
		outputPath ?? `session-${new Date().toISOString().replace(/[:.]/g, "-")}.jsonl`,
		process.cwd(),
	);
	const dir = dirname(filePath);
	if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
	writeFileSync(filePath, serializeSessionBranch(sessionManager, createTrailingEntries));
	return filePath;
}
