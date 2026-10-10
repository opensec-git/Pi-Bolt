# OpenSec's extensions compiled in: what they cost (2026-10-10)

Windows 11, the machine in `../2026-10-10-windows/environment.txt`. Both builds use runtime `Bun 1.4.3-canary.1+1ad2e13f5`, JIT
off, and the model is bench's local fake model on 127.0.0.1.

- **without**: `scripts\build-pi.ps1` (the build that `out\pi-bolt.old-20261010125512` keeps).
- **with OpenSec**: `scripts\build-pi.ps1 -Plugins plugins\opensec\plugins.ts`, which compiles in opensec-pi-subagents 0.20.0 and
  opensec-pi-todo 2.13.0. The profile is trained with them, and this is the release build. The A/B ran on `.work\opensec-test4`,
  the same sources built without `-VerifyDeterminism`.

## Pi itself, with nothing installed (`startup-headless-interactive.jsonl`)

Medians of 11 runs after 2 warm-ups (`bench\benchmark.py --scenarios startup,headless,interactive`).

|  | without | with OpenSec |
|---|---:|---:|
| `pi-bolt --version` | 35 ms | 36 ms |
| `pi-bolt -p`: one prompt, 4 tool calls | 114 ms | 180 ms |
| `pi-bolt -p`: CPU, all threads | 94 ms | 141 ms |
| `pi-bolt -p`: peak private bytes | 77 MB | 86 MB |
| Launch to interactive (TUI) | 97 ms | 95 ms |
| Interactive, 5 prompts: CPU, all threads | 281 ms | 328 ms |
| Interactive: peak private bytes | 113 MB | 131 MB |
| Executable | 243 MB | 271 MB |

`--version` is answered before either extension is loaded, as it is in Pi's own entry.

Almost all of the `-p` cost is opensec-pi-subagents loading itself. It costs the same when it is installed from npm: in
`../2026-10-10-plugins-real`, a plain prompt with only it installed takes 177 ms, against 118 ms with only opensec-pi-todo.
The time to an interactive screen doesn't change, because Pi draws the screen while extensions are still loading.

To turn either one off, put `-builtin:opensec-pi-subagents` or `-builtin:opensec-pi-todo` in the `extensions` setting
(docs/PLUGINS.md).

## Their actions: compiled in vs. installed from npm (`actions.jsonl`)

This is `bench\plugins_real\compiled.py`: medians of 7 runs after a warm-up, with 0 failures. "npm" is the build without
OpenSec, using the agent directory that `bench\plugins_real\install.ps1` makes for that one extension. "compiled" is the release
build with nothing installed, so both extensions are loaded. "own" is the action prompt minus a plain prompt in the same setup.

| extension | way | action | (plain) | own | own CPU | job CPU | peak private |
|---|---|---:|---:|---:|---:|---:|---:|
| opensec-pi-todo: create, list, update | npm | 126 ms | 118 ms | 8 ms | 8 ms | 125 ms | 76 MB |
| | compiled | 162 ms | 148 ms | 14 ms | 10 ms | 125 ms | 74 MB |
| opensec-pi-subagents: one subagent | npm | 251 ms | 177 ms | 74 ms | 26 ms | 203 ms | 82 MB |
| | compiled | 223 ms | 153 ms | 70 ms | 20 ms | 172 ms | 73 MB |

Each action costs about the same either way. Having both compiled in costs less than installing opensec-pi-subagents alone from
npm, which comes to 153 ms against 177 ms for a plain prompt, and 172 ms against 203 ms of CPU for a subagent action. The
compiled todo rows are higher than npm only because the compiled build also loads opensec-pi-subagents.
