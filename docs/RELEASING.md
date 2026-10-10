# Releasing Pi-Bolt

Five workflows, all on GitHub's own runners:

| Workflow | When | What |
|---|---|---|
| `ci` | every push to `pi-bolt`, every pull request | lint and types, Pi's tests, Pi-Bolt built and tested on Linux x86-64 and macOS (Apple silicon), the installer's checks, whether the engine patches apply |
| `tag` | `ci` passed on a push to `pi-bolt` | when `VERSION` has no `bolt-vX.Y.Z` tag yet, tags that commit and runs `release` |
| `release` | a `bolt-vX.Y.Z` tag | builds the 15 archives on Linux, macOS and Windows, tests each as unpacked from its archive, makes a **draft** release with `SHA256SUMS`, `extensions.txt` and `RUNTIME_STAMP`, and runs `publish` |
| `publish` | after `release`, or by hand | signs `SHA256SUMS`, checks the signature and the checksums, publishes the release, puts `install.sh`, `install.ps1` and the site on `gh-pages`, and publishes npm |
| `upstream` | daily | opens an issue when Pi has a new release, with the files where merging it conflicts |

`tag` and the hand-over from `release` to `publish` run only when the repository variable `AUTO_RELEASE` is `true`. The
release key is a secret of the `release` environment, used only by `publish` run from `pi-bolt` ([Signing](#signing)).

- [A release, step by step](#a-release-step-by-step)
- [What a release holds](#what-a-release-holds)
- [When the engine changed](#when-the-engine-changed)
- [npm](#npm)
- [A new Pi version](#a-new-pi-version)
- [The macOS builds](#the-macos-builds)
- [The Windows builds](#the-windows-builds)
- [Signing](#signing)

## A release, step by step

1. Write the release notes in `docs/releases/X.Y.Z.md`; they become the GitHub release's text.
2. `scripts/bump-version.sh` (the last number goes up by one, 0.7.0 to 0.7.1; `--version X.Y.Z` sets another), commit, and push
   to `pi-bolt` (or merge a pull request).
3. When `ci` passes, `tag` tags the commit `bolt-vX.Y.Z` and runs `release`, which builds and tests for about an hour and leaves
   a draft; `publish` then signs it and publishes it. `tag` leaves a version without its release notes untagged.
4. Try every install, looking for "signature verified": `curl -fsSL https://pi-bolt.opensec.in/install.sh | sh`,
   `npm install -g pi-bolt`, and on Windows `powershell -c "irm https://pi-bolt.opensec.in/install.ps1 | iex"`.

By hand (with `AUTO_RELEASE` unset, or to redo a step): push the tag yourself
(`git tag -a bolt-vX.Y.Z -m "Pi-Bolt X.Y.Z" && git push origin bolt-vX.Y.Z`), then, once the draft is there, run `publish` from
`pi-bolt`: `gh workflow run publish.yml -R opensec-git/Pi-Bolt -f tag=bolt-vX.Y.Z`. A draft can also be signed on a machine that
has the key, before `publish`:

```bash
gh release download bolt-vX.Y.Z -R opensec-git/Pi-Bolt -p SHA256SUMS -D dist/X.Y.Z --clobber
scripts/sign-release.sh --key pi-bolt-signing-key.pem dist/X.Y.Z
gh release upload bolt-vX.Y.Z -R opensec-git/Pi-Bolt dist/X.Y.Z/SHA256SUMS.sig
```

`publish` refuses a draft whose signature does not verify against `keys/release.pub`. Either workflow can be run again with the
same tag: the draft's files are replaced (a signature of the old `SHA256SUMS` is dropped), and a published release is left as it is.
`release` can also be run with `dry_run` (from the Actions tab, with the tag): it builds and tests everything and makes no draft.

Old-distribution and old-CPU checks (`tests/compat/run.sh`: CentOS 7, Debian 9, Amazon Linux 2, emulated Haswell to Nehalem)
need bubblewrap and qemu-user, which GitHub's runners do not allow; run them on a Linux machine before a release that changes
the engine or the build. On Windows, the same for older CPUs is Intel SDE (`-snb`: no AVX2, the bytecode is used; `-hsw`).

## What a release holds

- The archives: `pi-bolt-linux-x64`, `-baseline` and `-jit` and `pi-bolt-darwin-arm64` and `-jit` (`.tar.xz` and `.tar.gz`);
  `pi-bolt-win32-x64` and `-jit` (`.zip`); the runtimes `pi-bolt-runtime-linux-x64.tar.gz`, `pi-bolt-runtime-darwin-arm64.tar.gz`
  and `pi-bolt-runtime-win32-x64.zip`.
- `extensions.txt`: the optional extensions the installers offer (OpenSec's `opensec-pi-subagents` and `opensec-pi-todo`), each
  with its version and npm integrity. The installers install those versions and only if the registry's tarball has that
  integrity; a release without the file pins none, and they offer nothing. Update it in the repository when either has a new
  release.
- `RUNTIME_STAMP`: what engine the runtimes were built from (below).
- `SHA256SUMS`: its first line is `# pi-bolt X.Y.Z`, then one line for each archive, `extensions.txt` and `RUNTIME_STAMP`.
  `install.ps1` and the npm package's Windows install refuse checksums whose first line is not their version: an older release,
  signed all the same, served as a newer one.
- `SHA256SUMS.sig` ([Signing](#signing)).

`scripts/release-files.txt` names the files of a release. `release` and `publish` check that `SHA256SUMS` lists exactly those,
each as it is, and that `extensions.txt` pins at least one extension and has no line the installers could not read
(`scripts/check-release-files.sh`). A new platform or variant is added to that list.

## When the engine changed

The runtime (patched WebKit and Bun) takes about 32 GB of RAM, 40 GB of disk and an hour on 16 cores to build, more than
GitHub's runners give. `release` therefore uses the previous release's runtimes when the engine is the same: `RUNTIME_STAMP`
is the hash of the engine entries of `sources.json`, the patches and the runtime build scripts
(`scripts/runtime-stamp.sh`). `ci` says when it differs. The previous release's stamp counts only as its signed `SHA256SUMS`
gives it, for that release's version (releases before 0.8.0 did not sign it, so their runtimes are not reused). Runtimes
uploaded to the draft are hashed by `release`'s first job and checked again by each build job before it uses one.

When it changed, build the three runtimes by hand before tagging:

1. On Linux x86-64: `scripts/toolchain/make-sysroot.sh` once, then `scripts/fetch-sources.sh` and `scripts/build-runtime.sh`
   ([BUILDING.md](BUILDING.md)). On a Mac with Apple silicon: `scripts/fetch-sources.sh` and `scripts/build-runtime.sh`. On
   Windows x64: `scripts\fetch-sources.ps1` and `scripts\build-runtime.ps1` ([WINDOWS.md](WINDOWS.md)).
2. On each, `scripts/package-release.sh` (Windows: `scripts\package-release.ps1`) and keep `pi-bolt-runtime-<platform>.tar.gz`
   (Windows: `.zip`) from `dist/X.Y.Z`. Run `tests/aot/run.sh` and `tests/runtime/run.sh` there (Windows: the `.ps1` ones).
3. Make a draft for the tag and upload all three: `gh release create bolt-vX.Y.Z --draft --title "Pi-Bolt X.Y.Z" --notes ""`,
   then `gh release upload bolt-vX.Y.Z pi-bolt-runtime-linux-x64.tar.gz pi-bolt-runtime-darwin-arm64.tar.gz
   pi-bolt-runtime-win32-x64.zip`.
4. Push the tag (or run `release` for it): it builds Pi on those runtimes.

Say in the release notes, for each runtime, where it was built, the engine commits it was built from (`.work/webkit` and
`.work/bun`, whose trees the patches make) and its SHA-256, so that it can be built again elsewhere and compared. (A runtime is
not yet built bit for bit the same twice; the prebuilt heap of a Pi build is checked that way: `-VerifyDeterminism`.)

## npm

The installer downloads the executable from npm when it can (`pi-bolt-<platform>-<variant>@X.Y.Z`, the release's `.tar.xz`, or on
Windows its `.zip`, in a package), a CDN that is fast where GitHub's downloads are slow, and from GitHub otherwise. The checksums
and their signature always come from the GitHub release.

The `pi-bolt` package works natively on Windows, as Bun's and Claude Code's packages do: its `bin` is `bin/pi-bolt.exe` on every
platform, a placeholder without a `#!` line (so npm's `pi-bolt.cmd` and `pi-bolt.ps1` start it directly), which its install
script, `npm/install.cjs`, replaces. On Windows that script downloads the build of the package's version from
`pi-bolt-win32-<variant>` on npm (or the GitHub release if that fails) and checks it as `install.ps1` does: the Ed25519 signature
of the release's `SHA256SUMS` by `keys/release.pub`, the version on its first line, the build's SHA-256. If a check fails it
installs nothing; otherwise it puts `pi-bolt.exe` and its files in the package's `bin` folder. On Linux and macOS it puts the `sh`
launcher there, and where install scripts are blocked the placeholder runs that launcher itself, as before. On Windows the
install script must run (with pnpm or Bun, allow it, or run `node "$(npm root -g)\pi-bolt\install.cjs"`). So the
`pi-bolt-win32-*` packages are published before `pi-bolt`, as `publish` does.

`publish` publishes the eight packages with npm's trusted publishing: npm trusts this repository's `publish.yml` through OpenID
Connect, so no token is stored in GitHub, and each version carries a provenance statement. The job runs in the `npm`
environment, which accepts only the `pi-bolt` branch and `bolt-v*` tags. To set it up once, logged in to npm with two-factor authentication:

```bash
for p in pi-bolt pi-bolt-linux-x64 pi-bolt-linux-x64-baseline pi-bolt-linux-x64-jit pi-bolt-darwin-arm64 pi-bolt-darwin-arm64-jit \
  pi-bolt-win32-x64 pi-bolt-win32-x64-jit; do
  npm trust github "$p" --file publish.yml --repo opensec-git/Pi-Bolt --env npm --allow-publish --yes
done
```

(or on npmjs.com: each package's Settings → Trusted publishing → GitHub Actions, `opensec-git` / `Pi-Bolt` / `publish.yml`,
environment `npm`). A package that does not exist yet (`pi-bolt-win32-x64` and `-jit` before their first release) is
published once by hand, as below, and then given the trust. Then set the repository variable `NPM_TRUSTED_PUBLISHING` to
`true` (Settings → Secrets and variables → Actions → Variables).

Until then the npm job is skipped, and the packages are published from the maintainer's machine after `publish`:

```bash
gh release download bolt-vX.Y.Z -R opensec-git/Pi-Bolt -p '*.tar.xz' -p '*.zip' -p SHA256SUMS -D dist/X.Y.Z --clobber
scripts/publish-npm-builds.sh dist/X.Y.Z
(cd npm && npm publish --access public)
```

npm answers a publish with "being processed": a new version can take a few minutes to appear.

## A new Pi version

`upstream` opens an issue for each new Pi release. A scheduled agent follows it every day: it merges the release on a branch,
records the profile, opens a pull request and merges it when `ci` passes, after which `tag`, `release` and `publish` take it out.
By hand:

1. Merge the tag into `pi-bolt` (`git fetch https://github.com/earendil-works/pi.git tag vX.Y.Z && git merge vX.Y.Z`). Where
   Pi-Bolt changed the same code, keep both: Pi-Bolt's changes are its commits on top of the previous Pi tag
   (`git log vX.Y.Z..pi-bolt -- packages/`).
2. `scripts/bump-version.sh --pi X.Y.Z` (the last number goes up by one, as for any release), `scripts/prepare-pi.sh --refresh-models`
   (the model catalog of the new Pi, `packages/ai/src/providers/data`), then `scripts/train-profile.sh` **on Linux**, and commit
   `profiles/pi-X.Y.Z` and the catalog. A profile recorded on Linux serves every platform. On Windows, `scripts\train-heap.ps1`
   then adds the prebuilt heap's order to it (`heap-functions.txt`, and the strings' order in `bytecode.order`): commit that too.
3. Push; `ci` builds and tests it, and it is released as above.

## The macOS builds

`release` builds them on GitHub's macOS runners (Apple silicon). The executables are signed ad hoc, as `bun build --compile`
signs them, which is what an install through `install.sh` or npm needs: neither quarantines what it downloads. A build downloaded
with a browser is quarantined, and Gatekeeper only accepts a Developer ID signature that is notarized. Notarization requires the
hardened runtime, under which the executable needs the entitlement `com.apple.security.cs.disable-library-validation` to map
its compiled code from its own file (and the `-jit` build `com.apple.security.cs.allow-jit`);
[ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port) says why.

## The Windows builds

`release` builds them on GitHub's Windows runners, on the Windows runtime: `scripts\package-release.ps1` makes
`pi-bolt-win32-x64.zip` (JIT off) and `pi-bolt-win32-x64-jit.zip` (JIT on, for extensions that do heavy JavaScript work at run
time: [PLUGINS.md](PLUGINS.md)). Each is built twice to check that its prebuilt heap does not depend on where the runtime was
loaded (`-VerifyDeterminism`); that check sometimes fails on one record of the heap (docs/RESUME.md), and `release` builds
again, up to three times, so that only a build whose two copies agreed is released. Each archive is then tested as unpacked:
`tests\pi\run.ps1`, `scripts\windows-selftest.ps1`, and `tests\runtime\run.ps1`.

`install.ps1` verifies the signature itself (it carries an Ed25519 verifier, since Windows has none), and refuses a Windows
release without one. The executables carry no Authenticode signature yet: SmartScreen may warn about one downloaded with a
browser (the installers do not mark what they download). An Authenticode signature, with the owner's certificate, would go on
`pi-bolt.exe` before the archive is made, as it changes the file and so its checksum. Never ask users to turn SmartScreen off or
to exclude Pi-Bolt from Defender.

## Signing

`SHA256SUMS` in every release is signed with an Ed25519 key. The installer carries the public key and refuses a download
whose signature is missing or does not verify. It checks it with OpenSSL 3 (on `PATH` or Homebrew's), or else with the Pi-Bolt
already installed; with neither (a first install on a Mac without OpenSSL 3), it checks the checksums alone and says so.
`install.ps1`, `scripts/fetch-runtime.sh` and the `publish` workflow require the signature too.

`publish` signs with the secret `PIBOLT_SIGNING_KEY` of the `release` environment. The environment accepts only workflows run
from `pi-bolt`, a secret cannot be read back from GitHub, and workflows of pull requests from forks get no secrets. `publish`
signs only a tag whose commit is on `pi-bolt`, and what runs in that job (the checks, `keys/release.pub`) is `pi-bolt`'s as the
run was started, not the tag's; the installers and the site it publishes are `pi-bolt`'s too. It signs only a `SHA256SUMS`
that lists exactly the files of a release, each checked, and only with the key of `keys/release.pub`. The check of the files
runs in a step of its own, without the key.

Two repository settings complete this, and are the owner's to set: a tag ruleset (Settings → Rules → Rulesets → New tag
ruleset) for `refs/tags/bolt-v*` that lets only administrators (or the `tag` workflow) create, move or delete such tags, and
required reviewers on the `release` environment (Settings → Environments → release), so that a signing run waits for one.

To set the key, as an administrator, on the machine that has it:

```bash
gh secret set PIBOLT_SIGNING_KEY -R opensec-git/Pi-Bolt --env release < pi-bolt-signing-key.pem
```

To make the key pair:

```bash
openssl genpkey -algorithm ed25519 -out pi-bolt-signing-key.pem
openssl pkey -in pi-bolt-signing-key.pem -pubout -out keys/release.pub
```

Then copy the contents of `keys/release.pub` into `RELEASE_KEY` in `install.sh`, and its base64 line into `$PiBoltReleaseKey` in
`install.ps1`, commit them, set the secret as above, and keep a copy of `pi-bolt-signing-key.pem` somewhere safe and private (a
password manager), never in the repository. Rotating the key is the same procedure; releases signed with the old key stay
verifiable with an installer that carries the old key.
