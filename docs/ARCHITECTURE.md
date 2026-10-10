# Architecture

This document explains what a Pi-Bolt executable contains, how it is built, and what happens when it starts.

- [The executable](#the-executable)
- [Build pipeline](#build-pipeline)
- [The ahead-of-time compiler](#the-ahead-of-time-compiler)
- [Launch](#launch)
- [JIT off and JIT on](#jit-off-and-jit-on)
- [The x86-64 port](#the-x86-64-port)
- [The macOS ARM64 port](#the-macos-arm64-port)
- [Limitations](#limitations)

## The executable

A Pi-Bolt executable is a Bun single-file executable with two extra sections appended:

```
┌──────────────────────────────┐
│ Bun runtime                  │  Bun + JavaScriptCore (with the AOT engine), ICU, statically linked
├──────────────────────────────┤
│ Bundled program              │  Pi's modules, as Bun's --compile embeds them (bytecode optional)
├──────────────────────────────┤
│ Static heap                  │  the JavaScript heap after Pi's modules were loaded and evaluated
├──────────────────────────────┤
│ Code image                   │  machine code for every function, regular expressions, metadata
├──────────────────────────────┤
│ Trailer                      │  offsets, engine stamp, CPU features and options the code was compiled for
└──────────────────────────────┘
```

Both appended sections are page-aligned, so that they can be mapped directly from the file.

## Build pipeline

`scripts/build-pi.sh` runs `bun build --compile --bytecode` with the Pi-Bolt runtime and these settings:

| Setting | Effect |
|---|---|
| `BUN_STATIC_HEAP=1` | Build the static heap. |
| `BUN_AOT=1` | Compile every function ahead of time into the code image. |
| `BUN_AOT_JIT=0` | Mark the code as running with the JIT off. This is the default build; `--jit on` leaves it out. |
| `BUN_JSC_omitBytecodeFromStaticHeap=1` | Leave bytecode out of the heap. Functions run from machine code only. This saves memory and enables program-wide numbering of identifiers and constants, which cross-function inlining needs. |
| `BUN_JSC_useAOTLoopSplitting=1`, `BUN_JSC_aotLoopSplittingPolicy=5` | Give loops a fast copy (see below). |
| `--bytecode-order=profiles/.../bytecode.order` | Lay out code and heap in the order the training session first used them. |
| `BUN_JSC_aotRegExpsPath=profiles/.../regexps.txt` | Compile regular expressions that Pi builds from strings at run time, as well as its literals. |

The steps are:

1. **Bundle.** Bun resolves Pi's module graph from `dist/bun/cli.js` and its two worker entries, and transpiles and links it.
2. **Bytecode.** Every module and function is compiled to JavaScriptCore bytecode, and the module graph is prelinked.
3. **Evaluate into a heap.** Pi's modules are loaded and evaluated in a VM whose heap is laid out for snapshotting. The result is
   serialized, with every pointer relocatable to a fixed address: the static heap.
4. **Compile.** The ahead-of-time compiler turns every function's bytecode into machine code, linked as one image.
5. **Append.** The heap, the image and a trailer are appended to the executable.

## The ahead-of-time compiler

The compiler comes from [oven-sh/WebKit#743](https://github.com/oven-sh/WebKit/pull/743). It takes JavaScriptCore bytecode
through its own graph and analyses, then through B3 and Air: the back end of JavaScriptCore's top-tier FTL JIT.

```mermaid
flowchart LR
    BC[Bytecode] --> G[AOT graph<br/>SSA, type inference,<br/>range analysis]
    G --> I[Inlining<br/>loop splitting<br/>escape analysis]
    I --> B3[B3<br/>optimizer]
    B3 --> Air[Air<br/>register allocation]
    Air --> MC[x86-64<br/>machine code]
```

A JIT learns what types flow through the code by watching it run. The AOT compiler has no such feedback, so it works from what
it can prove:

- **Type inference** over the whole program: types of locals, module bindings that always hold the same function, constants.
- **Guards** for what it assumes but cannot prove (a value is an int32, an object has a given shape), with a generic path when a
  guard fails. Nothing is deoptimized; the generic path is part of the compiled code.
- **Stubs**: shared machine-code routines for the generic paths (property access with inline caches, calls, arithmetic on
  unknown types). They keep the code compact, so a slow path costs a call, not code in every function.
- **Inline caches** for property access, filled in at run time, with a polymorphic inline check where it pays off.
- **Intrinsics** for hot built-ins: `Math.*`, `charCodeAt`, `push`/`pop`, and `Map`/`Set` `get`/`has`/`set`/`add`.
- **Inlining** of known small functions across the program: function declarations, functions held in module constants that
  are never reassigned, and methods whose name the program defines exactly once. A call through a constant checks that it has
  been initialized, and throws as it would if not. A method call checks that the callee is the function it was taken for, and
  makes the call if not; inside a loop's fast copy, that check leaves for the generic copy, so the fast copy keeps no call.
- **Array callbacks**: `forEach`, `map`, `filter`, `reduce` and the like are inlined with their callback. The standard objects'
  original methods are frozen when the program starts (`useImmutableIntrinsics`), which is what makes `[].map` knowable.
- **Loop splitting**: a loop gets a fast copy without slow paths, which falls back to a generic copy at the first failed check.
  This applies to loops without real calls, and to loops whose calls are to built-ins or known module functions, or that
  index arrays. String scanning and number crunching run several times faster this way.
- **Number encoding**: a number the compiler holds as a double is boxed as an int32 whenever it is one, as JavaScriptCore's
  `jsNumber()` does. Every fast path downstream (indexing, `charCodeAt`, compares, inline caches) is for int32s, so a counter
  boxed as a double would miss them all.
- **Regular expressions**: compiled by Yarr, JavaScriptCore's regular-expression compiler, into the same image. This covers
  literals, plus patterns the training profile recorded being built at run time.

## Launch

When the executable starts:

1. Bun finds the trailer and checks it against the engine:
   - the engine stamp must match exactly;
   - the CPU must have the instruction-set features the code was compiled for;
   - the address range the heap and code expect must be available.
2. **The static heap is mapped** at its fixed address in the static region (0x200000000000), copy-on-write from the executable
   file. Structures go to the 4 GB right after it, where the code expects them.
3. **The code image is mapped** executable and registered with the VM. Functions in the heap point straight at their machine
   code.
4. Pi's modules run, then its `main()`. Their functions and code are in the heap, so nothing is parsed or compiled; what the
   heap does not hold is the modules' state: their top-level code (and Bun's `node:*` modules they import) runs at every start,
   as it would without Pi-Bolt, only compiled.

If any check fails, the executable prints one notice and runs from bytecode instead: correct, just slower to start. The checks
are the engine stamp, the CPU features, the address space (for example a tight `ulimit -v`), and `BUN_AOT=0`. See
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

### Where the memory goes

- **Code and the static heap are file-backed.** Pages that are only read are shared with the page cache, and the kernel can drop
  them under memory pressure. A process's own memory (private dirty pages) is what it writes.
- **No JIT.** With the JIT off there are no compiler threads, no JIT code caches and no profiling data.
- **No bytecode**, because it is left out of the heap.

## JIT off and JIT on

| | JIT off (default) | JIT on (`--jit on`) |
|---|---|---|
| Pi's own code | AOT machine code | AOT machine code; it does not tier up |
| Code loaded at run time (run-time plugins, `eval`) | interpreter | interpreter, then baseline JIT, DFG and FTL as it warms up |
| Memory | lowest | higher once the JIT has compiled anything |
| Choose it for | Pi with compiled-in plugins | heavy use of run-time-loaded plugins |

## The x86-64 port

The upstream compiler targets ARM64: on x86-64 nothing was compiled ahead of time. Pi-Bolt's
[WebKit patch](../patches/webkit.patch) adds the x86-64 back end and runtime work, and its [Bun patch](../patches/bun.patch) adds the
executable format.

**x86-64 code generation**

- A register convention for stubs on x86-64's smaller register file. Stubs work in the C argument and scratch registers. "Light"
  stubs also preserve `rbx` and `r12`, so values the caller keeps there survive a property access.
- Every stub family ported: property access, calls, arithmetic and comparisons, iteration, intrinsics, `Map`/`Set` lookups, and
  bit operations on doubles.
- Regular expressions with the `u` or `v` flag: the surrogate-pair slow path is generated into the image, so Unicode patterns
  run as machine code too.
- Truncation of doubles to int32 with a short inline sequence, instead of a C call.
- `charAt`/`charCodeAt` resolve a substring in place, as `codePointAt` already did, so the compiled fast paths read it directly.

**Runtime**

- Realms other than the main one (`ShadowRealm`, `vm` contexts) commit their memory lazily, not 400 MB up front.
- Address-space limits are handled: the static region and allocation pools shrink or fall back when `RLIMIT_AS` or
  `vm.overcommit_memory=2` will not give them their usual reservation.
- Worker threads share the static heap.
- Portable executables: the build links against a glibc 2.17 sysroot with static ICU.

**Executable format (Bun)**

- `bun build --compile` appends the static heap and code image, and the executable maps them at launch, with a fallback to
  bytecode when it cannot.
- Training profiles: function order output (`BUN_BYTECODE_ORDER_OUT`) and run-time regular-expression recording.

## The macOS ARM64 port

The compiler's ARM64 back end is upstream's; the macOS port is about the executable around it, and about what the x86-64 work
had left x86-64 only. The macOS parts of Pi-Bolt's [WebKit patch](../patches/webkit.patch) and [Bun patch](../patches/bun.patch) are this port's.

**Where the image is.** Bun keeps a compiled program in the `__BUN` section of the Mach-O executable, which starts on a 16 KB
page of the file, as the static heap and code image need. The executable asks the kernel which file and offset back the static
heap's bytes (`proc_pidinfo` with `PROC_PIDREGIONPATHINFO`), opens that file and checks that it is the one mapped (device and
inode): the equivalent of Linux's `/proc/self/exe`.

**Executable code without a JIT.** On Apple silicon every executable page must be covered by a code signature. `bun build
--compile` signs the whole executable, `__BUN` included (ad hoc), so the code image is mapped executable straight from the file,
as on Linux: no writable code and no `MAP_JIT`. With the hardened runtime (which notarization requires) mapping a file's pages
executable is subject to library validation: an ad hoc signature then needs
`com.apple.security.cs.disable-library-validation`; `allow-jit` does not help. Without the hardened runtime, as released, nothing
is needed.

**One address for the executable.** The static heap holds pointers into the executable itself: native functions, what describes
the engine's classes, the text of static strings. On Linux the runtime is linked without PIE, so those are good in every process.
On macOS the executable runs at the address it was linked for: `pi` is a small launcher
([`scripts/lib/darwin-launcher.c`](../scripts/lib/darwin-launcher.c)) that starts `pi-bin`, the executable beside it, that way in
the same process (`posix_spawn` with `POSIX_SPAWN_SETEXEC`). The system's libraries still move at every boot; the build checks
that the heap points at none of their functions or objects. If the executable is not at its address after all, it runs from
bytecode.

**A start from disk.** When `pi-bin` is not in memory (after a restart or an update), a start reads its pages one by one as each
is first touched, and waits for the disk each time: about 170 ms instead of 30. `build-pi.sh` records which parts of `pi-bin` a
start reads (`pi-bin.hot`, from [`scripts/lib/darwin_hot_pages.py`](../scripts/lib/darwin_hot_pages.py): the TUI with one prompt
and `pi -p` against the scripted model), and the launcher asks for all of them at once (`F_RDADVISE`) before it starts `pi-bin`,
so that the disk reads them while dyld and the engine start: about 65 ms. It is only advice: a list that is missing, malformed or
made for another `pi-bin` (its first line is `pi-bin`'s size) changes nothing, and pages already in memory cost nothing.

**The programs Pi starts.** Before the launcher starts `pi-bin`, it forks a helper that is not `pi-bin`'s descendant
([`scripts/lib/darwin-spawn.h`](../scripts/lib/darwin-spawn.h)), so that the programs Pi runs start with the system's own
process setup rather than `pi-bin`'s. Where Bun would `posix_spawn` a program, it starts `pi-spawn` instead (`spawn_through_proxy`
in Bun's `posix_spawn.rs`), which hands the helper what it was given: its files (`SCM_RIGHTS`), directory, environment, signal
mask and ignored signals, limits, umask, and process group or session. A worker forked by the helper starts the program;
`child.pid` is the program's pid and `kill()` signals it; `pi-spawn` waits for word that the program has exited and exits the same
way, so `pi-bin` waits for it as it would for the program. If `pi-spawn` is killed, the worker kills the program. Without the
helper, `pi-spawn` runs the program in its own place. Not covered: programs started on a pseudo-terminal or as another user, which
Bun starts with `fork`. It costs 0.3 ms at each start and about 1.4 ms per program.

**Fixed regions.** The static region (36 GB of address space at 0x200000000000) and the structures' 4 GB are free in every
macOS process. macOS has no `RLIMIT_AS`, so the fallbacks for an address-space limit are not needed there.

**Code generation.** The ARM64 code that the later x86-64 work changed compiles and passes the engine tests and the fuzzer. As on
x86-64, a regular expression with the `u` or `v` flag now carries the surrogate-pair slow path in its own code, so it runs as
machine code on 16-bit subjects too (87 of Pi's patterns had none on ARM64). The image records the ARM64 features the compiler
uses (LSE, JSCVT, FP16, FRINTTS, SHA3, DotProd): every Apple silicon CPU has them, and a CPU without one runs from bytecode.

**The runtime.** Built natively against Xcode's SDK, for macOS 13 and later and `-mcpu=apple-m1`, with ICU from the system
(`libicucore`). With the JIT off and no sandbox policy given, JavaScriptCore on Darwin turns `SharedArrayBuffer` off, which Pi's
codemode worker needs; Bun now says it is not sandboxed.

**Fewer pages, a smaller first heap.** The runtime is linked with an order file: the functions a Pi session enters, in the order
it first enters them (traced from sessions of Pi, [`profiles/runtime-darwin-arm64.hints`](../profiles/runtime-darwin-arm64.hints)),
then those Bun's own workloads enter, placed together at the front of the code, so that Pi maps and keeps fewer of the
executable's pages (9 MB less resident for `pi --version`). The heap's first collection comes after 12 MB rather than after as
much as the program's modules weigh (about 23 MB for Pi): with a static heap the modules are not loaded from those bytes, and
most of what starting allocates is garbage soon after.

**Builtin modules when they are needed.** In an executable compiled ahead of time, a module that is evaluated later (one that the
program `import()`s) requires the builtin modules it imports when it is evaluated, as a CommonJS bundle does, instead of having them
imported before the program starts. A start of Pi then loads none of `node:http`, `https`, `net` and `tls`, which only Bedrock,
the proxy agents and the OAuth callback servers use (the first two alone cost a start about 60 million instructions).

## Limitations

- **Linux x86-64 and macOS on Apple silicon** only. Windows and Linux on ARM64 are not built yet.
- **Code loaded at run time is not compiled ahead of time.** With the JIT off it is interpreted. Compile plugins in
  ([PLUGINS.md](PLUGINS.md)) or use the JIT-on build.
- **No type feedback.** A warmed-up JIT specializes on the types it actually observes, and the AOT compiler can only use what
  it proves. Code written so that types are predictable gets the fast paths ([PLUGINS.md](PLUGINS.md#performance-guide)).
  Pi itself, including the long streaming phases, now uses less CPU than stock Bun's warmed-up JIT
  ([BENCHMARKS.md](BENCHMARKS.md)).
- Each executable is built for one Pi version and one engine. The executable checks the engine stamp at launch.
