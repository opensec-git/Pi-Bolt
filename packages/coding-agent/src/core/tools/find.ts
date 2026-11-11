import { stat } from "node:fs/promises";
import { spawn } from "child_process";
import { Minimatch } from "minimatch";
import path from "path";
import type { Static } from "typebox";
import { PIBOLT } from "../../pi-bolt.ts";
import { createInterface } from "../../utils/readline.ts";
import { ensureTool } from "../../utils/tools-manager.ts";
import type { ExtensionContext, ToolDefinition } from "../extensions/types.ts";
import { nativeFind } from "./native-search.ts";
import { pathExists, resolveToCwd } from "./path-utils.ts";
import { findRenderers } from "./renderers/find.ts";
import { type TypeBoxAgentTool, wrapToolDefinition } from "./tool-definition-wrapper.ts";
import { findSchema } from "./tool-schemas.generated.ts";
import { DEFAULT_MAX_BYTES, formatSize, type TruncationResult, truncateHead, truncationDetails } from "./truncate.ts";

/** Relativize a find result against the search root and normalize it to posix separators. */
export function relativizeFindResultPath(
	resultPath: string,
	searchPath: string,
	pathModule: path.PlatformPath = path,
): string {
	const hadTrailingSeparator =
		resultPath.endsWith(pathModule.sep) || (pathModule.sep === "\\" && resultPath.endsWith("/"));
	const relativePath = pathModule.isAbsolute(resultPath) ? pathModule.relative(searchPath, resultPath) : resultPath;
	const posixPath = relativePath.split(pathModule.sep).join("/");
	return hadTrailingSeparator && !posixPath.endsWith("/") ? `${posixPath}/` : posixPath;
}

export const findToolSystemPromptContribution = {
	snippet: "Find files by glob pattern (respects .gitignore)",
	guidelines: [],
} as const;

export type FindToolInput = Static<typeof findSchema>;

const DEFAULT_LIMIT = 1000;

/**
 * Windows: a glob written with `\` for the separator (`src\**\*.ts`) as one with `/`. A Windows name cannot have `*`, `?` or
 * `\` in it, so a `\` before one of them, or before a plain character, is a separator; before `[`, `]`, `{`, `}`, `!` or `,`
 * (which a name may have) it is an escape, and stays.
 */
export function windowsGlobSeparators(glob: string): string {
	return glob.replace(/\\+(?![[\]{}!,])/g, "/");
}

export interface FindToolDetails {
	truncation?: TruncationResult;
	resultLimitReached?: number;
}

/**
 * Pluggable operations for the find tool.
 * Override these to delegate file search to remote systems (for example SSH).
 */
export interface FindOperations {
	/** Check if path exists */
	exists: (absolutePath: string) => Promise<boolean> | boolean;
	/** Find files matching glob pattern. Returns relative or absolute paths. */
	glob: (pattern: string, cwd: string, options: { ignore: string[]; limit: number }) => Promise<string[]> | string[];
}

const defaultFindOperations: FindOperations = {
	exists: pathExists,
	// This is a placeholder. Actual fd execution happens in execute() when no custom glob is provided.
	glob: () => [],
};

export interface FindToolOptions {
	/** Custom operations for find. Default: local filesystem plus fd */
	operations?: FindOperations;
	/** Search in-process instead of with fd (native-search.ts). Default: in Pi-Bolt builds, without custom operations. */
	native?: boolean;
}

/**
 * The tool's result for the paths found (by fd, or in-process), relative to the search folder: cut to size, with notices.
 * `stored` holds the results within the limit, or with `totals` only up to the first one past the byte cap (truncateHead
 * then decides truncation as for the full output, and `totals` gives the full output's line and byte counts).
 */
function formatFindResults(
	stored: string[],
	effectiveLimit: number,
	resultLimitReached: boolean,
	totals?: { totalLines: number; totalBytes: number },
): { content: Array<{ type: "text"; text: string }>; details: FindToolDetails | undefined } {
	const truncation = {
		...truncateHead(stored.join("\n"), { maxLines: Number.MAX_SAFE_INTEGER }),
		...totals,
	};
	let resultOutput = truncation.content;
	const details: FindToolDetails = {};
	const notices: string[] = [];
	if (resultLimitReached) {
		notices.push(
			`${effectiveLimit} results limit reached. Use limit=${effectiveLimit * 2} for more, or refine pattern`,
		);
		details.resultLimitReached = effectiveLimit;
	}
	if (truncation.truncated) {
		notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
		details.truncation = truncationDetails(truncation);
	}
	if (notices.length > 0) {
		resultOutput += `\n\n[${notices.join(". ")}]`;
	}
	return {
		content: [{ type: "text", text: resultOutput }],
		details: Object.keys(details).length > 0 ? details : undefined,
	};
}

export function createFindToolDefinition(
	cwd: string,
	options?: FindToolOptions,
): ToolDefinition<typeof findSchema, FindToolDetails | undefined> {
	const customOps = options?.operations;
	const native = !customOps && (options?.native ?? PIBOLT !== undefined);
	return {
		name: "find",
		label: "find",
		description: `Search for files by glob pattern. Returns matching file paths relative to the search directory. Respects .gitignore. Output is truncated to ${DEFAULT_LIMIT} results or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first).`,
		promptSnippet: findToolSystemPromptContribution.snippet,
		parameters: findSchema,
		async execute(
			_toolCallId,
			{ pattern, path: searchDir, limit }: { pattern: string; path?: string; limit?: number },
			signal?: AbortSignal,
			_onUpdate?,
			ctx?: ExtensionContext,
		) {
			return new Promise((resolve, reject) => {
				if (signal?.aborted) {
					reject(new Error("Operation aborted"));
					return;
				}

				let settled = false;
				let stopChild: (() => void) | undefined;
				const settle = (fn: () => void) => {
					if (settled) return;
					settled = true;
					signal?.removeEventListener("abort", onAbort);
					stopChild = undefined;
					fn();
				};
				const onAbort = () => {
					stopChild?.();
					settle(() => reject(new Error("Operation aborted")));
				};
				signal?.addEventListener("abort", onAbort, { once: true });

				(async () => {
					try {
						const searchPath = resolveToCwd(searchDir || ".", ctx?.cwd || cwd);
						// fd treats --max-results 0 as unlimited, so a limit below 1 means 1.
						const effectiveLimit = Math.max(1, Math.floor(limit ?? DEFAULT_LIMIT));
						const ops = customOps ?? defaultFindOperations;

						// If custom operations provide glob(), use that instead of fd.
						if (customOps?.glob) {
							if (!(await ops.exists(searchPath))) {
								settle(() => reject(new Error(`Path not found: ${searchPath}`)));
								return;
							}
							if (signal?.aborted) {
								settle(() => reject(new Error("Operation aborted")));
								return;
							}
							// One result past the limit tells "exactly N matches" apart from "more than N".
							const results = await ops.glob(pattern, searchPath, {
								ignore: ["**/node_modules/**", "**/.git/**"],
								limit: effectiveLimit + 1,
							});
							if (signal?.aborted) {
								settle(() => reject(new Error("Operation aborted")));
								return;
							}
							if (results.length === 0) {
								settle(() =>
									resolve({
										content: [{ type: "text", text: "No files found matching pattern" }],
										details: undefined,
									}),
								);
								return;
							}

							const resultLimitReached = results.length > effectiveLimit;
							// Relativize paths against the search root for stable output.
							const relativized = results
								.slice(0, effectiveLimit)
								.map((p) => relativizeFindResultPath(p, searchPath));
							const rawOutput = relativized.join("\n");
							const truncation = truncateHead(rawOutput, { maxLines: Number.MAX_SAFE_INTEGER });
							let resultOutput = truncation.content;
							const details: FindToolDetails = {};
							const notices: string[] = [];
							if (resultLimitReached) {
								notices.push(`${effectiveLimit} results limit reached`);
								details.resultLimitReached = effectiveLimit;
							}
							if (truncation.truncated) {
								notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
								details.truncation = truncationDetails(truncation);
							}
							if (notices.length > 0) {
								resultOutput += `\n\n[${notices.join(". ")}]`;
							}
							settle(() =>
								resolve({
									content: [{ type: "text", text: resultOutput }],
									details: Object.keys(details).length > 0 ? details : undefined,
								}),
							);
							return;
						}

						// Pi-Bolt: in-process (native-search.ts), with fd's rules and output.
						if (native) {
							let isDirectory = false;
							try {
								isDirectory = (await stat(searchPath)).isDirectory();
							} catch {
								settle(() => reject(new Error(`Path not found: ${searchPath}`)));
								return;
							}
							if (!isDirectory) {
								settle(() => reject(new Error(`The search path '${searchPath}' is not a directory.`)));
								return;
							}
							const globPattern =
								process.platform === "win32"
									? windowsGlobSeparators(pattern).replace(/^(?:\.\/)+(?=.)/, "")
									: pattern;
							// One result past the limit tells "exactly N matches" apart from "more than N" (#7121).
							const found = await nativeFind({
								root: searchPath,
								pattern: globPattern,
								limit: effectiveLimit + 1,
								signal,
							});
							settle(() =>
								resolve(
									found.length === 0
										? {
												content: [{ type: "text", text: "No files found matching pattern" }],
												details: undefined,
											}
										: formatFindResults(
												found.slice(0, effectiveLimit),
												effectiveLimit,
												found.length > effectiveLimit,
											),
								),
							);
							return;
						}

						// Default implementation uses fd.
						const fdPath = await ensureTool("fd");
						if (signal?.aborted) {
							settle(() => reject(new Error("Operation aborted")));
							return;
						}
						if (!fdPath) {
							settle(() => reject(new Error("fd is not available and could not be downloaded")));
							return;
						}

						const args: string[] = ["--color=never", "--hidden"];

						// fd normally ignores .gitignore outside git repos, so keep --no-require-git
						// there. Inside repos, use fd's default git-aware behavior so parent
						// .gitignore rules stop at nested repo boundaries:
						// https://github.com/earendil-works/pi/issues/5960
						let insideGitRepo = false;
						for (let current = searchPath; ; ) {
							if (await pathExists(path.join(current, ".git"))) {
								insideGitRepo = true;
								break;
							}
							const parent = path.dirname(current);
							if (parent === current) break;
							current = parent;
						}
						if (!insideGitRepo) args.push("--no-require-git");

						// fd --glob matches against the basename unless --full-path is set; in --full-path
						// mode it matches against the absolute candidate path, so a path-containing
						// pattern like 'src/**/*.spec.ts' needs a leading '**/' to match anything.
						// On Windows `\` is a path separator, so native patterns like 'src\**\*.ts' are
						// normalized to '/' (#9262). On POSIX `\` stays a glob escape character.
						let effectivePattern = process.platform === "win32" ? pattern.replaceAll("\\", "/") : pattern;
						const isPathPattern = effectivePattern.includes("/");
						if (
							isPathPattern &&
							!effectivePattern.startsWith("/") &&
							!effectivePattern.startsWith("**/") &&
							effectivePattern !== "**"
						) {
							effectivePattern = `**/${effectivePattern}`;
						}
						// fd before 10.5.1 never matches --full-path globs on Windows, and workarounds for that
						// break on fd 10.5.1 (sharkdp/fd#2067). pi cannot rely on either fd version, so on
						// Windows fd lists every entry and minimatch matches path patterns. The options keep
						// fd's glob behavior: `*` matches dotfiles, `+(a|b)` is literal, and an all-lowercase
						// pattern matches case-insensitively (smart case). A trailing '/**' becomes '/**/*'
						// because minimatch's 'src/**' also matches 'src' itself and fd's does not.
						const windowsPathMatcher =
							process.platform === "win32" && isPathPattern
								? new Minimatch(effectivePattern.replace(/\/\*\*$/, "/**/*"), {
										dot: true,
										noext: true,
										nocase: effectivePattern === effectivePattern.toLowerCase(),
									})
								: undefined;
						if (windowsPathMatcher) {
							args.push("--", "", searchPath);
						} else {
							// One result past the limit tells "exactly N matches" apart from "more than N" (#7121).
							args.push("--glob", "--max-results", String(effectiveLimit + 1));
							if (isPathPattern) args.push("--full-path");
							args.push("--", effectivePattern, searchPath);
						}

						// An abort during the .git lookup above already rejected the call, but there was no
						// child to stop yet. Do not start fd.
						if (signal?.aborted) return;
						const child = spawn(fdPath, args, { stdio: ["ignore", "pipe", "pipe"] });
						const rl = createInterface({ input: child.stdout });
						let stderr = "";
						// Results within the limit, stored only up to the first one past the byte cap:
						// truncateHead drops the rest anyway, so they are counted but not kept.
						const storedResults: string[] = [];
						let resultCount = 0;
						// Byte size of all results within the limit, each counted with a trailing newline.
						let outputBytes = 0;
						let resultLimitReached = false;

						stopChild = () => {
							if (!child.killed) {
								child.kill();
							}
						};

						const cleanup = () => {
							rl.close();
						};

						child.stderr?.on("data", (chunk) => {
							stderr += chunk.toString();
						});

						rl.on("line", (rawLine) => {
							const line = rawLine.replace(/\r$/, "").trim();
							if (!line) return;
							if (windowsPathMatcher && !windowsPathMatcher.match(line)) return;
							if (resultCount === effectiveLimit) {
								resultLimitReached = true;
								// Without --max-results, fd keeps listing after the limit.
								if (windowsPathMatcher) stopChild?.();
								return;
							}
							const result = relativizeFindResultPath(line, searchPath);
							resultCount++;
							if (outputBytes - 1 <= DEFAULT_MAX_BYTES) storedResults.push(result);
							outputBytes += Buffer.byteLength(result, "utf-8") + 1;
						});

						child.on("error", (error) => {
							cleanup();
							settle(() => reject(new Error(`Failed to run fd: ${error.message}`)));
						});

						child.on("close", (code) => {
							cleanup();
							if (signal?.aborted) {
								settle(() => reject(new Error("Operation aborted")));
								return;
							}
							if (code !== 0 && resultCount === 0) {
								const errorMsg = stderr.trim() || `fd exited with code ${code}`;
								settle(() => reject(new Error(errorMsg)));
								return;
							}
							if (resultCount === 0) {
								settle(() =>
									resolve({
										content: [{ type: "text", text: "No files found matching pattern" }],
										details: undefined,
									}),
								);
								return;
							}

							settle(() =>
								resolve(
									formatFindResults(storedResults, effectiveLimit, resultLimitReached, {
										totalLines: resultCount,
										totalBytes: outputBytes - 1,
									}),
								),
							);
						});
					} catch (e) {
						if (signal?.aborted) {
							settle(() => reject(new Error("Operation aborted")));
							return;
						}
						const error = e instanceof Error ? e : new Error(String(e));
						settle(() => reject(error));
					}
				})();
			});
		},
		...findRenderers,
	};
}

export function createFindTool(cwd: string, options?: FindToolOptions): TypeBoxAgentTool<typeof findSchema> {
	return wrapToolDefinition(createFindToolDefinition(cwd, options));
}
