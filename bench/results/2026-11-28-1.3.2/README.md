# Pi-Bolt 1.3.2 against the published 1.3.0 and 1.3.1

We compared the official JIT-off packages of Pi-Bolt 1.3.0 and 1.3.1 (both Pi 1.6.0) with Pi-Bolt 1.3.2 (Pi 1.7.0) built from
`release/1.3.2` at `08b1fb342` on the 1.3.2 runtimes. The three builds ran on the same host, interleaved in every workload with the
order rotating between rounds, one pass on macOS and one on Linux, both at the same time. All model workloads used a loopback
provider. Each table gives the medians of 1.3.0, 1.3.1 and **1.3.2**, and the change of 1.3.2 from 1.3.0 and from 1.3.1. A negative
change means 1.3.2 used less. A change marked * has p < 0.05 in a two-sided Mann-Whitney U test of the runs. Memory is macOS
physical footprint or Linux private dirty memory, compared within each OS. Column n is the number of runs per build.

## Builds

| Build | File | SHA-256 |
|---|---|---|
| 1.3.0, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.3.0](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.3.0), as in its `SHA256SUMS` | `1079b3c89b095491cefb4bbc978ff71e223a89ef83a10ab080ef27503c9434ae` |
| | its `pi-bin` | `e93ca85413c7c2dcd04fe8a9cd444637f8fc66f06c1d379c1fda7cc94930ab49` |
| 1.3.0, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.3.0, as in its `SHA256SUMS`; started with its `pi-start` | `6d15e573d7eeebfecf10c1e1bab288deb0ffd773d9315b682c0c32fedb3aea58` |
| 1.3.1, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.3.1](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.3.1), as in its `SHA256SUMS` | `21dac458a49c01e49446d06c87e82b7c8022937363d1993e4459e49a627d9b1b` |
| | its `pi-bin` | `f80e397c11c2d73d64d61b80024a36e686f38b35b2511683ee64b6633287c205` |
| 1.3.1, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.3.1, as in its `SHA256SUMS`; started with its `pi-start` | `9bdc07ae7121110f429418c6667d39282484b880d53d126a063cac68025014fc` |
| 1.3.2, macOS | runtime `pi-bolt-runtime-darwin-arm64.tar.gz` from the bolt-v1.3.2 release | `8f13df19daff9abee4dbeaa7a9562a13ec2e1e766f3f65bccf5921f6af86a491` |
| | built `pi-bin` | `bc153726465970c01770bbe9628dd42cd093465a31017960c6ebef791f973c55` |
| 1.3.2, Linux | runtime `pi-bolt-runtime-linux-x64.tar.gz` from the bolt-v1.3.2 release | `89041a766103bff5aba35a1e9250db8cef96973f98554757bf51b2429d89fdd2` |
| | built `pi` | `71dfb026553245b3095ec4b493c4dcb6ae55b4ecb2fc97461c57e0d45d0e8c54` |
| | built `pi-start`, which started it | `6f83dc88d50eed5bef2a88ca2b7e4b952c9f1ea1a746c61a4cf6fa9dba5eb62c` |

Both 1.3.2 builds are `npm run build:offline` and then `scripts/build-pi.sh` with its defaults (JIT off, training profile
`profiles/pi-1.7.0`) at `08b1fb342`. Both print `1.7.0 (Pi-Bolt 1.3.2, …, JIT off)`. `gh attestation verify <file> -R
opensec-git/Pi-Bolt --signer-workflow opensec-git/Pi-Bolt/.github/workflows/runtime.yml --source-ref refs/heads/pi-bolt` passed
for both runtimes: runtime.yml run 46061689873, which checked out `08b1fb342`. The tree's `scripts/runtime-stamp.sh` gives
`55c2a5e33d6295cb`.

Hosts: an Apple M5 MacBook Air (10 cores, 16 GB) on mains power, macOS 27.2, not pinned, with a 1-minute load average of 1.5 to
3.5 at the start of each step (3.3 when the pass started) and 15.7 right after the codemode step, whose 20 workers raise it; an AMD EPYC 9355P (64 threads)
shared with other users, Linux 7.0, at a load average of 3 to 27. The Linux host gives no access to hardware counters, so Linux
rows have CPU time and no instructions.

## Workloads, macOS arm64

| Measurement | n | 1.3.0 | 1.3.1 | 1.3.2 | 1.3.2 vs 1.3.0 | 1.3.2 vs 1.3.1 |
|---|---:|---:|---:|---:|---:|---:|
| Blank-home launch to visible typing, ms | 30 | 14.68 | 14.59 | **14.66** | -0.1% | +0.5% |
| HEADLESS single answer · elapsed time, ms | 12 | 15.91 | 16.00 | **15.84** | -0.5% | -1.0% |
| HEADLESS single answer · client CPU, ms | 12 | 12.21 | 12.38 | **12.29** | +0.6% | -0.7% |
| RPC single answer · elapsed time, ms | 12 | 3.04 | 3.07 | **3.08** | +1.4% | +0.4% |
| RPC single answer · client CPU, ms | 12 | 2.08 | 2.07 | **2.11** | +1.7% | +2.1% |
| RPC single answer · instructions, M | 12 | 23.62 | 23.56 | **23.71** | +0.4%* | +0.6%* |
| RPC single answer · final private memory, MiB | 12 | 14.0 | 14.0 | **14.0** | +0.0% | +0.0% |
| TUI single answer · elapsed time, ms | 12 | 15.32 | 14.27 | **14.60** | -4.7%* | +2.3% |
| TUI single answer · client CPU, ms | 12 | 15.55 | 14.31 | **14.68** | -5.6%* | +2.5% |
| TUI single answer · instructions, M | 12 | 87.50 | 83.15 | **83.01** | -5.1%* | -0.2%* |
| TUI single answer · final private memory, MiB | 12 | 18.5 | 17.8 | **17.8** | -3.8%* | +0.0% |
| 101 interactive tool prompts · elapsed ms/prompt | 6 | 39.45 | 39.31 | **39.22** | -0.6% | -0.2% |
| 101 interactive tool prompts · client CPU ms/prompt | 6 | 21.09 | 20.90 | **20.86** | -1.1% | -0.2% |
| 101 interactive tool prompts · final private MiB | 6 | 57.35 | 57.15 | **58.25** | +1.6% | +1.9% |
| 6,000 restored messages in RPC · elapsed ms/prompt | 6 | 5.11 | 5.04 | **5.05** | -1.3% | +0.1% |
| 6,000 restored messages in RPC · client CPU ms/prompt | 6 | 2.25 | 2.23 | **2.24** | -0.6% | +0.7% |
| 6,000 restored messages in RPC · final private MiB | 6 | 51.80 | 51.30 | **51.40** | -0.8% | +0.2% |
| Post-answer idle CPU, ms/s | 12 | 0.782 | 0.770 | **0.804** | +2.8% | +4.3% |
| Post-answer idle instructions, M/s | 12 | 1.157 | 1.164 | **1.166** | +0.8% | +0.1% |
| Post-answer idle private memory, MiB | 12 | 17.9 | 18.0 | **18.0** | +0.6% | +0.0% |

## Workloads, Linux x64

| Measurement | n | 1.3.0 | 1.3.1 | 1.3.2 | 1.3.2 vs 1.3.0 | 1.3.2 vs 1.3.1 |
|---|---:|---:|---:|---:|---:|---:|
| Blank-home launch to visible typing, ms | 30 | 14.69 | 14.74 | **14.86** | +1.1% | +0.9% |
| HEADLESS single answer · elapsed time, ms | 12 | 15.71 | 15.72 | **15.77** | +0.4% | +0.3% |
| HEADLESS single answer · client CPU, ms | 12 | 15.56 | 15.70 | **15.43** | -0.8% | -1.7% |
| RPC single answer · elapsed time, ms | 12 | 5.37 | 5.30 | **5.29** | -1.5% | -0.3% |
| RPC single answer · client CPU, ms | 12 | 4.14 | 4.11 | **4.18** | +0.9% | +1.7% |
| RPC single answer · final private memory, MiB | 12 | 10.8 | 10.9 | **10.8** | +0.0%* | -1.4%* |
| TUI single answer · elapsed time, ms | 12 | 11.89 | 10.92 | **11.24** | -5.5%* | +2.9% |
| TUI single answer · client CPU, ms | 12 | 12.85 | 11.50 | **11.56** | -10.0%* | +0.5% |
| TUI single answer · final private memory, MiB | 12 | 15.1 | 14.7 | **14.9** | -1.7%* | +1.4% |
| 101 interactive tool prompts · elapsed ms/prompt | 6 | 46.69 | 46.36 | **46.63** | -0.1% | +0.6% |
| 101 interactive tool prompts · client CPU ms/prompt | 6 | 19.61 | 18.85 | **19.55** | -0.3% | +3.7%* |
| 101 interactive tool prompts · final private MiB | 6 | 60.30 | 59.65 | **60.95** | +1.1% | +2.2%* |
| 6,000 restored messages in RPC · elapsed ms/prompt | 6 | 6.98 | 6.97 | **6.88** | -1.4% | -1.2% |
| 6,000 restored messages in RPC · client CPU ms/prompt | 6 | 3.33 | 3.32 | **3.24** | -2.9%* | -2.4%* |
| 6,000 restored messages in RPC · final private MiB | 6 | 49.95 | 49.80 | **50.25** | +0.6% | +0.9% |
| Post-answer idle CPU, ms/s | 12 | 0.469 | 0.459 | **0.451** | -3.7% | -1.7% |
| Post-answer idle private memory, MiB | 12 | 14.5 | 14.5 | **14.6** | +0.7%* | +0.7%* |

The startup probe uses a fresh home and project for every launch and waits for the first painted keystrokes. Single-answer tests
use the same 5,000-character Markdown answer and check its digest. Headless includes launch. RPC and TUI time the answer after
readiness. The 101-prompt test replays 505 HTTPS requests per run with the same tools and system prompt. The restored-session test
seeds 6,000 text messages, warms up with three prompts, then verifies 60 answers. The idle test measures 15-second windows after a
tool-using answer and two seconds of settling.

## Special operations, macOS arm64

| Measurement | n | 1.3.0 | 1.3.1 | 1.3.2 | 1.3.2 vs 1.3.0 | 1.3.2 vs 1.3.1 |
|---|---:|---:|---:|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 6 | 0.0088 | 0.0087 | **0.0089** | +1.5% | +2.1% |
| get_session_stats on a 12,000-entry session, CPU ms/query | 6 | 0.0086 | 0.0084 | **0.0087** | +1.2% | +2.9% |
| linear session tree search, elapsed ms/keystroke | 6 | 3.91 | 3.94 | **3.95** | +0.8% | +0.2% |
| linear session tree search, client CPU ms/keystroke | 6 | 4.03 | 4.03 | **4.04** | +0.3% | +0.2% |
| branched session tree search, elapsed ms/keystroke | 6 | 1.68 | 1.62 | **1.61** | -4.5% | -1.1% |
| branched session tree search, client CPU ms/keystroke | 6 | 1.57 | 1.52 | **1.50** | -4.0% | -1.2% |
| 8 Mi characters of nested edit text, private MiB at delivery | 18 | 38.0 | 38.0 | **37.9** | -0.3%* | -0.3%* |
| 8 Mi characters of nested edit text, client CPU ms | 18 | 12.2 | 11.7 | **11.6** | -5.0%* | -0.9% |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 18 | 19.5 | 18.9 | **18.9** | -2.9% | +0.3% |
| 32 Mi characters of nested edit text, private MiB at delivery | 18 | 41.7 | 40.0 | **40.0** | -4.1% | +0.0% |
| 32 Mi characters of nested edit text, client CPU ms | 18 | 28.9 | 26.9 | **26.5** | -8.3%* | -1.3%* |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 18 | 35.4 | 33.9 | **33.6** | -5.1%* | -1.0% |
| grep tool, 7 searches of a source tree in `pi -p`, elapsed ms | 12 | 712 | 714 | **711** | -0.2% | -0.4% |
| grep tool, 7 searches of a source tree in `pi -p`, client CPU ms | 12 | 2967 | 2971 | **2968** | +0.0% | -0.1% |
| grep tool, 7 searches of a source tree in `pi -p`, instructions, M | 12 | 21676 | 21691 | **21695** | +0.1% | +0.0% |

## Special operations, Linux x64

| Measurement | n | 1.3.0 | 1.3.1 | 1.3.2 | 1.3.2 vs 1.3.0 | 1.3.2 vs 1.3.1 |
|---|---:|---:|---:|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 6 | 0.0085 | 0.0081 | **0.0082** | -3.1% | +1.1% |
| get_session_stats on a 12,000-entry session, CPU ms/query | 6 | 0.0081 | 0.0083 | **0.0081** | -0.9% | -3.4% |
| linear session tree search, elapsed ms/keystroke | 6 | 3.47 | 3.31 | **3.26** | -6.0% | -1.3% |
| linear session tree search, client CPU ms/keystroke | 6 | 3.76 | 3.57 | **3.52** | -6.3% | -1.3% |
| branched session tree search, elapsed ms/keystroke | 6 | 0.97 | 0.97 | **0.90** | -7.7% | -7.5% |
| branched session tree search, client CPU ms/keystroke | 6 | 1.05 | 1.01 | **0.95** | -9.3% | -6.6% |
| 8 Mi characters of nested edit text, private MiB at delivery | 18 | 32.4 | 32.1 | **30.3** | -6.5%* | -5.6%* |
| 8 Mi characters of nested edit text, client CPU ms | 18 | 18.0 | 17.8 | **17.3** | -4.1%* | -3.0% |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 18 | 19.5 | 19.2 | **18.9** | -2.7%* | -1.5% |
| 32 Mi characters of nested edit text, private MiB at delivery | 18 | 31.5 | 31.6 | **31.0** | -1.6%* | -1.9%* |
| 32 Mi characters of nested edit text, client CPU ms | 18 | 32.0 | 32.8 | **32.7** | +2.2% | -0.3% |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 18 | 40.6 | 41.4 | **40.8** | +0.6% | -1.4% |
| grep tool, 7 searches of a source tree in `pi -p`, elapsed ms | 12 | 689 | 690 | **702** | +1.8%* | +1.8%* |
| grep tool, 7 searches of a source tree in `pi -p`, client CPU ms | 12 | 1127 | 1130 | **1137** | +0.9% | +0.6% |

RPC clients ran 160 checked queries for each command per process. The entries query asks for the tail after entry 11,999. Tree
clients typed and cleared the filter eight times per process and checked the match count. The branched fixture has 1,024 leaves with
2,048 shared ancestors; the linear fixture has 12,000 messages. The preview test appends a large saved edit and measures until its
capped RPC preview and the following message arrive, and checks both. The grep row is `pi -p` with a fake model that has the
built-in `grep` tool search `packages/` of `08b1fb342` (`git archive`, 3,801 files) seven times with ordinary expressions
(`drivers/grep_tree.py`); every run checks the number of result lines.

## Codemode with many workers

`pi -p --tools codemode` with a fake model that makes N `codemode` calls in one turn, each a script that computes a checksum
(300,000 loop iterations in the QuickJS sandbox), with `PI_CODEMODE_MAX_WORKERS=51` so that all N run at once
(`drivers/codemode.py`, `drivers/fake_model_codemode.py`). A run counts as completed when Pi exits with 0 and all N scripts
return the expected checksum. CPU, elapsed time and peak memory cover the whole `pi -p` of the completed runs. 6 runs per build
and N.

macOS:

| Codemode, N scripts at once | Build | Completed runs | CPU ms | Elapsed ms | Peak memory MB | Instructions M |
|---|---|---:|---:|---:|---:|---:|
| N = 4 | 1.3.0 | 6 of 6 | 4014 | 1023 | 38.8 | 50089 |
| N = 4 | 1.3.1 | 6 of 6 | 3999 | 1020 | 38.5 | 50086 |
| N = 4 | 1.3.2 | 6 of 6 | 4018 | 1025 | 38.6 | 50088 |
| N = 20 | 1.3.0 | 0 of 6 | aborts (exit status -5) |  |  |  |
| N = 20 | 1.3.1 | 0 of 6 | aborts (exit status -5) |  |  |  |
| N = 20 | 1.3.2 | 6 of 6 | 54219 | 5675 | 140.8 | 250451 |

Linux:

| Codemode, N scripts at once | Build | Completed runs | CPU ms | Elapsed ms | Peak memory MB |
|---|---|---:|---:|---:|---:|
| N = 4 | 1.3.0 | 6 of 6 | 11071 | 2819 | 32.4 |
| N = 4 | 1.3.1 | 6 of 6 | 11017 | 2814 | 32.2 |
| N = 4 | 1.3.2 | 6 of 6 | 10999 | 2795 | 32.5 |
| N = 20 | 1.3.0 | 0 of 6 | aborts (exit status -6) |  |  |
| N = 20 | 1.3.1 | 0 of 6 | aborts (exit status -6) |  |  |
| N = 20 | 1.3.2 | 6 of 6 | 151303 | 7783 | 112.8 |

With N = 20, 1.3.0 and 1.3.1 end on a signal (SIGTRAP on macOS, SIGABRT on Linux) 25 to 61 ms after launch, before any
script returns; 1.3.2 completes all of them. With N = 4 the three builds are equal.

## Standard set

The rows of the release notes, from `benchmark.py` (4 runs per rotation of the build order, 3 rotations), `long_answer.py`
(60,000 characters at 1,200 characters a second, once per build order) and `measure_resume.py` (once per build order). The typical
home is `benchmark.py --home realistic`; the extensions are `opensec-pi-subagents` 0.20.12 and `opensec-pi-todo` 2.13.1 from npm,
unpacked outside any checkout. Peak memory is the peak physical footprint (macOS) or peak private memory (Linux); for resuming it
is the footprint once settled. Each cell is 1.3.0 / 1.3.1 / **1.3.2**, then the change of 1.3.2 from 1.3.0 / from 1.3.1.

macOS:

| | n | Instructions | CPU | Peak memory |
|---|---:|---|---|---|
| `pi --version` | 12 | 56 / 56 / **56** M (-0.2% / -0.4%*) | 5 / 5 / **5** ms (-1.5% / -1.9%) | 6 / 6 / **6** MB (+0.0% / +0.0%) |
| `pi -p` (5 model turns), blank home | 12 | 169 / 168 / **168** M (-0.7%* / -0.1%) | 16 / 16 / **16** ms (-1.1% / -0.3%) | 19 / 19 / **19** MB (+0.5%* / +0.0%) |
| `pi -p`, typical home | 12 | 189 / 187 / **187** M (-0.6%* / -0.0%) | 17 / 17 / **17** ms (-0.5% / +0.8%) | 20 / 20 / **20** MB (+0.5%* / +0.5%*) |
| `pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo` | 12 | 501 / 500 / **500** M (-0.0% / +0.1%) | 47 / 46 / **47** ms (-0.2% / +0.7%*) | 34 / 34 / **33** MB (-1.5% / -1.0%) |
| Interactive, 5 prompts | 12 | 434 / 425 / **425** M (-2.0%* / +0.0%) | 80 / 80 / **80** ms (-0.9% / -0.8%) | 25 / 25 / **25** MB (-1.8%* / -0.4%) |
| 60,000-character answer, per frame | 6 | 1.25 / 1.25 / **1.25** M (-0.8% / -0.4%) |  |  |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 6 | 401 / 393 / **403** M (+0.5% / +2.5%) |  | 28 / 31 / **30** MB (+4.2% / -3.6%) |
| Resuming, until drawing settles | 6 | 468 / 459 / **470** M (+0.4% / +2.3%) | 38 / 37 / **39** ms (+1.8% / +5.0%) |  |

Linux:

| | n | CPU | Peak memory |
|---|---:|---|---|
| `pi --version` | 12 | 4 / 5 / **4** ms (-4.7% / -7.8%) | 2 / 2 / **2** MB (+15.4% / +2.3%) |
| `pi -p` (5 model turns), blank home | 12 | 25 / 25 / **24** ms (-1.6% / -3.0%) | 15 / 15 / **15** MB (-2.6% / -1.0%) |
| `pi -p`, typical home | 12 | 25 / 25 / **25** ms (-0.2% / +0.7%) | 16 / 16 / **16** MB (+0.0% / +0.3%) |
| `pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo` | 12 | 61 / 63 / **60** ms (-1.7% / -3.8%*) | 28 / 28 / **28** MB (+0.2% / +0.2%) |
| Interactive, 5 prompts | 12 | 83 / 83 / **83** ms (+0.5% / -0.5%) | 24 / 23 / **23** MB (-1.7%* / -1.1%) |
| 60,000-character answer, per frame | 6 | 0.40 / 0.40 / **0.39** ms (-2.5% / -2.5%) |  |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 6 | 43 / 44 / **44** ms (+2.7%* / +0.8%) | 26 / 26 / **26** MB (+0.8%* / +0.2%) |
| Resuming, until drawing settles | 6 | 57 / 58 / **58** ms (+0.3% / -1.5%) |  |

In the interactive row on macOS, instructions and peak footprint come from the 12, 11 and 11 of 12 runs per build in which the
harness read them at exit.

## Compared with 1.3.1

Better with p < 0.05:

- **Codemode with 20 scripts at once**: completes (macOS 54.2 s CPU, 5.7 s elapsed, 141 MB peak; Linux 151 s CPU, 7.8 s
  elapsed, 113 MB); 1.3.0 and 1.3.1 abort.
- **Linux, 8 Mi nested edit preview**: private memory at delivery 32.1 → 30.3 MiB (-5.6%, p < 0.001); 32 Mi: 31.6 → 31.0 MiB
  (-1.9%, p < 0.001).
- **Linux, `pi -p` with the two extensions**: CPU 63 → 60 ms (-3.8%, p=0.046).
- **Linux, RPC single answer**: private memory 10.9 → 10.8 MiB (-1.4%, p=0.003); 6,000 restored messages, CPU 3.32 → 3.24 ms per
  prompt (-2.4%, p=0.031).
- **macOS**: smaller changes with p < 0.05: TUI single answer instructions 83.15 → 83.01 M (-0.2%), 32 Mi preview CPU 26.9 →
  26.5 ms (-1.3%), 8 Mi preview memory -0.3%, `pi --version` instructions -0.4%.

Worse with p < 0.05:

- **Linux, 101 interactive tool prompts**: CPU 18.85 → 19.55 ms per prompt (+3.7%, n=6, p=0.045) and private memory 59.65 →
  60.95 MiB (+2.2%, p=0.045) in the pass. Run again with 18 rounds ([linux-followup](linux-followup)): CPU 18.95 → 19.22 ms (+1.4%,
  p=0.44), elapsed 46.55 → 46.55 ms, memory 60.00 → 60.35 MiB (+0.6%, p=0.41): within noise.
- Below 3%: macOS RPC single answer instructions 23.56 → 23.71 M (+0.6%), macOS `pi -p` typical home peak footprint +0.5%,
  macOS `pi -p` with the extensions CPU 46.2 → 46.6 ms (+0.7%), Linux idle private memory 14.5 → 14.6 MiB (+0.7%), Linux grep row
  elapsed 690 → 702 ms (+1.8%, CPU +0.6%, p=0.26), Linux codemode N = 4 peak memory 32.3 → 32.6 MB (+0.9%).

More than 3% above 1.3.1 without p < 0.05: macOS idle CPU 0.770 → 0.804 ms/s (+4.3%, p=0.20, instructions +0.1%), and macOS
resuming CPU until drawing settles 37 → 39 ms (+5.0%, n=6, p=0.47). The resume row was run again with 12 runs per build
([mac-followup](mac-followup)): instructions to the first frame 391.2 → 392.8 M (+0.4%, p=0.12), instructions until settled
458.1 → 460.8 M (+0.6%, p=0.010), CPU until settled 37.0 → 37.9 ms (+2.6%, p=0.66).

No row is worse than 1.3.1 by more than 3% with p < 0.05 after the re-runs.

## Notes

- The extensions row on macOS measures about 500 M instructions for all three builds here, against about 424 M in the 1.3.1
  comparison of 25 November with the same packed extensions and drivers; `pi --version` measures 56 M against 54 M. The change is
  the same for every build, so it lies outside the builds; the comparison within this run holds. It does not depend on where the
  extensions are unpacked (outside a checkout) or on the temporary directory.
- `summarize.py` here compares 1.3.2 with 1.3.0 and 1.3.1 and lists the rows better and not better than 1.3.1; `measure.sh` takes
  three builds and rotates their order and adds the codemode step; `drivers/` are those of the 1.3.1 comparison plus
  `codemode.py` and `fake_model_codemode.py`.

## Files

- [measure.sh](measure.sh): `measure.sh A B C OUT [STEP...]` runs every workload above on three builds (launchers or extracted
  archive folders) and writes the raw results to OUT. `NAMES` names the builds; `EXT=dir,dir` adds the extensions row,
  `GREP_TREE=dir` the grep row.
- [summarize.py](summarize.py): the tables above from `mac/` and `linux/`, the worse-row test, the better and not-ahead lists, the
  codemode tables and the follow-up tables; `--notes` also prints the standard set in the release notes' shape.
- [drivers](drivers): every script the measurements ran, with the fixtures and answers they read.
- [mac](mac), [linux](linux): raw results, with `builds.txt` (hosts, versions, SHA-256, load) and `progress.txt` (times and load
  of each step).
- [mac-followup](mac-followup), [linux-followup](linux-followup): the re-runs.

To repeat, extract the 1.3.0 and 1.3.1 archives for a platform, build 1.3.2, and run from this folder with a new output folder
(results are appended):

```sh
NAMES=1.3.0,1.3.1,1.3.2 EXT=/tmp/ext/opensec-pi-subagents,/tmp/ext/opensec-pi-todo GREP_TREE=/tmp/grep-tree \
  ./measure.sh 130/pi-bolt-darwin-arm64 131/pi-bolt-darwin-arm64 out/pi-bolt mac-again
python3 summarize.py --mac mac-again --linux linux-again
```
