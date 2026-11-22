#!/usr/bin/env bash
# The installer's checks of what it downloads, against the latest release served from a local folder (PIBOLT_DOWNLOAD_BASE):
#   - the release as published installs, its signature verified;
#   - without SHA256SUMS.sig it is not installed (a signed release must carry its signature);
#   - an archive replaced together with its checksum is not installed (the signature of SHA256SUMS does not match);
#   - a signature that is not one (a page of HTML served in its place) is not taken for a missing one;
#   - a release from before releases were signed is installed only with PIBOLT_UNVERIFIED set to the SHA-256 of its SHA256SUMS
#     (not with PIBOLT_UNVERIFIED=1), and then with a warning;
#   - a mirror is taken for the release asked for only: without PIBOLT_VERSION, when which release is the latest cannot be found
#     out, nothing is installed;
#   - a PIBOLT_BIN_DIR with quotes, $(...) and backquotes stays a folder name in the line given for the shell's configuration;
#   - an update keeps the build it replaces as <name>.previous (for pi-bolt update --rollback), and the same build installed
#     again does not replace the one kept;
#   - PIBOLT_CHANNEL=beta is kept for the installation's updates (<install>/channel), and stable takes it away;
#   - an xz that is not XZ Utils' (BusyBox's: no -T, no x86 filter) is not used: the .tar.gz is installed, or the .tar.xz through a
#     tar that reads it itself; an xz that fails after tar ended well is a failed unpack, and the .tar.gz is installed instead;
#     where that cannot be downloaded either, nothing is installed and nothing is left;
#   - OpenSSL 3 installed as openssl3, beside an older openssl (AlmaLinux 8), checks the signature.
# Needs curl, python3 and gh (or a GitHub token for gh), and OpenSSL 3 for the verified case.
#
# Usage: tests/installer/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$PWD
case "$(uname -s)-$(uname -m)" in Darwin-*) NAME=pi-bolt-darwin-arm64 ;; *-aarch64) NAME=pi-bolt-linux-arm64 ;; *) NAME=pi-bolt-linux-x64 ;; esac
WORK=$(mktemp -d)
SERVER=""
trap '[ -z "$SERVER" ] || kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT
status=0
pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; status=1; }

mkdir -p "$WORK/good"
# (Tried three times: GitHub's API answers a download with an occasional HTTP 500.)
for attempt in 1 2 3; do
	gh release download --repo opensec-git/Pi-Bolt --dir "$WORK/good" --clobber --pattern SHA256SUMS --pattern SHA256SUMS.sig \
		--pattern "$NAME.tar.gz" --pattern "$NAME.tar.xz" && break
	[ "$attempt" = 3 ] && exit 1
	sleep 10
done
cp -R "$WORK/good" "$WORK/nosig" && rm "$WORK/nosig/SHA256SUMS.sig"
cp -R "$WORK/good" "$WORK/htmlsig" && printf '<!DOCTYPE html><html><body>Not Found</body></html>\n' >"$WORK/htmlsig/SHA256SUMS.sig"
# (As a release from before 0.6.0: no signature, and checksums that say that version.)
cp -R "$WORK/nosig" "$WORK/unsigned" && { echo "# pi-bolt 0.5.99"; tail -n +2 "$WORK/good/SHA256SUMS"; } >"$WORK/unsigned/SHA256SUMS"
cp -R "$WORK/good" "$WORK/tampered"
(
	cd "$WORK/tampered"
	echo "not Pi-Bolt" >x && tar -czf "$NAME.tar.gz" x && cp "$NAME.tar.gz" "$NAME.tar.xz" && rm x
	sum() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
	grep -v " $NAME\.tar\." SHA256SUMS >s && sum "$NAME.tar.gz" "$NAME.tar.xz" >>s && mv s SHA256SUMS
)

port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$WORK" >/dev/null 2>&1 &
SERVER=$!
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$port/" >/dev/null && break; sleep 0.1; done

# install CASE [ENV...]: runs the installer against $WORK/CASE in a home of its own; prints its output.
install() {
	local case=$1
	shift
	rm -rf "${WORK:?}/home"
	mkdir -p "$WORK/home"
	env HOME="$WORK/home" SHELL=/bin/sh PIBOLT_DOWNLOAD_BASE="http://127.0.0.1:$port/$case" PIBOLT_EXTENSIONS=no PIBOLT_NO_START=1 \
		PIBOLT_YES=1 PIBOLT_INSTALL="$WORK/home/inst" PIBOLT_BIN_DIR="$WORK/home/bin" "$@" sh "$ROOT/install.sh" </dev/null 2>&1 || true
}
installed() { [ -x "$WORK/home/inst/$NAME/pi" ]; }
# install_again CASE [ENV...]: the same, over what the last run installed.
install_again() {
	local case=$1
	shift
	env HOME="$WORK/home" SHELL=/bin/sh PIBOLT_DOWNLOAD_BASE="http://127.0.0.1:$port/$case" PIBOLT_EXTENSIONS=no PIBOLT_NO_START=1 \
		PIBOLT_YES=1 PIBOLT_INSTALL="$WORK/home/inst" PIBOLT_BIN_DIR="$WORK/home/bin" "$@" sh "$ROOT/install.sh" </dev/null 2>&1 || true
}

out=$(install good)
if installed && grep -q "signature verified" <<<"$out"; then pass "the release installs, signature verified"; else
	fail "the release installs, signature verified"; printf '%s\n' "$out"; fi

out=$(install nosig)
if ! installed && grep -q "could not download the release's signature" <<<"$out"; then pass "no signature: not installed"; else
	fail "no signature: not installed"; printf '%s\n' "$out"; fi

out=$(install tampered)
if ! installed && grep -q "signature does not verify" <<<"$out"; then pass "replaced archive and checksum: not installed"; else
	fail "replaced archive and checksum: not installed"; printf '%s\n' "$out"; fi

out=$(install htmlsig)
if ! installed && grep -q "signature does not verify" <<<"$out"; then pass "a page of HTML for the signature: not installed"; else
	fail "a page of HTML for the signature: not installed"; printf '%s\n' "$out"; fi

sums_sha=$(if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi <"$WORK/unsigned/SHA256SUMS" | cut -c1-64)
out=$(install unsigned PIBOLT_VERSION=bolt-v0.5.99)
if ! installed && grep -q "from before Pi-Bolt signed its releases" <<<"$out" && grep -q "PIBOLT_UNVERIFIED=$sums_sha" <<<"$out"; then
	pass "a release from before signing: not installed, and the SHA-256 to give is said"; else
	fail "a release from before signing: not installed, and the SHA-256 to give is said"; printf '%s\n' "$out"; fi
# (An opt-in left in the environment, or one for another download, is not one for this download.)
for value in 1 "$(printf '%064d' 0)"; do
	out=$(install unsigned PIBOLT_VERSION=bolt-v0.5.99 PIBOLT_UNVERIFIED="$value")
	if ! installed && grep -q "not to this download's SHA-256" <<<"$out"; then pass "a release from before signing, PIBOLT_UNVERIFIED=${value:0:8}...: not installed"; else
		fail "a release from before signing, PIBOLT_UNVERIFIED=${value:0:8}...: not installed"; printf '%s\n' "$out"; fi
done
out=$(install unsigned PIBOLT_VERSION=bolt-v0.5.99 PIBOLT_UNVERIFIED="$sums_sha")
if installed && grep -q "WARNING:" <<<"$out"; then pass "a release from before signing, PIBOLT_UNVERIFIED=<its SHA-256>: installed, with a warning"; else
	fail "a release from before signing, PIBOLT_UNVERIFIED=<its SHA-256>: installed, with a warning"; printf '%s\n' "$out"; fi

# (GitHub out of reach through a proxy that is not there; the mirror is on this machine, which the proxy is not used for.)
out=$(install good https_proxy=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9)
if ! installed && grep -q "set PIBOLT_VERSION to the release it serves" <<<"$out"; then
	pass "a mirror, and the latest release not known: not installed"; else
	fail "a mirror, and the latest release not known: not installed"; printf '%s\n' "$out"; fi

# Programs that stand in for others, first on PATH: BusyBox's xz and tar (#328), an xz that fails after writing everything,
# and OpenSSL 1.1 as openssl beside OpenSSL 3 as openssl3 (#323). Each notes how it was called in $WORK/calls.
real_tar=$(command -v tar) real_xz=$(command -v xz || true)
stub() { # stub DIR NAME BODY: DIR/NAME, a shell script running BODY
	mkdir -p "$WORK/$1"
	printf '#!/bin/sh\necho "%s $*" >>"%s/calls"\n%s\n' "$2" "$WORK" "$3" >"$WORK/$1/$2"
	chmod 755 "$WORK/$1/$2"
}
busybox_xz='for a; do case "$a" in -T*) echo "xz: unrecognized option: T" >&2; exit 1 ;; --version) echo "BusyBox v1.37.0 multi-call binary." >&2; exit 1 ;; esac; done
echo "xz: corrupted data" >&2; exit 1'
stub busybox xz "$busybox_xz"
stub busybox-tar-too xz "$busybox_xz"
stub busybox-tar-too tar "if [ \"\$1\" = --version ]; then echo 'tar (busybox) 1.37.0'; exit 0; fi
exec '$real_tar' \"\$@\""
dl() { grep -c "^downloading http://127.0.0.1:$port/good/$NAME\.$1 " <<<"$out" || true; }

# (BusyBox's xz, and a tar that reads no .tar.xz itself: the .tar.gz.) #328
rm -f "$WORK/calls"
out=$(install good PATH="$WORK/busybox-tar-too:$PATH")
if installed && [ "$(dl tar.gz)" = 1 ] && [ "$(dl tar.xz)" = 0 ] && ! grep -q '^xz .*-d' "$WORK/calls"; then
	pass "BusyBox's xz and tar: the .tar.gz is installed"; else
	fail "BusyBox's xz and tar: the .tar.gz is installed"; printf '%s\n' "$out"; cat "$WORK/calls" 2>/dev/null; fi
# (BusyBox's xz, and a tar that reads a .tar.xz itself, as macOS's: the .tar.xz, through tar.) #328
if tar --version 2>/dev/null | grep -q liblzma; then
	rm -f "$WORK/calls"
	out=$(install good PATH="$WORK/busybox:$PATH")
	if installed && [ "$(dl tar.xz)" = 1 ] && ! grep -q '^xz .*-d' "$WORK/calls"; then
		pass "BusyBox's xz and a tar with liblzma: the .tar.xz is installed, through tar"; else
		fail "BusyBox's xz and a tar with liblzma: the .tar.xz is installed, through tar"; printf '%s\n' "$out"; fi
fi

if [ -n "$real_xz" ]; then
	# (XZ Utils' xz, as far as the installer can tell, that writes the whole archive and then fails: tar ends well.) #328
	stub badxz xz "if [ \"\$1\" = --version ]; then exec '$real_xz' --version; fi
'$real_xz' \"\$@\"; exit 1"
	out=$(install good PATH="$WORK/badxz:$PATH")
	if installed && [ "$(dl tar.xz)" = 1 ] && [ "$(dl tar.gz)" = 1 ] && grep -q "could not unpack $NAME.tar.xz here" <<<"$out"; then
		pass "xz fails after tar ended well: a failed unpack, the .tar.gz is installed instead"; else
		fail "xz fails after tar ended well: a failed unpack, the .tar.gz is installed instead"; printf '%s\n' "$out"; fi
	# (The same, and the .tar.gz cannot be downloaded: nothing installed, no half-unpacked build left.)
	cp -R "$WORK/good" "$WORK/nogz" && rm "$WORK/nogz/$NAME.tar.gz"
	out=$(install nogz PATH="$WORK/badxz:$PATH")
	left=$(find "$WORK/home/inst" -mindepth 1 -maxdepth 1 2>/dev/null | head -n 1)
	if ! installed && [ -z "$left" ] && grep -q "download failed: .*/$NAME.tar.gz" <<<"$out"; then
		pass "xz fails and no .tar.gz: nothing installed, nothing left"; else
		fail "xz fails and no .tar.gz: nothing installed, nothing left"; printf '%s\n%s\n' "$out" "$left"; fi
fi

# (OpenSSL 1.1 as openssl, OpenSSL 3 as openssl3: openssl3 checks the signature.) #323
real_ssl=""
for candidate in openssl /opt/homebrew/opt/openssl@3/bin/openssl /usr/local/opt/openssl@3/bin/openssl; do
	case "$("$candidate" version 2>/dev/null)" in "OpenSSL "[3-9]* | "OpenSSL "[1-9][0-9]*) real_ssl=$(command -v "$candidate"); break ;; esac
done
if [ -n "$real_ssl" ]; then
	stub openssl3 openssl 'echo "OpenSSL 1.1.1k  FIPS 25 Mar 2021"; [ "$1" = version ] || exit 1'
	stub openssl3 openssl3 "exec '$real_ssl' \"\$@\""
	rm -f "$WORK/calls"
	out=$(install good PATH="$WORK/openssl3:$PATH")
	if installed && grep -q "signature verified" <<<"$out" && grep -q '^openssl3 pkeyutl -verify' "$WORK/calls"; then
		pass "OpenSSL 3 as openssl3, beside OpenSSL 1.1: signature verified with it"; else
		fail "OpenSSL 3 as openssl3, beside OpenSSL 1.1: signature verified with it"; printf '%s\n' "$out"; cat "$WORK/calls" 2>/dev/null; fi
fi

bin="$WORK/home/b\"\$(touch $WORK/pwned)\`touch $WORK/pwned\`"
out=$(install good PIBOLT_BIN_DIR="$bin")
line=$(sed -n 's/^  export PATH=/export PATH=/p' <<<"$out" | head -1)
if [ -n "$line" ]; then
	sh -c "$line" && first=$(sh -c "$line; printf '%s' \"\$PATH\"" | cut -d: -f1)
	if [ ! -e "$WORK/pwned" ] && [ "${first:-}" = "$bin" ]; then pass "PIBOLT_BIN_DIR stays a folder name"; else
		fail "PIBOLT_BIN_DIR stays a folder name"; printf '%s\n' "$line"; fi
else
	fail "PIBOLT_BIN_DIR stays a folder name (no PATH line)"; printf '%s\n' "$out"
fi

inst="$WORK/home/inst"
out=$(install good)
if [ ! -e "$inst/$NAME.previous" ]; then pass "a first install keeps no previous build"; else fail "a first install keeps no previous build"; fi
printf 'an older build\n' >>"$inst/$NAME/pi-bolt.txt"
out=$(install_again good)
if installed && grep -q "an older build" "$inst/$NAME.previous/pi-bolt.txt" 2>/dev/null &&
	! grep -q "an older build" "$inst/$NAME/pi-bolt.txt"; then pass "an update keeps the build it replaces as .previous"; else
	fail "an update keeps the build it replaces as .previous"; printf '%s\n' "$out"; fi
out=$(install_again good)
if grep -q "an older build" "$inst/$NAME.previous/pi-bolt.txt" 2>/dev/null; then
	pass "the same build installed again leaves the previous one"; else
	fail "the same build installed again leaves the previous one"; printf '%s\n' "$out"; fi

version=$(head -n 1 "$WORK/good/SHA256SUMS" | sed 's/^# pi-bolt //')
out=$(install_again good PIBOLT_CHANNEL=beta PIBOLT_VERSION="bolt-v$version")
if [ "$(cat "$inst/channel" 2>/dev/null)" = beta ]; then pass "PIBOLT_CHANNEL=beta is kept for updates"; else
	fail "PIBOLT_CHANNEL=beta is kept for updates"; printf '%s\n' "$out"; fi
out=$(install_again good PIBOLT_CHANNEL=stable)
if [ ! -e "$inst/channel" ]; then pass "PIBOLT_CHANNEL=stable takes it away"; else fail "PIBOLT_CHANNEL=stable takes it away"; fi
out=$(install_again good PIBOLT_CHANNEL=nightly)
if grep -q "PIBOLT_CHANNEL must be stable or beta" <<<"$out"; then pass "another channel is refused"; else
	fail "another channel is refused"; printf '%s\n' "$out"; fi
exit $status
