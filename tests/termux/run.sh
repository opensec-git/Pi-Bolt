#!/usr/bin/env bash
# Pi-Bolt as install.sh installs it in Termux, on Linux ARM64 made to look like Android where it matters (no device needed):
#   - Termux's prefix (/data/data/com.termux/files/usr), TERMUX_VERSION and getprop, with a glibc loader where Termux's glibc has
#     it ($PREFIX/glibc/lib; here the system's own) and /system/bin/linker64, by which the runtime knows it is on Android;
#   - Termux's LD_PRELOAD: a library that glibc's loader cannot load (it needs a library that is not there, as Termux's needs
#     Android's libc), so that a glibc program started with it stops, as in Termux;
#   - Android's system call filter (android-filter.c): the calls Android 10 refuses to apps, which kill the process (SIGSYS);
#     glibc's rseq is off, as in Termux's glibc;
#   - Android's 39-bit address space (512 GB): what Pi-Bolt maps at fixed addresses must be below it. (Only where it is: qemu-user's
#     reserved_va, which would make the limit real, stops on stock Bun as well, at mimalloc's address hints above it.)
# The build is served from a local folder as a release from before releases were signed, which install.sh takes with
# PIBOLT_UNVERIFIED set to the SHA-256 of its SHA256SUMS. Not covered: Termux's patched glibc itself, Android's SELinux policy and kernel.
#
# Usage: tests/termux/run.sh --build DIR    (DIR: an unpacked pi-bolt-linux-arm64 build, or out/pi-bolt)
# Run it as root in a throwaway container (it writes /data and /system), for instance:
#   docker run --rm -v "$PWD:/repo" -w /repo pibolt-build tests/termux/run.sh --build out/pi-bolt
# Needs: cc with static libc (libc6-dev), readelf, python3, curl, xz, a static busybox (the shell of the launcher install.sh writes:
# Termux's is Android's, which the LD_PRELOAD does not stop).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD=""
while [ $# -gt 0 ]; do
	case "$1" in
	--build) BUILD="$(realpath "$2")"; shift ;;
	-h | --help) sed -n '2,17p' "$0"; exit 0 ;;
	*) echo "unknown option $1"; exit 2 ;;
	esac
	shift
done
[ -x "$BUILD/pi" ] || { echo "no build at '$BUILD' (--build DIR)"; exit 2; }
[ "$(uname -s)-$(uname -m)" = Linux-aarch64 ] || { echo "Linux ARM64 only"; exit 2; }
[ "$(id -u)" = 0 ] && { [ -f /.dockerenv ] || [ "${PIBOLT_TERMUX_TEST:-}" = 1 ]; } ||
	{ echo "run it as root in a throwaway container (or set PIBOLT_TERMUX_TEST=1): it writes /data and /system"; exit 2; }

status=0
pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '%s\n' "$2" | tail -n 15 | sed 's/^/   /'; status=1; }

export PREFIX=/data/data/com.termux/files/usr TERMUX_VERSION=0.118.3 HOME=/data/data/com.termux/files/home
mkdir -p "$PREFIX/bin" "$PREFIX/lib" "$PREFIX/etc" "$PREFIX/tmp" "$PREFIX/glibc/lib" "$HOME" /system/bin
export TMPDIR="$PREFIX/tmp" PATH="$PREFIX/bin:/usr/local/bin:/usr/bin:/bin" GLIBC_TUNABLES=glibc.pthread.rseq=0
WORK=$(mktemp -d)
SERVER=""
trap '[ -z "$SERVER" ] || kill "$SERVER" 2>/dev/null; rm -rf "$WORK"' EXIT
ln -sf /lib/ld-linux-aarch64.so.1 "$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
busybox=$(command -v busybox) && ! ldd "$busybox" >/dev/null 2>&1 || { echo "no static busybox (busybox-static)"; exit 2; }
ln -sf "$busybox" "$PREFIX/bin/sh"
: >/system/bin/linker64
printf 'nameserver 192.0.2.53\n' >"$PREFIX/etc/resolv.conf"
printf '#!/bin/sh\n[ "$1" = persist.sys.timezone ] && echo Asia/Kolkata\n' >"$PREFIX/bin/getprop" && chmod 755 "$PREFIX/bin/getprop"
# Termux's LD_PRELOAD, which glibc's loader cannot load: it needs libc.so, as Android's libc is named.
printf 'int termux_exec;\n' >"$WORK/preload.c" && printf 'int missing;\n' >"$WORK/libc.c"
cc -shared -o "$WORK/libc.so" "$WORK/libc.c" &&
	cc -shared -o "$PREFIX/lib/libtermux-exec.so" "$WORK/preload.c" -Wl,--no-as-needed "$WORK/libc.so" -Wl,-soname,libtermux-exec.so &&
	rm "$WORK/libc.so" || { echo "cc failed"; exit 2; }
# (Only for what stands for Termux's programs: its shell, and the filter below, static. This system's tools are glibc's.)
PRELOAD="$PREFIX/lib/libtermux-exec.so"
# What the programs Pi starts are given: a static program, which no LD_PRELOAD stops, prints it.
printf '#include <stdio.h>\n#include <stdlib.h>\nint main(void) { const char* v = getenv("LD_PRELOAD"); printf("%%s", v ? v : "(none)"); return 0; }\n' >"$WORK/show.c"
cc -static -O2 -o "$PREFIX/bin/show-preload" "$WORK/show.c" || { echo "cc -static failed (libc6-dev)"; exit 2; }
export SHOW_PRELOAD="$PREFIX/bin/show-preload"
cc -static -O2 -o "$WORK/android-filter" "$ROOT/tests/termux/android-filter.c" || { echo "cc failed"; exit 2; }

if out=$(LD_PRELOAD="$PRELOAD" /bin/true 2>&1); then fail "the LD_PRELOAD stops a glibc program, as Termux's does" "$out"; else pass "the LD_PRELOAD stops a glibc program, as Termux's does"; fi

# The release: the build as pi-bolt-linux-arm64, unsigned (0.5.99).
NAME=pi-bolt-linux-arm64
mkdir -p "$WORK/release/$NAME"
cp -R "$BUILD/." "$WORK/release/$NAME/"
(cd "$WORK/release" && tar -cf - "$NAME" | xz -T0 -1 >"$NAME.tar.xz" && rm -rf "$NAME" &&
	{ echo "# pi-bolt 0.5.99"; sha256sum "$NAME.tar.xz"; } >SHA256SUMS)
SUMS_SHA256=$(sha256sum "$WORK/release/SHA256SUMS" | cut -c1-64)
port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$WORK/release" >/dev/null 2>&1 &
SERVER=$!
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$port/SHA256SUMS" >/dev/null && break; sleep 0.1; done

# The installer, in a POSIX shell as Termux's (dash). (Without the LD_PRELOAD: the tools it runs are this system's, glibc's.)
INSTALL="$HOME/.pi-bolt"

# Where Termux's repository has no glibc-repo (Termux from Google Play, whose termux.net leaves the glibc packages out; or a
# mirror without it), the installer says what to do instead of failing in pkg. (Termux's apt and pkg stand in as scripts.)
rm "$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
printf '#!/bin/sh\nexit 0\n' >"$PREFIX/bin/apt-get"
printf '#!/bin/sh\necho "N: Unable to locate package $3" >&2\nexit 100\n' >"$PREFIX/bin/apt-cache"
printf '#!/bin/sh\necho "pkg $*" >>"%s/pkg-calls"\nexit 100\n' "$WORK" >"$PREFIX/bin/pkg"
chmod 755 "$PREFIX/bin/apt-get" "$PREFIX/bin/apt-cache" "$PREFIX/bin/pkg"
mkdir -p "$PREFIX/etc/apt"
for repo in "https://termux.net stable main|Google Play" "https://mirror.example.org/termux/termux-main stable main|termux-change-repo"; do
	printf 'deb %s\n' "${repo%|*}" >"$PREFIX/etc/apt/sources.list"
	want=${repo#*|}
	what="without glibc-repo in Termux's repository, install.sh says so ($want)"
	out=$(PIBOLT_VERSION=bolt-v0.5.99 PIBOLT_UNVERIFIED="$SUMS_SHA256" PIBOLT_DOWNLOAD_BASE="http://127.0.0.1:$port" PIBOLT_YES=1 PIBOLT_EXTENSIONS=no PIBOLT_NO_START=1 \
		/bin/sh "$ROOT/install.sh" </dev/null 2>&1)
	if [ $? -ne 0 ] && grep -qF "$want" <<<"$out" && [ ! -e "$WORK/pkg-calls" ]; then pass "$what"; else fail "$what" "$out"; fi
done
rm -f "$PREFIX/bin/apt-get" "$PREFIX/bin/apt-cache" "$PREFIX/bin/pkg" "$PREFIX/etc/apt/sources.list"
ln -sf /lib/ld-linux-aarch64.so.1 "$PREFIX/glibc/lib/ld-linux-aarch64.so.1"

out=$(PIBOLT_VERSION=bolt-v0.5.99 PIBOLT_UNVERIFIED="$SUMS_SHA256" PIBOLT_DOWNLOAD_BASE="http://127.0.0.1:$port" PIBOLT_YES=1 PIBOLT_EXTENSIONS=no PIBOLT_NO_START=1 \
	/bin/sh "$ROOT/install.sh" </dev/null 2>&1)
if [ -x "$INSTALL/$NAME/pi-termux" ] && [ "$(readlink "$PREFIX/bin/pi-bolt")" = "$INSTALL/$NAME/pi-termux" ]; then
	pass "install.sh installs it, pi-bolt in \$PREFIX/bin is the Termux launcher"
else
	fail "install.sh installs it, pi-bolt in \$PREFIX/bin is the Termux launcher" "$out"
fi
interp=$(readelf -l "$INSTALL/$NAME/pi" 2>/dev/null | sed -n 's/.*Requesting program interpreter: \(.*\)]/\1/p')
if [ "$interp" = "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" ]; then pass "its loader is Termux's glibc's"; else fail "its loader is Termux's glibc's" "$interp"; fi
if [ -f "$PREFIX/glibc/etc/resolv.conf" ]; then pass "Termux's resolv.conf is where its glibc reads it"; else fail "Termux's resolv.conf is where its glibc reads it"; fi

out=$(LD_PRELOAD="$PRELOAD" pi-bolt --version 2>&1) && pass "pi-bolt --version, with Termux's LD_PRELOAD set ($out)" || fail "pi-bolt --version, with Termux's LD_PRELOAD set" "$out"
out=$(LD_PRELOAD="$PRELOAD" BUN_STATIC_HEAP_VERBOSE=1 pi-bolt --version 2>&1)
if grep -q "image registered: true" <<<"$out"; then pass "it runs its compiled code"; else fail "it runs its compiled code" "$out"; fi

# Under Android's filter: a program without the runtime's SIGSYS handling is killed by it (stock Bun's event loop calls
# epoll_pwait2); Pi-Bolt is not.
if command -v bun >/dev/null 2>&1; then
	"$WORK/android-filter" bun -e 'setTimeout(() => console.log("ok"), 1)' >/dev/null 2>&1
	code=$?
	if [ "$code" = 159 ]; then pass "the filter kills stock Bun (SIGSYS)"; else fail "the filter kills stock Bun (SIGSYS): exit $code"; fi
fi
out=$(LD_PRELOAD="$PRELOAD" "$WORK/android-filter" pi-bolt --version 2>&1) && pass "pi-bolt --version under Android's filter" || fail "pi-bolt --version under Android's filter" "$out"
mkdir -p "$WORK/extensions" && cp "$ROOT/tests/termux/termux-env.js" "$ROOT/tests/termux/address-space.js" "$WORK/extensions/"
for filter in "" "$WORK/android-filter"; do
	what="the programs Pi starts get Termux's LD_PRELOAD back, in Android's time zone${filter:+, under the Android filter}"
	out=$(LD_PRELOAD="$PRELOAD" $filter pi-bolt -ne -e "$WORK/extensions/termux-env.js" --offline --no-session -p hi 2>&1 </dev/null)
	want="termux-env: sync=$PRELOAD async=$PRELOAD glibc-after=true tz=Asia/Kolkata"
	if grep -qxF "$want" <<<"$out"; then pass "$what"; else fail "$what" "$out"; fi
done

# Android's 512 GB of addresses: the prebuilt heap and code (mapped from the executable) and the Structures are below them.
out=$(LD_PRELOAD="$PRELOAD" pi-bolt -ne -e "$WORK/extensions/address-space.js" --offline --no-session -p hi 2>&1 </dev/null)
line=$(grep '^address-space: ' <<<"$out")
highest=$(sed -n 's/.* highest=\(0x[0-9a-f]*\) .*/\1/p' <<<"$line")
if [ -n "$highest" ] && [ "$((highest))" -le $((0x8000000000)) ] && ! grep -q 'from-file=0 ' <<<"$line" && grep -q 'structures=true' <<<"$line"; then
	pass "what it maps at fixed addresses is within Android's 512 GB ($line)"
else
	fail "what it maps at fixed addresses is within Android's 512 GB" "$out"
fi
exit $status
