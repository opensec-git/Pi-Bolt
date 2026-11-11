#!/usr/bin/env bash
# Builds the Pi-Bolt runtime for Linux x86-64 with musl (Alpine): the same engine as scripts/build-runtime.sh, built natively,
# with ICU linked in. Based on the patch set by @nthrow in #280.
# Usage: scripts/build-runtime-musl.sh [--icu] [--lto on|off] [-jN]
#   --icu        first build ICU from source and install it into /usr as static libraries only (needs root): the ICU of the glibc
#                sysroot (scripts/toolchain/make-sysroot.sh), whose libicudata.a holds all of ICU's data. Alpine's own icu-dev
#                must not be installed: its libicudata.a is a stub, and its libicu*.so would be linked instead, so that the
#                executable would need the system's ICU of that version, and its data files.
#   --lto off    no link-time optimization
#   -jN          jobs at once (default: Bun's build, one per core)
# Needs: Alpine (musl), the sources (scripts/fetch-sources.sh), clang, lld and LLVM 23 (Alpine's edge), cmake, ninja, rust
# (cargo), bun (a musl build), and ICU as static libraries (--icu). A separate script from build-runtime.sh, so that what only
# the musl runtime is built with changes only its stamp (scripts/runtime-stamp.sh musl), and not the other runtimes'. Bun makes
# no linker order file on musl (it links one only on glibc, macOS and Windows), so there is no ordering step.
source "$(dirname "$0")/lib/common.sh"
[ "$PIBOLT_LIBC" = musl ] || die "this is the musl runtime's build: on $PIBOLT_RELEASE_PLATFORM, scripts/build-runtime.sh"
[ "$PIBOLT_ARCH" = x64 ] || die "musl runtimes are built for x86-64 only"

ICU=""; LTO=on; JOBS=()
while [ $# -gt 0 ]; do
	case "$1" in
	--icu) ICU=1 ;;
	--lto) LTO="$2"; shift ;;
	-j*) JOBS=("$1") ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
need bun; need cmake; need cargo; need clang
WEBKIT="$PIBOLT_WORK/webkit"; BUN_SRC="$PIBOLT_WORK/bun"
[ -d "$WEBKIT/.git" ] && [ -d "$BUN_SRC/.git" ] || die "sources missing: run scripts/fetch-sources.sh first"

if [ -n "$ICU" ]; then
	# (The version and checksum are the sysroot's: one ICU for every Linux runtime.)
	sysroot_script="$PIBOLT_ROOT/scripts/toolchain/make-sysroot.sh"
	ICU_VERSION=$(sed -n 's/^ICU_VERSION=//p' "$sysroot_script")
	ICU_SHA256=$(sed -n 's/^ICU_SHA256=//p' "$sysroot_script")
	[ -n "$ICU_VERSION" ] && [ -n "$ICU_SHA256" ] || die "no ICU_VERSION and ICU_SHA256 in $sysroot_script"
	work=$(mktemp -d)
	log "ICU $ICU_VERSION, static, into /usr"
	curl -fsSL --retry 10 --retry-delay 30 --retry-all-errors "https://github.com/unicode-org/icu/releases/download/release-${ICU_VERSION}/icu4c-${ICU_VERSION}-sources.tgz" -o "$work/icu.tgz"
	echo "$ICU_SHA256  $work/icu.tgz" | sha256sum -c - >/dev/null
	tar -xzf "$work/icu.tgz" -C "$work"
	# (-march=nehalem: Bun's and WebKit's floor on x86-64, as in the sysroot.)
	(cd "$work/icu/source" &&
		CC=clang CXX=clang++ CFLAGS="-Os -fPIC -march=nehalem" CXXFLAGS="-Os -fPIC -march=nehalem" LDFLAGS="-fuse-ld=lld" \
			./configure --prefix=/usr --enable-static --disable-shared --disable-tests --disable-samples >"$work/icu-configure.log" 2>&1 &&
		make -j"$(nproc)" >"$work/icu-make.log" 2>&1 &&
		make install >"$work/icu-install.log" 2>&1) || { tail -30 "$work"/icu-*.log; exit 1; }
	rm -rf "$work"
fi
for lib in icudata icui18n icuuc; do
	[ -f "/usr/lib/lib$lib.a" ] || die "no /usr/lib/lib$lib.a: run with --icu"
	[ ! -e "/usr/lib/lib$lib.so" ] || die "/usr/lib/lib$lib.so would be linked instead of ICU's static library: apk del icu-dev"
done
# Alpine's stub has no data: the executable would then need ICU's data files at run time, and Intl fail without them.
[ "$(stat -c %s /usr/lib/libicudata.a)" -gt 10000000 ] || die "/usr/lib/libicudata.a has no data (Alpine's icu-static): run with --icu"

BUILD_DIR="build/pibolt-release-musl"; [ "$LTO" = off ] && BUILD_DIR="build/pibolt-release-musl-nolto"
log "building the runtime in $BUN_SRC/$BUILD_DIR (WebKit from $WEBKIT)"
(cd "$BUN_SRC" && BUN_WEBKIT_PATH="$WEBKIT" bun scripts/build.ts --profile=release-local --lto="$LTO" --build-dir="$BUILD_DIR" "${JOBS[@]}")

mkdir -p "$PIBOLT_WORK/runtime"
cp "$BUN_SRC/$BUILD_DIR/bun" "$PIBOLT_WORK/runtime/bun"
cp "$BUN_SRC/$BUILD_DIR/bun-profile" "$PIBOLT_WORK/runtime/bun-profile" 2>/dev/null || true
# Which engine it is (scripts/build-pi.sh records it in the executables it builds, for `pi-bolt doctor`).
"$PIBOLT_ROOT/scripts/runtime-stamp.sh" musl >"$PIBOLT_WORK/runtime/RUNTIME_STAMP" 2>/dev/null || rm -f "$PIBOLT_WORK/runtime/RUNTIME_STAMP"
log "runtime: $PIBOLT_WORK/runtime/bun ($("$PIBOLT_WORK/runtime/bun" --version))"
