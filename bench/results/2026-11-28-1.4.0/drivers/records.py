"""Measure a multi-poll child transcript append. Verify the exact capped text and the following record."""
import argparse
from hashlib import sha256
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from threading import Event, Thread
import time

from discovery import b, message, rpc_records, write_records


def run(build, port, size, probe, probe_seconds, pause=False):
    # A changing prefix makes repeats, missing blocks or incorrect truncation observable.
    pattern = "".join(sha256(str(i).encode()).hexdigest() for i in range(2048))
    text = (pattern * ((size + len(pattern) - 1) // len(pattern)))[:size]
    expected = text if size <= 131072 else text[:131072] + "\n… (cut for the phone)"
    return run_record(build, port, size, message("assistant", text), message("assistant", expected)["message"],
                      sha256(expected.encode()).hexdigest(), probe, probe_seconds, pause)


def run_record(build, port, size, record, expected_message, expected_digest, probe=False, probe_seconds=0, pause=False,
               append_delay=0, before_append=None, after_delivery=None):
    idle = {}
    second = message("assistant", "following record")
    payload = (json.dumps(record, ensure_ascii=False) + "\n" + json.dumps(second) + "\n").encode()
    with b.workdir() as cwd, b.pi_home(port) as config, tempfile.TemporaryDirectory() as home:
        folder = cwd / "sessions"
        folder.mkdir()
        parent, child = folder / "parent.jsonl", folder / "child.jsonl"
        header = dict(type="session", version=3, cwd=str(cwd), timestamp="2026-11-01T00:00:00Z")
        call = dict(type="toolCall", name="Agent", id="call", arguments={"resume": "child123456", "prompt": "p"})
        write_records(parent, [dict(header, id="parent"), dict(type="message", id="one", parentId=None,
                      timestamp=header["timestamp"], message=dict(role="assistant", content=[call], timestamp=0,
                      provider="fake", model="fake-model", api="openai-completions", stopReason="toolUse"))])
        write_records(child, [dict(header, id="child", parentSession=str(parent)),
                      dict(type="session_info", name="child#child123"), message("user", "p")])
        with subprocess.Popen([*build.argv, "--mode", "rpc", "--session", str(parent), *b.MODEL_ARGS],
                              cwd=cwd, env=b.pi_env(config, {"HOME": home}), stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
            stop = Event()
            pinger = None
            sent = {}
            latencies = []
            try:
                events = rpc_records(proc)

                def send(key, command, **params):
                    proc.stdin.write((json.dumps(dict(id=key, type=command, **params)) + "\n").encode())
                    proc.stdin.flush()

                send("subscribe", "subagent_subscribe", runId="call")
                seen = set()
                for event in events:
                    if event.get("id") == "subscribe":
                        assert event["success"]
                        seen.add("ack")
                    if event.get("type") == "subagent_entries":
                        assert event["reset"] and event["messages"] == [message("user", "p")["message"]]
                        seen.add("history")
                    if seen == {"ack", "history"}:
                        break
                if before_append is not None:
                    idle.update(before_append(proc.pid, child))
                if append_delay:
                    time.sleep(append_delay)
                cpu = b.harness.cpu_ms(proc.pid)
                start = time.perf_counter()
                with child.open("ab") as f:
                    f.write(payload)
                if probe:
                    def ping():
                        while not stop.wait(0.01):
                            key = f"ping-{len(sent)}"
                            sent[key] = time.perf_counter()
                            send(key, "get_state")

                    pinger = Thread(target=ping)
                    pinger.start()

                def record_ping(event):
                    assert event["success"] and event["data"]["messageCount"] == 1
                    latencies.append((time.perf_counter() - sent[event["id"]]) * 1000)

                received = []
                for event in events:
                    if event.get("id") in sent:
                        record_ping(event)
                    if event.get("type") == "subagent_entries":
                        assert event["runId"] == "call" and not event["reset"]
                        received.extend(event["messages"])
                        if len(received) >= 2:
                            break
                wall = (time.perf_counter() - start) * 1000
                cost = b.harness.cpu_ms(proc.pid) - cpu
                if pinger:
                    while time.perf_counter() - start < probe_seconds:
                        record_ping(next(events))
                    stop.set()
                    pinger.join()
                    while len(latencies) < len(sent):
                        record_ping(next(events))
                    assert latencies
                assert received == [expected_message, second["message"]]
                private = b.harness.memory_mb(proc.pid)["own"]
                if after_delivery is not None:
                    idle.update(after_delivery(child, events))
                send("stop", "subagent_unsubscribe", runId="call")
                assert next(e for e in events if e.get("id") == "stop")["success"]
                if pause:
                    time.sleep(2)
                    idle["settled_mib"] = b.harness.memory_mb(proc.pid)["own"]
                    clock = b.harness.cpu_ms(proc.pid)
                    time.sleep(15)
                    idle["quiet_cpu_ms"] = b.harness.cpu_ms(proc.pid) - clock
                    idle["quiet_mib"] = b.harness.memory_mb(proc.pid)["own"]
                send("state", "get_state")
                assert next(e for e in events if e.get("id") == "state")["data"]["messageCount"] == 1
            finally:
                stop.set()
                if pinger:
                    pinger.join()
                proc.terminate()
                _, status, usage = os.wait4(proc.pid, 0)
                proc.returncode = os.waitstatus_to_exitcode(status)
    latencies.sort()
    return dict(bytes=size, cpu_ms=cost, wall_ms=wall, private_mib=private,
                peak_mib=usage.ru_maxrss / (1024**2 if sys.platform == "darwin" else 1024),
                expected_sha256=expected_digest, **idle,
                **(dict(probes=len(latencies), probe_seconds=probe_seconds,
                        p95_ms=latencies[math.ceil(.95 * len(latencies)) - 1],
                        max_ms=latencies[-1]) if probe else {}))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--build", required=True, action="append")
    p.add_argument("--rounds", type=int, default=4)
    p.add_argument("--probe", action="store_true")
    p.add_argument("--probe-seconds", type=float, default=0, help="Keep issuing probes for at least this long.")
    args = p.parse_args()
    builds = b.parse_builds(args.build)
    with b.fake_model() as port:
        for size in ((32 * 1024**2,) if args.probe else (16384, 8 * 1024**2, 32 * 1024**2)):
            for pair in range(args.rounds):
                for build in builds if pair % 2 == 0 else reversed(builds):
                    result = run(build, port, size, args.probe, args.probe_seconds)
                    print(json.dumps(dict(pair=pair, build=build.name, **result)), flush=True)
