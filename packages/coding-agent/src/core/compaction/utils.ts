/**
 * Shared utilities for compaction and branch summarization.
 */

import type { AgentMessage } from "@earendil-works/pi-agent-core";
import { contentText, type Message } from "@earendil-works/pi-ai";

// ============================================================================
// File Operation Tracking
// ============================================================================

export interface FileOperations {
	read: Set<string>;
	written: Set<string>;
	edited: Set<string>;
}

export function createFileOps(): FileOperations {
	return {
		read: new Set(),
		written: new Set(),
		edited: new Set(),
	};
}

/**
 * Extract file operations from tool calls in an assistant message, or from the nested calls
 * recorded on a tool result.
 */
export function extractFileOpsFromMessage(message: AgentMessage, fileOps: FileOperations): void {
	if (message.role === "toolResult") {
		// Calls made from codemode scripts are recorded on the script's result.
		for (const call of message.nestedCalls?.calls ?? []) addFileOp(call.name, call.arguments, fileOps);
		return;
	}
	if (message.role !== "assistant") return;
	if (!("content" in message) || !Array.isArray(message.content)) return;

	for (const block of message.content) {
		if (typeof block !== "object" || block === null) continue;
		if (!("type" in block) || block.type !== "toolCall") continue;
		if (!("arguments" in block) || !("name" in block)) continue;
		addFileOp(block.name, block.arguments as Record<string, unknown> | undefined, fileOps);
	}
}

function addFileOp(toolName: string, args: Record<string, unknown> | undefined, fileOps: FileOperations): void {
	const path = typeof args?.path === "string" ? args.path : undefined;
	if (!path) return;
	switch (toolName) {
		case "read":
			touchFile(fileOps.read, path);
			break;
		case "write":
			touchFile(fileOps.written, path);
			break;
		case "edit":
			touchFile(fileOps.edited, path);
			break;
	}
}

/** Add a path as the most recently touched: sets keep insertion order, so re-adding moves it to the end. */
function touchFile(files: Set<string>, path: string): void {
	files.delete(path);
	files.add(path);
}

/**
 * Most paths each file list keeps. Lists are carried from one compaction (or branch summary) to the
 * next, so without a cap a long session appends thousands of stale paths to every summary.
 */
export const MAX_FILE_LIST_ENTRIES = 100;

export interface FileLists {
	/** Files only read, not modified, sorted */
	readFiles: string[];
	/** Files edited or written, sorted */
	modifiedFiles: string[];
	/** Older read-only files dropped by the {@link MAX_FILE_LIST_ENTRIES} cap */
	omittedReadFiles: number;
	/** Older modified files dropped by the {@link MAX_FILE_LIST_ENTRIES} cap */
	omittedModifiedFiles: number;
}

/**
 * Keep the most recently touched paths. Each set is ordered from least to most recently touched,
 * so a path is ranked by its distance from the end of the sets that contain it.
 */
function keepMostRecent(paths: string[], sets: Set<string>[]): { kept: string[]; omitted: number } {
	if (paths.length <= MAX_FILE_LIST_ENTRIES) return { kept: paths, omitted: 0 };
	const age = new Map<string, number>();
	for (const set of sets) {
		let index = set.size;
		for (const path of set) {
			index--;
			age.set(path, Math.min(age.get(path) ?? index, index));
		}
	}
	const newest = [...paths].sort((a, b) => (age.get(a) ?? 0) - (age.get(b) ?? 0)).slice(0, MAX_FILE_LIST_ENTRIES);
	return { kept: newest.sort(), omitted: paths.length - newest.length };
}

/**
 * Compute final file lists from file operations: read-only files and modified files, each capped
 * at the {@link MAX_FILE_LIST_ENTRIES} most recently touched paths.
 */
export function computeFileLists(fileOps: FileOperations): FileLists {
	const modified = new Set([...fileOps.edited, ...fileOps.written]);
	const readOnly = [...fileOps.read].filter((f) => !modified.has(f)).sort();
	const read = keepMostRecent(readOnly, [fileOps.read]);
	const written = keepMostRecent([...modified].sort(), [fileOps.edited, fileOps.written]);
	return {
		readFiles: read.kept,
		modifiedFiles: written.kept,
		omittedReadFiles: read.omitted,
		omittedModifiedFiles: written.omitted,
	};
}

function formatFileList(files: string[], omitted: number): string {
	return omitted > 0 ? `${files.join("\n")}\n[${omitted} older files omitted]` : files.join("\n");
}

/**
 * Format file operations as XML tags for summary. A list shortened by {@link computeFileLists} ends
 * with a line saying how many older files were omitted.
 */
export function formatFileOperations(
	readFiles: string[],
	modifiedFiles: string[],
	omittedReadFiles = 0,
	omittedModifiedFiles = 0,
): string {
	const sections: string[] = [];
	if (readFiles.length > 0) {
		sections.push(`<read-files>\n${formatFileList(readFiles, omittedReadFiles)}\n</read-files>`);
	}
	if (modifiedFiles.length > 0) {
		sections.push(`<modified-files>\n${formatFileList(modifiedFiles, omittedModifiedFiles)}\n</modified-files>`);
	}
	if (sections.length === 0) return "";
	return `\n\n${sections.join("\n\n")}`;
}

// ============================================================================
// Message Serialization
// ============================================================================

/** Maximum characters for a tool result in serialized summaries. */
const TOOL_RESULT_MAX_CHARS = 2000;

/**
 * Truncate text to a maximum character length for summarization.
 * Keeps the beginning and appends a truncation marker.
 */
function truncateForSummary(text: string, maxChars: number): string {
	if (text.length <= maxChars) return text;
	const truncatedChars = text.length - maxChars;
	return `${text.slice(0, maxChars)}\n\n[... ${truncatedChars} more characters truncated]`;
}

/**
 * Serialize LLM messages to text for summarization.
 * This prevents the model from treating it as a conversation to continue.
 * Call convertToLlm() first to handle custom message types.
 *
 * Tool results are truncated to keep the summarization request within
 * reasonable token budgets. Full content is not needed for summarization.
 *
 * Thinking is omitted. Providers return summaries of the real reasoning, and
 * replayed thinking is often not part of the model's live context: for example,
 * a block that does not match its request is dropped. Including it can make the
 * summarization request larger than the context it summarizes. Visible text,
 * tool calls, and tool results record what happened.
 */
export function serializeConversation(messages: Message[]): string {
	const parts: string[] = [];

	for (const msg of messages) {
		if (msg.role === "user") {
			const content = contentText(msg.content, "");
			if (content) parts.push(`[User]: ${content}`);
		} else if (msg.role === "assistant") {
			const toolCalls: string[] = [];

			for (const block of msg.content) {
				if (block.type === "toolCall") {
					const args = block.arguments as Record<string, unknown>;
					const argsStr = Object.entries(args)
						.map(([k, v]) => `${k}=${JSON.stringify(v)}`)
						.join(", ");
					toolCalls.push(`${block.name}(${argsStr})`);
				}
			}

			if (msg.content.some((block) => block.type === "text")) {
				parts.push(`[Assistant]: ${contentText(msg.content)}`);
			}
			if (toolCalls.length > 0) {
				parts.push(`[Assistant tool calls]: ${toolCalls.join("; ")}`);
			}
		} else if (msg.role === "toolResult") {
			const content = contentText(msg.content, "");
			if (content) {
				parts.push(`[Tool result]: ${truncateForSummary(content, TOOL_RESULT_MAX_CHARS)}`);
			}
		}
	}

	return parts.join("\n\n");
}

// ============================================================================
// Summarization System Prompt
// ============================================================================

export const SUMMARIZATION_SYSTEM_PROMPT = `You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.`;
