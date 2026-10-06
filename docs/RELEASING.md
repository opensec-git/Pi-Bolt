# Releasing Pi-Bolt

Releases are made by the `release` workflow when a `bolt-vX.Y.Z` tag is pushed. It builds the runtime if the engine changed,
builds and tests Pi, packages, signs, publishes the GitHub release, updates the installer site and publishes the npm package.
The `upstream` workflow watches Pi's releases and opens a pull request for each new one.

- [The self-hosted runner](#the-self-hosted-runner)
- [Secrets](#secrets)
- [A release, step by step](#a-release-step-by-step)
- [A new Pi version](#a-new-pi-version)
- [Signing](#signing)

## The self-hosted runner

Building the runtime (patched WebKit and Bun) needs about 32 GB of RAM, 40 GB of disk and an hour on 16 cores, more than
GitHub's standard runners have, so the release workflow runs on a self-hosted runner with the labels `linux`, `x64` and
`pi-bolt`. Any Linux x86-64 machine that can run [`scripts/build-runtime.sh`](BUILDING.md#building-the-runtime) will do.

1. In the repository, Settings → Actions → Runners → New self-hosted runner, and follow GitHub's instructions. When it asks
   for labels, add `pi-bolt`.
2. Install the build requirements from [BUILDING.md](BUILDING.md), and make the glibc 2.17 sysroot once:
   `scripts/toolchain/make-sysroot.sh` in the runner's checkout (it is kept between runs).
3. Run the runner as a service (`./svc.sh install && ./svc.sh start`).

The `ci` workflow (lint, build Pi with the released runtime, tests) runs on GitHub's runners and needs nothing.

GitHub Actions must be allowed for the repository in the organization's settings (Settings → Actions → General).

## Secrets

| Secret | What |
|---|---|
| `PIBOLT_SIGNING_KEY` | The Ed25519 private key that signs `SHA256SUMS` (see [Signing](#signing)) |
| `NPM_TOKEN` | A granular npm access token with publish rights on `pi-bolt`, `pi-bolt-linux-x64`, `pi-bolt-linux-x64-baseline`, `pi-bolt-linux-x64-jit`, `pi-bolt-darwin-arm64` and `pi-bolt-darwin-arm64-jit`, so `npm publish --provenance` can run |

## A release, step by step

1. Write the release notes in `docs/releases/X.Y.Z.md` (the workflow uses them as the GitHub release's text).
2. `scripts/bump-version.sh --version X.Y.Z` (or without `--version`: a patch bump), commit.
3. Push, and wait for `ci` to pass.
4. `git tag -a bolt-vX.Y.Z -m "Pi-Bolt X.Y.Z" && git push origin bolt-vX.Y.Z`.
5. The `release` workflow does the rest. If it fails after the GitHub release was created, fix the cause and run it again from
   the Actions tab with the tag as input: every step is safe to repeat.

To release by hand instead, run the same steps as the workflow: `scripts/package-release.sh`, `tests/aot/run.sh`, the
end-to-end checks, `scripts/sign-release.sh --key ... dist/X.Y.Z`, `gh release create`, `scripts/publish-installer.sh`,
`scripts/publish-npm-builds.sh dist/X.Y.Z` and `npm publish --access public` in `npm/`.

The installer downloads the executable from npm (`pi-bolt-<platform>-<variant>@X.Y.Z`, the release's `.tar.xz` in a package), a
CDN that is fast where GitHub's release downloads are slow, and from GitHub if npm does not have it or the download fails.
The checksums always come from the GitHub release. Publish the npm builds before announcing a release.

The `pi-bolt` package works natively on Windows, as Bun's and Claude Code's packages do: its `bin` is `bin/pi-bolt.exe` on every
platform, a placeholder without a `#!` line (so npm's `pi-bolt.cmd` and `pi-bolt.ps1` start it directly), which its install
script, `npm/install.cjs`, replaces. On Windows that script downloads the build of the package's version from
`pi-bolt-win32-<variant>` on npm (or the GitHub release if that fails) and checks it as `install.ps1` does: the Ed25519 signature
of the release's `SHA256SUMS` by `keys/release.pub`, the version on its first line, the build's SHA-256. If a check fails it
installs nothing; otherwise it puts `pi-bolt.exe` and its files in the package's `bin` folder. On Linux and macOS it puts the `sh`
launcher there, and where install scripts are blocked the placeholder runs that launcher itself, as before. On Windows the
install script must run (with pnpm or Bun, allow it, or run `node "$(npm root -g)\pi-bolt\install.cjs"`). So a Windows release
needs `pi-bolt-win32-x64` (and its variants) published before `pi-bolt`, and `NPM_TOKEN` needs publish rights on them.

## The macOS builds

The release workflow builds on the Linux runner. The macOS archives (`pi-bolt-darwin-arm64`, `pi-bolt-darwin-arm64-jit` and
`pi-bolt-runtime-darwin-arm64`) are built on a Mac with Apple silicon: by hand, or on a self-hosted runner with the labels `macos`,
`arm64` and `pi-bolt`. The same scripts do it there:

1. `scripts/fetch-sources.sh` and `scripts/build-runtime.sh` (Xcode's SDK, macOS 13 and later; [BUILDING.md](BUILDING.md)).
2. `scripts/prepare-pi.sh`, then `scripts/package-release.sh`: the two builds, the runtime and their `SHA256SUMS` in `dist/X.Y.Z`.
3. The checks, as on Linux: `tests/aot/run.sh`, `tests/pi/run.sh`, `tests/runtime/run.sh`, and the end-to-end checks against
   `scripts/build-pi.sh --stable --out out/pi-stable`.
4. Put the archives of every platform and `extensions.txt` in one folder, and make one `SHA256SUMS` over all of them, whose first
   line says the version (`install.ps1` refuses checksums of another version: an older release, signed all the same, served as
   a newer one):
   `{ echo "# pi-bolt X.Y.Z"; sha256sum -- *.tar.gz *.tar.xz *.zip extensions.txt; } >SHA256SUMS` (or `shasum -a 256`). Then
   `scripts/sign-release.sh`: one signature for the release. `extensions.txt` pins the optional extensions the installers offer
   (each version and its npm integrity); update it when one of them has a new release.

## The Windows build

`pi-bolt-win32-x64.zip` and `pi-bolt-runtime-win32-x64.zip` are built on Windows x64 ([WINDOWS.md](WINDOWS.md)):
`scripts\fetch-sources.ps1`, `scripts\build-runtime.ps1`, then `scripts\package-release.ps1`, which builds Pi twice to check
that the prebuilt heap does not depend on where the runtime was loaded (`-VerifyDeterminism`). The archives join the others
in step 4 above. `install.ps1` verifies the signature itself (it carries an Ed25519 verifier, since Windows has none), and refuses
a Windows release without one. If the executable is to carry an Authenticode signature, it is signed before the archive is made,
with the owner's certificate. Publish `install.ps1` next to `install.sh`.

The executables are signed ad hoc, as `bun build --compile` signs them, which is what an install through `install.sh` or npm needs:
neither quarantines what it downloads. A build downloaded with a browser is quarantined, and Gatekeeper only accepts a Developer
ID signature that is notarized. Notarization requires the hardened runtime, under which the executable needs the entitlement
`com.apple.security.cs.disable-library-validation` to map its compiled code from its own file (and the `-jit` build
`com.apple.security.cs.allow-jit`); [ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port) says why.

## A new Pi version

The `upstream` workflow runs daily. When Pi has a new release it opens a pull request that:

- merges Pi's tag into a branch `upstream/vX.Y.Z` (a conflict is left to be resolved by hand, and said in the pull request);
- bumps Pi-Bolt's minor version and `sources.json` with `scripts/bump-version.sh --pi X.Y.Z`;
- records a training profile with `scripts/train-profile.sh` and commits `profiles/pi-X.Y.Z`;
- builds and tests, on the self-hosted runner.

Review it, merge it, and push the tag.

## Signing

`SHA256SUMS` in every release is signed with an Ed25519 key. The installer carries the public key and refuses a download
whose signature does not verify (when `openssl` is installed; otherwise it checks the checksums alone). To make the key pair:

```bash
openssl genpkey -algorithm ed25519 -out pi-bolt-signing-key.pem
openssl pkey -in pi-bolt-signing-key.pem -pubout -out keys/release.pub
gh secret set PIBOLT_SIGNING_KEY --repo opensec-git/Pi-Bolt < pi-bolt-signing-key.pem
```

Then copy the contents of `keys/release.pub` into `RELEASE_KEY` in `install.sh`, and its base64 line into `$PiBoltReleaseKey` in
`install.ps1`, commit them, and keep
`pi-bolt-signing-key.pem` somewhere safe and private (a password manager). Rotating the key is the same procedure; releases
signed with the old key stay verifiable with an installer that carries the old key.
