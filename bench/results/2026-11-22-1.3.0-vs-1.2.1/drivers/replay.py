"""Record the normal provider exchange once, then check every byte before replaying each recorded SSE response."""
import argparse
import hashlib
from http.server import ThreadingHTTPServer
import io
import json
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fake_model
import harness as h


class Handler(fake_model.Handler):
    def setup(self):
        super().setup()
        self.server.connections += 1
        if isinstance(self.connection, ssl.SSLSocket):
            self.server.tls = {"version": self.connection.version(), "cipher": self.connection.cipher()[0]}

    def do_POST(self):
        server = self.server
        length = int(self.headers["Content-Length"])
        if server.recording:
            body = self.rfile.read(length)
            digest = hashlib.sha256(body).digest()
            reader, writer = self.rfile, self.wfile
            capture = io.BytesIO()
            self.rfile, self.wfile = io.BytesIO(body), capture
            try:
                super().do_POST()
            finally:
                self.rfile, self.wfile = reader, writer
            response = capture.getvalue()
            server.reference.append((digest, response))
        else:
            digest = hashlib.sha256()
            remaining = length
            while remaining:
                chunk = self.rfile.read(min(256 * 1024, remaining))
                if not chunk:
                    raise EOFError("Short model request")
                digest.update(chunk)
                remaining -= len(chunk)
            expected, response = server.reference[server.index]
            if digest.digest() != expected:
                server.failure = f"Request {server.index + 1} differs from the reference"
                self.send_error(400, server.failure)
                return
        # The next request or the final visible answer can arrive as soon as we send these bytes.
        # Finish recording/checking this exchange before making the response visible.
        server.total_bytes += length
        server.last_bytes = length
        server.index += 1
        self.wfile.write(response)
        self.wfile.flush()


def measure(build, server, env, cwd, turns, cpus, sample_threads=None, quiet_after=None):
    server.index = server.connections = server.total_bytes = server.last_bytes = 0
    server.tls = None
    argv = h.pinned([*build.argv, "--no-session", "--system-prompt", "Inspect.", "--tools", "read", *h.MODEL_ARGS], cpus)
    tty = h.Tty(argv, env, cwd)
    elapsed = 0
    quiet = None
    pause_cpu = 0
    try:
        assert tty.wait_for("fake-model", 0, time.monotonic() + 20)
        assert tty.settle(.1, time.monotonic() + 5)
        before = sample_threads(tty.pid) if sample_threads else {}
        cpu = h.cpu_ms(tty.pid)
        for i in range(1, turns + 1):
            for name in fake_model.FILES:
                with (cwd/name).open("r+b") as f:
                    f.write(f"// {i:08d}".encode())
            pos = len(tty.buf)
            start = time.monotonic()
            tty.send(h.PROMPT.encode() + b"\r")
            assert tty.wait_for(h.done(i), pos, start + 15), server.failure or tty.screen_text(pos)[-500:]
            latency = 1000 * (time.monotonic() - start)
            elapsed += latency / 1000
            if i == quiet_after:
                offset = len(tty.buf)
                pause_clock = h.cpu_ms(tty.pid)
                immediate_mib = h.memory_mb(tty.pid)["own"]
                readings = []
                for seconds in (2, 15):
                    at, cpu_start = time.monotonic(), h.cpu_ms(tty.pid)
                    until = at + seconds
                    while (remaining := until - time.monotonic()) > 0:
                        tty.pump(min(.2, remaining))
                    readings.append(dict(duration_s=time.monotonic()-at, cpu_ms=h.cpu_ms(tty.pid)-cpu_start,
                                         private_mib=h.memory_mb(tty.pid)["own"]))
                quiet = dict(after_turn=i, immediate_mib=immediate_mib, windows=readings,
                             terminal_bytes=len(tty.buf)-offset)
                pause_cpu = h.cpu_ms(tty.pid) - pause_clock
            elif quiet and i == quiet_after + 1:
                quiet["next_answer_ms"] = latency
        assert server.index == turns * 5 and not server.failure, (server.index, turns * 5, server.failure)
        result = dict(build=build.name, requests=server.index, cpu_ms=(h.cpu_ms(tty.pid)-cpu-pause_cpu)/turns,
                      wall_ms=1000*elapsed/turns, private_mib=h.memory_mb(tty.pid)["own"])
        if server.tls:
            result["transport"] = dict(**server.tls, connections=server.connections, total_bytes=server.total_bytes,
                                       last_bytes=server.last_bytes)
        if quiet:
            result["quiet"] = quiet
        if sample_threads:
            costs = {}
            for tid, (name, ns) in sample_threads(tty.pid).items():
                previous = before.get(tid, (name, 0))[1]
                if ns >= previous:
                    costs[name] = costs.get(name, 0) + (ns - previous) / (1e6 * turns)
            result["thread_cpu_ms_per_prompt"] = costs
        return result
    finally:
        status, _ = tty.quit()
        assert status == 0


p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--build", required=True, action="append")
p.add_argument("--turns", type=int, default=100)
p.add_argument("--rounds", type=int, default=4)
p.add_argument("--cpus")
p.add_argument("--tls", action="store_true", help="Use a locally generated certificate and verify HTTPS requests.")
p.add_argument("--threads", action="store_true", help="Read native thread CPU counters at the two session boundaries.")
p.add_argument("--quiet-after", type=int, help="Measure 2 s of settling and 15 s of idle after this prompt.")
p.add_argument("--rotate-order", action="store_true", help="Rotate all clients through each launch position.")
args = p.parse_args()
if args.quiet_after is not None and not (0 < args.quiet_after < args.turns):
    p.error("the pause must have a prompt before and after it")
if args.quiet_after is not None and args.threads:
    p.error("per-thread active CPU and quiet windows use separate trials")
sample_threads = None
if args.threads:
    p.error("--threads is not part of this copy of the driver")
builds = h.parse_builds(args.build)
with (ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server, h.pi_home(server.server_port) as agent,
      tempfile.TemporaryDirectory() as home, tempfile.TemporaryDirectory() as tls_dir, h.workdir() as cwd):
    extra = {"HOME": home}
    if args.tls:
        cert, key = Path(tls_dir)/"cert.pem", Path(tls_dir)/"key.pem"
        subprocess.run(["openssl", "req", "-nodes", "-x509", "-newkey", "rsa:2048", "-keyout", str(key),
                        "-out", str(cert), "-days", "1", "-subj", "/CN=127.0.0.1", "-addext",
                        "subjectAltName=IP:127.0.0.1"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        models = agent/"models.json"
        config = json.loads(models.read_text())
        for provider in config["providers"].values():
            url = provider["baseUrl"]
            assert url.startswith(f"http://127.0.0.1:{server.server_port}/") or url == f"http://127.0.0.1:{server.server_port}"
            provider["baseUrl"] = "https:" + url.removeprefix("http:")
        models.write_text(json.dumps(config))
        extra["NODE_EXTRA_CA_CERTS"] = str(cert)
    env = h.pi_env(agent, extra)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    server.reference, server.failure, server.recording = [], None, True
    thread.start()
    try:
        measure(builds[0], server, env, cwd, args.turns, args.cpus)
        server.recording = False
        for i in range(args.rounds):
            if args.rotate_order:
                shift = i % len(builds)
                order = builds[shift:] + builds[:shift]
            else:
                order = builds if i % 2 == 0 else reversed(builds)
            for build in order:
                result = measure(build, server, env, cwd, args.turns, args.cpus, sample_threads, args.quiet_after)
                print(json.dumps(dict(trial=i, **result)), flush=True)
    finally:
        server.shutdown()
        thread.join()
