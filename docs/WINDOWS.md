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

The writer refuses a heap that points into any other module (a system DLL moves at every boot).

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

- **CFG**: every C and C++ object with `/guard:cf` (and Rust with `-C control-flow-guard`), the link with `/guard:cf`. JIT
  memory is allocated executable at run time, so its targets are valid by default. The code image is part of the image: any of its
  entry points that instrumented C++ calls through a pointer must be in the image's guard function table, which the PE writer
  can extend. To be verified by running with CFG on.
- **CET shadow stacks**: this CPU supports them, so they can be tested here. JavaScriptCore discards frames when it unwinds to a
  JavaScript `catch` handler or to its entry frame, without a matching return; whether that is compatible with a shadow stack has
  to be established by running Bun's and Pi's tests on a `/CETCOMPAT` build. If it is not, the finding and its numbers come back
  to the owner before anything is decided.
- **Unwind information** for the code image (`RUNTIME_FUNCTION` entries, in `.pdata` or registered with `RtlAddFunctionTable`),
  so that stack walks and crash reports go through compiled frames.

## What would carry over to macOS

The same idea, applied to Mach-O, would remove the ASLR exception there: the region as a section of the executable (a
zero-fill section), the heap's pointers as rebase opcodes of the chained fixups, applied by dyld. The difference is that dyld
applies rebases in the process (private dirty pages, the 44 MB that the macOS session measured), unless the pages are in the
shared region, which an application's are not. So the Windows result does not carry over by itself: on macOS the fix-up cost is
per process, and has to be measured against keeping ASLR off for the executable.
