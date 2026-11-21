import assert from "node:assert/strict";
import { it } from "node:test";
import { Markdown, setMarkdownCachingForTest } from "../src/components/markdown.ts";
import { wrapStreamingParagraph, wrapTextWithAnsi } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

it("resumes only at an unchanged row boundary with no active ANSI styling", () => {
	const word = "A \x1b[31mstyled\x1b[39m word with text. ";
	const before = `${word.repeat(40)}end`;
	const width = 44;
	const previous = wrapStreamingParagraph(before, width);
	assert.ok(previous.next);
	// Replacing the unfinished end is safe when the word that forced the last retained break still matches.
	const extension = `${before.slice(0, -3)}another word `;
	const resumed = wrapStreamingParagraph(extension, width, previous.next);
	assert.ok(resumed.shared > 10);
	assert.deepEqual(previous.lines.slice(0, resumed.shared).concat(resumed.lines), wrapTextWithAnsi(extension, width));

	const fallbacks = [
		[`edited ${extension}`, width],
		[extension, width + 1],
		[`${extension}日本語`, width],
		[`${extension}\nnext line`, width],
		[extension + "x".repeat(100), width],
	] as const;
	for (const [text, columns] of fallbacks) {
		const wrapped = wrapStreamingParagraph(text, columns, previous.next);
		assert.equal(wrapped.shared, 0);
		assert.deepEqual(wrapped.lines, wrapTextWithAnsi(text, columns));
	}
});

it("renders the same streaming paragraph after edits, resizing and closing Markdown spans", () => {
	const start = `Intro.\n\n${"A `code span`, **bold text**, and another word. ".repeat(40)}`;
	const background = { bgColor: (text: string) => `\x1b[44m${text}\x1b[49m` };
	const component = new Markdown("", 1, 1, defaultMarkdownTheme, background);
	const opened = `${start}[ref][one] and *unfinished emphasis`;
	const closed = `${opened}* appended`;
	const versions = [
		[start, 84],
		[opened, 84],
		[closed, 84],
		[`${closed} in a narrower terminal`, 60],
		[closed.replace("A `code span`", "A [link](https://example.com)"), 60],
		[`${closed} with 日本語 👩\u200d💻`, 84],
		[`${closed}\n\n[one]: https://example.com\n`, 84],
		[`${closed}\n\n- a new block`, 84],
	] as const;
	for (const [text, width] of versions) {
		component.setText(text);
		const actual = component.renderLive(width).lines;
		setMarkdownCachingForTest(false);
		try {
			assert.deepEqual(actual, new Markdown(text, 1, 1, defaultMarkdownTheme, background).render(width));
		} finally {
			setMarkdownCachingForTest(true);
		}
	}
});

it("keeps a streaming wrap on append and releases it when the paragraph stops growing", () => {
	for (const renderLive of [false, true]) {
		const text = `Live rendering ${renderLive}. ${"A `code span` and other words. ".repeat(50)}`;
		const component = new Markdown("", 1, 0, defaultMarkdownTheme);
		const checkpoint = (): unknown => Reflect.get(component, "paragraphWrap");
		const render = () => (renderLive ? component.renderLive(48) : component.render(48));
		component.setText(text);
		render();
		const previous = checkpoint();
		assert.ok(previous);
		component.setText(`${text}more text`);
		assert.equal(checkpoint(), previous);
		render();
		assert.ok(checkpoint());

		component.setText(`${text}more text\n\n- a list`);
		render();
		assert.equal(checkpoint(), undefined);

		for (const replacement of ["", "Replaced text"]) {
			component.setText(`${text}Before replacing with ${JSON.stringify(replacement)}`);
			render();
			assert.ok(checkpoint());
			component.setText(replacement);
			assert.equal(checkpoint(), undefined);
		}

		component.setText(`${text}Before invalidation`);
		render();
		assert.ok(checkpoint());
		component.invalidate();
		assert.equal(checkpoint(), undefined);
	}
});
