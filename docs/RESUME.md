# Windows x64 port: where it stands

Paused on 2026-10-06; resumed 2026-10-09 (work list item 1). Everything below that is committed is green on windows-x64 (tests\aot 58/58, tests\cfg, the SysV check of
CFG dispatch: 0 in 3,264 objects); `patches\webkit.patch` and `patches\bun.patch` apply to the pins and give the engine commits'
trees exactly. Nothing is pushed.

## The runtime in use

`.work\runtime\bun.exe` is a copy of `.work\bun\build\pibolt-release-lto\bun.exe` as linked with the order file, with the heap
ordering of 2026-10-09 (below):

    runtime: Bun 1.4.3-canary.1+aaab7e31c, CFG, build/pibolt-release-lto, LTO, 106028032 bytes, sha256 6e5c9d5d5bd90288

(The revision is the engine commit's before it was amended with that work; the next runtime build says the amended one.)
`scripts\build-pi.ps1` uses it by default and writes that line into each build's `pi-bolt.txt`. Older runtimes are kept beside
it in `.work\runtime` (`bun.exe.lto-order-4610987b`, the one before; `bun.exe.lto-noorder-*`, `bun.exe.nolto-*`,
`bun.exe.heap-tag-v1-broken`; `heap-tag-v2\` is the tag bits alone). The current Pi build is `out\pi-bolt-heap-strings`
(this runtime, the retrained profile); `out\pi-bolt-heap-hot` is the same before the profile was, and `out\pi-bolt-ab-base` the
same `dist` on the runtime before, for A/B runs.

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
| (2026-10-09) | strings laid out in the order Pi touches them (`train-heap-strings.ps1`, profile retrained) | against the commit before: CPU 18.9 -> 18.0 / 99.9 -> 97.7 / 315.2 -> 310.0 ms, peak WS -1.8 / -2.0 / -1.9 MB (startup / headless / TUI); TTI unchanged |
| b6c343b07 | prebuilt heap laid out by first use: see work list item 1 | `.pbheap` pages 2,092 -> 1,567 at `--version`, 3,853 -> 3,325 headless; peak WS -2.0 / -1.8 / -1.6 MB (startup / headless / TUI); CPU at `--version` 19.7 -> 18.9 ms; headless and TUI CPU unchanged (within noise) |

Default-theme check: the colour reply costs 1.6 ms in conhost and about 9 ms in Windows Terminal, which answers all 18 colour
queries before DA1; no timeouts. The behaviour is kept.

## Work list

1. **Heap first-touch ordering: first round done (2026-10-09), in the engine commits.** tests\aot 58/58 (also with
   `BUN_STATIC_HEAP_WRITES=all`), CFG SysV check 0, `-VerifyDeterminism`: the heap is the same from both builds.
   - The static atom table's entries carry 8 bits of the hash above the distance, whose width is in the header
     (`distanceBitsOfStaticAtoms`, `BTHEAP08`): a probe passes over other strings without reading them. Atom StringImpl pages
     399 -> 257 at `--version`. The table is no more than 3/4 full (it was half): 256 -> 128 pages.
   - Bug fixed: the heap's copy of the string table was written in ordinal order and the executable's in the order file's, so
     `StaticHeap::tryCreateStringTable` never matched them (it compares 4 KB of each) and every Pi build decoded strings through a
     second `DecoderStringTable` instead of the heap's slots. The link encoder now gets the order file's strings
     (`Bun__BytecodeLinkEncoder__create`), so both copies are the same (checked: three identical copies in `pi.exe`), and the atoms
     and JSStrings are made in first-use order. JSString pages 65 -> 30.
   - Executables in two passes per module (`makeExecutables`): first the functions whose code is in the payload's Hot or Unknown
     region (the recorded run ran them) and the functions of code that ran (made when it runs, called or not), then the rest.
     FunctionExecutable pages 574 -> 376 at `--version`, 878 -> 626 headless.
   - Strings in the order Pi touches them (`scripts\train-heap-strings.ps1`, `scripts\lib\train_heap_strings.py`): a debugger
     guards the pages of the strings' records, StringImpls and JSStrings, records every access of `--version`, a headless
     prompt and a TUI session, and puts those strings first in the profile's `S` lines (the order file's own follow). What a
     prebuilt heap touches is what is looked up by name (the engine's identifiers, single characters, the global object's
     properties: 4,338 StringImpls at `--version`), not what the training's decoding read. StringImpl pages 252 -> 35, records
     254 -> 37, JSStrings 30 -> 19 at `--version`; `.pbheap` 1,567 -> 1,122 at `--version`, 3,325 -> 2,817 headless. The
     profile is the Pi version's, so a new Pi version needs `scripts/train-profile.sh` and then this, on Windows.
   Left (measured, `.work\exp\handoff\heapmap.py`, `heapmap_headless.py`; the tracer is `.work\exp\handoff\touchtrace.py`):
   FunctionExecutables 376/1,410 at `--version` and UnlinkedFunctionExecutables 179/690 (made by decoding, in payload order).
   The same trace of their pages says which are touched; ordering them needs the executables named across builds (module,
   start offset), as `orderFunctionKey` does, in a list the build reads. "Other malloc" 122/2,005 (568 headless).
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
