# Benchmark and end-to-end test tools

Every tool takes the builds to compare as `--build name=command`. The command can be an executable (`out/pi-bolt/pi`) or a
command line (`"node packages/coding-agent/dist/bundle/cli.js"`). Each run gets its own scripted model server and a
throwaway Pi home, so runs are isolated from your Pi configuration and from each other.

| Tool | What it does |
|---|---|
| `benchmark.py` | Startup (`--version`), headless (`-p`) and interactive (TUI on a pseudo-terminal) scenarios. Fresh processes, interleaved round-robin. Reports wall, CPU and peak memory. |
| `long_session.py` | One interactive process, many prompts (default 40; 75 makes a conversation of about 4.2M tokens). Reports time and CPU per prompt, memory and request size as the session grows. |
| `large_write.py` | A file written through a tool call whose arguments stream in 16 characters at a time: wall and CPU of `pi -p`, for files of 50 and 200 KB. |
| `long_answer.py` | The CPU it takes to stream Markdown answers of 5,000 to 60,000 characters in the TUI at the pace of a fast model: what a chunk costs must not grow with the length of the answer. |
| `tmux_check.py` | Pi in a real tmux pane against a model streaming at human pace: time to interactive, keystroke latency, paste, streaming CPU and frames, resize, Escape to abort, idle CPU, memory. On Windows it runs `conpty_check.py`. |
| `conpty_check.py` | `tmux_check.py` for Windows, which has no tmux: the same steps and JSONL fields with Pi in a ConPTY (the pseudo-console behind Windows Terminal), plus the p99 frame time while streaming and peak memory. The bytes and frame times are what the console sends on: ConPTY keeps its own screen and sends the changes at most once a console frame. |
| `ui_check.py` | Functional check of the TUI: trust prompt, commands, `/hotkeys`, `/session`, `!` bash, a model turn with tool calls, `/model`, `/quit`. Exit status 1 on any failure. |
| `e2e_tools.py` | Drives every core Pi tool through a scripted model. The transcript and files must be byte-identical to a reference build's. |
| `stress.py` | Load and failure: N Pi processes at once doing large tool work (whose tool results and requests must equal a reference build's), a 2 MB streamed answer, tool arguments a few bytes at a time, a connection cut mid-answer, HTTP 500/429, a stream that is not JSON, SIGINT/SIGTERM while a tool runs, stdout closed early, and a soak of many prompts in one RPC session whose memory floor must not rise. Exit status 1 on any failure. |
| `e2e_screen.py` | A long answer with every kind of Markdown and code block streams into the TUI in tmux. The whole scrollback, text and colors, must be what the reference build shows. |
| `e2e_fullscreen.py` | The fullscreen TUI scrolls the rows that only moved instead of drawing them again. A long answer streams in, the transcript is paged and the window resized: every screen must be what it is when every row is drawn, in tmux and in zmx (`--docker IMAGE`: inside a container). |
| `tmux_load.py` | What Pi costs the multiplexer it runs in: Pi in a tmux pane with a client attached. CPU of Pi and of the tmux server, bytes Pi writes, bytes tmux sends its client. |
| `pauses.py` | The longest stretch in which the TUI writes nothing while it should be drawing (large files written through a tool call, a streamed answer): the program busy with one thing. Also the p99 frame time (time between screen updates), and with `--gc` the garbage collector's pauses (p99, longest): JavaScriptCore's own log (`BUN_JSC_logGC=1`) for Bun builds, a preloaded `perf_hooks` observer (`gc_log.cjs`) for Node, sent to a file instead of the terminal. |
| `plugin_bench.py` | A Pi extension compiled into the executable vs loaded at run time: launch time and the plugin's hot loop. |
| `run-suite.sh` | Runs all of the benchmarks above into one results folder, then `report.py`: what the README and docs/BENCHMARKS.md show. |
| `run-suite.ps1` | The same on Windows: Pi-Bolt, Pi on stock Bun, Node 22 and Node 24; `conpty_check.py` for `tmux_check.py`, and `pauses.py` (frames and GC pauses), `long_answer.py` and `large_write.py` too. Writes `environment.txt` (CPU, Windows build, Defender, power) and `summary.md`. |
| `report.py` | Draws the charts (light and dark SVG) and prints the tables, from a results folder. |
| `fake_model.py`, `fake_model_tools.py`, `fake_model_stress.py` | The scripted OpenAI-compatible model servers the tools start. |
| `harness.py` | Shared pieces: build parsing, model server, Pi home, pseudo-terminal, CPU and memory readings. |
| `fixtures/` | Four Pi source files (MIT, from Pi) that the scripted model asks Pi to read. |
| `floor/floor.c` | The process floor (`benchmark.py --floor`): a minimal native program that takes Pi's arguments and answers each scenario as Pi does. |

`results/` holds the published runs: the raw JSONL of each tool, with a note on what was compared.

Requires Python 3.9+; `tmux_check.py` also needs tmux. Pin runs to idle cores with `--cpus` for stable figures. See
[docs/BENCHMARKS.md](../docs/BENCHMARKS.md) for methodology and results.

## Windows

The tools run on Windows 10 2004 or later with Python from python.org (`winproc.py`): every process in a Job object of its own
(what it starts is counted with it), CPU from the main process's cycles (thread times there advance in 15.6 ms ticks), the TUI in a
ConPTY instead of a pseudo-terminal or tmux. `--cpus` is ignored: Windows has no `taskset`, so keep the machine idle and on AC
power. Memory is reported several ways, because Windows counts it several ways:

| Field | What it is | Closest on Linux |
|---|---|---|
| `peak_mb`, `rss` | (Peak) working set: resident pages, shared ones included | `ru_maxrss`, Rss |
| `private_ws`, `peak_private_ws_mb` | Private working set: resident and the process's own (what Task Manager shows). Windows keeps no peak of it: sampled every 50 ms | Private_Dirty ("own") |
| `own`, `peak_private_mb` | Private bytes, the commit charge: what the process has committed, resident or not | — |
| `system_commit_peak_mb` | The rise of the system's commit charge while the process ran; also counts an executable's image pages that are charged once, when first mapped. System-wide: only meaningful on a quiet machine | — |

Full suite: `powershell -ExecutionPolicy Bypass -File bench\run-suite.ps1 -Out bench\results\<date>-windows` (the builds are
parameters; see the top of the script). `plugin_bench.py --compiled` needs a build with the plugin compiled in:
`scripts\build-pi.ps1 -Plugins examples\plugins\plugins.ts -Out out\pi-bolt-plugins` (and `-Jit on -Out out\pi-bolt-plugins-jit`).
The suite measures those, and `out\pi-bolt-aot-lto-jit` loaded at run time, when they are there, as run-suite.sh does.

### The process floor

Starting a process costs far more on Windows than on Linux: process creation, the loader and Defender's check of a new process
take 10-25 ms and some 2,000 page faults even for a program that only prints a line (under a millisecond on Linux), so the
ratios between builds look closer than what the builds themselves cost. `benchmark.py --floor PATH` adds such a program to
every round, as the build `floor`, and prints each build's time over it; `report.py` then adds rows for the floor and for
each build's figures over it ("over floor": its median less the floor's) to the Time and CPU tables. Without `--floor` the
output is what it was.

`floor/floor.c` takes the arguments Pi gets: `--version` prints a line, `-p` prints the line that ends the scripted model's
answer, and anything else is the TUI's stand-in in the ConPTY: it shows `fake-model` (time to interactive), answers each line
typed as the scripted model ends its nth answer, and exits on `/quit`. `run-suite.ps1` builds it with clang-cl, linked as the
real executable is (`/O2 /MT`, `/DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT /guard:cf`), into `%TEMP%\pibolt-bench-floor`; `-Floor`
takes one built already, `-NoFloor` leaves it out. By hand (from a shell with C:\pb\env.ps1 dot-sourced):

```powershell
clang-cl /nologo /O2 /MT /guard:cf bench\floor\floor.c /Fo$env:TEMP\ /Fe$env:TEMP\floor.exe /link /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT /guard:cf
python bench\benchmark.py --floor $env:TEMP\floor.exe --build pi-bolt=out\pi-bolt-aot-lto\pi.exe --build bun=out\pi-stable-upstream\pi.exe
```
