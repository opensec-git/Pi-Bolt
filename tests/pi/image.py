"""Pi resizes an image it is given in a worker (utils/image-resize.ts): `pi -p @big.png ...` against bench's local fake model must
answer, not end the process. On Windows the first worker ended it (0xC0000409) while the static region had room for one realm.
  python tests/pi/image.py PI
"""
import struct
import subprocess
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "bench"))
from harness import MODEL_ARGS, fake_model, pi_env, pi_home, workdir  # noqa: E402


def png(width, height):
    row = b"\x00" + bytes((x * 7) & 0xFF for x in range(width * 3))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height, 6)) + chunk(b"IEND", b""))


def main():
    exe = sys.argv[1]
    failed = 0
    for round_ in range(3):
        with fake_model() as port, pi_home(port) as home, workdir() as cwd:
            (cwd / "big.png").write_bytes(png(3000, 2000))
            p = subprocess.run([exe, "--no-session", *MODEL_ARGS, "-p", "@big.png", "What is in this image?"], cwd=cwd,
                               env=pi_env(home), capture_output=True, timeout=300)
            out = (p.stdout + p.stderr).decode("utf-8", "replace")
            if p.returncode != 0 or "Done:" not in out:
                failed += 1
                last = f"exit {p.returncode & 0xFFFFFFFF:#x}: {out.strip()[-300:]}"
    if failed:
        print(f"FAIL an image given to Pi (resized in a worker): {failed} of 3 runs\n   {last}")
        sys.exit(1)
    print("PASS an image given to Pi (resized in a worker, 3 runs)")


main()
