#!/usr/bin/env python3
"""A fake OpenAI-compatible model for stress tests (stress.py). The user's prompt names a scenario, `SCENARIO <name> CWD <dir>`:

  heavy   about twenty turns of large and awkward tool work: megabytes of command output, a 3 MB line, a 1 MB file written
          through a tool call, reads, edits and grep in it, 3,000 files to find and grep through, Unicode and binary files,
          three tool calls at once, failing tools, a command that times out. The final answer is a digest of every tool result
          (with the working directory replaced), so a wrong result anywhere changes it.
  light   two small tool calls and an answer: for long sessions of many prompts
  stream  no tools: a 2 MB answer in some 20,000 small events
  bigargs a 200 KB file written through a tool call whose arguments arrive a few bytes at a time
  drop    half an answer, then the connection is cut
  http500 / http429 / garbage   an error status, or a stream that is not JSON
  sleep   a command that runs for a minute (for interrupting Pi while a tool runs)

Usage: fake_model_stress.py PORT"""

import hashlib
import json
import os
import re
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIG_FILE = "".join(f"line {i:06d} {'abcdefghij' * 9}\n" for i in range(10000))  # about 1 MB
UNICODE = "Grüße 😀 🇮🇳 日本語テキスト العربية עברית é zero​width  sep\n" * 50

MARKDOWN = """## Section {n}: what the function does

The `parse()` function reads **one token at a time** and keeps a *small* stack; see [the grammar](https://example.com/g/{n}).
It handles these cases:

- plain text, with `inline code` and a long line that has to be wrapped by the terminal because it does not fit in its width at all
- nested lists:
  1. first, with a number {n}
  2. second, with ~~strikethrough~~ and unicode: naïve café 日本語 😀

```ts
export function parse{n}(input: string): Node[] {
	const out: Node[] = [];
	for (let i = 0; i < input.length; i++) {
		if (input.charCodeAt(i) === 0x5b) out.push({ kind: "open", at: i });
	}
	return out;
}
```

| column | value | note |
|---|---|---|
| a{n} | 1 | first |
| b{n} | 22 | second, longer |

> A quote that closes the section, number {n}.

"""

HEAVY = [
    [("bash", {"command": "seq 1 300000"})],
    [("bash", {"command": "head -c 3000000 /dev/zero | tr '\\0' x; echo"})],
    [("bash", {"command": "for i in $(seq 1 2000); do echo \"stderr line $i\" >&2; done; exit 3"})],
    [("write", {"path": "big/data.txt", "content": BIG_FILE})],
    [("read", {"path": "big/data.txt"})],
    [("read", {"path": "big/data.txt", "offset": 9990, "limit": 20})],
    [("edit", {"path": "big/data.txt", "edits": [{"oldText": "line 005000 ", "newText": "LINE FIVE THOUSAND "}]})],
    [("grep", {"pattern": "FIVE THOUSAND|line 0099[0-9]{2}", "path": "big", "limit": 50})],
    [("bash", {"command": "mkdir -p tree && cd tree && for d in $(seq 1 30); do mkdir -p d$d; for f in $(seq 1 100); do echo \"needle $d $f\" > d$d/f$f.txt; done; done; echo made"})],
    [("find", {"pattern": "**/*.txt", "path": "tree"})],
    [("grep", {"pattern": "needle 17 ", "path": "tree", "limit": 500})],
    [("ls", {"path": "tree/d1"})],
    [("write", {"path": "uni/text.txt", "content": UNICODE})],
    [("read", {"path": "uni/text.txt"}), ("grep", {"pattern": "日本語", "path": "uni"}), ("bash", {"command": "wc -c uni/text.txt"})],
    [("bash", {"command": "printf '%b' $(for i in $(seq 0 255); do printf '\\\\x%02x' $i; done) > bin.dat; wc -c < bin.dat"})],
    [("read", {"path": "bin.dat"})],
    [("read", {"path": "does/not/exist.txt"})],
    [("edit", {"path": "uni/text.txt", "edits": [{"oldText": "not in the file", "newText": "x"}]})],
    [("no_such_tool", {"x": 1})],
    [("bash", {"command": "sleep 20", "timeout": 1})],
]
TRAIN_SOURCE = """// A file written, read and edited while a training profile is recorded.
import { readFileSync } from "node:fs";

export interface Options { path: string; limit?: number }

export function count(options: Options): number {
	const text = readFileSync(options.path, "utf-8");
	let lines = 0;
	for (const line of text.split("\\n")) {
		if (line.trim() !== "" && lines < (options.limit ?? 1000)) lines++;
	}
	return lines; // counted
}
"""
TRAIN = [
    [("write", {"path": "train/count.ts", "content": TRAIN_SOURCE})],
    [("read", {"path": "train/count.ts"})],
    [("edit", {"path": "train/count.ts", "edits": [{"oldText": "let lines = 0;", "newText": "let lines = 0;\n\tlet blank = 0;"},
                                                   {"oldText": "return lines; // counted", "newText": "return lines - blank;"}]})],
    [("write", {"path": "train/notes.md", "content": "# Notes\n\n- one\n- two\n\n```sh\nls -la\n```\n"}),
     ("write", {"path": "train/data.json", "content": json.dumps({"a": [1, 2, {"b": None}], "c": "text"}, indent=2)}),
     ("write", {"path": "train/run.py", "content": "import sys\n\ndef main() -> int:\n    print('ok', file=sys.stderr)\n    return 0\n"})],
    [("read", {"path": "train/notes.md"}), ("read", {"path": "train/data.json"}), ("read", {"path": "train/run.py"})],
    [("bash", {"command": "ls -la train && wc -l train/* && echo 'to stderr' >&2 && printf '\\033[31mred\\033[0m\\n' && exit 2"})],
    [("grep", {"pattern": "lines|blank", "path": "train", "limit": 20}), ("find", {"pattern": "**/*.ts", "path": "."}), ("ls", {"path": "train"})],
    [("edit", {"path": "train/count.ts", "edits": [{"oldText": "not in the file", "newText": "x"}]})],
    [("read", {"path": "train/missing.txt"})],
]
LIGHT = [
    [("read", {"path": "fixture/README.md"})],
    [("bash", {"command": "echo light; ls fixture | wc -l"})],
]


def chunk(delta=None, finish=None):
    return b"data: " + json.dumps({"id": "fake", "object": "chat.completion.chunk", "model": "fake-model",
                                   "choices": [{"index": 0, "delta": delta or {}, "finish_reason": finish}]}).encode() + b"\n\n"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = json.dumps({"object": "list", "data": [{"id": "fake-model", "object": "model"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def stream(self, pieces, cut_after=None):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for n, piece in enumerate(pieces):
            if PACE:
                self.wfile.flush()
                time.sleep(PACE)
            if cut_after is not None and n == cut_after:
                self.wfile.flush()
                self.connection.shutdown(socket.SHUT_RDWR)
                self.close_connection = True
                return
            self.wfile.write(f"{len(piece):x}\r\n".encode() + piece + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")

    def do_POST(self):
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        path = self.path.split("?", 1)[0]
        api = "anthropic" if path.endswith("/messages") else "responses" if path.endswith("/responses") else "completions"
        scenario, cwd, turn, results, prompts = read_conversation(api, req)

        if scenario in ("http500", "http429"):
            body = json.dumps({"error": {"message": "fake failure", "type": "server_error"}}).encode()
            self.send_response(500 if scenario == "http500" else 429)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            if scenario == "http429":
                self.send_header("Retry-After", "1")
            self.end_headers()
            self.wfile.write(body)
            return
        if scenario == "garbage":
            return self.stream([b"data: {not json\n\n", b"data: [[[\n\n", b"data: [DONE]\n\n"])

        encode = {"completions": encode_completions, "anthropic": encode_anthropic, "responses": encode_responses}[api]
        intro = f"Turn {turn + 1}. "
        steps = {"heavy": HEAVY, "light": LIGHT, "train": TRAIN, "seq": [[("bash", {"command": "seq 1 300000"})]], "sleep": [[("bash", {"command": "sleep 60; echo slept"})]],
                 "bigargs": [[("write", {"path": "args/big.txt", "content": "0123456789abcdef" * 12800})], [("bash", {"command": "sha256sum args/big.txt"})]]}.get(scenario, [])
        if scenario.startswith("write:"):
            # A file of N KB of source code written through a tool call whose arguments stream in 16 characters at a time, about
            # what a model sends per token (bench/large_write.py).
            size = int(scenario[6:]) * 1024
            lines = []
            while sum(len(line) + 1 for line in lines) < size:
                n = len(lines)
                lines.append(f"export function step{n}(input: number[]): number {{ return input.reduce((a, b) => a + b * {n % 97}, {n}); }}")
            source = "\n".join(lines)[:size]
            steps = [[("write", {"path": "big/source.ts", "content": source})], [("bash", {"command": "wc -c < big/source.ts"})]]
        if turn < len(steps):
            # The arguments in pieces, as models send them: a few bytes at a time for `bigargs`.
            calls = steps[turn]
            step = 7 if scenario == "bigargs" else 16 if scenario.startswith("write:") else 4096 if max(len(json.dumps(a)) for _, a in calls) > 65536 else 97
            return self.stream(encode(turn, [intro], calls, step))

        if scenario.startswith("mdfile:") and turn == 0:
            # The Markdown file named, a few characters at a time (scripts/lib/train_session.py).
            with open(scenario[7:], encoding="utf-8") as f:
                body = f.read()
            return self.stream(encode(turn, [intro] + [body[i:i + 24] for i in range(0, len(body), 24)] + [f"\n\nDone: mdfile, answer {prompts}."], None, 0))
        if scenario.startswith("md:"):
            # A Markdown answer of about N characters, a few words at a time: what the TUI lays out again as it grows.
            size = int(scenario[3:])
            body = ""
            n = 0
            while len(body) < size:
                n += 1
                body += MARKDOWN.replace("{n}", str(n))
            body = body[:size]
            texts = [intro] + [body[i:i + 24] for i in range(0, len(body), 24)]
            return self.stream(encode(turn, texts + [f"\n\nDigest: {hashlib.sha256(body.encode()).hexdigest()[:16]}. Done: md, answer {prompts}."], None, 0))
        if scenario in ("stream", "drop"):
            body = "".join(f"Paragraph {i}: {'lorem ipsum dolor sit amet ' * 3}\n" for i in range(25000))[:2_000_000]
            texts = [intro] + [body[i:i + 100] for i in range(0, len(body), 100)]
            pieces = encode(turn, texts + [f"\nDigest: {hashlib.sha256(body.encode()).hexdigest()[:16]}. Done: {scenario}."], None, 0)
            return self.stream(pieces, cut_after=len(pieces) // 2 if scenario == "drop" else None)

        digest = hashlib.sha256("\x00".join(results).encode()).hexdigest()[:16]
        if os.environ.get("STRESS_DUMP_DIR"):
            with open(os.path.join(os.environ["STRESS_DUMP_DIR"], f"{scenario}-{digest}.json"), "w") as f:
                json.dump(results, f, indent=1, ensure_ascii=False)
        # And what Pi sent: system prompt, tool definitions, the conversation (without what changes from run to run).
        sent = json.dumps(req, sort_keys=True, ensure_ascii=False)
        if cwd:
            for form in cwd_forms(cwd):
                sent = sent.replace(form, "<CWD>")
        sent = re.sub(r"(?:/tmp|/private/var/folders|/var/folders)/[^\s\"')\]\\]+", "<TMP>", sent)  # (macOS: $TMPDIR)
        sent = re.sub(r"/[^\s\"']+/(README\.md|docs|examples|CHANGELOG\.md)\b", r"<PI>/\1", sent)  # Where Pi is installed.
        sent = re.sub(r"\d{4}-\d\d-\d\d[T ]?[\d:.]*Z?|\b\d{1,2}:\d\d(:\d\d)?( ?[AP]M)?|\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,? [A-Z][a-z]+ \d{1,2},? \d{4}", "<DATE>", sent)
        sent = re.sub(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", "<ID>", sent)  # The session's.
        sent_digest = hashlib.sha256(sent.encode()).hexdigest()[:16]
        if os.environ.get("STRESS_DUMP_DIR"):
            with open(os.path.join(os.environ["STRESS_DUMP_DIR"], f"{scenario}-sent-{sent_digest}.json"), "w") as f:
                f.write(sent)
        sizes = sum(len(r) for r in results)
        self.stream(encode(turn, [intro, f"{len(results)} tool results, {sizes} characters, digest {digest}, sent {sent_digest}. Done: {scenario}."], None, 0))


def cwd_forms(cwd):
    """The ways a work folder appears in requests: as named, JSON-escaped, and on Windows with backslashes too."""
    if not cwd:
        return []
    forms = [cwd, cwd.replace("/", "\\")]
    forms += [json.dumps(form)[1:-1] for form in forms]
    return sorted(set(forms), key=len, reverse=True)


def read_conversation(api, req):
    """What a request says of the conversation: (scenario, cwd, turns of tool calls made since the prompt, their results, how
    many prompts there have been). A long answer ends with the number of its prompt, so that whoever waits for its end on a
    screen that still shows the answer before it can tell them apart."""
    scenario, cwd, turn, results, prompts = "light", "", 0, [], 0

    def prompt(text):
        nonlocal scenario, cwd, turn, results, prompts
        prompts += 1
        if "SCENARIO " in text:
            words = text[text.index("SCENARIO "):].replace('"', " ").replace("\\", " ").split()
            scenario = words[1]
            cwd = words[3] if len(words) > 3 and words[2] == "CWD" else ""
            turn, results = 0, []

    def result(content):
        text = content if isinstance(content, str) else json.dumps(content)
        # (On Windows the prompt names the folder with forward slashes and the tools answer with backslashes: both.)
        for form in cwd_forms(cwd):
            text = text.replace(form, "<CWD>")
        # Where Pi saves the whole of a truncated output: a new temporary name every time.
        results.append(re.sub(r"(?:/tmp|/private/var/folders|/var/folders)/[^\s\"')\]]+", "<TMP>", text))

    if api == "responses":
        calling = False
        for item in req.get("input", []) if isinstance(req.get("input"), list) else []:
            kind = item.get("type") or ("message" if "role" in item else "")
            if kind == "function_call":
                if not calling:
                    turn += 1
                calling = True
                continue
            calling = False
            if kind == "function_call_output":
                result(item.get("output"))
            elif item.get("role") == "user":
                content = item.get("content")
                prompt(content if isinstance(content, str) else json.dumps(content))
        return scenario, cwd, turn, results, prompts
    for m in req.get("messages", []):
        content = m.get("content")
        parts = content if isinstance(content, list) else []
        if m.get("role") == "tool":
            result(content)
        elif m.get("role") == "assistant":
            if m.get("tool_calls") or any(part.get("type") == "tool_use" for part in parts):
                turn += 1
        elif m.get("role") == "user":
            tool_results = [part for part in parts if part.get("type") == "tool_result"]
            for part in tool_results:
                result(part.get("content"))
            if not tool_results:
                prompt(content if isinstance(content, str) else json.dumps(content))
    return scenario, cwd, turn, results, prompts


def encode_completions(turn, texts, calls, step):
    """OpenAI chat completions: a text delta for each of `texts`, then the tool calls with their arguments `step` characters at a time."""
    pieces = [chunk({"role": "assistant", "content": ""})] + [chunk({"content": text}) for text in texts]
    for k, (name, args) in enumerate(calls or []):
        text = json.dumps(args)
        pieces.append(chunk({"tool_calls": [{"index": k, "id": f"call_{turn}_{k}", "type": "function", "function": {"name": name, "arguments": ""}}]}))
        pieces += [chunk({"tool_calls": [{"index": k, "function": {"arguments": text[i:i + step]}}]}) for i in range(0, len(text), step)]
    return pieces + [chunk(finish="tool_calls" if calls else "stop"), b"data: [DONE]\n\n"]


def encode_anthropic(turn, texts, calls, step):
    """Anthropic messages."""
    def event(kind, data):
        return f"event: {kind}\ndata: {json.dumps({'type': kind, **data})}\n\n".encode()

    usage = {"input_tokens": 10, "output_tokens": 1, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
    pieces = [event("message_start", {"message": {"id": f"msg_fake_{turn}", "type": "message", "role": "assistant", "model": "fake-model", "content": [],
                                                  "stop_reason": None, "stop_sequence": None, "usage": usage}}),
              event("content_block_start", {"index": 0, "content_block": {"type": "text", "text": ""}})]
    pieces += [event("content_block_delta", {"index": 0, "delta": {"type": "text_delta", "text": text}}) for text in texts]
    pieces.append(event("content_block_stop", {"index": 0}))
    for k, (name, args) in enumerate(calls or []):
        text = json.dumps(args)
        pieces.append(event("content_block_start", {"index": k + 1, "content_block": {"type": "tool_use", "id": f"toolu_{turn}_{k}", "name": name, "input": {}}}))
        pieces += [event("content_block_delta", {"index": k + 1, "delta": {"type": "input_json_delta", "partial_json": text[i:i + step]}}) for i in range(0, len(text), step)]
        pieces.append(event("content_block_stop", {"index": k + 1}))
    pieces.append(event("message_delta", {"delta": {"stop_reason": "tool_use" if calls else "end_turn", "stop_sequence": None}, "usage": {"output_tokens": 20}}))
    return pieces + [event("message_stop", {})]


def encode_responses(turn, texts, calls, step):
    """OpenAI responses."""
    sequence = 0

    def event(kind, data):
        nonlocal sequence
        sequence += 1
        return f"event: {kind}\ndata: {json.dumps({'type': kind, 'sequence_number': sequence, **data})}\n\n".encode()

    response = {"id": f"resp_fake_{turn}", "object": "response", "created_at": 0, "model": "fake-model", "status": "in_progress", "output": []}
    message_id = f"msg_fake_{turn}"
    text = "".join(texts)
    pieces = [event("response.created", {"response": response}),
              event("response.output_item.added", {"output_index": 0, "item": {"type": "message", "id": message_id, "role": "assistant", "status": "in_progress", "content": []}}),
              event("response.content_part.added", {"item_id": message_id, "output_index": 0, "content_index": 0, "part": {"type": "output_text", "text": "", "annotations": []}})]
    pieces += [event("response.output_text.delta", {"item_id": message_id, "output_index": 0, "content_index": 0, "delta": delta}) for delta in texts]
    message = {"type": "message", "id": message_id, "role": "assistant", "status": "completed", "content": [{"type": "output_text", "text": text, "annotations": []}]}
    pieces += [event("response.output_text.done", {"item_id": message_id, "output_index": 0, "content_index": 0, "text": text}),
               event("response.content_part.done", {"item_id": message_id, "output_index": 0, "content_index": 0, "part": message["content"][0]}),
               event("response.output_item.done", {"output_index": 0, "item": message})]
    output = [message]
    for k, (name, args) in enumerate(calls or []):
        arguments = json.dumps(args)
        item = {"type": "function_call", "id": f"fc_{turn}_{k}", "call_id": f"call_{turn}_{k}", "name": name, "arguments": "", "status": "in_progress"}
        pieces.append(event("response.output_item.added", {"output_index": k + 1, "item": item}))
        pieces += [event("response.function_call_arguments.delta", {"item_id": item["id"], "output_index": k + 1, "delta": arguments[i:i + step]}) for i in range(0, len(arguments), step)]
        done = {**item, "arguments": arguments, "status": "completed"}
        pieces += [event("response.function_call_arguments.done", {"item_id": item["id"], "output_index": k + 1, "arguments": arguments}),
                   event("response.output_item.done", {"output_index": k + 1, "item": done})]
        output.append(done)
    usage = {"input_tokens": 10, "output_tokens": 20, "total_tokens": 30, "input_tokens_details": {"cached_tokens": 0}, "output_tokens_details": {"reasoning_tokens": 0}}
    return pieces + [event("response.completed", {"response": {**response, "status": "completed", "output": output, "usage": usage}})]


PACE = 0.0

if __name__ == "__main__":
    if "--pace-ms" in sys.argv:
        PACE = float(sys.argv[sys.argv.index("--pace-ms") + 1]) / 1000
    server = ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 18082), Handler)
    server.daemon_threads = True
    server.request_queue_size = 256
    server.serve_forever()
