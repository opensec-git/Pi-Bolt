// features2 copy of fake-model-scripted.mjs with "e2e-overload" (HTTP 503).
// Fake OpenAI-compatible chat completions server for the Android on-device E2E suite.
// The basic fake model (default, "tool" and "slow" replies) plus a scripted mode:
//
// - A prompt containing a keyword from SCRIPTS gets one bash tool call with that command. Once Pi sends the tool
//   result back, the reply is "E2E-RESULT <keyword>" followed by the output in a ```text fence, so the UI shows it.
// - "e2e-run: <command>" (rest of the line) runs any command the same way.
// Keywords match case-insensitively (the keyboard may capitalise a prompt's first letter).
// - A last user message starting with "STEER-" (a steer delivered after a tool) is answered "Steer received: <text>".
// - GET /health answers "ok" (readiness probe); everything else that is not POST is 404.
//
// Usage: node fake-model-scripted.mjs <port> [request-log.jsonl]
// Each request appends one JSON line to the log: the last role, the last user text, every user text, the script used,
// the Authorization header (dummy keys only: never point real keys at this server) and whether the client aborted.
import { appendFileSync } from "node:fs";
import { createServer } from "node:http";

const port = Number(process.argv[2] ?? 18765);
const logFile = process.argv[3];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const SCRIPTS = {
	"e2e-ls": "ls -la",
	"e2e-write": "echo hi > a.txt && cat a.txt",
	"e2e-git": "git init -q && git add -A && git -c user.name=e2e -c user.email=e2e@example.invalid commit -qm x && git log --oneline",
	"e2e-rg": "rg hi",
	"e2e-fd": "fd a",
	"e2e-steer": "sleep 25; echo steer-tool-done",
	"e2e-long-tool": "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do echo tick $i; sleep 1; done",
};

const textOf = (content) => {
	if (typeof content === "string") return content;
	if (Array.isArray(content)) return content.map((c) => (typeof c === "string" ? c : (c?.text ?? ""))).join("");
	return JSON.stringify(content ?? "");
};

function scriptFor(text) {
	const run = /e2e-run:\s*(.+)$/im.exec(text);
	if (run) return { name: "e2e-run", command: run[1].trim() };
	// Case-insensitive: the keyboard may capitalise the first letter of a prompt.
	const lower = text.toLowerCase();
	for (const [name, command] of Object.entries(SCRIPTS)) if (lower.includes(name)) return { name, command };
	return null;
}

function log(entry) {
	if (!logFile) return;
	try {
		appendFileSync(logFile, `${JSON.stringify({ at: new Date().toISOString(), ...entry })}\n`);
	} catch {}
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
	const last = messages[messages.length - 1] ?? {};
	const users = messages.filter((m) => m.role === "user").map((m) => textOf(m.content));
	const text = users[users.length - 1] ?? "";
	const entry = { lastRole: last.role, lastUser: text, users, model: request.model, auth: req.headers.authorization ?? null };

	// features2: a provider error Pi retries ("e2e-overload"), for the retry banner's "Stop retrying".
	if (/e2e-overload/i.test(text)) {
		log({ ...entry, event: "overloaded" });
		res.writeHead(503, { "content-type": "application/json" }).end(JSON.stringify({ error: { message: "overloaded (e2e)", type: "server_error" } }));
		return;
	}
	res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
	let aborted = false;
	let finished = false;
	res.on("close", () => {
		if (!finished) {
			aborted = true;
			log({ ...entry, event: "aborted" });
		}
	});
	const send = (delta, finish = null) =>
		res.write(`data: ${JSON.stringify({ id: "c1", object: "chat.completion.chunk", created: 0, model: request.model ?? "fake-model", choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`);
	const end = () => {
		finished = true;
		res.write(`data: ${JSON.stringify({ id: "c1", object: "chat.completion.chunk", created: 0, model: request.model ?? "fake-model", choices: [], usage: { prompt_tokens: 10, completion_tokens: 20, total_tokens: 30 } })}\n\n`);
		res.write("data: [DONE]\n\n");
		res.end();
	};
	const stream = async (content, delayMs) => {
		send({ role: "assistant", content: "" });
		for (const word of content.split(/(?<= )/)) {
			if (aborted) return;
			send({ content: word });
			await sleep(delayMs);
		}
		send({}, "stop");
		end();
	};
	const toolCall = async (prefix, command) => {
		send({ role: "assistant", content: prefix });
		send({ tool_calls: [{ index: 0, id: `call_${Date.now()}`, type: "function", function: { name: "bash", arguments: "" } }] });
		const args = JSON.stringify({ command });
		for (let i = 0; i < args.length; i += 12) {
			send({ tool_calls: [{ index: 0, function: { arguments: args.slice(i, i + 12) } }] });
			await sleep(20);
		}
		send({}, "tool_calls");
		end();
	};

	if (last.role === "tool") {
		// The command this result belongs to: the last assistant tool call.
		const call = [...messages].reverse().find((m) => m.role === "assistant" && m.tool_calls?.length)?.tool_calls?.[0];
		let command = "";
		try {
			command = JSON.parse(call?.function?.arguments ?? "{}").command ?? "";
		} catch {}
		const script = Object.entries(SCRIPTS).find(([, c]) => c === command)?.[0] ?? (command ? "e2e-run" : "tool");
		const output = textOf(last.content).trim().slice(0, 1500);
		log({ ...entry, event: "tool-result", script, command, output });
		if (script === "tool") {
			await stream("The command finished. **Done.**", 30);
			return;
		}
		await stream(`E2E-RESULT ${script} done.\n\n\`\`\`text\n${output || "(no output)"}\n\`\`\`\n`, 5);
		return;
	}
	if (last.role === "user" && /^steer-/i.test(text.trim())) {
		log({ ...entry, event: "steer", toolOutputs: messages.filter((m) => m.role === "tool").map((m) => textOf(m.content).slice(0, 200)) });
		await stream(`Steer received: ${text.trim()}`, 20);
		return;
	}
	const script = scriptFor(text);
	if (script) {
		log({ ...entry, event: "tool-call", script: script.name, command: script.command });
		await toolCall(`Running ${script.name}. `, script.command);
		return;
	}
	if (/\btool\b/i.test(text)) {
		log({ ...entry, event: "tool-call", script: "tool" });
		await toolCall("Running a command. ", "for i in 1 2 3 4 5; do echo line $i; sleep 1; done");
		return;
	}
	if (/\bslow\b/i.test(text)) {
		log({ ...entry, event: "slow" });
		await stream(Array.from({ length: 200 }, (_, i) => `word${i} `).join(""), 300);
		return;
	}
	log({ ...entry, event: "reply" });
	await stream("Hello from the **fake model**. Here is some code:\n\n```ts\nconst answer = 42;\nconsole.log(answer);\n```\n\n- one\n- two\n", 40);
}).listen(port, "127.0.0.1", () => console.log(`fake model on ${port}`));
