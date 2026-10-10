# Building Pi-Bolt

Pi-Bolt is built in two stages:

1. **The runtime**: Bun on a patched JavaScriptCore with the ahead-of-time compiler. Build it once, or download it from a release.
2. **Pi**: compiled with that runtime into a single executable. This takes about a minute, and it is where plugins are added.

- [Quick start: release runtime](#quick-start-release-runtime)
- [Building the runtime](#building-the-runtime)
- [Building Pi](#building-pi)
- [Training profiles](#training-profiles)
- [Packaging a release](#packaging-a-release)
- [Tests](#tests)
- [Where things go](#where-things-go)

## Quick start: release runtime

Needs git, Node.js 22.19 or later with npm (to build Pi), Python 3, and rsync (for plugins).

```bash
git clone https://github.com/opensec-git/Pi-Bolt.git && cd Pi-Bolt
scripts/fetch-runtime.sh     # the Pi-Bolt runtime from the latest release -> .work/runtime/bun
scripts/prepare-pi.sh        # builds the Pi in this repository (Pi-Bolt is a fork of Pi)
scripts/build-pi.sh          # -> out/pi-bolt/pi
out/pi-bolt/pi --version
```

Apart from downloading the runtime and npm packages, the build is offline: Pi's model catalog
(`packages/ai/src/providers/data`) is in the repository. `scripts/prepare-pi.sh --refresh-models` fetches it again from the
model providers; every provider has to answer, so it is for updating the catalog (commit what changes), not for building.
When a step fails, the script prints the end of its output, and the whole of it is in `.work/prepare-pi.log`.

## Building the runtime

### Requirements

| Tool | Version | Notes |
|---|---|---|
| Linux x86-64 | | 32 GB of RAM or more; about 40 GB of disk |
| or macOS on Apple silicon | 13 or later | Xcode (its SDK); 16 GB of RAM is enough with ThinLTO; about 40 GB of disk |
| clang / LLVM | 23.1 | the version Bun's build pins (`scripts/build/ci-images/spec.ts` in Bun) |
| CMake | 3.30 | |
| Ninja | | |
| Rust | stable | `cargo` on `PATH` |
| Bun | 1.4.2 | runs Bun's build script |
| Docker | | Linux only: for the portable sysroot (`make-sysroot.sh`) |

### Steps

```bash
scripts/fetch-sources.sh            # oven-sh/WebKit and oven-sh/bun at the pinned commits, with patches/ applied
scripts/toolchain/make-sysroot.sh   # Ubuntu 20.04 sysroot + gcc-13 libstdc++ + static ICU 78.3 -> .toolchain/sysroot-glibc
scripts/build-runtime.sh            # release build with LTO -> .work/runtime/bun
```

`fetch-sources.sh` reads [`sources.json`](../sources.json):

- the upstream repositories and commits: WebKit from [oven-sh/WebKit#743](https://github.com/oven-sh/WebKit/pull/743), and Bun
  from `main`;
- Pi-Bolt's changes to each, one patch apiece: [`patches/webkit.patch`](../patches/webkit.patch) and
  [`patches/bun.patch`](../patches/bun.patch), applied with `git am`.

The sysroot is what makes the executables portable. Linked against it, they need only glibc 2.17, and they carry their own
ICU, like official Bun builds.

`scripts/build-runtime.sh --native` skips the sysroot and links against the host's libc and ICU. That is faster to set up, but
the result runs only on systems like the build machine.

On macOS there is no sysroot: the runtime is built against Xcode's SDK for macOS 13 and later (Bun's own floor), with
`-mcpu=apple-m1`, and uses the system's ICU. LLVM 23 from Homebrew (`brew install llvm cmake ninja bash xz`), Rust and Bun 1.4.2 are
what it needs besides Xcode. The scripts need bash 4.4 or later (macOS's own is 3.2) and Python 3; `build-pi.sh` also needs the
Command Line Tools (`xcrun clang`) for the launcher. On an M5 MacBook Air (10 cores, 16 GB) the first build takes about 40 minutes and later ones a few;
`scripts/build-runtime.sh --lto off -j8` builds without link-time optimization, for working on the engine.

```bash
scripts/fetch-sources.sh
scripts/build-runtime.sh            # -> .work/runtime/bun (ThinLTO, as released)
```

### Working on the engine

The checkouts in `.work/webkit` and `.work/bun` are ordinary git repositories, each with Pi-Bolt's changes as one commit on top
of the pinned upstream commit. Make a change part of that commit (`git commit --amend`), rebuild with `scripts/build-runtime.sh`,
then regenerate the patch:

```bash
git -C .work/webkit format-patch --no-numbered --zero-commit --stdout <pinned-commit>..HEAD > patches/webkit.patch
git -C .work/bun format-patch --no-numbered --zero-commit --stdout <pinned-commit>..HEAD > patches/bun.patch
```

## Building Pi

```bash
scripts/build-pi.sh [options]
```

| Option | Default | |
|---|---|---|
| `--pi DIR` | this repository | A built Pi tree (`scripts/prepare-pi.sh`). |
| `--out DIR` | `out/pi-bolt` | Where the executable and its asset files go. |
| `--jit on\|off` | `off` | `on` also JIT-compiles code loaded at run time, such as run-time plugins. |
| `--cpu native\|baseline` | `native` | `native`: code for the build machine's instruction set (AVX2 class); on a CPU without it, the executable runs from bytecode. `baseline`: any x86-64 CPU. On ARM64 there is one build, for every Apple silicon CPU. |
| `--profile DIR` | `profiles/pi-<version>` | Training profile ([below](#training-profiles)). |
| `--plugins FILE` | | Compile plugins in ([PLUGINS.md](PLUGINS.md)). |
| `--plugin-worker PATH` | | A worker script a plugin starts. Repeatable. |
| `--keep-bytecode` | | Keep bytecode in the prebuilt heap. By default it is left out, which saves memory and lets the compiler inline across Pi's functions. |
| `--stable` | | Build with a stock Bun (`PIBOLT_STABLE_BUN`, default `bun`) instead, as a comparison. |

The executable has to stay next to the files `build-pi.sh` puts beside it: Pi's themes, assets, HTML export template, the
photon WASM module and the native terminal helpers. On macOS `pi` is a launcher and the executable is `pi-bin` beside it, with
`pi-spawn`, which starts the programs Pi starts ([ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port)); either can be run.

The script checks the result: the executable must report `image registered: true`, meaning it runs its compiled code.

### Building another Pi version

```bash
scripts/prepare-pi.sh --tag v1.0.1 --dir .work/pi-1.0.1   # clones that release from upstream
scripts/train-profile.sh --pi .work/pi-1.0.1        # records profiles/pi-1.0.1
scripts/build-pi.sh --pi .work/pi-1.0.1 --out out/pi-bolt-1.0.1
```

A build without a profile works, with somewhat slower startup. `build-pi.sh` warns when there is none.

## Training profiles

A profile ([`profiles/pi-1.1.0`](../profiles/pi-1.1.0)) has two files, recorded by running a plain bytecode build of Pi through
two scripted interactive sessions (`scripts/lib/train_session.py`). The first is a plain start and a few plain turns, and gives
the order. The second is there for the regular expressions: an answer that uses every kind of Markdown and a code block in
every language Pi highlights (`scripts/lib/training.md`), tool calls whose results the TUI renders, and the editor's
completions. A regular expression that is not recorded is not compiled into the executable, and runs in the interpreter (ten
times slower or more) on the default build: when Pi starts to highlight another language or to build new patterns, add what
exercises them to `training.md` and record again.

| File | What it holds | What it is used for |
|---|---|---|
| `bytecode.order` | The functions that ran, in the order they first ran | Laying out code and heap so that startup touches as few pages as possible |
| `regexps.txt` | Regular expressions the program built from strings at run time | Compiling them ahead of time, like regular-expression literals |

```bash
scripts/train-profile.sh [--pi DIR] [--plugins FILE] [--out DIR]
```

### The runtime's order file (macOS)

On macOS `build-runtime.sh` also lays out the runtime's own code by what Pi runs: the functions a Pi session enters first and
most, placed together at the front of the code with a linker order file, so that starting and running Pi touches fewer pages of
the executable (it maps less of it from disk on a cold start, and keeps less of it resident). The file is made after the build,
from [`profiles/runtime-darwin-arm64.hints`](../profiles/runtime-darwin-arm64.hints) (the functions Pi entered, in first-entry
order) and then Bun's own workloads (`.work/bun/scripts/orderfile`), and the runtime is linked again with it. The hints are
names, so they hold from one build of the runtime to the next; record them again when what Pi runs has changed much:

```bash
scripts/train-runtime-hints.sh [--pi BUILD]   # traces sessions of out/pi-bolt (bench/orderfile_session.py) -> profiles/runtime-darwin-arm64.hints
```

## Packaging a release

```bash
scripts/package-release.sh [--pi DIR] [--no-build]
```

This builds the Pi targets of the machine it runs on (on Linux `linux-x64`, `linux-x64-baseline` and `linux-x64-jit`; on macOS
`darwin-arm64` and `darwin-arm64-jit`) and checks that each uses its compiled code. It writes them as `.tar.xz` and `.tar.gz`, the
runtime and `SHA256SUMS` to `dist/<VERSION>/`. A release puts the archives of both platforms together, with one `SHA256SUMS`. Each archive includes the license
notices and a `pi-bolt.txt` naming the build. Publishing a release, by the release workflow or by hand, is described in
[RELEASING.md](RELEASING.md).

## Tests

| Command | What it checks |
|---|---|
| `tests/aot/run.sh` | Engine correctness. Programs that stress values held in registers across slow paths, `Map`/`Set` fast paths, realms and workers, inlined helpers, methods, callbacks, narrowed variables, functions the compiler declines, and every built-in with a fast path on edge-case inputs (`builtins.mjs`, written by `gen-builtins.py`) are compiled ahead of time three ways: JIT on, JIT off, and JIT off with every operation compiled compactly (as outside loops). Their output must equal stock Bun's. |
| `tests/aot/fuzz/run.sh --count 2000` | Differential fuzzing: random programs aimed at loops and their guards, compiled ahead of time (`--mode jit-off`, `jit-on`, `baseline` or `compact`), must print what the same bundle prints as bytecode. 0.5.0 was released after 32,000 programs (four rounds; the last 10,000, on its compiler, all passed). |
| `tests/runtime/run.sh` | The runtime itself: `Error.captureStackTrace()` on objects that are not Errors, and fetch's keep-alive pool (how long an idle connection waits, the server's `Keep-Alive` timeout, a connection that went dead while idle). Takes about a minute: it waits out the timeouts. |
| `tests/pi/run.sh` | Pi itself, under conditions that once crashed it: errors formatted at the end of a garbage collection. |
| `python3 bench/e2e_tools.py --reference bun=out/pi-stable/pi --build pi-bolt=out/pi-bolt/pi` | Every Pi tool (`ls`, `find`, `grep`, `write`, `edit`, `bash`, `read`) driven by a scripted model. The transcript and resulting files must be byte-identical to the reference build's. |
| `python3 bench/ui_check.py --project . --build pi-bolt=out/pi-bolt/pi` | The TUI on a pseudo-terminal: trust prompt, `/` commands, `/hotkeys`, `/session`, `!` bash, a model turn with tool calls, `/model`, `/quit`. |
| `python3 bench/stress.py --reference bun=out/pi-stable/pi --build pi-bolt=out/pi-bolt/pi --concurrency 32 --soak 600` | Load and failures: 32 Pi processes at once doing large tool work (tool results and requests byte-identical to the reference's), streamed and cut-off answers, HTTP errors, signals, and 600 prompts in one RPC session with a flat memory floor. `--api anthropic` and `--api responses` run it through Pi's Anthropic and OpenAI Responses clients instead of chat completions. |
| `python3 bench/e2e_screen.py --reference bun=out/pi-stable/pi --build pi-bolt=out/pi-bolt/pi` | What the terminal shows: a long answer with every kind of Markdown and code block streams in, and the whole scrollback, text and colors, must be the reference's. |
| `python3 bench/e2e_fullscreen.py --build pi-bolt=out/pi-bolt/pi` | The fullscreen TUI's scrolling of rows that only moved: after a long answer, paging and a resize, each screen (text and colors) must be what it is when every row is drawn, in tmux and in zmx. `--docker IMAGE` runs Pi and the terminals in a container. |
| `python3 bench/pauses.py --build pi-bolt=out/pi-bolt/pi` | Large files written through a tool call in the TUI: the longest pause in drawing must not grow with the file. |
| `python3 bench/long_answer.py --build pi-bolt=out/pi-bolt/pi` | The CPU it takes to stream answers of 5,000 to 60,000 characters: the share of a core must not grow with the length. |
| `tests/compat/run.sh --builds DIR` | Linux only. Other systems and CPUs, without root: the three builds in the userlands of CentOS 7 (glibc 2.17), Debian 9 and Amazon Linux 2 (bubblewrap), and on emulated Intel Haswell, Skylake, Sandy Bridge and Nehalem (qemu-user). |
| `python3 bench/tmux_check.py --build pi-bolt=out/pi-bolt/pi` | Pi in a real tmux pane: keystroke latency, paste, streaming, resize, Escape to abort, idle CPU, memory. |

`out/pi-stable/pi` is the stock-Bun comparison build: `scripts/build-pi.sh --stable --out out/pi-stable`.

The engine itself was also checked against JavaScriptCore's own test suite, `JSTests/stress`. Each test runs with and without
ahead-of-time compilation, and the outputs are compared. 4,780 of the 4,786 tests that run behave the same. The 6 that differ
inspect engine internals that do not exist without a JIT (tier-up and reoptimization counters, sampling-profiler frames), or run
out of memory or stack at limits that differ by design.

## Where things go

| Path | Contents | In git |
|---|---|---|
| `.work/` (`PIBOLT_WORK`) | sources, the runtime, Pi checkouts, test output | no |
| `.toolchain/` | the sysroot | no |
| `out/` | Pi builds | no |
| `dist/` | release archives | no |
