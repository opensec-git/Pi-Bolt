import { readdir as fsReaddir, stat as fsStat } from "node:fs/promises";
import nodePath from "path";
import type { Static } from "typebox";
import type { ExtensionContext, ToolDefinition } from "../extensions/types.ts";
import { pathExists, resolveToCwd } from "./path-utils.ts";
import { lsRenderers } from "./renderers/ls.ts";
import { type TypeBoxAgentTool, wrapToolDefinition } from "./tool-definition-wrapper.ts";
import { lsSchema } from "./tool-schemas.generated.ts";
import { DEFAULT_MAX_BYTES, formatSize, type TruncationResult, truncateHead, truncationDetails } from "./truncate.ts";

export const lsToolSystemPromptContribution = {
	snippet: "List directory contents",
	guidelines: [],
} as const;

export type LsToolInput = Static<typeof lsSchema>;

const DEFAULT_LIMIT = 500;

export interface LsToolDetails {
	truncation?: TruncationResult;
	entryLimitReached?: number;
}

/**
 * Pluggable operations for the ls tool.
 * Override these to delegate directory listing to remote systems (for example SSH).
 */
export interface LsOperations {
	/** Check if path exists */
	exists: (absolutePath: string) => Promise<boolean> | boolean;
	/** Get file or directory stats. Throws if not found. */
	stat: (absolutePath: string) => Promise<{ isDirectory: () => boolean }> | { isDirectory: () => boolean };
	/** Read directory entries */
	readdir: (absolutePath: string) => Promise<string[]> | string[];
}

const defaultLsOperations: LsOperations = {
	exists: pathExists,
	stat: fsStat,
	readdir: fsReaddir,
};

/** A directory entry, and whether it is a directory when its type is known without a stat. */
interface LsEntry {
	name: string;
	/** The name in lower case, the sort key, computed once instead of in every comparison. */
	key: string;
	/** Undefined for symbolic links (a stat follows them, and drops broken ones) and for entries of unknown type. */
	isDirectory: boolean | undefined;
}

/**
 * The entries of `dirPath`. On the local filesystem one readdir also gives each entry's type, so only symbolic links and
 * entries of unknown type need a stat to tell whether they are directories: a stat per entry, awaited one after the
 * other, took 44-79 ms for a folder of 1,161 entries.
 */
async function readEntries(ops: LsOperations, dirPath: string): Promise<LsEntry[]> {
	if (ops !== defaultLsOperations) {
		return (await ops.readdir(dirPath)).map((name) => ({ name, key: name.toLowerCase(), isDirectory: undefined }));
	}
	return (await fsReaddir(dirPath, { withFileTypes: true })).map((dirent) => ({
		name: dirent.name,
		key: dirent.name.toLowerCase(),
		// A symbolic link's dirent is neither a file nor a directory.
		isDirectory: dirent.isDirectory() ? true : dirent.isFile() ? false : undefined,
	}));
}

export interface LsToolOptions {
	/** Custom operations for directory listing. Default: local filesystem */
	operations?: LsOperations;
}

export function createLsToolDefinition(
	cwd: string,
	options?: LsToolOptions,
): ToolDefinition<typeof lsSchema, LsToolDetails | undefined> {
	const ops = options?.operations ?? defaultLsOperations;
	return {
		name: "ls",
		label: "ls",
		description: `List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. Includes dotfiles. Output is truncated to ${DEFAULT_LIMIT} entries or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first).`,
		promptSnippet: lsToolSystemPromptContribution.snippet,
		parameters: lsSchema,
		async execute(
			_toolCallId,
			{ path, limit }: { path?: string; limit?: number },
			signal?: AbortSignal,
			_onUpdate?,
			ctx?: ExtensionContext,
		) {
			return new Promise((resolve, reject) => {
				if (signal?.aborted) {
					reject(new Error("Operation aborted"));
					return;
				}

				const onAbort = () => reject(new Error("Operation aborted"));
				signal?.addEventListener("abort", onAbort, { once: true });

				(async () => {
					try {
						const dirPath = resolveToCwd(path || ".", ctx?.cwd || cwd);
						const effectiveLimit = limit ?? DEFAULT_LIMIT;

						// Check if path exists.
						if (!(await ops.exists(dirPath))) {
							reject(new Error(`Path not found: ${dirPath}`));
							return;
						}

						// Check if path is a directory.
						const stat = await ops.stat(dirPath);
						if (!stat.isDirectory()) {
							reject(new Error(`Not a directory: ${dirPath}`));
							return;
						}

						// Read directory entries.
						let entries: LsEntry[];
						try {
							entries = await readEntries(ops, dirPath);
						} catch (e: any) {
							reject(new Error(`Cannot read directory: ${e.message}`));
							return;
						}

						// Sort alphabetically, case-insensitive.
						entries.sort((a, b) => a.key.localeCompare(b.key));

						// Format entries with directory indicators.
						const results: string[] = [];
						let entryLimitReached = false;
						// Whether a stat has confirmed that the entries' own types can be used. Until then every entry is
						// stat-ed: in a folder that can be read but not searched, readdir works and every stat fails, and
						// those entries are skipped.
						let typesConfirmed = false;
						for (const entry of entries) {
							if (results.length >= effectiveLimit) {
								entryLimitReached = true;
								break;
							}

							let isDirectory: boolean;
							if (entry.isDirectory !== undefined && typesConfirmed) {
								isDirectory = entry.isDirectory;
							} else {
								try {
									isDirectory = (await ops.stat(nodePath.join(dirPath, entry.name))).isDirectory();
								} catch {
									// Skip entries we cannot stat.
									continue;
								}
								if (entry.isDirectory !== undefined) typesConfirmed = true;
							}
							results.push(isDirectory ? `${entry.name}/` : entry.name);
						}

						signal?.removeEventListener("abort", onAbort);

						if (results.length === 0) {
							resolve({ content: [{ type: "text", text: "(empty directory)" }], details: undefined });
							return;
						}

						const rawOutput = results.join("\n");
						// Apply byte truncation. There is no separate line limit because entry count is already capped.
						const truncation = truncateHead(rawOutput, { maxLines: Number.MAX_SAFE_INTEGER });
						let output = truncation.content;
						const details: LsToolDetails = {};
						// Build actionable notices for truncation and entry limits.
						const notices: string[] = [];
						if (entryLimitReached) {
							notices.push(`${effectiveLimit} entries limit reached. Use limit=${effectiveLimit * 2} for more`);
							details.entryLimitReached = effectiveLimit;
						}
						if (truncation.truncated) {
							notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
							details.truncation = truncationDetails(truncation);
						}
						if (notices.length > 0) {
							output += `\n\n[${notices.join(". ")}]`;
						}

						resolve({
							content: [{ type: "text", text: output }],
							details: Object.keys(details).length > 0 ? details : undefined,
						});
					} catch (e: any) {
						signal?.removeEventListener("abort", onAbort);
						reject(e);
					}
				})();
			});
		},
		...lsRenderers,
	};
}

export function createLsTool(cwd: string, options?: LsToolOptions): TypeBoxAgentTool<typeof lsSchema> {
	return wrapToolDefinition(createLsToolDefinition(cwd, options));
}
