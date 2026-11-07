// A long session for frame measurements: <turns> turns, each a prompt, an assistant step with a bash tool call and its
// result, then a markdown answer with code; every 3rd answer has a table, every 5th a mermaid diagram.
// Usage: node gen-long-session.mjs <cwd> <turns> <out.jsonl>
import { writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

const [cwd, turnsArg, out] = process.argv.slice(2);
const turns = Number(turnsArg);
const start = Date.now() - turns * 4 * 60_000;
let t = 0;
const ts = () => start + t++ * 60_000;
const usage = { input: 100, output: 200, cacheRead: 0, cacheWrite: 0, totalTokens: 300, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };
const lines = [JSON.stringify({ type: "session", version: 3, id: randomUUID(), timestamp: new Date(start).toISOString(), cwd })];
let parent = null;
const push = (message) => {
	const id = randomUUID().slice(0, 8);
	lines.push(JSON.stringify({ type: "message", id, parentId: parent, timestamp: new Date(message.timestamp).toISOString(), message }));
	parent = id;
};
const assistant = (content, stopReason) => ({ role: "assistant", content, api: "openai-completions", provider: "fake", model: "fake-model", usage, stopReason, timestamp: ts() });
for (let i = 0; i < turns; i++) {
	push({ role: "user", content: [{ type: "text", text: `Turn ${i}: explain module ${i} and show the code.` }], timestamp: ts() });
	const callId = `call_${i}_${randomUUID().slice(0, 6)}`;
	push(assistant([{ type: "text", text: `Reading module ${i}.` }, { type: "toolCall", id: callId, name: "bash", arguments: { command: `cat src/module${i}.ts | head -20` } }], "toolUse"));
	const output = Array.from({ length: 14 }, (_, k) => `export const value${k} = ${i} * ${k}; // line ${k}`).join("\n");
	push({ role: "toolResult", toolCallId: callId, toolName: "bash", content: [{ type: "text", text: output }], isError: false, timestamp: ts() });
	let text = `## Module ${i}\n\nModule **${i}** reads the \`config\`, validates it and then:\n\n1. loads the files\n2. checks the [docs](https://example.com/${i})\n3. writes the cache\n\n\`\`\`kotlin\nclass Module${i}(private val config: Config) {\n    fun run(x: Int): Int {\n        val y = x * ${i} // scale\n        return if (y > 10) y - 1 else y + 1\n    }\n}\n\`\`\`\n`;
	if (i % 3 === 0) text += `\n| field | type | default |\n| --- | --- | --- |\n| name | String | "m${i}" |\n| size | Int | ${i * 8} |\n| enabled | Boolean | true |\n`;
	if (i % 5 === 0) text += `\n\`\`\`mermaid\ngraph TD\n  A[Config ${i}] --> B{Valid?}\n  B -->|yes| C[Load]\n  B -->|no| D[Error]\n  C --> E[Cache]\n\`\`\`\n`;
	text += `\nThat covers module ${i}.`;
	push(assistant([{ type: "text", text }], "stop"));
}
writeFileSync(out, `${lines.join("\n")}\n`);
