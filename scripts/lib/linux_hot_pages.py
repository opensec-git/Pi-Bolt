"""Generate C file ranges for a Linux Pi executable at its first visible prompt."""
import os
from pathlib import Path
import struct
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bench"))
from harness import MODEL_ARGS, PROMPT, Tty, done, fake_model, pi_env, pi_home, workdir


def resident_offsets(pid, binary):
    page = os.sysconf("SC_PAGESIZE")
    result = set()
    with open(f"/proc/{pid}/pagemap", "rb") as table:
        for line in Path(f"/proc/{pid}/maps").read_text().splitlines():
            parts = line.split(maxsplit=5)
            if len(parts) < 6 or parts[5] != str(binary):
                continue
            low, high = (int(end, 16) for end in parts[0].split("-"))
            offset = int(parts[2], 16)
            table.seek(low // page * 8)
            entries = table.read((high - low) // page * 8)
            for index, (flags,) in enumerate(struct.iter_unpack("=Q", entries)):
                if flags >> 63:
                    result.add(offset + index * page)
    if not result:
        raise RuntimeError("no resident pages found for the executable")
    return result


def startup_ranges(binary):
    page = os.sysconf("SC_PAGESIZE")
    with tempfile.TemporaryDirectory() as build_home:
        os.environ.clear()
        os.environ.update(HOME=build_home, PATH="/usr/bin:/bin", LC_ALL="C")
        with fake_model() as port:
            offsets = train(binary, port)
    ranges = []
    for offset in sorted(offsets):
        if ranges and offset - sum(ranges[-1]) <= 65536:
            ranges[-1][1] = offset + page - ranges[-1][0]
        else:
            ranges.append([offset, page])
    return len(offsets) * page, ranges


def train(binary, port):
    offsets = set()
    for _ in range(3):
        with tempfile.TemporaryDirectory() as user, pi_home(port) as agent, workdir() as cwd:
            tty = Tty([str(binary), "--no-session", *MODEL_ARGS], pi_env(agent, {"HOME": user}), cwd)
            try:
                if not tty.wait_for("fake-model", 0, time.perf_counter() + 30):
                    raise RuntimeError("no model visible at startup")
                offsets.update(resident_offsets(tty.pid, binary))
                start = len(tty.buf)
                tty.send(PROMPT.encode() + b"\r")
                if not tty.wait_for(done(1), start, time.perf_counter() + 30):
                    raise RuntimeError("first tool-using answer did not finish")
            finally:
                status, _ = tty.quit()
            if status:
                raise RuntimeError(f"client exited with {status}")
    return offsets


if __name__ == "__main__":
    binary = Path(sys.argv[1]).resolve()
    _, runs = startup_ranges(binary)
    print(f"static const off_t trained_size = {binary.stat().st_size};")
    print("static const off_t ranges[][2] = {")
    for offset, length in runs:
        print(f"    {{{offset}, {length}}},")
    print("};")
