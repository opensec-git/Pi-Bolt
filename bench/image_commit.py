#!/usr/bin/env python3
"""Windows: what an executable's image costs the system's commit charge, apart from what its processes use.

An executable's image section is charged to the system's commit for its writable and uninitialized pages when it is first mapped,
and stays charged while Windows keeps the image cached: after the process has exited, and for as long as the file is not
changed. Process private bytes do not show it. For each executable: its cached image section is dropped (opening the file for
writing makes Windows let go of it; nothing is written), it is run once (`--version`), and what the system's commit charge still
has of it a moment after it exited is measured. Repeated, on a quiet machine; the median.

  python bench/image_commit.py [--runs 5] EXE [EXE...]
"""
import argparse
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import winproc  # noqa: E402


def drop_cached_image(path: Path):
    with open(path, "r+b"):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("exes", nargs="+")
    a = ap.parse_args()
    print(f"{'executable':48} {'image commit MB':>16} {'process private MB':>19}   (medians of {a.runs})")
    for exe in map(Path, a.exes):
        image, private = [], []
        for _ in range(a.runs):
            drop_cached_image(exe)
            time.sleep(1)
            before = winproc.system_commit_mb()
            _, r = winproc.run([str(exe), "--version"])
            time.sleep(1)
            image.append(winproc.system_commit_mb() - before)
            private.append(r["peak_private_mb"])
        print(f"{str(exe)[-48:]:48} {statistics.median(image):16.0f} {statistics.median(private):19.1f}")


if __name__ == "__main__":
    main()
