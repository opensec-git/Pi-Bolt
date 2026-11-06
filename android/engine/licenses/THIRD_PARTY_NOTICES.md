# Third-party software in the Pi-Bolt Android app's on-device engine

The app runs Pi-Bolt on the phone with the programs and libraries below, shipped inside the APK as separate files
(lib*.so in the app's native library directory). Exact versions: `VERSIONS.txt`. Full licence texts: `texts/`.
Per-package copyright files: `userland/` (Debian packages), `pi-bolt/` (the engine itself) and `extensions/` (the
bundled Pi extensions).

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
| OpenSec Subagents for Pi (opensec-pi-subagents, from npm) | the engine's `subagent` tool; based on tintinweb/pi-subagents | Apache-2.0 (`extensions/opensec-pi-subagents/LICENSE`, `NOTICE`) |
| OpenSec Todo for Pi (opensec-pi-todo, from npm) | the engine's `todo` tool; based on juicesharp/rpiv-todo | Apache-2.0 (`extensions/opensec-pi-todo/LICENSE`, `NOTICE`) |

## glibc is modified

The glibc in this app is GNU C Library release 2.44 with Termux's Android patches applied and rebuilt for the app's
private directory (`/data/data/in.opensec.pibolt/files/usr`): its library directory, configuration files (`etc/`),
temporary directory and `/bin/sh` point there. It is a separate shared library: you may replace it with a modified
version by rebuilding the engine pack from the published source (`android/engine/build-pack.sh`) and the APK.

## Not modified

bash, busybox, ripgrep, fd and the shared libraries from Debian are Debian's binaries, unchanged except that the
program interpreter path is set to the app's glibc loader and the run-path entry is removed (patchelf), and debug
symbols are stripped. git and curl are built from the unmodified release tarballs.

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
