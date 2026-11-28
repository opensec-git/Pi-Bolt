#!/usr/bin/env python3
"""A deterministic OpenAI-compatible model for grep_tree.py: after the user's prompt it calls Pi's `grep` tool once per pattern
in PATTERNS (one call per turn, over the whole working folder), then answers with the number of grep results it got.

Usage: fake_model_grep.py PORT
"""

import json
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Ordinary searches of a source tree, each with fewer matches than the limit, so every call reads the whole tree.
PATTERNS = [
    {"pattern": "TODO"},
    {"pattern": "Intl\\.Segmenter"},
    {"pattern": "FIXME|XXX"},
    {"pattern": "AbortSignal\\.timeout\\("},
    {"pattern": "export (async )?function [a-z]+Stream"},
    {"pattern": "class [A-Za-z]+ extends Error"},
    {"pattern": "deprecated", "ignoreCase": True},
]
LIMIT = 500


def chunk(delta=None, finish=None, usage=None):
    body = {"id": "chatcmpl-grep", "object": "chat.completion.chunk", "created": 0, "model": "fake-model",
            "choices": [] if usage else [{"index": 0, "delta": delta or {}, "finish_reason": finish}]}
    if usage:
        body["usage"] = usage
    return f"data: {json.dumps(body)}\n\n".encode()


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
        turn, lines = 0, 0
        for m in req.get("messages", []):
            if m.get("role") == "user":
                turn, lines = 0, 0
            elif m.get("role") == "tool":
                turn += 1
                content = m.get("content")
                text = content if isinstance(content, str) else "".join(p.get("text", "") for p in content or [])
                lines += text.count("\n") + 1
        out = [chunk({"role": "assistant", "content": ""})]
        if turn < len(PATTERNS):
            args = json.dumps({**PATTERNS[turn], "limit": LIMIT})
            out.append(chunk({"tool_calls": [{"index": 0, "id": f"call_{turn}", "type": "function",
                                               "function": {"name": "grep", "arguments": args}}]}))
            out.append(chunk(finish="tool_calls"))
        else:
            out.append(chunk({"content": f"Done: {turn} searches, {lines} lines."}))
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
