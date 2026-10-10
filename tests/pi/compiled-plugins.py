"""OpenSec's extensions compiled in (plugins/opensec/plugins.ts): they are there with nothing installed, their actions work,
`-builtin:<name>` in the `extensions` setting turns one off, a copy installed from npm replaces the compiled one without a
conflict, and opensec-pi-todo's translations are written to the agent directory's cache. Uses bench/plugins_real's fake model
and, for the npm copy, the agent directory bench\\plugins_real\\install.ps1 made (.work\\plugins-real\\opensec-pi-todo).
  python tests/pi/compiled-plugins.py PI
"""
import json
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "bench" / "plugins_real"))
import measure  # noqa: E402

exe = sys.argv[1]
measure.BUILDS = {"under-test": [exe]}
failures = 0


def check(name, ok, detail=""):
    global failures
    print(f"{'PASS' if ok else 'FAIL'} {name}{'' if ok else f': {detail}'}")
    failures += 0 if ok else 1


def run(agent_name, prompt, settings=None):
    """`pi -p PROMPT` with the agent directory `agent_name` (fresh unless it names an existing one): tools offered, tool results."""
    with measure.workdir() as cwd, tempfile.TemporaryDirectory() as tmp:
        tools, results = Path(tmp) / "tools.txt", Path(tmp) / "results.txt"
        with measure.fake_model(tools_out=tools, results_out=results) as port:
            agent = measure.agent_dir(agent_name, port, "under-test")  # (the build's own copy, which run_headless() uses)
            if settings is not None:
                current = json.loads((agent / "settings.json").read_text())
                current.update(settings)
                (agent / "settings.json").write_text(json.dumps(current))
            out, r = measure.run_headless("under-test", agent_name, prompt, port, cwd)
        names = tools.read_text().split() if tools.exists() else []
        said = results.read_text(encoding="utf-8").splitlines() if results.exists() else []
        return out, r, names, said, agent


home = "compiled-test-home"
shutil.rmtree(measure.WORK / home, ignore_errors=True)
shutil.rmtree(measure.WORK / "opensec-pi-todo" / "agent-under-test", ignore_errors=True)
out, r, tools, _, agent = run(home, "hello")
check("both compiled-in extensions are there with nothing installed", r["exit"] == 0 and "todo" in tools and "Agent" in tools,
      f"exit {r['exit']}, tools {tools}, {out[-200:]}")
check("opensec-pi-todo's translations are written once to the agent directory's cache",
      (agent / "cache" / "opensec-pi-todo-2.13.0" / "locales" / "de.json").exists(), "no locales in the cache")
out, r, _, said, _ = run(home, measure.ACTIONS["opensec-pi-todo"])
check("the todo action works", r["exit"] == 0 and any("completed" in s for s in said), f"{said[-1:] or out[-200:]}")
out, r, _, said, _ = run(home, measure.ACTIONS["opensec-pi-subagents"])
check("the subagent action works", r["exit"] == 0 and any("Agent completed" in s for s in said), f"{said[-1:] or out[-200:]}")
out, r, tools, _, _ = run(home, "hello", {"extensions": ["-builtin:opensec-pi-todo"]})
check("-builtin:opensec-pi-todo in the extensions setting turns it off (and only it)",
      r["exit"] == 0 and "todo" not in tools and "Agent" in tools, f"tools {tools}")
shutil.rmtree(measure.WORK / home, ignore_errors=True)
if (measure.WORK / "opensec-pi-todo" / "agent").exists():
    out, r, tools, said, _ = run("opensec-pi-todo", measure.ACTIONS["opensec-pi-todo"])
    check("an npm copy of opensec-pi-todo replaces the compiled one: no conflict, one todo tool, it works",
          r["exit"] == 0 and tools.count("todo") == 1 and "conflict" not in out.lower() and any("completed" in s for s in said),
          f"exit {r['exit']}, tools {tools}, {out[-300:]}")
sys.exit(1 if failures else 0)
