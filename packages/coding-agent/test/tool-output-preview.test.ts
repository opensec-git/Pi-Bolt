import { describe, expect, it } from "vitest";
import { formatTextPreview } from "../src/core/tools/renderers/text-preview.ts";
import { keyHint } from "../src/modes/interactive/components/keybinding-hints.ts";
import { initTheme, theme } from "../src/modes/interactive/theme/theme.ts";

describe("tool output previews", () => {
	it("matches full splitting at preview boundaries, including empty and trailing lines", () => {
		initTheme("dark");
		for (const limit of [0, 1, 10, 15, 20, undefined]) {
			for (const count of [0, 1, 9, 10, 11, 14, 15, 16, 19, 20, 21, 2000]) {
				const prefix = Array.from({ length: count }, (_, i) => (i % 3 ? `α🌲${i}` : "")).join("\n");
				for (const tail of ["", "\n", "\n\n"]) {
					const output = prefix + tail;
					const lines = output.split("\n");
					const visible = lines.slice(0, limit);
					let reference = visible.map((line) => theme.fg("toolOutput", line)).join("\n");
					const remaining = lines.length - visible.length;
					if (remaining > 0) {
						reference += `${theme.fg("muted", `\n... (${remaining} more lines,`)} ${keyHint("app.tools.expand", "to expand")}${theme.fg("muted", ")")}`;
					}
					expect(formatTextPreview(output, theme, limit)).toBe(reference);
				}
			}
		}
	});
});
