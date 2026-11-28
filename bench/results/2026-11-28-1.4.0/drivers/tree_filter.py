"""Measure /tree search through a PTY using native builds and a restored session."""
import argparse
import json
from pathlib import Path
import re
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness as h
from idle_profile import cpu_reader


def seed(session, cwd, shape):
    depth, leaves = (12000, 0) if shape == "linear" else (2048, 1024)
    stamp = "2026-11-19T10:00:00Z"
    with session.open("w") as out:
        print(json.dumps(dict(type="session", version=3, id="fa123456", cwd=str(cwd), timestamp=stamp)), file=out)
        for i in range(1, depth + leaves + 1):
            parent = min(depth, i - 1)
            match = (i % 2 == 0) if shape == "linear" else (i > depth)
            msg = dict(role="user", content=f"{'zebra' if match else 'ordinary'} request {i}", timestamp=i)
            print(json.dumps(dict(type="message", id=str(i), parentId=str(parent) if parent else None,
                                  timestamp=stamp, message=msg)), file=out)
    return depth // 2 if shape == "linear" else leaves


def run(build, shape, port, cpus):
    with h.pi_home(port) as agent, h.workdir() as cwd, tempfile.TemporaryDirectory() as user:
        session = cwd / "saved.jsonl"
        matches = seed(session, cwd, shape)
        argv = h.pinned([*build.argv, "--session", str(session), *h.MODEL_ARGS], cpus)
        tty = h.Tty(argv, h.pi_env(agent, {"HOME": user}), cwd)
        deadline = time.perf_counter() + 60
        try:
            assert tty.wait_for("fake-model", 0, deadline)
            assert tty.settle(0.15, deadline)
            pos = len(tty.buf)
            tty.send(b"/tree\r")
            assert tty.wait_for("Session Tree", pos, deadline), tty.screen_text(pos)[-1000:]
            assert tty.settle(0.1, deadline)
            status = re.findall(r"\(\d+/(\d+)\)", tty.screen_text(pos))
            assert status, tty.screen_text(pos)[-1000:]
            total = int(status[-1])
            assert total >= (12000 if shape == "linear" else 3072)
            clock = cpu_reader(tty.pid)
            samples = []
            for _ in range(8):
                for key, expected in [(b"z", matches), (b"\x7f", total)]:
                    pos = len(tty.buf)
                    cpu, start = clock(), time.perf_counter()
                    tty.send(key)
                    assert tty.wait_for(f"/{expected})", pos, deadline), tty.screen_text(pos)[-1000:]
                    samples.append(dict(action="filter" if key == b"z" else "clear",
                                        cpu_ms=clock()-cpu, wall_ms=(time.perf_counter()-start)*1000))
            memory = h.memory_mb(tty.pid)["own"]
            tty.send(b"\x1b")
            assert tty.settle(0.15, deadline)
            return dict(build=build.name, shape=shape, total=total, matches=matches,
                        private_mib=memory, samples=samples)
        finally:
            status, _ = tty.quit(timeout=2)
            if sys.exc_info()[0] is None:
                assert status == 0, status


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--build", action="append", required=True)
    p.add_argument("--rounds", type=int, default=6)
    p.add_argument("--cpus")
    args = p.parse_args()
    builds = h.parse_builds(args.build)
    with h.fake_model() as port:
        for r in range(args.rounds):
            for shape in ["linear", "branched"]:
                for build in builds if r % 2 == 0 else reversed(builds):
                    print(json.dumps(dict(round=r, **run(build, shape, port, args.cpus))), flush=True)
