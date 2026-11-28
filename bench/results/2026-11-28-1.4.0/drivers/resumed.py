"""Measure prompt CPU in a packaged RPC client with 1,200 pre-existing text messages."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

growing = Path(__file__).resolve().parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from answer import b, body, cpu_reader, rpc_records

PROMPTS = 60
WARMUP = 3


def seed(path, cwd, entries, sentences):
    stamp = "2026-11-01T00:00:00.000Z"
    header = dict(type="session", version=3, id="d03ea777-fe4d-44e7-8a5f-c2c271835555",
                  timestamp=stamp, cwd=str(cwd))
    usage = dict(input=1000, output=50, totalTokens=1050, cacheRead=0, cacheWrite=0,
                 cost=dict(input=0, output=0, cacheRead=0, cacheWrite=0, total=0))
    with path.open("w") as out:
        print(json.dumps(header), file=out)
        for i in range(entries):
            text = f"Entry {i}." + " The parser reports an unexpected token." * sentences
            msg = dict(timestamp=i, role="user", content=text)
            if i % 2:
                msg.update(role="assistant", content=[dict(type="text", text=text)], api="openai-completions",
                           provider="fake", model="fake-model", usage=usage, stopReason="stop")
            print(json.dumps(dict(type="message", id=str(i+1), parentId=str(i) if i else None,
                                  timestamp=stamp, message=msg)), file=out)


def measure(build, cpus, port, entries, sentences):
    with b.workdir() as cwd, b.pi_home(port) as agent, tempfile.TemporaryDirectory() as home:
        path = cwd / "seeded.jsonl"
        seed(path, cwd, entries, sentences)
        argv = b.harness.pinned([*build.argv, "--mode", "rpc", "--session", str(path), *b.MODEL_ARGS], cpus)
        with subprocess.Popen(argv, cwd=cwd, env=b.pi_env(agent, {"HOME": home}),
                              stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
            try:
                records = rpc_records(proc)
                proc.stdin.write(b'{"id":"ready","type":"get_state"}\n')
                proc.stdin.flush()
                state = next(r for r in records if r.get("id") == "ready")
                assert state["success"] and state["data"]["messageCount"] == entries
                clock = cpu_reader(proc.pid)
                answer = body[:512]
                digest = hashlib.sha256(answer.encode()).hexdigest()[:16]
                for i in range(WARMUP + PROMPTS):
                    if i == WARMUP:
                        start, cpu = time.perf_counter(), clock()
                    proc.stdin.write(b'{"type":"prompt","message":"SCENARIO md:512"}\n')
                    proc.stdin.flush()
                    marker = f"Digest: {digest}. Done: md, answer {entries // 2 + i + 1}."
                    matched = False
                    for record in records:
                        if record.get("type") == "message_end" and record["message"]["role"] == "assistant":
                            text = "".join(p.get("text", "") for p in record["message"]["content"])
                            matched = answer in text and marker in text
                        if record.get("type") == "agent_end":
                            break
                    assert matched, (i, marker)
                return dict(build=build.name, prompts=PROMPTS, prior_messages=entries,
                            cpu_ms_per_prompt=(clock()-cpu)/PROMPTS,
                            wall_ms_per_prompt=(time.perf_counter()-start)*1000/PROMPTS,
                            private_mib=b.harness.memory_mb(proc.pid)["own"])
            finally:
                proc.stdin.close()
                try:
                    proc.wait(timeout=4)
                except subprocess.TimeoutExpired:
                    proc.terminate()
                    proc.wait(timeout=4)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="append", required=True)
    parser.add_argument("--rounds", type=int, default=6)
    parser.add_argument("--entries", type=int, default=1200)
    parser.add_argument("--sentences", type=int, default=12)
    parser.add_argument("--cpus")
    args = parser.parse_args()
    if args.entries < 2 or args.entries % 2 or args.sentences < 1:
        parser.error("provide a positive even number of entries and at least one sentence")
    builds = b.parse_builds(args.build)
    with b.fake_model(script=str(growing / "model.py")) as port:
        for i in range(args.rounds):
            for build in (builds if i % 2 == 0 else builds[::-1]):
                print(json.dumps({**measure(build, args.cpus, port, args.entries, args.sentences),
                                  "round": i}), flush=True)
