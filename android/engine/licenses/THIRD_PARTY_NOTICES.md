# Third-party software in the Pi-Bolt Android app's on-device engine

The app runs Pi-Bolt on the phone with the programs and libraries below, shipped inside the APK as separate files
(lib*.so in the app's native library directory). Exact versions: `VERSIONS.txt`. Full licence texts: `texts/`.
Per-package copyright files: `userland/` (Debian packages), `pi-bolt/` (the engine itself), `extensions/` (the
bundled Pi extensions), `runtimes/` (Node.js, npm, Python, pip) and `esbuild/` (esbuild, Go, golang.org/x/sys).

## Corresponding source

The complete corresponding source of every GPL and LGPL component listed here, including our build scripts and
the changes we made, is published with each release of the app as the asset
`pi-bolt-android-engine-sources.tar` next to the APK (https://github.com/opensec-git/Pi-Bolt/releases). It contains
the glibc release tarball, Termux's glibc patches, the git and curl release tarballs, the Debian source packages of
bash and busybox, and the scripts that build this engine pack (`android/engine/` in the repository). The source is
also available on request for three years from the release date: open an issue at
https://github.com/opensec-git/Pi-Bolt/issues.

## Components

| Component | What it is here | Licence |
|---|---|---|
| GNU C Library (glibc) | C library and dynamic loader for the engine and every tool | LGPL-2.1-or-later (some files under other permissive licences, see glibc's LICENSES) |
| Termux glibc patches (termux-pacman/glibc-packages) | Android support for glibc: seccomp-safe system calls, Android users and groups, paths | patches: LGPL-2.1-or-later (as part of glibc); build scripts: MIT, Copyright (c) 2023 Ivan Max |
| GNU Bash | the shell (`bash`, `sh`) | GPL-3.0-or-later |
| BusyBox | coreutils, grep, sed, awk, find, tar, gzip, unzip, diff, less, vi and other applets | GPL-2.0-only |
| Git | `git` and its helpers | GPL-2.0-only (some parts LGPL-2.1 and other compatible licences) |
| curl / libcurl | `curl` and git's HTTPS transport | curl licence (MIT-style) |
| OpenSSL | TLS for libcurl | Apache-2.0 |
| nghttp2 | HTTP/2 for libcurl | MIT |
| zlib | compression | zlib licence |
| Zstandard (libzstd) | compression (used by OpenSSL) | BSD-3-Clause (dual-licensed with GPL-2.0; used under BSD) |
| PCRE2 | regular expressions for ripgrep | BSD-3-Clause with PCRE2 exception |
| ncurses (libtinfo, terminfo) | terminal capabilities for bash | MIT/X11 |
| GCC runtime library (libgcc_s) | unwinding support for ripgrep and fd | GPL-3.0-or-later with the GCC Runtime Library Exception 3.1 |
| ripgrep | `rg` | MIT or Unlicense |
| fd | `fd` | MIT or Apache-2.0 |
| Mozilla CA certificate list (Debian ca-certificates) | trusted root certificates | MPL-2.0 |
| Node.js | `node`: JavaScript runtime (with V8, libuv, ICU, OpenSSL, llhttp and the other components its licence file lists) | MIT; bundled components under their own licences (`runtimes/node.LICENSE`) |
| npm | `npm`, `npx`: Node's package manager, with its bundled dependencies | Artistic-2.0 (`runtimes/npm.LICENSE`); dependencies: ISC, MIT, BSD-2/3-Clause, Apache-2.0 (each in its own folder under `lib/node_modules/npm/node_modules`) |
| CPython | `python3`: the Python interpreter and standard library | PSF-2.0 (`runtimes/python.LICENSE`, which also lists the licences of the parts it includes) |
| pip | `pip`: Python's package installer, with its vendored libraries | MIT (`runtimes/pip.LICENSE`) |
| esbuild | `esbuild`: the JavaScript and TypeScript bundler that Vite and other tools run through its npm package | MIT, Copyright (c) 2020 Evan Wallace (`esbuild/esbuild.LICENSE`) |
| Go runtime and standard library | compiled into `esbuild` | BSD-3-Clause, Copyright 2009 The Go Authors (`esbuild/go.LICENSE`) |
| golang.org/x/sys | compiled into `esbuild` | BSD-3-Clause, Copyright 2009 The Go Authors (`esbuild/golang-x-sys.LICENSE`) |
| GNU C++ library (libstdc++) | C++ runtime for Node.js | GPL-3.0-or-later with the GCC Runtime Library Exception 3.1 |
| SQLite | `sqlite3` module | public domain |
| libffi | `ctypes` module | MIT |
| bzip2 (libbz2) | `bz2` module | bzip2 licence (BSD-style) |
| XZ Utils (liblzma) | `lzma` module | 0BSD |
| mpdecimal (libmpdec) | `decimal` module | BSD-2-Clause |
| ncurses (libncursesw) | `curses` module | MIT/X11 |
| util-linux libuuid | `uuid` module | BSD-3-Clause |
| OpenSec Subagents for Pi (opensec-pi-subagents, from npm) | the engine's `subagent` tool; based on tintinweb/pi-subagents | Apache-2.0 (`extensions/opensec-pi-subagents/LICENSE`, `NOTICE`) |
| OpenSec Todo for Pi (opensec-pi-todo, from npm) | the engine's `todo` tool; based on juicesharp/rpiv-todo | Apache-2.0 (`extensions/opensec-pi-todo/LICENSE`, `NOTICE`) |
| Pi Web Access (pi-web-access 0.44.0 by Nico Bailon, from npm) | the engine's `web_search`, `fetch_content`, `get_search_content` and `source_check` tools | MIT (`extensions/pi-web-access/LICENSE`) |
| pi-web-access's 210 runtime dependencies (from npm, pinned in `android/engine/npm/pi-web-access.lock.json`): the Model Context Protocol SDK, Mozilla Readability, defuddle, linkedom, turndown, unpdf, undici, zod and their dependencies | web search (Exa's MCP endpoint), page reading and PDF text | MIT (176), BSD-2-Clause (19), ISC (12), BSD-3-Clause (2), Apache-2.0 (1: @mozilla/readability); each package's licence under `extensions/pi-web-access/node_modules/`, the list in `extensions/pi-web-access/DEPENDENCIES.txt` |
| rpiv-ask-user-question (@juicesharp/rpiv-ask-user-question 2.15.0) and its dependency @juicesharp/rpiv-config 2.15.0, by juicesharp, from npm | the engine's `ask_user_question` tool | MIT (`extensions/juicesharp-rpiv-ask-user-question/LICENSE`) |

## glibc is modified

The glibc in this app is GNU C Library release 2.44 with Termux's Android patches applied and rebuilt for the app's
private directory (`/data/data/in.opensec.pibolt/files/usr`): its library directory, configuration files (`etc/`),
temporary directory and `/bin/sh` point there. Its `execve` is replaced by ours (`android/engine/glibc/execve.c`): it
runs scripts and programs in the app's data directory, which Android does not let the kernel execute, the way the
kernel would. It is a separate shared library: you may replace it with a modified
version by rebuilding the engine pack from the published source (`android/engine/build-pack.sh`) and the APK.

## Not modified

bash, busybox, ripgrep, fd and the shared libraries from Debian are Debian's binaries, unchanged except that the
program interpreter path is set to the app's glibc loader and the run-path entry is removed (patchelf), and debug
symbols are stripped. git and curl are built from the unmodified release tarballs. Node.js is the official
linux-arm64 release binary with the same interpreter change; CPython is built from the unmodified release tarball.

esbuild is built from its release source with one change (`android/engine/runtimes/esbuild-host-versions.patch`): it
accepts the JavaScript API of other esbuild versions (0.19 to 0.28), so projects that pin different versions share it.

## Licence texts

- `texts/LGPL-2.1`: GNU Lesser General Public License 2.1 (glibc)
- `texts/GPL-2`: GNU General Public License 2 (busybox, git)
- `texts/GPL-3`: GNU General Public License 3 (bash; libgcc_s, with the GCC Runtime Library Exception in
  `userland/gcc-14-base.copyright`)
- `texts/Apache-2.0`: Apache License 2.0 (OpenSSL, fd, the bundled extensions)
- `texts/MPL-2.0`: Mozilla Public License 2.0 (CA certificates)
- `userland/curl.COPYING`, `userland/git.COPYING` and the Debian copyright files: the remaining licences (MIT, BSD,
  zlib, X11, Unlicense) with their copyright notices
- `glibc/`: glibc's COPYING.LIB and LICENSES; `glibc/termux-LICENSE.md`: termux-pacman's MIT licence
