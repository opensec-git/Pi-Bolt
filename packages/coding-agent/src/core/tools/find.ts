import { stat } from "node:fs/promises";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { spawn } from "child_process";
import path from "path";
import { type Static, Type } from "typebox";
import { PIBOLT } from "../../pi-bolt.ts";
import { createInterface } from "../../utils/readline.ts";
import { ensureTool } from "../../utils/tools-manager.ts";
import type { ExtensionContext, ToolDefinition } from "../extensions/types.ts";
import { nativeFind } from "./native-search.ts";
import { pathExists, resolveToCwd } from "./path-utils.ts";
import { findRenderers } from "./renderers/find.ts";
import { wrapToolDefinition } from "./tool-definition-wrapper.ts";
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

const findSchema = Type.Object({
	pattern: Type.String({
		description: "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'",
	}),
	path: Type.Optional(Type.String({ description: "Directory to search in (default: current directory)" })),
	limit: Type.Optional(Type.Number({ description: "Maximum number of results (default: 1000)" })),
});

export const findToolSystemPromptContribution = {
	snippet: "Find files by glob pattern (respects .gitignore)",
	guidelines: [],
} as const;

export type FindToolInput = Static<typeof findSchema>;

const DEFAULT_LIMIT = 1000;

/**
 * A glob of fd's (`**`, `*`, `?`, `[...]`, `{a,b}`, `/`) as a regular expression over a whole Windows path: `/` is either
 * separator, `**` any number of folders, `*` and `?` stay within one name. For fd's --full-path, where its glob mode cannot
 * match Windows paths.
 */
export function globToWindowsPathRegex(glob: string): string {
	const sep = String.raw`[\\/]`;
	const notSep = String.raw`[^\\/]`;
	// A character inside a class, for Rust's regex: `[`, `\`, `&`, `~` (its class operators `&&`, `~~`) and `^` taken as they are.
	const classChar = (ch: string) => ch.replace(/[[\]\\&~^]/g, "\\$&");
	let out = "";
	let braces = 0;
	for (let i = 0; i < glob.length; i++) {
		const c = glob[i];
		if (c === "\\" && i + 1 < glob.length) {
			// An escaped character (`\[id\]`), as it is.
			out += glob[i + 1].replace(/[.+*?^$()[\]{}|\\]/g, "\\$&");
			i++;
		} else if (c === "*" && glob[i + 1] === "*") {
			const atStart = i === 0 || glob[i - 1] === "/";
			if (atStart && glob[i + 2] === "/") {
				out += `(?:.*${sep})?`; // "**/": no folder or any number of them
				i += 2;
			} else if (atStart && i + 2 === glob.length) {
				out += ".*"; // a trailing "**": everything below
				i += 1;
			} else {
				out += ".*";
				i += 1;
			}
		} else if (c === "*") {
			out += `${notSep}*`;
		} else if (c === "?") {
			out += notSep;
		} else if (c === "/") {
			out += sep;
		} else if (c === "[") {
			// A class: `[!...]` or `[^...]` negated (within one name, as `?`), a `]` first taken as itself (`[[]`, `[]]`),
			// `\x` escaped, `a-z` a range.
			let j = i + 1;
			const negated = glob[j] === "!" || glob[j] === "^";
			if (negated) j++;
			let body = "";
			let first = true;
			while (j < glob.length && (glob[j] !== "]" || first)) {
				if (glob[j] === "\\" && j + 1 < glob.length) {
					body += classChar(glob[j + 1]);
					j += 2;
				} else if (glob[j] === "-" && !first && glob[j + 1] !== "]" && glob[j - 1] !== "-") {
					body += "-";
					j++;
				} else {
					body += glob[j] === "-" ? "\\-" : classChar(glob[j]);
					j++;
				}
				first = false;
			}
			if (j >= glob.length) {
				out += "\\[";
			} else {
				out += negated ? `[^\\\\/${body}]` : `[${body}]`;
				i = j;
			}
		} else if (c === "{") {
			out += "(?:";
			braces++;
		} else if (c === "}" && braces > 0) {
			out += ")";
			braces--;
		} else if (c === "," && braces > 0) {
			out += "|";
		} else {
			out += c.replace(/[.+^$()|\\{}[\]]/g, "\\$&");
		}
	}
	return `^${out}$`;
}

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

/** The tool's result for the paths found (by fd, or in-process), relative to the search folder: cut to size, with notices. */
function formatFindResults(
	relativized: string[],
	effectiveLimit: number,
): { content: Array<{ type: "text"; text: string }>; details: FindToolDetails | undefined } {
	const resultLimitReached = relativized.length >= effectiveLimit;
	const rawOutput = relativized.join("\n");
	const truncation = truncateHead(rawOutput, { maxLines: Number.MAX_SAFE_INTEGER });
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
						const effectiveLimit = limit ?? DEFAULT_LIMIT;
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
							const results = await ops.glob(pattern, searchPath, {
								ignore: ["**/node_modules/**", "**/.git/**"],
								limit: effectiveLimit,
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

							// Relativize paths against the search root for stable output.
							const relativized = results.map((p) => relativizeFindResultPath(p, searchPath));
							const resultLimitReached = relativized.length >= effectiveLimit;
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
							const found = await nativeFind({
								root: searchPath,
								pattern: globPattern,
								limit: effectiveLimit,
								signal,
							});
							settle(() =>
								resolve(
									found.length === 0
										? {
												content: [{ type: "text", text: "No files found matching pattern" }],
												details: undefined,
											}
										: formatFindResults(found, effectiveLimit),
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

						const args: string[] = ["--glob", "--color=never", "--hidden"];

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
						args.push("--max-results", String(effectiveLimit));

						// fd --glob matches against the basename unless --full-path is set; in --full-path
						// mode it matches against the absolute candidate path, so a path-containing
						// pattern like 'src/**/*.spec.ts' needs a leading '**/' to match anything.
						// (Windows: `src\**\*.ts` as `src/**/*.ts`, and `./src/*.ts` as `src/*.ts`: see windowsGlobSeparators.)
						const globPattern =
							process.platform === "win32"
								? windowsGlobSeparators(pattern).replace(/^(?:\.\/)+(?=.)/, "")
								: pattern;
						let effectivePattern = globPattern;
						if (globPattern.includes("/")) {
							args.push("--full-path");
							if (!globPattern.startsWith("/") && !globPattern.startsWith("**/") && globPattern !== "**") {
								effectivePattern = `**/${globPattern}`;
							}
							// Windows: fd matches a full path with its backslashes, and its glob takes neither `/` nor `\` for them
							// (`[/\\]` in their place stops `**` spanning folders, and lets `*` cross them). The same pattern as a
							// regular expression, with `[\\/]` for a separator.
							if (process.platform === "win32") {
								args.splice(args.indexOf("--glob"), 1);
								effectivePattern = globToWindowsPathRegex(effectivePattern);
							}
						}
						args.push("--", effectivePattern, searchPath);

						const child = spawn(fdPath, args, { stdio: ["ignore", "pipe", "pipe"] });
						const rl = createInterface({ input: child.stdout });
						let stderr = "";
						const lines: string[] = [];

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

						rl.on("line", (line) => {
							lines.push(line);
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
							const output = lines.join("\n");
							if (code !== 0) {
								const errorMsg = stderr.trim() || `fd exited with code ${code}`;
								if (!output) {
									settle(() => reject(new Error(errorMsg)));
									return;
								}
							}
							if (!output) {
								settle(() =>
									resolve({
										content: [{ type: "text", text: "No files found matching pattern" }],
										details: undefined,
									}),
								);
								return;
							}

							const relativized: string[] = [];
							for (const rawLine of lines) {
								const line = rawLine.replace(/\r$/, "").trim();
								if (!line) continue;
								relativized.push(relativizeFindResultPath(line, searchPath));
							}

							settle(() => resolve(formatFindResults(relativized, effectiveLimit)));
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

export function createFindTool(cwd: string, options?: FindToolOptions): AgentTool<typeof findSchema> {
	return wrapToolDefinition(createFindToolDefinition(cwd, options));
}
