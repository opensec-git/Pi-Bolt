<p align="center">
  <a href="https://github.com/opensec-git/Pi-Bolt">
    <img alt="Pi-Bolt logo" src="docs/images/logo.svg" width="128">
  </a>
</p>

<h1 align="center">Pi-Bolt</h1>

<p align="center">
  <b>The <a href="https://github.com/earendil-works/pi">Pi</a> coding agent, compiled ahead of time to native code.</b><br>
  One executable for Linux x86-64 and ARM64, Android (Termux), macOS on Apple silicon and Windows x64.<br>
  No JIT, no Node.js or Bun required.
</p>

<p align="center">
  <a href="https://github.com/opensec-git/Pi-Bolt/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/opensec-git/Pi-Bolt/ci.yml?branch=pi-bolt&event=push&style=flat-square&label=ci&logo=githubactions&logoColor=white" /></a>
  <a href="https://github.com/opensec-git/Pi-Bolt/releases/latest"><img alt="Latest release" src="https://img.shields.io/npm/v/pi-bolt?style=flat-square&label=release&color=2a78d6" /></a>
  <a href="https://www.npmjs.com/package/pi-bolt"><img alt="npm downloads" src="https://img.shields.io/npm/dm/pi-bolt?style=flat-square&logo=npm&logoColor=white&label=downloads&color=2a78d6" /></a>
  <a href="https://github.com/opensec-git/Pi-Bolt/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/opensec-git/Pi-Bolt?style=flat-square&logo=github&logoColor=white&color=f0b03a" /></a>
  <a href="sources.json"><img alt="The Pi release Pi-Bolt is built on" src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fraw.githubusercontent.com%2Fopensec-git%2FPi-Bolt%2Fpi-bolt%2Fsources.json&query=%24.pi.tag&label=built%20on%20pi&color=f0b03a&style=flat-square" /></a>
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-1baf7a?style=flat-square" /></a>
  <br>
  <a href="#requirements"><img alt="Linux x86-64" src="https://img.shields.io/badge/Linux-x86--64-FCC624?style=flat-square&logo=linux&logoColor=white" /></a>
  <a href="#requirements"><img alt="Linux ARM64" src="https://img.shields.io/badge/Linux-ARM64-FCC624?style=flat-square&logo=linux&logoColor=white" /></a>
  <a href="#requirements"><img alt="macOS on Apple silicon" src="https://img.shields.io/badge/macOS-Apple%20silicon-000000?style=flat-square&logo=apple&logoColor=white" /></a>
  <a href="#requirements"><img alt="Windows x64" src="https://img.shields.io/badge/Windows-x64-0078D4?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI%2BPHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0wIDBoMTEuNHYxMS40SDB6TTEyLjYgMEgyNHYxMS40SDEyLjZ6TTAgMTIuNmgxMS40VjI0SDB6TTEyLjYgMTIuNkgyNFYyNEgxMi42eiIvPjwvc3ZnPg%3D%3D" /></a>
  <a href="#android-termux"><img alt="Android in Termux" src="https://img.shields.io/badge/Android-ARM64%20%C2%B7%20Termux-3DDC84?style=flat-square&logo=android&logoColor=white" /></a>
  <br>
  <a href="#quick-start"><img alt="Install with curl or PowerShell" src="https://img.shields.io/badge/install-curl%20%C2%B7%20PowerShell-555?style=flat-square&logo=gnubash&logoColor=white" /></a>
  <a href="#quick-start"><img alt="Homebrew" src="https://img.shields.io/badge/Homebrew-opensec--git%2Ftap-FBB040?style=flat-square&logo=homebrew&logoColor=black" /></a>
  <a href="#quick-start"><img alt="Scoop" src="https://img.shields.io/badge/Scoop-opensec--git-3d8fd1?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI%2BPHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0wIDBoMTEuNHYxMS40SDB6TTEyLjYgMEgyNHYxMS40SDEyLjZ6TTAgMTIuNmgxMS40VjI0SDB6TTEyLjYgMTIuNkgyNFYyNEgxMi42eiIvPjwvc3ZnPg%3D%3D" /></a>
  <a href="#quick-start"><img alt="npm" src="https://img.shields.io/badge/npm-pi--bolt-CB3837?style=flat-square&logo=npm&logoColor=white" /></a>
  <a href="#quick-start"><img alt="mise" src="https://img.shields.io/badge/mise-github%20backend-6c47ff?style=flat-square" /></a>
  <a href="https://aur.archlinux.org/packages/pi-bolt-avx2-bin-release-git"><img alt="AUR" src="https://img.shields.io/badge/AUR-pi--bolt-1793d1?style=flat-square&logo=archlinux&logoColor=white" /></a>
</p>

<p align="center">
  <a href="#quick-start"><b>Install</b></a> ·
  <a href="#benchmarks"><b>Benchmarks</b></a> ·
  <a href="#documentation"><b>Docs</b></a> ·
  <a href="https://pi-bolt.opensec.in"><b>Website</b></a>
</p>

Pi-Bolt is the Pi you already use, with the same commands, sessions, settings, providers and extensions, but every function is
compiled to machine code when the executable is built, so nothing is parsed or JIT-compiled at launch. Against Pi as released on
Node 24, it writes a 400 KB file 120 times faster, uses 8 times less CPU while a long answer streams, 7 times less over a session
and a quarter of the memory; against Pi on stock Bun, much the same.

<img alt="Pi-Bolt 1.0.0 against Pi 1.4.0 as released, Linux x86-64: writing a 400 KB file 120 times faster than on Node 24 (162 times than on Bun), 8 times less CPU while a long answer streams, 7.1 times less CPU over an interactive session, 4.5 times less memory after 40 prompts" src="docs/images/pi-bolt-benchmarks.png" width="880">

<img alt="Pi-Bolt 1.0.0 against Pi 1.4.0 as released on Node 24 and on Bun, on Linux x86-64, Linux ARM64, macOS and Windows: 61 to 162 times faster writing a 400 KB file, 4.8 to 17 times less CPU streaming a long answer, 5.3 to 8.8 times less CPU per prompt, 5.9 to 7.1 times less CPU over a session, 2.5 to 15 times less memory, 3.8 to 11 times sooner ready to type" src="docs/images/pi-bolt-every-platform.png" width="880">

https://github.com/user-attachments/assets/d043eb7d-ecb2-4837-a3ee-cbd98079adf9

## Why Pi-Bolt

| | Pi on Node&nbsp;24 | Pi&#8209;Bolt |
|---|---:|---:|
| Ready to type | 423 ms | **41 ms**<br><sub>10×&nbsp;sooner</sub> |
| Writing a 400 KB file through a tool call | 143.7 s | **1.2 s**<br><sub>120×&nbsp;faster</sub> |
| CPU while a long answer streams | 18.6 s | **2.3 s**<br><sub>8×&nbsp;less</sub> |
| CPU per prompt, 40 prompts into a session | 352 ms | **46 ms**<br><sub>7.7×&nbsp;less</sub> |
| Memory after 40 prompts | 521 MB | **117 MB**<br><sub>4.5×&nbsp;less</sub> |

<sub>Pi-Bolt 1.0.0 against Pi 1.4.0 as released, Linux x86-64, one CPU. Every platform: [Benchmarks](#benchmarks).</sub>

- **Nothing to install first:** one executable; no Node.js, Bun or npm to run it.
- **The same Pi:** same commands, sessions, settings and providers; every Pi extension works unchanged.
- **Long sessions stay fast:** a frame costs about the same with 4,000 messages as with none, and typing stays responsive.
- **Everywhere:** Linux x86-64 and ARM64 (Android too, in Termux), macOS on Apple silicon, Windows x64.

## Quick start

**Linux, macOS, Android (Termux)**

```bash
curl -fsSL https://pi-bolt.opensec.in/install.sh | sh
```

**Windows** (PowerShell)

```powershell
irm https://pi-bolt.opensec.in/install.ps1 | iex
```

Or:

- Command Prompt: `powershell -c "irm https://pi-bolt.opensec.in/install.ps1 | iex"`
- Homebrew (macOS on Apple silicon, Linux): `brew install opensec-git/tap/pi-bolt`, updated with `brew upgrade pi-bolt`
- Scoop (Windows): `scoop bucket add opensec-git https://github.com/opensec-git/scoop-bucket`, then
  `scoop install opensec-git/pi-bolt`, updated with `scoop update pi-bolt`
- mise: `github:opensec-git/Pi-Bolt` with the options in the
  [installation guide](https://pi-bolt.opensec.in/docs/installation.html), updated with
  `mise upgrade --bump github:opensec-git/Pi-Bolt`
- npm: `npm install -g --allow-scripts=pi-bolt pi-bolt`
- Bun: `bun add -g --trust pi-bolt`
- Arch Linux: the community AUR package
  [`pi-bolt-avx2-bin-release-git`](https://aur.archlinux.org/packages/pi-bolt-avx2-bin-release-git) (the x86-64 AVX2 build,
  signature checked), `paru -S pi-bolt-avx2-bin-release-git`

Then, in your project:

```bash
pi-bolt                         # start
pi-bolt update                  # install the latest release
pi-bolt update --rollback       # put back the build it replaced
pi-bolt update --channel beta   # take pre-releases too
```

New to Pi? Run `/login` to connect a provider. Your Pi configuration in `~/.pi/agent` is used as is. Pi-Bolt installs next to Pi as `pi-bolt`, so both can stay installed;
running the installer again updates, reinstalls or uninstalls it.

<details>
<summary><b>Optional extensions the installer offers</b></summary>

The installer verifies the release signature and offers three optional extensions by OpenSec:

- [`opensec-pi-subagents`](https://www.npmjs.com/package/opensec-pi-subagents): specialized agents in separate sessions,
  parallel workflows and scheduled jobs.
- [`opensec-pi-todo`](https://www.npmjs.com/package/opensec-pi-todo): a todo list for the model, shown as a live panel above the
  editor.
- [`opensec-pi-fff`](https://www.npmjs.com/package/opensec-pi-fff): pi-fff's fast fuzzy find, grep and `@`-mentions, at full
  speed on the default build.

</details>

<details>
<summary><a id="jit-build-for-heavy-plugins"></a><b>JIT build, for heavy plugins</b></summary>

The default build has no JIT: Pi's own code is compiled machine code, and an extension loaded at run time is interpreted. Light
extensions don't notice. For extensions that do heavy work (search, parsing) or use `bun:ffi`, install the JIT build. Pi itself
stays compiled, so it is within about 5% of the default.

```bash
# macOS, Linux ARM64, Termux
curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=arm64-jit sh
# Linux x86-64
curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=x64-jit sh
```

On Windows, run `$env:PIBOLT_VARIANT = "x64-jit"` before the PowerShell command. With npm, keep `PIBOLT_VARIANT` set in your
shell profile; it is read on every run. `pi-bolt --version` then ends in `JIT on`, and `pi-bolt update` keeps the build you
chose.

On the default build, an extension that needs the JIT (it calls into a native library through `bun:ffi`) fails with a line that
names the extension and gives the command that switches to the JIT build.

</details>

<details>
<summary><b>Manual download</b></summary>

Download a build from the [latest release](https://github.com/opensec-git/Pi-Bolt/releases/latest), check it against
`SHA256SUMS`, unpack it and run `./pi` (on Windows, `pi-bolt.exe`).

| Download | For |
|---|---|
| `pi-bolt-linux-x64.tar.xz` | **Linux**, CPUs with AVX2 (Intel Haswell, AMD Zen and later) |
| `pi-bolt-linux-x64-baseline.tar.xz` | Linux, any x86-64 CPU |
| `pi-bolt-linux-arm64.tar.xz` | **Linux** ARM64, any 64-bit ARM CPU (Raspberry Pi 3 and later, AWS Graviton, Ampere) |
| `pi-bolt-darwin-arm64.tar.xz` | **macOS**, Apple silicon |
| `pi-bolt-win32-x64.zip` | **Windows** x64; compiled code on CPUs with AVX2, bytecode on others |

`-jit` builds are for heavy use of plugins loaded at run time; the runtime and kit archives are what `pi-bolt compile` builds
with ([compiling plugins in](docs/PLUGINS.md)). In Termux, use the installer: it sets the executable's loader to Termux's glibc
([Android](#android-termux)).

</details>

<details>
<summary><a id="android-termux"></a><b>Android (Termux)</b></summary>

In [Termux](https://termux.dev) from F-Droid or GitHub (the Google Play version has no glibc packages), run the same
`curl ... | sh` command. Pi-Bolt is a glibc program and Android's libc is not glibc, so the installer first installs Termux's
glibc (`pkg install glibc-repo && pkg install glibc`, after asking), sets it as the executable's loader, and links `pi-bolt`
into `$PREFIX/bin`.

`pi-bolt` is a small launcher that starts Pi-Bolt in Android's time zone and without Termux's `LD_PRELOAD` (a library of
Android's libc, which glibc cannot load); the programs Pi-Bolt starts get it back. The installer copies Termux's `resolv.conf`
and `hosts` to `$PREFIX/glibc/etc`, where Termux's glibc reads them.

</details>

<details>
<summary><a id="requirements"></a><b>Requirements</b></summary>

- **Linux** x86-64 or ARM64 with glibc 2.17 or later (Ubuntu 20.04+, Debian 11+, RHEL/Rocky 8+, Amazon Linux 2, Raspberry Pi
  OS 64-bit). musl is not supported.
- **Android** 7 or later on 64-bit ARM, in Termux.
- **macOS** 13 or later on Apple silicon. Install with the installer, Homebrew, mise or npm
  ([Troubleshooting](docs/TROUBLESHOOTING.md#macos)).
- **Windows** 10 (1809) or later, or Windows 11, on x64. Install with the installer, Scoop, mise or npm; for problems, see
  [Troubleshooting: Windows](docs/TROUBLESHOOTING.md#windows).
- Windows on ARM64 runs the x64 build, in Windows 11's x64 emulation.

</details>

## Features

- **Editors:** `pi-bolt --acp` runs Pi-Bolt in the agent panel of Zed, JetBrains IDEs, VS Code, Neovim and other
  [ACP](https://agentclientprotocol.com) editors, with your Pi settings, extensions, skills and sessions. [Setup](docs/ACP.md)
- **Other agents:** `pi-bolt --mcp` is an [MCP](https://modelcontextprotocol.io) server; other agents give Pi-Bolt a task with
  its `prompt` tool and continue the session in later calls. [Setup](docs/MCP.md)
- **Built-in search:** the `grep` and `find` tools are on by default and search in-process, following `.gitignore`, with
  nothing to install. With [`opensec-pi-fff`](https://www.npmjs.com/package/opensec-pi-fff) or `@ff-labs/pi-fff` loaded,
  FFF's search tools are used instead.
- **Compiled plugins:** `pi-bolt compile` compiles the extensions installed on this machine into your Pi-Bolt (Linux and macOS)
  with the release's own compiler, where they run as machine code; `pi-bolt update` compiles them into each new release.
  [Plugins](docs/PLUGINS.md)
- **Doctor:** `pi-bolt doctor` prints a plain-text report of your installation to paste into an issue. [Details](#pi-bolt-doctor)
- **Updates:** `pi-bolt update` installs the latest release, and `--rollback` puts back the build it replaced.
- **Herdr:** Pi-Bolt shows its state in a [Herdr](https://herdr.dev) pane, and a Herdr restart reopens the conversation.
  [Details](#herdr)
- **ARM64 and Android:** Linux ARM64 builds, and the same installer in Termux. [Android](#android-termux)

Pi extensions work unchanged. The default build has no JIT, so an extension loaded at run time is interpreted; compile the ones
you use every day into Pi-Bolt with `pi-bolt compile`, or use the [JIT build](#jit-build-for-heavy-plugins).

<details>
<summary><b>Setup in Zed and Claude Code</b></summary>

In Zed's `settings.json` ([other editors](docs/ACP.md)):

```json
"agent_servers": {
  "Pi-Bolt": { "type": "custom", "command": "/full/path/to/pi-bolt", "args": ["--acp"], "env": {} }
}
```

In Claude Code ([other clients](docs/MCP.md)):

```bash
claude mcp add pi-bolt -- /full/path/to/pi-bolt --mcp
```

</details>

<details>
<summary><a id="pi-and-pi-bolt-side-by-side"></a><b>Pi and Pi-Bolt side by side</b></summary>

Pi-Bolt uses Pi's agent directory (`~/.pi/agent`) and shared `packages` list. Ordinary `pi-bolt install` and `pi-bolt remove`
update that shared list, just as Pi does, and drop Pi-Bolt's own entry for that package, so both load what the shared list
declares. Add `--pi-bolt` to change only Pi-Bolt's overrides. Using fictional package names:

```bash
pi-bolt install --pi-bolt npm:@example/bolt-tools
pi-bolt remove --pi-bolt npm:@example/shared-tools
```

`piBolt.packages` extends the shared list. A `-source` entry excludes an inherited package from Pi-Bolt without changing Pi;
other shared packages remain inherited. See [Configuration](https://pi-bolt.opensec.in/docs/configuration.html).

```json
{
  "packages": ["npm:@juicesharp/rpiv-todo"],
  "piBolt": { "packages": ["-npm:@juicesharp/rpiv-todo", "npm:opensec-pi-todo"] }
}
```

</details>

<details>
<summary><a id="pi-bolt-doctor"></a><b>pi-bolt doctor</b></summary>

`pi-bolt doctor` prints a report of your installation, in plain text to paste into an issue: the build and how it was installed,
whether the release's signature verifies, which extensions are compiled in, run at run time or fail to load, how Pi's `packages`
and `piBolt.packages` differ, the Herdr and editor setup, and the problems it finds, such as another `pi-bolt` earlier on your
`PATH`. It prints no keys or tokens, and shows your home folder as `~`. `pi-bolt doctor --offline` leaves out the release
signature and the check for a newer version.

</details>

<details>
<summary><a id="herdr"></a><b>Herdr</b></summary>

In a [Herdr](https://herdr.dev) pane, Pi-Bolt shows up as `pi-bolt` (idle, working or blocked), and a Herdr restart reopens the
same conversation in the same pane. There is nothing to install; `"-builtin:herdr"` in the `extensions` setting turns it off.
Restoring a session needs Herdr 0.9.2 or later. To start other agents in Herdr panes from Pi-Bolt, install
[pi-herdr](https://www.npmjs.com/package/@andrewjacop/pi-herdr): `pi-bolt install npm:@andrewjacop/pi-herdr`.

</details>

## Benchmarks

Pi-Bolt 1.0.0 against Pi 1.4.0 as released on npm, run by stock Bun 1.4.4 and by Node 24, doing the same work against the same
local model; lower is better.

<details open>
<summary><b>Linux x86-64</b></summary>

| Benchmark | Pi-Bolt | Pi on Bun | Pi on Node 24 |
|---|---:|---:|---:|
| Writing a 400 KB file through a tool call, s | **1.2** | 194.8 | 143.7 |
| CPU, streaming a 60,000-character answer, s | **2.34** | 21.92 | 18.62 |
| CPU per prompt, prompts 21 to 40 of a session, ms | **46** | 328 | 352 |
| CPU, interactive session, ms | **167** | 1,087 | 1,180 |
| Memory after 40 prompts, MB | **117** | 309 | 521 |
| Ready to type, ms | **41** | 366 | 423 |

</details>

<details>
<summary><b>Linux ARM64</b></summary>

| Benchmark | Pi-Bolt | Pi on Bun | Pi on Node 24 |
|---|---:|---:|---:|
| Writing a 400 KB file through a tool call, s | **1.3** | 164.1 | 134.5 |
| CPU, streaming a 60,000-character answer, s | **2.32** | 18.82 | 18.19 |
| CPU per prompt, prompts 21 to 40 of a session, ms | **42** | 280 | 299 |
| CPU, interactive session, ms | **156** | 1,021 | 1,099 |
| Memory after 40 prompts, MB | **120** | 299 | 640 |
| Ready to type, ms | **37** | 334 | 390 |

</details>

<details>
<summary><b>macOS (Apple M5)</b></summary>

| Benchmark | Pi-Bolt | Pi on Bun | Pi on Node 24 |
|---|---:|---:|---:|
| Writing a 400 KB file through a tool call, s | **0.6** | 76.2 | 96.7 |
| CPU, streaming a 60,000-character answer, s | **1.35** | 7.10 | 6.47 |
| CPU per prompt, prompts 21 to 40 of a session, ms | **17** | 111 | 149 |
| CPU, interactive session, ms | **80** | 485 | 523 |
| Memory after 40 prompts, MB | **89** | 746 | 1,363 |
| Ready to type, ms | **24** | 120 | 151 |

</details>

<details>
<summary><b>Windows x64</b></summary>

| Benchmark | Pi-Bolt | Pi on Bun | Pi on Node 24 |
|---|---:|---:|---:|
| Writing a 400 KB file through a tool call, s | **1.8** | 131.0 | 110.1 |
| CPU, streaming a 60,000-character answer, s | **2.69** | 44.86 | 39.01 |
| CPU per prompt, prompts 21 to 40 of a session, ms | **53** | 278 | 379 |
| CPU, interactive session, ms | **213** | 1,266 | 1,286 |
| Memory after 40 prompts, MB | **79** | 286 | 378 |
| Ready to type, ms | **78** | 300 | 363 |

</details>

GitHub's runners for Linux (pinned to one CPU) and Windows (2 cores; memory there is the working set); macOS on an Apple M5,
not pinned. Node 22, the method and the raw data: [`bench/results/2026-10-23-stock-bun-1.0.0`](bench/results/2026-10-23-stock-bun-1.0.0).

Against [PiG](https://github.com/MichaelKinsy/PiG) 0.5.1 (Pi ported to Go):
[Pi-Bolt against PiG](docs/BENCHMARKS.md#100-against-pig-051-and-099). Every other result, and the method:
[docs/BENCHMARKS.md](docs/BENCHMARKS.md).

## How it works

1. **Bun bundles Pi** and compiles it to JavaScriptCore bytecode.
2. **An ahead-of-time compiler** turns every function into optimized x86-64 or ARM64 machine code, through B3, the back end of
   JavaScriptCore's top-tier JIT.
3. **A prebuilt heap** holds Pi's modules already loaded, and is mapped from the executable at launch.
4. **A training profile** orders the code so that startup touches as few pages as possible.

<details>
<summary><b>Diagram and sources</b></summary>
<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/how-it-works-dark.svg">
  <img alt="Build time: Pi is bundled and compiled to bytecode by bun build, then every function is compiled to machine code by the AOT compiler, guided by a training profile. The result is one executable holding the Bun runtime, a prebuilt heap and the machine code. At launch, pi maps the code and heap from the file and runs main()." src="docs/images/how-it-works-light.svg">
</picture>

The compiler builds on [oven-sh/WebKit#743](https://github.com/oven-sh/WebKit/pull/743). Pi-Bolt ports it to x86-64 and macOS
and adds its own code generation and runtime work ([docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)). `packages/` is the Pi release
named in [`sources.json`](sources.json) with Pi-Bolt's changes on top; the engine changes are in `patches/`.

</details>

## Documentation

- [Architecture](docs/ARCHITECTURE.md): what the executable contains, how it is built, what happens at launch
- [Building](docs/BUILDING.md): building the runtime and Pi from source, training profiles, tests
- [Benchmarks](docs/BENCHMARKS.md): method, results and raw data
- [Plugins](docs/PLUGINS.md): porting Pi extensions and writing them for AOT
- [Editors](docs/ACP.md): Pi-Bolt in Zed, JetBrains IDEs, VS Code and other ACP editors
- [Other agents](docs/MCP.md): Pi-Bolt as a tool of other agents and MCP clients
- [Troubleshooting](docs/TROUBLESHOOTING.md): diagnostics, environment variables, known limitations
- [Releasing](docs/RELEASING.md): CI, releases, signing

## Contributing

Bug reports, benchmarks on other hardware and fixes are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Report security
issues privately, as described in [SECURITY.md](SECURITY.md).

<details>
<summary><b>Build from source</b></summary>

```bash
git clone https://github.com/opensec-git/Pi-Bolt.git && cd Pi-Bolt
scripts/fetch-runtime.sh            # the released Pi-Bolt runtime (or build it: docs/BUILDING.md)
scripts/prepare-pi.sh               # builds the Pi in this repository
scripts/build-pi.sh                 # out/pi-bolt/pi
tests/aot/run.sh                    # engine tests
```

</details>

## Star history

<a href="https://www.star-history.com/#opensec-git/pi-bolt&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=opensec-git/pi-bolt&type=Date&theme=dark">
    <img alt="Star history of Pi-Bolt" src="https://api.star-history.com/svg?repos=opensec-git/pi-bolt&type=Date" width="600">
  </picture>
</a>

## License

MIT. Release executables include Bun (MIT), JavaScriptCore (LGPL-2.0 and BSD), ICU (Unicode License) and Pi (MIT); see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

> [!NOTE]
> Pi-Bolt is an independent fork of [Pi](https://github.com/earendil-works/pi), not affiliated with Pi's authors or with Oven.

<p align="center">
  Built on <a href="https://github.com/earendil-works/pi">Pi</a> by Mario Zechner and contributors,
  and <a href="https://github.com/oven-sh/bun">Bun</a> by Oven.
</p>
