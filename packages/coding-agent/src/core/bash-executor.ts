/**
 * Bash command execution with streaming support and cancellation.
 *
 * This module provides a unified bash execution implementation used by:
 * - AgentSession.executeBash() for interactive and RPC modes
 * - Direct calls from modes that need bash execution
 */

import { splitIncompleteAnsiSuffix, stripAnsi } from "../utils/ansi.ts";
import { createOutputFile, type OutputFile } from "../utils/output-files.ts";
import { sanitizeBinaryOutput } from "../utils/shell.ts";
import type { BashOperations } from "./tools/bash.ts";
import { DEFAULT_MAX_BYTES, truncateTail } from "./tools/truncate.ts";

// ============================================================================
// Types
// ============================================================================

export interface BashExecutorOptions {
	/** Callback for streaming output chunks (already sanitized) */
	onChunk?: (chunk: string) => void;
	/** AbortSignal for cancellation */
	signal?: AbortSignal;
}

export interface BashResult {
	/** Combined stdout + stderr output (sanitized, possibly truncated) */
	output: string;
	/** Process exit code (undefined if killed/cancelled) */
	exitCode: number | undefined;
	/** Whether the command was cancelled via signal */
	cancelled: boolean;
	/** Whether the output was truncated */
	truncated: boolean;
	/** Path to temp file containing full output (if output exceeded truncation threshold) */
	fullOutputPath?: string;
}

// ============================================================================
// Implementation
// ============================================================================

/**
 * Execute a bash command using custom BashOperations.
 * Used for remote execution (SSH, containers, etc.).
 */
export async function executeBashWithOperations(
	command: string,
	cwd: string,
	operations: BashOperations,
	options?: BashExecutorOptions,
): Promise<BashResult> {
	// Rolling buffer of the last chunks: outputChunks[outputStart..] are kept, the ones before were dropped.
	let outputChunks: string[] = [];
	let outputStart = 0;
	let outputBytes = 0;
	const maxOutputBytes = DEFAULT_MAX_BYTES * 2;

	let tempFile: OutputFile | undefined;
	let totalBytes = 0;

	const ensureTempFile = () => {
		if (tempFile) {
			return;
		}
		tempFile = createOutputFile("pi-bash", ".log");
		for (let i = outputStart; i < outputChunks.length; i++) {
			tempFile.write(outputChunks[i]);
		}
	};

	const closeTempFile = () => {
		try {
			tempFile?.close();
		} catch {
			// The output the file holds up to the failed write stays; the result still carries the output tail.
		}
	};

	const decoder = new TextDecoder();
	// Unfinished escape sequence at the end of the previous chunk, completed by the next chunk.
	let pendingAnsi = "";

	const appendText = (rawText: string) => {
		// Sanitize: strip ANSI, replace binary garbage, normalize newlines
		const text = sanitizeBinaryOutput(stripAnsi(rawText)).replace(/\r/g, "");
		if (!text) {
			return;
		}

		// Start writing to temp file if exceeds threshold
		if (totalBytes > DEFAULT_MAX_BYTES) {
			ensureTempFile();
		}

		tempFile?.write(text);

		// Keep rolling buffer. Dropped chunks are skipped by index and compacted away now and then: shifting them off one
		// at a time moves the whole array each time, and a command printing many small chunks keeps many of them.
		outputChunks.push(text);
		outputBytes += text.length;
		while (outputBytes > maxOutputBytes && outputChunks.length - outputStart > 1) {
			outputBytes -= outputChunks[outputStart].length;
			outputStart++;
		}
		if (outputStart > 1024 && outputStart * 2 > outputChunks.length) {
			outputChunks = outputChunks.slice(outputStart);
			outputStart = 0;
		}

		// Stream to callback
		if (options?.onChunk) {
			options.onChunk(text);
		}
	};

	const onData = (data: Buffer) => {
		totalBytes += data.length;
		const { complete, pending } = splitIncompleteAnsiSuffix(pendingAnsi + decoder.decode(data, { stream: true }));
		pendingAnsi = pending;
		appendText(complete);
	};

	const flushOutput = () => {
		const rest = pendingAnsi + decoder.decode();
		pendingAnsi = "";
		appendText(rest);
	};

	let exitCode: number | null = null;
	try {
		({ exitCode } = await operations.exec(command, cwd, { onData, signal: options?.signal }));
	} catch (err) {
		// An aborted command still returns the output it produced so far
		if (!options?.signal?.aborted) {
			closeTempFile();
			throw err;
		}
	}

	flushOutput();
	const fullOutput = outputChunks.slice(outputStart).join("");
	const truncationResult = truncateTail(fullOutput);
	if (truncationResult.truncated) {
		ensureTempFile();
	}
	closeTempFile();
	const cancelled = options?.signal?.aborted ?? false;

	return {
		output: truncationResult.truncated ? truncationResult.content : fullOutput,
		exitCode: cancelled ? undefined : (exitCode ?? undefined),
		cancelled,
		truncated: truncationResult.truncated,
		fullOutputPath: tempFile?.opened ? tempFile.path : undefined,
	};
}
