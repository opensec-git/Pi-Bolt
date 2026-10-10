#!/usr/bin/env python3
"""The parts of pi-bin that a start of Pi reads, for the launcher to ask for at once (darwin-launcher.c, readAhead).

Drops pi-bin's pages from memory (msync MS_INVALIDATE: no privileges needed), starts Pi the two usual ways against the fake
model (the TUI with one prompt, then `pi -p`), and writes the runs of pi-bin's 16 KB pages that are then in memory, with runs
less than 256 KB apart joined, after a first line with pi-bin's size.
Usage: darwin_hot_pages.py DIR   (DIR holds pi, pi-bin; writes DIR/pi-bin.hot)"""
import ctypes
import mmap
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bench"))
from harness import DONE, MODEL_ARGS, PROMPT, Tty, fake_model, pi_env, pi_home, workdir  # noqa: E402

PAGE = 16384
GAP = 256 * 1024
libc = ctypes.CDLL(None, use_errno=True)
libc.mmap.restype = ctypes.c_void_p
libc.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_longlong]
libc.msync.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
libc.mincore.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_char_p]
libc.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
MS_INVALIDATE = 0x0002


def mapped(path):
    size = os.path.getsize(path)
    fd = os.open(path, os.O_RDONLY)
    try:
        at = libc.mmap(None, size, mmap.PROT_READ, mmap.MAP_SHARED, fd, 0)
    finally:
        os.close(fd)
    if at in (None, ctypes.c_void_p(-1).value):
        raise OSError(ctypes.get_errno(), "mmap")
    return at, size


def drop(path):
    at, size = mapped(path)
    libc.msync(at, size, MS_INVALIDATE)
    libc.munmap(at, size)


def resident_runs(path):
    at, size = mapped(path)
    pages = (size + PAGE - 1) // PAGE
    vector = ctypes.create_string_buffer(pages)
    libc.mincore(at, size, vector)
    libc.munmap(at, size)
    runs = []
    for i, flag in enumerate(vector.raw):
        if not flag & 1:
            continue
        offset = i * PAGE
        if runs and offset - (runs[-1][0] + runs[-1][1]) <= GAP:
            runs[-1][1] = offset + PAGE - runs[-1][0]
        else:
            runs.append([offset, PAGE])
    return size, runs


directory = Path(sys.argv[1]).resolve()
exe, binary = directory / "pi", directory / "pi-bin"
drop(binary)
if resident_runs(binary)[1]:
    print("darwin_hot_pages: pi-bin's pages could not be dropped from memory; no pi-bin.hot", file=sys.stderr)
    sys.exit(0)
with fake_model() as port, pi_home(port) as home, workdir() as cwd:
    tty = Tty([str(exe), "--no-session", *MODEL_ARGS], pi_env(home), cwd)
    deadline = time.perf_counter() + 120
    assert tty.wait_for("fake-model", 0, deadline), "the TUI did not start"
    start = len(tty.buf)
    tty.send(PROMPT.encode() + b"\r")
    assert tty.wait_for(DONE, start, deadline), "no answer"
    status, _ = tty.quit()
    assert status == 0, f"the TUI exited with {status}"
    subprocess.run([str(exe), "-p", "--no-session", *MODEL_ARGS, PROMPT], env=pi_env(home), cwd=cwd, stdin=subprocess.DEVNULL,
                   stdout=subprocess.DEVNULL, check=True)
size, runs = resident_runs(binary)
with open(directory / "pi-bin.hot", "w") as out:
    out.write(f"{size}\n")
    for offset, length in runs:
        out.write(f"{offset} {length}\n")
print(f"pi-bin.hot: {len(runs)} runs, {sum(r[1] for r in runs) / 1048576:.1f} MB of {size / 1048576:.1f} MB")
