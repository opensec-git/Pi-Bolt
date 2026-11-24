# Pi-Bolt 1.3.1 against the published 1.2.1 and 1.3.0

We compared the official JIT-off packages of Pi-Bolt 1.2.1 (Pi 1.5.2) and Pi-Bolt 1.3.0 (Pi 1.6.0) with Pi-Bolt 1.3.1 (Pi
1.6.0) built from `release/1.3.1` at `fb75700f2` on the released 1.3.0 runtimes. The three builds ran on the same host, interleaved
in every workload with the order rotating between rounds, on macOS and on Linux at the same time, two passes per host. All model
workloads used a loopback provider. Each table gives the medians of 1.2.1, 1.3.0 and **1.3.1**, and the change of 1.3.1 from 1.2.1
and from 1.3.0. A negative change means 1.3.1 used less. A change marked * has p < 0.05 in a two-sided Mann-Whitney U test of the
runs. Memory is macOS physical footprint or Linux private dirty memory, compared within each OS. Column n is the number of runs per
build.

## Builds

| Build | File | SHA-256 |
|---|---|---|
| 1.2.1, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.2.1](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.2.1), as in its `SHA256SUMS` | `7cc2d4f27541af48d976a8c30032aedb7abdd0cf1544ae15229d882363a4d8ad` |
| | its `pi-bin` | `ec44db0d3e6130bcf3b74163e7fa0e6d1276300e2fca7fa5c67e2d9a1fc54f8f` |
| 1.2.1, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.2.1, as in its `SHA256SUMS`; started with `pi` (no `pi-start` in it) | `db1caa7f07a733a402677569fe44fda15c19a42402f0940d5a6934ebcc9df4da` |
| 1.3.0, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.3.0](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.3.0), as in its `SHA256SUMS` | `1079b3c89b095491cefb4bbc978ff71e223a89ef83a10ab080ef27503c9434ae` |
| | its `pi-bin` | `e93ca85413c7c2dcd04fe8a9cd444637f8fc66f06c1d379c1fda7cc94930ab49` |
| 1.3.0, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.3.0, as in its `SHA256SUMS`; started with its `pi-start` | `6d15e573d7eeebfecf10c1e1bab288deb0ffd773d9315b682c0c32fedb3aea58` |
| 1.3.1, macOS | runtime `pi-bolt-runtime-darwin-arm64.tar.gz` from bolt-v1.3.0, as in its `SHA256SUMS` | `7701f25ff372aff217b7717bd1de590a7c5ef8e104cb221901842d2cbd207151` |
| | built `pi-bin` | `69b8d3de60116ea770efa792899d1577eb481d7363c5687dabc473c258fd4544` |
| 1.3.1, Linux | runtime `pi-bolt-runtime-linux-x64.tar.gz` from bolt-v1.3.0, as in its `SHA256SUMS` | `88169bd293739905c5a5048e45da66bcc6808b47e6d9f82481e28b89295c82f4` |
| | built `pi` | `30b5c0427ef85c749ca21b12ba4160a7bebb86d6216259520da3d996cad47f9f` |
| | built `pi-start`, which started it | `1b0eeb806bfe478fd8456914fad87ff0ab622e1438f0bbb0788dd8dbc3780d15` |

Both 1.3.1 builds are `npm run build:offline` and then `scripts/build-pi.sh` with its defaults (JIT off, training profile
`profiles/pi-1.6.0`) at `fb75700f2`, on the runtime the 1.3.0 release ships; `scripts/runtime-stamp.sh` gives `13ef7565ee8ee6b5` for the
tree and the runtime's `RUNTIME_STAMP` says the same. Both print `1.6.0 (Pi-Bolt 1.3.1, …, JIT off)`.

Hosts: an Apple M5 MacBook Air (10 cores, 16 GB) on mains power, macOS 27.2, not pinned, with a 1-minute load average of 1.9 to 5.9
while measuring (each pass started below 4; a music player and a development server from another session kept running); an AMD EPYC 9355P
(64 threads) shared with other users, Linux 7.0, at a load average of 6 to 18 in the first pass and 6 to 142 in the second. The
Linux host gives no access to hardware counters, so Linux rows have CPU time and no instructions.

## Workloads, macOS arm64

| Measurement | n | 1.2.1 | 1.3.0 | 1.3.1 | 1.3.1 vs 1.2.1 | 1.3.1 vs 1.3.0 |
|---|---:|---:|---:|---:|---:|---:|
| Blank-home launch to visible typing, ms | 60 | 18.76 | 17.45 | **17.49** | -6.8%* | +0.2% |
| HEADLESS single answer · elapsed time, ms | 24 | 19.98 | 19.29 | **18.50** | -7.4% | -4.1% |
| HEADLESS single answer · client CPU, ms | 24 | 15.72 | 14.92 | **14.90** | -5.3% | -0.2% |
| RPC single answer · elapsed time, ms | 24 | 3.56 | 3.54 | **3.59** | +0.9% | +1.3% |
| RPC single answer · client CPU, ms | 24 | 2.38 | 2.43 | **2.50** | +5.0% | +2.9% |
| RPC single answer · instructions, M | 24 | 22.37 | 23.63 | **23.77** | +6.3%* | +0.6%* |
| RPC single answer · final private memory, MiB | 24 | 14.3 | 14.0 | **14.0** | -2.1%* | +0.0% |
| TUI single answer · elapsed time, ms | 24 | 15.74 | 17.89 | **17.11** | +8.7%* | -4.4%* |
| TUI single answer · client CPU, ms | 24 | 15.65 | 17.82 | **16.92** | +8.1%* | -5.0%* |
| TUI single answer · instructions, M | 24 | 89.44 | 87.53 | **83.20** | -7.0%* | -4.9%* |
| TUI single answer · final private memory, MiB | 24 | 18.9 | 18.5 | **17.9** | -5.0%* | -3.2%* |
| 101 interactive tool prompts · elapsed ms/prompt | 12 | 53.53 | 51.38 | **50.62** | -5.4%* | -1.5% |
| 101 interactive tool prompts · client CPU ms/prompt | 12 | 28.60 | 26.06 | **25.59** | -10.5%* | -1.8% |
| 101 interactive tool prompts · final private MiB | 12 | 88.45 | 55.15 | **56.45** | -36.2%* | +2.4% |
| 6,000 restored messages in RPC · elapsed ms/prompt | 12 | 7.38 | 6.32 | **6.28** | -15.0%* | -0.6% |
| 6,000 restored messages in RPC · client CPU ms/prompt | 12 | 3.83 | 2.77 | **2.74** | -28.5%* | -1.3% |
| 6,000 restored messages in RPC · final private MiB | 12 | 49.35 | 50.90 | **51.25** | +3.9%* | +0.7% |
| Post-answer idle CPU, ms/s | 24 | 0.580 | 0.721 | **0.677** | +16.7%* | -6.1% |
| Post-answer idle instructions, M/s | 24 | 1.159 | 1.353 | **1.352** | +16.6%* | -0.1% |
| Post-answer idle private memory, MiB | 24 | 18.8 | 17.9 | **18.0** | -4.3%* | +0.6%* |

## Workloads, Linux x64

| Measurement | n | 1.2.1 | 1.3.0 | 1.3.1 | 1.3.1 vs 1.2.1 | 1.3.1 vs 1.3.0 |
|---|---:|---:|---:|---:|---:|---:|
| Blank-home launch to visible typing, ms | 60 | 19.28 | 17.86 | **17.98** | -6.7%* | +0.7% |
| HEADLESS single answer · elapsed time, ms | 24 | 19.33 | 19.78 | **19.22** | -0.6% | -2.8% |
| HEADLESS single answer · client CPU, ms | 24 | 19.26 | 19.61 | **19.20** | -0.3% | -2.1% |
| RPC single answer · elapsed time, ms | 24 | 5.93 | 5.90 | **5.78** | -2.6% | -2.1% |
| RPC single answer · client CPU, ms | 24 | 4.71 | 4.77 | **4.81** | +2.1% | +0.8% |
| RPC single answer · final private memory, MiB | 24 | 11.2 | 10.9 | **10.9** | -2.7%* | +0.0% |
| TUI single answer · elapsed time, ms | 24 | 13.18 | 13.78 | **12.37** | -6.2% | -10.2% |
| TUI single answer · client CPU, ms | 24 | 13.98 | 14.73 | **13.11** | -6.3% | -11.0% |
| TUI single answer · final private memory, MiB | 24 | 15.5 | 15.1 | **14.9** | -4.2%* | -1.7%* |
| 101 interactive tool prompts · elapsed ms/prompt | 12 | 53.68 | 49.57 | **49.48** | -7.8%* | -0.2% |
| 101 interactive tool prompts · client CPU ms/prompt | 12 | 28.14 | 22.44 | **22.07** | -21.6%* | -1.7% |
| 101 interactive tool prompts · final private MiB | 12 | 92.20 | 60.35 | **59.35** | -35.6%* | -1.7%* |
| 6,000 restored messages in RPC · elapsed ms/prompt | 12 | 9.43 | 8.45 | **7.92** | -16.0%* | -6.3% |
| 6,000 restored messages in RPC · client CPU ms/prompt | 12 | 5.37 | 4.10 | **3.88** | -27.8%* | -5.4% |
| 6,000 restored messages in RPC · final private MiB | 12 | 57.00 | 49.75 | **49.30** | -13.5%* | -0.9% |
| Post-answer idle CPU, ms/s | 24 | 0.842 | 0.447 | **0.447** | -46.9%* | +0.0% |
| Post-answer idle private memory, MiB | 24 | 13.9 | 14.5 | **14.5** | +4.3%* | +0.0% |

The startup probe uses a fresh home and project for every launch and waits for the first painted keystrokes. Single-answer tests
use the same 5,000-character Markdown answer and check its digest. Headless includes launch. RPC and TUI time the answer after
readiness. The 101-prompt test replays 505 HTTPS requests per run with the same tools and system prompt. The restored-session test
seeds 6,000 text messages, warms up with three prompts, then verifies 60 answers. The idle test measures 15-second windows after a
tool-using answer and two seconds of settling.

## Special operations, macOS arm64

| Measurement | n | 1.2.1 | 1.3.0 | 1.3.1 | 1.3.1 vs 1.2.1 | 1.3.1 vs 1.3.0 |
|---|---:|---:|---:|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 12 | 0.1722 | 0.0114 | **0.0113** | -93.5%* | -1.0% |
| get_session_stats on a 12,000-entry session, CPU ms/query | 12 | 0.2770 | 0.0114 | **0.0106** | -96.2%* | -7.0% |
| linear session tree search, elapsed ms/keystroke | 12 | 4.47 | 2.73 | **2.74** | -38.6%* | +0.5% |
| linear session tree search, client CPU ms/keystroke | 12 | 4.46 | 3.06 | **3.22** | -27.7%* | +5.1% |
| branched session tree search, elapsed ms/keystroke | 12 | 40.20 | 1.39 | **1.37** | -96.6%* | -1.8% |
| branched session tree search, client CPU ms/keystroke | 12 | 40.21 | 1.34 | **1.32** | -96.7%* | -1.4% |
| 8 Mi characters of nested edit text, private MiB at delivery | 36 | 163.5 | 38.0 | **38.4** | -76.5%* | +1.1%* |
| 8 Mi characters of nested edit text, client CPU ms | 36 | 89.4 | 12.7 | **12.7** | -85.8%* | -0.3% |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 36 | 2810.3 | 19.2 | **19.0** | -99.3%* | -1.3% |
| 32 Mi characters of nested edit text, private MiB at delivery | 36 | 979.5 | 39.7 | **40.0** | -95.9%* | +0.8% |
| 32 Mi characters of nested edit text, client CPU ms | 36 | 493.3 | 33.6 | **32.3** | -93.5%* | -3.9% |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 36 | 10482.0 | 51.7 | **53.2** | -99.5%* | +2.9% |
| grep tool, 7 searches of a source tree in `pi -p`, elapsed ms | 24 | 932 | 936 | **932** | +0.0% | -0.4% |
| grep tool, 7 searches of a source tree in `pi -p`, client CPU ms | 24 | 3304 | 3310 | **3302** | -0.1% | -0.2% |
| grep tool, 7 searches of a source tree in `pi -p`, instructions, M | 24 | 21024 | 21019 | **21024** | -0.0% | +0.0% |

## Special operations, Linux x64

| Measurement | n | 1.2.1 | 1.3.0 | 1.3.1 | 1.3.1 vs 1.2.1 | 1.3.1 vs 1.3.0 |
|---|---:|---:|---:|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 12 | 0.3044 | 0.0100 | **0.0094** | -96.9%* | -5.7% |
| get_session_stats on a 12,000-entry session, CPU ms/query | 12 | 0.3733 | 0.0103 | **0.0100** | -97.3%* | -2.9% |
| linear session tree search, elapsed ms/keystroke | 12 | 9.96 | 5.27 | **5.27** | -47.2%* | -0.1% |
| linear session tree search, client CPU ms/keystroke | 12 | 10.14 | 5.54 | **5.40** | -46.8%* | -2.6% |
| branched session tree search, elapsed ms/keystroke | 12 | 61.69 | 1.48 | **1.87** | -97.0%* | +26.6% |
| branched session tree search, client CPU ms/keystroke | 12 | 61.43 | 1.53 | **1.90** | -96.9%* | +24.7% |
| 8 Mi characters of nested edit text, private MiB at delivery | 36 | 175.7 | 32.4 | **32.2** | -81.7%* | -0.6%* |
| 8 Mi characters of nested edit text, client CPU ms | 36 | 118.2 | 20.4 | **19.5** | -83.5%* | -4.4% |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 36 | 2825.5 | 21.6 | **20.7** | -99.3%* | -3.8% |
| 32 Mi characters of nested edit text, private MiB at delivery | 36 | 1117.8 | 31.5 | **31.6** | -97.2%* | +0.3% |
| 32 Mi characters of nested edit text, client CPU ms | 36 | 771.7 | 36.9 | **37.0** | -95.2%* | +0.2% |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 36 | 10590.7 | 45.4 | **45.8** | -99.6%* | +1.0% |
| grep tool, 7 searches of a source tree in `pi -p`, elapsed ms | 24 | 720 | 700 | **703** | -2.5% | +0.4% |
| grep tool, 7 searches of a source tree in `pi -p`, client CPU ms | 24 | 1213 | 1188 | **1190** | -1.9% | +0.1% |

RPC clients ran 160 checked queries for each command per process. The entries query asks for the tail after entry 11,999. Tree
clients typed and cleared the filter eight times per process and checked the match count. The branched fixture has 1,024 leaves with
2,048 shared ancestors; the linear fixture has 12,000 messages. The preview test appends a large saved edit and measures until its
capped RPC preview and the following message arrive, and checks both.

The grep row is `pi -p` with a fake model that has Pi's built-in `grep` tool search the working folder seven times, one search per
turn, then answer (`drivers/grep_tree.py`, `drivers/fake_model_grep.py`). The folder is `packages/` of `fb75700f2` (`git archive`,
3,721 files, 50 MB). The seven expressions are ordinary ones (`TODO`, `Intl\.Segmenter`, `FIXME|XXX`, `AbortSignal\.timeout\(`,
`export (async )?function [a-z]+Stream`, `class [A-Za-z]+ extends Error`, and `deprecated` ignoring case), each with fewer matches
than the limit of 500, so every search reads the whole tree. Every run checks that the answer reports the same 344 result lines.
Pi-Bolt searches in-process. None of these expressions sends a line to ripgrep in 1.3.1 (the three builds' instruction counts are
equal), so the row measures the in-process search, including the bound on its work that 1.3.1 adds.

## Standard set

The rows of the release notes, from `benchmark.py` (4 runs per rotation of the build order, 3 rotations, two passes),
`long_answer.py` (60,000 characters at 1,200 characters a second, once per build order) and `measure_resume.py` (once per build
order). The typical home is `benchmark.py --home realistic`; the extensions are `opensec-pi-subagents` 0.20.12 and
`opensec-pi-todo` 2.13.1 from npm, unpacked outside any checkout. Peak memory is the peak physical footprint (macOS) or peak private
memory (Linux); for resuming it is the footprint once settled. Each cell is 1.2.1 / 1.3.0 / **1.3.1**, then the change of 1.3.1
from 1.2.1 / from 1.3.0.

macOS:

| | n | Instructions | CPU | Peak memory |
|---|---:|---|---|---|
| `pi --version` | 24 | 54 / 54 / **54** M (-0.3%* / -0.2%) | 6 / 6 / **6** ms (-4.0% / -3.1%) | 6 / 6 / **6** MB (+0.0% / +0.0%) |
| `pi -p` (5 model turns), blank home | 24 | 189 / 169 / **168** M (-10.9%* / -0.7%*) | 23 / 21 / **21** ms (-11.5%* / -2.3%) | 20 / 19 / **19** MB (-7.4%* / +0.0%) |
| `pi -p`, typical home | 24 | 206 / 187 / **185** M (-10.0%* / -0.9%*) | 24 / 22 / **22** ms (-8.3%* / -1.1%) | 21 / 20 / **20** MB (-7.3%* / +0.0%) |
| `pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo` | 24 | 438 / 424 / **424** M (-3.2%* / +0.0%) | 59 / 57 / **56** ms (-4.1%* / -0.3%) | 33 / 32 / **32** MB (-2.9%* / +0.9%*) |
| Interactive, 5 prompts | 24 | 560 / 438 / **428** M (-23.5%* / -2.1%*) | 74 / 75 / **79** ms (+6.1% / +5.6%) | 27 / 25 / **25** MB (-6.0%* / -0.2%) |
| 60,000-character answer, per frame | 12 | 1.26 / 1.26 / **1.26** M (+0.0% / +0.0%) |  |  |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 12 | 531 / 424 / **429** M (-19.1%* / +1.2%) |  | 74 / 28 / **29** MB (-61.3%* / +0.9%) |
| Resuming, until drawing settles | 12 | 6334 / 493 / **497** M (-92.2%* / +0.7%) | 461 / 51 / **51** ms (-89.0%* / -0.4%) |  |

Linux:

| | n | CPU | Peak memory |
|---|---:|---|---|
| `pi --version` | 24 | 4 / 4 / **4** ms (-5.9% / +0.0%) | 2 / 2 / **2** MB (+0.0% / +0.0%) |
| `pi -p` (5 model turns), blank home | 24 | 29 / 27 / **27** ms (-9.3%* / -1.1%) | 17 / 15 / **15** MB (-8.4%* / +0.7%) |
| `pi -p`, typical home | 24 | 30 / 28 / **28** ms (-7.8%* / -1.0%) | 17 / 16 / **16** MB (-8.7%* / -0.3%) |
| `pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo` | 24 | 71 / 66 / **68** ms (-3.2% / +2.9%) | 29 / 28 / **28** MB (-3.4%* / +0.0%) |
| Interactive, 5 prompts | 24 | 106 / 91 / **88** ms (-16.8%* / -3.5%) | 26 / 24 / **23** MB (-11.2%* / -1.1%*) |
| 60,000-character answer, per frame | 12 | 0.55 / 0.48 / **0.43** ms (-21.5% / -10.0%) |  |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 12 | 67 / 62 / **60** ms (-11.3% / -3.2%) | 76 / 26 / **26** MB (-65.3%* / +1.0%*) |
| Resuming, until drawing settles | 12 | 958 / 80 / **79** ms (-91.8%* / -1.3%) |  |

In the interactive row on macOS, instructions and peak footprint come from the 21, 16 and 21 of 24 runs per build in which the
harness read them at exit. Its CPU column moved between runs: the follow-up in [mac-followup](mac-followup) (24 runs per build, three rotations)
gives 85.6 / 83.0 / **84.4** ms.

## Where 1.3.1 is not ahead of 1.2.1

Every row in which 1.3.1's median is not below 1.2.1's (`summarize.py`, "Not ahead of the baseline"), with the follow-up runs
and what causes it where known.

- **RPC first answer after launch, macOS**: 22.37 → 23.77 M instructions (+6.3%, n=24, p < 0.001), CPU 2.38 → 2.50 ms (+5.0%,
  p=0.068); follow-up 2.19 → 2.26 ms (+3.3%, n=24, p < 0.001), elapsed +1.4%. Cause: Pi 1.6.0 moved the OpenAI SDK from 7.19 to
  7.59, and the larger SDK is loaded and its client constructed at the first request. The attribution build (1.3.0's source with
  `openai` 7.19.0, [mac-sdk](mac-sdk)) answers with 22.64 M (+0.6% over 1.2.1) and 2.52 ms CPU (-0.3%), against 23.82 M and
  2.62 ms for 1.3.1 and 22.52 M for 1.2.1 in the same run: about 1.2 of the 1.3 M instructions are the SDK. 1.3.0 is at the
  same level (23.63 M). On Linux the row is within noise in this run: CPU 4.71 → 4.81 ms (+2.1%, p=0.45), elapsed 5.93 → 5.78 ms (-2.6%).
- **TUI first answer after launch, CPU time on macOS**: 15.65 → 16.92 ms (+8.1%, n=24, p=0.035); follow-up 15.68 → 17.02 ms (+8.5%,
  p < 0.001), while instructions went 89.6 → 83.3 M (-7.0%) and cycles 40.0 → 37.1 M (-7.1%) with the same instructions per cycle
  (2.24). 1.3.0 measured 17.70 ms (+12.9%). The answer ran at fewer cycles per millisecond of CPU time (1.2.1 2.65, 1.3.1 2.19) and
  with more system time (1.72 → 2.02 ms); in the follow-up all three builds ran on the efficiency cores. The OpenAI SDK is not
  the cause: the attribution build measured +12.9% too. Not isolated further. On Linux, where the counters are not readable, the row is
  13.98 → 13.11 ms (-6.3%, p=0.42).
- **Idle CPU after an answer, macOS**: 0.580 → 0.677 ms/s (+16.7%, n=24, p < 0.001), instructions 1.159 → 1.352 M/s (+16.6%,
  p < 0.001), wakeups 1.1 → 0.9 per second. 1.3.0 is at the same level (0.721 ms/s, 1.353 M/s); the per-run values vary with
  whether a collection falls into the 15-second window (found in the 1.3.1 performance work). Cause not found. On Linux idle
  CPU went down by 46.9%.
- **Idle private memory after an answer, Linux**: 13.9 → 14.5 MiB (+0.6 MiB, +4.3%, n=24, p < 0.001), the same as 1.3.0. With the
  attribution build ([linux-sdk](linux-sdk), n=8): 1.2.1 13.9, `openai` 7.19 14.3, 1.3.1 14.5 MiB, so about 0.2 MiB is the SDK; the
  rest is outside the JS heap (allocator retention after the answer, per the 1.3.1 performance work). On macOS this row went down,
  18.8 → 18.0 MiB.
- **6,000 restored messages in RPC, final private memory, macOS**: 49.35 → 51.25 MiB (+3.9%, n=12, p=0.014); 1.3.0 50.90 MiB.
  Not investigated; in the 1.3.0 comparison the same row went 51.75 → 51.00 MiB. On Linux it went down, 57.0 → 49.3 MiB.
- **Equal**: the grep row on macOS (931.5 → 931.9 ms elapsed, 21,024 → 21,024 M instructions), the 60,000-character answer on
  macOS (1.26 M instructions per frame), and peak memory of `pi --version` on both systems (6 MB, 2 MB).
- **macOS interactive, 5 prompts, CPU** read 74 → 79 ms (+6.1%, p=0.66) in the main run; the follow-up gives 85.6 → 84.4 ms (-1.5%,
  n=24, p=0.023), with 24% fewer instructions in both.

## Rows where 1.3.1 measured worse than 1.3.0

No row is worse than 1.3.0 by more than 3% with p < 0.05 (`summarize.py`). Rows that are worse with p < 0.05 by less: RPC first
answer instructions on macOS +0.6%, macOS idle private memory +0.6%, macOS peak footprint with extensions +0.9%, macOS private
memory at delivery of the 8 Mi edit preview +1.1%, Linux private memory after resuming +1.0%.

Rows with a median more than 3% above 1.3.0 without p < 0.05 were run again with more rounds ([mac-followup](mac-followup),
[linux-followup](linux-followup)):

- **Linux branched tree search**: 1.48 → 1.87 ms per keystroke elapsed (+26.6%, n=12, p=1.0) and 1.53 → 1.90 ms CPU (+24.7%,
  p=0.93) in the two passes together. Each pass on its own has 1.3.1 at or below 1.3.0 (CPU: first pass 1.11 → 1.02 ms, second
  1.93 → 1.92 ms); the load rose between the passes, and the median of the pooled runs falls in the gap between them.
  Follow-up, 12 rounds, both orders: 2.00 → 1.97 ms CPU (-1.7%, p=0.51), 2.12 → 2.05 ms elapsed (-3.1%, p=0.44); linear
  6.62 → 6.65 ms CPU (+0.4%, p=0.89).
- **macOS interactive, 5 prompts, CPU**: 75 → 79 ms (+5.6%, n=24, p=0.88); follow-up 83.0 → 84.4 ms (+1.7%, n=24, p=0.63),
  instructions 437 → 427 M (-2.2%, p < 0.001).
- **macOS linear tree search CPU**: 3.06 → 3.22 ms (+5.1%, n=12, p=0.31); follow-up 3.07 → 3.15 ms (+2.8%, n=12, p=0.93), elapsed
  2.64 → 2.61 ms.

## Notes

- The extension packages must not sit inside a checkout of this repository: with its `node_modules` above them, the extensions'
  imports resolve to the packages there and the row measures loading those (about 1,450 M instructions instead of 430 M on macOS in
  a discarded first attempt).
- The Linux host was loaded by other users in the second pass (load average up to 142); builds were interleaved, so the
  comparison holds, but absolute Linux times of the second pass are higher than those of the first.
- During this run macOS placed the TUI answers on the efficiency cores (`answer_cores.py` reports no performance-core time for any
  build), unlike the 1.3.0 comparison; CPU times are not comparable with that report's.
- `summarize.py` here compares three builds; `measure.sh` takes three builds and rotates their order; `drivers/` are those of the
  1.3.0 comparison plus `grep_tree.py` and `fake_model_grep.py`.

## Files

- [measure.sh](measure.sh): `measure.sh A B C OUT [STEP...]` runs every workload above on three builds (launchers or extracted
  archive folders) and writes the raw results to OUT. `EXT=dir,dir` adds the extensions row, `GREP_TREE=dir` the grep row.
- [summarize.py](summarize.py): the tables above from `mac/` and `linux/`, the worse-row test, the not-ahead list and the
  follow-up tables; `--notes` also prints the standard set in the release notes' shape.
- [drivers](drivers): every script the measurements ran, with the fixtures and answers they read.
- [mac](mac), [linux](linux): raw results of both passes, with `builds.txt` (hosts, versions, SHA-256, load) and `progress.txt`
  (times and load of each step).
- [mac-followup](mac-followup), [linux-followup](linux-followup): the follow-up runs.
- [mac-sdk](mac-sdk), [linux-sdk](linux-sdk): runs with an attribution build, 1.3.0's source with `openai` 7.19.0 (the version in
  1.2.1) in place of 7.59.0.

To repeat, extract the 1.2.1 and 1.3.0 archives for a platform, build 1.3.1, and run from this folder with a new output folder
(results are appended):

```sh
EXT=/tmp/ext/opensec-pi-subagents,/tmp/ext/opensec-pi-todo GREP_TREE=/tmp/grep-tree \
  ./measure.sh 121/pi-bolt-darwin-arm64 130/pi-bolt-darwin-arm64 out/pi-bolt mac-again
python3 summarize.py --mac mac-again --linux linux-again
```
