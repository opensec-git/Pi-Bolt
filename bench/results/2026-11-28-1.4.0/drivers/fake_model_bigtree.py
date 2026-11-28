#!/usr/bin/env python3
"""A deterministic OpenAI-compatible model for big_tree.py: the prompt `BIGTREE grep` makes it call Pi's `grep` tool once with
the pattern `function`, `BIGTREE find` Pi's `find` tool once with the pattern `readme*` (`BIGTREE find <pattern>`: that pattern),
both over the whole working folder with their default limits; then it answers with the number of lines the tool result had.

Usage: fake_model_bigtree.py PORT
"""

import json
import re
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CALLS = {"grep": {"pattern": "function"}, "find": {"pattern": "readme*"}}


def chunk(delta=None, finish=None, usage=None):
    body = {"id": "chatcmpl-bigtree", "object": "chat.completion.chunk", "created": 0, "model": "fake-model",
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
        kind, pattern, results = None, None, []
        for m in req.get("messages", []):
            content = m.get("content")
            text = content if isinstance(content, str) else "".join(p.get("text", "") for p in content or [])
            if m.get("role") == "user":
                found = re.search(r"BIGTREE (grep|find)(?: (\S+))?", text)
                kind, pattern, results = (found[1] if found else None), (found[2] if found else None), []
            elif m.get("role") == "tool":
                results.append(text)
        out = [chunk({"role": "assistant", "content": ""})]
        if kind and not results:
            out.append(chunk({"tool_calls": [{"index": 0, "id": "call_0", "type": "function",
                                               "function": {"name": kind, "arguments": json.dumps({**CALLS[kind], **({"pattern": pattern} if pattern else {})})}}]}))
            out.append(chunk(finish="tool_calls"))
        else:
            lines = results[0].count("\n") + 1 if results else 0
            out.append(chunk({"content": f"Done: {kind}, {lines} lines."}))
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
