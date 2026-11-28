"""Sample sleeping processes externally. Linux's process CPU clock includes threads that exit during the wait."""
import argparse
import contextlib
import ctypes
import json
import os
from pathlib import Path
import sys
import tempfile
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness as h


def cpu_reader(pid):
    if h.MACOS:
        return lambda: h.cpu_ms(pid)
    clock_id = ctypes.c_int()
    err = ctypes.CDLL(None).clock_getcpuclockid(pid, ctypes.byref(clock_id))
    if err:
        raise OSError(err, os.strerror(err))
    return lambda: time.clock_gettime_ns(clock_id.value) / 1e6


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--pig", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=150)
    args = parser.parse_args()
    settings = {"default": {}, "one-marker": {"BUN_JSC_numberOfGCMarkers": "1"},
                "serial": {"BUN_JSC_useConcurrentGC": "0"}, "large-tick": {"BUN_GC_TICK_MIN_KB": "16384"},
                "immediate-purge": {"MIMALLOC_PURGE_DELAY": "0"}, "pig": {}}
    with contextlib.ExitStack() as stack:
        port = stack.enter_context(h.fake_model())
        procs = {}
        for label, extra in settings.items():
            home = stack.enter_context(h.pi_home(port))
            cwd = stack.enter_context(h.workdir())
            user_home = stack.enter_context(tempfile.TemporaryDirectory())
            env = h.pi_env(home, dict(extra, HOME=user_home))
            binary = args.pig if label == "pig" else args.candidate
            tty = h.Tty([str(binary.resolve()), "--no-session", *h.MODEL_ARGS], env, cwd)
            stack.callback(tty.quit)
            deadline = time.monotonic() + 45
            assert tty.wait_for("fake-model", 0, deadline)
            tty.send(h.PROMPT.encode() + b"\r")
            assert tty.wait_for(h.done(1), 0, deadline)
            tty.settle(2, deadline)
            cpu = cpu_reader(tty.pid)
            procs[label] = (tty, time.monotonic(), cpu, cpu(), len(tty.buf))
        # Setup is sequential. Each observation is relative to its own baseline.
        for second in [15, 30, args.seconds]:
            for label, (tty, start, cpu, cpu0, offset) in procs.items():
                while (remaining := start + second - time.monotonic()) > 0:
                    tty.pump(remaining)
                tty.pump(0)
                elapsed = time.monotonic() - start
                print(json.dumps(dict(label=label, target_s=second, elapsed_s=elapsed,
                                      cpu_ms_per_s=(cpu()-cpu0)/elapsed, private_mib=h.memory_mb(tty.pid)["own"],
                                      terminal_bytes=len(tty.buf)-offset)), flush=True)


if __name__ == "__main__":
    main()
