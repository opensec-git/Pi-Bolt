<p align="center">
  <a href="https://github.com/opensec-git/Pi-Bolt">
    <img alt="Pi-Bolt logo" src="docs/images/logo.svg" width="128">
  </a>
</p>
<p align="center">
  <a href="https://github.com/opensec-git/Pi-Bolt/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/opensec-git/Pi-Bolt/ci.yml?branch=pi-bolt&event=push&style=flat-square&label=ci" /></a>
  <a href="https://github.com/opensec-git/Pi-Bolt/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/opensec-git/Pi-Bolt?style=flat-square&color=2a78d6" /></a>
  <a href="https://www.npmjs.com/package/pi-bolt"><img alt="npm" src="https://img.shields.io/npm/v/pi-bolt?style=flat-square&logo=npm&logoColor=white&color=2a78d6" /></a>
  <a href="https://github.com/earendil-works/pi/releases/tag/v1.1.0"><img alt="Pi 1.1.0" src="https://img.shields.io/badge/pi-1.1.0-f0b03a?style=flat-square" /></a>
  <a href="#requirements"><img alt="Linux x86-64" src="https://img.shields.io/badge/linux-x86--64-444?style=flat-square&logo=linux&logoColor=white" /></a>
  <a href="#requirements"><img alt="macOS Apple silicon" src="https://img.shields.io/badge/macOS-Apple%20silicon-444?style=flat-square&logo=apple&logoColor=white" /></a>
  <a href="#requirements"><img alt="Windows x64" src="https://img.shields.io/badge/windows-x64-444?style=flat-square&logo=windows&logoColor=white" /></a>
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-1baf7a?style=flat-square" /></a>
</p>

<h1 align="center">Pi-Bolt</h1>

<p align="center"><b>The <a href="https://github.com/earendil-works/pi">Pi</a> coding agent, compiled ahead of time to native code.</b><br>
One executable for Linux x86-64, macOS on Apple silicon and Windows x64. No JIT, no Node.js or Bun required.</p>

Pi-Bolt is the Pi you already use, with the same commands, sessions, settings, providers and extensions. Every function is
compiled to machine code when the executable is built, so nothing is parsed or JIT-compiled at launch. It starts two to three
times sooner than Pi on Bun, uses about a third of its CPU over a session, and handles long answers and large files ten to forty
times more cheaply.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-hero-dark.svg">
  <img alt="Pi-Bolt 0.7.3 vs Pi-Bolt 0.7.0 vs Bun 1.4.2 vs Node 24: ready to type 47 / 48 / 143 / 334 ms; CPU per session 309 / 328 / 913 / 1,312 ms; CPU while streaming 332 / 362 / 646 / 564 ms; memory after a long session 139 / 198 / 296 / 537 MB" src="docs/images/bench-hero-light.svg">
</picture>

<sub>AMD EPYC 7B13, Linux. Pi-Bolt against Pi 1.1.0 as released on stock Bun 1.4.2 and Node 24; medians of interleaved runs,
lower is better. See [Benchmarks](#benchmarks).</sub>

> [!NOTE]
> Pi-Bolt is an independent fork of [Pi](https://github.com/earendil-works/pi), not affiliated with Pi's authors or with Oven.

## Install

```bash
curl -fsSL https://pi-bolt.opensec.in/install.sh | sh
```

On Windows, in PowerShell:

```powershell
powershell -c "irm https://pi-bolt.opensec.in/install.ps1 | iex"
```

or `npm install -g pi-bolt`. Then run `pi-bolt` in your project. Your Pi configuration in `~/.pi/agent` is used as is; new to
Pi? Run `/login` to connect a provider. `pi-bolt update` installs the latest release.

The installer verifies the release signature and offers two optional extensions by OpenSec:

| Extension | What it does |
|---|---|
| [`opensec-pi-subagents`](https://www.npmjs.com/package/opensec-pi-subagents) | Specialized agents in separate sessions, parallel workflows and scheduled jobs |
| [`opensec-pi-todo`](https://www.npmjs.com/package/opensec-pi-todo) | A todo list for the model, shown as a live panel above the editor |

<details>
<summary>Manual download</summary>

Download a build from the [latest release](https://github.com/opensec-git/Pi-Bolt/releases/latest), check it against
`SHA256SUMS`, unpack it and run `./pi` (on Windows, `pi-bolt.exe`).

| Download | For |
|---|---|
| `pi-bolt-linux-x64.tar.xz` | **Linux**, CPUs with AVX2 (Intel Haswell, AMD Zen and later) |
| `pi-bolt-linux-x64-baseline.tar.xz` | Linux, any x86-64 CPU |
| `pi-bolt-darwin-arm64.tar.xz` | **macOS**, Apple silicon |
| `pi-bolt-win32-x64.zip` | **Windows** x64; compiled code on CPUs with AVX2, bytecode on others |

`-jit` builds are for heavy use of plugins loaded at run time; the runtime archive is for [compiling plugins in](docs/PLUGINS.md).

</details>

### Requirements

- **Linux** x86-64 with glibc 2.17 or later (Ubuntu 20.04+, Debian 11+, RHEL/Rocky 8+, Amazon Linux 2). musl is not supported.
- **macOS** 13 or later on Apple silicon. Install with the installer or npm ([Troubleshooting](docs/TROUBLESHOOTING.md#macos)).
- **Windows** 10 (1809) or later, or Windows 11, on x64. Install with the installer or npm ([docs/WINDOWS.md](docs/WINDOWS.md)).
- Linux and Windows on ARM64 are not available yet.

## Benchmarks

Pi-Bolt 0.7.3 and 0.7.0 against Pi 1.1.0 as released, on stock Bun 1.4.2 and on Node. Medians of fresh processes, interleaved
across the builds; lower is better.

**Linux x86-64** (AMD EPYC 7B13)

| Benchmark | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|---:|
| Writing a 200 KB file through a tool call | **0.8 s** | 0.9 s | 30.0 s | 39.4 s |
| CPU, streaming a 60,000-character answer | **4.3 s** | 4.5 s | 44.4 s | 42.3 s |
| Memory after a 4.2M-token session | **139 MB** | 198 MB | 296 MB | 537 MB |
| Memory of a session in tmux | **28 MB** | 28 MB | 87 MB | 93 MB |
| CPU, interactive session (5 prompts) | **309 ms** | 328 ms | 913 ms | 1,312 ms |
| CPU, one prompt (`pi -p`) | **85 ms** | 89 ms | 333 ms | 645 ms |
| Ready to type | **47 ms** | 48 ms | 143 ms | 334 ms |

**macOS** (Apple M5)

| Benchmark | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Pi on Bun 1.4.2 | Pi on Node 26 |
|---|---:|---:|---:|---:|
| Writing a 200 KB file through a tool call | **0.5 s** | 0.5 s | 22.1 s | 26.6 s |
| CPU, streaming a 60,000-character answer | **7.0 s** | 6.6 s | 38.4 s | 37.8 s |
| Memory after a 4.2M-token session | **39 MB** | 41 MB | 91 MB | 2,441 MB |
| Memory of a session in tmux | **25 MB** | 24 MB | 68 MB | 152 MB |
| CPU, interactive session (5 prompts) | **175 ms** | 185 ms | 555 ms | 915 ms |
| CPU, one prompt (`pi -p`) | **58 ms** | 59 ms | 236 ms | 525 ms |
| Ready to type | **46 ms** | 43 ms | 101 ms | 347 ms |

**Windows x64** (Intel Core i5-1335U laptop; Pi-Bolt 0.8.0, Pi 1.1.0 as released)

| Benchmark | Pi-Bolt 0.8.0 | Pi on Bun 1.4.2 | Pi on Node 24 |
|---|---:|---:|---:|
| Writing a 200 KB file through a tool call | **0.7 s** | 38.1 s | 26.6 s |
| CPU, streaming a 60,000-character answer | **6.5 s** | 41.1 s | 32.6 s |
| Memory after a 4.2M-token session | **159 MB** | 248 MB | 413 MB |
| Memory of a session in a terminal (ConPTY) | **32 MB** | 97 MB | 60 MB |
| CPU, interactive session (5 prompts) | **291 ms** | 805 ms | 1,125 ms |
| CPU, one prompt (`pi -p`) | **92 ms** | 329 ms | 544 ms |
| Ready to type | **92 ms** | 177 ms | 311 ms |

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-long-dark.svg">
  <img alt="Long answers and large files, Pi-Bolt 0.7.3 vs Pi-Bolt 0.7.0 vs Bun 1.4.2 vs Node 24: CPU streaming a 20,000-character answer 1.1 / 1.2 / 10.7 / 8.0 s; a 60,000-character answer 4.3 / 4.5 / 44.4 / 42.3 s; share of a core while streaming 9 / 9 / 88 / 84%; writing a 200 KB file through a tool call 0.8 / 0.9 / 30.0 / 39.4 s" src="docs/images/bench-long-light.svg">
</picture>

<details>
<summary>More charts: time, CPU and memory on Linux</summary>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-speed-dark.svg">
  <img alt="Time on Linux, Pi-Bolt 0.7.3 vs 0.7.0 vs Bun 1.4.2 vs Node 24: ready to type 47 / 48 / 143 / 334 ms; pi --version 13 / 14 / 89 / 255 ms; one prompt 83 / 87 / 193 / 454 ms; time per prompt in a 4.2M-token session 565 / 576 / 777 / 961 ms" src="docs/images/bench-speed-light.svg">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-cpu-dark.svg">
  <img alt="CPU time on Linux, Pi-Bolt 0.7.3 vs 0.7.0 vs Bun 1.4.2 vs Node 24: interactive session 309 / 328 / 913 / 1,312 ms; one prompt 85 / 89 / 333 / 645 ms; pi --version 14 / 15 / 150 / 322 ms; per prompt in a 4.2M-token session 301 / 319 / 548 / 794 ms" src="docs/images/bench-cpu-light.svg">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-memory-dark.svg">
  <img alt="Memory and streaming on Linux, Pi-Bolt 0.7.3 vs 0.7.0 vs Bun 1.4.2 vs Node 24: peak memory 154 / 170 / 218 / 213 MB; own memory in tmux 28 / 28 / 87 / 93 MB; own memory after a 4.2M-token session 139 / 198 / 296 / 537 MB; CPU while replies stream 332 / 362 / 646 / 564 ms" src="docs/images/bench-memory-light.svg">
</picture>

</details>

Method, raw data and the macOS and Windows charts: [docs/BENCHMARKS.md](docs/BENCHMARKS.md). (Memory on Windows is the private
working set.)

## Plugins

Pi extensions work unchanged. The default build has no JIT, so an extension loaded at run time is interpreted; compile the ones
you use every day into the executable, where they run as machine code:

```bash
scripts/build-pi.sh --plugins my-plugins/plugins.ts --out out/pi-bolt-plugins
```

See [docs/PLUGINS.md](docs/PLUGINS.md).

## Herdr

In a [Herdr](https://herdr.dev) pane, Pi-Bolt shows up as `pi-bolt`: idle, working (also while background subagents run), or
blocked when an extension waits for an answer during a run. It also hands Herdr the command that reopens its session, so a
Herdr restart brings the same conversation back in the same pane. There is nothing to install. Pi-Bolt does not load the file
`herdr integration install pi` writes (stock Pi still uses it), and `"-builtin:herdr"` in the `extensions` setting turns this
off. Restoring a session needs Herdr 0.9.2 or later, with `pi-bolt` on the Herdr server's `PATH`: start Herdr from a terminal,
not with `brew services`.

To start other agents in Herdr panes from a Pi-Bolt session, add the third-party
[pi-herdr](https://www.npmjs.com/package/@andrewjacop/pi-herdr) (MIT): `pi-bolt install npm:@andrewjacop/pi-herdr`. Herdr
starts Pi agents as `pi`, so the Pi agents it spawns run stock Pi, which must be installed (Claude Code, Codex and the other
agents it supports need only their own CLIs). Pi-Bolt turns off pi-herdr's own report of the Pi-Bolt pane
(`PI_HERDR_NO_SELF_REPORT=1`), so the pane keeps showing `pi-bolt` and can still be restored.

## How it works

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/how-it-works-dark.svg">
  <img alt="Build time: Pi is bundled and compiled to bytecode by bun build, then every function is compiled to machine code by the AOT compiler, guided by a training profile. The result is one executable holding the Bun runtime, a prebuilt heap and the machine code. At launch, pi maps the code and heap from the file and runs main()." src="docs/images/how-it-works-light.svg">
</picture>

1. **Bun bundles Pi** and compiles it to JavaScriptCore bytecode.
2. **An ahead-of-time compiler** turns every function into optimized x86-64 or ARM64 machine code, through B3, the back end of
   JavaScriptCore's top-tier JIT.
3. **A prebuilt heap** holds Pi's modules already loaded, and is mapped from the executable at launch.
4. **A training profile** orders the code so that startup touches as few pages as possible.

The compiler builds on [oven-sh/WebKit#743](https://github.com/oven-sh/WebKit/pull/743). Pi-Bolt ports it to x86-64 and macOS
and adds its own code generation and runtime work ([docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)). `packages/` is Pi
[v1.1.0](https://github.com/earendil-works/pi/releases/tag/v1.1.0) with Pi-Bolt's changes on top; the engine changes are in
`patches/`.

## Documentation

| | |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | What the executable contains, how it is built, what happens at launch |
| [Building](docs/BUILDING.md) | Building the runtime and Pi from source, training profiles, tests |
| [Benchmarks](docs/BENCHMARKS.md) | Method, results and raw data |
| [Plugins](docs/PLUGINS.md) | Porting Pi extensions and writing them for AOT |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Diagnostics, environment variables, known limitations |
| [Releasing](docs/RELEASING.md) | CI, releases, signing |

## Development

```bash
git clone https://github.com/opensec-git/Pi-Bolt.git && cd Pi-Bolt
scripts/fetch-runtime.sh            # the released Pi-Bolt runtime (or build it: docs/BUILDING.md)
scripts/prepare-pi.sh               # builds the Pi in this repository
scripts/build-pi.sh                 # out/pi-bolt/pi
tests/aot/run.sh                    # engine tests
```

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security issues privately, as described in [SECURITY.md](SECURITY.md).

## License

MIT. Release executables include Bun (MIT), JavaScriptCore (LGPL-2.0 and BSD), ICU (Unicode License) and Pi (MIT); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<p align="center">
  Built on <a href="https://github.com/earendil-works/pi">Pi</a> by Mario Zechner and contributors,
  and <a href="https://github.com/oven-sh/bun">Bun</a> by Oven.
</p>
