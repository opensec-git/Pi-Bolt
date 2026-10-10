# Handoff: releasing Pi-Bolt 0.8.0 (Windows x64 added)

For the machine that finishes the release (the owner's Mac). The Windows side is done on the Windows PC. This file says what
is left, in order, and what not to do. Delete it from the branch once 0.8.0 is out.

## Where things are

- Branch `windows-x64`, draft pull request [#4](https://github.com/opensec-git/Pi-Bolt/pull/4) into `pi-bolt`. It is
  `pi-bolt` (0.7.3, Pi 1.1.0, up to `a9672fd06`) merged with the Windows port, as **0.8.0**. Nothing is merged, tagged or
  published: users get 0.7.3.
- What changed and why: the PR's description; `docs/releases/0.8.0.md` (the release notes); `docs/RESUME.md` (the Windows
  port's state and open items); `docs/RELEASING.md` (the release process, with Windows in it).
- Windows: built and tested on Windows 11 (`tests\pi`, the self-test, `tests\runtime`, `tests\aot`, `tests\cfg`, Intel SDE),
  benchmarked (`bench/results/2026-10-10-windows-0.8.0`), code-reviewed. Its runtime (`bun.exe` sha256
  `27bbd0ad3aa9483c23102df7133dfa6e7d31a9f3f0b22ba55db8466f7a45e3e2`) is packaged on the Windows PC as
  `pi-bolt-runtime-win32-x64.zip` (sha256 `9b0109ca9d931b6f540bf8ef8240f226c3cde9a18a53f535a959ee644e226f63`), to be uploaded
  to the draft release from there (step 4).
- The engine patches changed (the Windows port, and 0.7.3's engine changes merged with it): **the Linux and macOS runtimes
  must be built again from this branch.** Nobody has built or run them yet. That is the one step that can break Linux or
  macOS; everything else is checked by CI or only reaches users at `publish`.

## Status from the Mac (2026-10-10)

- **0.7.4 is merged into this branch.** 0.7.4 (released from `pi-bolt`) fixed loading code from the working directory: the
  tui's native helper lookup (the same fix this branch had, its version kept) and, in the engine, the resolver: code built into
  the executable resolves no module or package in the working directory (`tests/runtime` workdir, also in `run.ps1`). It also
  builds with `--compile-autoload-package-json` (in `build-pi.ps1` too) and runs `tests/pi`'s extensions from a folder of their
  own (also in `run.ps1`), with a test of an extension's own packages.
- **The engine patches changed again** (three-way merge of the engine trees, replayed and checked): Bun tree
  `522ef6e2a120616ab9e0b6317be1487ab8eb18f0`, WebKit tree `40dbb846da619a806fcfe4a844e4239f65c93f93`. **The Windows runtime packaged
  before this (`bun.exe` sha256 `27bbd0ad...`) is out of date: build it again on the Windows PC from this branch, run
  `tests\runtime\run.ps1` (workdir must pass), `tests\aot`, `tests\pi\run.ps1`, and upload that one in step 4.**

## Rules

- No AI attribution in commits, PRs or releases. Commits: `-c user.name=OpenSec -c
  user.email=323596076+opensec-intelligence@users.noreply.github.com`, `type(scope): message`, `npm run check` first.
- No force push. Ask the owner before anything outward-facing (push, tag, release, publish, npm).
- Never put a token or key in the repository. The release key stays where the owner keeps it.
- Keep Linux and macOS building; Windows-only code stays behind `OS(WINDOWS)` / `_WIN32` / `cfg(windows)` /
  `process.platform === "win32"`.
- Don't move files out of the way by deleting them: rename them aside.

## Steps

### 1. CI on the PR

Open the PR's Checks. `ci` should run lint and types, Pi's tests, the Linux and macOS builds on the released runtime, the
installer's checks, and whether the patches apply. If no checks start, Settings → Actions may need enabling or approving. Fix
anything red with a commit pushed to `windows-x64` (a branch of this repository).

### 2. The macOS runtime (on the Mac)

```bash
git fetch origin && git switch windows-x64 && git pull
scripts/fetch-sources.sh            # the pinned WebKit and Bun, with patches/*.patch applied
scripts/build-runtime.sh            # about an hour
scripts/prepare-pi.sh               # Pi, offline (the model catalog is in the repository)
scripts/package-release.sh          # dist/0.8.0: the macOS archives and pi-bolt-runtime-darwin-arm64.tar.gz
tests/aot/run.sh && tests/runtime/run.sh && tests/pi/run.sh "$PWD/out/pi-bolt/pi"
```

All must pass. Then compare with 0.7.3 on the Mac (`bench/benchmark.py`, startup/headless/interactive, against the 0.7.3
release) to make sure nothing got slower. Keep `dist/0.8.0/pi-bolt-runtime-darwin-arm64.tar.gz` and note the SHA-256 of the
`bun` inside it.

### 3. The Linux runtime (a Linux x86-64 machine or cloud VM, about 32 GB RAM, 40 GB disk)

```bash
scripts/toolchain/make-sysroot.sh   # once
scripts/fetch-sources.sh && scripts/build-runtime.sh
scripts/prepare-pi.sh && scripts/package-release.sh
tests/aot/run.sh && tests/runtime/run.sh && tests/pi/run.sh "$PWD/out/pi-bolt/pi"
```

Before a release that changes the engine, also `tests/compat/run.sh` there (old distributions and CPUs; it needs bubblewrap
and qemu-user). Keep `dist/0.8.0/pi-bolt-runtime-linux-x64.tar.gz`.

If either platform fails, fix it on `windows-x64` (and tell the Windows PC: an engine change means its runtime is built
again too).

### 4. The draft release with the three runtimes

```bash
gh release create bolt-v0.8.0 -R opensec-git/Pi-Bolt --draft --title "Pi-Bolt 0.8.0" --notes ""
gh release upload bolt-v0.8.0 -R opensec-git/Pi-Bolt pi-bolt-runtime-darwin-arm64.tar.gz pi-bolt-runtime-linux-x64.tar.gz
```

and, from the Windows PC: `gh release upload bolt-v0.8.0 -R opensec-git/Pi-Bolt dist\0.8.0\pi-bolt-runtime-win32-x64.zip`.
A draft is not visible to users. Fill the Linux and macOS rows of "The runtimes" in `docs/releases/0.8.0.md` (where built, the
`bun` SHA-256), commit, push.

### 5. Repository settings (owner, once)

- Settings → Rules → Rulesets → New tag ruleset for `refs/tags/bolt-v*`: only administrators (and the `tag` workflow) may
  create, move or delete.
- Settings → Environments → `release`: required reviewers (the owner).
- Settings → Secrets and variables → Actions → Variables: `AUTO_RELEASE` not `true` for this release, so nothing is published
  without a person.
- npm: the packages `pi-bolt-win32-x64` and `pi-bolt-win32-x64-jit` do not exist yet. They are published once by hand (step 7),
  then given trusted publishing like the others (`docs/RELEASING.md`, "npm").

### 6. Merge, tag, release

1. Decide the two open questions below; mark PR #4 ready; merge it into `pi-bolt` (a merge commit, not a squash: the history
   carries the engine commits' provenance).
2. When `ci` passes on `pi-bolt`, push the tag (`git tag -a bolt-v0.8.0 -m "Pi-Bolt 0.8.0"` on the merge commit, `git push
   origin bolt-v0.8.0`), or run `release` for it. First time: run `release` from the Actions tab with `dry_run` checked; its
   Windows job has never run on GitHub's runners. Then the real run, which leaves a draft with the 17 files of
   `scripts/release-files.txt`.
3. Check the draft: every archive, `extensions.txt`, `SHA256SUMS` beginning `# pi-bolt 0.8.0`.

### 7. Publish

1. `gh workflow run publish.yml -R opensec-git/Pi-Bolt -f tag=bolt-v0.8.0` (from `pi-bolt`). It signs (the `release`
   environment's key, after the reviewer approves), checks, publishes the release, puts `install.sh`, `install.ps1` and the
   site on gh-pages.
2. npm, from the machine with npm publish rights: `gh release download bolt-v0.8.0 -R opensec-git/Pi-Bolt -p '*.tar.xz' -p
   '*.zip' -p SHA256SUMS -D dist/0.8.0 --clobber`, `scripts/publish-npm-builds.sh dist/0.8.0`, `(cd npm && npm publish
   --access public)`.
3. Try every install and look for "signature verified": `curl -fsSL https://pi-bolt.opensec.in/install.sh | sh` (Linux and
   macOS), `powershell -c "irm https://pi-bolt.opensec.in/install.ps1 | iex"` (Windows 10 and 11 if you can), `npm install -g
   pi-bolt`; then `pi-bolt --version` says 0.8.0, and the installer offers OpenSec's two extensions.

If something is wrong after publishing: 0.7.3 stays installable (`PIBOLT_VERSION=bolt-v0.7.3`), and can be marked the latest
release again on GitHub.

## Open questions for the owner

- **`pi-bolt update`'s folder.** 0.8.0 installs into the installation that is running; `PIBOLT_INSTALL` decides only for an
  executable outside one. 0.7.3 did the opposite. Keep (a stray or hostile environment variable cannot put the update elsewhere
  and leave the running copy stale), or go back to 0.7.3's order (`packages/coding-agent/src/pi-bolt.ts`,
  `piBoltUpdateEnvironment`, and its two tests).
- **The version:** 0.8.0 for a new platform (not 0.7.4).

## Known limits shipping with 0.8.0

- No Authenticode signature on `pi-bolt.exe` (SmartScreen may warn about a browser download; the installers are not affected).
- CET (shadow stacks) off, planned after this release; Control Flow Guard on.
- The determinism check of a Windows build fails about one time in three on one record of the heap; `release` builds again
  (exit code 3) and ships only a build whose two copies agreed. What it is and the stronger check to add: `docs/RESUME.md`.
- Not yet run: a real Windows 10 machine; `shellcheck` on the changed shell scripts (CI does it).
