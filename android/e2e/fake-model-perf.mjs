// Fake OpenAI-compatible model for perf.sh: the scripted fake model's replies, plus
// - "perf-long": a long markdown reply (headings, paragraphs, lists, code, a table) streamed in small chunks every
//   8 ms, for frame timing while streaming;
// - "perf-hold": an answer that starts after 20 s, so a turn stays running (kill / airplane / rotate checks).
// Usage: node fake-model-perf.mjs <port> [request-log.jsonl]
import { appendFileSync } from "node:fs";
import { createServer } from "node:http";

const port = Number(process.argv[2] ?? 18765);
const logFile = process.argv[3];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const textOf = (c) => (typeof c === "string" ? c : Array.isArray(c) ? c.map((b) => b.text ?? "").join(" ") : "");
const log = (entry) => {
	if (!logFile) return;
	try {
		appendFileSync(logFile, `${JSON.stringify({ at: new Date().toISOString(), ...entry })}\n`);
	} catch {}
};

function longReply() {
	const parts = [];
	for (let s = 1; s <= 12; s++) {
		parts.push(`## Section ${s}\n\nThis paragraph explains part ${s} of the change in some detail, with \`inline code\`, **bold** and *italic* words so the markdown renderer has real work on every frame. It wraps over several lines on a phone.\n`);
		parts.push(`- item one of ${s}\n- item two with a [link](https://example.com/${s})\n- item three\n`);
		parts.push("```kotlin\nfun section" + s + "(x: Int): Int {\n    val y = x * " + s + "\n    return y + 1 // comment\n}\n```\n");
		if (s % 3 === 0) parts.push(`| key | value |\n| --- | --- |\n| a${s} | ${s * 2} |\n| b${s} | ${s * 3} |\n`);
	}
	return parts.join("\n");
}

createServer(async (req, res) => {
	if (req.method === "GET" && req.url === "/health") {
		res.writeHead(200, { "content-type": "text/plain" }).end("ok");
		return;
	}
	let body = "";
	for await (const chunk of req) body += chunk;
	if (req.method !== "POST") {
		res.writeHead(404).end();
		return;
	}
	let request = {};
	try {
		request = JSON.parse(body || "{}");
	} catch {}
	const messages = request.messages ?? [];
	const users = messages.filter((m) => m.role === "user").map((m) => textOf(m.content));
	const text = users[users.length - 1] ?? "";
	res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
	let aborted = false;
	let finished = false;
	res.on("close", () => {
		if (!finished) aborted = true;
	});
	const chunk = (delta, finish = null) =>
		res.write(`data: ${JSON.stringify({ id: "c1", object: "chat.completion.chunk", created: 0, model: request.model ?? "fake-model", choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`);
	const stream = async (content, delayMs, size) => {
		chunk({ role: "assistant", content: "" });
		for (let i = 0; i < content.length; i += size) {
			if (aborted) return;
			chunk({ content: content.slice(i, i + size) });
			await sleep(delayMs);
		}
		chunk({}, "stop");
		finished = true;
		res.write(`data: ${JSON.stringify({ id: "c1", object: "chat.completion.chunk", created: 0, model: "fake-model", choices: [], usage: { prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 } })}\n\n`);
		res.write("data: [DONE]\n\n");
		res.end();
	};
	const lower = text.toLowerCase();
	if (lower.includes("perf-long")) {
		log({ event: "long", lastUser: text });
		await stream(longReply(), 8, 24);
	} else if (lower.includes("perf-hold")) {
		log({ event: "hold", lastUser: text });
		await sleep(20_000);
		await stream("Held reply done.", 20, 8);
	} else {
		log({ event: "reply", lastUser: text });
		await stream("Hello from the **perf** fake model.", 10, 8);
	}
}).listen(port, "127.0.0.1", () => console.log(`fake model on ${port}`));
