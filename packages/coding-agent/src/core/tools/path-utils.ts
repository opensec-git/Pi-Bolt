import { accessSync, constants } from "node:fs";
import { access } from "node:fs/promises";
import { normalizePath, resolvePath } from "../../utils/paths.ts";

const NARROW_NO_BREAK_SPACE = "\u202F";

function tryMacOSScreenshotPath(filePath: string): string {
	return filePath.replace(/ (AM|PM)\./gi, `${NARROW_NO_BREAK_SPACE}$1.`);
}

function tryNFDVariant(filePath: string): string {
	// macOS stores filenames in NFD (decomposed) form, try converting user input to NFD
	return filePath.normalize("NFD");
}

function tryCurlyQuoteVariant(filePath: string): string {
	// macOS uses U+2019 (right single quotation mark) in screenshot names like "Capture d'écran"
	// Users typically type U+0027 (straight apostrophe)
	return filePath.replace(/'/g, "\u2019");
}

function fileExists(filePath: string): boolean {
	try {
		accessSync(filePath, constants.F_OK);
		return true;
	} catch {
		return false;
	}
}

export async function pathExists(filePath: string): Promise<boolean> {
	try {
		await access(filePath, constants.F_OK);
		return true;
	} catch {
		return false;
	}
}

export function expandPath(filePath: string): string {
	return normalizePath(filePath, { normalizeUnicodeSpaces: true, stripAtPrefix: true });
}

/**
 * Resolve a path relative to the given cwd.
 * Handles ~ expansion and absolute paths.
 */
export function resolveToCwd(filePath: string, cwd: string): string {
	return resolvePath(filePath, cwd, { normalizeUnicodeSpaces: true, stripAtPrefix: true });
}

/** Alternate spellings are relevant only if normalization changes the pathname. */
function readPathVariants(resolved: string): string[] {
	const nfd = tryNFDVariant(resolved);
	return [tryMacOSScreenshotPath(resolved), nfd, tryCurlyQuoteVariant(resolved), tryCurlyQuoteVariant(nfd)].filter(
		(path, index, variants) => path !== resolved && variants.indexOf(path) === index,
	);
}

export function resolveReadPath(filePath: string, cwd: string): string {
	const resolved = resolveToCwd(filePath, cwd);
	const variants = readPathVariants(resolved);
	if (variants.length === 0 || fileExists(resolved)) return resolved;
	return variants.find(fileExists) ?? resolved;
}

export async function resolveReadPathAsync(filePath: string, cwd: string): Promise<string> {
	const resolved = resolveToCwd(filePath, cwd);
	const variants = readPathVariants(resolved);
	if (variants.length === 0 || (await pathExists(resolved))) return resolved;
	for (const variant of variants) {
		if (await pathExists(variant)) return variant;
	}
	return resolved;
}
