"""A fake OpenAI-style model for the real-extension measurements (bench/plugins_real/measure.py). No provider is called.

  python fake_model.py PORT [--tools-out FILE] [--pace-ms N]

The latest user message says what to do:
  CALL <tool> <json args> [; <tool> <json args> ...]   one tool call per turn, in order, then "Done: <first tool>."
  STREAM <n>                                          an answer of n words, streamed a word an event (with --pace-ms)
  anything else                                       "Done: <the message>."
--tools-out: the names of the tools the first request offered, one per line (which tools the extensions registered).
"""
import json
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])
TOOLS_OUT = sys.argv[sys.argv.index("--tools-out") + 1] if "--tools-out" in sys.argv else None
PACE = float(sys.argv[sys.argv.index("--pace-ms") + 1]) / 1000 if "--pace-ms" in sys.argv else 0.0
# --results-out FILE: the first 400 characters of each tool result the model is sent, one JSON string a line (did the action work?)
RESULTS_OUT = sys.argv[sys.argv.index("--results-out") + 1] if "--results-out" in sys.argv else None
WORDS = "the module wires session state into the renderer and streams tool results back through the agent loop".split()
wrote_tools = False


def chunk(delta=None, finish=None, usage=None):
    body = {"id": "chatcmpl-real", "object": "chat.completion.chunk", "created": 0, "model": "fake-model",
            "choices": [] if usage else [{"index": 0, "delta": delta or {}, "finish_reason": finish}]}
    if usage:
        body["usage"] = usage
    return f"data: {json.dumps(body)}\n\n".encode()


def text_of(message):
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def parse_calls(text):
    calls = []
    for part in text[len("CALL "):].split(" ; "):
        name, _, args = part.strip().partition(" ")
        calls.append((name, args.strip() or "{}"))
    return calls


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = json.dumps({"object": "list", "data": [{"id": "fake-model", "object": "model"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global wrote_tools
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if TOOLS_OUT and not wrote_tools:
            wrote_tools = True
            with open(TOOLS_OUT, "w") as f:
                for tool in req.get("tools", []):
                    f.write(tool.get("function", {}).get("name", "?") + "\n")
            with open(TOOLS_OUT + ".json", "w") as f:
                json.dump(req.get("tools", []), f, indent=1)
        messages = req.get("messages", [])
        if RESULTS_OUT and messages and messages[-1].get("role") == "tool":
            with open(RESULTS_OUT, "a", encoding="utf-8") as f:
                f.write(json.dumps(text_of(messages[-1])[:400]) + "\n")
        last_user = max((i for i, m in enumerate(messages) if m.get("role") == "user"), default=-1)
        prompt = text_of(messages[last_user]).strip() if last_user >= 0 else ""
        turn = sum(1 for m in messages[last_user + 1:] if m.get("role") == "tool")
        out = [chunk({"role": "assistant", "content": ""})]
        finish = "stop"
        if prompt.startswith("CALL "):
            calls = parse_calls(prompt)
            if turn < len(calls):
                name, args = calls[turn]
                out.append(chunk({"tool_calls": [{"index": 0, "id": f"call_{turn}", "type": "function",
                                                   "function": {"name": name, "arguments": args}}]}))
                finish = "tool_calls"
            else:
                out.append(chunk({"content": f"Done: {calls[0][0]}."}))
        elif prompt.startswith("STREAM "):
            parts = prompt.split()
            n, tag = int(parts[1]), (parts[2] if len(parts) > 2 else "")
            for i in range(n):
                out.append(chunk({"content": WORDS[i % len(WORDS)] + " "}))
            out.append(chunk({"content": f"\n\nDone: streamed {tag}."}))
        else:
            out.append(chunk({"content": f"Done: {prompt[:40]}."}))
        out.append(chunk(finish=finish))
        out.append(chunk(usage={"prompt_tokens": 1000, "completion_tokens": 100, "total_tokens": 1100}))
        out.append(b"data: [DONE]\n\n")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        if PACE:
            for piece in out:
                self.wfile.write(f"{len(piece):x}\r\n".encode() + piece + b"\r\n")
                self.wfile.flush()
                time.sleep(PACE)
        else:
            self.wfile.write(b"".join(f"{len(p):x}\r\n".encode() + p + b"\r\n" for p in out))
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass  # (A client that closed its connection first: nothing to say.)


Server(("127.0.0.1", PORT), Handler).serve_forever()
