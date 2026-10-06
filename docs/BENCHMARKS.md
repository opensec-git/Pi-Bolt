# Benchmarks

Pi-Bolt compared with the same Pi release on stock Bun and on Node.js. Every figure comes from the tools in
[`bench/`](../bench), and the raw results are in [`bench/results/`](../bench/results). The charts are drawn from those files by
[`bench/report.py`](../bench/report.py).

**These are measurements, not guarantees.** Each set of results comes from one machine: an AMD EPYC 7B13 server for Linux and an
M5 MacBook Air for macOS. On other hardware the milliseconds will differ, and so can the load, the terminal and the disk. What
carries over is the comparison, because every run interleaves the runtimes on the same machine: Pi-Bolt starts two to three
times sooner than Pi on Bun and uses about a third of its CPU over an interactive session. The ratio varies by scenario: see
each table.

- [Results](#results)
- [macOS on Apple silicon](#macos-on-apple-silicon)
- [With a real model](#with-a-real-model)
- [Setup and method](#setup-and-method)
- [In a real terminal (tmux)](#in-a-real-terminal-tmux)
  - [What Pi writes to the terminal](#what-pi-writes-to-the-terminal)
- [Long answers and large files](#long-answers-and-large-files)
- [Plugins](#plugins)
- [Questions](#questions)
  - [Why is a run-time plugin's loop 1,080 ms on Pi-Bolt and 38 ms on Bun?](#why-is-a-run-time-plugins-loop-1080-ms-on-pi-bolt-and-38-ms-on-bun)
  - [Did Pi-Bolt get slower when it was made production-ready?](#did-pi-bolt-get-slower-when-it-was-made-production-ready)
  - [How was the streaming gap closed?](#how-was-the-streaming-gap-closed)
- [Reproduce](#reproduce)
- [Correctness](#correctness)

## Results

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/bench-speed-dark.svg">
  <img alt="Time: launch to interactive, pi --version, one prompt, and time per prompt in a long session, for Pi-Bolt, Bun and Node" src="images/bench-speed-light.svg">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/bench-cpu-dark.svg">
  <img alt="CPU time of an interactive session, one prompt, pi --version, and per prompt in a long session" src="images/bench-cpu-light.svg">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/bench-memory-dark.svg">
  <img alt="Peak memory, own memory in tmux and after a long session, and CPU while replies stream" src="images/bench-memory-light.svg">
</picture>

Pi-Bolt 0.6.1 on Linux, against Pi 1.0.0 as released on stock Bun 1.4.2, on Node 22 and on Node 24:

| Scenario | Metric | Pi-Bolt | Pi-Bolt, JIT on | Bun 1.4.2 | Node 22 | Node 24 |
|---|---|---:|---:|---:|---:|---:|
| `pi --version` | wall | **14 ms** | 15 ms | 82 ms | 228 ms | 228 ms |
| | CPU | **15 ms** | 15 ms | 145 ms | 287 ms | 293 ms |
| `pi -p "<prompt>"`: one prompt, 5 model turns, 4 tool calls | wall | **79 ms** | 80 ms | 172 ms | 404 ms | 400 ms |
| | CPU | **81 ms** | 84 ms | 329 ms | 572 ms | 593 ms |
| Interactive TUI: launch, 5 prompts (25 model turns), `/quit` | time to interactive | **45 ms** | 46 ms | 128 ms | 303 ms | 307 ms |
| | CPU | 303 ms | **297 ms** | 844 ms | 1,242 ms | 1,233 ms |
| | peak memory | **147 MB** | 156 MB | 204 MB | 214 MB | 236 MB |
| Long session: 75 prompts, 300 tool calls, a conversation of about 4.2M tokens | time per prompt, last 25 | **562 ms** | | 704 ms | 965 ms | 912 ms |
| | CPU per prompt, last 25 | **301 ms** | | 507 ms | 823 ms | 760 ms |
| | own memory at the end | **201 MB** | | 241 MB | 601 MB | 613 MB |

Medians: 21 runs of each scenario (after 3 warm-up runs), and 3 long sessions per runtime. The long-session memory is a snapshot
after the last prompt, so it depends on when the garbage collector last ran. Across the three sessions it was:
- 186, 201 and 283 MB for Pi-Bolt;
- 241, 241 and 298 MB for Bun;
- 521, 601 and 602 MB for Node 22;
- 537, 613 and 622 MB for Node 24.

Pi-Bolt and Bun overlap there, and Node uses two and a half to three times as much. In the long session every model turn sends
the whole conversation, 17 MB at the end: its time per prompt is mostly that, on every runtime.

The charts above are drawn from these measurements,
[`bench/results/2026-10-04-pi-bolt-0.6.1`](../bench/results/2026-10-04-pi-bolt-0.6.1), with `bench/report.py`.

**0.6.1 against earlier releases**, Pi-Bolt only, the same method (medians of 21 interleaved runs):

| | 0.5.2 | 0.6.0 | 0.6.1 |
|---|---:|---:|---:|
| `pi --version` | 54 ms | 17â€“18 ms | 14â€“17 ms |
| `pi -p`, one prompt | 138 ms | 88â€“102 ms | 79â€“85 ms |
| Time to interactive | 85 ms | 47â€“49 ms | 45â€“47 ms |
| Interactive TUI, CPU | 378 ms | 306â€“311 ms | 303â€“307 ms |

Ranges are the sessions they were measured in. Side by side, 0.6.1 measures the same as 0.6.0 on Linux (within 3%). Its
changes are the macOS process spawning, the `pi-bolt` command name and the installer's extensions.

**Measured again for 0.5.2.** Up to 0.5.1 these tables came from tools that waited for the end of an answer by its last words.
In fullscreen mode a screen that is drawn again shows the answers before it too, and the tools took one of those for the one
they waited for: from the second prompt of a session on, the next prompt was sent while the one before was still being
answered, and cut it short, on every runtime. The interactive and long-session rows were of sessions that did less than they
say (the long session reached 2.7M tokens, not 4.2M). Answers are now numbered, and every figure here was taken again.

## macOS on Apple silicon

The same tools on an Apple M5 MacBook Air (10 cores, 16 GB, macOS 27.0.1), against Pi 1.0.0 as released on Bun 1.4.2 and its npm
package on Node 26.10. Pi-Bolt is 0.6.1. macOS cannot pin processes to cores, so runs are interleaved. "Own memory" and "peak
footprint" are the physical footprint (what Activity Monitor shows), the closest measure to private dirty pages on Linux; "peak
memory" is the resident peak, which also counts clean pages of the executable and freed memory the kernel may take back. Raw data:
[`bench/results/2026-10-04-darwin-arm64-0.6.1-vs-pi-1.0.0`](../bench/results/2026-10-04-darwin-arm64-0.6.1-vs-pi-1.0.0).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/darwin-arm64/bench-hero-dark.svg">
  <img alt="macOS: ready to type 31 / 63 / 193 ms; CPU per session 126 / 363 / 544 ms; CPU while streaming 555 / 629 / 898 ms; memory after a long session 64 / 95 / 1,890 MB" src="images/darwin-arm64/bench-hero-light.svg">
</picture>

| Scenario | Metric | Pi-Bolt | Pi-Bolt, JIT on | Bun 1.4.2 | Node 26 |
|---|---|---:|---:|---:|---:|
| `pi --version` | wall | **12 ms** | 13 ms | 33 ms | 157 ms |
| | CPU | **8 ms** | 9 ms | 58 ms | 166 ms |
| `pi -p "<prompt>"`: one prompt, 5 model turns, 4 tool calls | wall | **37 ms** | 38 ms | 69 ms | 225 ms |
| | CPU | **32 ms** | 34 ms | 138 ms | 290 ms |
| Interactive TUI: launch, 5 prompts (25 model turns), `/quit` | time to interactive | 31 ms | **29 ms** | 63 ms | 193 ms |
| | CPU | 126 ms | **125 ms** | 363 ms | 544 ms |
| | peak footprint | **52 MB** | 52 MB | 142 MB | 207 MB |
| | peak memory | **106 MB** | 107 MB | 206 MB | 240 MB |
| Long session: 75 prompts, a conversation of about 4.2M tokens | time per prompt, last 25 | **209 ms** | | 280 ms | 349 ms |
| | CPU per prompt, last 25 | **70 ms** | | 158 ms | 265 ms |
| | own memory at the end | **64 MB** | | 95 MB | 1,890 MB |
| tmux: replies streaming at human pace (4 prompts) | CPU | **555 ms** | | 629 ms | 898 ms |
| | own memory at the end | **31 MB** | | 85 MB | 161 MB |
| | bytes written to the terminal per prompt | **155 KB** | | 341 KB | 341 KB |
| | CPU while idle, per second | **0.4 ms** | | 3.8 ms | 0.5 ms |
| tmux with a client: a 20,000-character answer | written to the pane | **547 KB** | | 3,594 KB | 3,594 KB |
| | CPU of Pi | **1.5 s** | | 5.4 s | 4.9 s |
| Long answers in the TUI (1,200 characters a second) | CPU, 20,000 characters | **1.9 s** | | 6.4 s | 5.5 s |
| | CPU, 60,000 characters | **6.9 s** | | 25.3 s | 24.0 s |
| A file written through a tool call (`pi -p`) | 200 KB: wall / CPU | **0.3 / 0.2 s** | | 12.0 / 20.1 s | 14.2 / 14.5 s |
| The same in the TUI | longest pause in drawing, 200 KB | **80 ms** | | 3.3 s | 6.4 s |
| | longest pause in drawing, 400 KB | **81 ms** | | 45.4 s | 39.5 s |
| A plugin's hot loop (0.6.0, not measured again) | compiled in | **43 ms** | 42 ms | | |
| | loaded at run time | 483 ms | **38 ms** | 37 ms | |

Medians of 21 runs (3 warm-up runs), 3 long sessions and 5 tmux rounds per runtime; one run of each of the others.

**Compare CPU times only within one table.** This MacBook Air has no fan. After hours of building it ran hot and at a lower
clock, so the same work took more CPU time than in a cool session: the 20,000-character answer took Pi-Bolt 0.98 s in the
0.6.0 measurements and 1.9 s here. Run side by side in this session, 0.6.0 and 0.6.1 took the same (1.66â€“1.76 s), and the
build behind the 0.6.0 table took 2.0â€“2.1 s to 0.6.1's 1.8 s. Every table here interleaves its runtimes, so each table is fair
within itself.

**CPU time understates the difference on Apple silicon.** macOS runs a light, bursty process on the efficiency cores or at a low
clock, and a busy one fast. While a 20,000-character answer streams, Pi-Bolt executes 2.7 billion instructions in 2.6 billion
cycles, nearly all of them on the efficiency cores (1.5 s of CPU at an average 1.7 GHz), and Bun 48.9 billion in 16.6 billion
cycles (6.4 s at 2.6 GHz): an eighteenth of the work, in a quarter of the CPU time.
Linux, on a server CPU at a fixed clock, shows the work more directly.

**The longest pause while a file is written.** Pi as released highlights the whole file again once the write is complete and
draws nothing until it has (see [Long answers and large files](#long-answers-and-large-files)), at the very end of the write.

**What macOS adds at launch.** `pi` is a small launcher: it starts `pi-bin`, the Pi-Bolt executable, in the same process, and
forks the helper that starts the programs Pi runs ([ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port)). The figures are of the
release builds, launcher included. The helper adds 0.3 ms to `pi -p` (1%; nothing to `pi --version`, which starts nothing), and
each program Pi starts takes 1.4 ms more to start and be waited for (`spawnSync` of `/usr/bin/true`: 2.1 ms against 0.7). None of
the scenarios above starts a program. The programs' CPU time is not counted in Pi's on macOS (`wait4` counts a process's
children; the programs are the helper's).

## Windows x64

The full suite (`bench\run-suite.ps1`) on Windows 11 25H2 (26200) on an Intel Core i5-1335U laptop (16 GB, on AC, best
performance, Defender real-time protection on): Pi-Bolt 0.7.0 from `7ec4e71f7` (the runtime with ThinLTO and Control Flow Guard,
Pi compiled ahead of time for this CPU, JIT off), Pi 1.0.3 as released on stock Bun 1.4.2, and Pi 1.0.3's npm package on Node
22.23.3 and Node 24.21.0. Medians of 11 runs (2 warm-up), 4 long sessions of 75 prompts, 5 streaming rounds; streaming runs in a
ConPTY rather than tmux (`bench\conpty_check.py`: ConPTY passes frames on at about 16 ms, which is the floor of the frame
times). Raw data and charts: [`bench/results/2026-10-06-windows-suite`](../bench/results/2026-10-06-windows-suite). (A read-only
review that ran alongside used the CPU for up to about two minutes at some point of the run.)

| | Pi-Bolt | Pi on Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | **100 ms** | 165 ms | 344 ms | 311 ms |
| `pi --version` | **39 ms** | 92 ms | 237 ms | 213 ms |
| `pi -p`: one prompt, 4 tool calls | **121 ms** | 195 ms | 422 ms | 377 ms |
| Time per prompt, 4.2M-token session | **533 ms** | 557 ms | 959 ms | 908 ms |
| CPU, interactive session (5 prompts) | **317 ms** | 791 ms | 1,209 ms | 1,134 ms |
| CPU, `pi -p` | **100 ms** | 319 ms | 572 ms | 546 ms |
| CPU, `pi --version` | **23 ms** | 132 ms | 275 ms | 266 ms |
| CPU per prompt, 4.2M-token session | **253 ms** | 332 ms | 828 ms | 749 ms |
| Peak working set, interactive session | **110 MB** | 164 MB | 166 MB | 194 MB |
| Peak private working set, interactive session | **60 MB** | 127 MB | 133 MB | 154 MB |
| Peak private bytes (commit), interactive session | 247 MB | 344 MB | **176 MB** | 269 MB |
| Private working set, end of 4.2M-token session | **157 MB** | 249 MB | 387 MB | 405 MB |
| Private bytes, end of 4.2M-token session | **370 MB** | 469 MB | 433 MB | 452 MB |
| CPU, streaming replies (ConPTY) | **810 ms** | 931 ms | 1,436 ms | 1,198 ms |
| Frame time p99, streaming replies | **17 ms** | 17 ms | 20 ms | 21 ms |
| Frame time p99 / longest stall, 50 KB file written | **18 / 164 ms** | 50 / 1,408 ms | 36 / 2,606 ms | 98 / 3,519 ms |
| Frame time p99 / longest stall, 200 KB file written | **18 / 133 ms** | 220 / 57,658 ms | 117 / 61,424 ms | 771 / 55,265 ms |
| GC pause p99, 20,000-char answer / longest GC pause | 3.5 / 8.3 ms | 4.1 / 8.7 ms | **1.2 / 3.4 ms** | 2.2 / 8.8 ms |

Pi-Bolt is the fastest and has the smallest private working set (what Task Manager shows) everywhere. Commit (private bytes) is
where it is not ahead: Node 22 commits less in the TUI. Most of Pi-Bolt's is address space committed and never touched (thread
stacks' default commit, mimalloc's pages committed in full, freed memory that keeps its commit on Windows): what to lower next.
A plugin loaded at run time runs its hot loop in 788 ms against Bun's 32 ms (Pi-Bolt runs with the JIT off, as on Linux);
compiled into the executable it would not, but `scripts\build-pi.ps1` cannot build that yet.

### Earlier Windows results

The same scenarios on Windows 11 (26200) on an Intel Core i5-1335U laptop (16 GB, on AC, Defender real-time protection on),
against Pi 1.0.3 as released on Bun 1.4.2 and its npm package on Node 24.21. Pi-Bolt is the `windows-x64` branch: the runtime without
LTO yet, Pi compiled ahead of time with the JIT off for this CPU (`scripts\build-pi.ps1`). Processes run in Job objects of their own
and the TUI in a ConPTY (`bench/winproc.py`); CPU is the main process's, by cycles. "Peak memory" is the peak working set,
"peak private" the peak private bytes (commit charge). Medians of 11 runs (2 warm-up), interleaved. Raw data:
[`bench/results/2026-10-06-windows-aot`](../bench/results/2026-10-06-windows-aot), and before the compiled code
[`bench/results/2026-10-06-windows-baseline`](../bench/results/2026-10-06-windows-baseline).

| Scenario | Metric | Pi-Bolt | Pi-Bolt, bytecode | Pi (fork) on Bun 1.4.2 | Pi 1.0.3 on Bun 1.4.2 | Node 24 |
|---|---|---:|---:|---:|---:|---:|
| `pi --version` | wall | **44 ms** | 60 ms | 53 ms | 104 ms | 240 ms |
| | CPU | **29 ms** | 46 ms | 38 ms | 147 ms | 294 ms |
| `pi -p "<prompt>"`: 5 model turns, 4 tool calls | wall | **163 ms** | 220 ms | 205 ms | 258 ms | 491 ms |
| | CPU | **138 ms** | 314 ms | 289 ms | 425 ms | 701 ms |
| | peak memory | **83 MB** | 100 MB | 104 MB | 117 MB | 118 MB |
| Interactive TUI: launch, 5 prompts, `/quit` | time to interactive | **122 ms** | 159 ms | 153 ms | 191 ms | 359 ms |
| | CPU | **368 ms** | 752 ms | 723 ms | 887 ms | 1,302 ms |
| | peak memory | **113 MB** | 138 MB | 147 MB | 163 MB | 195 MB |
| | peak private | **241 MB** | 326 MB | 324 MB | 342 MB | 269 MB |

**What an executable costs Windows when it is not running.** Windows charges an image's uninitialized sections to the system's
commit for as long as it keeps the image cached, after the process has exited (process memory does not show it;
[WINDOWS.md](WINDOWS.md#what-was-measured)). `bench/image_commit.py`, medians of 5: Pi on Bun 1 MB; Pi-Bolt 33 MB (the 32 MB of the
region that has to be in the image); before that was fixed, 1,029 MB.


## With a real model

The tables above use a local scripted model, which answers instantly and the same way every time, so they measure Pi and its
runtime alone. These use a hosted model over the internet, with thinking at its highest setting, as in daily use.
- Pi loads the provider's extension, in its Bun build for Pi-Bolt and Bun and its Node build for Node.
- Every runtime gets the same prompts and a fresh copy of the same project, and the runtimes take turns.
- CPU and memory are those of Pi's own process.

A real model's answers differ from run to run in length and in the tools they call, so some figures are also given per 1,000
characters streamed (answer and thinking). With a real model, a turn's wall time is almost all the model's (its thinking and the
network), and it varies by several seconds from one request to the next.

### Linux

**A prompt with tools, `pi -p`:** "Use ls to list this folder (and src), read README.md, then tell me its first heading and how
many .ts files there are." 10 rounds per runtime.

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Correct answers | 10/10 | 10/10 | 10/10 | 10/10 |
| Wall time, median | 7.96 s | 7.96 s | 8.17 s | 7.48 s |
| Wall time, fastest / slowest | 6.0 / 12.0 s | 6.3 / 9.4 s | 6.5 / 10.7 s | 6.0 / 10.2 s |
| **Pi's CPU, median** | **0.20 s** | 0.49 s | 0.77 s | 0.76 s |
| Pi's CPU, range | 0.17â€“0.23 s | 0.45â€“0.52 s | 0.69â€“0.89 s | 0.73â€“0.79 s |
| Peak memory, median | **124 MB** | 131 MB | 126 MB | 135 MB |

- **Wall time:** no runtime is faster or slower. In a permutation test, differences in median as large as these came out by
  chance 65â€“97% of the time: they are the model's variation.
- **CPU:** the difference does not overlap. Pi-Bolt's slowest run used less than Bun's fastest.

**A long answer, `pi -p`:** about 2,500 words of Markdown with three TypeScript code blocks, answered in the chat. One run per runtime.

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Answer | 19,803 characters | 20,116 characters | 19,103 characters | 20,305 characters |
| Wall time | 51 s | 53 s | 40 s | 44 s |
| **Pi's CPU** | **0.47 s** | 0.91 s | 1.06 s | 1.06 s |
| Peak memory | **124 MB** | 129 MB | 125 MB | 137 MB |

**The interactive TUI in tmux:** a 160Ã—48 pane, "read README.md and summarize it in two sentences". One run per runtime.

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Time to interactive | **88 ms** | 192 ms | 389 ms | 388 ms |
| Answer on screen after | 5.2 s | 6.5 s | 6.8 s | 5.4 s |
| **Pi's CPU, launch to answer** | **0.20 s** | 0.64 s | 0.94 s | 0.89 s |

All answers correct; no errors on screen; `/quit` exited with 0 everywhere.

### macOS

On the M5, against Pi 1.0.0 as released on Bun 1.4.2 and on Node 26. Two rounds per runtime.

**A long answer, `pi -p`:** a technical article of about 1,500 words with headings, a table and two code blocks, without tools.

| | Pi-Bolt | Bun 1.4.2 | Node 26 |
|---|---:|---:|---:|
| Characters streamed (answer and thinking), the two runs | 31,478 / 23,748 | 20,004 / 15,294 | 20,744 / 23,981 |
| **Pi's CPU per 1,000 characters** | **19.1 / 18.7 ms** | 76.6 / 36.3 ms | 47.1 / 39.1 ms |
| Pi's CPU | **600 / 443 ms** | 1,532 / 555 ms | 978 / 938 ms |
| Peak footprint | **29 / 29 MB** | 161 / 75 MB | 115 / 110 MB |

**A long session in the fullscreen TUI:** five prompts in one session.
- In the first four, the model reads one of four large Pi source files (140, 239, 63 and 47 KB) and explains it in about 600 words.
- In the fifth, it writes a design review of all four, about 1,000 words.
- Each session took 11 to 22 tool calls, and the context grew to between 82,000 and 141,000 tokens.

| | Pi-Bolt | Bun 1.4.2 | Node 26 |
|---|---:|---:|---:|
| Time to interactive, with the provider's extension | **67 / 72 ms** | 96 / 95 ms | 222 / 235 ms |
| Characters streamed, the two sessions | 74,602 / 62,691 | 61,840 / 64,547 | 68,458 / 61,331 |
| **Pi's CPU, whole session** | **5.6 / 4.3 s** | 11.5 / 8.9 s | 10.1 / 8.0 s |
| Pi's CPU per 1,000 characters | **74 / 68 ms** | 183 / 136 ms | 142 / 125 ms |
| Own memory after the last prompt | **38 / 33 MB** | 105 / 97 MB | 163 / 162 MB |
| Peak footprint | **51 / 45 MB** | not recorded / 155 MB | 166 / 163 MB |

Every prompt ended normally on every runtime. Bun's first session did not exit in time for its peak to be read.

The user waits the same on every runtime: the model sets the pace. Pi-Bolt does that waiting with:
- 40â€“50% of the CPU of Pi on Bun and a quarter to a half of Node's;
- a third of Bun's memory, and less than a quarter of Node's.

## Setup and method

| | |
|---|---|
| Pi | 1.0.0 (`v1.0.0`, a13d35a7) |
| Machine | AMD EPYC 7B13 (Zen 3), Linux 7.0, every run pinned to the same 8 cores |
| **Pi-Bolt** 0.6.1 | `pi-bolt-linux-x64` from the release: Pi 1.0.0 with Pi-Bolt's changes, compiled ahead of time, JIT off, CPU `native` |
| **Bun 1.4.2** | Pi 1.0.0 as released, built with Pi's own `bun build --compile` command, plus `--bytecode` (which makes stock Bun start faster) |
| **Node 22.23.3** and **Node 24.21.0** | Pi 1.0.0 as released: its npm package (`dist/bundle/cli.js`, with Node's compile cache) |

**The model.** A local server ([`bench/fake_model.py`](../bench/fake_model.py)) speaks the OpenAI chat-completions protocol and
streams a scripted conversation: for each prompt, four `read` tool calls on Pi source files, then an answer. There is no network
and no model latency, so the figures measure Pi and its runtime.

**Isolation.**

- Each run gets a fresh Pi home with only that model configured, and an environment cleared of `BUN_*`, `NODE_*` and `PI_*`.
- Runs are interleaved round-robin across builds, so background load affects all of them alike.

**Metrics.**

- **Wall**: elapsed time.
- **CPU**: user plus system time of all threads, from `wait4()` or the scheduler's per-thread statistics.
- **Peak memory**: maximum resident set.
- **Own memory**: private dirty pages (`smaps_rollup`). This is what a process costs beyond shared and file-backed pages, which
  matters for Pi-Bolt: its code and prebuilt heap are file-backed.

**The machine is shared.** It is a cloud VM that other heavy jobs also use. Interleaving keeps comparisons fair, but absolute
figures vary between sessions. Figures from sessions taken under heavy load were discarded and re-run.

## In a real terminal (tmux)

Pi in a 160Ã—48 tmux pane ([`bench/tmux_check.py`](../bench/tmux_check.py)): keys sent with `send-keys`, the screen read back
with `capture-pane`, and the model streaming at human pace, one event every 10 ms.

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| time to interactive | **68 ms** | 143 ms | 328 ms | 346 ms |
| keystroke to screen, median | 5.2 ms | 5.1 ms | 5.0 ms | 5.1 ms |
| 20 KB paste | **5.1 ms** | 5.8 ms | 6.1 ms | 10.1 ms |
| **CPU while 4 replies stream** (6.5 s) | **320 ms** | 524 ms | 605 ms | 530 ms |
| written to the terminal for a reply | **138 KB** | 324 KB | 323 KB | 324 KB |
| idle CPU at the prompt | 0.5 ms/s | 2.6 ms/s | **0.3 ms/s** | **0.3 ms/s** |
| own memory, start â†’ end | **17 â†’ 27 MB** | 62 â†’ 89 MB | 60 â†’ 132 MB | 68 â†’ 209 MB |
| resize while streaming, Escape to abort, prompt after abort, `/quit`, errors on screen | all ok, none | all ok, none | all ok, none | all ok, none |

Medians of 5 rounds of 4 prompts each, Pi-Bolt 0.6.1. Keystroke latency is the terminal's own round trip and is the same
everywhere. Pi-Bolt uses 40% less CPU than Bun's warmed-up JIT while replies stream, and a third of Bun's private memory. Node
uses the least CPU at the idle prompt.

### What Pi writes to the terminal

Everything Pi writes, tmux parses and draws again for its client, and an ssh connection or `docker exec` carries.
[`bench/tmux_load.py`](../bench/tmux_load.py) runs Pi in a tmux pane with a client attached while a 20,000-character Markdown
answer streams in at 1,200 characters a second:

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Pi writes to the pane | **528 KB** | 3,573 KB | 3,573 KB | 3,573 KB |
| tmux sends its client | **282 KB** | 1,443 KB | 1,443 KB | 1,443 KB |
| CPU of the tmux server | **0.16 s** | 0.61 s | 0.64 s | 0.61 s |
| CPU of Pi | **0.93 s** | 9.79 s | 8.29 s | 7.72 s |

In fullscreen mode, Pi's default, a line added to the transcript moves every row of the screen, and Pi writes every row again.
Pi-Bolt (from 0.5.2) scrolls the rows that only moved, with a scroll region and line feeds, and draws what differs afterwards.
What the screen shows is the same: [`bench/e2e_fullscreen.py`](../bench/e2e_fullscreen.py) compares every screen, text and
colors, with and without it, in tmux and in zmx, also inside a container. `PI_TUI_SCROLL_ROWS=0` turns it off. Means of two
runs.

## Long answers and large files

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/bench-long-dark.svg">
  <img alt="Long answers and large files, Pi-Bolt vs Bun 1.4.2 vs Node 22: CPU streaming a 20,000-character answer 1.0 / 10.4 / 8.4 s; a 60,000-character answer 3.8 / 43.1 / 43.7 s; share of a core while streaming 8 / 85 / 87%; writing a 200 KB file through a tool call 0.8 / 27.8 / 44.3 s" src="images/bench-long-light.svg">
</picture>

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Streaming a 20,000-character answer: CPU | **1.0 s** | 10.4 s | 8.4 s | 7.6 s |
| Streaming a 60,000-character answer: CPU | **3.8 s** | 43.1 s | 43.7 s | 40.3 s |
| Share of a core while it streams (60,000 characters) | **8%** | 85% | 87% | 80% |
| Own memory after the 60,000-character answer | **34 MB** | 168 MB | 253 MB | 243 MB |
| Writing a 50 KB file through a tool call (`pi -p`) | **0.2 s** | 1.9 s | 2.9 s | 2.7 s |
| Writing a 200 KB file through a tool call (`pi -p`) | **0.8 s** | 27.8 s | 44.3 s | 33.6 s |
| Writing a 200 KB file through a tool call: CPU | **0.5 s** | 53.1 s | 45.3 s | 34.2 s |
| The same in the TUI: longest stretch without a frame, 50 KB | **0.04 s** | 10.3 s | 10.9 s | 9.6 s |
| longest stretch without a frame, 200 KB | **0.08 s** | 62.6 s | 57.9 s | 21.9 s |
| CPU in the TUI, 200 KB | **3.5 s** | 91.6 s | 91.0 s | 76.6 s |

`bench/long_answer.py` streams Markdown answers (headings, lists, code blocks in several languages, tables) into the TUI at 1,200
characters a second, 24 at a time, and measures the CPU Pi uses until the answer has been drawn. `bench/large_write.py` has the
model write a file of TypeScript through the `write` tool, its arguments streaming 16 characters at a time (about one token),
and measures `pi -p` from start to exit. Means of two runs, which differed by at most 5%; pinned to 8 cores. Bun and Node run
Pi 1.0.0 as released (`v1.0.0`): Bun built with `scripts/build-pi.sh --stable --pi <Pi 1.0.0>`, Node from Pi's npm bundle.
`bench/pauses.py` writes the same files in the TUI and reports the longest time between two writes to the terminal: while
it lasts nothing is drawn and no key is taken. Pi-Bolt 0.6.1; the chart and the table are drawn from
[`bench/results/2026-10-04-pi-bolt-0.6.1`](../bench/results/2026-10-04-pi-bolt-0.6.1), with `bench/report.py`.

Both come from Pi's own code, not from the runtime. Pi draws a message again each time a few more words arrive, with a new
component that lexes the whole Markdown text, renders and wraps every block and highlights every code block, so the cost of each
redraw grows with the length of the answer. Pi-Bolt keeps what it lexed and rendered, and does again only the last two blocks
(where a block ends depends on the line after it); highlighted code is kept by language and text. What is drawn is the same, line
for line and color for color (`bench/e2e_screen.py`). Pi also parses all of a tool call's arguments each time a few more
characters arrive; Pi-Bolt parses them again only once they have grown by an eighth while they stream, and in full when the call
is complete. And Pi highlights every line of a file being written, and all of it again on every redraw once it is complete;
Pi-Bolt highlights the lines a collapsed call shows, and the rest when the call is expanded.

## Plugins

The example plugin ([`examples/plugins`](../examples/plugins)): a `/words` command that counts the words of a 16.8 MB file in a
character loop, timed inside Pi with `performance.now()`. See [PLUGINS.md](PLUGINS.md).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/bench-plugins-dark.svg">
  <img alt="Plugin hot loop and launch time: compiled in vs loaded at run time on Pi-Bolt, Pi-Bolt with JIT on, and Bun" src="images/bench-plugins-light.svg">
</picture>

| How the plugin runs | Launch to interactive | `/words` hot loop |
|---|---:|---:|
| **Compiled in**, Pi-Bolt (default, JIT off) | 46 ms | 50 ms |
| Compiled in, Pi-Bolt JIT on | **45 ms** | 53 ms |
| Loaded at run time, Pi-Bolt JIT off (interpreted) | 75 ms | 1,119 ms |
| Loaded at run time, Pi-Bolt JIT on | 79 ms | 42 ms |
| Loaded at run time, Bun 1.4.2 | 195 ms | **38 ms** |

Medians of 5 sessions, each running `/words` 5 times, Pi-Bolt 0.6.1. The hot-loop figure is the median of runs 2â€“5.

## Questions

### Why is a run-time plugin's loop 1,080 ms on Pi-Bolt and 38 ms on Bun?

Because that plugin's code is not compiled at all on that build: it runs in JavaScriptCore's bytecode interpreter.

- The default Pi-Bolt executable runs with the **JIT off**. Pi's own code does not need a JIT: all of it was compiled to machine
  code when the executable was built.
- A plugin loaded at **run time** (from `~/.pi/agent/extensions`, a project's `.pi/extensions` or a Pi package) is not part of
  that build. `jiti` turns its TypeScript into JavaScript when Pi starts. With no JIT, JavaScriptCore can then only interpret
  it, and an interpreter runs a tight loop 20â€“30Ã— slower than compiled code.
- Stock Bun's JIT compiles the loop after a few thousand iterations, which is how it gets to 38 ms.

This is not a regression. The figure is the same in every Pi-Bolt release: it is the cost of loading code at run time into a
runtime with no JIT. There are two ways around it:

| | Plugin hot loop |
|---|---:|
| Compile the plugin in: `scripts/build-pi.sh --plugins` ([PLUGINS.md](PLUGINS.md)) | 50 ms, and 3 ms at launch instead of 34 ms |
| Use the JIT-on build (`pi-bolt-linux-x64-jit`) and keep loading it at run time | 38 ms after warm-up (Bun: 38 ms) |
| Load it at run time on the default (JIT-off) build | 1,105 ms |

### Did Pi-Bolt get slower when it was made production-ready?

No. The release work changed three things:

- a portable runtime, linked against a glibc 2.17 sysroot with ICU built in;
- loop splitting;
- a retrained profile.

The last build before the release work, v0.1.0 and stock Bun were measured together, interleaved, 21 runs each
([raw data](../bench/results/2026-10-02-prerelease-vs-v0.1.0)):

| | Pre-release | v0.1.0 | Bun 1.4.2 |
|---|---:|---:|---:|
| `pi --version`, wall / CPU | 50 / 53 ms | **47 / 50 ms** | 77 / 140 ms |
| `pi -p`, wall / CPU | 113 / 127 ms | **109 / 123 ms** | 161 / 312 ms |
| interactive: time to interactive / CPU / peak memory | 79 ms / 395 ms / 159 MB | **76 ms / 381 ms / 153 MB** | 126 ms / 829 ms / 202 MB |
| long session, time / CPU per prompt (last 25, median of 3) | 157 / 93 ms | **150 / 92 ms** | 215 / 163 ms |

v0.1.0 was as fast or faster on every metric.

What does vary is streaming CPU in tmux. It is the most load-sensitive measurement on this shared machine, and v0.1.0 measured
531 ms in one session and 578 ms in another. That variation is noise. What the follow-up review did find is a real gap: stock
Bun's warmed-up JIT used 5â€“15% less CPU than v0.1.0 while replies streamed. v0.2.0 closes it, as the next answer explains.

### How was the streaming gap closed?

A profile of the TUI while replies stream showed what was different. Pi-Bolt spent 8% of its CPU calling the native
`String.prototype.charCodeAt`, once per character, from pi-tui's `visibleWidth()`, which measures every line on every frame.
Three engine fixes followed (in the [WebKit patch](../patches/webkit.patch)):

1. **Integer counters boxed as doubles.** `visibleWidth`'s loop counter is updated with `i += ansiCodeLength(...)`, so the
   compiler could not prove it an integer and kept it as a double. Boxed as a double, it missed the int32 fast path of
   `charCodeAt`, and every character went to C. Numbers are now boxed as int32 whenever they are integers, as JavaScriptCore's
   `jsNumber()` does.
2. **Substrings.** `slice()` and `split()` return substrings that `charAt`/`charCodeAt` read without resolving them, so the
   fast path never applied to them. They are now resolved on first use, as `codePointAt` already did.
3. **Faster loops with calls.** The loop-splitting mode that also covers loops calling known functions (policy 5) had been left
   off, because it miscompiled a spread call. The bug was found and fixed: a spread's result arriving through a phi was passed
   as one argument. Policy 5 is now on, and JavaScriptCore's test suite gives the same results with it as without.

| Streaming 4 replies in tmux, CPU (median of 5 rounds) | |
|---|---:|
| v0.1.0 | 531 ms |
| v0.2.0 engine, loop policy 3 | 496 ms |
| **v0.2.0** (policy 5) | **468 ms** |
| Bun 1.4.2, warmed-up JIT | 516 ms |

This was an earlier session than the main results, of Pi-Bolt 0.2.0 ([raw data](../bench/results/2026-10-02-streaming-fix));
0.5.2 is at 366 ms. pi-tui's `visibleWidth` loop on its own: 25 ms â†’ 6.3 ms (Bun: 2.4 ms).

## Reproduce

```bash
scripts/package-release.sh                      # out/pi-bolt, out/pi-bolt-baseline, out/pi-bolt-jit
scripts/build-pi.sh --stable --out out/pi-stable
scripts/build-pi.sh --plugins examples/plugins/plugins.ts --out out/pi-bolt-plugins
scripts/build-pi.sh --plugins examples/plugins/plugins.ts --jit on --out out/pi-bolt-plugins-jit
scripts/build-pi.sh --stable --pi ../pi-1.0.0 --out out/pi-stable-upstream     # Pi as released, in a checkout of its tag
PIBOLT_PI=../pi-1.0.0 PIBOLT_STABLE_PI=out/pi-stable-upstream/pi \
	bench/run-suite.sh bench/results/my-run --cpus 8-15   # about an hour; then charts and tables from bench/report.py
python3 bench/long_answer.py --cpus 8-15 --build pi-bolt=out/pi-bolt/pi --build bun=out/pi-stable/pi --sizes 20000,60000
python3 bench/large_write.py --cpus 8-15 --build pi-bolt=out/pi-bolt/pi --build bun=out/pi-stable/pi --sizes 50,200
python3 bench/tmux_load.py --cpus 8-15 --build pi-bolt=out/pi-bolt/pi --build bun=out/pi-stable/pi --steps md:20000
python3 bench/pauses.py --cpus 8-15 --build pi-bolt=out/pi-bolt/pi --build bun=out/pi-stable/pi --steps write:50,write:200
```

Each tool also runs on its own, with any builds given as `--build name=command`. See [`bench/README.md`](../bench/README.md).

## Correctness

Speed is only measured on builds that pass the correctness checks:

- `bench/e2e_tools.py`: every Pi tool, with output and files byte-identical to stock Bun's.
- `bench/ui_check.py` and `bench/tmux_check.py`: the TUI.
- `tests/aot/run.sh`: engine tests, including regression tests for the v0.2.0 fixes.
- JavaScriptCore's own stress tests, run with and without AOT compilation: 4,779 of the 4,786 that run behave the same. The 7
  that differ inspect JIT internals that do not exist without a JIT.

See [BUILDING.md](BUILDING.md#tests).
