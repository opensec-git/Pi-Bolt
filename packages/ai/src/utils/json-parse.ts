import { parse as partialParse } from "partial-json";

const VALID_JSON_ESCAPES = new Set(['"', "\\", "/", "b", "f", "n", "r", "t", "u"]);

function escapeControlCharacter(char: string): string {
	switch (char) {
		case "\b":
			return "\\b";
		case "\f":
			return "\\f";
		case "\n":
			return "\\n";
		case "\r":
			return "\\r";
		case "\t":
			return "\\t";
		default:
			return `\\u${char.codePointAt(0)?.toString(16).padStart(4, "0") ?? "0000"}`;
	}
}

/**
 * Repairs malformed JSON string literals by:
 * - escaping raw control characters inside strings
 * - doubling backslashes before invalid escape characters
 *
 * Returns `json` itself when nothing needs repair. Unchanged runs are copied with slice() rather than one character at a
 * time: streamed tool-call arguments, often a whole file, come through here each time they are parsed.
 */
export function repairJson(json: string): string {
	let repaired = "";
	// Start of the run of characters that has not been copied to `repaired` yet.
	let runStart = 0;
	let inString = false;

	for (let index = 0; index < json.length; index++) {
		const code = json.charCodeAt(index);

		if (!inString) {
			if (code === 0x22) {
				inString = true;
			}
			continue;
		}

		if (code === 0x22) {
			inString = false;
			continue;
		}

		if (code === 0x5c) {
			const nextChar = json[index + 1];
			// "\u" stays as it is, with or without four hex digits after it.
			if (nextChar !== undefined && VALID_JSON_ESCAPES.has(nextChar)) {
				index += 1;
				continue;
			}
			repaired += `${json.slice(runStart, index)}\\\\`;
			runStart = index + 1;
			continue;
		}

		if (code <= 0x1f) {
			repaired += json.slice(runStart, index) + escapeControlCharacter(json[index]);
			runStart = index + 1;
		}
	}

	return runStart === 0 ? json : repaired + json.slice(runStart);
}

export function parseJsonWithRepair<T>(json: string): T {
	try {
		return JSON.parse(json) as T;
	} catch (error) {
		const repairedJson = repairJson(json);
		if (repairedJson !== json) {
			return JSON.parse(repairedJson) as T;
		}
		throw error;
	}
}

/**
 * Attempts to parse potentially incomplete JSON during streaming.
 * Always returns a valid object, even if the JSON is incomplete.
 *
 * @param partialJson The partial JSON string from streaming
 * @returns Parsed object or empty object if parsing fails
 */
export function parseStreamingJson<T = Record<string, unknown>>(partialJson: string | undefined): T {
	if (!partialJson || partialJson.trim() === "") {
		return {} as T;
	}

	try {
		return parseJsonWithRepair<T>(partialJson);
	} catch {
		try {
			const result = partialParse(partialJson);
			return (result ?? {}) as T;
		} catch {
			try {
				const result = partialParse(repairJson(partialJson));
				return (result ?? {}) as T;
			} catch {
				return {} as T;
			}
		}
	}
}

const lengthsParsedWhileStreaming = new WeakMap<object, number>();

/**
 * Arguments of a tool call that are still streaming in, for showing them as they arrive: parsed again on every delta while
 * shorter than 1 KB, and after that once they have grown by an eighth since `block`'s were last parsed; otherwise `current` is
 * kept. Parsing all that
 * has arrived on every delta made streaming a large argument, such as a whole file passed to a write tool, quadratic in its size.
 * The final arguments are parsed with parseStreamingJson() once the tool call is complete.
 */
export function parseStreamingJsonWhileStreaming<T = Record<string, unknown>>(
	block: object,
	partialJson: string | undefined,
	current: T | undefined,
): T {
	const length = partialJson?.length ?? 0;
	const parsed = lengthsParsedWhileStreaming.get(block);
	if (current !== undefined && parsed !== undefined && length - parsed < (parsed < 1024 ? 1 : parsed >> 3)) {
		return current;
	}
	lengthsParsedWhileStreaming.set(block, length);
	return parseStreamingJson<T>(partialJson);
}
