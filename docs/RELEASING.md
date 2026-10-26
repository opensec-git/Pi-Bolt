# Releasing Pi-Bolt

Six workflows, all on GitHub's own runners (Ubuntu 24.04 x86-64 and ARM64, Windows Server 2025, macOS 15 on Apple silicon):

| Workflow | When | What |
|---|---|---|
| `ci` | every push to `pi-bolt`, every pull request | lint and types (the workflows too, with actionlint), Pi's tests, Pi-Bolt built and tested on Linux x86-64, Linux ARM64, macOS (Apple silicon: its tests and its comparison with stock Pi side by side) and Windows x64, the installer's checks, whether the engine patches apply. A pull request that changes only documentation builds and tests nothing |
| `tag` | `ci` passed on a push to `pi-bolt` | when `VERSION` has no `bolt-vX.Y.Z` tag yet, tags that commit and runs `release` |
| `release` | a `bolt-vX.Y.Z` tag | checks the runtimes' attestations, builds the 20 archives on Linux (x86-64 and ARM64), macOS and Windows, tests each as unpacked from its archive, attests each, makes a **draft** release with `SHA256SUMS`, `extensions.txt` and `RUNTIME_STAMP`, and runs `publish` |
| `publish` | after `release`, or by hand | signs `SHA256SUMS`, checks the signature and the checksums, publishes the release, puts `install.sh`, `install.ps1` and the site on `gh-pages`, publishes npm, and updates the Homebrew formula |
| `runtime` | by hand, when the engine changed | builds the four runtimes, each on its platform's runner, attests them, and attaches them to the tag's draft ([below](#when-the-engine-changed)) |
| `upstream` | daily | opens an issue when Pi has a new release, with the files where merging it conflicts |

`tag` and the hand-over from `release` to `publish` run only when the repository variable `AUTO_RELEASE` is `true`. The
release key is a secret of the `release` environment, used only by `publish` run from `pi-bolt` ([Signing](#signing)).

- [A release, step by step](#a-release-step-by-step)
- [What a release holds](#what-a-release-holds)
- [When the engine changed](#when-the-engine-changed)
- [Provenance](#provenance)
- [npm](#npm)
- [Homebrew](#homebrew)
- [Scoop](#scoop)
- [mise](#mise)
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
   `npm install -g pi-bolt`, `brew install opensec-git/tap/pi-bolt` (macOS or Linux; `brew upgrade pi-bolt` where it is
   installed), and on Windows `irm https://pi-bolt.opensec.in/install.ps1 | iex` (PowerShell) and `powershell -c "irm https://pi-bolt.opensec.in/install.ps1 | iex"` (Command Prompt).
   The Scoop bucket takes the release by itself within four hours, and mise once the release is older than mise's
   `minimum_release_age` ([Scoop](#scoop), [mise](#mise)).

By hand (with `AUTO_RELEASE` unset, or to redo a step): push the tag yourself
(`git tag -a bolt-vX.Y.Z -m "Pi-Bolt X.Y.Z" && git push origin bolt-vX.Y.Z`), then, once the draft is there, run `publish` from
`pi-bolt`: `gh workflow run publish.yml -R opensec-git/Pi-Bolt -f tag=bolt-vX.Y.Z`. A draft can also be signed on a machine that
has the key, before `publish`:

```bash
gh release download bolt-vX.Y.Z -R opensec-git/Pi-Bolt -p SHA256SUMS -D dist/X.Y.Z --clobber
scripts/sign-release.sh --key pi-bolt-signing-key.pem dist/X.Y.Z
gh release upload bolt-vX.Y.Z -R opensec-git/Pi-Bolt dist/X.Y.Z/SHA256SUMS.sig dist/X.Y.Z/SHA256SUMS.sshsig
```

`publish` refuses a draft whose signature does not verify against `keys/release.pub`. Either workflow can be run again with the
same tag: the draft's files are replaced (a signature of the old `SHA256SUMS` is dropped), and a published release is left as it is.
`release` can also be run with `dry_run` (from the Actions tab, with the tag): it builds and tests everything and makes no draft.

### A pre-release first

A release can be staged before it is made the latest: `publish` with `-f prerelease=true` publishes it as a GitHub pre-release.
Only the beta channel takes it (`pi-bolt update --channel beta`, or `PIBOLT_CHANNEL=beta` for the installers); its builds go to
npm under the dist-tag `beta`, and the `pi-bolt` package, the installers, the site and the Homebrew formula are left as they are. Running `publish`
again for the same tag without it makes the release the latest and publishes the `pi-bolt` package.

```bash
gh workflow run publish.yml -R opensec-git/Pi-Bolt -f tag=bolt-vX.Y.Z -f prerelease=true
gh workflow run publish.yml -R opensec-git/Pi-Bolt -f tag=bolt-vX.Y.Z      # later: the latest release
```

A pre-release is a version like any other: one that needs a fix is followed by the next version, not replaced.

Old-distribution and old-CPU checks (`tests/compat/run.sh`: CentOS 7, Debian 9, Amazon Linux 2, emulated Haswell to Nehalem)
need bubblewrap and qemu-user, which GitHub's runners do not allow; run them on a Linux machine before a release that changes
the engine or the build. On Windows, the same for older CPUs is Intel SDE (`-snb`: no AVX2, the bytecode is used; `-hsw`).

## What a release holds

- The archives: `pi-bolt-linux-x64`, `-baseline` and `-jit`, `pi-bolt-linux-arm64` and `-jit`, and `pi-bolt-darwin-arm64` and
  `-jit` (`.tar.xz` and `.tar.gz`); `pi-bolt-win32-x64` and `-jit` (`.zip`); the runtimes `pi-bolt-runtime-linux-x64.tar.gz`,
  `pi-bolt-runtime-linux-arm64.tar.gz`, `pi-bolt-runtime-darwin-arm64.tar.gz` and `pi-bolt-runtime-win32-x64.zip`.
- The compile kits `pi-bolt-kit-linux-x64.tar.gz`, `pi-bolt-kit-linux-arm64.tar.gz` and `pi-bolt-kit-darwin-arm64.tar.gz`: the
  files of Pi each platform's default build read, its compile command and the training profile (`scripts/make-kit.mjs`, made
  by `scripts/build-pi.sh --kit` in `package-release.sh`). `pi-bolt compile` builds with them and the runtimes.
- `extensions.txt`: the optional extensions the installers offer (OpenSec's `opensec-pi-subagents`, `opensec-pi-todo` and `opensec-pi-fff`), each
  with its version and npm integrity. The installers install those versions and only if the registry's tarball has that
  integrity; a release without the file pins none, and they offer nothing. Update it in the repository when either has a new
  release.
- `RUNTIME_STAMP`: what engine the runtimes were built from (below).
- `install.sh` and `install.ps1`, as they are in the tagged commit. `pi-bolt update` runs the release's own installer, and only
  once `SHA256SUMS` verifies with the release key built into the executable and lists the installer's checksum. A release up to
  1.0.1 does not list its installers: updating to one runs the installer the site serves, with a notice.
- `SHA256SUMS`: its first line is `# pi-bolt X.Y.Z`, then one line for each archive, `extensions.txt`, `RUNTIME_STAMP`,
  `install.sh` and `install.ps1`.
  `install.ps1` and the npm package's Windows install refuse checksums whose first line is not their version: an older release,
  signed all the same, served as a newer one.
- `SHA256SUMS.sig` ([Signing](#signing)).

`scripts/release-files.txt` names the files of a release. `release` and `publish` check that `SHA256SUMS` lists exactly those,
each as it is, and that `extensions.txt` pins at least one extension and has no line the installers could not read
(`scripts/check-release-files.sh`). A new platform or variant is added to that list.

## When the engine changed

`release` uses the previous release's runtimes when the engine is the same: `RUNTIME_STAMP` is the hash of the engine entries of
`sources.json`, the patches, the runtime build scripts and the linker-order hints they link with (`profiles/runtime-*.hints`)
(`scripts/runtime-stamp.sh`). `ci` says when it differs. The previous release's stamp counts only as its signed `SHA256SUMS`
gives it, for that release's version (releases before 0.8.0 did not sign it, so their runtimes are not reused). Runtimes
uploaded to the draft are hashed by `release`'s first job and checked again by each build job before it uses one.

When it changed, the four runtimes are built before tagging, by the `runtime` workflow, from `pi-bolt` once the change is
merged:

```bash
gh workflow run runtime.yml -R opensec-git/Pi-Bolt -f tag=bolt-vX.Y.Z
```

It builds each runtime on its platform's runner (Linux x86-64 and ARM64, macOS, Windows), runs `tests/aot` and `tests/runtime`
on it, attests the four archives ([Provenance](#provenance)) and attaches them to the tag's draft release (making the draft if
there is none). The runners are smaller than
the machine [BUILDING.md](BUILDING.md) asks for, so the build runs with swap and four jobs, for two to four hours. A branch
`runtime/<name>` builds its commit's runtimes the same way, as workflow artifacts, to try an engine change. Then push the tag (or
run `release` for it): it builds Pi on those runtimes.

By hand, on each platform: `scripts/toolchain/make-sysroot.sh` once (Linux), `scripts/fetch-sources.sh` and
`scripts/build-runtime.sh` ([BUILDING.md](BUILDING.md); Linux ARM64 can be the Mac's Docker, [Linux ARM64 in a
container](BUILDING.md#linux-arm64-in-a-container)), on Windows `scripts\fetch-sources.ps1` and `scripts\build-runtime.ps1`
([WINDOWS.md](WINDOWS.md)); then `scripts/package-release.sh --runtime-only` (Windows: `scripts\package-release.ps1
-RuntimeOnly`), and upload `dist/X.Y.Z/pi-bolt-runtime-<platform>.tar.gz` (Windows: `.zip`) to a draft of the tag:
`gh release create bolt-vX.Y.Z --draft --title "Pi-Bolt X.Y.Z" --notes ""`, then `gh release upload bolt-vX.Y.Z <archive>`.
A runtime built by hand has no attestation: `release` takes it only once its SHA-256 is in the list of the exception
([Provenance](#provenance)), added in a reviewed commit.
`fetch-sources` stops when `.work/webkit` or `.work/bun` is not the pinned commit with the current patch applied (sources of an
earlier engine, or a fetch that stopped halfway): delete that folder and run it again.

Say in the release notes, for each runtime, where it was built, the engine commits it was built from (`.work/webkit` and
`.work/bun`, whose trees the patches make) and its SHA-256, so that it can be built again elsewhere and compared. (A runtime is
not yet built bit for bit the same twice; the prebuilt heap of a Pi build is checked that way: `-VerifyDeterminism`.)

## Provenance

Every archive of a release has a build provenance attestation (`actions/attest-build-provenance`): a statement of which
workflow run built it, from which commit, signed with that run's GitHub identity through Sigstore and kept in the repository's
[attestations](https://github.com/opensec-git/Pi-Bolt/attestations). `runtime` attests the runtimes it builds; `release`'s build
jobs attest every archive they make, dry runs included. It is a second check beside the signature of `SHA256SUMS`: the signature
says the release key's holder approved a file, and the attestation says which run made it. Releases up to 1.0.1 have no
attestations.

To check a downloaded file (GitHub CLI 2.49 or later):

```bash
gh attestation verify pi-bolt-linux-x64.tar.xz -R opensec-git/Pi-Bolt
# Stricter: made by the release workflow, run from pi-bolt (the tag workflow starts it there) or from the release's tag.
gh attestation verify pi-bolt-linux-x64.tar.xz -R opensec-git/Pi-Bolt \
  --signer-workflow opensec-git/Pi-Bolt/.github/workflows/release.yml --source-ref refs/heads/pi-bolt
```

A runtime that `runtime` built and that is still on a draft is checked with
`--signer-workflow opensec-git/Pi-Bolt/.github/workflows/runtime.yml --source-ref refs/heads/pi-bolt`. Releases 1.0.2 and
1.0.3 were built on Blacksmith's runners, which GitHub counts as self-hosted, so `--deny-self-hosted-runners` fails on their
attestations; later releases are built on GitHub's runners again. An
attestation names the commit its run started from: `release` builds the tag's commit, and `runtime` run with `-f ref=...`
builds that commit, which the run's inputs give.

`release`'s first job checks each runtime the build will use before anything is built, and stops the release when one does not
verify:

- runtimes uploaded to the draft: attested by `runtime.yml` run from `pi-bolt`. A run from another branch (a `runtime/<name>`
  trial) does not count.
- the previous release's runtimes, used again: first checked against that release's signed `SHA256SUMS`. Each release packages
  the runtime again (a new `.tar.gz` or `.zip`, so a new SHA-256), so these are attested by that release's run of `release.yml`,
  from `pi-bolt` or from its tag (`runtime.yml` from `pi-bolt` counts too).

The exception: runtimes released before attestations existed have none. `release.yml` lists the SHA-256 of each one it accepts
without an attestation, in its "Runtime provenance" step. Each one it accepts gets a warning in the run and a line in its summary,
so nothing is skipped silently. The list holds only 1.0.1's four runtimes, the ones the next release reuses. Their `bun`
executables are 1.0.0's, and those are byte for byte the ones run
[39963518293](https://github.com/opensec-git/Pi-Bolt/actions/runs/39963518293) of `runtime` built (branch `runtime/v1.0.0b`,
commit `ca41028c`):

| Runtime of 1.0.1 | SHA-256 |
|---|---|
| `pi-bolt-runtime-linux-x64.tar.gz` | `9978b1d5128799c09fcf09f7802e31ac0117c5ac3de73e56bdbaafec8f0d2eb3` |
| its `bun` | `fe95fb4e64a1ac36b1689eba5d76baf26404174d46f3faec3ad6096402e07d2c` |
| `pi-bolt-runtime-linux-arm64.tar.gz` | `c9cc01452b1ea5194a8d53767b506c32dc16ac505ad254dfa3acb7e365b1056b` |
| its `bun` | `67b81894b0ed2c945556342ce47800ad51ce828ea404af8b62c92e4acbeae772` |
| `pi-bolt-runtime-darwin-arm64.tar.gz` | `270d802270477e46ba80ee676a9fe371772b0f4eed2aaf92f37a92a6c4b132ab` |
| its `bun` | `f04a349113682de46b9aefa7786f92e588c85a4918941fd6b1dc8c90d1d6fe8b` |
| `pi-bolt-runtime-win32-x64.zip` | `baafcdd97d363f4addf3ade6a9fdae41baeb1a4e08f16b90ee40df091ee486e4` |
| its `bun.exe` | `1457c97b5a0f236e880daa1bd203aaa10933d052a66f1c13d6ea92cf63c3d79c` |

The release after 1.0.1 attests its own copies of those runtimes, and the one after that verifies those copies. Then the list can
be emptied. A runtime is added to it only in a reviewed commit that says where it was built.

## npm

The installer downloads the executable from npm when it can (`pi-bolt-<platform>-<variant>@X.Y.Z`, the release's `.tar.xz`, or on
Windows its `.zip` in `@kushalkhemka/pi-bolt-win32-<variant>@X.Y.Z`, as npm refuses the unscoped Windows names), a CDN that is
fast where GitHub's downloads are slow, and from GitHub otherwise. The checksums and their signature always come from the GitHub
release.

The `pi-bolt` package works natively on Windows, as Bun's and Claude Code's packages do: its `bin` is `bin/pi-bolt.exe` on every
platform, a placeholder without a `#!` line (so npm's `pi-bolt.cmd` and `pi-bolt.ps1` start it directly), which its install
script, `npm/install.cjs`, replaces. On Windows that script downloads the build of the package's version from
`@kushalkhemka/pi-bolt-win32-<variant>` on npm (or the GitHub release if that fails) and checks it as `install.ps1` does: the
Ed25519 signature of the release's `SHA256SUMS` by `keys/release.pub`, the version on its first line, the build's SHA-256. If a check fails it
installs nothing; otherwise it puts `pi-bolt.exe` and its files in the package's `bin` folder. On Linux and macOS it puts the `sh`
launcher there, and where install scripts are blocked the placeholder runs that launcher itself, as before. On Windows the
install script must run (with pnpm or Bun, allow it, or run `node "$(npm root -g)\pi-bolt\install.cjs"`). So the
`@kushalkhemka/pi-bolt-win32-*` packages are published before `pi-bolt`, as `publish` does. A build npm does not publish there
is a warning, not a failure: the installers download it from GitHub, and `pi-bolt` is still published.

`publish` publishes the ten packages with npm's trusted publishing: npm trusts this repository's `publish.yml` through OpenID
Connect, so no token is stored in GitHub, and each version carries a provenance statement. The job runs in the `npm`
environment, which accepts only the `pi-bolt` branch and `bolt-v*` tags. To set it up once, logged in to npm with two-factor authentication:

```bash
for p in pi-bolt pi-bolt-linux-x64 pi-bolt-linux-x64-baseline pi-bolt-linux-x64-jit pi-bolt-linux-arm64 pi-bolt-linux-arm64-jit \
  pi-bolt-darwin-arm64 pi-bolt-darwin-arm64-jit @kushalkhemka/pi-bolt-win32-x64 @kushalkhemka/pi-bolt-win32-x64-jit; do
  npm trust github "$p" --file publish.yml --repo opensec-git/Pi-Bolt --env npm --allow-publish --yes
done
```

(or on npmjs.com: each package's Settings → Trusted publishing → GitHub Actions, `opensec-git` / `Pi-Bolt` / `publish.yml`,
environment `npm`). A package that does not exist yet (`pi-bolt-linux-arm64` and `-jit` before their first release) is
published once by hand, as below, and then given the trust. Then set the repository variable `NPM_TRUSTED_PUBLISHING` to
`true` (Settings → Secrets and variables → Actions → Variables).

Until then the npm job is skipped, and the packages are published from the maintainer's machine after `publish`:

```bash
gh release download bolt-vX.Y.Z -R opensec-git/Pi-Bolt -p '*.tar.xz' -p '*.zip' -p SHA256SUMS -D dist/X.Y.Z --clobber
scripts/publish-npm-builds.sh dist/X.Y.Z
(cd npm && npm publish --access public)
```

npm answers a publish with "being processed": a new version can take a few minutes to appear.

## Homebrew

`brew install opensec-git/tap/pi-bolt` installs the formula `Formula/pi-bolt.rb` of
[opensec-git/homebrew-tap](https://github.com/opensec-git/homebrew-tap): the default build of the latest release for macOS on
Apple silicon, Linux x86-64 and Linux ARM64 (the `.tar.gz` archives), unpacked into the keg's `libexec` with `pi-bolt` linked to
`pi`. Intel Macs are refused by the formula's `arm64` requirement. A Pi-Bolt whose executable is in a Homebrew Cellar
(`.../Cellar/pi-bolt/...`) knows it: `pi-bolt update` says to run `brew upgrade pi-bolt`, and `pi-bolt compile` and
`pi-bolt update --rollback` refuse.

`publish`'s last job, `homebrew`, points the formula at the release once the release and npm are done (not for a
pre-release): it checks the release's `SHA256SUMS` against `keys/release.pub`, rewrites the formula's version, URLs and
checksums with `scripts/update-homebrew-formula.mjs`, and pushes the commit `pi-bolt X.Y.Z` to the tap as OpenSec. It needs the
repository secret `HOMEBREW_TAP_TOKEN`; without it the job prints a notice and does nothing, and the release is not affected.
To create the token:

1. GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token.
2. Resource owner: `opensec-git`. Expiration: one year (set a reminder to rotate it).
3. Repository access: Only select repositories → `opensec-git/homebrew-tap`.
4. Permissions → Repository permissions → Contents: Read and write (Metadata: Read-only is added with it). Nothing else.
5. If the organization requires approval for fine-grained tokens, an owner approves it under the organization's Settings →
   Personal access tokens → Pending requests.
6. opensec-git/Pi-Bolt → Settings → Secrets and variables → Actions → New repository secret, named `HOMEBREW_TAP_TOKEN`. (A
   repository secret, not one of the `release` environment: a job in that environment waits for its reviewers.)

By hand, from a clone of the tap: `node scripts/update-homebrew-formula.mjs Formula/pi-bolt.rb SHA256SUMS X.Y.Z` with the
release's `SHA256SUMS`, then `brew style Formula/pi-bolt.rb`, commit and push.

## Scoop

`scoop install opensec-git/pi-bolt` (after `scoop bucket add opensec-git https://github.com/opensec-git/scoop-bucket`) installs
the manifest `bucket/pi-bolt.json` of [opensec-git/scoop-bucket](https://github.com/opensec-git/scoop-bucket): the default
Windows build of the latest release (`pi-bolt-win32-x64.zip`), unpacked into `<scoop>\apps\pi-bolt\<version>` with a shim for
`pi-bolt.exe`. A Pi-Bolt whose executable is there (`...\scoop\apps\pi-bolt\...`, or under `$SCOOP` or `$SCOOP_GLOBAL`)
knows it: `pi-bolt update` says to run `scoop update pi-bolt`, and `pi-bolt update --rollback` to run
`scoop reset pi-bolt@<version>`.

Nothing in Pi-Bolt's workflows touches the bucket. Its Excavator workflow (from Scoop's bucket template) runs every four hours:
it reads the latest release's `bolt-vX.Y.Z` tag, takes the archive's checksum from the release's `SHA256SUMS`, and commits the
new manifest. Its Install workflow installs the manifest with Scoop on a Windows runner and runs `pi-bolt --version` on every
push, every pull request and once a day. A pre-release is not taken (the tag comes from the latest release). To update it by
hand, from a clone of the bucket on Windows with Scoop installed: `.\bin\checkver.ps1 pi-bolt -Update`, then commit and push.

## mise

mise installs Pi-Bolt from the GitHub release with its `github` backend; nothing is published for it. The options (in
[docs-site/pages/installation.html](../docs-site/pages/installation.html)) pick the default build's archive per platform,
strip the `bolt-v` tag prefix, rename `pi` to `pi-bolt` and put only `pi-bolt` on PATH. mise takes a release once it is older
than its `minimum_release_age` setting. A Pi-Bolt in mise's installs folder (`.../mise/installs/<tool>/...`, or under
`$MISE_DATA_DIR/installs`) knows it: `pi-bolt update` says to run `mise upgrade --bump github:opensec-git/Pi-Bolt`
(`mise upgrade --bump pi-bolt` once mise's registry has the shorthand), and `pi-bolt update --rollback` to run
`mise use -g github:opensec-git/Pi-Bolt@<version>`. If a release renames its archives, update the options in the docs.

## A new Pi version

`upstream` opens an issue for each new Pi release. A scheduled agent follows it every day: it merges the release on a branch,
records the profile, opens a pull request and merges it when `ci` passes, after which `tag`, `release` and `publish` take it out.
By hand:

1. Merge the tag into `pi-bolt` (`git fetch https://github.com/earendil-works/pi.git tag vX.Y.Z && git merge vX.Y.Z`). Where
   Pi-Bolt changed the same code, keep both: Pi-Bolt's changes are its commits on top of the previous Pi tag
   (`git log vX.Y.Z..pi-bolt -- packages/`).
2. `scripts/bump-version.sh --pi X.Y.Z` (the last number goes up by one, as for any release), `scripts/prepare-pi.sh --refresh-models`
   (the model catalog of the new Pi, `packages/ai/src/providers/data`), then `scripts/train-profile.sh` **on Linux**, and commit
   `profiles/pi-X.Y.Z` and the catalog. A profile recorded on Linux serves every platform (recorded on macOS first with
   `--out DIR` and merged in on Linux with `--with DIR`, it also places what only macOS runs). On Windows, `scripts\train-heap.ps1`
   then adds the prebuilt heap's order to it (`heap-functions.txt`, and the strings' order in `bytecode.order`): commit that too.
   The `heap-profile` workflow runs it on the Windows runner with the latest release's runtime
   (`gh workflow run heap-profile.yml -R opensec-git/Pi-Bolt --ref <branch>`), uploads the profile as an artifact and pushes it to
   the branch `heap-profile/pi-X.Y.Z`; open a pull request from that branch by hand.
3. Push; `ci` builds and tests it, and it is released as above.

## The macOS builds

`release` builds them on the macOS 15 runners (Apple silicon), for macOS 13 and later: `scripts/check-macos-floor.sh` checks that
no executable or library asks for a later macOS. The executables are signed ad hoc, as `bun build --compile`
signs them, which is what an install through `install.sh` or npm needs: neither quarantines what it downloads. A build downloaded
with a browser is quarantined, and Gatekeeper only accepts a Developer ID signature that is notarized. Notarization requires the
hardened runtime, under which the executable needs the entitlement `com.apple.security.cs.disable-library-validation` to map
its compiled code from its own file (and the `-jit` build `com.apple.security.cs.allow-jit`);
[ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port) says why.

## The Windows builds

`release` builds them on the Windows runners, on the Windows runtime: `scripts\package-release.ps1` makes
`pi-bolt-win32-x64.zip` (JIT off) and `pi-bolt-win32-x64-jit.zip` (JIT on, for extensions that do heavy JavaScript work at run
time: [PLUGINS.md](PLUGINS.md)). Each is built twice to check that its prebuilt heap does not depend on where the runtime was
loaded (`-VerifyDeterminism`); that check sometimes fails on one record of the heap ([WINDOWS.md](WINDOWS.md#the-design)), and `release` builds
again, up to three times, so that only a build whose two copies agreed is released. Each archive is then tested as unpacked:
`tests\pi\run.ps1`, `scripts\windows-selftest.ps1`, and `tests\runtime\run.ps1`.

`install.ps1` verifies the signature itself (it carries an Ed25519 verifier, since Windows has none), and refuses a Windows
release without one. The executables carry no Authenticode signature yet: SmartScreen may warn about one downloaded with a
browser (the installers do not mark what they download). An Authenticode signature, with the owner's certificate, would go on
`pi-bolt.exe` before the archive is made, as it changes the file and so its checksum. Never ask users to turn SmartScreen off or
to exclude Pi-Bolt from Defender.

## Signing

`SHA256SUMS` in every release is signed with an Ed25519 key, twice: `SHA256SUMS.sig` (raw Ed25519) and, for releases after
0.9.9, `SHA256SUMS.sshsig` (the same key in OpenSSH's signature format, namespace `pi-bolt-release`, made by
`scripts/lib/sshsig.mjs`). The installer carries the public key and refuses a download whose signature is missing or does not
verify. It checks it with OpenSSL 3 (on `PATH` or Homebrew's), or else with `ssh-keygen -Y verify` against `SHA256SUMS.sshsig`
(OpenSSH 8.1 or later, which every current Mac has), or else with the Pi-Bolt already installed (which must print the SHA-256 of
the checksums it verified; an exit status alone does not count). Where none of them can check it, and for a release from before
0.6.0, which has no signature, nothing is installed unless `PIBOLT_UNVERIFIED` is the SHA-256 of that release's `SHA256SUMS`
(the installer shows it): the value matches one download only, so one left in a profile lets nothing else in, and the npm
package's first run and `pi-bolt update` never pass it on. `install.ps1` and the npm package's install script take
`PIBOLT_ALLOW_UNSIGNED` (a build of one's own) the same way.
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

Then copy the contents of `keys/release.pub` into `RELEASE_KEY` in `install.sh`, the output of
`node scripts/lib/sshsig.mjs pubkey keys/release.pub` into `RELEASE_SSH_KEY` in `install.sh`, and its base64 line into `$PiBoltReleaseKey` in
`install.ps1`, commit them, set the secret as above, and keep a copy of `pi-bolt-signing-key.pem` somewhere safe and private (a
password manager), never in the repository. Rotating the key is the same procedure; releases signed with the old key stay
verifiable with an installer that carries the old key.
