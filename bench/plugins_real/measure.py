"""Real extensions (installed by bench/plugins_real/install.ps1, each in an agent directory of its own) on Pi-Bolt with the JIT
off, Pi-Bolt with the JIT on, and Pi on stock Bun: whether each loads, what launching with it costs, what a typical action of it
costs, and what it costs per prompt and per frame. Against bench/plugins_real/fake_model.py on 127.0.0.1: no provider is called.

  python measure.py load    [--out FILE]
  python measure.py startup [--runs N] [--out FILE]
  python measure.py actions [--runs N] [--out FILE]
  python measure.py stream  [--rounds N] [--out FILE]
"""
import argparse
import contextlib
import json
import os
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench"))
import winproc  # noqa: E402
from harness import Tty, cpu_ms, memory_mb  # noqa: E402

WORK = ROOT / ".work" / "plugins-real"
BUILDS = {
    "pi-bolt": [str(ROOT / "out" / "pi-bolt" / "pi.exe")],
    "pi-bolt-jit": [str(ROOT / "out" / "pi-bolt-aot-lto-jit" / "pi.exe")],
    "bun": [str(ROOT / "out" / "pi-stable-upstream" / "pi.exe")],
}
EXTENSIONS = ["none", "opensec-pi-subagents", "opensec-pi-todo", "pi-mcp-adapter", "pi-subagents", "pi-powerline-footer",
              "pi-permission-system", "pi-lens", "statusline", "pi-docparser"]
MODEL_ARGS = ["--model", "fake/fake-model"]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@contextlib.contextmanager
def fake_model(tools_out=None, pace_ms=0, results_out=None):
    port = free_port()
    argv = [sys.executable, str(Path(__file__).with_name("fake_model.py")), str(port)]
    if tools_out:
        argv += ["--tools-out", str(tools_out)]
    if pace_ms:
        argv += ["--pace-ms", str(pace_ms)]
    if results_out:
        argv += ["--results-out", str(results_out)]
    proc = subprocess.Popen(argv)
    for _ in range(100):
        with contextlib.suppress(OSError), socket.create_connection(("127.0.0.1", port), timeout=0.1):
            break
        time.sleep(0.05)
    try:
        yield port
    finally:
        proc.kill()
        proc.wait()


def agent_dir(extension, port, build=None):
    """The extension's agent directory, with the fake model and quiet settings added (its `packages` kept). One copy for each
    build: the extensions' code is transformed once and kept in the agent directory (cache\\jiti), keyed by the runtime's
    version, so builds that shared one would each find the other's and transform it again at every start, as no user's does."""
    agent = WORK / extension / "agent"
    if build:
        own = WORK / extension / f"agent-{build}"
        if not own.exists():
            if agent.exists():
                shutil.copytree(agent, own)
            else:
                own.mkdir(parents=True)
        agent = own
    agent.mkdir(parents=True, exist_ok=True)
    settings_path = agent / "settings.json"
    settings = json.loads(settings_path.read_text()) if settings_path.exists() else {}
    settings.update({"lastChangelogVersion": "9999.0.0", "theme": "dark"})
    settings_path.write_text(json.dumps(settings, indent=2))
    models = [{"id": "fake-model", "contextWindow": 200000, "maxTokens": 8192, "input": ["text", "image"]}]
    (agent / "models.json").write_text(json.dumps({"providers": {"fake": {
        "baseUrl": f"http://127.0.0.1:{port}/v1", "api": "openai-completions", "apiKey": "fake", "models": models}}}, indent=2))
    if not (agent / "auth.json").exists():
        (agent / "auth.json").write_text("{}")
    if extension == "pi-mcp-adapter":
        # One server, the echo server, as `pi mcp add` would record it.
        (agent / "mcp.json").write_text(json.dumps({"mcpServers": {"echo": {
            "command": sys.executable, "args": [str(Path(__file__).with_name("echo_mcp.py"))]}}}, indent=2))
    if extension == "pi-permission-system":
        # Reads allowed, the rest asked (its documented example, cut down): every tool call goes through its check.
        config = agent / "extensions" / "pi-permission-system" / "config.json"
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(json.dumps({"permission": {"*": "ask", "path": {"*": "allow"}, "read": "allow"}}, indent=2))
    return agent


def make_pdf(path, pages=300):
    """A text PDF of about 1.5 MB (no compression), for pi-docparser."""
    line = "Pi-Bolt measures what an extension costs when its code is interpreted rather than compiled. "
    objects = []
    kids = []
    for p in range(pages):
        text = "\n".join(f"BT /F1 9 Tf 40 {780 - i * 14} Td ({line} page {p + 1} line {i + 1}) Tj ET" for i in range(52))
        content = text.encode("latin-1")
        objects.append(b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")
        objects.append(None)  # the page, filled in below
        kids.append(len(objects))
    font = len(objects) + 1
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    pages_obj = len(objects) + 1
    for k in kids:
        objects[k - 1] = (b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Contents %d 0 R "
                          b"/Resources << /Font << /F1 %d 0 R >> >> >>" % (pages_obj, k - 1, font))
    objects.append(b"<< /Type /Pages /Kids [" + b" ".join(b"%d 0 R" % k for k in kids) + b"] /Count %d >>" % len(kids))
    objects.append(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_obj)
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += b"".join(b"%010d 00000 n \n" % o for o in offsets)
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, len(objects), xref)
    path.write_bytes(bytes(out))


APP_TS = """export interface Config { name: string; retries: number }

export function parseConfig(text: string): Config {
\tconst [name, retries] = text.split(":");
\treturn { name, retries: Number(retries) || 3 };
}

export class Client {
\tconstructor(private readonly config: Config) {}
\tasync fetchWithRetry(url: string): Promise<string> {
\t\tfor (let i = 0; i < this.config.retries; i++) {
\t\t\ttry { return await (await fetch(url)).text(); } catch {}
\t\t}
\t\tthrow new Error(`gave up on ${url}`);
\t}
}
"""

# What a typical use of each extension is, as the tool calls the model makes (fake_model.py: CALL <tool> <args> ; ...).
ACTIONS = {
    "opensec-pi-todo": 'CALL todo {"action":"create","subject":"write the tests"} ; todo {"action":"list"} ; '
                       'todo {"action":"update","id":1,"status":"completed"}',
    "opensec-pi-subagents": 'CALL Agent {"prompt":"hello","description":"a short task","subagent_type":"general-purpose",'
                            '"run_in_background":false}',
    "pi-subagents": 'CALL subagent {"agent":"worker","task":"hello","async":false}',
    "pi-mcp-adapter": 'CALL mcp {"server":"echo","tool":"echo","args":{"text":"hi"}}',
    "pi-permission-system": 'CALL read {"path":"note.txt"}',
    "pi-lens": 'CALL module_report {"path":"src/app.ts","view":"compact"}',
    # pi-docparser 4.0.0's parse runs its worker as `spawn(process.execPath, [worker])` (native-executor.ts), which in any
    # compiled Pi (Pi-Bolt's or stock Bun's) is pi.exe, not a JavaScript runtime: "Native document worker protocol error"
    # on all three builds. Measured only for loading: python measure.py actions --docparser to see it fail.
}
if "--docparser" in sys.argv:
    ACTIONS["pi-docparser"] = 'CALL document_parse {"path":"doc.pdf","ocr":"off"}'
    sys.argv.remove("--docparser")


def env_for(agent, extra=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith(("BUN_", "NODE_", "PI_"))}
    env.update({"PI_CODING_AGENT_DIR": str(agent), "PI_OFFLINE": "1", "PI_SKIP_VERSION_CHECK": "1", "PI_TELEMETRY": "0",
                "TERM": "xterm-256color"})
    env.update(extra or {})
    return env


@contextlib.contextmanager
def workdir():
    d = Path(tempfile.mkdtemp(prefix="pibolt-plugins-"))
    (d / "note.txt").write_text("a file for the tools\n")
    (d / "src").mkdir()
    (d / "src" / "app.ts").write_text(APP_TS)
    pdf = WORK / "doc.pdf"
    if not pdf.exists():
        make_pdf(pdf)
    shutil.copyfile(pdf, d / "doc.pdf")
    try:
        yield d
    finally:
        shutil.rmtree(d, ignore_errors=True)


def run_headless(build, extension, prompt, port, cwd, timeout=300):
    """One `pi -p PROMPT`: (output text, winproc's result: exit, wall_ms, cpu_ms, job_cpu_ms, peak_private_mb, ...)."""
    agent = agent_dir(extension, port, build)
    argv = [*BUILDS[build], "--no-session", *MODEL_ARGS, "-p", prompt]
    out, result = winproc.run(argv, env=env_for(agent), cwd=str(cwd), timeout=timeout)
    return out.decode("utf-8", "replace"), result


def load(args):
    rows = []
    for extension in EXTENSIONS[1:]:
        for build in BUILDS:
            with workdir() as cwd, tempfile.TemporaryDirectory() as tmp:
                tools = Path(tmp) / "tools.txt"
                with fake_model(tools_out=tools) as port:
                    out, result = run_headless(build, extension, "hello", port, cwd)
                names = tools.read_text().split() if tools.exists() else []
                failed = [line.strip()[:160] for line in out.splitlines() if "Failed to load extension" in line or "rror" in line][:3]
                ok = result["exit"] == 0 and "Done: hello" in out and not any("Failed to load" in f for f in failed)
                row = {"extension": extension, "build": build, "loads": ok, "exit": result["exit"], "wall_ms": round(result["wall_ms"]),
                       "tools": names, "problems": failed}
                rows.append(row)
                print(f"{extension:22} {build:12} {'loads' if ok else 'FAILS'}  exit {result['exit']:<6} {result['wall_ms']:6.0f} ms  "
                      f"{len(names)} tools{'  ' + ' | '.join(failed) if failed else ''}")
    if args.out:
        Path(args.out).write_text("\n".join(json.dumps(r) for r in rows) + "\n")


def actions(args):
    """Each extension's action as `pi -p`, against a plain prompt in the same home: the action's own cost is the difference."""
    rows = []
    # One build after another, each from a warm-up round (round 0, not kept): code transformed for one runtime is kept, by its
    # version, in caches that the three builds share (Pi's in the agent directory, the runtime's own), one entry for each file,
    # so builds taken in turns would each find the other's and transform everything again at every start, as no user's does.
    for build in BUILDS:
        for round_ in range(args.runs + 1):
            for extension, action in ACTIONS.items():
                for kind, prompt in (("plain", "hello"), ("action", action)):
                    with workdir() as cwd, tempfile.TemporaryDirectory() as tmp:
                        results = Path(tmp) / "results.txt"
                        with fake_model(results_out=results) as port:
                            out, r = run_headless(build, extension, prompt, port, cwd)
                        tool_results = results.read_text(encoding="utf-8").splitlines() if results.exists() else []
                        ok = r["exit"] == 0 and "Done:" in out
                        if round_ == 0:
                            if kind == "action":
                                print(f"{extension:22} {build:12} {'ok' if ok else 'FAILED'} (warm-up); "
                                      f"tool said: {(tool_results[-1] if tool_results else '(nothing)')[:150]}")
                            continue
                        rows.append({"round": round_, "extension": extension, "build": build, "kind": kind, "ok": ok,
                                     "wall_ms": r["wall_ms"], "cpu_ms": r["cpu_ms"], "job_cpu_ms": r["job_cpu_ms"],
                                     "peak_private_mb": r["peak_private_mb"], "tool_results": tool_results})
            print(f"actions: {build} round {round_}/{args.runs}{' (warm-up)' if round_ == 0 else ''}", flush=True)
    if args.out:
        Path(args.out).write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    summarize_actions(rows)


def summarize_actions(rows):
    med = statistics.median
    print(f"\n{'extension':22} {'build':12} {'action ms':>10} {'(plain)':>8} {'own ms':>7} {'action CPU':>10} {'own CPU':>8} "
          f"{'job CPU':>8} {'priv MB':>8}")
    for extension in ACTIONS:
        for build in BUILDS:
            def pick(kind, key):
                values = [r[key] for r in rows if r["extension"] == extension and r["build"] == build and r["kind"] == kind and r["ok"]]
                return med(values) if values else float("nan")
            print(f"{extension:22} {build:12} {pick('action', 'wall_ms'):10.0f} {pick('plain', 'wall_ms'):8.0f} "
                  f"{pick('action', 'wall_ms') - pick('plain', 'wall_ms'):7.0f} {pick('action', 'cpu_ms'):10.0f} "
                  f"{pick('action', 'cpu_ms') - pick('plain', 'cpu_ms'):8.0f} {pick('action', 'job_cpu_ms'):8.0f} "
                  f"{pick('action', 'peak_private_mb'):8.0f}")


def tui(args):
    """The TUI with each extension installed: time to interactive, then five prompts whose replies stream (a word every 10 ms),
    with the CPU of each and the gaps between the screen's writes while it streams (the frames), then the peak private bytes."""
    rows = []
    # One build after another, each from a warm-up round (round 0, not kept), as in actions().
    for build in BUILDS:
        for round_ in range(args.rounds + 1):
            for extension in EXTENSIONS:
                with workdir() as cwd, fake_model(pace_ms=10) as port:
                    agent = agent_dir(extension, port, build)
                    began = time.perf_counter()
                    tty = Tty([*BUILDS[build], "--no-session", *MODEL_ARGS], env_for(agent), str(cwd), cols=160, rows=48)
                    deadline = began + 180
                    row = {"round": round_, "extension": extension, "build": build, "ok": False}
                    if tty.wait_for("fake-model", 0, deadline):
                        row["tti_ms"] = (time.perf_counter() - began) * 1e3
                        tty.settle(0.5, deadline)
                        cpus, gaps = [], []
                        for n in range(1, 6):
                            start, cpu0, last = len(tty.buf), cpu_ms(tty.pid), time.perf_counter()
                            tty.send(f"STREAM 120 n{n}".encode() + b"\r")
                            marker = f"Done: streamed n{n}.".encode()
                            while time.perf_counter() < deadline:
                                if tty.pump(0.002):
                                    now = time.perf_counter()
                                    gaps.append((now - last) * 1e3)
                                    last = now
                                    if marker in tty.buf[start:]:
                                        break
                            cpus.append(cpu_ms(tty.pid) - cpu0)
                            tty.settle(0.2, deadline)
                        gaps.sort()
                        row.update(ok=True, cpu_per_prompt_ms=statistics.median(cpus),
                                   frame_p99_ms=gaps[int(len(gaps) * 0.99)] if gaps else None, frames=len(gaps))
                    status, ru = tty.quit()
                    row["peak_private_mb"] = ru.result.get("peak_private_mb") if hasattr(ru, "result") else None
                    row["cpu_ms"] = ru.result.get("cpu_ms") if hasattr(ru, "result") else None
                    if round_:
                        rows.append(row)
                    print(f"{round_}/{args.rounds} {extension:22} {build:12} "
                          + (f"tti {row['tti_ms']:6.0f} ms  cpu/prompt {row['cpu_per_prompt_ms']:6.0f} ms  frame p99 "
                             f"{row['frame_p99_ms']:5.1f} ms  private {row['peak_private_mb']} MB" if row["ok"] else "FAILED"), flush=True)
    if args.out:
        Path(args.out).write_text("\n".join(json.dumps(r) for r in rows) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["load", "actions", "tui"])
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--rounds", type=int, default=7)
    ap.add_argument("--out")
    args = ap.parse_args()
    {"load": load, "actions": actions, "tui": tui}[args.mode](args)


if __name__ == "__main__":
    main()
