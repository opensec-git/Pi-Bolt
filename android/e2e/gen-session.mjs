// Writes a Pi session file (session-manager.ts format, version 3) with <count> messages, alternating a user prompt and a
// markdown assistant reply, for perf.sh (scrolling a long session, opening a huge one).
// Usage: node gen-session.mjs <cwd as the engine sees it> <count> <out.jsonl>
import { writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

const [cwd, countArg, out] = process.argv.slice(2);
const count = Number(countArg);
const start = Date.now() - count * 60_000;
const iso = (i) => new Date(start + i * 60_000).toISOString();
const usage = { input: 100, output: 200, cacheRead: 0, cacheWrite: 0, totalTokens: 300, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };
const lines = [JSON.stringify({ type: "session", version: 3, id: randomUUID(), timestamp: iso(0), cwd })];
let parent = null;
for (let i = 0; i < count; i++) {
	const id = randomUUID().slice(0, 8);
	const message =
		i % 2 === 0
			? { role: "user", content: [{ type: "text", text: `Question ${i}: how does part ${i} of the project work? ünïcödé ✓` }], timestamp: start + i * 60_000 }
			: {
					role: "assistant",
					content: [
						{
							type: "text",
							text: `Answer ${i}. Part **${i}** reads the \`config\` and then:\n\n- loads the files\n- checks the [docs](https://example.com/${i})\n\n\`\`\`kotlin\nfun part${i}(x: Int) = x * ${i} // comment\n\`\`\`\n\nThat is all for part ${i}.`,
						},
					],
					api: "openai-completions",
					provider: "fake",
					model: "fake-model",
					usage,
					stopReason: "stop",
					timestamp: start + i * 60_000,
				};
	lines.push(JSON.stringify({ type: "message", id, parentId: parent, timestamp: iso(i), message }));
	parent = id;
}
writeFileSync(out, `${lines.join("\n")}\n`);
