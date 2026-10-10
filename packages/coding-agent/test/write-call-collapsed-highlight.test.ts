import type { TUI } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test } from "vitest";
import { createWriteToolDefinition } from "../src/core/tools/write.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";
import { stripAnsi } from "../src/utils/ansi.ts";

// A collapsed write call highlights only the first lines of the file, not every line as it arrives and the whole file again
// when it is complete. What it shows must be what a call that highlights everything shows: the first ten lines of the
// expanded call, and the same count of lines.

function writeCall(expanded: boolean): ToolExecutionComponent {
	const component = new ToolExecutionComponent(
		"write",
		"tool-write",
		{},
		{},
		createWriteToolDefinition(process.cwd()),
		{ requestRender: () => {} } as unknown as TUI,
		process.cwd(),
	);
	if (expanded) component.setExpanded(true);
	return component;
}

/** The lines of the box without its padding rows; the first is the header, the second empty. */
function shown(component: ToolExecutionComponent): string[] {
	return component
		.render(200)
		.slice(2, -1)
		.map((line) => line.trimEnd());
}

function expectSameAsExpanded(
	collapsed: ToolExecutionComponent,
	expanded: ToolExecutionComponent,
	label: string,
): void {
	const all = shown(expanded);
	const some = shown(collapsed);
	const lines = all.length - 2;
	if (lines <= 10) {
		expect(some, label).toEqual(all);
		return;
	}
	expect(some.slice(0, 11), label).toEqual(all.slice(0, 11));
	// (The color of "... more lines" starts at the end of the tenth line, before the line break.)
	expect(stripAnsi(some[11]), label).toBe(stripAnsi(all[11]));
	expect(some[11].startsWith(all[11].replace(/ *\x1b\[49m$/, "")), label).toBe(true);
	expect(some.length, label).toBe(13);
	expect(some[12], label).toContain(`(${lines - 10} more lines, ${lines} total,`);
}

const SOURCES: Record<string, string> = {
	"big/source.ts": Array.from(
		{ length: 400 },
		(_, n) =>
			`export function step${n}(input: number[]): number { return input.reduce((a, b) => a + b * ${n % 97}, ${n}); } // ${n}\n` +
			(n % 37 === 5
				? `/* a comment\n   over ${n} lines\n*/\nconst text${n} = \`template\n\t\${${n}} lines\`;\n\n`
				: ""),
	).join(""),
	"notes/readme.md": Array.from(
		{ length: 120 },
		(_, n) =>
			`## Section ${n}\n\nSome *text* with \`code\` and a [link](https://example.com/${n}).\r\n\n- item\n\n\`\`\`sh\nls -la ${n}\n\`\`\`\n`,
	).join(""),
	"data/values.json": JSON.stringify(
		{ list: Array.from({ length: 300 }, (_, n) => ({ n, text: `value ${n}`, ok: n % 2 === 0 })) },
		null,
		2,
	),
	"run.py":
		Array.from({ length: 200 }, (_, n) => `def f${n}(x):\n\t"""doc ${n}"""\n\treturn x + ${n}  # add\n\n`).join("") +
		"\n\n\n",
	"short.ts": "const a = 1;\nconst b = `two\nlines`;\n\n",
	"plain.txt": Array.from({ length: 40 }, (_, n) => `line ${n}`).join("\n"),
};

describe("write call, collapsed", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (const [path, content] of Object.entries(SOURCES)) {
		for (const step of [1, 7, 16, 97, 4096]) {
			test(`${path} streamed ${step} characters at a time shows what the expanded call does`, () => {
				const collapsed = writeCall(false);
				const expanded = writeCall(true);
				for (let length = step; length < content.length; length += step) {
					const args = { path, content: content.slice(0, length) };
					collapsed.updateArgs(args);
					expanded.updateArgs(args);
					if (step > 1 || length % 53 === 0)
						expectSameAsExpanded(collapsed, expanded, `after ${length} characters`);
				}
				for (const component of [collapsed, expanded]) {
					component.updateArgs({ path, content });
					component.setArgsComplete();
				}
				expectSameAsExpanded(collapsed, expanded, "complete");
				for (const component of [collapsed, expanded]) {
					component.markExecutionStarted();
					component.updateResult(
						{ content: [{ type: "text", text: "Successfully wrote" }], isError: false },
						false,
					);
				}
				expectSameAsExpanded(collapsed, expanded, "with its result");

				// Expanded after the fact, it is the expanded call; collapsed again, what it was.
				const before = shown(collapsed);
				collapsed.setExpanded(true);
				expect(shown(collapsed)).toEqual(shown(expanded));
				collapsed.setExpanded(false);
				expect(shown(collapsed)).toEqual(before);
				// (The expanded call it is compared with highlights all of the file at every step: a minute at 1 character a
				// time on a slow machine, such as a CI runner.)
			}, 120_000);
		}
	}

	test("expanded while the file streams in, and collapsed again", () => {
		const path = "big/source.ts";
		const content = SOURCES[path];
		const toggled = writeCall(false);
		const expanded = writeCall(true);
		const collapsed = writeCall(false);
		let step = 0;
		for (let length = 16; length < content.length; length += 61) {
			const args = { path, content: content.slice(0, length) };
			for (const component of [toggled, expanded, collapsed]) component.updateArgs(args);
			step++;
			if (step % 40 === 20) toggled.setExpanded(true);
			if (step % 40 === 0) toggled.setExpanded(false);
			expect(shown(toggled), `after ${length} characters`).toEqual(shown(step % 40 >= 20 ? expanded : collapsed));
		}
	});

	test("a large file is not highlighted when it is complete", () => {
		const content = SOURCES["big/source.ts"].repeat(12);
		const component = writeCall(false);
		component.updateArgs({ path: "big/source.ts", content: content.slice(0, 5000) });
		component.updateArgs({ path: "big/source.ts", content });
		const began = performance.now();
		component.setArgsComplete();
		component.markExecutionStarted();
		component.updateResult({ content: [{ type: "text", text: "ok" }], isError: false }, false);
		component.render(160);
		// (Highlighting all of it takes over a hundred times this.)
		expect(performance.now() - began).toBeLessThan(50);
		expect(shown(component)[12]).toMatch(/more lines, \d+ total/);
	});
});
