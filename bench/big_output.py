#!/usr/bin/env python3
"""A bash tool call whose command prints a lot of output as fast as it can: what `pi -p` costs in wall time, CPU and peak
memory, and whether the full output file it keeps is complete. Memory must stay bounded however long the output is: Pi
keeps a tail for the model and writes the rest to a file, and the file must not fall behind the pipe and queue in memory.

Usage: big_output.py --build name=command [--build ...] [--mb 64,512] [--runs 3] [--max-peak-mb 400]
Exit status 1 if a run fails, its output file is incomplete, or (with --max-peak-mb) its peak memory is above the limit."""

import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from harness import MACOS, cpu_ms, maxrss_mb, parse_builds, peak_footprint_mb, pi_env, pi_home, workdir  # noqa: E402

FLUSH = False
LINE = "0123456789abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789\n"  # 85 bytes


def chunk(delta=None, finish=None):
    return b"data: " + json.dumps({"id": "fake", "object": "chat.completion.chunk", "model": "fake-model",
                                   "choices": [{"index": 0, "delta": delta or {}, "finish_reason": finish}]}).encode() + b"\n\n"


def serve(command: str):
    """A model that runs `command` through the bash tool once, then answers with the tool result's last line."""

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            tool = [m for m in req.get("messages", []) if m.get("role") == "tool"]
            out = [chunk({"role": "assistant", "content": ""})]
            if not tool:
                out.append(chunk({"tool_calls": [{"index": 0, "id": "call_0", "type": "function",
                                                   "function": {"name": "bash", "arguments": json.dumps({"command": command})}}]}))
                out.append(chunk(finish="tool_calls"))
            else:
                content = tool[-1].get("content")
                text = content if isinstance(content, str) else json.dumps(content)
                path = re.search(r"Full output: (\S+?)\]", text)
                out.append(chunk({"content": "PATH=" + (path.group(1) if path else "none")}))
                out.append(chunk(finish="stop"))
            out.append(b"data: [DONE]\n\n")
            body = b"".join(out)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def run(build, mb: int):
    lines = mb * (1 << 20) // len(LINE)
    # awk prints as fast as the pipe takes it (with --flush: one write per line, so Pi reads many small chunks); the last
    # line says how many came before it.
    flush = " fflush();" if FLUSH else ""
    command = f"awk 'BEGIN {{ for (i = 0; i < {lines}; i++) {{ printf \"%s\", \"{LINE[:-1]}\\n\";{flush} }} print \"END\", {lines} }}'"
    server = serve(command)
    port = server.server_address[1]
    try:
        with pi_home(port) as home, workdir() as cwd:
            argv = [*build.argv, "-p", "--provider", "fake", "--model", "fake-model", "--no-session", "go"]
            t0 = time.perf_counter()
            proc = subprocess.Popen(argv, cwd=cwd, env=pi_env(home), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE)
            # Read stdout/stderr without reaping: the peak footprint is read from the exited, unreaped child.
            chunks = {"out": [], "err": []}

            def pump(f, key):
                for piece in iter(lambda: f.read(65536), b""):
                    chunks[key].append(piece)

            threads = [threading.Thread(target=pump, args=(proc.stdout, "out")),
                       threading.Thread(target=pump, args=(proc.stderr, "err"))]
            for t in threads:
                t.start()
            peak = peak_footprint_mb(proc.pid) if MACOS else None
            cpu = cpu_ms(proc.pid) if MACOS else None
            _, status, ru = os.wait4(proc.pid, 0)
            wall = (time.perf_counter() - t0) * 1000
            for t in threads:
                t.join()
            out, err = b"".join(chunks["out"]).decode(errors="replace"), b"".join(chunks["err"]).decode(errors="replace")
            if cpu is None:
                cpu = (ru.ru_utime + ru.ru_stime) * 1000
            if peak is None:
                peak = maxrss_mb(ru)
            ok = os.waitstatus_to_exitcode(status) == 0
            note = ""
            m = re.search(r"PATH=(\S+)", out)
            if not ok:
                note = f"exit {os.waitstatus_to_exitcode(status)}: {err.strip()[-300:]}"
            elif not m or m.group(1) == "none":
                note = f"no full output path in the answer: {out.strip()[-200:]}"
                ok = False
            else:
                path = Path(m.group(1))
                size = path.stat().st_size if path.exists() else -1
                want = lines * len(LINE) + len(f"END {lines}\n")
                if size != want:
                    ok, note = False, f"full output file has {size} bytes, expected {want}"
                else:
                    with open(path, "rb") as f:
                        f.seek(-len(f"END {lines}\n"), 2)
                        if f.read() != f"END {lines}\n".encode():
                            ok, note = False, "full output file does not end with the last line"
                path.unlink(missing_ok=True)
            return {"build": build.name, "mb": mb, "ok": ok, "wall_ms": round(wall), "cpu_ms": round(cpu),
                    "peak_mb": peak, "note": note}
    finally:
        server.shutdown()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True)
    ap.add_argument("--mb", default="64,512", help="output sizes in MiB, comma-separated")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--max-peak-mb", type=float, default=None)
    ap.add_argument("--flush", action="store_true", help="the command writes each line on its own")
    ap.add_argument("--out", default=None, help="append results as JSON lines to this file")
    args = ap.parse_args()
    builds = parse_builds(args.build)
    global FLUSH
    FLUSH = args.flush
    failed = False
    for mb in [int(x) for x in args.mb.split(",")]:
        for i in range(args.runs):
            for build in builds:
                r = run(build, mb)
                if args.max_peak_mb is not None and r["peak_mb"] > args.max_peak_mb:
                    r["ok"] = False
                    r["note"] = (r["note"] + "; " if r["note"] else "") + f"peak {r['peak_mb']} MB > {args.max_peak_mb}"
                failed |= not r["ok"]
                print(json.dumps(r), flush=True)
                if args.out:
                    with open(args.out, "a") as f:
                        f.write(json.dumps(r) + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
