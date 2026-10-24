/**
 * Fuzzy matching utilities.
 * Matches if all query characters appear in order (not necessarily consecutive).
 * Lower score = better match.
 */

export interface FuzzyMatch {
	matches: boolean;
	score: number;
}

/** Whether text[i] starts a word: it is the first character, or follows whitespace or one of - _ . / : */
function isWordBoundary(textLower: string, i: number): boolean {
	if (i === 0) return true;
	const code = textLower.charCodeAt(i - 1);
	// ASCII without a regex test: every candidate alignment checks this for each query character.
	if (code < 128) {
		return (
			code === 32 ||
			code === 45 ||
			code === 95 ||
			code === 46 ||
			code === 47 ||
			code === 58 ||
			(code >= 9 && code <= 13)
		);
	}
	return /\s/.test(textLower[i - 1]!);
}

/**
 * Score one alignment of the query in the text, or return Infinity if the query does not match there.
 * With start === -1 the alignment is greedy: each query character at its first occurrence after the previous one.
 * Otherwise the query is matched contiguously at start.
 */
function scoreAlignment(normalizedQuery: string, textLower: string, start: number): number {
	let score = 0;
	let lastMatchIndex = -1;
	let consecutiveMatches = 0;

	for (let queryIndex = 0; queryIndex < normalizedQuery.length; queryIndex++) {
		const i = start === -1 ? textLower.indexOf(normalizedQuery[queryIndex]!, lastMatchIndex + 1) : start + queryIndex;
		if (i === -1) return Number.POSITIVE_INFINITY;

		// Reward consecutive matches
		if (lastMatchIndex === i - 1) {
			consecutiveMatches++;
			score -= consecutiveMatches * 5;
		} else {
			consecutiveMatches = 0;
			// Penalize gaps
			if (lastMatchIndex >= 0) {
				score += (i - lastMatchIndex - 1) * 2;
			}
		}

		// Reward word boundary matches
		if (isWordBoundary(textLower, i)) {
			score -= 10;
		}

		// Slight penalty for later matches
		score += i * 0.1;

		lastMatchIndex = i;
	}

	return score;
}

function matchQuery(normalizedQuery: string, textLower: string): FuzzyMatch {
	if (normalizedQuery.length === 0) {
		return { matches: true, score: 0 };
	}

	if (normalizedQuery.length > textLower.length) {
		return { matches: false, score: 0 };
	}

	let score = scoreAlignment(normalizedQuery, textLower, -1);
	if (score === Number.POSITIVE_INFINITY) {
		return { matches: false, score: 0 };
	}

	// The greedy alignment can scatter the query over an earlier, unrelated part of the text,
	// e.g. "opus" in "openrouter openrouter/anthropic/claude-opus" takes o, p, u from "openrouter".
	// Also score every contiguous occurrence and keep the best alignment.
	for (
		let start = textLower.indexOf(normalizedQuery);
		start !== -1;
		start = textLower.indexOf(normalizedQuery, start + 1)
	) {
		score = Math.min(score, scoreAlignment(normalizedQuery, textLower, start));
	}

	if (normalizedQuery === textLower) {
		score -= 100;
	}

	return { matches: true, score };
}

export function fuzzyMatch(query: string, text: string): FuzzyMatch {
	const queryLower = query.toLowerCase();
	const textLower = text.toLowerCase();

	const primaryMatch = matchQuery(queryLower, textLower);
	if (primaryMatch.matches) {
		return primaryMatch;
	}

	const alphaNumericMatch = queryLower.match(/^(?<letters>[a-z]+)(?<digits>[0-9]+)$/);
	const numericAlphaMatch = queryLower.match(/^(?<digits>[0-9]+)(?<letters>[a-z]+)$/);
	const swappedQuery = alphaNumericMatch
		? `${alphaNumericMatch.groups?.digits ?? ""}${alphaNumericMatch.groups?.letters ?? ""}`
		: numericAlphaMatch
			? `${numericAlphaMatch.groups?.letters ?? ""}${numericAlphaMatch.groups?.digits ?? ""}`
			: "";

	if (!swappedQuery) {
		return primaryMatch;
	}

	const swappedMatch = matchQuery(swappedQuery, textLower);
	if (!swappedMatch.matches) {
		return primaryMatch;
	}

	return { matches: true, score: swappedMatch.score + 5 };
}

/**
 * Filter and sort items by fuzzy match quality (best matches first).
 * Supports whitespace- and slash-separated tokens: all tokens must match.
 */
export function fuzzyFilter<T>(items: T[], query: string, getText: (item: T) => string): T[] {
	if (!query.trim()) {
		return items;
	}

	const tokens = query
		.trim()
		.split(/[\s/]+/)
		.filter((t) => t.length > 0);

	if (tokens.length === 0) {
		return items;
	}

	const results: { item: T; totalScore: number }[] = [];

	for (const item of items) {
		const text = getText(item);
		let totalScore = 0;
		let allMatch = true;

		for (const token of tokens) {
			const match = fuzzyMatch(token, text);
			if (match.matches) {
				totalScore += match.score;
			} else {
				allMatch = false;
				break;
			}
		}

		if (allMatch) {
			results.push({ item, totalScore });
		}
	}

	results.sort((a, b) => a.totalScore - b.totalScore);
	return results.map((r) => r.item);
}
