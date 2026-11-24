"""Compare a single identical local Markdown answer through the CLI, PTY and JSONL RPC."""
import argparse
import hashlib
import itertools
import json
import os
from pathlib import Path
import random
import select
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import benchmark as b
from fake_model_stress import MARKDOWN
from idle_profile import cpu_reader

body = "".join(MARKDOWN.replace("{n}", str(i)) for i in range(1, 100))[:5000]
marker = f"Digest: {hashlib.sha256(body.encode()).hexdigest()[:16]}. Done: md, answer 1."


def rpc_records(proc):
    pending = b""
    while select.select([proc.stdout], [], [], 20)[0]:
        block = os.read(proc.stdout.fileno(), 65536)
        if not block:
            break
        lines = (pending + block).split(b"\n")
        pending = lines.pop()
        for line in lines:
            yield json.loads(line)
    raise RuntimeError("RPC stream ended or timed out")


def instructions(pid):
    """macOS: instructions the process has retired so far; None elsewhere."""
    ri = b.harness._rusage_macos(pid) if b.harness.MACOS else None
    return ri[29] if ri else None


def instr_since(pid, start):
    """The instructions retired since `start` (macOS), as {"instr_m": ...}; {} elsewhere."""
    now = instructions(pid)
    return {"instr_m": round((now - start) / 1e6, 2)} if start is not None and now is not None else {}


def rpc(argv, env, cwd, prompt):
    with subprocess.Popen([*argv, "--mode", "rpc", "--no-session", *b.MODEL_ARGS], cwd=cwd, env=env,
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
        try:
            records = rpc_records(proc)
            proc.stdin.write(b'{"id":"state","type":"get_state"}\n')
            proc.stdin.flush()
            state = next(record for record in records if record.get("id") == "state")
            assert state["success"] and state["data"]["messageCount"] == 0
            clock = cpu_reader(proc.pid)
            start_cpu, start, start_instr = clock(), time.perf_counter(), instructions(proc.pid)
            proc.stdin.write((json.dumps({"id": "turn", "type": "prompt", "message": prompt})+"\n").encode())
            proc.stdin.flush()
            matched = False
            for record in records:
                if record.get("type") == "message_end":
                    msg = record["message"]
                    if msg.get("role") == "assistant":
                        answer = "".join(part.get("text", "") for part in msg["content"])
                        matched = body in answer and marker in answer
                if record.get("type") == "agent_end":
                    assert matched
                    return {"wall_ms": (time.perf_counter()-start)*1000, "cpu_ms": clock()-start_cpu,
                            "private_mib": b.harness.memory_mb(proc.pid)["own"], **instr_since(proc.pid, start_instr)}
        finally:
            proc.stdin.close()
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.terminate()
                proc.wait(timeout=4)


def run(build, mode, env, cwd):
    prompt = f"SCENARIO md:5000 CWD {cwd.as_posix()}"
    if mode == "rpc":
        return rpc(build.argv, env, cwd, prompt)
    if mode == "headless":
        output, row = b.run_plain(build, ["-p", "--no-session", *b.MODEL_ARGS, prompt], env, cwd, None)
        assert row["ok"] and body.encode() in output and marker.encode() in output
        return row
    tty = b.Tty([*build.argv, "--no-session", *b.MODEL_ARGS], env, cwd, cols=160, rows=48)
    try:
        deadline = time.perf_counter()+25
        assert tty.wait_for("fake-model",0,deadline)
        tty.settle(0.05,deadline)
        clock = cpu_reader(tty.pid)
        start_cpu, start, offset, start_instr = clock(), time.perf_counter(), len(tty.buf), instructions(tty.pid)
        tty.send(prompt.encode()+b"\r")
        assert tty.wait_for(marker,offset,deadline)
        return {"wall_ms": (time.perf_counter()-start)*1000, "cpu_ms": clock()-start_cpu,
                "private_mib": b.harness.memory_mb(tty.pid)["own"], **instr_since(tty.pid, start_instr)}
    finally:
        tty.quit()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--build", action="append", required=True)
    ap.add_argument("--rounds", type=int, default=12)
    ap.add_argument("--modes", default="headless,rpc,tui")
    ap.add_argument("--legacy-model", action="store_true", help="use the original unbuffered stress server")
    args = ap.parse_args()
    server = "fake_model_stress.py" if args.legacy_model else str(Path(__file__).resolve().with_name("model.py"))
    builds = b.parse_builds(args.build)
    orders = list(itertools.permutations(builds))
    assert args.rounds % len(orders) == 0
    rng = random.Random(41012)
    with b.fake_model(script=server) as port, tempfile.TemporaryDirectory() as user, b.pi_home(port) as home, b.workdir() as cwd:
        env = b.pi_env(home, {"HOME":user})
        for mode in args.modes.split(","):
            for build in builds:
                run(build, mode, env, cwd)
            for i in range(args.rounds):
                if i % len(orders) == 0:
                    rng.shuffle(orders)
                for build in orders[i % len(orders)]:
                    row = run(build, mode, env, cwd)
                    print(json.dumps(dict(row, mode=mode, build=build.name, round=i)), flush=True)


if __name__ == "__main__":
    main()
