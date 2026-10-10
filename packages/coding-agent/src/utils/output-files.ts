/**
 * Output files: files pi writes so the model can use output it was not shown in full, such as the
 * full text of truncated tool output, binary MCP resources, and images shown by codemode scripts.
 * Every output file is created here, so where they are stored can change in one place. Today they
 * go to the OS temp directory.
 */

import { randomBytes } from "node:crypto";
import { closeSync, openSync, writeSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

/** Output can carry private data, so only the user may read the files. */
const OUTPUT_FILE_MODE = 0o600;

/**
 * Bytes an output file keeps. A command that prints without end (`yes`, a log follower, a runaway loop) would
 * otherwise fill the disk: the output file is written as fast as the command prints, until the disk is full.
 */
export const MAX_OUTPUT_FILE_BYTES = 1024 * 1024 * 1024;

/** A new, unused path: `<dir>/<prefix>-<random hex><extension>`. `extension` includes the dot. */
function createOutputFilePath(prefix: string, extension: string): string {
	return join(tmpdir(), `${prefix}-${randomBytes(8).toString("hex")}${extension}`);
}

/** Write `data` to a new output file and return its path. */
export async function writeOutputFile(prefix: string, extension: string, data: string | Uint8Array): Promise<string> {
	const path = createOutputFilePath(prefix, extension);
	// `wx` never follows a link someone else placed at the path.
	await writeFile(path, data, { mode: OUTPUT_FILE_MODE, flag: "wx" });
	return path;
}

/**
 * An output file that streamed output is appended to as it arrives.
 *
 * Writes are synchronous. A command can print faster than an asynchronous file stream drains (a slow or
 * busy disk), and a stream queues what it has not written yet in memory without bound: a long command
 * then holds its whole output in memory, and draining a long queue costs more per write the longer it
 * gets. Writing each chunk before the next one is read keeps memory bounded: while the disk is slow the
 * pipe fills up and the command waits.
 */
export class OutputFile {
	readonly path: string;
	/** Whether the file was created. When it was not, `close()` throws why, and there is no file at `path`. */
	readonly opened: boolean;
	private readonly maxBytes: number;
	private fd: number | undefined;
	private error: Error | undefined;
	private bytesWritten = 0;

	constructor(path: string, maxBytes = MAX_OUTPUT_FILE_BYTES) {
		this.path = path;
		this.maxBytes = maxBytes;
		try {
			// `wx` never follows a link someone else placed at the path.
			this.fd = openSync(path, "wx", OUTPUT_FILE_MODE);
		} catch (error) {
			// Writes run in output handlers, where a throw would end the process; `close()` reports it.
			this.error = error instanceof Error ? error : new Error(String(error));
		}
		this.opened = this.fd !== undefined;
	}

	/** Whether the file misses output: it was not created, or a write to it failed. */
	get failed(): boolean {
		return this.error !== undefined;
	}

	/**
	 * Append `data`. After a failed write the file stops taking data, and `close()` throws the error. Past `maxBytes`
	 * the file ends with a note and takes no more data.
	 */
	write(data: string | Uint8Array): void {
		if (this.fd === undefined) {
			return;
		}
		try {
			let bytes = typeof data === "string" ? Buffer.from(data, "utf-8") : data;
			const room = this.maxBytes - this.bytesWritten;
			const full = bytes.length >= room;
			if (full) {
				bytes = Buffer.concat([
					bytes.subarray(0, Math.max(0, room)),
					Buffer.from(`\n[Output beyond ${this.maxBytes} bytes was not saved]\n`),
				]);
			}
			let offset = 0;
			while (offset < bytes.length) {
				offset += writeSync(this.fd, bytes, offset, bytes.length - offset);
			}
			this.bytesWritten += bytes.length;
			if (full) this.closeFd();
		} catch (error) {
			this.error = error instanceof Error ? error : new Error(String(error));
			this.closeFd();
		}
	}

	/** Close the file. Throws the error of a failed write, if there was one. */
	close(): void {
		this.closeFd();
		if (this.error) {
			throw this.error;
		}
	}

	private closeFd(): void {
		if (this.fd === undefined) {
			return;
		}
		const fd = this.fd;
		this.fd = undefined;
		try {
			closeSync(fd);
		} catch (error) {
			this.error ??= error instanceof Error ? error : new Error(String(error));
		}
	}
}

/** Open a new output file for streamed output. */
export function createOutputFile(prefix: string, extension: string): OutputFile {
	return new OutputFile(createOutputFilePath(prefix, extension));
}
