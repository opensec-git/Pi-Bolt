"""OpenSec's extensions compiled into Pi-Bolt (scripts\\build-pi.ps1 -Plugins plugins\\opensec\\plugins.ts) against the same
extensions installed from npm on the build without them: what their actions cost each way. Against fake_model.py on 127.0.0.1.

  python compiled.py WITHOUT_EXE WITH_EXE [--runs N] [--out FILE]
"""
import argparse
import json
import statistics
import tempfile
from pathlib import Path

import measure
from measure import ACTIONS, agent_dir, env_for, fake_model, workdir, winproc

EXTENSIONS = ["opensec-pi-todo", "opensec-pi-subagents"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("without")
    parser.add_argument("with_")
    parser.add_argument("--runs", type=int, default=7)
    parser.add_argument("--out")
    args = parser.parse_args()
    # npm: the build without them, in the agent directory install.ps1 made for each; compiled: the build with them, in an agent
    # directory with nothing installed.
    ways = {"npm": (args.without, lambda ext, port: agent_dir(ext, port, "npm")),
            "compiled": (args.with_, lambda ext, port: agent_dir("none", port, "compiled"))}
    rows = []
    # One way after another, each from a warm-up round (round 0, not kept), as measure.py actions does.
    for way, (exe, agent_for) in ways.items():
        for round_ in range(args.runs + 1):
            for extension in EXTENSIONS:
                for kind, prompt in (("plain", "hello"), ("action", ACTIONS[extension])):
                    with workdir() as cwd, tempfile.TemporaryDirectory() as tmp:
                        results = Path(tmp) / "results.txt"
                        with fake_model(results_out=results) as port:
                            argv = [exe, "--no-session", *measure.MODEL_ARGS, "-p", prompt]
                            out, r = winproc.run(argv, env=env_for(agent_for(extension, port)), cwd=str(cwd), timeout=300)
                        out = out.decode("utf-8", "replace")
                        tool_results = results.read_text(encoding="utf-8").splitlines() if results.exists() else []
                        ok = r["exit"] == 0 and "Done:" in out
                        if round_ == 0:
                            if kind == "action":
                                print(f"{extension:22} {way:9} {'ok' if ok else 'FAILED'} (warm-up); "
                                      f"tool said: {(tool_results[-1] if tool_results else '(nothing)')[:150]}")
                            continue
                        rows.append({"round": round_, "extension": extension, "way": way, "kind": kind, "ok": ok,
                                     "wall_ms": r["wall_ms"], "cpu_ms": r["cpu_ms"], "job_cpu_ms": r["job_cpu_ms"],
                                     "peak_private_mb": r["peak_private_mb"], "tool_results": tool_results})
            print(f"{way} round {round_}/{args.runs}{' (warm-up)' if round_ == 0 else ''}", flush=True)
    if args.out:
        Path(args.out).write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    med = statistics.median
    print(f"\n{'extension':22} {'way':9} {'action ms':>10} {'(plain)':>8} {'own ms':>7} {'action CPU':>10} {'own CPU':>8} "
          f"{'job CPU':>8} {'priv MB':>8} {'failed':>6}")
    for extension in EXTENSIONS:
        for way in ways:
            def pick(kind, key):
                values = [r[key] for r in rows if r["extension"] == extension and r["way"] == way and r["kind"] == kind and r["ok"]]
                return med(values) if values else float("nan")
            failed = sum(1 for r in rows if r["extension"] == extension and r["way"] == way and not r["ok"])
            print(f"{extension:22} {way:9} {pick('action', 'wall_ms'):10.0f} {pick('plain', 'wall_ms'):8.0f} "
                  f"{pick('action', 'wall_ms') - pick('plain', 'wall_ms'):7.0f} {pick('action', 'cpu_ms'):10.0f} "
                  f"{pick('action', 'cpu_ms') - pick('plain', 'cpu_ms'):8.0f} {pick('action', 'job_cpu_ms'):8.0f} "
                  f"{pick('action', 'peak_private_mb'):8.0f} {failed:6}")


if __name__ == "__main__":
    main()
