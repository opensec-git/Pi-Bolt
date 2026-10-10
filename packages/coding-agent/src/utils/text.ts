/** Split a leading UTF-8 byte order mark from decoded text. */
export function splitBom(content: string): { bom: string; text: string } {
	return content.startsWith("\uFEFF") ? { bom: "\uFEFF", text: content.slice(1) } : { bom: "", text: content };
}

/** Remove a leading UTF-8 byte order mark from decoded text. */
export function stripBom(content: string): string {
	return splitBom(content).text;
}

/**
 * A copy of `text` that shares no memory with the string it was cut from. Substrings (`slice`, `split`, regex
 * matches) of a long string reference that string in JavaScriptCore and V8, so keeping a short piece of a large file
 * or output keeps all of it alive. For well-formed text (it goes through UTF-8, which keeps ASCII text at one byte per
 * character where UTF-16 would take two; a lone surrogate would become U+FFFD).
 */
export function detachString(text: string): string {
	return Buffer.from(text, "utf-8").toString("utf-8");
}
