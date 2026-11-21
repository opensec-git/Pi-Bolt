# Published Pi-Bolt 1.2.1 versus the Pi-Bolt 1.3.0 release build

We compared the official November 11 JIT-off packages of Pi-Bolt 1.2.1 (Pi 1.5.2) with Pi-Bolt 1.3.0 (Pi 1.6.0) built from `release/1.3.0` at `03338d997` with the attested 1.3.0 runtimes. Both ran on the same host, builds interleaved in every workload. All model workloads used a loopback provider. Each entry is the median for 1.2.1 → 1.3.0. A negative change means 1.3.0 used less. Memory is macOS physical footprint or Linux private dirty memory, compared within each OS. Column n is the number of runs per build.

## Builds

| Build | File | SHA-256 |
|---|---|---|
| 1.2.1, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.2.1](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.2.1), as in its `SHA256SUMS` | `7cc2d4f27541af48d976a8c30032aedb7abdd0cf1544ae15229d882363a4d8ad` |
| | its `pi-bin` | `ec44db0d3e6130bcf3b74163e7fa0e6d1276300e2fca7fa5c67e2d9a1fc54f8f` |
| 1.2.1, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.2.1, as in its `SHA256SUMS`; started with `pi` (no `pi-start` in it) | `db1caa7f07a733a402677569fe44fda15c19a42402f0940d5a6934ebcc9df4da` |
| 1.3.0, macOS | runtime `pi-bolt-runtime-darwin-arm64.tar.gz` | `cf8f3631ed99008b8730e51f821c3d63b156973d22deb07e783f60ae1b472575` |
| | built `pi-bin` | `c1b4a3172ca830f97c186a617f6d51c86e14fed83f3533b5b357c6652f806622` |
| 1.3.0, Linux | runtime `pi-bolt-runtime-linux-x64.tar.gz` | `b221b5a55514e94f68088f862fb92de50551b51ab9754a77ff04aab62bb28f0a` |
| | built `pi`, started with the built `pi-start` | `2d07705da0a7f6b9fe5d114d948a9247ae12867589da30a38a3088d212cb2dfe` |

Both 1.3.0 builds are `scripts/build-pi.sh` with its defaults: JIT off, training profile `profiles/pi-1.6.0`. The runtimes come from the draft release `bolt-v1.3.0`. `gh attestation verify <file> -R opensec-git/Pi-Bolt --signer-workflow opensec-git/Pi-Bolt/.github/workflows/runtime.yml --source-ref refs/heads/pi-bolt` passed for both: runtime.yml run 44946110942 at `3c29bca79`, Bun `1.4.3-canary.1+3c29bca79`. The tree's `scripts/runtime-stamp.sh` gives `13ef7565ee8ee6b5`. Later commits on `release/1.3.0` change `scripts/lib/train_heap.py` (used on Windows only) and add the Windows heap order to `profiles/pi-1.6.0` (`heap-functions.txt`, used on Windows only, and the strings' order in `bytecode.order`); the released builds may differ from these by that string order.

Hosts: an Apple M5 MacBook Air (10 cores, 16 GB) on battery, macOS 27.2, not pinned; an AMD EPYC 9355P (64 threads) shared with other users at a load average of 35 to 95, Linux 7.0. The Linux host gives no access to hardware counters, so Linux rows have CPU time and no instructions.

## Workloads of the candidate's comparison

The same workloads as the 1.3.0 candidate's comparison with 1.2.1, run twice per host. Idle on macOS is the second run only (see Notes).

| Measurement | n | macOS arm64 | Linux x64 |
|---|---:|---:|---:|
| Blank-home launch to visible typing, ms | 60 | 21.21 → 20.03 (-5.5%) | 23.70 → 20.98 (-11.5%) |
| HEADLESS single answer · elapsed time, ms | 24 | 16.37 → 16.58 (+1.3%) | 20.78 → 20.95 (+0.8%) |
| HEADLESS single answer · client CPU, ms | 24 | 13.02 → 13.17 (+1.2%) | 20.80 → 20.57 (-1.1%) |
| RPC single answer · elapsed time, ms | 24 | 3.07 → 3.15 (+2.4%) | 6.27 → 6.60 (+5.3%) |
| RPC single answer · client CPU, ms | 24 | 2.05 → 2.19 (+6.9%) | 5.03 → 5.37 (+6.7%) |
| RPC single answer · final private memory, MiB | 24 | 14.3 → 14.1 (-1.4%) | 11.2 → 10.9 (-2.7%) |
| TUI single answer · elapsed time, ms | 24 | 11.96 → 12.69 (+6.1%) | 16.27 → 17.31 (+6.4%) |
| TUI single answer · client CPU, ms | 24 | 11.69 → 12.22 (+4.5%) | 17.14 → 18.36 (+7.1%) |
| TUI single answer · final private memory, MiB | 24 | 18.9 → 18.5 (-2.1%) | 15.5 → 15.2 (-1.9%) |
| 101 interactive tool prompts · elapsed ms/prompt | 10 | 40.44 → 38.94 (-3.7%) | 60.12 → 55.84 (-7.1%) |
| 101 interactive tool prompts · client CPU ms/prompt | 10 | 22.16 → 20.28 (-8.5%) | 35.24 → 29.54 (-16.2%) |
| 101 interactive tool prompts · final private MiB | 10 | 94.95 → 58.95 (-37.9%) | 90.95 → 58.50 (-35.7%) |
| 6,000 restored messages in RPC · elapsed ms/prompt | 12 | 5.68 → 4.96 (-12.8%) | 13.84 → 10.92 (-21.1%) |
| 6,000 restored messages in RPC · client CPU ms/prompt | 12 | 2.94 → 2.16 (-26.6%) | 7.67 → 5.21 (-32.0%) |
| 6,000 restored messages in RPC · final private MiB | 12 | 51.75 → 51.00 (-1.4%) | 55.65 → 49.40 (-11.2%) |
| Post-answer idle CPU, ms/s | 10/20 | 0.702 → 0.788 (+12.3%) | 0.912 → 0.478 (-47.5%) |
| Post-answer idle private memory, MiB | 10/20 | 18.7 → 17.8 (-4.8%) | 13.9 → 14.6 (+5.0%) |

The startup probe uses a fresh home and project for every launch and waits for the first painted keystrokes. Single-answer tests use the same 5,000-character Markdown answer and check its digest. Headless includes launch. RPC and TUI time the answer after readiness. The 101-prompt test replays 505 HTTPS requests per run with the same tools and system prompt. The restored-session test seeds 6,000 text messages, warms up with three prompts, then verifies 60 answers. The idle test measures 15-second windows after a tool-using answer and two seconds of settling.

## Special operations

| Measurement | n | macOS arm64 | Linux x64 |
|---|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 12 | 0.1216 → 0.0085 (-93.0%) | 0.2740 → 0.0092 (-96.6%) |
| get_session_stats on a 12,000-entry session, CPU ms/query | 12 | 0.2036 → 0.0083 (-95.9%) | 0.3174 → 0.0094 (-97.0%) |
| linear session tree search, elapsed ms/keystroke | 12 | 5.15 → 3.91 (-24.1%) | 8.17 → 3.90 (-52.3%) |
| linear session tree search, client CPU ms/keystroke | 12 | 5.17 → 3.99 (-22.7%) | 8.33 → 4.24 (-49.1%) |
| branched session tree search, elapsed ms/keystroke | 12 | 34.15 → 1.37 (-96.0%) | 47.02 → 1.27 (-97.3%) |
| branched session tree search, client CPU ms/keystroke | 12 | 34.14 → 1.31 (-96.2%) | 47.04 → 1.33 (-97.2%) |
| 8 Mi characters of nested edit text, private MiB at delivery | 12 | 163.4 → 38.3 (-76.6%) | 175.7 → 30.4 (-82.7%) |
| 8 Mi characters of nested edit text, client CPU ms | 12 | 107.0 → 11.5 (-89.3%) | 119.0 → 20.5 (-82.7%) |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 12 | 2818.1 → 19.8 (-99.3%) | 2825.3 → 21.8 (-99.2%) |
| 32 Mi characters of nested edit text, private MiB at delivery | 12 | 987.8 → 41.0 (-95.8%) | 1118.7 → 34.9 (-96.9%) |
| 32 Mi characters of nested edit text, client CPU ms | 12 | 566.8 → 26.0 (-95.4%) | 799.4 → 39.4 (-95.1%) |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 12 | 10474.1 → 33.9 (-99.7%) | 10601.1 → 49.6 (-99.5%) |

RPC clients ran 160 checked queries for each command per process. The entries query asks for the tail after entry 11,999. Tree clients typed and cleared the filter eight times per process and checked the match count. The branched fixture has 1,024 leaves with 2,048 shared ancestors; the linear fixture has 12,000 messages. The preview test appends a large saved edit and measures until its capped RPC preview and the following message arrive, and checks both.

## Standard set

The rows of the 1.2.1 release notes, from `benchmark.py` (11 runs per pass, two passes), `long_answer.py` (60,000 characters at 1,200 characters a second, builds in alternating order) and `measure_resume.py`. The typical home is `benchmark.py --home realistic`; the extensions are `opensec-pi-subagents` 0.20.12 and `opensec-pi-todo` 2.13.1. Peak memory is the peak physical footprint (macOS) or peak private memory (Linux); for resuming it is the footprint once settled, as in the 1.2.1 notes.

macOS, 1.2.1 / **1.3.0**:

| | n | Instructions | CPU | Peak memory |
|---|---:|---|---|---|
| `pi --version` | 22 | 55 / **54** M | 4 / **4** ms | 6 / **6** MB |
| `pi -p` (5 model turns), blank home | 22 | 188 / **169** M | 17 / **15** ms | 20 / **19** MB |
| `pi -p`, typical home | 22 | 206 / **186** M | 18 / **17** ms | 21 / **20** MB |
| `pi -p`, typical home with extensions | 22 | 435 / **420** M | 42 / **40** ms | 33 / **32** MB |
| Interactive, 5 prompts | 22 | 561 / **434** M | 84 / **78** ms | 27 / **25** MB |
| 60,000-character answer, per frame | 10 | 1.26 / **1.24** M | | |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 14 | 492 / **386** M | | 78 / **30** MB |
| Resuming, until drawing settles | 14 | 6,278 / **453** M | 357 / **35** ms | |

Linux, 1.2.1 / **1.3.0**:

| | n | CPU | Peak memory |
|---|---:|---|---|
| `pi --version` | 22 | 5 / **5** ms | 2 / **2** MB |
| `pi -p` (5 model turns), blank home | 22 | 30 / **29** ms | 17 / **15** MB |
| `pi -p`, typical home | 22 | 32 / **30** ms | 17 / **16** MB |
| `pi -p`, typical home with extensions | 22 | 80 / **77** ms | 29 / **28** MB |
| Interactive, 5 prompts | 22 | 124 / **106** ms | 26 / **24** MB |
| 60,000-character answer, CPU per frame | 10 | 0.95 / **0.91** ms | |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 14 | 86 / **61** ms | 76 / **26** MB |
| Resuming, until drawing settles | 14 | 896 / **81** ms | |

In the interactive row, instructions and peak footprint come from the 18 of 22 runs per build in which the harness read them at exit.

Resuming in fullscreen: 1.2.1 draws the rest of the transcript within half a second of the first frame (6.3 G instructions, 78 MB). 1.3.0 draws the rows in view and leaves the rest until it is scrolled to; its instruction count stayed at 454 M from 0.5 s to 12 s after the first frame in a separate check.

## Rows where 1.3.0 is worse

A row is listed when 1.3.0's median is more than 3% higher and a two-sided Mann-Whitney U test of the runs gives p < 0.05 (`summarize.py`), and for the rows that came close, with the follow-up runs in [mac-followup](mac-followup) and [linux-followup](linux-followup).

- **TUI single answer, first answer after launch.** macOS: CPU 11.69 → 12.22 ms (+4.5%, n=24) over both passes; 8.39 → 8.43 ms in the first pass and 12.25 → 14.52 ms in the second; follow-up 12.39 → 14.70 ms (+18.6%, n=24, p < 0.001) while instructions went 89.27 → 87.25 M (-2.3%). A per-core breakdown (`drivers/answer_cores.py`, n=16 and n=12) found all of it on performance cores at the same QoS, with more system time (1.64 → 2.01 ms) and more CPU time for fewer instructions. A second answer in the same process was not slower: 10.91 → 10.70 ms elapsed, 36.1 → 31.2 M instructions (n=12). Linux: CPU 17.14 → 18.36 ms (+7.1%, n=24, p=0.018); follow-up 18.41 → 19.67 ms with `pi-start` (+6.8%, n=36, p=0.051) and 18.41 → 18.48 ms with `pi` started directly (+0.4%, p=0.32).
- **RPC single answer.** macOS: CPU 2.05 → 2.19 ms (+6.9%, n=24); follow-up 1.95 → 2.05 ms (+4.6%) with instructions 22.39 → 23.63 M (+5.5%), n=24, p < 0.001. Linux: CPU 5.03 → 5.37 ms (+6.7%, n=24); follow-up 5.13 → 5.45 ms with `pi-start` (+6.3%, p=0.013) and 5.51 ms with `pi` (+7.3%, p=0.001), n=36.
- **Idle CPU on macOS.** 0.702 → 0.788 ms/s (+12.3%, n=10, p=0.002) in the second pass; follow-up with alternating order 0.720 → 0.739 ms/s (+2.7%, n=10, p=0.73), instructions 1.050 → 1.125 M/s (+7.1%, p=0.003), wakeups 1.6 → 1.3 per second. Linux idle CPU went down by 47%.
- **Idle private memory on Linux.** 13.9 → 14.6 MiB (+5.0%, n=20, p < 0.001); follow-up 13.8 → 14.6 MiB (n=10). macOS went down, 18.7 → 17.8 MiB.

No other row is worse by more than 3% with p < 0.05.

## Notes

- The first macOS idle run (`mac/idle-first-pass.txt`, 0.738 → 0.766 ms/s) overlapped a 30-second side check on the same Mac and is left out of the table.
- Linux wall times come from a loaded shared machine; CPU time moves less with load, but both move.
- `idle.py` in this folder alternates the order of the builds per round and records instructions and wakeups on macOS; `answer.py` records instructions of RPC and TUI answers on macOS; `measure_resume.py` prints JSON and works on Linux. Otherwise the drivers are the candidate's, with their imports pointed at this folder.

## Files

- [measure.sh](measure.sh): `measure.sh OLD NEW OUT [STEP...]` runs every workload above on two builds (launchers or extracted archive folders) and writes the raw results to OUT. `EXT=dir,dir` adds the extensions row.
- [summarize.py](summarize.py): the tables above from `mac/` and `linux/`, the worse-row test and the follow-up tables.
- [drivers](drivers): every script the measurements ran, with the fixtures and answers they read.
- [mac](mac), [linux](linux): raw results of both passes, with `builds.txt` (hosts, versions, SHA-256) and `progress.txt` (times of each step).
- [mac-followup](mac-followup), [linux-followup](linux-followup): the follow-up runs. In `linux-followup`, `1.3.0-pi` is the same 1.3.0 build started without `pi-start`.

To repeat on the official 1.3.0 archives, extract both releases' archives for a platform and run, from this folder, with a new output folder (results are appended):

```sh
EXT=ext/opensec-pi-subagents,ext/opensec-pi-todo ./measure.sh 121/pi-bolt-darwin-arm64 130/pi-bolt-darwin-arm64 mac-release
python3 summarize.py --mac mac-release --linux linux-release
```
