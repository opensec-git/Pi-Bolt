# Benchmarks

Pi-Bolt compared with the same Pi release on stock Bun and on Node.js. Every figure comes from the tools in
[`bench/`](../bench), and the raw results are in [`bench/results/`](../bench/results). The charts are drawn from those files by
[`bench/report.py`](../bench/report.py).

**Versions.** [1.2.0 against 1.1.7](#120-against-117) is the latest: Pi-Bolt 1.2.0 against Pi-Bolt 1.1.7 on an M5 Mac. [1.0.0 against PiG 0.5.1 and 0.9.9](#100-against-pig-051-and-099) is the one before: Pi-Bolt 1.0.0 against PiG 0.5.1 and Pi-Bolt 0.9.9, on GitHub's Linux and Windows runners and an M5 Mac. [0.9.7 against Pi as released](#097-against-pi-as-released) is earlier: Pi-Bolt 0.9.7 against Pi 1.3.0 as released on npm, run by stock Bun 1.4.2, Node 22 and Node 24, on GitHub's runners. [0.9.6 against PiG 0.5.1](#096-against-pig-051) is earlier: Pi-Bolt 0.9.6 against PiG 0.5.1, Pi-Bolt 0.9.5 and Pi 1.3.0 on Node 24, on GitHub's runners and an M5 Mac. [0.9.2 on GitHub's runners](#092-on-githubs-runners) is earlier: Pi-Bolt 0.9.2 (Pi 1.2.0) on GitHub's Linux
x86-64 and ARM64 runners. The sections after it are earlier measurements: Pi-Bolt 0.8.5 (Pi 1.1.0), against Pi 1.1.0 as released on stock Bun 1.4.2 and on Node, every build installed from
its release archive; [against PiG and oh-my-pi](#against-pig-and-oh-my-pi) in the same runs. [With a real model](#with-a-real-model) is an earlier measurement, of Pi-Bolt 0.6.1.

**These are measurements, not guarantees.** Each set of results comes from one machine: for 0.8.5, an AMD EPYC 7B13 server for
Linux and an M5 MacBook Air for macOS. On other hardware the milliseconds will differ, and so can the load, the terminal and the disk. What
carries over is the comparison, because every run interleaves the runtimes on the same machine: Pi-Bolt starts three times
sooner than Pi on Bun and uses about a quarter of its CPU over an interactive session. The ratio varies by scenario: see
each table.

- [1.2.0 against 1.1.7](#120-against-117)
- [1.0.0 against PiG 0.5.1 and 0.9.9](#100-against-pig-051-and-099)
- [0.9.7 against Pi as released](#097-against-pi-as-released)
- [0.9.6 against PiG 0.5.1](#096-against-pig-051)
- [0.9.2 on GitHub's runners](#092-on-githubs-runners)
- [0.7.3 against 0.7.2](#073-against-072)
- [Results](#results)
- [macOS on Apple silicon](#macos-on-apple-silicon)
- [Linux on ARM64](#linux-on-arm64)
- [Windows x64](#windows-x64)
- [Against PiG and oh-my-pi](#against-pig-and-oh-my-pi)
- [Against OpenCode 2.0](#against-opencode-20)
- [With a real model](#with-a-real-model)
- [Setup and method](#setup-and-method)
- [In a real terminal (tmux)](#in-a-real-terminal-tmux)
  - [What Pi writes to the terminal](#what-pi-writes-to-the-terminal)
- [Long answers and large files](#long-answers-and-large-files)
- [Plugins](#plugins)
- [Questions](#questions)
  - [Why is a run-time plugin's loop slow on the default build?](#why-is-a-run-time-plugins-loop-slow-on-the-default-build)
- [Reproduce](#reproduce)
- [Correctness](#correctness)

## 1.2.0 against 1.1.7

What 1.2.0 changed, measured against 1.1.7 on an Apple M5 MacBook Air (macOS, not pinned, shared with other work), both
built from their own trees with the same runtime, interleaved in every run. Instructions are retired instructions of the
Pi process (M = millions, K = thousands); CPU and wall time move with the machine's load, instructions much less. Medians
(min-max) of 11 runs unless a row says otherwise. Lower is better; 1.1.7 / **1.2.0**.

Ready to type (instructions until the TUI's first frame is written and input is live, n=9) and `pi -p` (one prompt, 5
model turns). The typical home is `bench/benchmark.py --home realistic` (43 prompt templates, 8 skills, a 118-model store,
`AGENTS.md` files, a git project); "with extensions" adds `opensec-pi-subagents` and `opensec-pi-todo`
(`--home-extensions`), two of the extensions the installers offer.

| Home | Ready to type | `pi -p`, instructions | `pi -p`, CPU | `pi -p`, peak footprint |
|---|---|---|---|---|
| Blank | 161.6 / **143.1** M (-11%) | 206.3 / **187.5** M (-9%) | 18.7 / **17.4** ms | 21.8 / **20.2** MB |
| Typical | 270.5 / **163.4** M (-40%) | 296.2 / **205.1** M (-31%) | 23.8 / **18.5** ms | 24.6 / **21.0** MB |
| Typical with extensions | 501.3 / **390.0** M (-22%) | 586.3 / **487.2** M (-17%) | 53.4 / **47.9** ms | 35.6 / **33.8** MB |

| Scenario | Metric | 1.1.7 | 1.2.0 |
|---|---|---:|---:|
| `pi --version` (n=31) | instructions / CPU / peak footprint | 55.5 M / 4.5 ms / 5.6 MB | 55.5 M / 4.6 ms / 5.6 MB |
| Interactive, 5 prompts (n=21) | time in the 5 turns | 119.7 ms | **83.3 ms** |
| | instructions / peak footprint | 575.5 M / 28.7 MB | **552.9 M / 27.0 MB** |
| Long answer, 60,000 characters (n=5) | instructions per frame | 1.23 M | **1.20 M** |
| Streaming a 300-item list (n=3) | instructions per frame / own memory | 16.6 M / 38 MB | **1.13 M / 21 MB** |
| Streaming a 150-row table (n=3) | instructions per frame | 10.15 M | **1.41 M** |
| Streaming a 150-line TypeScript block (n=3) | instructions per frame | 28.7 M | **2.32 M** |
| Streaming a 400-line TypeScript block (n=3) | instructions per frame / own memory | 74.6 M / 32 MB | **2.54 M / 20 MB** |
| Streaming a 150-line Python block (n=3) | instructions per frame | 12.2 M | **1.41 M** |
| Generated answer of 20,000 characters, fast (5 ms a chunk, n=15, answer 2) | instructions per frame, fullscreen / regular | 1,588 K / 1,690 K | **1,564 K / 1,619 K** |
| Resume a 2,292-entry session, fullscreen (n=5) | first frame / instructions to it | 295 ms / 6,073 M | **41 ms / 519 M** |
| | everything drawn (instructions) / peak footprint | 6,352 M / 81.8 MB | **6,311 M / 79.2 MB** |
| Resume a 2,292-entry session, regular (n=3) | first frame / peak footprint | 2,204 ms / 83.3 MB | 2,213 ms / 90.5 MB |
| Codex, a request after a prompt in a 9 MB conversation (n=8) | instructions, default transport / SSE | 178.8 M / 164.1 M | **8.5 M / 111.6 M** |
| Long session, 40 prompts (n=3) | CPU per prompt at prompt 40 / own memory | 14.6 ms / 39.3 MB | **14.1 ms / 38.7 MB** |
| Files written in tmux (`write:50`, n=3) | Pi CPU | 4.12 s | **3.03 s** |
| Idle at the prompt | CPU per second | 0.43 ms | 0.41 ms |

- The list, table and code rows stream `bench/answers/` with `bench/long_answer.py`. Code blocks are followed as they grow
  in TypeScript, JavaScript, Python, Bash, JSON, Rust, Go, C, C++, Java, HTML, CSS, YAML, diff and SQL, from 256
  characters on; other languages and shorter blocks are highlighted in full, as in 1.1.7.
- The fast generated answer has two modes in every build (about 1,550 K and 1,750 K per frame, depending on when the
  collector runs): 1.2.0's median is lower in both display modes, and with two different answers too (fullscreen 1,688 /
  1,662 K, regular 1,819 / 1,665 K).
- Resuming in fullscreen draws the end of the transcript first: the first frame comes 250 ms sooner, and a key typed at
  that frame is echoed within 5 ms while the rest is drawn. Regular mode draws the whole transcript first, as before; its
  peak footprint at 2,292 entries is 5-7 MB higher (88.8-90.5 against 83.3-84.0 MB in two runs of 3), with fewer
  instructions.
- `pi-bin` is 1.4 MB larger (213.9 MB): generated settings and schema checks, and the code-block highlighter.

## 1.0.0 against PiG 0.5.1 and 0.9.9

Pi-Bolt 1.0.0 against [PiG](https://github.com/MichaelKinsy/PiG) 0.5.1 (Pi 1.3.0 ported to Go) and Pi-Bolt 0.9.9 (its release
archive, checked against its SHA256SUMS). 1.0.0 is the release's code built on its runtimes (CI run 39894603087). Every build
does the same work against the same local model: `bench/pig_verify.py` checked the requests, tool results, answers and files
written on every platform. Times to start and sessions are medians of 21 runs, streaming of three rounds, the long session of
two. Linux and Windows: GitHub's runners (CI runs 39939443543 and 39939443492; Linux pinned to one CPU, Windows two cores); macOS:
an Apple M5 Mac, not pinned. Raw data: [`bench/results/2026-10-23-pig-1.0.0`](../bench/results/2026-10-23-pig-1.0.0). Lower is
better; Pi-Bolt 1.0.0 / PiG 0.5.1 / Pi-Bolt 0.9.9.

| CPU or memory | Linux x86-64 | Linux ARM64 | Windows x64 | Apple M5 |
|---|---|---|---|---|
| Ready to type, ms | 43 / **16** / 48 | 39 / **15** / 43 | **93** / 106 / 107 | 25 / **17** / 26 |
| One prompt (`pi -p`), CPU ms | 58 / **24** / 63 | 55 / **26** / 60 | 97 / **93** / 106 | 34 / **24** / 37 |
| Interactive session, CPU ms | 176 / **165** / 187 | 171 / **160** / 199 | **274** / 378 / 285 | **78** / 118 / 80 |
| 60,000-character answer, CPU s | 2.31 / **1.33** / 2.61 | 2.40 / **0.96** / 2.49 | 3.25 / **3.08** / 3.87 | 1.58 / **1.11** / 1.91 |
| CPU per prompt at 20 / 40 prompts, ms | 29.2 / **46.9**; 28.8 / 49.5; 36.1 / 69.5 | **26.0 / 41.3**; 26.9 / 43.3; 33.3 / 57.3 | **41.7 / 61.0**; 50.4 / 69.2; 43.8 / 70.2 | at 40: **19.3-25.5** / 30.9-52.7 / 21.2-50.9 (two runs each) |
| Peak memory, interactive, MB (rss, own) | 116, 33 / **72, 20** / 134, 42 | 116, 38 / **56, 18** / 132, 48 | 70, 90 / **45, 67** / 84, 101 (working set, private bytes) | 73, 31 / **54, 26** / 91, 40 (rss, footprint) |
| Memory after 40 prompts, MB (rss, own) | 123, **39** / **94**, 42 / 162, 70 | 123, 44 / **76, 39** / 199, 114 | 78, 100 / **67, 88** / 112, 132 (working set, private bytes) | 83-84, **40-44** / **74-76**, 48-49 / 236-257, 35-69 |
| Answer streaming into a 4,000-entry session, fullscreen, CPU s | **0.48** / 0.54 / 4.46 | **0.32** / 0.45 / 3.77 | **0.50** / 0.61 / 4.34 | **0.21** / 0.23 / 1.36 |
| The same at 2,292 entries, regular mode, CPU s | 0.73 / **0.22** / 4.29 | 0.60 / **0.22** / 3.83 | 0.70 / **0.33** / 4.67 | (not run: 0.9.9 did not start in time) |
| Files written in tmux (write:50), Pi CPU s | **0.99** / 3.22 / 1.08 | | | **0.61** / 2.13 / 1.16 |
| Idle CPU, two minutes after an answer, ms/s | 0.017 / **0.003** / 0.043 | 0.046 / **0.004** / 0.065 | **0.20** / 1.64 / 0.41 | 0.18 / **0.006** / 0.17 |
| Download / installed, MB | 55.6 / **32.1** / 55.4; 268 / **70** / 267 | 52.5 / **30.2** / 52.3; 253 / **66** / 252 | | |

- **Against 0.9.9,** every speed and memory row improves or ties on every platform. Over 40 prompts, CPU per prompt falls by a
  third on Linux x86-64 (69.5 to 46.9 ms) and Pi's own memory by 44% (70 to 39 MB); on ARM64 from 114 to 44 MB. A frame no
  longer costs more the longer the session: an answer streaming into a 4,000-entry session takes a tenth of 0.9.9's CPU. The
  executable is about 1 MB larger.
- **Against PiG,** Pi-Bolt 1.0.0 uses less CPU per prompt deep into a session (Linux, Windows), less own memory after 40 prompts
  on Linux x86-64, a third of PiG's CPU writing files through a terminal (longest pause 84 against 610 ms), and less CPU on long
  sessions in fullscreen. On Windows it is also ready sooner, uses less CPU over an interactive session and idles lower.
- **PiG** starts sooner and answers `pi -p` with less than half the CPU on Linux, streams a long answer with less CPU (1.7 to
  2.5 times on Linux, close on Windows), peaks lower in memory, idles at zero on Linux, draws long sessions in regular mode with
  less CPU, and is a third of the size: a native Go program.

The long-transcript rows are one round on Linux (0.9.9 did not start within the tool's deadline at 4,000 entries in regular
mode, which ended that tool); the Linux "own" memory is the process's anonymous resident memory, sampled (`peak_own_mb`), the
counterpart of macOS's footprint, since `ru_maxrss` also counts the clean pages of the files a build maps.

## 0.9.7 against Pi as released

Pi-Bolt 0.9.7 against Pi 1.3.0 as released on npm (`@earendil-works/pi-coding-agent`, `dist/bundle/cli.js`), run by stock Bun
1.4.2, by Node 22 and by Node 24. Every build was installed from its release archive or npm package and checked against its
checksums, and does the same work against the same local model. Times to start and sessions are medians of 21 runs. Raw data,
the runners and the workflow: [`bench/results/2026-10-21-stock-bun-0.9.7`](../bench/results/2026-10-21-stock-bun-0.9.7). Lower
is better.

Pi-Bolt / Pi on Bun / Pi on Node 22 / Pi on Node 24:

| Benchmark | Linux x86-64 | Linux ARM64 | macOS | Windows x64 |
|---|---|---|---|---|
| Writing a 400 KB file through a tool call, wall s | **1.3** / 182.2 / 195.3 / 145.8 | **1.3** / 157.3 / 173.7 / 131.8 | **1.7** / 129.7 / 178.9 / 129.2 | **1.6** / 139.4 / 171.2 / 112.1 |
| 60,000-character answer, CPU s | **2.94** / 41.14 / 40.03 / 37.87 | **3.01** / 40.45 / 39.84 / 37.55 | **5.40** / 66.68 / 74.31 / 63.16 | **3.39** / 44.58 / 42.28 / 38.20 |
| CPU per prompt, prompts 21 to 40, ms | **65** / 350 / 379 / 362 | **58** / 308 / 329 / 301 | **47** / 272 / 395 / 291 | **59** / 303 / 427 / 361 |
| Interactive session, CPU ms | **190** / 1,189 / 1,196 / 1,179 | **196** / 1,187 / 1,212 / 1,183 | **199** / 1,133 / 1,295 / 1,287 | **243** / 1,407 / 1,490 / 1,280 |
| Memory after 40 prompts, MB | **166** / 289 / 508 / 578 | **197** / 267 / 545 / 612 | **182** / 423 / 678 / 762 | **141** / 262 / 292 / 325 |
| Ready to type, ms | **45** / 382 / 383 / 411 | **44** / 353 / 360 / 410 | **85** / 253 / 341 / 383 | **93** / 315 / 410 / 363 |
| 20,000-character answer in tmux, Pi CPU s | **0.94** / 9.09 / 7.99 / 7.34 | **0.88** / 8.18 / 7.43 / 7.16 | **2.00** / 14.23 / 13.46 / 12.75 | |
| Bytes sent to the terminal for it, KB | **528** / 3,517 / 3,554 / 3,563 | **529** / 3,523 / 3,564 / 3,571 | **1,032** / 4,092 / 4,024 / 4,028 | |

GitHub's Linux runners are pinned to one CPU; macOS is `macos-15` on an Apple M1 (virtual, 3 CPUs, not pinned); Windows is
`windows-2025` with two cores, and its memory is the working set. GitHub's macOS runner stretches the paced tools (the long
answer and tmux), so their wall times there are not comparable; their CPU times are.

In the runs of 0.9.2 to 0.9.6 ([`2026-10-16-linux-0.9.2`](../bench/results/2026-10-16-linux-0.9.2),
[`2026-10-20-linux-0.9.6`](../bench/results/2026-10-20-linux-0.9.6)), "Bun" was this repository's Pi on stock Bun, with Pi-Bolt's
changes to Pi's code; here it is Pi as released.

## 0.9.6 against PiG 0.5.1

Pi-Bolt 0.9.6 against [PiG](https://github.com/MichaelKinsy/PiG) 0.5.1 (Pi 1.3.0 ported to Go), with Pi-Bolt 0.9.5 and Pi 1.3.0 on
Node 24. Every build does the same work against the same local model: `bench/pig_verify.py` checks the requests, tool results,
answers and files written (Pi-Bolt's requests carry two more tool definitions, `grep` and `find`, enabled by default). Times to
start and sessions are medians of 21 runs (15 on Windows), streaming the mean of three rounds (two on the Mac). Raw data and the
exact builds: [`bench/results/2026-10-20-pig-0.5.1`](../bench/results/2026-10-20-pig-0.5.1). Lower is better.

| CPU or memory | Linux x86-64 | Linux ARM64 | Windows x64 | Apple M5 |
|---|---|---|---|---|
| Ready to type, ms | 42 / **15** | 43 / **15** | 78 / **75** | 31 / **22** |
| One prompt (`pi -p`), CPU ms | 56 / **25** | 63 / **27** | 87 / **70** | 27 / **18** |
| Interactive session, CPU ms | 164 / **141** | 187 / **155** | **229** / 272 | **91** / 125 |
| 60,000-character answer, CPU s | 2.58 / **1.53** | 2.31 / **1.18** | 2.68 / **2.06** | 3.36 / **2.34** |
| Files written in tmux (write:50), Pi CPU s | **1.10** / 2.67 | **0.96** / 2.71 | | **1.94** / 4.35 |
| CPU per prompt, prompts 21 to 40, ms | 56.8 / **41.5** | 53.4 / **41.2** | | **18.9** / 27.0 |
| Startup, CPU ms | 12 / **5** | 10 / **4** | **17** / 33 | 7 / **6** |
| Peak memory, interactive, MB | 154 / **66** | 132 / **56** | 102 / **67** (private) | 41 / **26** (footprint) |
| Idle CPU, ms/s | 0.54 / **0.00** | | | 0.98 / **0.01** |
| Download / installed, MB | 55 / **32**, 266 / **70** | | | |

Pi-Bolt 0.9.6 / PiG 0.5.1. GitHub's Linux runners are pinned to one CPU; the Windows runner has two cores; the Mac was not pinned.
GitHub's macOS runner stretches the paced tools about sixfold, so the macOS column comes from an M5 Mac; a shorter run on GitHub's
macOS runner (`macos-github`) agrees on every row but the long session, where the two are even.

- **PiG** starts sooner, streams with less CPU and uses about half the memory. It is a native Go program; on Linux it leads every
  row but writing files in the terminal.
- **Pi-Bolt** writes 2.2–2.8 times less CPU through a terminal and sends a seventh of the bytes PiG does (528 KB against 3,573 KB
  for a 20,000-character answer). On macOS it uses less CPU over an interactive session (91 / 125 ms on the M5, 191 / 238 ms on
  GitHub's runner), and on the M5 over a long session (even on GitHub's runner). On Windows it starts with half PiG's CPU.
- **Pi on Node 24** is far behind both: on Linux x86-64 it takes 362 ms to be ready, 1,026 ms of CPU over a session and 34.7 s for
  the long answer.

## 0.9.2 on GitHub's runners

Pi-Bolt 0.9.2 and 0.8.5 (0.8.8 on ARM64, the first ARM64 release), from their release archives, against Pi 1.2.0 on stock Bun
1.4.2 (this repository's Pi) and Pi 1.2.0 as released on npm on Node 22 and Node 24. GitHub's `ubuntu-24.04` and
`ubuntu-24.04-arm` runners, one CPU each, interleaved. Times to start, prompts and sessions are medians of 11 runs; long
answers, files, tmux and the 40-prompt session are one run each. The tools' printed results:
[`bench/results/2026-10-16-linux-0.9.2`](../bench/results/2026-10-16-linux-0.9.2).

**Linux x86-64**

| Benchmark | 0.9.2 | 0.8.5 | Bun | Node 22 | Node 24 |
|---|---:|---:|---:|---:|---:|
| `pi --version` | 10 ms | 11 ms | 24 ms | 178 ms | 192 ms |
| Ready to type | 38 ms | 36 ms | 84 ms | 259 ms | 277 ms |
| CPU, one prompt (`pi -p`) | 51 ms | 49 ms | 128 ms | 369 ms | 385 ms |
| CPU, interactive session | 142 ms | 138 ms | 383 ms | 790 ms | 790 ms |
| CPU, streaming a 60,000-character answer | 1.56 s | 1.97 s | 1.69 s | 32.6 s | 28.9 s |
| CPU, a 400 KB file through a tool call | 1.53 s | 1.82 s | 1.87 s | 178 s | 150 s |
| Pi's CPU in tmux, a 50 KB file through a tool call | 0.92 s | 1.27 s | 1.06 s | 11.8 s | 10.4 s |
| Peak memory, `pi --version` | 46 MB | 59 MB | 46 MB | 98 MB | 107 MB |
| Peak memory, one prompt | 107 MB | 117 MB | 114 MB | 127 MB | 138 MB |
| Own memory of a session in tmux | 24 MB | 40 MB | 77 MB | 143 MB | 212 MB |
| Own memory after 40 prompts | 101 MB | 95 MB | 164 MB | 418 MB | 520 MB |

**Linux ARM64**

| Benchmark | 0.9.2 | 0.8.8 | Bun | Node 22 | Node 24 |
|---|---:|---:|---:|---:|---:|
| `pi --version` | 10 ms | 10 ms | 26 ms | 246 ms | 271 ms |
| Ready to type | 43 ms | 44 ms | 120 ms | 349 ms | 391 ms |
| CPU, one prompt (`pi -p`) | 61 ms | 62 ms | 186 ms | 545 ms | 547 ms |
| CPU, interactive session | 196 ms | 197 ms | 557 ms | 1,155 ms | 1,108 ms |
| CPU, streaming a 60,000-character answer | 3.05 s | 4.79 s | 2.98 s | 38.0 s | 36.5 s |
| CPU, a 400 KB file through a tool call | 2.56 s | 3.51 s | 2.60 s | 250 s | 221 s |
| Pi's CPU in tmux, a 50 KB file through a tool call | 1.06 s | 2.44 s | 1.39 s | 17.3 s | 15.6 s |
| Peak memory, one prompt | 108 MB | 108 MB | 122 MB | 160 MB | 174 MB |
| Own memory of a session in tmux | 26 MB | 28 MB | 82 MB | 158 MB | 214 MB |
| Own memory after 40 prompts | 90 MB | 149 MB | 169 MB | 482 MB | 572 MB |

The Bun build is this repository's Pi, with Pi-Bolt's terminal changes; the Node builds are Pi as released, so the gap on long
answers and large files is mostly Pi's own redrawing there. On x86-64 the runner has transparent huge pages always on, where
0.9.2's smaller static-heap footprint shows (`pi --version` 46 against 59 MB), at a few percent of CPU on short commands.

## 0.7.3 against 0.7.2

What 0.7.3 changed, measured against 0.7.2 on the same machines, both installed from their release archives and interleaved.
Raw data and method: [`bench/results/2026-10-10-0.7.3-vs-0.7.2`](../bench/results/2026-10-10-0.7.3-vs-0.7.2). Medians;
"own memory" is the physical footprint on macOS and private dirty memory on Linux.

| Scenario | Metric | macOS 0.7.2 | macOS 0.7.3 | Linux 0.7.2 | Linux 0.7.3 |
|---|---|---:|---:|---:|---:|
| `pi -p`, `pi-bin` not in memory | wall | 177 ms | **64 ms** | | |
| `pi --version` | CPU | 7 ms | 7 ms | 16 ms | **15 ms** |
| | peak memory | 30 MB | 30 MB | 80 MB | **66 MB** |
| `pi -p` (one prompt, 5 turns) | CPU | 30 ms | 30 ms | 97 ms | **90 ms** |
| | peak footprint / peak memory | 32 MB | **29 MB** | 144 MB | **127 MB** |
| Interactive, 5 prompts | CPU | 131 ms | **125 ms** | 345 ms | **326 ms** |
| | peak footprint / peak memory | 51 MB | **46 MB** | 172 MB | **154 MB** |
| Long session, CPU per prompt | at prompt 70 / 50 | 73 ms | **63 ms** | 252 ms | **231 ms** |
| | own memory at 70 / 50 | 47-70 MB | **45-48 MB** | 115-157 MB | 129-160 MB |
| Streaming in tmux | CPU | 592 ms | **565 ms** | 408 ms | **363 ms** |
| Idle at the prompt | CPU per second | 1.0 ms | 1.1 ms | 0.56 ms | **0.30 ms** |
| 32 MB of command output, a line at a time | CPU | 2.0 s | **0.26 s** | 5.1 s | **1.7 s** |
| | peak memory | 104 MB | **56 MB** | 202 MB | **128 MB** |

Long-session memory moves with when the collector runs more than with the build: on Linux three runs of each gave 115-157 MB
for 0.7.2 and 129-160 MB for 0.7.3 at prompt 50, and mostly lower for 0.7.3 earlier in the session. In the 600-prompt soak of one RPC
session (`bench/stress.py`), the floor of memory stays flat on both platforms with 0.7.3 (macOS 24-25 MB; Linux 104-105 MB
resident), where 0.7.2's rose by 5 MB per 100 prompts on Linux (143-155 MB over 300 prompts).

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

Pi-Bolt 0.8.5 on Linux (AMD EPYC 7B13, pinned to four cores), against Pi 1.1.0 as released on stock Bun 1.4.2 and on Node 24.
Medians of 21 runs of each scenario (after 3 warm-up runs), 3 long sessions and 5 tmux rounds per build; raw data in
[`bench/results/2026-10-12-linux-x64-0.8.5`](../bench/results/2026-10-12-linux-x64-0.8.5), drawn by `bench/report.py`.

#### Time

|  | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Launch to interactive (TUI) | 38 ms | 116 ms | 296 ms |
| pi --version | 11 ms | 72 ms | 222 ms |
| pi -p: one prompt, 4 tool calls | 60 ms | 154 ms | 390 ms |
| Time per prompt, 4.2M-token session | 435 ms | 691 ms | 841 ms |
| Launch to interactive (TUI), default theme | 37 ms | 112 ms | 295 ms |

#### CPU

|  | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Interactive session: 5 prompts | 193 ms | 746 ms | 1,093 ms |
| pi -p: one prompt | 61 ms | 283 ms | 550 ms |
| pi --version | 11 ms | 131 ms | 281 ms |
| Per prompt, 4.2M-token session | 180 ms | 488 ms | 685 ms |

#### Memory and streaming

|  | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Peak memory, interactive session | 150 MB | 217 MB | 213 MB |
| Own memory, tmux session | 22 MB | 84 MB | 185 MB |
| Own memory, end of 4.2M-token session | 134 MB | 253 MB | 536 MB |
| Streaming replies (tmux), CPU | 204 ms | 382 ms | 475 ms |

## macOS on Apple silicon

The same tools on an Apple M5 MacBook Air (10 cores, 16 GB, macOS 27.0.1). macOS cannot pin processes to cores, so runs are
interleaved. "Own memory" and "peak footprint" are the physical footprint (what Activity Monitor shows), the closest measure to
private dirty pages on Linux; "peak memory" is the resident peak, which also counts clean pages of the executable and freed
memory the kernel may take back. The MacBook Air has no fan, so differences under about 10% are within its noise.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/darwin-arm64/bench-hero-dark.svg">
  <img alt="macOS, Pi-Bolt 0.8.5 vs Pi on Bun 1.4.2 vs Pi on Node 26: ready to type 41 / 100 / 348 ms; CPU per session 139 / 561 / 937 ms; CPU while streaming 455 / 720 / 759 ms; memory after a long session 38 / 92 / 1,581 MB" src="images/darwin-arm64/bench-hero-light.svg">
</picture>

Pi-Bolt 0.8.5 against Pi 1.1.0 as released on Bun 1.4.2 and on Node 26.11 (21 runs, 3 long sessions and 5 tmux rounds per build;
raw data in [`bench/results/2026-10-12-darwin-arm64-0.8.5`](../bench/results/2026-10-12-darwin-arm64-0.8.5)):

#### Time

|  | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 26 |
|---|---:|---:|---:|
| Launch to interactive (TUI) | 41 ms | 100 ms | 348 ms |
| pi --version | 19 ms | 56 ms | 284 ms |
| pi -p: one prompt, 4 tool calls | 55 ms | 115 ms | 400 ms |
| Time per prompt, 4.2M-token session | 330 ms | 507 ms | 629 ms |
| Launch to interactive (TUI), default theme | 40 ms | 99 ms | 346 ms |

#### CPU

|  | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 26 |
|---|---:|---:|---:|
| Interactive session: 5 prompts | 139 ms | 561 ms | 937 ms |
| pi -p: one prompt | 49 ms | 221 ms | 512 ms |
| pi --version | 13 ms | 93 ms | 305 ms |
| Per prompt, 4.2M-token session | 113 ms | 329 ms | 537 ms |

#### Memory and streaming

|  | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 26 |
|---|---:|---:|---:|
| Peak memory, interactive session | 94 MB | 209 MB | 230 MB |
| Own memory, tmux session | 24 MB | 65 MB | 162 MB |
| Own memory, end of 4.2M-token session | 38 MB | 92 MB | 1,581 MB |
| Streaming replies (tmux), CPU | 455 ms | 720 ms | 759 ms |

**What macOS adds at launch.** `pi` is a small launcher: it starts `pi-bin`, the Pi-Bolt executable, in the same process, and
forks the helper that starts the programs Pi runs ([ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port)). The figures are of the
release builds, launcher included. The helper adds 0.3 ms to `pi -p` (1%; nothing to `pi --version`, which starts nothing), and
each program Pi starts takes 1.4 ms more to start and be waited for (`spawnSync` of `/usr/bin/true`: 2.1 ms against 0.7). None of
the scenarios above starts a program. The programs' CPU time is not counted in Pi's on macOS (`wait4` counts a process's
children; the programs are the helper's).

## Linux on ARM64

Pi-Bolt's linux-arm64 build (Pi 1.2.0, the code for ARMv8.0 that is released) against Pi 1.2.0 on stock Bun 1.4.2 and on Node 24,
in Docker Desktop's Linux VM on an Apple M5 (arm64, 4 KB pages), pinned to four of its cores. Medians of 15 runs of each scenario
(after 3 warm-up runs) and of 4 tmux rounds; raw data in
[`bench/results/2026-10-15-linux-arm64-0.8.8`](../bench/results/2026-10-15-linux-arm64-0.8.8).

#### Time

|  | Pi-Bolt | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Launch to interactive (TUI) | 29 ms | 45 ms | 114 ms |
| pi --version | 5 ms | 10 ms | 83 ms |
| pi -p: one prompt, 4 tool calls | 27 ms | 52 ms | 142 ms |
| Launch to interactive (TUI), default theme | 23 ms | 46 ms | 115 ms |

#### CPU

|  | Pi-Bolt | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Interactive session: 5 prompts | 115 ms | 316 ms | 401 ms |
| pi -p: one prompt | 27 ms | 86 ms | 210 ms |
| pi --version | 5 ms | 11 ms | 95 ms |

#### Memory and streaming

|  | Pi-Bolt | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Peak memory, interactive session | 129 MB | 157 MB | 179 MB |
| Own memory, tmux session | 37 MB | 75 MB | 79 MB |
| Streaming replies (tmux), CPU | 627 ms | 800 ms | 849 ms |
| Launch to interactive in tmux | 64 ms | 72 ms | 141 ms |

## Windows x64

Pi-Bolt 0.8.5 against 0.8.0 on the same laptop (on battery, best performance; both from their release archives; medians of 21
runs and of 3 long sessions, means of two rounds for long answers and files):

| | Pi-Bolt 0.8.5 | Pi-Bolt 0.8.0 |
|---|---:|---:|
| Writing a 200 KB file through a tool call | **0.5 s** | 0.7 s |
| CPU, streaming a 60,000-character answer | **6.9 s** | 7.0 s |
| Private working set, end of 4.2M-token session | **122 MB** | 142 MB |
| Private working set, TUI streaming in a ConPTY | **32 MB** | 32 MB |
| CPU, interactive session (5 prompts) | **278 ms** | 318 ms |
| CPU, `pi -p` | **92 ms** | 100 ms |
| Launch to interactive (TUI) | 105 ms | 105 ms |

The full suite (`bench\run-suite.ps1`) on Windows 11 25H2 (26200) on an Intel Core i5-1335U laptop (16 GB, on AC, best
performance, Defender real-time protection on): Pi-Bolt 0.8.0 from `1d373d18b` (Pi 1.1.0; the runtime `8bcf03bc0` with ThinLTO and
Control Flow Guard, Pi compiled ahead of time for this CPU, JIT off, the prebuilt heap laid out by the Pi 1.1.0 profile), Pi
1.1.0 as released on stock Bun 1.4.2, and Pi 1.1.0's bundle on Node 22.23.3 and Node 24.21.0. Medians of 11 runs (2 warm-up),
4 long sessions of 75 prompts, 5 streaming rounds; streaming runs in a ConPTY rather than tmux (`bench\conpty_check.py`: ConPTY
passes frames on at about 16 ms, which is the floor of the frame times). Raw data and charts:
[`bench/results/2026-10-10-windows-0.8.0`](../bench/results/2026-10-10-windows-0.8.0).

| | Pi-Bolt 0.8.0 | Pi 1.1.0 on Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | **92 ms** | 177 ms | 345 ms | 311 ms |
| `pi --version` | **35 ms** | 98 ms | 242 ms | 219 ms |
| `pi -p`: one prompt, 4 tool calls | **113 ms** | 203 ms | 438 ms | 383 ms |
| Time per prompt, 4.2M-token session | **400 ms** | 506 ms | 816 ms | 794 ms |
| CPU, interactive session (5 prompts) | **291 ms** | 805 ms | 1,206 ms | 1,125 ms |
| CPU, `pi -p` | **92 ms** | 329 ms | 582 ms | 544 ms |
| CPU, `pi --version` | **17 ms** | 133 ms | 277 ms | 268 ms |
| CPU per prompt, 4.2M-token session | **166 ms** | 298 ms | 684 ms | 634 ms |
| Peak working set, interactive session | **95 MB** | 164 MB | 165 MB | 178 MB |
| Peak private working set, interactive session | **56 MB** | 126 MB | 132 MB | 138 MB |
| Peak private bytes (commit), interactive session | **113 MB** | 345 MB | 176 MB | 197 MB |
| Private working set, TUI streaming in a ConPTY | **32 MB** | 97 MB | 105 MB | 60 MB |
| Private working set, end of 4.2M-token session | **159 MB** | 248 MB | 391 MB | 413 MB |
| Private bytes, end of 4.2M-token session | **245 MB** | 465 MB | 437 MB | 467 MB |
| CPU, streaming replies (ConPTY) | **840 ms** | 982 ms | 1,468 ms | 1,285 ms |
| Frame time p99, streaming replies | **17 ms** | 17 ms | 20 ms | 19 ms |
| Frame time p99 / longest stall, 50 KB file written | **18 / 107 ms** | 59 / 3,612 ms | 75 / 2,727 ms | 64 / 858 ms |
| Frame time p99 / longest stall, 200 KB file written | **18 / 63 ms** | 154 / 43,934 ms | 302 / 57,962 ms | 71 / 46,595 ms |
| GC pause p99, 20,000-char answer / longest GC pause | 2.9 / 6.3 ms | 4.3 / 11 ms | **1.6 / 3.6 ms** | 1.6 / 8.4 ms |

Pi-Bolt is the fastest and uses the least memory in every row but the garbage collector's pauses, where Node is shorter (its
p99 by about a millisecond, its longest pause by 2.7 ms).

These come from memory committed on demand, the prebuilt heap laid out in first-use order, and the runtime's code laid out in
the order it runs ([WINDOWS.md](WINDOWS.md)). Each 50 KB and 200 KB step ends with a `bash` tool call. The only `bash.exe` on
this machine is the WSL launcher with no distribution installed, which takes about 0.1 s to start and fail. That is Pi-Bolt's
longest stall of the 50 KB step, and every build pays it.

Plugins: the example plugin's loop takes 41 ms compiled into the executable. Loaded at run time it takes 756 ms with the JIT off,
and 32 ms in the build with the JIT on, as on Bun (32 ms).

**What an executable costs Windows when it is not running.** Windows charges an image's uninitialized sections to the system's
commit for as long as it keeps the image cached, after the process has exited (process memory does not show it;
[WINDOWS.md](WINDOWS.md#what-was-measured)). `bench/image_commit.py`, medians of 5: Pi on Bun 1 MB; Pi-Bolt 33 MB (the 32 MB of the
region that has to be in the image); before that was fixed, 1,029 MB.


## Against PiG and oh-my-pi

The same runs as above also measure two other fast builds of Pi: [PiG](https://github.com/MichaelKinsy/PiG) 0.4.1
(`@pi-in-go/pig`), the Go port of Pi, based on Pi 1.0.3, and [oh-my-pi](https://github.com/can1357/oh-my-pi) 18.8.7 (`omp`), a
fork of Pi that runs on Bun. All five builds run interleaved, on the same cores; the tables in the
[README](../README.md#benchmarks) take the medians of 21 runs (`bench/benchmark.py`), the mean of two rounds for long answers and
large files, and the medians of the long sessions (75 prompts, three each) and the tmux rounds. PiG reads its own names for Pi's
settings (`PIG_HOME`, `PIG_CODING_AGENT_DIR`, `PIG_OFFLINE`) and oh-my-pi reads `models.yml`; `bench/harness.py` gives both the
same local fake model as the others.

oh-my-pi compacts a long conversation on its own: by prompt 25 it sends 0.5 MB where the others send 5.6 MB, and it stops
answering the scripted session after that, so it is left out of the long session. Raw data: as above,
[`bench/results/2026-10-12-linux-x64-0.8.5`](../bench/results/2026-10-12-linux-x64-0.8.5) and
[`bench/results/2026-10-12-darwin-arm64-0.8.5`](../bench/results/2026-10-12-darwin-arm64-0.8.5). oh-my-pi runs with
`OMP_SKIP_SETUP=1`, which skips the setup screen it shows on a first start.

## Against OpenCode 2.0

[OpenCode](https://github.com/anomalyco/opencode) 2.0.27 (`@opencode/cli`, its release binary for each platform), against
Pi-Bolt 0.8.5 in a run of its own on the same machines, with the same tools and the same local model. `bench/opencode.sh` turns
the tools' Pi arguments into OpenCode's and writes its configuration: an OpenAI-compatible provider pointing at the local model,
and automatic compaction off, so that a long session sends the whole conversation as the others do. OpenCode runs with
`--standalone`, a server of its own started by the process, so that the server's CPU is counted (`wait4` includes it) and each run
starts fresh; its memory figures are those of the process started, without the server's. OpenCode's read and write tools take
the same `path` argument as Pi's, so the scripted model does the same work for both. OpenCode opens its command menu on `/`, so the
harness waits for it before the Enter of `/quit`.

| | macOS: Pi-Bolt | macOS: OpenCode | Linux: Pi-Bolt | Linux: OpenCode |
|---|---:|---:|---:|---:|
| Ready to type | **41 ms** | 1,489 ms | **39 ms** | 1,687 ms |
| `pi -p`: one prompt, 4 tool calls | **55 ms** | 738 ms | **61 ms** | 1,153 ms |
| CPU, `pi -p` | **49 ms** | 1,548 ms | **62 ms** | 2,220 ms |
| CPU, interactive session (5 prompts) | **133 ms** | 6,493 ms | **192 ms** | 8,593 ms |
| Peak memory, interactive session | **94 MB** | 443 MB | **149 MB** | 408 MB |
| CPU per prompt, long session, prompt 25 | **34 ms** | 372 ms | **49 ms** | 475 ms |
| Time per prompt, long session, prompt 75 | **323 ms** | 1,454 ms | **426 ms** | 1,902 ms |
| CPU per prompt, long session, prompt 75 | **108 ms** | 480 ms | **177 ms** | 679 ms |
| Own memory, long session, prompt 75 | **38 MB** | 260 MB | **117 MB** | 273 MB |
| Writing a 200 KB file through a tool call | **0.4 s** | 1.7 s | **0.6 s** | 2.4 s |
| CPU, streaming a 60,000-character answer | **6.3 s** | 13.6 s | **2.6 s** | 8.6 s |

Medians of 21 runs and of 3 long sessions of 75 prompts, means of two rounds for long answers and files. OpenCode's own cost per
prompt is high from the start (about 0.4 s of CPU) and grows slowly with the conversation, so the gap narrows from about ten
times at prompt 25 to about four at prompt 75; its requests are also larger (19.1 MB at prompt 75 against Pi's 16.9 MB). In tmux
OpenCode did not finish all of its answers on either machine, so it has no tmux figures. Raw data:
[`bench/results/2026-10-12-linux-x64-opencode-2.0`](../bench/results/2026-10-12-linux-x64-opencode-2.0) and
[`bench/results/2026-10-12-darwin-arm64-opencode-2.0`](../bench/results/2026-10-12-darwin-arm64-opencode-2.0).

## With a real model

The tables above use a local scripted model, which answers instantly and the same way every time, so they measure Pi and its
runtime alone. These use a hosted model over the internet, with thinking at its highest setting, as in daily use. They were
taken with Pi-Bolt 0.6.1 against Pi 1.0.0 as released, and have not been taken again since.
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
| Pi's CPU, range | 0.17–0.23 s | 0.45–0.52 s | 0.69–0.89 s | 0.73–0.79 s |
| Peak memory, median | **124 MB** | 131 MB | 126 MB | 135 MB |

- **Wall time:** no runtime is faster or slower. In a permutation test, differences in median as large as these came out by
  chance 65–97% of the time: they are the model's variation.
- **CPU:** the difference does not overlap. Pi-Bolt's slowest run used less than Bun's fastest.

**A long answer, `pi -p`:** about 2,500 words of Markdown with three TypeScript code blocks, answered in the chat. One run per runtime.

| | Pi-Bolt | Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Answer | 19,803 characters | 20,116 characters | 19,103 characters | 20,305 characters |
| Wall time | 51 s | 53 s | 40 s | 44 s |
| **Pi's CPU** | **0.47 s** | 0.91 s | 1.06 s | 1.06 s |
| Peak memory | **124 MB** | 129 MB | 125 MB | 137 MB |

**The interactive TUI in tmux:** a 160×48 pane, "read README.md and summarize it in two sentences". One run per runtime.

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
- 40–50% of the CPU of Pi on Bun and a quarter to a half of Node's;
- a third of Bun's memory, and less than a quarter of Node's.

The measurements are in [`bench/results/2026-10-04-darwin-arm64-0.6.1-real-model`](../bench/results/2026-10-04-darwin-arm64-0.6.1-real-model).

## Setup and method

| | |
|---|---|
| Pi | 1.1.0 (`v1.1.0`) |
| Linux machine | AMD EPYC 7B13 (Zen 3), Linux 7.0, every run pinned to the same 4 cores (the least busy of a shared VM) |
| macOS machine | Apple M5 MacBook Air, macOS 27.0.1, not pinned |
| **Pi-Bolt 0.8.5** | `pi-bolt-linux-x64` and `pi-bolt-darwin-arm64` from the release: JIT off |
| **PiG 0.4.1**, **oh-my-pi 18.8.7** | their release binaries for each platform (`pig`, `omp`) |
| **Bun 1.4.2** | Pi 1.1.0 as released (`scripts/prepare-pi.sh --tag v1.1.0`), built with `scripts/build-pi.sh --stable`: Pi's own `bun build --compile` command plus `--bytecode` |
| **Node 24.21** (Linux), **Node 26.11** (macOS) | Pi 1.1.0 as released: its npm bundle (`dist/bundle/cli.js`, with Node's compile cache) |

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

Pi in a 160×48 tmux pane ([`bench/tmux_check.py`](../bench/tmux_check.py)): keys sent with `send-keys`, the screen read back
with `capture-pane`, and the model streaming at human pace, one event every 10 ms.

| | Pi-Bolt 0.8.5 | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Streaming a 20,000-character answer: CPU | **0.6 s** | 8.0 s | 6.3 s |
| Streaming a 60,000-character answer: CPU | **2.6 s** | 40.2 s | 38.7 s |
| Share of a core while it streams (60,000 characters) | **5%** | 80% | 77% |
| Writing a 200 KB file through a tool call (`pi -p`) | **0.6 s** | 24.0 s | 32.5 s |
| Writing a 200 KB file through a tool call: CPU | **0.2 s** | 32.5 s | 33.1 s |

On the Mac: streaming a 60,000-character answer took 6.5 s of CPU (Pi on Bun 37.9 s, on Node 37.6 s), and writing a 200 KB file
0.4 s (Bun 22.6 s, Node 28.1 s).

`bench/long_answer.py` streams Markdown answers (headings, lists, code blocks in several languages, tables) into the TUI at 1,200
characters a second, 24 at a time, and measures the CPU Pi uses until the answer has been drawn. `bench/large_write.py` has the
model write a file of TypeScript through the `write` tool, its arguments streaming 16 characters at a time (about one token),
and measures `pi -p` from start to exit. Means of two runs on Linux, pinned to 4 cores. Bun and Node run Pi 1.1.0 as released.

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
| **Compiled in**, Pi-Bolt (default, JIT off) | 52 ms | 55 ms |
| Compiled in, Pi-Bolt JIT on | **50 ms** | 68 ms |
| Loaded at run time, Pi-Bolt JIT off (interpreted) | 91 ms | 1,315 ms |
| Loaded at run time, Pi-Bolt JIT on | 83 ms | 48 ms |
| Loaded at run time, Bun 1.4.2 | 244 ms | **45 ms** |

Linux, medians of 5 sessions, each running `/words` 5 times, Pi-Bolt 0.7.3. The hot-loop figure is the median of runs 2–5.

## Questions

### Why is a run-time plugin's loop slow on the default build?

Because that plugin's code is not compiled at all on that build: it runs in JavaScriptCore's bytecode interpreter.

- The default Pi-Bolt executable runs with the **JIT off**. Pi's own code does not need a JIT: all of it was compiled to machine
  code when the executable was built.
- A plugin loaded at **run time** (from `~/.pi/agent/extensions`, a project's `.pi/extensions` or a Pi package) is not part of
  that build. `jiti` turns its TypeScript into JavaScript when Pi starts. With no JIT, JavaScriptCore can then only interpret
  it, and an interpreter runs a tight loop 20–30× slower than compiled code.
- Stock Bun's JIT compiles the loop after a few thousand iterations, which is how it gets to 45 ms.

This is not a regression. The figure is the same in every Pi-Bolt release: it is the cost of loading code at run time into a
runtime with no JIT. There are two ways around it:

| | Plugin hot loop |
|---|---:|
| Compile the plugin in: `scripts/build-pi.sh --plugins` ([PLUGINS.md](PLUGINS.md)) | 55 ms, and nothing measurable at launch instead of 39 ms |
| Use the JIT-on build (`pi-bolt-linux-x64-jit`) and keep loading it at run time | 48 ms after warm-up (Bun: 45 ms) |
| Load it at run time on the default (JIT-off) build | 1,315 ms |

## Reproduce

```bash
scripts/package-release.sh                      # out/pi-bolt, out/pi-bolt-baseline, out/pi-bolt-jit
scripts/build-pi.sh --plugins examples/plugins/plugins.ts --out out/pi-bolt-plugins
scripts/build-pi.sh --plugins examples/plugins/plugins.ts --jit on --out out/pi-bolt-plugins-jit
scripts/prepare-pi.sh --tag v1.1.0 --dir ../pi-1.1.0                              # Pi as released
scripts/build-pi.sh --stable --pi ../pi-1.1.0 --out out/pi-stable-upstream
PIBOLT_PI=../pi-1.1.0 PIBOLT_STABLE_PI=out/pi-stable-upstream/pi PIBOLT_COMPARE="pi-bolt-0.7.0=<0.7.0's pi>" \
	bench/run-suite.sh bench/results/my-run --cpus 8-15   # about an hour; then charts and tables from bench/report.py
B="--cpus 8-15 --build pi-bolt=out/pi-bolt/pi --build bun=out/pi-stable-upstream/pi"
python3 bench/long_answer.py $B --sizes 20000,60000
python3 bench/large_write.py $B --sizes 50,200
python3 bench/tmux_load.py $B --steps md:20000
```

Each tool also runs on its own, with any builds given as `--build name=command`. See [`bench/README.md`](../bench/README.md).

## Correctness

Speed is only measured on builds that pass the correctness checks:

- `bench/e2e_tools.py`: every Pi tool, with output and files byte-identical to stock Bun's.
- `bench/ui_check.py` and `bench/tmux_check.py`: the TUI.
- `tests/aot/run.sh`: engine tests, and `tests/aot/fuzz/run.sh`: random programs compiled ahead of time must print what they
  print as bytecode.
- JavaScriptCore's own stress tests, run with and without AOT compilation: 4,779 of the 4,786 that run behave the same. The 7
  that differ inspect JIT internals that do not exist without a JIT.

See [BUILDING.md](BUILDING.md#tests).
