# Pi-Bolt 0.7.3 against 0.7.2

Both builds installed the same way: extracted from their release archives (the 0.7.2 archives as published, 0.7.3 from
`scripts/package-release.sh`), except macOS `tmux.jsonl` and `big_output.jsonl`, run with the same 0.7.3 build in `out/`. On Linux both executables were dropped from the page cache before the startup runs, so that
neither is measured from pages written moments before (a file just written maps with smaller page-cache units: 0.7.3 measured
17% slower at `pi --version` that way, and 10% faster once both were read back from disk). Runs interleave the two builds.

- macOS: Apple M5 MacBook Air, macOS 27, fanless, other work on the machine; no core pinning.
- Linux: GCP VM, AMD EPYC, 56 vCPUs shared with other workloads; pinned to the four least busy cores (29,30,34,35).

| File | Tool |
|---|---|
| `benchmark.jsonl` | `bench/benchmark.py --runs 21 --warmup 3` (startup, headless, interactive) |
| `long_session.jsonl` | `bench/long_session.py --prompts 75` |
| `tmux.jsonl` | `bench/tmux_check.py --rounds 3` |
| `idle.txt` | `bench/idle.py --seconds 30 --rounds 3` |
| `big_output.jsonl` | `bench/big_output.py --mb 32 --runs 3 --flush` (macOS: also 256 MB in large writes) |
