"""Benchmark verified incremental RPC queries against a saved 12,000-entry session."""
import argparse
import json
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness

ENTRIES = 12000
REQUESTS = 160


def query(proc, command, **params):
    proc.stdin.write((json.dumps({"id": command, "type": command, **params}) + "\n").encode())
    proc.stdin.flush()
    while select.select([proc.stdout], [], [], 20)[0]:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError(f"closed during {command}")
        record = json.loads(line)
        if record.get("id") == command:
            if not record.get("success"):
                raise RuntimeError(record)
            return record["data"]
    raise TimeoutError(command)


def measure(build, home, cwd, userhome):
    session = cwd / "original.jsonl"
    header = {"type": "session", "version": 3, "id": "ac273b22-55af-475b-9772-555555555555",
              "cwd": str(cwd), "timestamp": "2026-11-01T00:00:00.000Z"}
    with session.open("w") as out:
        print(json.dumps(header), file=out)
        for n in range(1, ENTRIES + 1):
            print(json.dumps({"type": "message", "id": str(n), "parentId": str(n-1) if n > 1 else None,
                              "timestamp": header["timestamp"], "message": {"role": "user", "timestamp": n, "content": str(n)}}), file=out)
    env = harness.pi_env(home, {"HOME": userhome})
    with subprocess.Popen([*build.argv, "--mode", "rpc", "--session", str(session), *harness.MODEL_ARGS], env=env,
                          cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
        try:
            assert query(proc, "get_state")["messageCount"] == ENTRIES
            tail = query(proc, "get_entries", since=str(ENTRIES - 1))
            assert tail["entries"][0]["id"] == str(ENTRIES)
            for command in ("get_entries", "get_session_stats"):
                params = {"since": str(ENTRIES - 1)} if command == "get_entries" else {}
                def check():
                    data = query(proc, command, **params)
                    if params:
                        assert data == tail
                    else:
                        assert data["userMessages"] == ENTRIES and data["totalMessages"] >= ENTRIES
                check()
                start = time.perf_counter()
                cpu = harness.cpu_ms(proc.pid)
                for _ in range(REQUESTS):
                    check()
                elapsed = time.perf_counter() - start
                print(json.dumps({"build": build.name, "command": command, "requests": REQUESTS,
                                  "wall_ms_per_request": elapsed * 1000 / REQUESTS,
                                  "cpu_ms_per_request": (harness.cpu_ms(proc.pid) - cpu) / REQUESTS,
                                  "private_mib": harness.memory_mb(proc.pid)["own"]}), flush=True)
        finally:
            proc.stdin.close()
            try:
                proc.wait(timeout=4)
            except subprocess.TimeoutExpired:
                proc.terminate()
                proc.wait(timeout=4)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build", action="append", required=True)
    ap.add_argument("--rounds", type=int, default=6)
    args = ap.parse_args()
    builds = harness.parse_builds(args.build)
    with harness.fake_model() as port, tempfile.TemporaryDirectory() as userhome:
        for n in range(args.rounds):
            for build in (list(reversed(builds)) if n % 2 else builds):
                with harness.pi_home(port) as home, harness.workdir() as cwd:
                    measure(build, home, cwd, userhome)


if __name__ == "__main__":
    main()
