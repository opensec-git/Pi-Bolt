import { stat as fsStat, open } from "node:fs/promises";
import { spawn } from "child_process";
import path from "path";
import type { Static } from "typebox";
import { PIBOLT } from "../../pi-bolt.ts";
import { createInterface } from "../../utils/readline.ts";
import { detachString } from "../../utils/text.ts";
import { ensureTool } from "../../utils/tools-manager.ts";
import type { ExtensionContext, ToolDefinition } from "../extensions/types.ts";
import { type NativeGrepMatch, nativeGrep } from "./native-search.ts";
import { resolveToCwd } from "./path-utils.ts";
import { grepRenderers } from "./renderers/grep.ts";
import { type TypeBoxAgentTool, wrapToolDefinition } from "./tool-definition-wrapper.ts";
import { grepSchema } from "./tool-schemas.generated.ts";
import {
	DEFAULT_MAX_BYTES,
	formatSize,
	GREP_MAX_LINE_LENGTH,
	type TruncationResult,
	truncateHead,
	truncateLine,
	truncationDetails,
} from "./truncate.ts";

export const grepToolSystemPromptContribution = {
	snippet: "Search file contents for patterns (respects .gitignore)",
	guidelines: [],
} as const;

export type GrepToolInput = Static<typeof grepSchema>;
const DEFAULT_LIMIT = 100;
/** Characters of a match line kept for the output, well past GREP_MAX_LINE_LENGTH so cutting there still marks the line truncated. */
const MATCH_TEXT_KEEP_CHARS = GREP_MAX_LINE_LENGTH * 8;
/** Characters of rg's error output kept for an error message. */
const MAX_STDERR_CHARS = 16 * 1024;

export interface GrepToolDetails {
	truncation?: TruncationResult;
	matchLimitReached?: number;
	linesTruncated?: boolean;
}

/**
 * Pluggable operations for the grep tool.
 * The search itself always runs the local ripgrep; to search a remote system, override the tool's execute().
 */
export interface GrepOperations {
	/** Check if path is a directory. Throws if path does not exist. */
	isDirectory: (absolutePath: string) => Promise<boolean> | boolean;
	/**
	 * @deprecated Ignored. Context lines come directly from ripgrep. Override the tool's execute() for remote search.
	 */
	readFile?: (absolutePath: string) => Promise<string> | string;
}

const defaultGrepOperations: GrepOperations = {
	isDirectory: async (p) => (await fsStat(p)).isDirectory(),
};

/** Decode a ripgrep JSON text field (path or line). Non-UTF-8 data arrives base64-encoded in `bytes`. */
function decodeRgText(data: { text?: string; bytes?: string } | undefined): string {
	if (typeof data?.text === "string") return data.text;
	if (typeof data?.bytes === "string") return Buffer.from(data.bytes, "base64").toString("utf-8");
	return "";
}

export interface GrepToolOptions {
	/** Custom operations for grep. Default: local filesystem plus ripgrep */
	operations?: GrepOperations;
	/** Search in-process instead of with ripgrep (native-search.ts). Default: in Pi-Bolt builds, without custom operations. */
	native?: boolean;
}

/** One output line: `path:N: text` for a match, `path-N- text` for a context line. */
function formatGrepLine(
	relativePath: string,
	lineNumber: number,
	text: string,
	isMatch: boolean,
	wasTruncated: boolean,
): string {
	const line = isMatch ? `${relativePath}:${lineNumber}: ${text}` : `${relativePath}-${lineNumber}- ${text}`;
	return wasTruncated ? detachString(line) : line;
}

/**
 * The tool's result for the output lines (from ripgrep, or in-process), cut to size, with notices. `outputLines` may stop at
 * the byte cap; `outputLineCount` and `outputBytes` are the full output's.
 */
function grepResult(
	outputLines: string[],
	options: {
		outputLineCount: number;
		outputBytes: number;
		effectiveLimit: number;
		matchLimitReached: boolean;
		linesTruncated: boolean;
	},
): { content: Array<{ type: "text"; text: string }>; details: GrepToolDetails | undefined } {
	const { effectiveLimit, matchLimitReached, linesTruncated } = options;
	// Apply byte truncation. There is no line limit here because the match limit already capped rows.
	const truncation = truncateHead(outputLines.join("\n"), { maxLines: Number.MAX_SAFE_INTEGER });
	truncation.totalLines = options.outputLineCount;
	truncation.totalBytes = options.outputBytes;
	let output = truncation.content;
	const details: GrepToolDetails = {};
	// Build actionable notices for truncation and match limits.
	const notices: string[] = [];
	if (matchLimitReached) {
		notices.push(
			`${effectiveLimit} matches limit reached. Use limit=${effectiveLimit * 2} for more, or refine pattern`,
		);
		details.matchLimitReached = effectiveLimit;
	}
	if (truncation.truncated) {
		notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
		details.truncation = truncationDetails(truncation);
	}
	if (linesTruncated) {
		notices.push(`Some lines truncated to ${GREP_MAX_LINE_LENGTH} chars. Use read tool to see full lines`);
		details.linesTruncated = true;
	}
	if (notices.length > 0) output += `\n\n[${notices.join(". ")}]`;
	return {
		content: [{ type: "text", text: output }],
		details: Object.keys(details).length > 0 ? details : undefined,
	};
}

/** Bytes read at a time for context lines. */
const CONTEXT_READ_BYTES = 64 * 1024;

/**
 * The lines of `filePath` numbered in `ranges` (sorted, not overlapping, 1-based, inclusive), without their line terminator,
 * each cut to `keepChars`. The file is read a piece at a time up to the last range, so memory stays that of the lines kept
 * (a 95 MB log with a match near its start is not read whole: #11705). Lines are split at `\n` only, as ripgrep does: a
 * lone CR is content. A line past the end of the file is missing from the result; undefined if the file cannot be read.
 */
async function readLineRanges(
	filePath: string,
	ranges: ReadonlyArray<readonly [number, number]>,
	keepChars: number,
	signal: AbortSignal | undefined,
): Promise<Map<number, string> | undefined> {
	let handle: Awaited<ReturnType<typeof open>>;
	try {
		handle = await open(filePath, "r");
	} catch {
		return undefined;
	}
	const lines = new Map<number, string>();
	const lastLine = ranges[ranges.length - 1][1];
	const buffer = Buffer.allocUnsafe(CONTEXT_READ_BYTES);
	// The kept start of the line being read, as bytes (at most keepChars * 4: enough for keepChars characters).
	let pending: Buffer[] = [];
	let pendingBytes = 0;
	// Whether the line being read has any bytes yet (a last line without a line break is still a line).
	let lineHasBytes = false;
	let lineNumber = 1;
	let rangeIndex = 0;
	let position = 0;
	const wanted = () => {
		while (rangeIndex < ranges.length && ranges[rangeIndex][1] < lineNumber) rangeIndex++;
		return rangeIndex < ranges.length && ranges[rangeIndex][0] <= lineNumber;
	};
	const keep = (bytes: Buffer) => {
		const room = keepChars * 4 - pendingBytes;
		if (room <= 0) return;
		const part = bytes.length > room ? bytes.subarray(0, room) : bytes;
		pending.push(Buffer.from(part));
		pendingBytes += part.length;
	};
	const endLine = () => {
		if (wanted()) {
			const text = Buffer.concat(pending, pendingBytes).toString("utf-8");
			lines.set(lineNumber, (text.endsWith("\r") ? text.slice(0, -1) : text).slice(0, keepChars));
		}
		pending = [];
		pendingBytes = 0;
		lineNumber++;
	};
	try {
		let isWanted = wanted();
		while (lineNumber <= lastLine) {
			if (signal?.aborted) throw new Error("Operation aborted");
			const { bytesRead } = await handle.read(buffer, 0, buffer.length, position);
			if (bytesRead === 0) {
				// The last line, when the file does not end with a line break.
				if (lineHasBytes) endLine();
				break;
			}
			position += bytesRead;
			const piece = buffer.subarray(0, bytesRead);
			let start = 0;
			for (let at = piece.indexOf(10); at !== -1; at = piece.indexOf(10, start)) {
				if (isWanted) keep(piece.subarray(start, at));
				endLine();
				lineHasBytes = false;
				isWanted = wanted();
				start = at + 1;
				if (lineNumber > lastLine) break;
			}
			if (lineNumber <= lastLine && start < bytesRead) {
				lineHasBytes = true;
				if (isWanted) keep(piece.subarray(start));
			}
		}
	} catch (error) {
		if (signal?.aborted) throw error;
		return undefined;
	} finally {
		await handle.close().catch(() => {});
	}
	return lines;
}

/**
 * The in-process search's result, in ripgrep's output (the rg path above): `matches` holds up to one match past the limit.
 * With context, each match's surrounding lines are read from its file; windows that overlap or touch are printed once, and
 * after the last allowed match, a match in its trailing context is shown as a context line.
 */
async function formatNativeMatches(
	matches: NativeGrepMatch[],
	options: {
		effectiveLimit: number;
		contextValue: number;
		formatPath: (filePath: string) => string;
		signal: AbortSignal | undefined;
	},
): Promise<{ content: Array<{ type: "text"; text: string }>; details: GrepToolDetails | undefined }> {
	const { effectiveLimit, contextValue, formatPath, signal } = options;
	const matchLimitReached = matches.length > effectiveLimit;
	const shown = matchLimitReached ? matches.slice(0, effectiveLimit) : matches;
	const outputLines: string[] = [];
	let outputBytes = 0;
	let outputLineCount = 0;
	let linesTruncated = false;
	const pushLine = (relativePath: string, lineNumber: number, text: string, isMatch: boolean) => {
		const { text: truncatedText, wasTruncated } = truncateLine(text);
		if (wasTruncated) linesTruncated = true;
		const line = formatGrepLine(relativePath, lineNumber, truncatedText, isMatch, wasTruncated);
		// Byte truncation drops everything past the cap, so keep counting but stop storing.
		if (outputBytes - 1 <= DEFAULT_MAX_BYTES) outputLines.push(line);
		outputBytes += Buffer.byteLength(line, "utf-8") + 1;
		outputLineCount++;
	};
	for (let index = 0; index < shown.length; ) {
		// The matches of one file (nativeGrep returns them file by file, in line order).
		const filePath = shown[index].filePath;
		let end = index;
		while (end < shown.length && shown[end].filePath === filePath) end++;
		const fileMatches = shown.slice(index, end);
		index = end;
		const relativePath = formatPath(filePath);
		if (contextValue === 0) {
			for (const match of fileMatches) pushLine(relativePath, match.lineNumber, match.lineText, true);
			continue;
		}
		const ranges: Array<[number, number]> = [];
		for (const match of fileMatches) {
			const from = Math.max(1, match.lineNumber - contextValue);
			const to = match.lineNumber + contextValue;
			const last = ranges[ranges.length - 1];
			if (last && from <= last[1] + 1) last[1] = Math.max(last[1], to);
			else ranges.push([from, to]);
		}
		const lines = await readLineRanges(filePath, ranges, MATCH_TEXT_KEEP_CHARS, signal);
		const matchLines = new Map(fileMatches.map((match) => [match.lineNumber, match.lineText]));
		for (const [from, to] of ranges) {
			for (let lineNumber = from; lineNumber <= to; lineNumber++) {
				const matchText = matchLines.get(lineNumber);
				const text = lines?.get(lineNumber) ?? matchText;
				if (text === undefined) {
					// Past the end of the file (or it could not be read since): ripgrep has no such line either.
					continue;
				}
				pushLine(relativePath, lineNumber, text, matchText !== undefined);
			}
		}
	}
	return grepResult(outputLines, {
		outputLineCount,
		outputBytes: outputBytes - 1,
		effectiveLimit,
		matchLimitReached,
		linesTruncated,
	});
}

export function createGrepToolDefinition(
	cwd: string,
	options?: GrepToolOptions,
): ToolDefinition<typeof grepSchema, GrepToolDetails | undefined> {
	const customOps = options?.operations;
	const native = !customOps && (options?.native ?? PIBOLT !== undefined);
	return {
		name: "grep",
		label: "grep",
		description: `Search file contents for a pattern. Returns matching lines with file paths and line numbers. Respects .gitignore. Output is truncated to ${DEFAULT_LIMIT} matches or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). Long lines are truncated to ${GREP_MAX_LINE_LENGTH} chars.`,
		promptSnippet: grepToolSystemPromptContribution.snippet,
		parameters: grepSchema,
		async execute(
			_toolCallId,
			{
				pattern,
				path: searchDir,
				glob,
				ignoreCase,
				literal,
				context,
				limit,
			}: {
				pattern: string;
				path?: string;
				glob?: string;
				ignoreCase?: boolean;
				literal?: boolean;
				context?: number;
				limit?: number;
			},
			signal?: AbortSignal,
			_onUpdate?,
			ctx?: ExtensionContext,
		) {
			return new Promise((resolve, reject) => {
				if (signal?.aborted) {
					reject(new Error("Operation aborted"));
					return;
				}
				let settled = false;
				const settle = (fn: () => void) => {
					if (!settled) {
						settled = true;
						fn();
					}
				};

				(async () => {
					try {
						const rgPath = native ? "" : ((await ensureTool("rg")) ?? "");
						if (!native && !rgPath) {
							settle(() => reject(new Error("ripgrep (rg) is not available and could not be downloaded")));
							return;
						}

						const searchPath = resolveToCwd(searchDir || ".", ctx?.cwd || cwd);
						const ops = customOps ?? defaultGrepOperations;
						let isDirectory: boolean;
						try {
							isDirectory = await ops.isDirectory(searchPath);
						} catch {
							settle(() => reject(new Error(`Path not found: ${searchPath}`)));
							return;
						}

						// Clamp so String() never yields exponent notation, which rg rejects.
						const contextValue =
							context && context > 0 ? Math.min(Math.floor(context), Number.MAX_SAFE_INTEGER) : 0;
						const effectiveLimit = Math.max(1, Math.floor(limit ?? DEFAULT_LIMIT));
						// rg never matches a leading "./" ("./src/*.ts" finds nothing), so drop it; "!" negates.
						const effectiveGlob = glob?.replace(/^(!?)(?:\.\/)+/, "$1");
						const formatPath = (filePath: string): string => {
							if (isDirectory) {
								const relative = path.relative(searchPath, path.resolve(searchPath, filePath));
								if (relative && !relative.startsWith("..")) {
									return relative.replace(/\\/g, "/");
								}
							}
							return path.basename(filePath);
						};

						if (native) {
							// One match past the limit tells "exactly N matches" apart from "more than N".
							const found = await nativeGrep({
								root: searchPath,
								isDirectory,
								pattern,
								ignoreCase,
								literal,
								glob: effectiveGlob,
								limit: effectiveLimit + 1,
								keepChars: MATCH_TEXT_KEEP_CHARS,
								signal,
							});
							if (found.matches.length === 0) {
								settle(() =>
									resolve({ content: [{ type: "text", text: "No matches found" }], details: undefined }),
								);
								return;
							}
							const result = await formatNativeMatches(found.matches, {
								effectiveLimit,
								contextValue,
								formatPath,
								signal,
							});
							settle(() => resolve(result));
							return;
						}

						// --no-config: the tool contract (literal, glob, limit, ...) must not depend on
						// ambient user config like RIPGREP_CONFIG_PATH. Users can customize via bash or extensions.
						const args: string[] = ["--no-config", "--json", "--line-number", "--color=never", "--hidden"];
						if (ignoreCase) args.push("--ignore-case");
						if (literal) args.push("--fixed-strings");
						if (effectiveGlob) args.push("--glob", effectiveGlob);
						// rg emits the surrounding lines as `context` events, so no matched file is ever read here (#9276).
						if (contextValue > 0) args.push("--context", String(contextValue));
						// rg roots --glob at its own cwd, so a directory search runs inside searchPath (#11807).
						// It searches "." because rg canonicalizes its cwd: with an absolute path, a symlinked
						// searchPath (macOS /tmp) would no longer lie under the glob root.
						args.push("--", pattern, isDirectory ? "." : searchPath);

						const child = spawn(rgPath, args, {
							cwd: isDirectory ? searchPath : undefined,
							stdio: ["ignore", "pipe", "pipe"],
						});
						const rl = createInterface({ input: child.stdout });
						let stderr = "";
						let matchCount = 0;
						let matchLimitReached = false;
						let linesTruncated = false;
						let aborted = false;
						let killedDueToLimit = false;
						const outputLines: string[] = [];

						const cleanup = () => {
							rl.close();
							signal?.removeEventListener("abort", onAbort);
						};
						const stopChild = (dueToLimit = false) => {
							if (!child.killed) {
								killedDueToLimit = dueToLimit;
								child.kill();
							}
						};
						const onAbort = () => {
							aborted = true;
							stopChild();
						};
						signal?.addEventListener("abort", onAbort, { once: true });
						child.stderr?.on("data", (chunk) => {
							// rg prints an error per unreadable file and keeps going; the message only needs the first ones.
							if (stderr.length < MAX_STDERR_CHARS) stderr += chunk.toString();
						});

						// Size of the full output, including lines past the byte cap that are counted but not stored.
						let outputBytes = 0;
						let outputLineCount = 0;
						let lastFile: string | undefined;
						// Set at the last allowed match. rg still sends that match's trailing context, kept up to
						// trailingContextEnd, and the limit notice waits for proof of one more match.
						let limitHit = false;
						let trailingContextEnd = 0;
						let done = false;
						const finish = () => {
							done = true;
							stopChild(true);
						};
						const pushLine = (filePath: string, lineNumber: number, lineText: string, isMatch: boolean) => {
							lastFile = filePath;
							const relativePath = formatPath(filePath);
							// Strip only the line terminator: a lone CR inside a line is content, as in the read tool.
							const sanitized = lineText.replace(/\r?\n?$/, "");
							// Truncate long lines so grep output stays compact.
							const { text: truncatedText, wasTruncated } = truncateLine(sanitized);
							if (wasTruncated) linesTruncated = true;
							// A cut line is copied: a slice can keep a match line of megabytes (a minified file) in memory.
							const line = formatGrepLine(relativePath, lineNumber, truncatedText, isMatch, wasTruncated);
							// Byte truncation below drops everything past the cap, so keep counting but stop storing (#9276).
							// outputBytes counts a newline after every line; the joined output has one fewer.
							if (outputBytes - 1 <= DEFAULT_MAX_BYTES) outputLines.push(line);
							outputBytes += Buffer.byteLength(line, "utf-8") + 1;
							outputLineCount++;
						};

						rl.on("line", (line) => {
							if (done || !line.trim()) return;
							let event: any;
							try {
								event = JSON.parse(line);
							} catch {
								return;
							}
							const isMatch = event.type === "match";
							if (!isMatch && event.type !== "context") return;
							const filePath = decodeRgText(event.data?.path);
							const lineNumber = event.data?.line_number;
							if (!filePath || typeof lineNumber !== "number") return;
							const lineText = decodeRgText(event.data?.lines);
							if (!limitHit) {
								pushLine(filePath, lineNumber, lineText, isMatch);
								if (!isMatch) return;
								matchCount++;
								if (matchCount >= effectiveLimit) {
									limitHit = true;
									trailingContextEnd = lineNumber + contextValue;
								}
								return;
							}
							const inWindow = filePath === lastFile && lineNumber <= trailingContextEnd;
							// A match inside the last match's trailing context is shown as context.
							if (inWindow) pushLine(filePath, lineNumber, lineText, false);
							// Only another match proves that the limit cut results off.
							if (isMatch) matchLimitReached = true;
							if (matchLimitReached && !(inWindow && lineNumber < trailingContextEnd)) finish();
						});

						child.on("error", (error) => {
							cleanup();
							settle(() => reject(new Error(`Failed to run ripgrep: ${error.message}`)));
						});
						child.on("close", (code) => {
							cleanup();
							if (aborted) {
								settle(() => reject(new Error("Operation aborted")));
								return;
							}
							if (!killedDueToLimit && code !== 0 && code !== 1) {
								const errorMsg = stderr.trim() || `ripgrep exited with code ${code}`;
								settle(() => reject(new Error(errorMsg)));
								return;
							}
							if (matchCount === 0) {
								settle(() =>
									resolve({ content: [{ type: "text", text: "No matches found" }], details: undefined }),
								);
								return;
							}

							settle(() =>
								resolve(
									grepResult(outputLines, {
										outputLineCount,
										outputBytes: outputBytes - 1,
										effectiveLimit,
										matchLimitReached,
										linesTruncated,
									}),
								),
							);
						});
					} catch (err) {
						settle(() => reject(err as Error));
					}
				})();
			});
		},
		...grepRenderers,
	};
}

export function createGrepTool(cwd: string, options?: GrepToolOptions): TypeBoxAgentTool<typeof grepSchema> {
	return wrapToolDefinition(createGrepToolDefinition(cwd, options));
}
