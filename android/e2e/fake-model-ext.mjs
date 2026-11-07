// fake-model-ext.mjs: fake-model-scripted.mjs plus extension tool scripts (extensions.sh): e2e-todo-create,
// e2e-todo-update, e2e-todo-done, e2e-todo-list (`todo`), e2e-agent (foreground `Agent`), e2e-bgagent (slow background
// `Agent`), e2e-web-search, e2e-web-multi, e2e-web-fetch (pi-web-access; real network), e2e-ask (`ask_user_question`, a
// single and a multi-select question). See TOOL_SCRIPTS. For frames.sh: smooth-md (a long markdown reply in 16-character
// chunks every 8 ms) and smooth-agents (three foreground `Agent` calls at once, each subagent writing for 8 s).
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

// Extension tools (opensec-pi-todo, opensec-pi-subagents): a keyword gets one call of that tool with these arguments.
const TOOL_SCRIPTS = {
	"e2e-todo-create": ["todo", { action: "create", subject: "E2E task one", description: "made by the fake model" }],
	"e2e-todo-update": ["todo", { action: "update", id: 1, status: "in_progress" }],
	"e2e-todo-done": ["todo", { action: "update", id: 1, status: "completed" }],
	"e2e-todo-list": ["todo", { action: "list" }],
	"e2e-bgagent": [
		"Agent",
		{ subagent_type: "general-purpose", description: "E2E slow background subagent", prompt: "e2e-slowtask: count slowly", run_in_background: true },
	],
	"e2e-web-search": ["web_search", { query: "Pi coding agent terminal" }],
	"e2e-web-multi": ["web_search", { queries: ["kotlin coroutines flow", "jetpack compose animation"] }],
	"e2e-web-fetch": ["fetch_content", { urls: ["https://kotlinlang.org/docs/coroutines-flow.html", "https://nonexistent.invalid/x"] }],
	"e2e-ask": [
		"ask_user_question",
		{
			questions: [
				{
					question: "Which database should we use?",
					header: "Database",
					options: [
						{ label: "Postgres", description: "Relational, mature" },
						{ label: "SQLite", description: "Embedded, simple" },
					],
				},
				{
					question: "Which features do you want?",
					header: "Features",
					multiSelect: true,
					options: [
						{ label: "Auth", description: "Sign in" },
						{ label: "Search", description: "Full text" },
						{ label: "Export", description: "CSV" },
					],
				},
			],
		},
	],
	"e2e-agent": [
		"Agent",
		{ subagent_type: "general-purpose", description: "E2E subagent", prompt: "e2e-subtask: reply with a greeting", run_in_background: false },
	],
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
	// The todo extension may add a "<system-reminder>" user message right after the prompt: match the prompt.
	const isReminder = (t) => t.trim().startsWith("<system-reminder>");
	const prompts = users.filter((t) => !isReminder(t));
	const text = prompts[prompts.length - 1] ?? "";
	const entry = { lastRole: last.role, lastUser: text, users, model: request.model, auth: req.headers.authorization ?? null };

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

	const toolScript = Object.entries(TOOL_SCRIPTS).find(([k]) => text.toLowerCase().includes(k));
	if (last.role === "tool" && toolScript) {
		const call = [...messages].reverse().find((m) => m.role === "assistant" && m.tool_calls?.length)?.tool_calls?.[0];
		const output = textOf(last.content).trim().slice(0, 3000);
		log({ ...entry, event: "tool-result", script: toolScript[0], tool: call?.function?.name, output });
		await stream(`E2E-RESULT ${toolScript[0]} done.\n\n\`\`\`text\n${output || "(no output)"}\n\`\`\`\n`, 5);
		return;
	}
	if (last.role === "user" && /e2e-slowtask/i.test(text)) {
		log({ ...entry, event: "slow-subagent-request" });
		await stream(Array.from({ length: 120 }, (_, i) => `count${i} `).join(""), 500);
		log({ ...entry, event: aborted ? "slow-subagent-aborted" : "slow-subagent-finished" });
		return;
	}
	if (last.role === "user" && /e2e-subtask/i.test(text)) {
		log({ ...entry, event: "subagent-request", tools: (request.tools ?? []).map((t) => t.function?.name) });
		await stream("SUBAGENT-OK hello from the subagent.", 10);
		return;
	}
	if (last.role === "user" && /smooth-md/i.test(text)) {
		log({ ...entry, event: "smooth-md" });
		send({ role: "assistant", content: "" });
		const content = longReply();
		for (let i = 0; i < content.length; i += 16) {
			if (aborted) return;
			send({ content: content.slice(i, i + 16) });
			await sleep(8);
		}
		send({}, "stop");
		end();
		return;
	}
	if (last.role === "user" && /smooth-subwork/i.test(text)) {
		log({ ...entry, event: "smooth-subwork" });
		await stream(Array.from({ length: 80 }, (_, i) => `step${i} `).join(""), 100);
		return;
	}
	if (last.role === "user" && /smooth-agents/i.test(text)) {
		log({ ...entry, event: "smooth-agents" });
		send({ role: "assistant", content: "Starting three agents. " });
		for (let k = 0; k < 3; k++)
			send({ tool_calls: [{ index: k, id: `call_${Date.now()}_${k}`, type: "function", function: { name: "Agent", arguments: JSON.stringify({ subagent_type: "general-purpose", description: `Smooth agent ${k + 1}`, prompt: `smooth-subwork ${k}`, run_in_background: false }) } }] });
		send({}, "tool_calls");
		end();
		return;
	}
	if (last.role === "tool" && /smooth-agents/i.test(text)) {
		await stream("All three agents finished. **Done.**", 10);
		return;
	}
	// A reminder on its own (not right after a prompt) gets a short reply.
	if (last.role === "user" && isReminder(textOf(last.content)) && messages[messages.length - 2]?.role !== "user") {
		log({ ...entry, event: "reminder" });
		await stream("Noted.", 5);
		return;
	}
	if (last.role === "user" && toolScript) {
		const [name, args] = toolScript[1];
		log({ ...entry, event: "tool-call", script: toolScript[0], tool: name, tools: (request.tools ?? []).map((t) => t.function?.name) });
		send({ role: "assistant", content: `Calling ${name}. ` });
		send({ tool_calls: [{ index: 0, id: `call_${Date.now()}`, type: "function", function: { name, arguments: JSON.stringify(args) } }] });
		send({}, "tool_calls");
		end();
		return;
	}
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
