import { createRequire } from "node:module";
import type * as Yaml from "yaml";
import { isBunBinary, isBundledNode } from "../config.ts";
import { stripBom } from "./text.ts";

type ParsedFrontmatter<T extends Record<string, unknown>> = {
	frontmatter: T;
	body: string;
};

const normalizeNewlines = (value: string): string => value.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

const extractFrontmatter = (content: string): { yamlString: string | null; body: string } => {
	const normalized = normalizeNewlines(stripBom(content));

	if (!normalized.startsWith("---")) {
		return { yamlString: null, body: normalized };
	}

	const endIndex = normalized.indexOf("\n---", 3);
	if (endIndex === -1) {
		return { yamlString: null, body: normalized };
	}

	return {
		yamlString: normalized.slice(4, endIndex),
		body: normalized.slice(endIndex + 4).trim(),
	};
};

let yamlModule: typeof Yaml | undefined;

/**
 * The yaml package, loaded for the first frontmatter that parseSimpleFrontmatter leaves to it: evaluating it costs about
 * 2.3 M instructions, which a start whose files all have simple frontmatter does not pay. Bundlers inline the literal
 * require(); elsewhere it resolves from node_modules.
 */
function yaml(): typeof Yaml {
	yamlModule ??= (
		isBunBinary || isBundledNode ? require("yaml") : createRequire(import.meta.url)("yaml")
	) as typeof Yaml;
	return yamlModule;
}

export const parseFrontmatter = <T extends Record<string, unknown> = Record<string, unknown>>(
	content: string,
): ParsedFrontmatter<T> => {
	const { yamlString, body } = extractFrontmatter(content);
	if (!yamlString) {
		return { frontmatter: {} as T, body };
	}
	const parsed = parseSimpleFrontmatter(yamlString) ?? yaml().parse(yamlString);
	return { frontmatter: (parsed ?? {}) as T, body };
};

export const stripFrontmatter = (content: string): string => parseFrontmatter(content).body;

// `key: value` with a key of ASCII letters, digits, `_` and `-`: no indentation, no space before the colon, nothing
// but spaces after it.
const SIMPLE_LINE = /^([A-Za-z_][A-Za-z0-9_-]*):(?:$| +(.*)$)/;
// Keys that yaml reads as something other than their text (`null: x` gives the key ""), or that an object treats specially.
const SPECIAL_KEYS = new Set(["null", "Null", "NULL", "true", "True", "TRUE", "false", "False", "FALSE", "__proto__"]);
// Characters YAML prints as they are: no tab, no C0 or C1 control, no NEL, no U+2028/U+2029 (yaml reads those as line
// breaks), no BOM, no U+FFFE/U+FFFF; surrogates only in pairs (with the `u` flag a lone surrogate is in no range here).
const PRINTABLE = /^[\x20-\x7E\xA0-\u2027\u202A-\uD7FF\uE000-\uFEFE\uFF00-\uFFFD\u{10000}-\u{10FFFF}]*$/u;
// A plain scalar starting with one of these is an indicator, a number, or something this parser does not read.
const UNSAFE_PLAIN_START = /^[-?:,[\]{}#&*!|>'"%@`+.0-9]/;
const SINGLE_QUOTED = /^'((?:[^']|'')*)'$/;
const DOUBLE_QUOTED = /^"([^"\\]*)"$/;

/**
 * What yaml's `parse` returns for frontmatter made only of `key: value` lines (and empty lines) whose values are plain
 * words or simply quoted strings without escapes; undefined for anything else, which yaml then parses. For every input it
 * accepts, the result equals yaml's (test/frontmatter.test.ts compares the two over a generated corpus and every
 * frontmatter block in the package).
 */
export function parseSimpleFrontmatter(yamlString: string): Record<string, unknown> | undefined {
	const result: Record<string, unknown> = {};
	for (const line of yamlString.split("\n")) {
		if (line === "") continue;
		const match = SIMPLE_LINE.exec(line);
		if (!match) return undefined;
		const key = match[1];
		// Duplicate keys are an error in yaml; long keys are past its implicit-key limit or close to it.
		if (SPECIAL_KEYS.has(key) || key.length > 128 || Object.hasOwn(result, key)) return undefined;
		const value = parseSimpleValue(match[2] ?? "");
		if (value === undefined) return undefined;
		result[key] = value;
	}
	return result;
}

function parseSimpleValue(raw: string): string | boolean | null | undefined {
	if (!PRINTABLE.test(raw)) return undefined;
	const value = raw.replace(/ +$/, "");
	if (value === "") return null;
	if (value[0] === "'") {
		const quoted = SINGLE_QUOTED.exec(value);
		return quoted ? quoted[1].replaceAll("''", "'") : undefined;
	}
	if (value[0] === '"') {
		return DOUBLE_QUOTED.exec(value)?.[1];
	}
	// `: ` or a final `:` starts a nested mapping, ` #` a comment.
	if (UNSAFE_PLAIN_START.test(value) || value.includes(": ") || value.endsWith(":") || value.includes(" #")) {
		return undefined;
	}
	switch (value) {
		case "~":
		case "null":
		case "Null":
		case "NULL":
			return null;
		case "true":
		case "True":
		case "TRUE":
			return true;
		case "false":
		case "False":
		case "FALSE":
			return false;
		default:
			return value;
	}
}
