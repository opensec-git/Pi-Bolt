"""A minimal MCP server on stdio (newline-delimited JSON-RPC) with one tool, `echo`: what pi-mcp-adapter's action calls."""
import json
import sys


def reply(id_, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": id_, "result": result}) + "\n")
    sys.stdout.flush()


for line in sys.stdin:
    try:
        msg = json.loads(line)
    except ValueError:
        continue
    method, id_ = msg.get("method"), msg.get("id")
    if id_ is None:
        continue  # a notification
    if method == "initialize":
        reply(id_, {"protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
                    "capabilities": {"tools": {}}, "serverInfo": {"name": "echo", "version": "1.0.0"}})
    elif method == "tools/list":
        reply(id_, {"tools": [{"name": "echo", "description": "Returns its text.",
                               "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}]})
    elif method == "tools/call":
        text = msg.get("params", {}).get("arguments", {}).get("text", "")
        reply(id_, {"content": [{"type": "text", "text": f"echo: {text}"}]})
    else:
        reply(id_, {})
