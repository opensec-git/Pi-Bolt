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
import { TextDecoder } from "util";
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
	/**
	 * Stream file contents in chunks. When present, text files are read through it and only the lines
	 * that will be shown are kept in memory; otherwise readFile loads the whole file. Stop reading when
	 * the signal aborts; the read tool also stops consuming the stream then.
	 */
	createReadStream?: (absolutePath: string, options: { signal?: AbortSignal }) => AsyncIterable<Uint8Array>;
}

const defaultReadOperations: ReadOperations = {
	readFile: (path) => fsReadFile(path),
	createReadStream: readLocalFileChunks,
	access: (path) => fsAccess(path, constants.R_OK),
	detectImageMimeType: detectSupportedImageMimeTypeFromFile,
};

/**
 * Stream a local file the way fs.readFile reads it: a regular file up to its size when opened, so text
 * appended during the read is not counted and a growing file still ends; a file reporting size 0 (such
 * as /proc files) and other file types until EOF.
 */
async function* readLocalFileChunks(path: string, { signal }: { signal?: AbortSignal }): AsyncIterable<Uint8Array> {
	const handle = await fsOpen(path, "r");
	try {
		const stats = await handle.stat();
		const end = stats.isFile() && stats.size > 0 ? stats.size - 1 : undefined;
		yield* handle.createReadStream({ end, signal, autoClose: false });
	} finally {
		await handle.close();
	}
}

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

/** The lines a read shows, with the file's line count; line counts follow `text.split("\n")`. */
interface SelectedLines {
	totalFileLines: number;
	truncation: TruncationResult;
	/** Bytes of the first selected line, for the notice when it alone exceeds the byte limit. */
	firstLineBytes: number;
}

/**
 * Whether a range can be streamed. Other values (fractional, negative limit) keep the whole-file path,
 * whose array slicing defines what they select, for example limit -1 as all lines but the last.
 */
function isStreamableRange(offset: number | undefined, limit: number | undefined): boolean {
	return (
		(offset === undefined || Number.isInteger(offset)) &&
		(limit === undefined || (Number.isInteger(limit) && limit >= 0))
	);
}

/** Select lines from a whole file in memory: for operations without createReadStream and unusual ranges. */
function selectLinesFromBuffer(buffer: Buffer, startLine: number, limit: number | undefined): SelectedLines {
	const allLines = buffer.toString("utf-8").split("\n");
	const endLine = limit === undefined ? undefined : Math.min(startLine + limit, allLines.length);
	const truncation = truncateHead(allLines.slice(startLine, endLine).join("\n"));
	const firstLineBytes = truncation.firstLineExceedsLimit ? Buffer.byteLength(allLines[startLine], "utf-8") : 0;
	return { totalFileLines: allLines.length, truncation, firstLineBytes };
}

/**
 * Select lines from a file read in chunks. Only lines truncateHead can show are stored; the rest are
 * counted, so memory does not grow with the file. Stops reading when the signal aborts.
 */
async function selectLinesFromStream(
	chunks: AsyncIterable<Uint8Array>,
	startLine: number,
	limit: number | undefined,
	signal: AbortSignal | undefined,
): Promise<SelectedLines> {
	const endLine = limit === undefined ? Number.POSITIVE_INFINITY : startLine + limit;
	// Non-fatal UTF-8 that keeps a byte order mark decodes like Buffer.toString("utf-8").
	const decoder = new TextDecoder("utf-8", { ignoreBOM: true });
	const storedLines: string[] = [];
	let storedBytes = 0;
	let storedPastCaps = false;
	let line = 0;
	let selectedLines = 0;
	let selectedBytes = 0;
	let firstLineBytes = 0;
	let lastLineEmpty = true;
	// The selected line being read: its text, while it is stored, and its size.
	let pieces: string[] | undefined = [];
	let lineBytes = 0;
	let lineHasText = false;

	const appendToLine = (text: string, start: number, end: number) => {
		lineHasText = true;
		if (!pieces) return;
		const piece = text.slice(start, end);
		// A line over the byte cap is never shown, and a first line over it is only measured: never store
		// all of a 1 GB line.
		if (lineBytes <= DEFAULT_MAX_BYTES) pieces.push(piece);
		lineBytes += Buffer.byteLength(piece, "utf-8");
	};
	const finishLine = () => {
		if (pieces) {
			if (selectedLines === 0) firstLineBytes = lineBytes;
			// Storing continues one line past the caps: truncateHead ignores a trailing empty line, so the
			// line after the cap must be there for it to decide truncation as for the full text.
			if (storedLines.length > DEFAULT_MAX_LINES || storedBytes > DEFAULT_MAX_BYTES) storedPastCaps = true;
			const text = pieces.join("");
			storedBytes += Buffer.byteLength(text, "utf-8") + (storedLines.length > 0 ? 1 : 0);
			storedLines.push(text);
		}
		selectedLines++;
		lastLineEmpty = !lineHasText;
		pieces = storedPastCaps ? undefined : [];
		lineBytes = 0;
		lineHasText = false;
	};
	const select = (text: string) => {
		let position = 0;
		// Start of the selected text in this piece, measured as a whole: the joined lines' size.
		let selectionStart = -1;
		while (true) {
			const selected = line >= startLine && line < endLine;
			if (selected && selectionStart === -1) selectionStart = position;
			const newline = text.indexOf("\n", position);
			const end = newline === -1 ? text.length : newline;
			if (selected && end > position) appendToLine(text, position, end);
			if (newline === -1) break;
			if (selected) {
				finishLine();
				if (line + 1 === endLine) {
					selectedBytes += Buffer.byteLength(text.slice(selectionStart, newline), "utf-8");
					selectionStart = -1;
				}
			}
			line++;
			position = newline + 1;
		}
		if (selectionStart !== -1) selectedBytes += Buffer.byteLength(text.slice(selectionStart), "utf-8");
	};

	for await (const chunk of chunks) {
		// Leaving the loop closes the stream.
		if (signal?.aborted) throw new Error("Operation aborted");
		select(decoder.decode(chunk, { stream: true }));
	}
	select(decoder.decode());
	if (line >= startLine && line < endLine) finishLine();
	const truncation: TruncationResult = {
		...truncateHead(storedLines.join("\n")),
		// truncateHead's counts for the joined selected lines, which drop a trailing empty line.
		totalLines: selectedBytes === 0 ? 0 : selectedLines - (lastLineEmpty ? 1 : 0),
		totalBytes: selectedBytes,
	};
	return { totalFileLines: line + 1, truncation, firstLineBytes };
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
								// Read text content. Convert offset from 1-indexed input to 0-indexed line access.
								const startLine = offset ? Math.max(0, offset - 1) : 0;
								const startLineDisplay = startLine + 1;
								let totalFileLines: number;
								let truncation: TruncationResult;
								let firstLineBytes: () => number;
								if (ops === defaultReadOperations && isStreamableRange(offset, limit)) {
									// Pi-Bolt: lines are found in the bytes, and only the selected ones are decoded; a large
									// file is read in chunks (readSelectionInChunks), a smaller one whole without splitting it.
									const requestedEndLine = limit !== undefined ? startLine + limit : Number.POSITIVE_INFINITY;
									const size = (await fsStat(absolutePath)).size;
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
										truncation =
											startLine >= totalFileLines ? truncateHead("") : truncateSelection(selection);
										const selectedFirstLineBytes = selection.firstLineBytes;
										firstLineBytes = () => selectedFirstLineBytes;
									} else {
										const textContent = (await ops.readFile(absolutePath)).toString("utf-8");
										// Find the selected lines by scanning for newlines instead of splitting the file: splitting
										// allocates a string per line of the whole file to return at most a few thousand of them.
										const located = locateLines(textContent, startLine, requestedEndLine);
										totalFileLines = located.totalLines;
										const selectedContent =
											requestedEndLine > startLine && startLine < totalFileLines
												? textContent.slice(located.start, located.end)
												: "";
										truncation = truncateHead(selectedContent);
										firstLineBytes = () => {
											const firstNewline = selectedContent.indexOf("\n");
											const firstLine =
												firstNewline === -1 ? selectedContent : selectedContent.slice(0, firstNewline);
											return Buffer.byteLength(firstLine, "utf-8");
										};
									}
								} else {
									const selected =
										ops.createReadStream && isStreamableRange(offset, limit)
											? await selectLinesFromStream(
													ops.createReadStream(absolutePath, { signal }),
													startLine,
													limit,
													signal,
												)
											: selectLinesFromBuffer(await ops.readFile(absolutePath), startLine, limit);
									totalFileLines = selected.totalFileLines;
									truncation = selected.truncation;
									firstLineBytes = () => selected.firstLineBytes;
								}
								if (aborted) return;
								// Check if offset is out of bounds.
								if (startLine >= totalFileLines) {
									throw new Error(`Offset ${offset} is beyond end of file (${totalFileLines} lines total)`);
								}
								// A user-specified limit is honored before truncation.
								const userLimitedLines =
									limit === undefined ? undefined : Math.min(startLine + limit, totalFileLines) - startLine;
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
