# Pi-Bolt 1.4.0 against the published 1.3.1 and 1.3.2

We compared the official JIT-off packages of Pi-Bolt 1.3.1 (Pi 1.6.0) and 1.3.2 (Pi 1.7.0) with Pi-Bolt 1.4.0 (Pi 1.7.0) built
from `release/1.4.0` at `337a9d614` on the 1.4.0 runtimes. The three builds ran on the same host, interleaved in every workload with
the order rotating between rounds, one pass on macOS and one on Linux, both at the same time. All model workloads used a loopback
provider. Each table gives the medians of 1.3.1, 1.3.2 and **1.4.0**, and the change of 1.4.0 from 1.3.1 and from 1.3.2. A negative
change means 1.4.0 used less. A change marked * has p < 0.05 in a two-sided Mann-Whitney U test of the runs. Memory is macOS
physical footprint or Linux private dirty memory, compared within each OS. Column n is the number of runs per build.

## Builds

| Build | File | SHA-256 |
|---|---|---|
| 1.3.1, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.3.1](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.3.1), as in its `SHA256SUMS` | `21dac458a49c01e49446d06c87e82b7c8022937363d1993e4459e49a627d9b1b` |
| | its `pi-bin` | `f80e397c11c2d73d64d61b80024a36e686f38b35b2511683ee64b6633287c205` |
| 1.3.1, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.3.1, as in its `SHA256SUMS`; started with its `pi-start` | `9bdc07ae7121110f429418c6667d39282484b880d53d126a063cac68025014fc` |
| 1.3.2, macOS | `pi-bolt-darwin-arm64.tar.xz` from [bolt-v1.3.2](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.3.2), as in its `SHA256SUMS` | `37a4c25fbd1eb86d1979789268f90520a505cf8c0a90c23d069befbaae93c15e` |
| | its `pi-bin` | `f3432841a386f155f4c53203d41abaf1bee427529bea99e5f60ef040a5853054` |
| 1.3.2, Linux | `pi-bolt-linux-x64.tar.xz` from bolt-v1.3.2, as in its `SHA256SUMS`; started with its `pi-start` | `204dbf7653fe347ebbbefde7158346490376b1584cade59a26bf17341f1e5e21` |
| 1.4.0, macOS | runtime `pi-bolt-runtime-darwin-arm64.tar.gz` from the bolt-v1.4.0 release | `3e2df58f8da1ad3358ebefc59afb01e2f62212125585fff6059d9840c1909866` |
| | built `pi-bin` | `eda593d574b3c92e96f22d5d37c5ed4f0989201385058ffcfd0148f407b04be9` |
| 1.4.0, Linux | runtime `pi-bolt-runtime-linux-x64.tar.gz` from the bolt-v1.4.0 release | `ef98177b23652e9a1864254edf27d0a927b000ad287a2f9626f8d33a5c5b6356` |
| | built `pi` | `7d1878385ec4ffc53ec2911f402ad175fa8ff1eafbcb9c1cce0c17e13bdc5804` |
| | built `pi-start`, which started it | `ad22d91fa2bb1754098282e71222cd1d2a5deb543a54aabb05f6f54314c5737c` |

Both 1.4.0 builds are `npm run build:offline` and then `scripts/build-pi.sh` with its defaults (JIT off, training profile
`profiles/pi-1.7.0`) at `337a9d614`. Both print `1.7.0 (Pi-Bolt 1.4.0, …, JIT off)`. `gh attestation verify <file> -R
opensec-git/Pi-Bolt --signer-workflow opensec-git/Pi-Bolt/.github/workflows/runtime.yml --source-ref refs/heads/pi-bolt` passed
for both runtimes: runtime.yml run 46127404240, which checked out `ffc37da1f` on `release/1.4.0`; the runtime's inputs (patches,
sources, build scripts) are the same at `337a9d614`, whose `scripts/runtime-stamp.sh` gives `710691eb0ea6cd84`.

Hosts: an Apple M5 MacBook Air (10 cores, 16 GB) on mains power, macOS 27.2, not pinned, with a 1-minute load average of 2.7 to
5.5 at the start of each step (3.5 when the pass started) and 30 right after the codemode step, whose 20 workers raise it; an AMD
EPYC 9355P (64 threads) shared with other users, Linux 7.0, at a load average of 4 to 22. The Linux host gives no access to
hardware counters, so Linux rows have CPU time and no instructions.

## Workloads, macOS arm64

| Measurement | n | 1.3.1 | 1.3.2 | 1.4.0 | 1.4.0 vs 1.3.1 | 1.4.0 vs 1.3.2 |
|---|---:|---:|---:|---:|---:|---:|
| Blank-home launch to visible typing, ms | 30 | 15.00 | 15.03 | **15.19** | +1.3% | +1.1% |
| HEADLESS single answer · elapsed time, ms | 12 | 15.94 | 15.99 | **16.06** | +0.8% | +0.5% |
| HEADLESS single answer · client CPU, ms | 12 | 12.25 | 12.36 | **12.45** | +1.7% | +0.7% |
| RPC single answer · elapsed time, ms | 12 | 3.03 | 3.07 | **3.07** | +1.5% | +0.1% |
| RPC single answer · client CPU, ms | 12 | 2.09 | 2.12 | **2.10** | +0.4% | -0.7% |
| RPC single answer · instructions, M | 12 | 23.54 | 23.74 | **23.64** | +0.4%* | -0.4%* |
| RPC single answer · final private memory, MiB | 12 | 14.0 | 13.9 | **14.0** | +0.0%* | +0.7%* |
| TUI single answer · elapsed time, ms | 12 | 39.45 | 38.22 | **37.55** | -4.8% | -1.7% |
| TUI single answer · client CPU, ms | 12 | 31.86 | 30.91 | **30.43** | -4.5% | -1.6% |
| TUI single answer · instructions, M | 12 | 284.13 | 283.99 | **283.60** | -0.2% | -0.1% |
| TUI single answer · final private memory, MiB | 12 | 23.3 | 23.4 | **24.2** | +4.1%* | +3.6%* |
| 101 interactive tool prompts · elapsed ms/prompt | 6 | 40.10 | 39.92 | **39.98** | -0.3% | +0.1% |
| 101 interactive tool prompts · client CPU ms/prompt | 6 | 20.95 | 20.93 | **21.04** | +0.4% | +0.6% |
| 101 interactive tool prompts · final private MiB | 6 | 63.05 | 64.20 | **62.60** | -0.7% | -2.5% |
| 6,000 restored messages in RPC · elapsed ms/prompt | 6 | 5.10 | 5.27 | **5.20** | +1.9% | -1.3% |
| 6,000 restored messages in RPC · client CPU ms/prompt | 6 | 2.21 | 2.29 | **2.30** | +4.1%* | +0.4% |
| 6,000 restored messages in RPC · final private MiB | 6 | 53.80 | 51.90 | **50.65** | -5.9%* | -2.4%* |
| Post-answer idle CPU, ms/s | 12 | 0.903 | 0.882 | **0.858** | -5.0% | -2.7% |
| Post-answer idle instructions, M/s | 12 | 1.415 | 1.253 | **1.405** | -0.8% | +12.0% |
| Post-answer idle private memory, MiB | 12 | 23.3 | 23.4 | **23.4** | +0.4%* | +0.0% |

## Workloads, Linux x64

| Measurement | n | 1.3.1 | 1.3.2 | 1.4.0 | 1.4.0 vs 1.3.1 | 1.4.0 vs 1.3.2 |
|---|---:|---:|---:|---:|---:|---:|
| Blank-home launch to visible typing, ms | 30 | 14.96 | 14.75 | **15.27** | +2.1%* | +3.5%* |
| HEADLESS single answer · elapsed time, ms | 12 | 16.09 | 16.09 | **16.43** | +2.1% | +2.1% |
| HEADLESS single answer · client CPU, ms | 12 | 15.95 | 16.02 | **16.25** | +1.8% | +1.4% |
| RPC single answer · elapsed time, ms | 12 | 5.22 | 5.35 | **5.27** | +0.9% | -1.5% |
| RPC single answer · client CPU, ms | 12 | 4.09 | 4.10 | **4.14** | +1.2% | +1.2% |
| RPC single answer · final private memory, MiB | 12 | 10.9 | 10.8 | **11.0** | +0.5% | +1.9%* |
| TUI single answer · elapsed time, ms | 12 | 10.78 | 10.79 | **10.77** | -0.1% | -0.2% |
| TUI single answer · client CPU, ms | 12 | 11.45 | 11.34 | **11.57** | +1.1% | +2.1% |
| TUI single answer · final private memory, MiB | 12 | 14.7 | 15.0 | **15.1** | +2.7%* | +0.7%* |
| 101 interactive tool prompts · elapsed ms/prompt | 6 | 47.34 | 47.32 | **46.55** | -1.7% | -1.6%* |
| 101 interactive tool prompts · client CPU ms/prompt | 6 | 19.94 | 20.33 | **19.11** | -4.1% | -6.0%* |
| 101 interactive tool prompts · final private MiB | 6 | 59.90 | 60.50 | **60.85** | +1.6% | +0.6% |
| 6,000 restored messages in RPC · elapsed ms/prompt | 6 | 6.89 | 7.00 | **7.02** | +1.8% | +0.3% |
| 6,000 restored messages in RPC · client CPU ms/prompt | 6 | 3.29 | 3.25 | **3.38** | +2.8%* | +4.0% |
| 6,000 restored messages in RPC · final private MiB | 6 | 49.60 | 50.10 | **49.75** | +0.3% | -0.7% |
| Post-answer idle CPU, ms/s | 12 | 0.474 | 0.441 | **0.461** | -2.8% | +4.5% |
| Post-answer idle private memory, MiB | 12 | 14.5 | 14.6 | **14.7** | +1.4%* | +1.0%* |

The startup probe uses a fresh home and project for every launch and waits for the first painted keystrokes. Single-answer tests
use the same 5,000-character Markdown answer and check its digest. Headless includes launch. RPC and TUI time the answer after
readiness. The 101-prompt test replays 505 HTTPS requests per run with the same tools and system prompt. The restored-session test
seeds 6,000 text messages, warms up with three prompts, then verifies 60 answers. The idle test measures 15-second windows after a
tool-using answer and two seconds of settling.

## Special operations, macOS arm64

| Measurement | n | 1.3.1 | 1.3.2 | 1.4.0 | 1.4.0 vs 1.3.1 | 1.4.0 vs 1.3.2 |
|---|---:|---:|---:|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 6 | 0.0085 | 0.0088 | **0.0085** | -0.2% | -3.2% |
| get_session_stats on a 12,000-entry session, CPU ms/query | 6 | 0.0085 | 0.0087 | **0.0084** | -1.6% | -3.3% |
| linear session tree search, elapsed ms/keystroke | 6 | 4.21 | 4.20 | **4.25** | +0.9% | +1.1% |
| linear session tree search, client CPU ms/keystroke | 6 | 4.36 | 4.26 | **4.39** | +0.6% | +3.1% |
| branched session tree search, elapsed ms/keystroke | 6 | 2.28 | 2.42 | **2.03** | -11.0% | -16.4% |
| branched session tree search, client CPU ms/keystroke | 6 | 2.12 | 2.40 | **1.71** | -19.4% | -28.7% |
| 8 Mi characters of nested edit text, private MiB at delivery | 18 | 37.8 | 37.8 | **37.9** | +0.3%* | +0.3%* |
| 8 Mi characters of nested edit text, client CPU ms | 18 | 11.5 | 11.6 | **11.4** | -0.3% | -1.5%* |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 18 | 17.9 | 17.9 | **17.8** | -0.7% | -0.6% |
| 32 Mi characters of nested edit text, private MiB at delivery | 18 | 41.3 | 39.4 | **40.0** | -3.1% | +1.5%* |
| 32 Mi characters of nested edit text, client CPU ms | 18 | 27.7 | 27.1 | **26.7** | -3.4% | -1.4% |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 18 | 34.4 | 33.5 | **33.1** | -3.8% | -1.2% |
| grep tool, 7 searches of a source tree in `pi -p`, elapsed ms | 12 | 720 | 719 | **726** | +0.8% | +1.0% |
| grep tool, 7 searches of a source tree in `pi -p`, client CPU ms | 12 | 3091 | 3104 | **3126** | +1.1% | +0.7% |
| grep tool, 7 searches of a source tree in `pi -p`, instructions, M | 12 | 21980 | 21973 | **21974** | -0.0% | +0.0% |
| Large tree, grep `function` in `pi -p`, elapsed ms | 12 | 100 | 101 | **34** | -66.3%* | -66.7%* |
| Large tree, grep `function` in `pi -p`, client CPU ms | 12 | 495 | 498 | **105** | -78.7%* | -78.9%* |
| Large tree, grep `function` in `pi -p`, instructions, M | 12 | 2890 | 2890 | **690** | -76.1%* | -76.1%* |
| Large tree, find `readme*` in `pi -p`, elapsed ms | 12 | 80 | 81 | **83** | +3.1%* | +2.2%* |
| Large tree, find `readme*` in `pi -p`, client CPU ms | 12 | 426 | 431 | **433** | +1.8% | +0.5% |
| Large tree, find `readme*` in `pi -p`, instructions, M | 12 | 2474 | 2476 | **2515** | +1.6%* | +1.6%* |

## Special operations, Linux x64

| Measurement | n | 1.3.1 | 1.3.2 | 1.4.0 | 1.4.0 vs 1.3.1 | 1.4.0 vs 1.3.2 |
|---|---:|---:|---:|---:|---:|---:|
| get_entries on a 12,000-entry session, CPU ms/query | 6 | 0.0080 | 0.0082 | **0.0082** | +1.7% | +0.2% |
| get_session_stats on a 12,000-entry session, CPU ms/query | 6 | 0.0078 | 0.0080 | **0.0079** | +1.1% | -1.8% |
| linear session tree search, elapsed ms/keystroke | 6 | 3.08 | 3.22 | **3.30** | +7.1% | +2.6% |
| linear session tree search, client CPU ms/keystroke | 6 | 3.36 | 3.43 | **3.56** | +6.0% | +3.6% |
| branched session tree search, elapsed ms/keystroke | 6 | 0.88 | 0.90 | **0.92** | +4.9%* | +2.4% |
| branched session tree search, client CPU ms/keystroke | 6 | 0.95 | 0.97 | **0.96** | +1.6%* | -0.2% |
| 8 Mi characters of nested edit text, private MiB at delivery | 18 | 31.7 | 30.4 | **30.4** | -4.1%* | +0.2% |
| 8 Mi characters of nested edit text, client CPU ms | 18 | 17.0 | 16.7 | **17.0** | -0.1% | +1.8% |
| 8 Mi characters of nested edit text, elapsed ms to delivery | 18 | 18.6 | 18.6 | **18.5** | -0.8% | -0.4% |
| 32 Mi characters of nested edit text, private MiB at delivery | 18 | 33.2 | 31.0 | **30.9** | -7.1%* | -0.3% |
| 32 Mi characters of nested edit text, client CPU ms | 18 | 31.6 | 31.3 | **31.8** | +0.6% | +1.5%* |
| 32 Mi characters of nested edit text, elapsed ms to delivery | 18 | 39.1 | 39.3 | **39.8** | +1.8% | +1.2% |
| grep tool, 7 searches of a source tree in `pi -p`, elapsed ms | 12 | 688 | 708 | **709** | +2.9%* | +0.1% |
| grep tool, 7 searches of a source tree in `pi -p`, client CPU ms | 12 | 1155 | 1178 | **1172** | +1.5%* | -0.5% |
| Large tree, grep `function` in `pi -p`, elapsed ms | 12 | 79 | 80 | **28** | -64.5%* | -64.9%* |
| Large tree, grep `function` in `pi -p`, client CPU ms | 12 | 152 | 152 | **46** | -69.8%* | -70.0%* |
| Large tree, find `readme*` in `pi -p`, elapsed ms | 12 | 60 | 60 | **64** | +7.1%* | +7.8%* |
| Large tree, find `readme*` in `pi -p`, client CPU ms | 12 | 119 | 120 | **124** | +4.2%* | +3.8%* |

RPC clients ran 160 checked queries for each command per process. The entries query asks for the tail after entry 11,999. Tree
clients typed and cleared the filter eight times per process and checked the match count. The branched fixture has 1,024 leaves with
2,048 shared ancestors; the linear fixture has 12,000 messages. The preview test appends a large saved edit and measures until its
capped RPC preview and the following message arrive, and checks both. The grep row is `pi -p` with a fake model that has the
built-in `grep` tool search `packages/` of `337a9d614` (`git archive`, 3,843 files) seven times with ordinary expressions
(`drivers/grep_tree.py`); every run checks the number of result lines.

The large-tree rows are `pi -p` with a fake model that has Pi run one search with the tool's default limit
(`drivers/big_tree.py`, `drivers/fake_model_bigtree.py`): grep for `function` (limit 100) or find for `readme*` (limit 1,000). The
tree is the checkout of `337a9d614` with its `node_modules`, without `.git` and the `.gitignore` files outside `node_modules`, so
that both tools walk all of it: 51,715 files (macOS) and 51,728 (Linux). The times cover the whole `pi -p`: start, the search and
two model turns. grep returns 102 lines in all three builds; 1.4.0 stops walking at the limit, where 1.3.1 and 1.3.2 walk the
whole tree first. find returns 21 lines in 1.3.1 and 1.3.2 and 441 (macOS) or 446 (Linux) in 1.4.0, which matches fd: a
lowercase pattern matches without regard to case.

## Codemode with many workers

`pi -p --tools codemode` with a fake model that makes N `codemode` calls in one turn, each a script that computes a checksum
(300,000 loop iterations in the QuickJS sandbox), with `PI_CODEMODE_MAX_WORKERS=51` so that all N run at once
(`drivers/codemode.py`, `drivers/fake_model_codemode.py`). A run counts as completed when Pi exits with 0 and all N scripts
return the expected checksum. CPU, elapsed time and peak memory cover the whole `pi -p` of the completed runs. 6 runs per build
and N.

macOS:

| Codemode, N scripts at once | Build | Completed runs | CPU ms | Elapsed ms | Peak memory MB | Instructions M |
|---|---|---:|---:|---:|---:|---:|
| N = 4 | 1.3.1 | 6 of 6 | 4313 | 1100 | 38.7 | 50088 |
| N = 4 | 1.3.2 | 6 of 6 | 4276 | 1091 | 38.7 | 50086 |
| N = 4 | 1.4.0 | 6 of 6 | 4280 | 1090 | 38.7 | 50086 |
| N = 20 | 1.3.1 | 0 of 6 | aborts (exit status -5) |  |  |  |
| N = 20 | 1.3.2 | 6 of 6 | 54453 | 6159 | 138.8 | 250690 |
| N = 20 | 1.4.0 | 6 of 6 | 55223 | 6209 | 139.3 | 250718 |

Linux:

| Codemode, N scripts at once | Build | Completed runs | CPU ms | Elapsed ms | Peak memory MB |
|---|---|---:|---:|---:|---:|
| N = 4 | 1.3.1 | 6 of 6 | 10945 | 2771 | 32.3 |
| N = 4 | 1.3.2 | 6 of 6 | 10975 | 2779 | 32.3 |
| N = 4 | 1.4.0 | 6 of 6 | 10910 | 2772 | 32.5 |
| N = 20 | 1.3.1 | 0 of 6 | aborts (exit status -6) |  |  |
| N = 20 | 1.3.2 | 6 of 6 | 156493 | 7939 | 118.3 |
| N = 20 | 1.4.0 | 6 of 6 | 156091 | 7909 | 113.3 |

With N = 20, 1.3.1 ends on a signal (SIGTRAP on macOS, SIGABRT on Linux) before any script returns; 1.3.2 and 1.4.0 complete
all of them. With N = 4 the three builds are equal.

## Standard set

The rows of the release notes, from `benchmark.py` (4 runs per rotation of the build order, 3 rotations), `long_answer.py`
(60,000 characters at 1,200 characters a second, once per build order) and `measure_resume.py` (once per build order). The typical
home is `benchmark.py --home realistic`; the extensions are `opensec-pi-subagents` 0.20.12 and `opensec-pi-todo` 2.13.1 from npm,
unpacked outside any checkout. Peak memory is the peak physical footprint (macOS) or peak private memory (Linux); for resuming it
is the footprint once settled. Each cell is 1.3.1 / 1.3.2 / **1.4.0**, then the change of 1.4.0 from 1.3.1 / from 1.3.2.

macOS:

| | n | Instructions | CPU | Peak memory |
|---|---:|---|---|---|
| `pi --version` | 12 | 56 / 56 / **56** M (-0.2% / +0.0%) | 5 / 5 / **5** ms (+0.9% / +1.0%) | 6 / 6 / **6** MB (+0.0% / +0.0%) |
| `pi -p` (5 model turns), blank home | 12 | 168 / 168 / **169** M (+0.5%* / +0.5%*) | 16 / 16 / **16** ms (+1.0% / +1.4%) | 19 / 19 / **19** MB (+0.5%* / +0.5%*) |
| `pi -p`, typical home | 12 | 187 / 187 / **188** M (+0.6%* / +0.6%*) | 17 / 17 / **18** ms (+1.7% / +1.7%) | 20 / 20 / **20** MB (+0.0% / +0.0%) |
| `pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo` | 12 | 560 / 560 / **561** M (+0.2%* / +0.3%*) | 49 / 49 / **49** ms (+0.3% / +0.3%) | 35 / 35 / **36** MB (+2.0%* / +1.0%*) |
| Interactive, 5 prompts | 12 | 627 / 629 / **629** M (+0.4% / +0.1%) | 108 / 109 / **108** ms (+0.1% / -1.1%) | 31 / 31 / **31** MB (+1.8%* / -0.3%) |
| 60,000-character answer, per frame | 6 | 1.36 / 1.35 / **1.35** M (-0.7% / +0.0%) |  |  |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 6 | 422 / 422 / **428** M (+1.5% / +1.5%) |  | 29 / 30 / **31** MB (+7.0% / +3.7%) |
| Resuming, until drawing settles | 6 | 489 / 489 / **495** M (+1.4% / +1.2%) | 38 / 38 / **40** ms (+4.7% / +4.3%) |  |

Linux:

| | n | CPU | Peak memory |
|---|---:|---|---|
| `pi --version` | 12 | 4 / 4 / **4** ms (-6.1% / -11.0%) | 2 / 2 / **2** MB (+4.7% / +9.8%) |
| `pi -p` (5 model turns), blank home | 12 | 22 / 22 / **22** ms (-0.7% / -1.9%) | 15 / 15 / **15** MB (+2.7%* / +1.3%) |
| `pi -p`, typical home | 12 | 24 / 23 / **23** ms (-0.1% / +0.7%) | 16 / 16 / **15** MB (-2.5% / -1.0%) |
| `pi -p`, typical home with `opensec-pi-subagents` and `opensec-pi-todo` | 12 | 60 / 60 / **60** ms (+1.1% / +0.9%) | 28 / 28 / **29** MB (+2.0%* / +2.0%*) |
| Interactive, 5 prompts | 12 | 71 / 71 / **71** ms (+0.6% / +0.2%) | 23 / 23 / **24** MB (+1.3% / +1.7%*) |
| 60,000-character answer, per frame | 6 | 0.40 / 0.40 / **0.40** ms (-1.0% / +0.5%) |  |
| Resuming a 2,292-entry session (`pi -c`, fullscreen), until the first frame | 6 | 45 / 45 / **45** ms (-0.2% / -0.3%) | 26 / 26 / **26** MB (+0.0% / -0.6%) |
| Resuming, until drawing settles | 6 | 58 / 58 / **59** ms (+0.7% / +0.2%) |  |

In the interactive row on macOS, instructions and peak footprint come from the 12, 10 and 10 of 12 runs per build in which the
harness read them at exit.

## Compared with 1.3.2

Better with p < 0.05:

- **grep in a large tree**: macOS 101 → 34 ms elapsed (-66.7%), 498 → 105 ms CPU (-78.9%), 2,890 → 690 M instructions (-76.1%);
  Linux 80 → 28 ms elapsed (-64.9%), 152 → 46 ms CPU (-70.0%). Cause: the in-process walk stops at the limit (`a19cd5484`).
- **Linux, 101 interactive tool prompts**: CPU 20.33 → 19.11 ms per prompt (-6.0%, n=6, p=0.013), elapsed -1.6%.
- **macOS, 6,000 restored messages**: final private memory 51.9 → 50.7 MiB (-2.4%); small gains in RPC answer instructions
  (-0.4%) and 8 Mi preview CPU (-1.5%).

Worse with p < 0.05, more than 3%, and run again with more rounds ([mac-followup](mac-followup),
[linux-followup](linux-followup)):

- **find in a large tree**: Linux 60 → 64 ms elapsed (+7.8%), 120 → 124 ms CPU (+3.8%); follow-up, 24 rounds: 60.2 → 66.3 ms
  elapsed (+10.1%, p < 0.001), 119.8 → 126.1 ms CPU (+5.2%, p < 0.001). macOS: elapsed +2.2%, instructions +1.6%. Cause: 1.4.0
  matches fd (`c9746c819`), so `readme*` also finds `README.md` and the like: 446 results against 21 on Linux, 441 against 21 on
  macOS. With `README*`, which returns the same 420 results in all three builds, the difference is 60.1 → 61.9 ms elapsed (+2.9%,
  p < 0.001) and 118.4 → 120.9 ms CPU (+2.1%, p=0.002), n=24: most of the increase is the larger correct result, the rest the walk
  that now hands on results in parts.
- **Linux, blank-home launch to visible typing**: 14.75 → 15.27 ms (+3.5%, n=30, p=0.004); follow-up, 60 rounds: 15.36 → 15.70 ms
  (+2.2%, p=0.013). Below 3% on the re-run; not attributed. The output files' per-process folder is made only when the first
  output file is, so it is not part of this; macOS: +1.1% (p > 0.05).
- **macOS, TUI single answer final private memory**: 23.4 → 24.25 MiB (+3.6%, n=12, p=0.007); follow-up, 24 rounds: 24.1 →
  24.3 MiB (+0.8%, p < 0.001), CPU -6.2% (p=0.67), instructions -0.4%. Below 3% on the re-run.

Worse with p < 0.05 by less than 3%: Linux RPC answer private memory +1.9%, Linux TUI answer private memory +0.7%, Linux idle
private memory 14.6 → 14.7 MiB (+1.0%), Linux 32 Mi preview CPU +1.5%, macOS RPC answer private memory +0.7%, macOS 32 Mi preview
memory +1.5% and 8 Mi preview memory +0.3%, macOS `pi -p` instructions +0.5% (blank home) and +0.6% (typical home), peak
footprint with extensions +1.0%.

More than 3% above 1.3.2 without p < 0.05: Linux 6,000 restored messages CPU +4.0%, Linux idle CPU +4.5%, Linux linear tree search
CPU +3.6%, macOS idle instructions +12.0% (CPU -2.7%), macOS linear tree search CPU +3.1%, macOS resuming CPU until settled +4.3%
(follow-up, 12 runs: 38.5 → 41.0 ms, +6.5%, p=0.62; instructions until settled +1.0%, p=0.21).

## Notes

- On macOS the TUI single answer measures about 284 M instructions and the interactive row about 629 M for all three builds,
  against 83 M and 425 M in the 1.3.2 comparison earlier the same day; the extensions row reads 560 M against 500 M. The shift is
  the same for every build, so the comparison within this run holds; it is not the locale (`LANG` en_IN and en_US give the same
  count). Not investigated further.
- `summarize.py` here compares 1.4.0 with 1.3.1 and 1.3.2; `measure.sh` adds the `bigtree` step (`BIG_TREE=dir`); `drivers/`
  are those of the 1.3.2 comparison plus `big_tree.py` and `fake_model_bigtree.py`.

## Files

- [measure.sh](measure.sh): `measure.sh A B C OUT [STEP...]` runs every workload above on three builds (launchers or extracted
  archive folders) and writes the raw results to OUT. `NAMES` names the builds; `EXT=dir,dir` adds the extensions row,
  `GREP_TREE=dir` the grep row, `BIG_TREE=dir` the large-tree rows.
- [summarize.py](summarize.py): the tables above from `mac/` and `linux/`, the worse-row test, the better and not-ahead lists, the
  codemode tables and the follow-up tables; `--notes` also prints the standard set in the release notes' shape.
- [drivers](drivers): every script the measurements ran, with the fixtures and answers they read.
- [mac](mac), [linux](linux): raw results, with `builds.txt` (hosts, versions, SHA-256, load) and `progress.txt` (times and load
  of each step).
- [mac-followup](mac-followup), [linux-followup](linux-followup): the re-runs; `linux-followup/bigtree-README.jsonl` is find with
  `README*`.

To repeat, extract the 1.3.1 and 1.3.2 archives for a platform, build 1.4.0, and run from this folder with a new output folder
(results are appended):

```sh
NAMES=1.3.1,1.3.2,1.4.0 EXT=/tmp/ext/opensec-pi-subagents,/tmp/ext/opensec-pi-todo GREP_TREE=/tmp/grep-tree BIG_TREE=/tmp/big-tree \
  ./measure.sh 131/pi-bolt-darwin-arm64 132/pi-bolt-darwin-arm64 out/pi-bolt mac-again
python3 summarize.py --mac mac-again --linux linux-again
```
