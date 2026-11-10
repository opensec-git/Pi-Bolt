import type { Api, ImageContent, Model, ModelImageResizeOptions, TextContent } from "@earendil-works/pi-ai";
import { isUtf8 } from "buffer";
import { constants } from "fs";
import {
	type FileHandle,
	access as fsAccess,
	open as fsOpen,
	readFile as fsReadFile,
	stat as fsStat,
} from "fs/promises";
import type { Static } from "typebox";
import { markProcessedImageBlock, processImage } from "../../utils/image-process.ts";
import { detectSupportedImageMimeTypeFromFile } from "../../utils/mime.ts";
import { detachSharedString } from "../../utils/text.ts";
import type { ExtensionContext, ToolDefinition } from "../extensions/types.ts";
import { resolveReadPathAsync } from "./path-utils.ts";
import { readRenderers } from "./renderers/read.ts";
import { type TypeBoxAgentTool, wrapToolDefinition } from "./tool-definition-wrapper.ts";
import { readOutputSchema, readSchema } from "./tool-schemas.generated.ts";
import {
	DEFAULT_MAX_BYTES,
	DEFAULT_MAX_LINES,
	formatSize,
	type TruncationResult,
	truncateHead,
	truncationDetails,
} from "./truncate.ts";

export const readToolSystemPromptContribution = {
	snippet: "Read file contents",
	guidelines: ["Use read to examine files instead of cat or sed."],
} as const;

export type ReadToolInput = Static<typeof readSchema>;

/** Result for programmatic callers such as codemode scripts (see readOutputSchema in tool-schemas.ts). */
export type ReadToolOutput = Static<typeof readOutputSchema>;

export interface ReadToolDetails {
	truncation?: TruncationResult;
}

/**
 * Pluggable operations for the read tool.
 * Override these to delegate file reading to remote systems (for example SSH).
 */
export interface ReadOperations {
	/** Read file contents as a Buffer */
	readFile: (absolutePath: string) => Promise<Buffer>;
	/** Check if file is readable (throw if not) */
	access: (absolutePath: string) => Promise<void>;
	/** Detect image MIME type, return null or undefined for non-images */
	detectImageMimeType?: (absolutePath: string) => Promise<string | null | undefined>;
}

const defaultReadOperations: ReadOperations = {
	readFile: (path) => fsReadFile(path),
	access: (path) => fsAccess(path, constants.R_OK),
	detectImageMimeType: detectSupportedImageMimeTypeFromFile,
};

export interface ReadToolOptions {
	/** Whether to auto-resize images. Default: true */
	autoResizeImages?: boolean;
	/** Fallback resize profile when the execution context has no model metadata. */
	resizeOptions?: ModelImageResizeOptions;
	/** Custom operations for file reading. Default: local filesystem */
	operations?: ReadOperations;
}

/** The image block and its note, or the text for text files and images that could not be processed. */
function toReadOutput(content: (TextContent | ImageContent)[]): ReadToolOutput {
	const text = content.find((block) => block.type === "text")?.text ?? "";
	const image = content.find((block) => block.type === "image");
	return image ? { type: "image", data: image.data, mimeType: image.mimeType, note: text } : text;
}

/**
 * Lines of `text` split at "\n": how many there are, where line `startLine` starts, and where line `endLine - 1`
 * ends (the end of the text when `endLine` is past the last line). `start` is the end of the text when `startLine`
 * is past the last line.
 */
function locateLines(
	text: string,
	startLine: number,
	endLine: number,
): { totalLines: number; start: number; end: number } {
	let start = startLine === 0 ? 0 : text.length;
	let end = text.length;
	let newlines = 0;
	for (let i = text.indexOf("\n"); i !== -1; i = text.indexOf("\n", i + 1)) {
		// Line `newlines` ends at i, and line `newlines + 1` starts after it.
		if (newlines === endLine - 1) end = i;
		newlines++;
		if (newlines === startLine) start = i + 1;
	}
	return { totalLines: newlines + 1, start, end };
}

/** Text files at least this large are read in chunks instead of whole. */
const CHUNKED_READ_MIN_BYTES = 1024 * 1024;
const CHUNKED_READ_CHUNK_BYTES = 1024 * 1024;

/**
 * Where the bytes of `data` can be cut so that decoding each part on its own gives the text decoding all of it gives:
 * before a byte that starts a sequence, among the last three (a sequence is at most four bytes, and an invalid one
 * ends at the first byte that cannot continue it), or else at the end.
 */
function utf8SafeEnd(data: Buffer): number {
	for (let i = data.length - 1; i >= Math.max(0, data.length - 3); i--) {
		const byte = data[i]!;
		if (byte < 0x80) return data.length;
		if (byte >= 0xc0) return i;
	}
	return data.length;
}

/** The UTF-8 length of the text that bytes fed in order decode to (invalid sequences decode to U+FFFD, 3 bytes). */
class DecodedLength {
	private carry: Buffer | undefined;
	private total = 0;

	feed(bytes: Buffer): void {
		const data = this.carry ? Buffer.concat([this.carry, bytes]) : bytes;
		this.carry = undefined;
		const end = utf8SafeEnd(data);
		if (end < data.length) this.carry = Buffer.from(data.subarray(end));
		this.add(data.subarray(0, end));
	}

	end(): number {
		if (this.carry) this.add(this.carry);
		this.carry = undefined;
		return this.total;
	}

	private add(part: Buffer): void {
		this.total += isUtf8(part) ? part.length : Buffer.byteLength(part.toString("utf-8"), "utf-8");
	}
}

/**
 * What the read tool needs of a large file, read in chunks: the selected lines as `locateLines` and slicing the decoded
 * file select them, without decoding the file (a 128 MB log took 50-100 ms and 250-400 MB of memory per read).
 * A "\n" byte is "\n" in the decoded text and never part of a multi-byte sequence, so lines are found in the bytes.
 */
interface ChunkedSelection {
	totalLines: number;
	/** The selected text when it is at most `prefixBytes` long, else its first `prefixBytes` bytes, decoded. */
	prefix: string;
	/** Whether `prefix` is all of the selected text. */
	complete: boolean;
	/** Lines of the selected text, counted as truncateHead counts them. */
	selectedLines: number;
	/** UTF-8 length of the selected text. */
	selectedBytes: number;
	/** UTF-8 length of the selected text's first line. */
	firstLineBytes: number;
}

async function readSelectionInChunks(
	file: FileHandle,
	startLine: number,
	endLine: number,
	prefixBytes: number,
	isAborted: () => boolean,
): Promise<ChunkedSelection | undefined> {
	const chunk = Buffer.allocUnsafe(CHUNKED_READ_CHUNK_BYTES);
	const prefix = Buffer.allocUnsafe(prefixBytes);
	let prefixLength = 0;
	const selectedLength = new DecodedLength();
	const firstLineLength = new DecodedLength();
	let firstLineEnded = false;
	// No text is selected for a zero limit.
	const selecting = endLine > startLine;
	// Byte offsets of the selection in the file, once known; start is where line `startLine` starts.
	let start = startLine === 0 ? 0 : -1;
	let end = -1;
	let selectedRawBytes = 0;
	let selectedNewlines = 0;
	let lastSelectedByte = -1;
	let newlines = 0;
	let offset = 0;
	while (true) {
		if (isAborted()) return undefined;
		const { bytesRead } = await file.read(chunk, 0, chunk.length, null);
		if (bytesRead === 0) break;
		const bytes = chunk.subarray(0, bytesRead);
		const endBefore = end;
		for (let i = bytes.indexOf(0x0a); i !== -1; i = bytes.indexOf(0x0a, i + 1)) {
			// Line `newlines` ends at i, and line `newlines + 1` starts after it.
			if (newlines === endLine - 1) end = offset + i;
			else if (selecting && start !== -1 && end === -1) selectedNewlines++;
			newlines++;
			if (newlines === startLine) start = offset + i + 1;
		}
		if (selecting && start !== -1 && endBefore === -1) {
			// The part of this chunk in the selection.
			const from = Math.max(start, offset) - offset;
			const to = (end === -1 || end > offset + bytesRead ? offset + bytesRead : end) - offset;
			if (from < to) {
				const part = bytes.subarray(from, to);
				selectedLength.feed(part);
				selectedRawBytes += part.length;
				lastSelectedByte = part[part.length - 1]!;
				if (prefixLength < prefixBytes) {
					prefixLength += part.copy(prefix, prefixLength, 0, Math.min(part.length, prefixBytes - prefixLength));
				}
				if (!firstLineEnded) {
					const lineEnd = part.indexOf(0x0a);
					firstLineLength.feed(lineEnd === -1 ? part : part.subarray(0, lineEnd));
					firstLineEnded = lineEnd !== -1;
				}
			}
		}
		offset += bytesRead;
	}
	const complete = selectedRawBytes <= prefixBytes;
	return {
		totalLines: newlines + 1,
		prefix: prefix.toString("utf-8", 0, prefixLength),
		complete,
		selectedLines: selectedRawBytes === 0 ? 0 : lastSelectedByte === 0x0a ? selectedNewlines : selectedNewlines + 1,
		selectedBytes: selectedLength.end(),
		firstLineBytes: firstLineLength.end(),
	};
}

/**
 * truncateHead of the selected text, from a selection read in chunks. When `prefix` is only the start of the selection,
 * it is more than DEFAULT_MAX_BYTES bytes, and decoded text is never shorter in UTF-8 than its bytes (an invalid
 * sequence of up to 3 bytes becomes U+FFFD, 3 bytes): so the prefix holds every line truncateHead keeps, and the line
 * that does not fit, or enough of it not to fit. truncateHead then keeps what it keeps from the whole selection, and
 * only its totals are those of the prefix.
 */
function truncateSelection(selection: ChunkedSelection): TruncationResult {
	const truncation = truncateHead(selection.prefix);
	if (selection.complete) return truncation;
	return { ...truncation, totalLines: selection.selectedLines, totalBytes: selection.selectedBytes };
}

function getNonVisionImageNote(model: Model<Api> | undefined): string | undefined {
	if (!model || model.input.includes("image")) {
		return undefined;
	}
	return "[Current model does not support images. The image will be omitted from this request.]";
}

export function createReadToolDefinition(
	cwd: string,
	options?: ReadToolOptions,
): ToolDefinition<typeof readSchema, ReadToolDetails | undefined> {
	const autoResizeImages = options?.autoResizeImages ?? true;
	const fallbackResizeOptions = options?.resizeOptions;
	const ops = options?.operations ?? defaultReadOperations;
	return {
		name: "read",
		label: "read",
		description: `Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to ${DEFAULT_MAX_LINES} lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete.`,
		promptSnippet: readToolSystemPromptContribution.snippet,
		promptGuidelines: [...readToolSystemPromptContribution.guidelines],
		parameters: readSchema,
		outputSchema: readOutputSchema,
		constrainedSampling: { type: "json_schema", strict: "prefer" },
		async execute(
			_toolCallId,
			{ path, offset, limit }: { path: string; offset?: number; limit?: number },
			signal?: AbortSignal,
			_onUpdate?,
			ctx?: ExtensionContext,
		) {
			return new Promise<{ content: (TextContent | ImageContent)[]; details: ReadToolDetails | undefined }>(
				(resolve, reject) => {
					if (signal?.aborted) {
						reject(new Error("Operation aborted"));
						return;
					}
					let aborted = false;
					const onAbort = () => {
						aborted = true;
						reject(new Error("Operation aborted"));
					};
					signal?.addEventListener("abort", onAbort, { once: true });

					(async () => {
						try {
							const absolutePath = await resolveReadPathAsync(path, ctx?.cwd || cwd);
							if (aborted) return;
							// Check if file exists and is readable.
							await ops.access(absolutePath);
							if (aborted) return;
							const mimeType = ops.detectImageMimeType ? await ops.detectImageMimeType(absolutePath) : undefined;
							let content: (TextContent | ImageContent)[];
							let details: ReadToolDetails | undefined;
							const nonVisionImageNote = getNonVisionImageNote(ctx?.model);
							if (mimeType) {
								// Read image as binary.
								const buffer = await ops.readFile(absolutePath);
								const processOptions = {
									autoResizeImages,
									resizeOptions: ctx?.model?.inputLimits?.images?.resize ?? fallbackResizeOptions,
								};
								const processed = await processImage(buffer, mimeType, processOptions);
								if (!processed.ok) {
									let textNote = `Read image file [${mimeType}]\n${processed.message}`;
									if (nonVisionImageNote) textNote += `\n${nonVisionImageNote}`;
									content = [{ type: "text", text: textNote }];
								} else {
									let textNote = `Read image file [${processed.mimeType}]`;
									if (processed.hints.length > 0) textNote += `\n${processed.hints.join("\n")}`;
									if (nonVisionImageNote) textNote += `\n${nonVisionImageNote}`;
									const image: ImageContent = {
										type: "image",
										data: processed.data,
										mimeType: processed.mimeType,
									};
									markProcessedImageBlock(image, processOptions);
									content = [{ type: "text", text: textNote }, image];
								}
							} else {
								// Read text content.
								// Apply offset if specified. Convert from 1-indexed input to 0-indexed line numbers.
								const startLine = offset ? Math.max(0, Math.floor(offset) - 1) : 0;
								const startLineDisplay = startLine + 1;
								const requestedEndLine =
									limit !== undefined ? startLine + Math.max(0, Math.floor(limit)) : Number.POSITIVE_INFINITY;
								let totalFileLines: number;
								let truncation: TruncationResult;
								let firstLineBytes: () => number;
								const size = ops === defaultReadOperations ? (await fsStat(absolutePath)).size : 0;
								if (size >= CHUNKED_READ_MIN_BYTES) {
									const file = await fsOpen(absolutePath, "r");
									let selection: ChunkedSelection | undefined;
									try {
										// A few bytes more than truncateHead keeps: see truncateSelection().
										selection = await readSelectionInChunks(
											file,
											startLine,
											requestedEndLine,
											DEFAULT_MAX_BYTES + 4,
											() => aborted,
										);
									} finally {
										await file.close();
									}
									if (!selection) return;
									totalFileLines = selection.totalLines;
									if (startLine >= totalFileLines) {
										throw new Error(`Offset ${offset} is beyond end of file (${totalFileLines} lines total)`);
									}
									truncation = truncateSelection(selection);
									const selectedFirstLineBytes = selection.firstLineBytes;
									firstLineBytes = () => selectedFirstLineBytes;
								} else {
									const buffer = await ops.readFile(absolutePath);
									const textContent = buffer.toString("utf-8");
									// Find the selected lines by scanning for newlines instead of splitting the file: splitting
									// allocates a string per line of the whole file to return at most a few thousand of them.
									const located = locateLines(textContent, startLine, requestedEndLine);
									totalFileLines = located.totalLines;
									// Check if offset is out of bounds.
									if (startLine >= totalFileLines) {
										throw new Error(`Offset ${offset} is beyond end of file (${totalFileLines} lines total)`);
									}
									// If limit is specified by the user, honor it first. Otherwise truncateHead decides.
									const selectedContent =
										requestedEndLine > startLine ? textContent.slice(located.start, located.end) : "";
									// Apply truncation, respecting both line and byte limits.
									truncation = truncateHead(selectedContent);
									firstLineBytes = () => {
										const firstNewline = selectedContent.indexOf("\n");
										const firstLine =
											firstNewline === -1 ? selectedContent : selectedContent.slice(0, firstNewline);
										return Buffer.byteLength(firstLine, "utf-8");
									};
								}
								const userLimitedLines =
									limit !== undefined ? Math.min(requestedEndLine, totalFileLines) - startLine : undefined;
								let outputText: string;
								if (truncation.firstLineExceedsLimit) {
									// First line alone exceeds the byte limit. Point the model at a bash fallback.
									const firstLineSize = formatSize(firstLineBytes());
									outputText = `[Line ${startLineDisplay} is ${firstLineSize}, exceeds ${formatSize(DEFAULT_MAX_BYTES)} limit. Use bash: sed -n '${startLineDisplay}p' ${path} | head -c ${DEFAULT_MAX_BYTES}]`;
									details = { truncation: truncationDetails(truncation) };
								} else if (truncation.truncated) {
									// Truncation occurred. Build an actionable continuation notice.
									const endLineDisplay = startLineDisplay + truncation.outputLines - 1;
									const nextOffset = endLineDisplay + 1;
									outputText = truncation.content;
									if (truncation.truncatedBy === "lines") {
										outputText += `\n\n[Showing lines ${startLineDisplay}-${endLineDisplay} of ${totalFileLines}. Use offset=${nextOffset} to continue.]`;
									} else {
										outputText += `\n\n[Showing lines ${startLineDisplay}-${endLineDisplay} of ${totalFileLines} (${formatSize(DEFAULT_MAX_BYTES)} limit). Use offset=${nextOffset} to continue.]`;
									}
									details = { truncation: truncationDetails(truncation) };
								} else if (userLimitedLines !== undefined && startLine + userLimitedLines < totalFileLines) {
									// User-specified limit stopped early, but the file still has more content.
									const remaining = totalFileLines - (startLine + userLimitedLines);
									const nextOffset = startLine + userLimitedLines + 1;
									outputText = `${truncation.content}\n\n[${remaining} more lines in file. Use offset=${nextOffset} to continue.]`;
								} else {
									// No truncation and no remaining user-limited content.
									outputText = truncation.content;
								}
								// The output can be a substring of the whole file's text, which it would keep in memory for as
								// long as the session holds the result. A file read again unchanged gives the same string.
								content = [{ type: "text", text: detachSharedString(outputText) }];
							}

							if (aborted) return;
							signal?.removeEventListener("abort", onAbort);
							resolve({ content, details });
						} catch (error: any) {
							signal?.removeEventListener("abort", onAbort);
							if (!aborted) reject(error);
						}
					})();
				},
			).then((result) => ({ ...result, structuredContent: toReadOutput(result.content) }));
		},
		...readRenderers,
	};
}

export function createReadTool(cwd: string, options?: ReadToolOptions): TypeBoxAgentTool<typeof readSchema> {
	return wrapToolDefinition(createReadToolDefinition(cwd, options));
}
