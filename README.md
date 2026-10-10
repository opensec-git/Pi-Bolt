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
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-1baf7a?style=flat-square" /></a>
</p>

<h1 align="center">Pi-Bolt</h1>

<p align="center"><b>The <a href="https://github.com/earendil-works/pi">Pi</a> coding agent, compiled ahead of time to native code.</b><br>
One executable for Linux x86-64 and macOS on Apple silicon. No JIT, nothing to install alongside it.</p>

Pi-Bolt runs the Pi you already use: its commands, keys, sessions, settings, extensions and providers. What changes is how it
runs. Every function is compiled to machine code when the executable is built, and stored in it with a prebuilt JavaScript heap,
so at launch nothing is parsed, interpreted or JIT-compiled. It starts three times sooner than Pi on Bun, uses about a third of
its CPU over a session, and writes large files and streams long answers ten to forty times cheaper.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-hero-dark.svg">
  <img alt="Pi-Bolt 0.7.3 vs Pi-Bolt 0.7.0 vs Bun 1.4.2 vs Node 24: ready to type 47 / 48 / 143 / 334 ms; CPU per session 309 / 328 / 913 / 1,312 ms; CPU while streaming 332 / 362 / 646 / 564 ms; memory after a long session 139 / 198 / 296 / 537 MB" src="docs/images/bench-hero-light.svg">
</picture>

<sub>One Linux server (AMD EPYC 7B13): Pi-Bolt 0.7.3 and 0.7.0 against Pi 1.1.0 as released, on stock Bun 1.4.2 and on Node 24.
Medians of interleaved runs; lower is better. Times differ on other machines; the ratios carry over. More under
[Benchmarks](#benchmarks).</sub>

> [!NOTE]
> Pi-Bolt is an independent fork of [Pi](https://github.com/earendil-works/pi), not affiliated with Pi's authors or with Oven.

**Contents:** [Getting started](#getting-started) · [Benchmarks](#benchmarks) · [Plugins](#plugins) ·
[How it works](#how-it-works) · [The fork](#the-fork) · [Documentation](#documentation) · [Development](#development)

## Getting started

```bash
curl -fsSL https://pi-bolt.opensec.in/install.sh | sh
```

It installs Pi-Bolt to `~/.pi-bolt`, links the `pi-bolt` command into `~/.local/bin`, and offers OpenSec's two optional
[extensions](#extensions). Run it again to update, reinstall or uninstall.

Or with a package manager:

```bash
npm install -g pi-bolt      # or: bun add -g pi-bolt
```

Then start it in your project:

```bash
cd /path/to/project
pi-bolt
```

Pi-Bolt shares Pi's configuration in `~/.pi/agent`, so your providers, settings and sessions are already there. New to Pi? Run
`/login` inside it to connect a provider. Everything in [Pi's documentation](https://github.com/earendil-works/pi/tree/main/packages/coding-agent/docs)
applies. `pi-bolt update` installs the latest release; to use it as your `pi`, add `alias pi=pi-bolt` to your
shell profile.

Pi-Bolt is self-contained: it needs neither Node.js nor Bun, and installs extensions with npm where it is present, otherwise with
the package manager built into it.

<details>
<summary>Manual download</summary>

Download a build from the [latest release](https://github.com/opensec-git/Pi-Bolt/releases/latest), check it against
`SHA256SUMS`, unpack it, and run `./pi` in the unpacked folder.

| Download | For |
|---|---|
| `pi-bolt-linux-x64.tar.xz` | **Linux.** CPUs with AVX2: Intel Haswell (2013) and later, AMD Zen and later |
| `pi-bolt-linux-x64-baseline.tar.xz` | Linux, any x86-64 CPU |
| `pi-bolt-darwin-arm64.tar.xz` | **macOS.** Macs with Apple silicon (M1 and later) |

Each build also comes as a `.tar.gz`, for systems without `xz`. The release also has builds with the JIT on (`-jit`), for
heavy use of plugins loaded at run time, and the Pi-Bolt runtime, to [compile plugins in](docs/PLUGINS.md). Most people need
neither.

</details>

### Extensions

Pi extensions and Pi packages work as they do in Pi: `pi-bolt install npm:<package>` or `pi-bolt install git:<repository>`.
The installer offers two that OpenSec maintains for Pi-Bolt, prebuilt for Bun so that they load without being transpiled
(Apache-2.0):

| Package | What it does |
|---|---|
| [`opensec-pi-subagents`](https://www.npmjs.com/package/opensec-pi-subagents) | Specialized agents in separate sessions that inherit the parent's model and thinking level; parallel workflows and scheduled jobs |
| [`opensec-pi-todo`](https://www.npmjs.com/package/opensec-pi-todo) | A todo list for the model, shown as a live panel above the editor |

Install them with `pi-bolt install npm:opensec-pi-subagents` and `pi-bolt install npm:opensec-pi-todo`. For scripted
installs, `PIBOLT_EXTENSIONS=yes` (or `no`) answers the installer's question in advance.

### Pi and Pi-Bolt side by side

Pi-Bolt uses Pi's agent directory (`~/.pi/agent`): logins, models, sessions and settings are shared, and so are installed
packages. Where both are used and should load different packages, such as `@juicesharp/rpiv-todo` in Pi and its fork
`opensec-pi-todo` in Pi-Bolt (both provide a `todo` tool, so they cannot load together), give Pi-Bolt a package list of its
own in `~/.pi/agent/settings.json`:

```json
{
  "packages": ["npm:@juicesharp/rpiv-todo"],
  "piBolt": { "packages": ["npm:opensec-pi-todo"] }
}
```

Pi-Bolt then reads and changes `piBolt.packages` (`pi-bolt install`, `remove`, `config`), and Pi keeps `packages`. Without
`piBolt`, both use `packages`. The installed files are shared: `pi-bolt remove` keeps those of a package Pi's list still uses, and if
Pi removes one that Pi-Bolt's list uses, Pi-Bolt installs it again when it next starts, as it does any listed package that is
missing. To keep Pi-Bolt's whole setup apart instead, point it at a directory of its own with
`PI_CODING_AGENT_DIR`.

### Requirements

- **Linux** on x86-64 with glibc 2.17 or later: Ubuntu 20.04+, Debian 11+, Rocky Linux 8+, CentOS 7, Amazon Linux 2 and
  others. Alpine and other musl-based systems are not supported.
- **macOS** 13 (Ventura) or later on Apple silicon (M1 or later). Install with the installer or npm: a build downloaded with a
  browser is quarantined, and macOS refuses to run it ([Troubleshooting](docs/TROUBLESHOOTING.md#macos)).
- Windows and Linux on ARM64 are not available yet.

## Benchmarks

Pi run four ways: compiled by Pi-Bolt 0.7.3, by Pi-Bolt 0.7.0, and Pi 1.1.0 as released on stock
Bun 1.4.2 and from its npm package on Node. Medians of fresh processes, interleaved across the builds; lower is better. Each
table comes from one machine, so the milliseconds will differ on yours; the comparison is what carries over.

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
| `pi --version` | **13 ms** | 14 ms | 89 ms | 255 ms |

**macOS on Apple silicon** (M5 MacBook Air)

| Benchmark | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Pi on Bun 1.4.2 | Pi on Node 26 |
|---|---:|---:|---:|---:|
| Writing a 200 KB file through a tool call | **0.5 s** | 0.5 s | 22.1 s | 26.6 s |
| CPU, streaming a 60,000-character answer | **7.0 s** | 6.6 s | 38.4 s | 37.8 s |
| Memory after a 4.2M-token session | **39 MB** | 41 MB | 91 MB | 2,441 MB |
| Memory of a session in tmux | **25 MB** | 24 MB | 68 MB | 152 MB |
| CPU, interactive session (5 prompts) | **175 ms** | 185 ms | 555 ms | 915 ms |
| CPU, one prompt (`pi -p`) | **58 ms** | 59 ms | 236 ms | 525 ms |
| Ready to type | **46 ms** | 43 ms | 101 ms | 347 ms |

Every row uses a local model server that streams a scripted conversation, so they measure Pi and its runtime, not a network or
a model; with a hosted model Pi-Bolt spends less than half the CPU of Bun and Node for the same wait
([With a real model](docs/BENCHMARKS.md#with-a-real-model)). On the Mac, 0.7.3 and 0.7.0 differ within the noise of a fanless
laptop, except where 0.7.3 changed something: a start with Pi-Bolt not in memory (after a restart or an update) takes 64 ms
instead of 177, and long sessions and commands with large output cost less ([0.7.3 against 0.7.2](docs/BENCHMARKS.md#073-against-072)).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-long-dark.svg">
  <img alt="Long answers and large files, Pi-Bolt 0.7.3 vs Pi-Bolt 0.7.0 vs Bun 1.4.2 vs Node 24: CPU streaming a 20,000-character answer 1.1 / 1.2 / 10.7 / 8.0 s; a 60,000-character answer 4.3 / 4.5 / 44.4 / 42.3 s; share of a core while streaming 9 / 9 / 88 / 84%; writing a 200 KB file through a tool call 0.8 / 0.9 / 30.0 / 39.4 s" src="docs/images/bench-long-light.svg">
</picture>

Long answers and large files are where the gap is widest. Pi as released renders a whole answer again as each few words arrive,
and parses a tool call's arguments again as each few characters arrive, so the cost grows with the length. Pi-Bolt redraws
only the end of an answer, re-parses arguments only as they grow, and in fullscreen mode scrolls the rows that only moved: it
writes a seventh as much to the terminal, and never pauses drawing for more than 0.1 s while a 200 KB file is written.

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

[docs/BENCHMARKS.md](docs/BENCHMARKS.md) has every figure, the method, the raw data and the macOS charts. To reproduce
everything, run [`bench/run-suite.sh`](bench/run-suite.sh).

## Plugins

Pi extensions work in Pi-Bolt unchanged: in `~/.pi/agent/extensions`, in a project's `.pi/extensions`, or as Pi packages. The
default build has no JIT, so a plugin loaded at run time is interpreted. For the ones you use every day, compile them into the
executable, where they run as machine code:

```bash
scripts/build-pi.sh --plugins my-plugins/plugins.ts --out out/pi-bolt-plugins
```

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/bench-plugins-dark.svg">
  <img alt="A plugin's hot loop: 55 ms compiled in, 1,315 ms loaded at run time on the JIT-off build, 48 ms on the JIT-on build, 45 ms on Bun" src="docs/images/bench-plugins-light.svg">
</picture>

[docs/PLUGINS.md](docs/PLUGINS.md) covers porting, compatibility, and writing plugin code the compiler handles well.

## How it works

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/how-it-works-dark.svg">
  <img alt="Build time: Pi is bundled and compiled to bytecode by bun build, then every function is compiled to x86-64 machine code by the AOT compiler, guided by a training profile. The result is one executable holding the Bun runtime, a prebuilt heap and the machine code. At launch, pi maps the code and heap from the file and runs main()." src="docs/images/how-it-works-light.svg">
</picture>

1. **Bun bundles Pi** and compiles it to JavaScriptCore bytecode.
2. **The ahead-of-time compiler** turns every function into optimized machine code (x86-64 or ARM64), through B3, the back end
   of JavaScriptCore's top-tier JIT. It infers types from the program, guards its assumptions, and keeps generic paths for what
   it cannot prove.
3. **The prebuilt heap** is Pi's modules, already loaded and evaluated, stored in the executable and mapped at launch.
4. **A training profile**, recorded once per Pi version, orders the code so that startup touches as few pages as possible.

The compiler comes from [oven-sh/WebKit#743](https://github.com/oven-sh/WebKit/pull/743), which targets ARM64. Pi-Bolt ports it
to x86-64, brings it to macOS, and adds its own code-generation and runtime work. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## The fork

`packages/` is Pi at [v1.1.0](https://github.com/earendil-works/pi/releases/tag/v1.1.0), with Pi-Bolt's changes on top as
separate commits: how the terminal is drawn while answers stream, less work at startup, installing packages without npm, and
messages that name the `pi-bolt` command. The commands, settings, sessions and extension API are Pi's. Pi-Bolt adds:

| | |
|---|---|
| `patches/` | its changes to WebKit (JavaScriptCore's ahead-of-time compiler) and Bun, against the commits in `sources.json` |
| `scripts/` | `prepare-pi.sh`, `build-runtime.sh`, `build-pi.sh` and the rest, next to Pi's own scripts |
| `profiles/` | training profiles per Pi version |
| `bench/`, `tests/aot/` | benchmarks with their published results, and engine tests |
| `docs/`, `examples/`, `install.sh` | documentation, an example plugin, the installer |

It replaces Pi's README, CONTRIBUTING and SECURITY files with its own, and leaves out Pi's GitHub automation. For Pi itself,
see [earendil-works/pi](https://github.com/earendil-works/pi).

## Documentation

| | |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | What the executable contains, how it is built, what happens at launch |
| [Building](docs/BUILDING.md) | Building the runtime and Pi from source, training profiles, tests |
| [Benchmarks](docs/BENCHMARKS.md) | Method, results, raw data, and questions |
| [Plugins](docs/PLUGINS.md) | Porting Pi extensions, compatibility, writing them for AOT |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Diagnostics, environment variables, known limitations |
| [Releasing](docs/RELEASING.md) | CI, the release and publish workflows, signing, following Pi's releases |

## Development

```bash
git clone https://github.com/opensec-git/Pi-Bolt.git
cd Pi-Bolt
scripts/fetch-sources.sh            # WebKit and Bun at the pinned commits, with Pi-Bolt's patches
scripts/toolchain/make-sysroot.sh   # glibc 2.17 sysroot with static ICU, for portable executables (Linux only)
scripts/build-runtime.sh            # the Pi-Bolt Bun runtime
scripts/prepare-pi.sh               # builds the Pi in this repository (1.1.0)
scripts/build-pi.sh                 # out/pi-bolt/pi
```

`scripts/fetch-runtime.sh` instead of the first three commands downloads the released runtime; building Pi then takes about a
minute. Before submitting changes, run the engine tests and the end-to-end checks:

```bash
tests/aot/run.sh
python3 bench/e2e_tools.py --reference bun=out/pi-stable/pi --build pi-bolt=out/pi-bolt/pi
python3 bench/ui_check.py --project . --build pi-bolt=out/pi-bolt/pi
```

[docs/BUILDING.md](docs/BUILDING.md) has the requirements, every option, and how to regenerate the patches. To contribute, see
[CONTRIBUTING.md](CONTRIBUTING.md); report security issues privately, as described in [SECURITY.md](SECURITY.md).

## License

MIT. The release executables include third-party software under its own licenses: Bun (MIT), JavaScriptCore (LGPL-2.0 and
BSD), ICU (Unicode License) and Pi (MIT). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

<p align="center">
  Built on <a href="https://github.com/earendil-works/pi">Pi</a> by Mario Zechner and contributors,
  and <a href="https://github.com/oven-sh/bun">Bun</a> by Oven.
</p>
