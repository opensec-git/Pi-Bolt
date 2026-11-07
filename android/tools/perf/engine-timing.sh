#!/usr/bin/env bash
# Pi's own startup timings (PI_TIMING=1, packages/coding-agent/src/core/timings.ts) for the engine on a phone: the app's
# engine binary and prefix, run as the app (run-as), in RPC mode with stdin closed, so it prints its timings and exits.
# No session is written (--no-session) and nothing is installed.
#
#   android/tools/perf/engine-timing.sh <serial> [runs] [extra engine args...]
#   android/tools/perf/engine-timing.sh <serial> 3 --no-extensions    the same without any extension
set -euo pipefail
S=${1:?serial}
RUNS=${2:-3}
shift $(($# < 2 ? $# : 2))
PKG=in.opensec.pibolt
ADB=${ADB:-adb}
a() { "$ADB" -s "$S" "$@"; }
LIB=$(a shell dumpsys package $PKG | sed -n 's/^ *legacyNativeLibraryDir=//p' | head -1 | tr -d '\r')/arm64
D=/data/data/$PKG
P=$D/files/usr
ENV="HOME=$D/files/home TMPDIR=$P/tmp XDG_CACHE_HOME=$D/cache/xdg XDG_CONFIG_HOME=$D/files/home/.config \
XDG_DATA_HOME=$D/files/home/.local/share XDG_STATE_HOME=$D/files/home/.local/state LANG=C.UTF-8 TERM=dumb \
PATH=$D/files/home/.local/bin:$P/bin:/system/bin SHELL=$P/bin/bash PI_PACKAGE_DIR=$P/share/pi-bolt \
SSL_CERT_FILE=$P/etc/ssl/certs/ca-certificates.crt PI_TIMING=1"
for i in $(seq 1 "$RUNS"); do
	echo "== run $i $*"
	a shell "run-as $PKG sh -c 'cd $D/files/home/projects && export $ENV && $LIB/libpibolt.so --mode rpc --no-session --offline $* </dev/null 2>&1 | grep -E \"ms\$|TOTAL|---\"'"
done
