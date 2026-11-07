#!/usr/bin/env python3
"""Checks that every build does the same work for the scripted models before they are timed: what each one sends the model
(roles of the conversation, tool names, the tool results' digests) and what it prints, for the headless prompt of benchmark.py
(fake_model.py: four `read` calls and an answer) and for the scenarios of fake_model_stress.py that long_answer.py and
tmux_load.py use (a long Markdown answer, a 50 KB file written through a tool call, then `wc -c` through bash). Exit status 1
if the tool results or the answers differ between builds.

Usage: bench/pig_verify.py --build name=command [--build ...]
"""

import argparse
import hashlib
import io
import json
import subprocess
import sys
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fake_model  # noqa: E402
import harness  # noqa: E402

REQUESTS: list = []


class Recording(fake_model.Handler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        REQUESTS.append(json.loads(body or b"{}"))
        self.rfile = io.BytesIO(body)
        super().do_POST()


def text(content) -> str:
    return "".join(p.get("text", "") for p in content) if isinstance(content, list) else (content or "")


def sha(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()[:12]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="append", required=True)
    a = ap.parse_args()
    builds = harness.parse_builds(a.build)
    port = harness.free_port()
    server = ThreadingHTTPServer(("127.0.0.1", port), Recording)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    seen: dict = {}
    for b in builds:
        REQUESTS.clear()
        with harness.pi_home(port) as home, harness.workdir() as cwd:
            p = subprocess.run(harness.octet_argv([*b.argv, "-p", "--no-session", *harness.MODEL_ARGS, harness.PROMPT]), env=harness.pi_env(home),
                               cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True, timeout=300)
        last = REQUESTS[-1] if REQUESTS else {}
        msgs = last.get("messages", [])
        results = [sha(text(m.get("content"))) for m in msgs if m.get("role") == "tool"]
        system = text(next((m.get("content") for m in msgs if m.get("role") in ("system", "developer")), ""))
        row = {"exit": p.returncode, "requests": len(REQUESTS), "roles": "".join(m["role"][0] for m in msgs),
               "tool_results": results, "answer": sha(p.stdout.decode("utf-8", "replace"))}
        seen.setdefault("headless", {})[b.name] = row
        print(f"headless {b.name}: {json.dumps(row)} tools={[t['function']['name'] for t in last.get('tools', [])]} "
              f"system_prompt_chars={len(system)} last_request_bytes={len(json.dumps(last))}")
    server.shutdown()
    for scenario in ["md:5000", "write:50", "light"]:
        for b in builds:
            with harness.fake_model(script="fake_model_stress.py") as port, harness.pi_home(port) as home, harness.workdir() as cwd:
                p = subprocess.run(harness.octet_argv([*b.argv, "-p", "--no-session", *harness.MODEL_ARGS, f"SCENARIO {scenario} CWD {cwd.as_posix()}"]),
                                   env=harness.pi_env(home), cwd=cwd, stdin=subprocess.DEVNULL, capture_output=True, timeout=600)
                out = p.stdout.decode("utf-8", "replace").strip().splitlines()
                tail = out[-1] if out else ""
                # ("sent <digest>" covers the system prompt and tool definitions too, which differ between Pi-Bolt, Pi and PiG.)
                key = tail.split(", sent ")[0] if ", sent " in tail else tail
                f = cwd / "big" / "source.ts"
                if f.exists():
                    key += f" file {f.stat().st_size} B {sha(f.read_text())}"
            seen.setdefault(scenario, {})[b.name] = {"exit": p.returncode, "result": key}
            print(f"{scenario} {b.name}: exit {p.returncode}: {key}")
    failed = False
    for scenario, rows in seen.items():
        values = {json.dumps({k: v for k, v in r.items() if k not in ("requests",)}, sort_keys=True) for r in rows.values()}
        same = len(values) == 1 and all(r["exit"] == 0 for r in rows.values())
        failed |= not same
        print(f"{scenario}: {'same work in every build' if same else 'DIFFERS'}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
