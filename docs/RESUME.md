# Windows x64 port: where it stands

Paused on 2026-10-06. Everything below that is committed is green on windows-x64 (tests\aot 58/58, tests\cfg, the SysV check of
CFG dispatch: 0 in 3,264 objects); `patches\webkit.patch` and `patches\bun.patch` apply to the pins and give the engine commits'
trees exactly. Nothing is pushed.

## The runtime in use

`.work\runtime\bun.exe` is a copy of `.work\bun\build\pibolt-release-lto\bun.exe` as linked with the order file:

    runtime: Bun 1.4.3-canary.1+f7f19349b, CFG, build/pibolt-release-lto, LTO, 106022400 bytes, sha256 4610987bf0abbb88

`scripts\build-pi.ps1` uses it by default and writes that line into each build's `pi-bolt.txt`. Note that the build directory's
own `bun.exe` is not that one now: it is a heap experiment that was stopped (below), so `build-pi.ps1` warns that a newer build
exists; the next runtime build replaces it. Older runtimes are kept beside it in `.work\runtime` (`bun.exe.lto-noorder-*`,
`bun.exe.nolto-*`, `bun.exe.heap-tag-v1-broken`). The current Pi build is `out\pi-bolt-aot-lto-order`.

The LTO build directory is `build/pibolt-release-lto` (made by hand); `scripts\build-runtime.ps1` builds into
`build/pibolt-release`, so pass `-BuildDir build/pibolt-release-lto` to `train-runtime-hints.ps1` and `tests\cfg\run.ps1` meanwhile.

## Committed in this stretch

| Commit | What | Measured |
|---|---|---|
| bef494228 | mimalloc commits pages on demand, 256 KB stack commit, libuv stacks reserved, no Winsock at start, ICU with CFG | peak private bytes 203 -> 80 MB headless, 246 -> 117 MB TUI; CPU, TTI unchanged |
| e831e1439 | shell lookup walks PATH instead of starting `where.exe` | one process start less per shell tool call |
| db51f1354 | startup header before the colour reply when the theme cannot change; `PI_TTI_TRACE` | the editor no longer jumps; TTI unchanged |
| a7fcc14b8 | `build-pi.ps1 -Plugins/-PluginWorker`; suite measures plugin and JIT builds when present | |
| d3d68b846 | each Pi build records its runtime; the suite copies it into environment.txt | |
| 293329426 | runtime code laid out in the order Pi runs it (`profiles\runtime-win32-x64.hints`) | peak WS -4 to -7 MB; CPU 98 -> 93 ms headless, 309 -> 297 ms TUI |
| f4cf32990 | benchmark scenario `interactive-default-theme` | |

Default-theme check: the colour reply costs 1.6 ms in conhost and about 9 ms in Windows Terminal, which answers all 18 colour
queries before DA1; no timeouts. The behaviour is kept.

## Work list

1. **Heap first-touch ordering (next).** `--version` touches 2,096 `.pbheap` pages: the static atom table 256/256, atom
   StringImpls 399/451, FunctionExecutables 578/1,415. First step, stashed in `.work\webkit` (`git stash list`; a copy is in
   `.work\wip\webkit-heap-atoms-v2.diff`):
   - hash tag bits in the static atom table's entries, so a probe skips other strings without reading them; the distance width is
     recorded in the heap header (`distanceBitsOfStaticAtoms`, magic `BTHEAP08`; `scripts\lib\compare-static-heaps.py` too);
   - atoms and their JSStrings made in the string records' order, which is the training's first-use order.
   v1 (a fixed 24-bit distance) failed tests\aot: unsized test builds put atoms farther away. v2 records the width; it is not
   built yet. Then: build, tests\aot (also with `BUN_STATIC_HEAP_WRITES=all`), `-VerifyDeterminism`, and an A/B of two Pi builds
   from the same `dist` (old and new runtime) with `.pbheap` pages from `bench\ws_at_exit.py` beside CPU and WS. After that, the
   larger design: per-tier cursors in the arenas from a recorded touch map (`heap.order`), and a small hot atom table.
2. **Startup trace and filesystem calls.** About 65 ms of Pi's initialization precedes `tui.start`. Partial work is stashed in the
   fork (`git stash list`; a copy in `.work\wip\fork-startup-trace-and-heap-magic.diff`): `PI_TTI_TRACE` stages from process
   start through settings, sessions, models, extensions and tools, and `GetProcessIoCounters` in `bench\winproc.py`. To finish:
   measure the stages and the operation counts per scenario, then cut probes that cannot succeed.
3. **Startup mallocs**: what allocates about 3.9 MB at `--version`.
4. **Streaming CPU profile**: the reply-rendering path (810 ms against 931 ms on stock Bun).
5. **Plugin rows**: the two OpenSec extensions JIT off against the x64 JIT build; plugin builds in the suite.
6. **Clean suite** on AC power with nothing else running: floor rows, the default-theme row, the plugin rows; update
   `docs\BENCHMARKS.md` and drop its contention note.
7. **Docs**: `docs\WINDOWS.md` on the memory changes (commit on demand, stack commit) and the runtime order file.

Smaller items: a glance at JSC's nursery size; in Pi, caching in `Container.render`, request serialization, and startup fs probes.

## Open items for the owner

- **CET** (`/CETCOMPAT`): off, because JSC jumps to exception handlers without popping the shadow stack; the plan (rdssp/incssp)
  is in `docs\WINDOWS.md`. Needs a decision before work starts.
- **Reproducibility**: AOT output is not yet bit-for-bit deterministic (likely parallel type inference); documented in
  `docs\WINDOWS.md`.
- **Code-signing certificate** for the Windows executables. The owner signs.
- **Windows CI**: a runner that builds and tests windows-x64.
