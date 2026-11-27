#!/usr/bin/env python3
"""A deterministic OpenAI-compatible streaming model for benchmarking Pi.

Every request is answered from the request body alone, so concurrent runs are independent: after each user prompt the model reads
the four fixture files one at a time with Pi's `read` tool (one tool call per turn) and then answers. Each turn streams about 120
words of prose first, so the client's streaming, rendering and token accounting are exercised.

Usage: fake_model.py PORT [--pace-ms N] [--log-sizes FILE]
  --pace-ms N      stream one event every N ms, like a real model (default: the whole response at once)
  --log-sizes FILE append each request's body size to FILE (to see how large the conversation has grown)
"""

import json
import socket
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOL_TURNS = 4  # 4 tool-call turns + 1 final answer = 5 model turns
FILES = ["fixture/agent-session.ts", "fixture/interactive-mode.ts", "fixture/tui.ts", "fixture/openai-completions.ts"]
WORDS = ("Looking at the code structure the module wires the session state into the renderer and "
         "then streams tool results back through the agent loop before compaction runs ").split()


def chunk(delta=None, finish=None, usage=None):
    body = {
        "id": "chatcmpl-bench",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": "fake-model",
        "choices": [] if usage else [{"index": 0, "delta": delta or {}, "finish_reason": finish}],
    }
    if usage:
        body["usage"] = usage
    return f"data: {json.dumps(body)}\n\n".encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        # Without this, Nagle's algorithm holds the small SSE writes until the client's delayed ACK (40 ms on Linux),
        # which added ~40 ms to about half of all model turns and hid the runtime's own cost.
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
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if LOG:
            with open(LOG, "a") as f:
                f.write(f"{len(body)}\n")
        req = json.loads(body or b"{}")
        # Count tool results since the latest user prompt, so every prompt in a
        # multi-prompt session runs the same 5-turn loop.
        turn = prompts = 0
        for m in req.get("messages", []):
            if m.get("role") == "user":
                turn = 0
                prompts += 1
            elif m.get("role") == "tool":
                turn += 1

        out = [chunk({"role": "assistant", "content": ""})]
        # ~120 words of prose per turn, streamed a word at a time.
        for i in range(120):
            out.append(chunk({"content": WORDS[i % len(WORDS)] + " "}))
        if turn < TOOL_TURNS:
            args = json.dumps({"path": FILES[turn]})
            out.append(chunk({"tool_calls": [{"index": 0, "id": f"call_{turn}", "type": "function",
                                               "function": {"name": "read", "arguments": ""}}]}))
            for i in range(0, len(args), 8):
                out.append(chunk({"tool_calls": [{"index": 0, "function": {"arguments": args[i:i + 8]}}]}))
            out.append(chunk(finish="tool_calls"))
        else:
            # With the number of the prompt: a screen that is drawn again shows the answers before this one too, and
            # whoever waits for this answer must not take one of those for it.
            out.append(chunk({"content": f"\n\nDone: read all four files (prompt {prompts})."}))
            out.append(chunk(finish="stop"))
        out.append(chunk(usage={"prompt_tokens": 1000 * (turn + 1), "completion_tokens": 150,
                                "total_tokens": 1000 * (turn + 1) + 150}))
        out.append(b"data: [DONE]\n\n")

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        if PACE:
            # Streamed the way a model streams: one event at a time, PACE seconds apart (`fake_api.py <port> --pace-ms N`).
            for piece in out:
                self.wfile.write(f"{len(piece):x}\r\n".encode() + piece + b"\r\n")
                self.wfile.flush()
                time.sleep(PACE)
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            return
        # One write: the client still parses every SSE event, but the response arrives at once like a fast model would send it.
        self.wfile.write(b"".join(f"{len(piece):x}\r\n".encode() + piece + b"\r\n" for piece in out) + b"0\r\n\r\n")
        self.wfile.flush()


PACE = 0.0
LOG = None

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    if "--log-sizes" in sys.argv:
        LOG = sys.argv[sys.argv.index("--log-sizes") + 1]
    if "--pace-ms" in sys.argv:
        PACE = float(sys.argv[sys.argv.index("--pace-ms") + 1]) / 1000
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
