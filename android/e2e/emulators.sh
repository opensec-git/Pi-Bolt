#!/usr/bin/env bash
# Starts or stops the suite's headless emulators. Never touches pibolt-api36 / emulator-5554 (shared).
#
#   ./emulators.sh create                       pibolt-e2e-1 and pibolt-e2e-2 (android-36 google_apis arm64, pixel_8, 4 GB)
#   ./emulators.sh start pibolt-bench:5556 pibolt-e2e-1:5558 pibolt-e2e-2:5560   (waits for boot)
#   ./emulators.sh stop 5556 5558 5560
set -u
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
ADB="$ANDROID_HOME/platform-tools/adb"
LOGS="$(cd "$(dirname "$0")" && pwd)/out/emulators"
mkdir -p "$LOGS"

cmd="${1:-}"
shift || true
case "$cmd" in
create)
	for n in 1 2; do
		name=pibolt-e2e-$n
		[ -d "$HOME/.android/avd/$name.avd" ] && { echo "$name exists"; continue; }
		echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n "$name" -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_8 >/dev/null
		sed -i '' 's/^hw.ramSize=.*/hw.ramSize=4096/' "$HOME/.android/avd/$name.avd/config.ini"
		grep -q '^hw.ramSize' "$HOME/.android/avd/$name.avd/config.ini" || echo hw.ramSize=4096 >>"$HOME/.android/avd/$name.avd/config.ini"
		# 4 GB userdata: the default needs 12 GB free on the host at every boot.
		sed -i '' 's/^disk.dataPartition.size=.*/disk.dataPartition.size=4G/' "$HOME/.android/avd/$name.avd/config.ini"
		echo "created $name"
	done
	;;
start)
	for spec in "$@"; do
		avd=${spec%%:*}
		port=${spec##*:}
		{ [ "$avd" = pibolt-api36 ] || [ "$port" = 5554 ]; } && { echo "refusing $spec (shared)" >&2; exit 2; }
		nohup "$ANDROID_HOME/emulator/emulator" -avd "$avd" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot >"$LOGS/$avd.log" 2>&1 &
		echo "starting $avd on emulator-$port"
	done
	for spec in "$@"; do
		s=emulator-${spec##*:}
		t=0
		"$ADB" -s "$s" wait-for-device
		until [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
			sleep 2
			t=$((t + 2))
			[ $t -gt 300 ] && { echo "$s did not boot" >&2; exit 1; }
		done
		echo "$s booted"
	done
	;;
stop)
	for port in "$@"; do
		[ "$port" = 5554 ] && { echo "refusing 5554 (shared)" >&2; continue; }
		"$ADB" -s "emulator-$port" emu kill && echo "stopped emulator-$port"
	done
	;;
*)
	sed -n 2,6p "$0"
	exit 2
	;;
esac
