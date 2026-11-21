import { keyHint } from "../../../modes/interactive/components/keybinding-hints.ts";
import type { Theme } from "../../../modes/interactive/theme/theme.ts";

/** Render the visible lines and count the rest without allocating a string for every hidden line. */
export function formatTextPreview(output: string, theme: Theme, limit?: number): string {
	const lines = output.split("\n", limit);
	const text = lines.map((line) => theme.fg("toolOutput", line)).join("\n");
	if (limit === undefined || lines.length < limit) return text;

	let hiddenStart = 0;
	for (const line of lines) hiddenStart += line.length + 1;
	if (hiddenStart > output.length) return text;
	let remaining = 1;
	for (let i = output.indexOf("\n", hiddenStart); i !== -1; i = output.indexOf("\n", i + 1)) remaining++;
	return `${text}${theme.fg("muted", `\n... (${remaining} more lines,`)} ${keyHint("app.tools.expand", "to expand")}${theme.fg("muted", ")")}`;
}
