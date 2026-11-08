#!/bin/sh
# Installs in an Alpine container (as root) what the musl builds and tests need: what Pi-Bolt's scripts and tests run, stock Bun
# (the musl build, the reference tests/aot compares with), and with --build what scripts/build-runtime-musl.sh builds the
# runtime with: clang, lld and LLVM 23 from Alpine's edge (Alpine's releases stop at an older LLVM; `@edge` is a tagged
# repository, so apk takes only those from it and musl and libstdc++ stay the release's, as in Bun's own Alpine images), and
# Rust. Not Alpine's ICU: build-runtime-musl.sh --icu builds its own.
# Usage: scripts/alpine-tools.sh [--build]       (.github/workflows: runtime.yml, release.yml, ci.yml)
set -eu
BUN_VERSION=1.4.2
BUN_SHA256=4835eca59d6da70f4674f5642f6e459dcadab773695b2ed9922d131057989742 # bun-linux-x64-musl.zip
[ "$(uname -m)" = x86_64 ] || { echo "x86-64 only" >&2; exit 1; }
packages="bash ca-certificates coreutils curl findutils git gzip libgcc libstdc++ nodejs npm perl python3 rsync tar tmux unzip xz"
if [ "${1:-}" = --build ]; then
	echo "@edge https://dl-cdn.alpinelinux.org/alpine/edge/main" >>/etc/apk/repositories
	packages="$packages build-base cmake go linux-headers nasm ninja-is-really-ninja ruby llvm23@edge clang23@edge lld23@edge"
fi
# shellcheck disable=SC2086 # a list of names
apk add --no-cache --no-progress $packages >/dev/null
git config --global --add safe.directory '*'
if [ "${1:-}" = --build ]; then
	curl -fsSL --retry 5 --retry-all-errors https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path >/dev/null
fi
tmp=$(mktemp -d)
curl -fsSL --retry 5 --retry-all-errors -o "$tmp/bun.zip" "https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/bun-linux-x64-musl.zip"
echo "$BUN_SHA256  $tmp/bun.zip" | sha256sum -c - >/dev/null
unzip -q "$tmp/bun.zip" -d "$tmp"
install -D -m 755 "$tmp/bun-linux-x64-musl/bun" "$HOME/.bun/bin/bun"
rm -rf "$tmp"
"$HOME/.bun/bin/bun" --version
