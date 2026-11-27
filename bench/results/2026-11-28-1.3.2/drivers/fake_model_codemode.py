#!/usr/bin/env python3
"""A deterministic OpenAI-compatible model for codemode.py: the prompt `CODEMODE <N>` makes it call Pi's `codemode` tool N times
in one turn (N tool calls at once, each a small script that computes a checksum), then answer with how many scripts completed
with the expected value.

Usage: fake_model_codemode.py PORT
"""

import json
import re
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOOPS = 300_000
SCRIPT = (f"let s = 0;\nfor (let i = 0; i < {LOOPS}; i++) s = (s + i * i) % 1000003;\n"
          "return 'checksum ' + s;")
EXPECTED = f"checksum {sum(i * i for i in range(LOOPS)) % 1000003}"


def chunk(delta=None, finish=None, usage=None):
    body = {"id": "chatcmpl-codemode", "object": "chat.completion.chunk", "created": 0, "model": "fake-model",
            "choices": [] if usage else [{"index": 0, "delta": delta or {}, "finish_reason": finish}]}
    if usage:
        body["usage"] = usage
    return f"data: {json.dumps(body)}\n\n".encode()


def text_of(content):
    return content if isinstance(content, str) else "".join(p.get("text", "") for p in content or [])


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
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        calls, results = 0, []
        for m in req.get("messages", []):
            if m.get("role") == "user":
                found = re.search(r"CODEMODE (\d+)", text_of(m.get("content")))
                calls, results = (int(found[1]) if found else 0), []
            elif m.get("role") == "tool":
                results.append(text_of(m.get("content")))
        out = [chunk({"role": "assistant", "content": ""})]
        if not results:
            for k in range(calls):
                args = json.dumps({"code": SCRIPT})
                out.append(chunk({"tool_calls": [{"index": k, "id": f"call_{k}", "type": "function",
                                                   "function": {"name": "codemode", "arguments": args}}]}))
            out.append(chunk(finish="tool_calls"))
        else:
            good = sum(1 for r in results if r.startswith("Script completed") and EXPECTED in r)
            out.append(chunk({"content": f"Done: {good} of {calls} scripts."}))
            out.append(chunk(finish="stop"))
        out.append(chunk(usage={"prompt_tokens": 1000, "completion_tokens": 20, "total_tokens": 1020}))
        out.append(b"data: [DONE]\n\n")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        self.wfile.write(b"".join(f"{len(p):x}\r\n".encode() + p + b"\r\n" for p in out) + b"0\r\n\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
