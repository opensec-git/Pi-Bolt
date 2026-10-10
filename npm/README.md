<p align="center">
  <a href="https://github.com/opensec-git/Pi-Bolt">
    <img alt="Pi-Bolt logo" src="https://raw.githubusercontent.com/opensec-git/Pi-Bolt/HEAD/docs/images/logo.svg" width="112">
  </a>
</p>
<p align="center">
  <a href="https://www.npmjs.com/package/pi-bolt"><img alt="npm" src="https://img.shields.io/npm/v/pi-bolt?style=flat-square&color=2a78d6" /></a>
  <a href="https://github.com/opensec-git/Pi-Bolt/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/opensec-git/Pi-Bolt?style=flat-square&color=f0b03a" /></a>
  <a href="https://github.com/opensec-git/Pi-Bolt/blob/HEAD/LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-1baf7a?style=flat-square" /></a>
</p>

# pi-bolt

[Pi](https://github.com/earendil-works/pi), the coding agent, compiled ahead of time to native code. Pi-Bolt is one executable,
for Linux on x86-64, macOS on Apple silicon and Windows on x64, that starts two to three times sooner than Pi on Bun and uses
about a third of its CPU over a session, with no JIT. (Measured on one Linux server, one Mac and one Windows laptop; the times on
your machine will differ, the ratios less so.)

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/opensec-git/Pi-Bolt/HEAD/docs/images/bench-hero-dark.svg">
  <img alt="Pi-Bolt 0.7.3 vs Pi-Bolt 0.7.0 vs Bun 1.4.2 vs Node 24: ready to type 47 / 48 / 143 / 334 ms; CPU per session 309 / 328 / 913 / 1,312 ms; CPU while streaming 332 / 362 / 646 / 564 ms; memory after a long session 139 / 198 / 296 / 537 MB" src="https://raw.githubusercontent.com/opensec-git/Pi-Bolt/HEAD/docs/images/bench-hero-light.svg">
</picture>

## Install

```bash
npm install -g pi-bolt
```

With other package managers:

```bash
bun add -g pi-bolt
pnpm add -g pi-bolt
yarn global add pi-bolt
```

On Windows the package's install script puts the executable in place, so the package manager has to run it. npm does (npm
11 warns that it is not in `allowScripts`; `npm install -g --allow-scripts=pi-bolt pi-bolt` allows it by name), while pnpm
and Bun run install scripts only for the packages you allow to. If it did not run (`pi-bolt` then fails to start: "not
compatible with the version of Windows" or "not a valid application"), run it yourself, from the package's folder
(`npm root -g` says where npm's global packages are):

```powershell
node "$(npm root -g)\pi-bolt\install.cjs"
```

Without a package manager:

```bash
curl -fsSL https://pi-bolt.opensec.in/install.sh | sh
```

```powershell
powershell -c "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072; irm https://pi-bolt.opensec.in/install.ps1 | iex"
```

## Usage

Start Pi-Bolt in the directory where you want it to work:

```bash
cd /path/to/project
pi-bolt
```

Pi-Bolt is Pi. It uses your Pi configuration in `~/.pi/agent`, so your providers, settings, sessions and extensions are
already there. If you are new to Pi, run `/login` inside it to connect a provider, then give it a task. Everything in
[Pi's documentation](https://github.com/earendil-works/pi/tree/main/packages/coding-agent/docs) applies:

```bash
pi-bolt -p "summarize this repository"    # one prompt, print the answer
pi-bolt --help
```

## How the package works

This package does not contain the executable, and has no dependencies. Its `pi-bolt` command is `bin/pi-bolt.exe`, a
placeholder that the package's install script (`install.cjs`, plain Node.js) replaces.

**On Linux and macOS** the install script puts a small shell script there:

1. **On the first run**, it downloads the Pi-Bolt build that matches the package version, from the npm registry or, failing
   that, from the [GitHub release](https://github.com/opensec-git/Pi-Bolt/releases). It checks the download against the
   release's SHA-256 checksums and their signature (checked with OpenSSL 3 where there is one), then keeps it in `~/.pi-bolt/npm/<version>`.
2. **On every run**, it replaces itself with that native executable (`exec`). No Node.js or Bun process stays in between, and
   startup is the same as running the executable directly.

Where the package manager does not run install scripts (Bun, pnpm, `--ignore-scripts`), the placeholder starts the same
shell script, so nothing changes there.

**On Windows** the install script installs the native executable while the package is installed: the Windows build that
matches the package version, from the npm registry or, failing that, from the GitHub release. Before anything is unpacked it
checks the release's checksums against their Ed25519 signature (by the key in the repository's `keys/release.pub`), that they
are the checksums of this version, and the download against them; if any of that fails, nothing is installed. It then puts
`pi-bolt.exe` and the files it needs in the package's `bin` folder, where npm's `pi-bolt` command starts it directly, with no
Node.js process in between.

## Extensions

Pi extensions and Pi packages work as they do in Pi. Install them with Pi-Bolt itself, which needs neither npm nor Bun to do
it:

```bash
pi-bolt install npm:<package>
```

OpenSec maintains two for Pi-Bolt, prebuilt for Bun so that they load without being transpiled (Apache-2.0):

| Package | What it does |
|---|---|
| [`opensec-pi-subagents`](https://www.npmjs.com/package/opensec-pi-subagents) | Specialized agents in separate sessions that inherit the parent's model and thinking level |
| [`opensec-pi-todo`](https://www.npmjs.com/package/opensec-pi-todo) | A todo list for the model, shown as a live panel above the editor |

## Configuration

| Variable | Default | Effect |
|---|---|---|
| `PIBOLT_HOME` | `~/.pi-bolt` | Where downloaded executables are kept (Linux and macOS; on Windows they are in the package) |
| `PIBOLT_VARIANT` | the standard build for your CPU | `x64-baseline` for an x86-64 CPU without AVX2 (picked automatically). Advanced: `x64-jit` or `arm64-jit` also JIT-compile plugins loaded at run time. On Windows it is read when the package is installed |

## Update and uninstall

```bash
npm update -g pi-bolt       # Linux and macOS: the next run downloads the new version; Windows: while it updates
npm uninstall -g pi-bolt
rm -rf ~/.pi-bolt/npm       # Linux and macOS: downloaded executables
```

On Windows the executable is in the package's folder, and is removed with it.

## Requirements

- Linux on x86-64, glibc 2.17 or later: Ubuntu 20.04+, Debian 11+, Rocky Linux 8+, CentOS 7, Amazon Linux 2 and others.
  Alpine and other musl-based systems are not supported.
- Or macOS 13 or later on Apple silicon (M1 or later).
- `curl` or `wget`, `tar` and `sha256sum` (or, on macOS, `shasum`) for the first run.
- Or Windows 10 version 1809 or later, or Windows 11, on x64 (Windows on ARM runs it under its x64 emulation), and Node.js
  18 or later for the install script.

## Links

- Source, documentation and benchmarks: [github.com/opensec-git/Pi-Bolt](https://github.com/opensec-git/Pi-Bolt)
- Compiling Pi extensions into the executable: [docs/PLUGINS.md](https://github.com/opensec-git/Pi-Bolt/blob/HEAD/docs/PLUGINS.md)
- Problems: [troubleshooting](https://github.com/opensec-git/Pi-Bolt/blob/HEAD/docs/TROUBLESHOOTING.md) and
  [issues](https://github.com/opensec-git/Pi-Bolt/issues)

Pi-Bolt is a fork of [Pi](https://github.com/earendil-works/pi) by an independent team, not affiliated with Pi's authors.

## License

MIT
