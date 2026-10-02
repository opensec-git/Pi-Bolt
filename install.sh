#!/bin/sh
# Pi-Bolt installer: downloads a release, verifies its checksum, installs it to ~/.pi-bolt and links `pi-bolt` into ~/.local/bin.
#
#   curl -fsSL https://opensec-git.github.io/Pi-Bolt/install.sh | sh
#
# Environment:
#   PIBOLT_VERSION   a release tag such as bolt-v0.2.0 (default: the latest release)
#   PIBOLT_VARIANT   x64, x64-baseline or x64-jit (default: x64 on CPUs with AVX2, x64-baseline otherwise)
#   PIBOLT_INSTALL   where to install (default: ~/.pi-bolt)
#   PIBOLT_BIN_DIR   where to link the `pi-bolt` command (default: ~/.local/bin)
set -eu

REPO="https://github.com/opensec-git/Pi-Bolt"
VERSION="${PIBOLT_VERSION:-latest}"
INSTALL="${PIBOLT_INSTALL:-$HOME/.pi-bolt}"
BIN_DIR="${PIBOLT_BIN_DIR:-$HOME/.local/bin}"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }

[ "$(uname -s)" = Linux ] || die "Pi-Bolt runs on Linux only (this is $(uname -s))"
[ "$(uname -m)" = x86_64 ] || die "Pi-Bolt runs on x86-64 only (this is $(uname -m))"
if ldd --version 2>&1 | grep -qi musl; then
	die "Pi-Bolt needs glibc; musl-based systems such as Alpine are not supported"
fi
has sha256sum || die "sha256sum is required"
has tar || die "tar is required"
if has curl; then
	fetch() { curl -fL --progress-bar -o "$1" "$2"; }
elif has wget; then
	fetch() { wget -q -O "$1" "$2"; }
else
	die "curl or wget is required"
fi

VARIANT="${PIBOLT_VARIANT:-}"
if [ -z "$VARIANT" ]; then
	if grep -qw avx2 /proc/cpuinfo; then VARIANT=x64; else VARIANT=x64-baseline; fi
fi
case "$VARIANT" in
x64 | x64-baseline | x64-jit) ;;
*) die "PIBOLT_VARIANT must be x64, x64-baseline or x64-jit" ;;
esac
NAME="pi-bolt-linux-$VARIANT"

if [ "$VERSION" = latest ]; then BASE="$REPO/releases/latest/download"; else BASE="$REPO/releases/download/$VERSION"; fi
BASE="${PIBOLT_DOWNLOAD_BASE:-$BASE}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
say "downloading $NAME ($VERSION)"
fetch "$TMP/$NAME.tar.gz" "$BASE/$NAME.tar.gz"
fetch "$TMP/SHA256SUMS" "$BASE/SHA256SUMS" 2>/dev/null
(cd "$TMP" && grep " $NAME.tar.gz\$" SHA256SUMS | sha256sum -c --quiet -) || die "checksum mismatch: the download is corrupt or incomplete"
say "checksum verified"

mkdir -p "$INSTALL" "$BIN_DIR"
tar -C "$TMP" -xzf "$TMP/$NAME.tar.gz"
"$TMP/$NAME/pi" --version >/dev/null || die "the downloaded executable does not run on this system"
# Replace a previous install only once the new one is in place.
rm -rf "$INSTALL/$NAME.old"
if [ -d "$INSTALL/$NAME" ]; then mv "$INSTALL/$NAME" "$INSTALL/$NAME.old"; fi
mv "$TMP/$NAME" "$INSTALL/$NAME"
rm -rf "$INSTALL/$NAME.old"
ln -sfn "$INSTALL/$NAME/pi" "$BIN_DIR/pi-bolt"

say "installed Pi $("$INSTALL/$NAME/pi" --version) (Pi-Bolt, $VARIANT) to $INSTALL/$NAME"
say "run it with: pi-bolt"
case ":$PATH:" in
*":$BIN_DIR:"*) ;;
*) printf '\n%s is not on your PATH. Add it, for example:\n  echo '\''export PATH="%s:$PATH"'\'' >> ~/.profile\n' "$BIN_DIR" "$BIN_DIR" ;;
esac
