import assert from "node:assert";
import { describe, it } from "node:test";
import { setFlagsFromString } from "node:v8";
import { runInNewContext } from "node:vm";
import { Markdown } from "../src/components/markdown.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

// A reply that streams in is lexed and rendered again for each chunk, from what was lexed and rendered for the chunk
// before. Nothing made for one chunk may stay in memory once later chunks are rendered: a list of 400 items in chunks of
// 24 characters is about 1,000 renders, and keeping the text of each (the reply as it was then, or a token or line cut
// from it) would keep megabytes.
// The heap is measured after the job that rendered ends: until then, V8 keeps every WeakRef target made or dereferenced
// in the job (the tokens of each render, held weakly by the caches), which a loop of renders in one job grows by all
// of them, whatever the caches keep.

setFlagsFromString("--expose-gc");
const gc = runInNewContext("gc") as () => void;

async function heapAfterJob(): Promise<number> {
	await new Promise((resolve) => setImmediate(resolve));
	gc();
	gc();
	return process.memoryUsage().heapUsed;
}

function listAnswer(items: number): string {
	let answer = "";
	for (let i = 0; i < items; i++) answer += `- item ${i}: update the \`config${i}.json\` file and **restart** it\n`;
	return answer;
}

describe("Markdown memory while a reply streams in", () => {
	it("keeps about what rendering the whole reply once keeps", async () => {
		const answer = listAnswer(400);
		const start = await heapAfterJob();
		const once = new Markdown(answer, 1, 0, defaultMarkdownTheme);
		once.renderLive(120);
		const onceKept = (await heapAfterJob()) - start;

		const streamed = new Markdown("", 1, 0, defaultMarkdownTheme);
		let text = "";
		for (let at = 0; at < answer.length; at += 24) {
			const added = answer.slice(at, at + 24);
			text += added;
			streamed.setText(text, added);
			streamed.renderLive(120);
		}
		const streamedKept = (await heapAfterJob()) - start - onceKept;

		assert.deepStrictEqual(streamed.renderLive(120).lines, once.renderLive(120).lines);
		// (One copy of the reply for each chunk would be about 13 MB.)
		assert.ok(
			streamedKept < onceKept + 4_000_000,
			`streaming kept ${(streamedKept / 1e6).toFixed(1)} MB, rendering once ${(onceKept / 1e6).toFixed(1)} MB`,
		);
	});
});
