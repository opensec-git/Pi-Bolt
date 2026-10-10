# Windows x64 port: where it stands

Paused on 2026-10-06; resumed 2026-10-09 (work list item 1). Everything below that is committed is green on windows-x64 (tests\aot 58/58, tests\cfg, the SysV check of
CFG dispatch: 0 in 3,264 objects); `patches\webkit.patch` and `patches\bun.patch` apply to the pins and give the engine commits'
trees exactly. Nothing is pushed.

## The runtime in use

`.work\runtime\bun.exe` is a copy of `.work\bun\build\pibolt-release-lto\bun.exe` as linked with the order file, with the heap
ordering of 2026-10-09 (below):

    runtime: Bun 1.4.3-canary.1+1ad2e13f5, CFG, build/pibolt-release-lto, LTO, sha256 59f3cc77d4e9f852

(Built from the engine commits as they are: `1ad2e13f` in `.work\bun`, and in `.work\webkit` the commit `patches\webkit.patch`
makes.)
`scripts\build-pi.ps1` uses it by default and writes that line into each build's `pi-bolt.txt`. Older runtimes are kept beside
it in `.work\runtime`: `bun.exe.ship-cb39be1e` (the 0.7.0 candidate of 2026-10-09, before the hardening round),
`bun.exe.final-9e51d11a` (before the review's fixes), `bun.exe.heap-tiers-db54ae7d`,
`bun.exe.heap-hot-6e5c9d5d` (the heap ordering without the executables' tiers),
`bun.exe.lto-order-4610987b` (the one before any of it), `bun.exe.lto-noorder-*`, `bun.exe.nolto-*`,
`bun.exe.heap-tag-v1-broken`; the folders `heap-tag-v2`, `heap-hot`, `heap-functions`, `heap-tiers` hold each step's runtime.
The current Pi build is `out\pi-bolt` (this runtime, the retrained profile), with `out\pi-bolt-aot-lto-jit` (JIT on),
`out\pi-bolt-plugins` and `out\pi-bolt-plugins-jit` (the example plugin compiled in) for the suite.

The LTO build directory is `build/pibolt-release-lto` (made by hand); `scripts\build-runtime.ps1` builds into
`build/pibolt-release`, so pass `-BuildDir build/pibolt-release-lto` to `train-runtime-hints.ps1` and `tests\cfg\run.ps1` meanwhile.

## Release hardening (2026-10-10)

Seven read-only reviews (security of Pi's changes and the installers, security of the runtime, crashes in the engine and in Pi,
Windows 10, performance) and what came of them:
- **Fixed, a crash:** a worker (Pi resizes an image it is given in one) ended the process at once (0xC0000409): the realms'
  blocks were a 352 MB reservation, room for one. Now 4 GB of addresses (`StaticRegion.h`). `tests\pi\image.py` checks it.
- **Runtime:** DLLs that are delay-loaded come from System32 (`__pfnDliNotifyHook2`, `/DEPENDENTLOADFLAG:0x800`, WIC);
  `BUN_STATIC_HEAP_WRITES` only in a diagnostics runtime; a build without the JIT stays without it on every fallback; the
  debugging options that reach memory or load code are off in a compiled program; the blocks at a random address in a program
  (the builder keeps them at the top). See docs\WINDOWS.md, "The other mitigations".
- **Pi:** npm and git for the package manager are no longer looked up in the working directory (cross-spawn's `which` did, before
  any trust decision); the TUI's native helper is not resolved from the working directory's node_modules in a compiled
  executable; `pi-bolt update` turns TLS 1.2 on before its first download, installs the version it decided on, where this
  installation is, without a mirror or source from the environment, and checks the version that is there afterwards; quoted
  PATH entries; the Store's pwsh.exe alias.
- **Installers:** a signature is required, a mirror included (`PIBOLT_ALLOW_UNSIGNED=1` for a build of one's own); a file is moved
  next to its target before the old one is renamed aside, which is put back if the last rename fails; a folder outside the
  profile is made the user's, SYSTEM's and the Administrators' only; an extension whose lockfile does not show the pin is
  removed; Windows 10 on ARM is refused early; the AVX2 answer of Windows 10 is not taken for a no; pax records are capped.
- **Performance:** (one model refresh at start instead of two was tried and reverted: the one left took 23 ms, the two 12);
  `sanitizeSurrogates()` returns a well-formed string as it is
  (`isWellFormed()`), which every request ran a regular expression over the whole conversation for; the streaming reply's
  components are made when the frame is drawn, not at each delta.
- **Tests on Windows:** `tests\pi\run.ps1` and `tests\runtime\run.ps1` (the shell runners' counterparts, with Windows' own:
  programs started as Pi starts them, workers, Pi's image worker), and `scripts\windows-selftest.ps1`, self-contained, for a
  machine without the repository (Windows 10).
- **Not done:** CFG's check on compiled code's indirect calls (docs\WINDOWS.md); CET (cannot be tested here: Windows reports
  user shadow stacks unavailable on this machine, and SDE's CET emulation is Linux-only), planned after the release.

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
| (2026-10-09) | executables of the functions Pi touches made side by side, in two tiers (`train-heap.ps1`, profile retrained) | against the commit before: `.pbheap` at `--version` 1,122 -> 968; peak WS -0.6 / -1.4 MB (startup / headless), TUI of one prompt -1.1 MB; CPU and TTI unchanged (40-round TUI A/B: 93.0 vs 94.2 ms, quartiles overlapping) |
| 3540d99e6 | strings laid out in the order Pi touches them (`train-heap-strings.ps1`, profile retrained) | against the commit before: CPU 18.9 -> 18.0 / 99.9 -> 97.7 / 315.2 -> 310.0 ms, peak WS -1.8 / -2.0 / -1.9 MB (startup / headless / TUI); TTI unchanged |
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
   - Executables of the functions Pi touches made side by side (`scripts\train-heap.ps1` writes the profile's
     `heap-functions.txt`; `build-pi` passes it as `BUN_STATIC_HEAP_FUNCTIONS_FIRST`): each function is named by its module's
     number in the link and where it starts in that module's text (`StaticHeap::FunctionCell`), which both the decoder (for the
     unlinked executable) and `makeExecutables` (for the linked one) know before the cell is allocated. Two tiers, each a block
     at the start of the cells: what `--version` touches (6,632 functions), then what the TUI and headless runs touch besides
     (4,439). `build-pi -FunctionCellsOut` (`BUN_STATIC_HEAP_FUNCTION_CELLS_OUT`) writes where each executable is, which the
     trainer maps the trace through. `.pbheap` at `--version` 1,122 -> 968.
   - Strings in the order Pi touches them (`scripts\train-heap.ps1`, `scripts\lib\train_heap.py`): a debugger
     guards the pages of the strings' records, StringImpls and JSStrings, records every access of `--version`, a headless
     prompt and a TUI session, and puts those strings first in the profile's `S` lines (the order file's own follow). What a
     prebuilt heap touches is what is looked up by name (the engine's identifiers, single characters, the global object's
     properties: 4,338 StringImpls at `--version`), not what the training's decoding read. StringImpl pages 252 -> 35, records
     254 -> 37, JSStrings 30 -> 19 at `--version`; `.pbheap` 1,567 -> 1,122 at `--version`, 3,325 -> 2,817 headless. The
     profile is the Pi version's, so a new Pi version needs `scripts/train-profile.sh` and then this, on Windows.
   Left (measured, `.work\exp\handoff\heapmap.py`, `heapmap_headless.py`, `cellmap.py`; the tracer is
   `.work\exp\handoff\touchtrace.py`): the static atom table 128/128 (a small table of the hot atoms in front of it); "other
   malloc" 122/2,005 at `--version`, 568 headless (code blocks' arrays, symbol tables: the same trace and a name for each would
   do for them what was done for executables); `identifiersOfProgram` 69/75; MutableMalloc 178/348 headless.
   Build time: any WebKit change rebuilds Bun's precompiled header and its ~140 C++ objects (ccache cannot help those): find
   which input makes `pch/root-pch.h.hxx.pch` out of date (`ninja -d explain`). The ThinLTO cache (`/lldltocache`) has not
   shortened a link yet; measure on a quiet machine with `/time`.
2. **Startup trace and filesystem calls.** The trace and the bench's I/O counts are committed (5dca41b84). Measured, with one home
   reused as a user's is (`.work\exp\handoff\tti_stages.py`): ready at ~76 ms; 25 ms to `main()` (the runtime, and evaluating
   Pi's modules), ~10 ms for the model runtime, ~12 ms for resources and the models' refresh, 4.5 ms for the managed tools,
   4 ms for settings. A fresh home per run (`tti_probe.py`) adds 13-18 ms that is the on-access scan of files just written,
   not Pi. File calls (`.work\exp\handoff\fstrace.py`, a debugger on ntdll's file calls): `--version` 4; headless 200 (117
   failed probes); TUI 277 (128 failed, 45 directory listings). The failed ones repeat: `.agents\skills` and `.git` up every
   ancestor three times a start, `CLAUDE.md` and `CLAUDE.MD` both (one is enough on a case-insensitive volume, but for a
   per-directory case-sensitive folder), the gcloud ADC file six times. At ~50-65 µs a probe that is ~3 ms of a TUI start; to
   cut, in Pi: do the trust check's ancestor walk once per start (main.ts calls it twice), and memoize the walks within one
   load of resources.
3. **Startup mallocs**: the private pages at `--version` (1,385) are mostly mimalloc's arena (1,006), and of its commits
   (`.work\exp\handoff\commitwho.py`, a breakpoint on `_mi_os_commit_ex`, `pdbaddr.py` finds it) about half are JSC's
   MarkedBlocks: the objects evaluating Pi's modules makes (JSFunctions for top-level functions, Structures and their
   transitions, objects, environments), then Structure blocks and property tables. Less of it means making fewer of them at
   start (lazy closures, or a snapshot of evaluated modules): engine work, not a quick one.
   The static atom table (128 pages at `--version`): ~1,300 of ~4,000 lookups end at an empty entry (strings the program
   makes that are not static atoms), so a small table in front would not spare the big one; making those strings static atoms
   too (from a trace) would, and would save making them.
4. **Streaming CPU** (done, measured): a Node CPU profile of a TUI session (`.work\exp\handoff\node_prof.py`,
   `node_callers.py`) showed a deep copy of the whole conversation before every request (gone when no extension handles the
   context events, 473c37d2d) and request serialization (`JSON.stringify` of each request: inherent). Natively
   (`stream_prof.py`), the main thread's streaming time is mostly in system calls, and Pi writes once a frame
   (`writecount.py`: 419 frames, 419 writes); the rest is the socket reads of the model's events. Pi's compiled JS is ~3% of
   the samples. Nothing big is left there. Tried and reverted: a Markdown component's own invalidate() forgetting only its own
   blocks. The assistant message makes a new Markdown for each chunk, and the rendered blocks are shared between components
   through the token cache, so keying them by component made each chunk render the whole message again (the 50 KB write's
   longest stall 20 -> 87 ms, a 20,000-character answer's CPU 0.5 -> 0.73 s). Scoping it would need a generation of what the
   theme's functions give, not the component's identity. Measured and not kept on Windows: the 12 MB first heap budget and the 5 s GC timer
   that macOS has (-10 MB peak in the TUI, but twice the collections and +40% CPU while a long answer streams).
5. **Plugin rows** (done for the example plugin, in the suite): compiled in, its hot loop is 36 ms; loaded at run time with
   the JIT off, 750 ms (the JIT build: 32 ms). The two OpenSec extensions were not measured: they are not on this machine
   (downloading them needs the owner's go-ahead). Making run-time plugins fast is an owner decision (below).
6. **Clean suite** (done): `bench\results\2026-10-09-windows`, on the build that ships (`cdad1a5f8`), in
   `docs\BENCHMARKS.md`. The 50 KB write's longest stall (87 ms; 19 ms before the review's fixes) is the step's last tool
   call, `bash`, starting this machine's only `bash.exe`: WSL's launcher with no distribution, ~0.1 s. Node's `statSync`
   fails on that app execution alias (EACCES), so before `isProgramFile` the lookup found no bash and the call failed at
   once. Pi on Bun and Node runs it too (`where` lists it). A/B on one runtime: only `shell.ts` reverted, 19/20 ms.
7. **Docs** (done): `docs\WINDOWS.md` on the memory changes and the runtime order file.

Smaller items: a glance at JSC's nursery size; in Pi, caching in `Container.render`; the startup fs probes that are left
(each walk is one Pi needs again on a reload).

## Open items for the owner

- **CET** (`/CETCOMPAT`): off, because JSC jumps to exception handlers without popping the shadow stack; the plan (rdssp/incssp)
  is in `docs\WINDOWS.md`. Needs a decision before work starts.
- **Reproducibility**: AOT output is not yet bit-for-bit deterministic (likely parallel type inference); documented in
  `docs\WINDOWS.md`. With runtime 16ed5194, `-VerifyDeterminism` failed in 2 of 3 runs on one word of the data arena
  (`.pbheap` part 0, +0x190885c, not a pointer: "0 words differ by exactly the builds' addresses"): something there depends on
  where the building process was loaded. The release build is the one whose two builds agreed; what that word is (the build
  log's `describeBuiltAddress`, with `BUN_STATIC_HEAP_VERBOSE=1`) is to find out.
- **A write to the read-only prebuilt heap** ends the process on Windows (fail closed), where Linux and macOS take a private copy
  of the page: the release posture chosen. A write path that no traced run took would crash a user. Options: (A) as is; (B)
  write-through as elsewhere, losing the Windows-only protection; (C) as is, after a diagnostics runtime
  (`-DPIBOLT_STATIC_HEAP_WRITES`) with `BUN_STATIC_HEAP_WRITES=all` over the whole suite, npm test, tests\pi, the OpenSec
  extensions, the inspector, error paths and workers, and every writer moved to a mutable arena. Recommended: (C).
- **Stack commit at a thread's start** (`VM.cpp` `preCommitStackMemory`): when Windows cannot commit it, touching the pages fails
  the same way, and the thread ends with a stack overflow (as upstream). Better: keep the soft limit at what is committed, so
  that JavaScript gets a RangeError.
- **Code-signing certificate** for the Windows executables. The owner signs.
- **Windows CI**: a runner that builds and tests windows-x64.
- **`BUN_STATIC_HEAP_WRITES`**: decided (b), done: compiled out of release runtimes; tests\aot's `=all` needs a runtime built
  with `-DPIBOLT_STATIC_HEAP_WRITES` (or a debug one).
- **`pi-bolt update` trusts the site's install.ps1** (HTTPS only; what it downloads is then checked against the signed
  checksums). To make the script itself checked: put install.ps1 in each release and its SHA256SUMS, and have the update verify
  the signature and the script's checksum before it runs it. A change to the release steps (docs\RELEASING.md).
- **Run-time plugins with the JIT off** (750 ms against 32-36 ms): (a) recommend the x64-jit variant to who loads plugins
  with hot loops (exists; JIT on means generated code at run time); (b) compile installed plugins ahead of time on the
  user's machine, into a cached image the executable maps (code made on that machine, not signed: what `aotImagePath` was
  closed for; it would need its own check, a hash the executable keeps, for instance); (c) a JIT for plugin code only (the
  JIT is on, then, for whatever runs that code); (d) one executable with the JIT compilers in it, off at start, turned on only
  when plugins loaded at run time are configured (extension folders, `packages` in settings, `--extension`), so that who has
  none runs as today (to measure first: Pi's own rows with that executable and the JIT off; whether Pi's compiled functions
  tier up once it is on). Compiled in with `build-pi -Plugins` is already 36 ms.
- **From the review of 2026-10-09** (design, not fixed): code compiled ahead of time reaches some targets without CFG's
  check (the runtime table's entries in writable memory, entry words loaded from cells, the catch PC); a cold operation's stub
  returns by `pop; pop; jmp`, which would unbalance CET's shadow stack (and mispredicts returns). Both need a memory-corruption
  bug first; both are engine work.
