"""Measure native RPC while subagent files are pending and verify subsequent event delivery."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from answer import b, rpc_records
from resumed import seed


def write_records(path, records, mode="w"):
    with path.open(mode) as out:
        for record in records:
            print(json.dumps(record), file=out)


def trial(build, port, entries, files, streams, seconds, bound_probe=None):
    with b.workdir() as cwd, b.pi_home(port) as config, tempfile.TemporaryDirectory() as home:
        sessions = cwd / "sessions"
        sessions.mkdir()
        parent = sessions / "parent.jsonl"
        seed(parent, cwd, entries, 1)
        ids = [f"run-{i}" for i in range(streams)]
        calls = [dict(type="toolCall", name="Agent", id=i, arguments=dict(prompt=f"task {i}")) for i in ids]
        write_records(parent, [dict(type="message", id="calls", parentId=str(entries),
                                    timestamp="2026-11-01T00:00:00Z", message=dict(role="assistant", timestamp=0,
                                    content=calls, stopReason="toolUse", model="fake-model", provider="fake",
                                    api="openai-completions"))], "a")

        def message(role, text):
            return dict(type="message", message=dict(role=role, content=[dict(type="text", text=text)]))

        def child(i, related):
            file = sessions / f"{i}.jsonl"
            write_records(file, [dict(type="session", id=i, cwd=str(cwd), timestamp="2026-11-01T00:01:00Z",
                                      parentSession=str(parent) if related else "unrelated"),
                                 dict(type="session_info", name=f"child#{i}"), message("user", f"task {i}"),
                                 message("assistant", f"initial {i}")])
            return file

        for i in range(files):
            child(f"other-{i}", False)
        argv = [*build.argv, "--mode", "rpc", "--session", str(parent), *b.MODEL_ARGS]
        with subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                              cwd=cwd, env=b.pi_env(config, {"HOME": home})) as proc:
            try:
                records = rpc_records(proc)

                def command(kind, key, **params):
                    proc.stdin.write((json.dumps(dict(type=kind, id=key, **params)) + "\n").encode())
                    proc.stdin.flush()
                    for record in records:
                        if record.get("id") == key:
                            assert record["success"], record
                            return record.get("data")
                        assert record.get("type") != "subagent_entries", record

                state = command("get_state", "ready")
                assert state["messageCount"] == entries + 1, state
                clock = lambda: b.harness.cpu_ms(proc.pid)
                start, cpu = time.monotonic(), clock()
                for i in ids:
                    command("subagent_subscribe", i, runId=i)
                subscribe_ms, subscribe_cpu = (time.monotonic() - start) * 1000, clock() - cpu
                start, cpu = time.monotonic(), clock()
                time.sleep(seconds)
                waiting_cpu = (clock() - cpu) / (time.monotonic() - start)
                private = b.harness.memory_mb(proc.pid)["own"]

                def check_updates(reset, prefix):
                    pending = set(ids)
                    for record in records:
                        if record.get("type") != "subagent_entries":
                            continue
                        i = record["runId"]
                        assert i in pending and record["reset"] == reset, record
                        expected = ([message("user", f"task {i}")["message"]] if reset else [])
                        expected.append(message("assistant", f"{prefix} {i}")["message"])
                        assert record["messages"] == expected, record
                        pending.remove(i)
                        if not pending:
                            return

                for i in ids:
                    child(i, True)
                check_updates(True, "initial")
                observed = bound_probe(proc.pid, sessions) if bound_probe else {}
                written = time.monotonic()
                for i in ids:
                    write_records(sessions / f"{i}.jsonl", [message("assistant", f"append {i}")], "a")
                check_updates(False, "append")
                if bound_probe:
                    observed["all_appends_ms"] = (time.monotonic() - written) * 1000
                for i in ids:
                    command("subagent_unsubscribe", i, runId=i)
                assert command("get_state", "finished")["messageCount"] == entries + 1
                return dict(entries=entries, unrelated_files=files, streams=streams, seconds=seconds,
                            subscribe_ms=subscribe_ms, subscribe_cpu_ms=subscribe_cpu,
                            idle_cpu_ms_per_s=waiting_cpu, private_mib=private, checked_streams=streams, **observed)
            finally:
                proc.terminate()
                proc.wait(timeout=10)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", required=True, action="append")
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--seconds", type=float, default=4.25)
    parser.add_argument("--fixture", choices=("both", "small", "large"), default="both")
    args = parser.parse_args()
    builds = b.parse_builds(args.build)
    with b.fake_model() as port:
        for name, shape in [("small", (2, 4, 1)), ("large", (20000, 512, 8))]:
            if args.fixture not in ("both", name):
                continue
            for pair in range(args.rounds):
                for build in (builds if pair % 2 == 0 else reversed(builds)):
                    print(json.dumps(dict(pair=pair, build=build.name,
                                          **trial(build, port, *shape, args.seconds))), flush=True)
