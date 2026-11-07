// fake-model-rows.mjs: fake-model-todo.mjs plus flows that exercise the transcript's tool rows (manual checks and
// screenshots, not used by the area scripts):
//
// - e2e-rows-bash: bash with ANSI colours (SGR) and a failing exit, then a long coloured output.
// - e2e-rows-edit: writes src/parse.ts, then a multi-hunk edit and a big block edit (diff numbering, word highlights).
// - e2e-rows-read: reads with a line range, grep, find, ls, and an ls that fails on a missing folder.
// - e2e-rows-image: writes a PNG with bash and reads it back (an image in a tool result).
// - e2e-rows-todo: seven todo updates in a row (one grouped row).
// - e2e-rows-heavy: a long mixed turn (bash, edits, reads) for scroll and streaming jank measurements.
// Each flow's first call streams a short reasoning block first ("Thought for Ns").
import { appendFileSync } from "node:fs";
import { createServer } from "node:http";
import { deflateSync } from "node:zlib";

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

const todo = (args) => ["todo", args];

// A 48x48 PNG (a diagonal gradient), built at start so the flow needs no binary file.
function png() {
	const w = 48;
	const h = 48;
	const raw = Buffer.alloc((w * 3 + 1) * h);
	for (let y = 0; y < h; y++) {
		raw[y * (w * 3 + 1)] = 0;
		for (let x = 0; x < w; x++) {
			const o = y * (w * 3 + 1) + 1 + x * 3;
			raw[o] = 40 + x * 4;
			raw[o + 1] = 80 + y * 3;
			raw[o + 2] = 200 - x * 2;
		}
	}
	const crcTable = Array.from({ length: 256 }, (_, n) => {
		let c = n;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		return c >>> 0;
	});
	const crc = (buf) => {
		let c = 0xffffffff;
		for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
		return (c ^ 0xffffffff) >>> 0;
	};
	const chunk = (type, data) => {
		const len = Buffer.alloc(4);
		len.writeUInt32BE(data.length);
		const td = Buffer.concat([Buffer.from(type), data]);
		const c = Buffer.alloc(4);
		c.writeUInt32BE(crc(td));
		return Buffer.concat([len, td, c]);
	};
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 2;
	return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]).toString("base64");
}

const parseLines = Array.from({ length: 80 }, (_, i) => {
	const n = i + 1;
	if (n === 1) return 'import { Token, tokenize } from "./tokenize";';
	if (n === 3) return "export const MAX_DEPTH = 32;";
	if (n % 10 === 5) return `export function rule${n}(tokens: Token[], depth = 0): number {`;
	if (n % 10 === 6) return `\tif (depth > MAX_DEPTH) throw new Error("too deep: " + depth);`;
	if (n % 10 === 7) return `\treturn tokens.filter((t) => t.kind === "word").length + ${n};`;
	if (n % 10 === 8) return "}";
	return n % 10 === 0 ? `// section ${n / 10}` : "";
});
const parseTs = `${parseLines.join("\n")}\n`;
const bigOld = parseLines.slice(44, 58).join("\n");
const bigNew = [
	"export function rule45(tokens: Token[], depth = 0): number {",
	"\tif (depth >= MAX_DEPTH) {",
	'\t\tthrow new RangeError(`nesting deeper than ${MAX_DEPTH}`);',
	"\t}",
	'\tconst words = tokens.filter((t) => t.kind === "word");',
	"\treturn words.length + 45;",
	"}",
	"",
	"/** Counts nested groups without recursion. */",
	"export function depthOf(tokens: Token[]): number {",
	"\tlet depth = 0;",
	"\tlet max = 0;",
	"\tfor (const t of tokens) {",
	'\t\tif (t.kind === "open") max = Math.max(max, ++depth);',
	'\t\telse if (t.kind === "close") depth--;',
	"\t}",
	"\treturn max;",
	"}",
].join("\n");

const ANSI_TEST =
	"printf '\\033[1m> vitest run\\033[0m\\n\\n \\033[32m✓\\033[0m src/tokenize.test.ts \\033[2m(12 tests)\\033[0m \\033[33m48ms\\033[0m\\n \\033[32m✓\\033[0m src/lexer.test.ts \\033[2m(31 tests)\\033[0m \\033[33m112ms\\033[0m\\n \\033[31m✗\\033[0m src/parse.test.ts > depth guard\\n   \\033[31mAssertionError: expected 65 to be less than 64\\033[0m\\n   \\033[90m at src/parse.test.ts:42:18\\033[0m\\n\\n \\033[1;31mTest Files  1 failed\\033[0m | \\033[1;32m2 passed\\033[0m (3)\\n \\033[1mTests\\033[0m  \\033[31m1 failed\\033[0m | \\033[32m43 passed\\033[0m\\n'; echo 'npm ERR! Test failed.' >&2; exit 1";
const ANSI_LONG = "for i in $(seq 1 140); do printf '\\033[36m[%03d]\\033[0m compiling module_%d \\033[38;5;214m%dms\\033[0m \\033[32mok\\033[0m\\n' $i $i $((i * 7 % 300)); done; printf '\\033[1;32mBuild succeeded\\033[0m in 4.2s\\n'";

const heavy = [];
for (let k = 0; k < 12; k++) {
	heavy.push(["bash", { command: ANSI_LONG.replace("140", String(30 + k)) }]);
	heavy.push(["read", { path: "src/parse.ts", offset: 1 + k * 5, limit: 30 }]);
	heavy.push(["edit", { path: "src/parse.ts", edits: [{ oldText: `// section ${(k % 7) + 1}`, newText: `// section ${(k % 7) + 1} (pass ${k})\n// checked ${k}` }] }]);
	heavy.push(["bash", { command: `grep -n "rule" src/parse.ts | head -${10 + k}` }]);
}

const FLOWS = {
	"e2e-rows-bash": [
		["bash", { command: ANSI_TEST }],
		["bash", { command: ANSI_LONG }],
	],
	"e2e-rows-edit": [
		["write", { path: "src/parse.ts", content: parseTs }],
		[
			"edit",
			{
				path: "src/parse.ts",
				edits: [
					{ oldText: "export const MAX_DEPTH = 32;", newText: "export const MAX_DEPTH = 64;\nexport const MAX_TOKENS = 10_000;" },
					{ oldText: parseLines.slice(14, 17).join("\n"), newText: 'export function rule15(tokens: Token[], depth = 0): number {\n\tif (depth > MAX_DEPTH) throw new RangeError("too deep");' },
					{ oldText: "// section 7", newText: "// section 7: groups" },
				],
			},
		],
		["edit", { path: "src/parse.ts", edits: [{ oldText: bigOld, newText: bigNew }] }],
	],
	"e2e-rows-read": [
		["write", { path: "src/parse.ts", content: parseTs }],
		["read", { path: "src/parse.ts", offset: 10, limit: 40 }],
		["grep", { pattern: "MAX_DEPTH", path: "src" }],
		["find", { pattern: "*.ts" }],
		["ls", { path: "." }],
		["ls", { path: "missing-folder" }],
		["bash", { command: 'grep -rn "rule" src | head -20' }],
		["read", { path: "src/parse.ts" }],
	],
	"e2e-rows-image": [
		["bash", { command: `mkdir -p shots && echo '${png()}' | base64 -d > shots/gradient.png && ls -la shots` }],
		["read", { path: "shots/gradient.png" }],
	],
	"e2e-rows-todo": [
		todo({ action: "create", subject: "Write the parser", activeForm: "Writing the parser" }),
		todo({ action: "create", subject: "Add unit tests for the parser" }),
		todo({ action: "create", subject: "Update the README" }),
		todo({ action: "update", id: 1, status: "in_progress" }),
		todo({ action: "update", id: 1, status: "completed" }),
		todo({ action: "update", id: 2, status: "in_progress" }),
		todo({ action: "update", id: 2, status: "completed" }),
		["bash", { command: "echo tests written" }],
	],
	"e2e-rows-heavy": [["write", { path: "src/parse.ts", content: parseTs }], ...heavy],
};
const FLOW_END = {
	"e2e-rows-bash": "One test fails: the depth guard allows **65** levels. The build itself is fine.",
	"e2e-rows-edit": "Raised `MAX_DEPTH` to 64, added `MAX_TOKENS` and replaced the recursive depth check with `depthOf`.",
	"e2e-rows-read": "The guard lives in `src/parse.ts`; nothing else references `MAX_DEPTH`.",
	"e2e-rows-image": "Here is the gradient the script rendered.",
	"e2e-rows-todo": "All tasks are done.",
	"e2e-rows-heavy": "Done: twelve passes over the parser, each built, read, edited and searched.\n\n```ts\nexport const MAX_DEPTH = 64;\n```\n",
};
const THINK = "The user wants me to check the parser. First I will look at how the depth guard is set up, then run the tests and read the failing case. If the limit is off by one, the fix is a one-character change plus a test.";

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
	// The todo extension's reminder arrives as a user message: it is not the user's prompt.
	const isReminder = (m) => m.role === "user" && /system-reminder|open tasks/i.test(textOf(m.content));
	const users = messages.filter((m) => m.role === "user" && !isReminder(m)).map((m) => textOf(m.content));
	const text = users[users.length - 1] ?? "";
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

	const anyCall = async (prefix, name, args, think = "") => {
		if (think) {
			send({ role: "assistant", content: "" });
			for (const word of think.split(/(?<= )/)) {
				send({ reasoning_content: word });
				await sleep(60);
			}
		}
		send({ role: "assistant", content: prefix });
		send({ tool_calls: [{ index: 0, id: `call_${Date.now()}`, type: "function", function: { name, arguments: "" } }] });
		const text = JSON.stringify(args);
		for (let i = 0; i < text.length; i += 24) {
			send({ tool_calls: [{ index: 0, function: { arguments: text.slice(i, i + 24) } }] });
			await sleep(15);
		}
		send({}, "tool_calls");
		end();
	};
	// Scripted multi-step flows: the step is the number of tool results since the last user message.
	let lastUser = -1;
	messages.forEach((m, i) => { if (m.role === "user" && !isReminder(m)) lastUser = i; });
	const step = messages.slice(lastUser + 1).filter((m) => m.role === "tool").length;
	const flow = Object.entries(FLOWS).find(([k]) => text.toLowerCase().includes(k));
	if (flow) {
		const steps = flow[1];
		log({ ...entry, event: "flow", flow: flow[0], step });
		if (step < steps.length) {
			const [name, args] = steps[step];
			await anyCall(step === 0 ? "On it. " : "", name, args, step === 0 ? THINK : "");
		} else {
			await stream(FLOW_END[flow[0]] ?? "Done.", 30);
		}
		return;
	}
	if (/e2e-sub-task/i.test(text)) {
		log({ ...entry, event: "subagent" });
		await stream("The parser lives in src/parse.ts and its tests in test/parse.test.ts. Nothing else touches the grammar, so the change stays local. " + "Checked the build too. ".repeat(4), 700);
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
