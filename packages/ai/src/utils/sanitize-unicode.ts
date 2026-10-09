/**
 * Removes unpaired Unicode surrogate characters from a string.
 *
 * Unpaired surrogates (high surrogates 0xD800-0xDBFF without matching low surrogates 0xDC00-0xDFFF,
 * or vice versa) cause JSON serialization errors in many API providers.
 *
 * Valid emoji and other characters outside the Basic Multilingual Plane use properly paired
 * surrogates and will NOT be affected by this function.
 *
 * @param text - The text to sanitize
 * @returns The sanitized text with unpaired surrogates removed
 *
 * @example
 * // Valid emoji (properly paired surrogates) are preserved
 * sanitizeSurrogates("Hello 🙈 World") // => "Hello 🙈 World"
 *
 * // Unpaired high surrogate is removed
 * const unpaired = String.fromCharCode(0xD83D); // high surrogate without low
 * sanitizeSurrogates(`Text ${unpaired} here`) // => "Text  here"
 */
export function sanitizeSurrogates(text: string): string {
	// A well-formed string has no unpaired surrogate, which is all the expression below removes: as it is. Every request
	// sanitizes the whole conversation, and the engine answers this without a regular expression (for a string of 8-bit
	// characters, which cannot hold a surrogate, without looking at it). (isWellFormed: Node 20, Bun.)
	if (typeof text.isWellFormed === "function" && text.isWellFormed()) return text;
	// Replace unpaired high surrogates (0xD800-0xDBFF not followed by low surrogate)
	// Replace unpaired low surrogates (0xDC00-0xDFFF not preceded by high surrogate)
	return text.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "");
}
