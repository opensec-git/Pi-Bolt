#!/usr/bin/env bash
# Measures the installed Pi-Bolt app on a phone, without installing anything or touching its UI:
#   cold start (am start -W), the engine's prepare and spare-engine times (logcat PiBoltEngine), PSS of the app and its
#   engine processes, the dex compilation state (baseline profile applied or not), wake locks and the APK's size.
#
#   android/tools/perf/measure.sh <serial> [runs]       default 5 cold starts
#
# Each run force-stops the app (its engines too), starts MainActivity and waits for the spare engine's ready line.
# Use it only on a phone where force-stopping the app is fine (no turn running). Android 12 MIUI XSpace: --user 0.
set -euo pipefail
S=${1:?serial}
RUNS=${2:-5}
PKG=in.opensec.pibolt
ACT=$PKG/opensec.pibolt.app.MainActivity
ADB=${ADB:-adb}
a() { "$ADB" -s "$S" "$@"; }

echo "== package"
a shell dumpsys package $PKG | grep -E 'versionName|versionCode|lastUpdateTime' | head -3
a shell pm path --user 0 $PKG | sed 's/package://' | while read -r p; do a shell ls -l "$p"; done
echo "== dexopt (speed-profile = the baseline profile was applied)"
a shell dumpsys package dexopt | grep -A4 "\[$PKG\]" | head -6 || true

echo "== cold starts"
for i in $(seq 1 "$RUNS"); do
	a shell am force-stop --user 0 $PKG
	sleep 2
	a logcat -c
	out=$(a shell am start-activity -W --user 0 -n $ACT 2>&1)
	total=$(echo "$out" | sed -n 's/^TotalTime: //p' | tr -d '\r')
	wait=$(echo "$out" | sed -n 's/^WaitTime: //p' | tr -d '\r')
	# The spare engine: up to 30 s.
	spare=""
	for _ in $(seq 1 60); do
		spare=$(a logcat -d -s PiBoltEngine:I | grep -m1 'spare engine ready' || true)
		[ -n "$spare" ] && break
		sleep 0.5
	done
	prep=$(a logcat -d -s PiBoltEngine:I | grep -m1 'prepare:' | sed 's/.*prepare: //' || true)
	pre=$(a logcat -d -s PiBoltEngine:I | grep -m1 'prefetched' | sed 's/.*prefetched //' || true)
	displayed=$(a logcat -d -s ActivityTaskManager:I | grep -m1 "Displayed $ACT" | sed 's/.*: +//' || true)
	# Home's ReportDrawnWhen (projects listed): builds from 0.6.0 on.
	drawn=$(a logcat -d -s ActivityTaskManager:I | grep -m1 "Fully drawn $ACT" | sed 's/.*: +//' || true)
	echo "run $i: TotalTime=${total}ms WaitTime=${wait}ms displayed=$displayed fullyDrawn=${drawn:-n/a} | prepare: $prep | prefetch: $pre | ${spare#*spare engine ready: }"
done

echo "== memory (PSS kB), app idle on Home with the spare engine"
sleep 5
app=$(a shell pidof $PKG | tr -d '\r')
a shell dumpsys meminfo "$app" | grep -E 'TOTAL PSS|TOTAL:|Java Heap|Native Heap|Graphics|Code:' | head -8
for pid in $(a shell "ps -A -o PID,NAME" | awk '/libpibolt/ {print $1}' | tr -d '\r'); do
	pss=$(a shell "run-as $PKG cat /proc/$pid/smaps_rollup" 2>/dev/null | awk '/^Pss:/ {print $2}' | tr -d '\r')
	rss=$(a shell "run-as $PKG cat /proc/$pid/smaps_rollup" 2>/dev/null | awk '/^Rss:/ {print $2}' | tr -d '\r')
	echo "engine pid $pid: Pss ${pss} kB, Rss ${rss} kB"
done

echo "== wake locks held by the app"
a shell dumpsys power | grep -i "pibolt" || echo "none"
