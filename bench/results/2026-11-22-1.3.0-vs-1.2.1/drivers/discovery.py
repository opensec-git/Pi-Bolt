"""Open the oldest matching subagent among saved transcripts and verify its complete initial and appended messages."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from subscription import b, rpc_records, seed, write_records


def message(role, text):
    return {"type": "message", "message": {"role": role, "content": [{"type": "text", "text": text}]}}


def io_bytes(pid):
    if sys.platform != "linux":
        return None
    for line in Path(f"/proc/{pid}/io").read_text().splitlines():
        if line.startswith("rchar:"):
            return int(line.split()[1])
    raise RuntimeError("Missing process read counter")


def measure(build, port, cwd, parent, target, target_records):
    write_records(target, target_records)
    os.utime(target, (1, 1))
    with b.pi_home(port) as config, tempfile.TemporaryDirectory() as home:
        with subprocess.Popen([*build.argv, "--mode", "rpc", "--session", str(parent), *b.MODEL_ARGS],
                              cwd=cwd, env=b.pi_env(config, {"HOME": home}), stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
            try:
                events = rpc_records(proc)

                def send(kind, key, **fields):
                    proc.stdin.write((json.dumps(dict(type=kind, id=key, **fields)) + "\n").encode())
                    proc.stdin.flush()

                def check_response(key):
                    for event in events:
                        if event.get("id") == key:
                            assert event["success"], event
                            return event.get("data")
                        assert event.get("type") != "subagent_entries", event

                send("get_state", "ready")
                assert check_response("ready")["messageCount"] == 20001
                initial_io = io_bytes(proc.pid)
                clock = lambda: b.harness.cpu_ms(proc.pid)
                cpu, start = clock(), time.perf_counter()
                send("subagent_subscribe", "follow", runId="call")
                seen = set()
                for event in events:
                    if event.get("id") == "follow":
                        assert event["success"] and "ack" not in seen
                        seen.add("ack")
                    elif event.get("type") == "subagent_entries":
                        assert event["runId"] == "call" and event["reset"] and Path(event["file"]) == target, event
                        assert event["messages"] == [r["message"] for r in target_records if r["type"] == "message"]
                        assert "history" not in seen
                        seen.add("history")
                    if seen == {"ack", "history"}:
                        break
                wall_ms = (time.perf_counter() - start) * 1000
                cpu_ms = clock() - cpu
                final_io = io_bytes(proc.pid)
                private_mib = b.harness.memory_mb(proc.pid)["own"]
                extra = message("assistant", "verified transcript append")
                write_records(target, [extra], "a")
                update = next(e for e in events if e.get("type") == "subagent_entries")
                assert update["messages"] == [extra["message"]] and not update["reset"] and update["runId"] == "call"
                send("subagent_unsubscribe", "stop", runId="call")
                check_response("stop")
                send("get_state", "finished")
                assert check_response("finished")["messageCount"] == 20001
                return dict(cpu_ms=cpu_ms, wall_ms=wall_ms, private_mib=private_mib,
                            read_bytes=None if initial_io is None else final_io - initial_io, verified=True)
            finally:
                proc.terminate()
                proc.wait(timeout=10)


def fixture(cwd, count, setup_bytes, tail_bytes):
    sessions = cwd / "sessions"
    sessions.mkdir()
    parent = sessions / "parent.jsonl"
    seed(parent, cwd, 20000, 1)
    call = {"type": "toolCall", "id": "call", "name": "Agent", "arguments": {"prompt": "target prompt"}}
    write_records(parent, [dict(type="message", id="calls", parentId="20000", timestamp="2026-11-01T00:00:00Z",
                                message=dict(role="assistant", content=[call], timestamp=0, stopReason="toolUse",
                                             provider="fake", model="fake-model", api="openai-completions"))], "a")

    def header(name, parent_path):
        return dict(type="session", id=name, cwd=str(cwd), timestamp="2026-11-01T00:01:00Z", parentSession=parent_path)

    setup = [message("system", "s" * setup_bytes)] if setup_bytes else []
    tail = message("assistant", "a" * tail_bytes)
    for i in range(count):
        name = f"other-{i:04d}"
        write_records(sessions / f"{name}.jsonl", [header(name, str(parent) if i % 2 else "another parent"),
                      dict(type="session_info", name=name), *setup,
                      message("user", name if i % 2 else "target prompt"), tail])
    target_records = [header("target", str(parent)), dict(type="session_info", name="target"),
                      message("user", "target prompt"), message("assistant", "verified initial answer")]
    return parent, sessions / "target.jsonl", target_records


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="append", required=True)
    parser.add_argument("--rounds", type=int, default=6)
    args = parser.parse_args()
    builds = b.parse_builds(args.build)
    with b.fake_model() as port:
        for name, setup, tail in [("small", 0, 50), ("large", 0, 262144), ("large-setup", 12288, 262144),
                                  ("long-header", 98304, 262144)]:
            with b.workdir() as cwd:
                files = fixture(cwd, 256, setup, tail)
                for pair in range(args.rounds):
                    for build in builds if pair % 2 == 0 else reversed(builds):
                        print(json.dumps(dict(fixture=name, pair=pair, build=build.name,
                                              **measure(build, port, cwd, *files))), flush=True)
