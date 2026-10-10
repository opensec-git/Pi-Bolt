import { existsSync, readdirSync, readFileSync, statSync } from "fs";
import { basename, dirname, join, resolve, sep } from "path";
import { CONFIG_DIR_NAME } from "../config.ts";
import { parseFrontmatter } from "../utils/frontmatter.ts";
import { resolvePath } from "../utils/paths.ts";
import type { ResourceDiagnostic } from "./diagnostics.ts";
import { createSyntheticSourceInfo, type SourceInfo } from "./source-info.ts";

/**
 * Represents a prompt template loaded from a markdown file
 */
export interface PromptTemplate {
	name: string;
	description: string;
	argumentHint?: string;
	content: string;
	sourceInfo: SourceInfo;
	filePath: string; // Absolute path to the template file
}

/**
 * Parse command arguments respecting quoted strings (bash-style)
 * Returns array of arguments
 */
export function parseCommandArgs(argsString: string): string[] {
	const args: string[] = [];
	let current = "";
	let inQuote: string | null = null;

	for (let i = 0; i < argsString.length; i++) {
		const char = argsString[i];

		if (inQuote) {
			if (char === inQuote) {
				inQuote = null;
			} else {
				current += char;
			}
		} else if (char === '"' || char === "'") {
			inQuote = char;
		} else if (/\s/.test(char)) {
			if (current) {
				args.push(current);
				current = "";
			}
		} else {
			current += char;
		}
	}

	if (current) {
		args.push(current);
	}

	return args;
}

/**
 * Substitute argument placeholders in template content
 * Supports:
 * - $1, $2, ... for positional args
 * - $@ and $ARGUMENTS for all args
 * - ${N:-default} for positional arg N with default when missing/empty
 * - ${@:-default} and ${ARGUMENTS:-default} for all args with a default when empty
 * - ${@:N} for args from Nth onwards (bash-style slicing)
 * - ${@:N:L} for L args starting from Nth
 *
 * Note: Replacement happens on the template string only. Argument and default values
 * containing patterns like $1, $@, or $ARGUMENTS are NOT recursively substituted.
 */
export function substituteArgs(content: string, args: string[]): string {
	const allArgs = args.join(" ");

	return replaceArgumentReferences(
		content,
		(
			defaultTarget: string | undefined,
			defaultValue: string | undefined,
			sliceStart: string | undefined,
			sliceLength: string | undefined,
			simple: string | undefined,
		) => {
			if (defaultTarget) {
				const value =
					defaultTarget === "@" || defaultTarget === "ARGUMENTS" ? allArgs : args[parseInt(defaultTarget, 10) - 1];
				return value ? value : (defaultValue ?? "");
			}

			if (sliceStart) {
				let start = parseInt(sliceStart, 10) - 1; // Convert to 0-indexed (user provides 1-indexed)
				// Treat 0 as 1 (bash convention: args start at 1)
				if (start < 0) start = 0;

				if (sliceLength) {
					const length = parseInt(sliceLength, 10);
					return args.slice(start, start + length).join(" ");
				}
				return args.slice(start).join(" ");
			}

			if (simple === "ARGUMENTS" || simple === "@") {
				return allArgs;
			}

			const index = parseInt(simple ?? "", 10) - 1;
			return args[index] ?? "";
		},
	);
}

const DIGITS = /\d+/y;

/**
 * Calls `replace` for each reference to the arguments in `content`, left to right, as
 * `/\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)/g` would find them, and puts what it returns in
 * their place. A scan rather than that expression: on text with many "${1:-" and no "}" after them, the expression looks for
 * the "}" again from each of them (quadratic); here the next "}" is found once.
 */
function replaceArgumentReferences(
	content: string,
	replace: (
		defaultTarget: string | undefined,
		defaultValue: string | undefined,
		sliceStart: string | undefined,
		sliceLength: string | undefined,
		simple: string | undefined,
	) => string,
): string {
	const digitsAt = (at: number): string | undefined => {
		DIGITS.lastIndex = at;
		return DIGITS.exec(content)?.[0];
	};
	let closeAt: number | undefined; // the next "}" at or after where it was last looked for; -1: none
	const closeFrom = (at: number): number => {
		if (closeAt === undefined || (closeAt !== -1 && closeAt < at)) closeAt = content.indexOf("}", at);
		return closeAt;
	};
	let out = "";
	let copied = 0;
	for (let at = content.indexOf("$"); at !== -1; at = content.indexOf("$", at)) {
		let end = -1;
		let replacement = "";
		if (content[at + 1] === "{") {
			// ${N:-default}, ${ARGUMENTS:-default}, ${@:-default}
			const targetAt = at + 2;
			const target =
				digitsAt(targetAt) ??
				(content.startsWith("ARGUMENTS", targetAt) ? "ARGUMENTS" : content[targetAt] === "@" ? "@" : undefined);
			if (target !== undefined && content.startsWith(":-", targetAt + target.length)) {
				const valueAt = targetAt + target.length + 2;
				const close = closeFrom(valueAt);
				if (close !== -1) {
					end = close + 1;
					replacement = replace(target, content.slice(valueAt, close), undefined, undefined, undefined);
				}
			}
			// ${@:N} and ${@:N:L}
			if (end === -1 && content.startsWith("{@:", at + 1)) {
				const start = digitsAt(at + 4);
				if (start !== undefined) {
					const after = at + 4 + start.length;
					if (content[after] === "}") {
						end = after + 1;
						replacement = replace(undefined, undefined, start, undefined, undefined);
					} else if (content[after] === ":") {
						const length = digitsAt(after + 1);
						if (length !== undefined && content[after + 1 + length.length] === "}") {
							end = after + 2 + length.length;
							replacement = replace(undefined, undefined, start, length, undefined);
						}
					}
				}
			}
		}
		if (end === -1) {
			// $ARGUMENTS, $@, $N
			const simple = content.startsWith("ARGUMENTS", at + 1)
				? "ARGUMENTS"
				: content[at + 1] === "@"
					? "@"
					: digitsAt(at + 1);
			if (simple !== undefined) {
				end = at + 1 + simple.length;
				replacement = replace(undefined, undefined, undefined, undefined, simple);
			}
		}
		if (end === -1) {
			at++;
			continue;
		}
		out += content.slice(copied, at) + replacement;
		copied = end;
		at = end;
	}
	return out + content.slice(copied);
}

function loadTemplateFromFile(
	filePath: string,
	sourceInfo: SourceInfo,
): { template: PromptTemplate | null; diagnostics: ResourceDiagnostic[] } {
	const diagnostics: ResourceDiagnostic[] = [];
	let rawContent: string;
	try {
		rawContent = readFileSync(filePath, "utf-8");
	} catch (error) {
		const message = error instanceof Error ? error.message : "failed to read prompt template file";
		diagnostics.push({ type: "warning", message, path: filePath });
		return { template: null, diagnostics };
	}

	let frontmatter: Record<string, unknown>;
	let body: string;
	try {
		({ frontmatter, body } = parseFrontmatter(rawContent));
	} catch (error) {
		const message = error instanceof Error ? error.message : "failed to parse prompt template file";
		diagnostics.push({ type: "warning", message, path: filePath });
		return { template: null, diagnostics };
	}

	const name = basename(filePath).replace(/\.md$/, "");

	// Get description from frontmatter or first non-empty line
	let description = typeof frontmatter.description === "string" ? frontmatter.description : "";
	if (!description) {
		const firstLine = body.split("\n").find((line) => line.trim());
		if (firstLine) {
			// Truncate if too long
			description = firstLine.slice(0, 60);
			if (firstLine.length > 60) description += "...";
		}
	}

	const argumentHint = typeof frontmatter["argument-hint"] === "string" ? frontmatter["argument-hint"] : undefined;
	return {
		template: {
			name,
			description,
			...(argumentHint && { argumentHint }),
			content: body,
			sourceInfo,
			filePath,
		},
		diagnostics,
	};
}

/**
 * Scan a directory for .md files (non-recursive) and load them as prompt templates.
 */
function loadTemplatesFromDir(dir: string, getSourceInfo: (filePath: string) => SourceInfo): LoadPromptTemplatesResult {
	const templates: PromptTemplate[] = [];
	const diagnostics: ResourceDiagnostic[] = [];

	if (!existsSync(dir)) {
		return { templates, diagnostics };
	}

	try {
		const entries = readdirSync(dir, { withFileTypes: true });

		for (const entry of entries) {
			const fullPath = join(dir, entry.name);

			// For symlinks, check if they point to a file
			let isFile = entry.isFile();
			if (entry.isSymbolicLink()) {
				try {
					const stats = statSync(fullPath);
					isFile = stats.isFile();
				} catch {
					// Broken symlink, skip it
					continue;
				}
			}

			if (isFile && entry.name.endsWith(".md")) {
				const result = loadTemplateFromFile(fullPath, getSourceInfo(fullPath));
				if (result.template) {
					templates.push(result.template);
				}
				diagnostics.push(...result.diagnostics);
			}
		}
	} catch {
		return { templates, diagnostics };
	}

	return { templates, diagnostics };
}

export interface LoadPromptTemplatesOptions {
	/** Working directory for project-local templates. */
	cwd: string;
	/** Agent config directory for global templates. */
	agentDir: string;
	/** Explicit prompt template paths (files or directories). */
	promptPaths: string[];
	/** Include default prompt directories. */
	includeDefaults: boolean;
}

export interface LoadPromptTemplatesResult {
	templates: PromptTemplate[];
	diagnostics: ResourceDiagnostic[];
}

/**
 * Load all prompt templates from:
 * 1. Global: agentDir/prompts/
 * 2. Project: cwd/{CONFIG_DIR_NAME}/prompts/
 * 3. Explicit prompt paths
 */
export function loadPromptTemplates(options: LoadPromptTemplatesOptions): LoadPromptTemplatesResult {
	const resolvedCwd = resolvePath(options.cwd);
	const resolvedAgentDir = resolvePath(options.agentDir);
	const promptPaths = options.promptPaths;
	const includeDefaults = options.includeDefaults;

	const templates: PromptTemplate[] = [];
	const diagnostics: ResourceDiagnostic[] = [];
	const addResult = (result: LoadPromptTemplatesResult): void => {
		templates.push(...result.templates);
		diagnostics.push(...result.diagnostics);
	};

	const globalPromptsDir = join(resolvedAgentDir, "prompts");
	const projectPromptsDir = resolve(resolvedCwd, CONFIG_DIR_NAME, "prompts");

	const isUnderPath = (target: string, root: string): boolean => {
		const normalizedRoot = resolve(root);
		if (target === normalizedRoot) {
			return true;
		}
		const prefix = normalizedRoot.endsWith(sep) ? normalizedRoot : `${normalizedRoot}${sep}`;
		return target.startsWith(prefix);
	};

	const getSourceInfo = (resolvedPath: string): SourceInfo => {
		if (isUnderPath(resolvedPath, globalPromptsDir)) {
			return createSyntheticSourceInfo(resolvedPath, {
				source: "local",
				scope: "user",
				baseDir: globalPromptsDir,
			});
		}
		if (isUnderPath(resolvedPath, projectPromptsDir)) {
			return createSyntheticSourceInfo(resolvedPath, {
				source: "local",
				scope: "project",
				baseDir: projectPromptsDir,
			});
		}
		return createSyntheticSourceInfo(resolvedPath, {
			source: "local",
			baseDir: statSync(resolvedPath).isDirectory() ? resolvedPath : dirname(resolvedPath),
		});
	};

	if (includeDefaults) {
		addResult(loadTemplatesFromDir(globalPromptsDir, getSourceInfo));
		addResult(loadTemplatesFromDir(projectPromptsDir, getSourceInfo));
	}

	// 3. Load explicit prompt paths
	for (const rawPath of promptPaths) {
		const resolvedPath = resolvePath(rawPath, resolvedCwd, { trim: true });
		if (!existsSync(resolvedPath)) {
			continue;
		}

		try {
			const stats = statSync(resolvedPath);
			if (stats.isDirectory()) {
				addResult(loadTemplatesFromDir(resolvedPath, getSourceInfo));
			} else if (stats.isFile() && resolvedPath.endsWith(".md")) {
				const result = loadTemplateFromFile(resolvedPath, getSourceInfo(resolvedPath));
				if (result.template) {
					templates.push(result.template);
				}
				diagnostics.push(...result.diagnostics);
			}
		} catch (error) {
			const message = error instanceof Error ? error.message : "failed to read prompt template path";
			diagnostics.push({ type: "warning", message, path: resolvedPath });
		}
	}

	return { templates, diagnostics };
}

/**
 * Expand a prompt template if it matches a template name.
 * Returns the expanded content or the original text if not a template.
 */
export function expandPromptTemplate(text: string, templates: PromptTemplate[]): string {
	if (!text.startsWith("/")) return text;

	const match = text.match(/^\/([^\s]+)(?:\s+([\s\S]*))?$/);
	if (!match) return text;

	const templateName = match[1];
	const argsString = match[2] ?? "";

	const template = templates.find((t) => t.name === templateName);
	if (template) {
		const args = parseCommandArgs(argsString);
		return substituteArgs(template.content, args);
	}

	return text;
}
