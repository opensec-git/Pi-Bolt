import type { Readable } from "node:stream";
import { StringDecoder } from "node:string_decoder";

/**
 * Serialize a single strict JSONL record.
 *
 * Framing is LF-only. Payload strings may contain other Unicode separators such as
 * U+2028 and U+2029. Clients must split records on `\n` only.
 */
export function serializeJsonLine(value: unknown): string {
	return `${JSON.stringify(value)}\n`;
}

/**
 * Attach an LF-only JSONL reader to a stream.
 *
 * This intentionally does not use Node readline. Readline splits on additional
 * Unicode separators that are valid inside JSON strings and therefore does not
 * implement strict JSONL framing.
 */
export function attachJsonlLineReader(stream: Readable, onLine: (line: string) => void): () => void {
	const decoder = new StringDecoder("utf8");
	// Text of the unfinished line, in the pieces it arrived in. Only new text is searched for "\n": searching and
	// growing one buffer per chunk makes a line of N bytes cost O(N^2) (a 40 MB line, such as a prompt with images,
	// took 620 ms instead of 6 ms).
	let pending: string[] = [];

	const emitLine = (line: string) => {
		onLine(line.endsWith("\r") ? line.slice(0, -1) : line);
	};

	const onData = (chunk: string | Buffer) => {
		const text = typeof chunk === "string" ? chunk : decoder.write(chunk);
		let start = 0;
		for (let newlineIndex = text.indexOf("\n"); newlineIndex !== -1; newlineIndex = text.indexOf("\n", start)) {
			const piece = text.slice(start, newlineIndex);
			if (pending.length > 0) {
				pending.push(piece);
				emitLine(pending.join(""));
				pending = [];
			} else {
				emitLine(piece);
			}
			start = newlineIndex + 1;
		}
		if (start < text.length) pending.push(text.slice(start));
	};

	const onEnd = () => {
		const rest = pending.join("") + decoder.end();
		pending = [];
		if (rest.length > 0) {
			emitLine(rest);
		}
	};

	stream.on("data", onData);
	stream.on("end", onEnd);

	return () => {
		stream.off("data", onData);
		stream.off("end", onEnd);
	};
}
