# Pi-Bolt on Windows x64: design notes

Work in progress, on the `windows-x64` branch. This is the design for keeping the prebuilt heap and the code image with every
Windows mitigation on, and what it rests on: measurements on this machine (Intel Core i5-1335U, Windows 11 26200, Defender on).
Nothing below has run Pi yet: it is what the port is being built on.

## What has to be at a fixed address today

On Linux and macOS, `bmalloc::StaticRegion` is 36 GB of address space at `0x200000000000`, and three things depend on that:

1. **The prebuilt heap's pointers**: to other parts of the region (cells, strings, tables), to the region's runtime part (Bss:
   the empty string, the engine's symbols, the VM and the global object at fixed offsets), and into the executable (native
   functions, class info, the text of static strings).
2. **Offsets from the region's base**: the static atom table stores `(atom - base) >> shift`; per-function rows store offsets
   into an arena.
3. **The code image**: compiled code reaches the VM, the realm and every table through a pinned register (`instanceGPR`) and a
   runtime table, and the compiler *declines any function whose code contains an address* (`AOTCompiler.cpp`). The only absolute
   value in code is the structure heap's base (`structureIDBaseOfImages`), OR-ed into StructureIDs as a 64-bit immediate.

So the code is already position-independent. What is not is the heap, the region's base, and the structure heap's base.

## The design

**The region starts with a section of the executable.** The runtime has an uninitialized, read-only section, `.pbreg`: the
32 MB of `Arena::Bss` that has things at fixed offsets (the empty string, the engine's symbols, the VM, the global object, the
modules' decoders), nothing in the file. `StaticRegion::base()` is its first byte rounded up to 64 KB: a linker symbol, so a
RIP-relative `lea`, relocated with the image by ASLR. Offsets in Bss stay compile-time constants, so `StringImpl::empty()` and the
engine's symbols cost what they cost on Linux. Pages are made writable only as the engine places things in them
(`StaticRegion::makeWritable()`), so a process is charged only for those. (Why only 32 MB: see "What was measured".)

**The other arenas follow the image** (`bmalloc::StaticRegionLayout`, in the read-only section `.pblay`). While the runtime builds
a heap, they are reserved right after its image, at the next 64 KB. In the compiled executable, `bun build --compile`
(`Bun__StaticHeap__rewritePE()`, before Bun's own PE writer adds the module graph) adds them at the same place relative to the
image, as sections as big as they are: the heap's arenas as initialized data (`.pbheap`), the code's tables read-only
(`.pbimage`), the code as `RX` (`.pbcode`), and writes their layout into `.pblay`. `scripts\build-pi.ps1` builds twice: the first
build says how big each arena is (`BUN_STATIC_REGION_SIZES_OUT`), and the second makes them that big (`BUN_STATIC_REGION_SIZES`), so
that there is no room between them, which would be uninitialized. (The heap cannot be packed after it is built: tables hashed by
address, and the static atom table, have positions relative to the region in them.) `StaticHeap::allocateBlock()`'s blocks, which
only a running program makes, are a reservation of their own, anywhere, decommitted when they are freed.

**What a running program does not write to is read-only.** The arenas it is not expected to write to (Data, Malloc, Cells) are
read-only sections: their pages are the executable's, shared, and no process is charged for them. Only MutableCells and
MutableMalloc (1.7 MB for Pi) are writable sections. **A write to the others is a fault**: an exception handler that runs
before any other (`StaticRegion.cpp`) says in one line where it was, and Bun's crash handler reports it. What the engine did write
there has been moved to the mutable cells: the tables of a scope's variables (`SymbolTable`, whose lock a lookup takes when the
JIT is on) and a program's or module's top-level code block (whose execution counter the interpreter's prologue bumps). Logging
every write (`BUN_STATIC_HEAP_WRITES=all`: the page is writable for one instruction, the trap flag, then read-only again),
tests\aot in all three modes (JIT on, JIT off, compact) wrote nothing; before the move, the JIT-on tests wrote 5,500 times
(mostly symbol tables' locks). For finding out what else belongs there, `BUN_STATIC_HEAP_WRITES=1` lets writes through again, a
page at a time (about 5 µs a page), as on Linux, where the arenas are private writable mappings, and says where the first
write to each page was.

**The heap is in the executable once.** The module graph that Bun's PE writer adds (`.bun`) keeps only the heap's header, a copy
of the first 4 KB of its string table and the code image's first page (`StaticHeap::compactForExecutable()`): what says that the
sections are this heap's. (It had the whole heap, 104 MB of Pi's executable a second time.)

The code gets one `RUNTIME_FUNCTION` in the exception directory (`.pbpdata`), so stack walks go through compiled frames. Every
pointer in the heap becomes a **base relocation** (`IMAGE_REL_BASED_DIR64`): pointers into the region and into the executable move
by the same delta, since both are the image. The Windows loader applies them.

**Nothing executable is at a fixed address**: the code is a section of the image, wherever ASLR puts it, and needs no relocation.

**The structure heap is wherever Windows puts it.** On Windows the code ORs the base from the realm's `Instance`
(`or64(Address(instanceGPR, Instance::offsetOfStructureIDBase()), reg)`) instead of a 10-byte immediate: one load that is in L1.
To be measured.

**Pointers that are not plain words.** A base relocation moves an aligned 64-bit word. What the heap holds otherwise was found by
building the same program with two copies of the runtime (two files, so two ASLR bases) and comparing the heaps
(`scripts/lib/compare-static-heaps.py`; `scripts\build-pi.ps1 -VerifyDeterminism` does it for Pi):

- *Packed pointers* (`PackedRefPtr`, 6 bytes, unaligned): on Windows the few kinds of them that a prebuilt heap holds
  (`VariableEnvironment`'s names, a global code block's directives) are plain `RefPtr`s.
- *Words with bits above the address*: an `AOT::EntryWord` (the number of parameters above a code address) and
  `AOT::FunctionInfo`'s executable (flags above it). The builder records where each of them is
  (`StaticHeap::taggedWordsOfBuiltImage()`); the writer relocates exactly those, and a relocation of the whole word leaves the bits
  above the address as they are. Nothing is relocated because it merely looks like a pointer: a NaN-boxed double could.
- *Tables hashed by address* (`PtrHash`): on Windows a pointer is hashed by its distance from the region, which is at a fixed
  place in the executable, so a table built in one process finds its keys in another. Subtracting a constant keeps distinct
  pointers distinct; it costs one `lea` and one `sub`.
- *Compiled stubs that embed an address* (those not yet ported to x86-64 called a reporting function by its address): on Windows
  they trap instead, with the stub's number in the first argument register.

The writer refuses a heap that points into any other module (a system DLL moves at every boot), or into what only a build has.

*Bytes that were never written.* A disengaged `std::optional` is copied without its value's bytes, which keep whatever was
there; a class element's initializer position carried 12 bytes of the builder's memory into the heap that way (an address,
different in every build). It is a type whose bytes are always written now.

*The compiler's own order.* Pi's compiled code is not laid out the same way twice, even by the same runtime: the number of
inline-cache slots a function gets differs between builds (the same on Linux and macOS). Then the code and the entry words
differ between two builds, and `-VerifyDeterminism` judges what it can: the data arenas must be identical, and no word may differ
by exactly as much as the two builds' addresses did (a pointer that was not relocated). **Open, engine-wide, to be fixed before a
release is called reproducible or verifiable.** Read, not yet confirmed by a run: the likeliest cause is the type inference that
runs over all functions in parallel before compiling (`CachedTypes.cpp`, the inference rounds): a unit reads summaries that other
threads are still writing, a rule that is not monotone (`AOTTypeInference.cpp`: an array with no store seen yet is untyped)
widens a type on a stale read, and the join keeps it; the worklist after each round follows hash order
(`copyToVector(next)`), and `ClassesOfProgram` is read while other threads write it (`MethodsOfProgram` has a `seal()` for
that; it has none). The plan: build twice with `BUN_JSC_numberOfAOTCompilerThreads=1` (identical output points at the
threads); make each round read a snapshot and merge its results in unit order; make the rule monotone; sort the worklist; seal
`ClassesOfProgram`; then require identical output from 1, 2 and the default number of threads.

## What was measured

A test executable with a 128 MB section appended after linking, with 48,526 pointers spread like Pi's heap (35% of its 16 KB
pages; macOS measured 26,000–50,000 pointers in 8,000 pages), and base relocations for every one:

| | First run of a new file | Later runs |
|---|---:|---:|
| No pointers | 383–565 ms | 45 ms |
| Pi-like, 48,526 relocations | 431–513 ms | 43–46 ms |
| 2,097,152 relocations (one every 64 bytes) | 602–726 ms | 53–57 ms |

Reading every page of the section; 3 fresh copies each. (Clock-tick CPU times are too coarse to split further.)

- **Every pointer was relocated** (`bad: 0`), at high-entropy bases such as `0x7FF6E4EE0000`.
- **The relocation is once per image section, and shared.** Every later process of the same file got the same base, and every
  page of the section was reported shared (`QueryWorkingSetEx`, share count above 1). Private bytes did not grow: 3.3 MB for the
  128 MB read-only section.
- **At Pi's density relocation costs nothing measurable**; the first run of a new file is dominated by reading it (and by Defender,
  which scans a new executable once). Two million relocations cost about 150 ms once.
- **A writable section is charged to commit in full** when the image is mapped (131 MB of private bytes for the 128 MB section,
  nothing written). A read-only one is not. Hence `.pbreg` is read-only (`/SECTION:.pbreg,R`) and pages are made writable as
  needed: `VirtualProtect` takes 1.8 µs a page, and commit is charged per page. A page of an image cannot be decommitted
  afterwards (`VirtualFree` fails, error 87); freed GC blocks there are zeroed instead.
- **The loader refuses images of about 2 GB** ("not a valid application"); 1.7 GB loads. (A first version had the whole region in
  the image, 1,344 MB: 128 MB for data, malloc and cells, 64 MB for each mutable arena, 192 MB for the code image, 256 MB for
  what is only used while building, and 384 MB for Bss, with a realm's 256 MB of room for its compiled code's data. Now the image
  has the 32 MB of Bss and the arenas as big as they are.)
- **An uninitialized section is charged to the system's commit in full, read-only or not, and for as long as the image is
  cached.** The measurement above looked at the process's private bytes, which do not show it. The system's commit charge
  (`GetPerformanceInfo`) does: a fresh copy of the runtime, of a compiled program or of a bytecode build each added 1,348–1,351 MB
  on its first run (the 1,344 MB `.pbreg` of the first version), and that stayed after the process exited; `node.exe` added nothing. Windows keeps
  an image section, and its charge, until the file changes or is deleted, and did not let go of them under pressure: 67 test
  executables filled this machine's 41 GB commit limit ("the paging file is too small"), and opening them for writing (which
  drops the cached section; nothing written) gave back 30.6 GB. Confirmed independently with a 1 GB read-only uninitialized
  section. `bench/image_commit.py` measures it per executable; `bench/winproc.py` reports the system's commit charge with every
  run. So the region is only as big in the image as it has to be (see "The design"). Read-only pages with base relocations cost
  nothing of the kind: a 128 MB read-only section with 48,526 or 2 million relocations added no system commit (within a few MB),
  while the same section writable cost 131.5 MB of private bytes in each process (given back when it exits).
- **A write-fault handler** that makes a read-only heap page writable on first write costs about 5 µs a page (4,000 pages in
  21 ms): an option for the arenas that are rarely written, if their commit charge turns out to matter.
- **The address space right after the image** was free in this test (a 4 GB reservation there succeeded), but nothing needs it
  any more.

## The other mitigations

Stock Bun 1.4.2 for Windows is linked with `/DYNAMICBASE`, `/HIGHENTROPYVA` and `/NXCOMPAT`, but **without Control Flow Guard**
(no `GUARD_CF`, an empty function table) and **without `/CETCOMPAT`**. Pi-Bolt must add both:

- **CFG: linked in, and verified** (`GUARD_CF` is required of the executable by the build's binary checks): every C and C++
  object with `/guard:cf`, Rust with `-C control-flow-guard`, the link with `/guard:cf`; tests\aot passes all 58 checks with it,
  and Pi runs. Three things had to be done for it:
  - **LLVM checks an indirect call to a SysV-convention function with the target in the wrong register.** On x64 a checked
    call goes through the loader's dispatch function with the target in RAX, which LLVM assigns only for the Win64 convention
    (`CCIfCFGuardTarget` is in `CC_X86_Win64_C`, not `CC_X86_64_C`). JavaScriptCore on Windows x64 declares its host functions,
    JIT operations, custom getters and setters and compiled regular expressions `sysv_abi`; for a call to one, the target goes
    in the next argument register and the dispatcher jumps to whatever RAX held (LLVM 23.1: Pi failed at its first custom
    getter, calling a StructureID). Such calls go through `WTF::callSysV()` (`wtf/SysVCall.h`): it checks the target with the
    loader's check function (target in RCX, as an ordinary call has it, so the check is the one the dispatcher would make),
    then calls it unchecked (`guard(nocf)`). `FunctionPtr`'s call operator does that for every pointer declared a JIT
    operation or host call; the few raw pointers (Yarr's code, the DFG's math functions, FFI thunks, JIT probes, the
    structured-clone writer) call it directly, and Rust's calls of the structured-clone writer go through C++
    (`Bun__StructuredClone__writeBytes`), rustc's LLVM having the same bug. `scripts\lib\check-cfg-sysv-calls.py` reads every
    bitcode object of an LTO build (Rust's from its archives) and fails if any such call is checked the broken way;
    `scripts\build-runtime.ps1` runs it after an LTO build (3,264 objects, about two and a half minutes; none found).
  - **The compiled code's entry points that C++ calls through a pointer** (the regular expressions) are added to the image's
    table of valid targets: the PE writer writes the executable's table with them, sorted, in a section `.pbgfids`, and points
    the load configuration to it (Pi: 5,960 added to 31,577). Nothing is registered at run time.
  - **The jsc shell is not linked in an LTO build** (`deps/webkit.ts`): WTF's weak declarations of what Bun defines become
    duplicate definitions across ThinLTO modules in COFF; Bun needs only the libraries.

  What CFG costs in memory: the loader keeps a bitmap of valid targets, about 1.4 MB resident for Pi. With the JIT on, JSC's
  1 GB executable reservation is all valid targets, which costs 16 MB of bitmap pages; Pi runs with the JIT off.
- **CET shadow stacks: not linked in, for a reason the code shows** (read, not run: this machine runs no process with shadow
  stacks, not even Edge, nor one created with `PROCESS_CREATION_MITIGATION_POLICY2_CET_USER_SHADOW_STACKS_ALWAYS_ON`;
  `.work/exp/cet/cet_run.py`). JavaScriptCore reaches every exception handler by a jump that discards frames without popping
  the shadow stack (LLInt's throw trampolines, `llint\LowLevelInterpreter64.asm`; the JIT's `jumpToExceptionHandler`; the
  compiled code's `callAndCheckException`, `unwind()` and catch entry, `aot\AOTStubsX86_64.cpp`). The next `ret` then finds a
  stale return address on the shadow stack: the catching LLInt function's return when a frame was discarded, every compiled
  function's after it catches (the operation stub's call is always abandoned), and every uncaught exception's return to the
  entry frame (`llint_handle_uncaught_exception`). All of that is in `pi.exe`, so with `/CETCOMPAT` even compatibility mode
  would end Pi at its first caught exception. To earn it, JSC must record the shadow stack pointer at VM entry
  (`VMEntryRecord`, read with `rdsspq`), have the unwinder compute the handler's (one entry per physical frame between), and
  pop to it with `incsspq` before each jump to a handler (skipped when shadow stacks are off); then the test matrix below, on
  a machine with CET (Intel 11th generation or AMD Zen 3, Windows 11, the mitigation on for `pi.exe`:
  `Set-ProcessMitigation -Name pi.exe -Enable UserShadowStack`, and again with `UserShadowStackStrictMode`; the owner's
  setting to make): a positive control (a `/CETCOMPAT` program that overwrites its return address must end with 0xC0000409),
  the policy read back from the running process, and exceptions thrown and caught in one frame and across many, from host
  functions, through callbacks from C++ (`toJSON`, getters, Proxy traps), uncaught at the top level and in promise jobs,
  rethrown, through `finally`, a caught stack overflow, generators and async functions, tail calls, arity fixup, workers,
  tests\aot and Pi's own tests; a violation is exit 0xC0000409 with fast-fail code 57. `SetProcessDynamicEnforcedCetCompatibleRanges`
  does not help: it only adds enforcement for dynamic code, and cannot exempt code in the image.
- **Unwind information** for the code image: one `RUNTIME_FUNCTION` for all of it (`.pbpdata`), so that stack walks and crash
  reports go through compiled frames.

## Where Windows startup time and memory went

Measured with `bench/ws_at_exit.py` (what a run has resident when it ends, by section and reservation; it runs the program as
its debugger, which sees it at its exit) and a sampling profile of the threads (`.work/exp/stack`, not shipped).

- **JavaScriptCore touched 5 MB of stack at its start, a page at a time.** On Windows it pre-commits the stack down to its soft
  limit (`maxPerThreadStackUsage`, 5 MB), because LLInt, JIT and compiled frames can be bigger than the guard page that grows
  the stack. It did so by touching every page, each a guard-page fault: about 1,260 of them, some 20 ms of a fresh thread, and
  the 5 MB stayed resident for the life of the thread. Every Bun on Windows does it, stock Bun included (`bun -e 0`: 1,303 of
  its 1,770 private pages). `preCommitStackMemory` (VM.cpp) now commits those pages instead (0.1 to 0.35 ms), and leaves the
  stack as the system leaves it when it grows: the guard page below them, and the thread's stack limit in its TEB lowered to them
  (exception dispatch and `_chkstk` read it). It is the one place that writes the TEB; it checks afterwards that the stack is as
  the system would have left it (`VirtualQuery`, the limit), and touches the pages as upstream does if it is not. The guarantee is the same (the pages are committed, as touched ones were, and charge
  the same commit), but none is resident until used. `.work/exp/stack/precommit_test.cpp` checks it: the 5 MB committed and not
  resident, a frame that writes far below the stack pointer, SEH and C++ exceptions raised and caught down there, and recursion
  past it still growing the stack and ending in a catchable stack overflow. (Committing them through the executable's header
  instead, `/STACK:0x1200000,0x600000`, was tried and measured: the header's commit is every thread's default, not only the main
  thread's, and Bun's, libuv's and the system's thread pools create theirs with a reservation size only, so each committed 6 MB
  instead of 2: Pi's peak private bytes went from 194 to 233 MB headless and from 246 to 302 MB in the TUI.)
- **A page fault on the executable's image** costs about 1.5 µs once the file is in memory, 44 µs on the first run after it
  is not (`.work/exp/stack/imagefault.cpp`). `pi --version` touched about 1,900 pages of the runtime's code, so the runtime's
  code is now laid out in the order Pi enters it, as on macOS: `scripts\train-runtime-hints.ps1` traces which runtime functions
  Pi enters, in first-entry order, from a headless and an interactive session against the local fake model (a debugger of the
  one process, `functrace-windows.c`), into `profiles\runtime-win32-x64.hints`, which `scripts\build-runtime.ps1` turns into the
  linker's order file. Peak working set 29.6 -> 25.8 MB at `--version`, 79.9 -> 74.1 MB headless, 110.5 -> 103.4 MB in the TUI;
  CPU 98 -> 93 ms headless, 309 -> 297 ms in the TUI. The list is of names: it holds from one runtime build to the next, and is
  made again when what Pi runs has changed much.
- **Memory committed and never used.** Commit charge is what Windows promises a process, whether or not it touches it, and
  what "private bytes" shows; Pi's peak was 203 MB headless and 246 MB in the TUI. Now: mimalloc commits its pages' memory on
  demand (`MI_DEFAULT_PAGE_COMMIT_ON_DEMAND=2`) instead of a whole segment up front; the main thread's stack commit in the
  executable's header is 256 KB, and JavaScriptCore commits what it needs itself (above); libuv's threads reserve their stacks
  instead of committing them (`STACK_SIZE_PARAM_IS_A_RESERVATION`); the inspector no longer starts Winsock at every launch;
  timers round their waits up to a whole millisecond (a shorter wait is a busy one on Windows); a pipe writer keeps 256 KB when
  it shrinks. Peak private bytes 203 -> 80 MB headless and 246 -> 117 MB in the TUI, with CPU and time to interactive the same.
  ICU is built with `/guard:cf`, as the rest is.
- **The prebuilt heap's pages** were a quarter of `pi --version`'s: 2,092 of 7,655 page faults (`.work/exp/handoff/heapmap.py`
  classifies them). What was laid out in the order the training's decoding used it was not in the order a prebuilt heap is used:
  there is nothing left to decode, and what Pi touches as it starts is mostly what the engine and Pi look up by name. A string's
  three parts (its record, its atom's StringImpl, its JSString) are now laid out in the order Pi's runs first touch them, and
  the executables (unlinked and linked) of the functions they touch are made side by side, those of Pi's start first:
  `scripts\train-heap.ps1` traces the runs (every access to those pages, by a debugger that guards them) and writes the
  profile's string order and `heap-functions.txt`. The executables of each module that the training ran or made come before the
  rest without it, and the static atom table's entries carry hash bits, so a lookup reads no other string, in a table half the
  size. Atom StringImpls touched at `--version`: 4,338 of 76,955, which now fit on about 30 pages instead of 250; executables:
  about 6,700 of each kind at `--version`, 11,000 in all three runs, of 38,000.
- **Looking up a program in PATH** costs 5 to 7 ms for one that is not there (four extensions in each of this machine's 38
  directories; a probe for a missing file is 45 to 65 µs with Defender's filter). Pi looks for `rg`, `fd` and `fdfind` before
  its first frame. On Windows it did so by running each as `cmd --version`: three misses when none is installed, and a process
  start for each one that is. It now looks them up, as on Linux and macOS, from PATH's absolute directories, with the
  extensions spawn tries (`.com` last), and returns the file's path. That is also safer: spawning a bare name on Windows looks in
  the working directory first, so a project's own `rg.exe` would have run at Pi's start, and as the search tools.
- **Other file probes at start.** A TUI start makes about 280 file calls, 128 of them probes for files that are not there
  (`.work/exp/handoff/fstrace.py`, a debugger on ntdll's file calls). Most are Pi looking up the directory tree for project
  resources (`.agents\skills`, `.git`, `AGENTS.md`, `CLAUDE.md`), a few times a start, which it must do again on a reload; one
  repeat was not needed (the trust check asked twice about the same directory at startup) and is gone. What is left is about
  3 ms a start at this machine's depth.
- **A copy of the conversation before every request.** The extensions' context transform deep-copied the whole conversation
  before each request to the model, for handlers that may edit it, also when no extension had one. It no longer does then.
- **A fresh home is slower to read.** A file just written is scanned by the on-access scanner when it is first opened (1 to
  15 ms here), so a benchmark that makes a new home for every run measures that, not Pi: the bench reuses one home per session.

## What would carry over to macOS

The same idea, applied to Mach-O, would remove the ASLR exception there: the region as a section of the executable (a
zero-fill section), the heap's pointers as rebase opcodes of the chained fixups, applied by dyld. The difference is that dyld
applies rebases in the process (private dirty pages, the 44 MB that the macOS session measured), unless the pages are in the
shared region, which an application's are not. So the Windows result does not carry over by itself: on macOS the fix-up cost is
per process, and has to be measured against keeping ASLR off for the executable.
