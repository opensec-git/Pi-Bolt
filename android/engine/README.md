# On-device engine pack

Everything the Android app needs to run Pi-Bolt on the phone: the stock Pi-Bolt linux-arm64 engine, glibc rebuilt
for the app's private prefix, a userland (bash, busybox, git, curl, rg, fd) and language runtimes (Node.js with npm,
Python with pip). The paths, file names, manifest and
environment the app relies on are in [CONTRACT.md](CONTRACT.md).

## Build

```sh
android/engine/build-pack.sh            # -> android/engine/out/{jniLibs/arm64-v8a, assets/engine}
android/engine/scripts/verify-pack.sh   # run it like the app does, in an Alpine arm64 container
android/engine/scripts/source-bundle.sh # -> out/pi-bolt-android-engine-sources.tar (LGPL/GPL source)
```

Needs Docker on an arm64 host (an Apple Silicon Mac or GitHub's ubuntu-24.04-arm). The first build takes about
8 minutes on an M-series Mac (glibc 2 min, userland with curl and git 1.5 min, runtimes with CPython 2 min); stages are cached in `.work/` by a hash of their inputs.
`PIBOLT_PACK_WORK` and `PIBOLT_PACK_OUT` move the cache and the output (e.g. to an external disk). Gradle picks up
`out/` when it exists.

## Layout

| Path | What |
|---|---|
| `versions.env` | pins: engine release + sha256, glibc + Termux commit, curl, git, Node.js, CPython |
| `../../extensions.txt` | pins of the bundled Pi extensions (opensec-pi-subagents, opensec-pi-todo): npm version + integrity |
| `userland/packages.txt`, `userland/debs.lock` | Debian packages and their pinned versions + sha256 (`scripts/lock-debs.sh` refreshes) |
| `userland/busybox-skip.txt` | busybox applets that get no link |
| `docker/Dockerfile` | the builder (Debian trixie) |
| `glibc/build-glibc.sh` | glibc 2.44 + termux-pacman's Android patches, prefix substituted |
| `userland/build-userland.sh` | Debian binaries + curl and git from source, laid out as PREFIX |
| `runtimes/build-runtimes.sh` | Node.js (official linux-arm64 build) and CPython (from source for PREFIX), laid out as PREFIX |
| `glibc/execve.c` | glibc's execve for Android: runs scripts and programs in the app's data dir (CONTRACT.md) |
| `scripts/assemble.py` | renames to `lib*.so`, sets PT_INTERP (engine: in place; others: patchelf), drops RPATH, strips, writes manifest.json |
| `licenses/THIRD_PARTY_NOTICES.md` | notices for the app's About screen (copied to `assets/engine/licenses/` with the licence texts) |

## Updating

- Engine: change `ENGINE_*` in `versions.env` (sha256 from the release's `SHA256SUMS`).
- Debian packages (security updates): `scripts/lock-debs.sh`, rebuild, verify.
- Any change gives a new `packFingerprint`, so the app rebuilds its symlink farm after the update.
