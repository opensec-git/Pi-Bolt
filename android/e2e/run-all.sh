#!/usr/bin/env bash
# Runs the area scripts sharded across the given adb serials, in parallel (one area at a time per serial), then prints
# a pass/fail table with per-area timings, case counts and failure screenshots.
#
#   ./run-all.sh [--areas session,tools,...] [--install] [--skip-engine] <serial> [<serial>...]
#
# --install      reinstall the APK ($APK, default build/app-debug.apk) on every serial before its first area
# --skip-engine  E2E_SKIP_ENGINE=1: only the cases that work without the on-device engine
# Results: out/run-<time>/<serial>/<area>/ (results.tsv, screenshots, logcat.txt, fake-model-requests.jsonl).
set -u
SUITE="$(cd "$(dirname "$0")" && pwd)"
AREAS="session lifecycle tools providers projects files extensions"
INSTALL=0
while [ $# -gt 0 ]; do
	case "$1" in
	--areas) AREAS=$(printf '%s' "$2" | tr ',' ' '); shift 2 ;;
	--install) INSTALL=1; shift ;;
	--skip-engine) export E2E_SKIP_ENGINE=1; shift ;;
	-h | --help) sed -n 2,9p "$0"; exit 0 ;;
	*) break ;;
	esac
done
[ $# -gt 0 ] || { echo "usage: $0 [--areas a,b] [--install] [--skip-engine] <serial>..." >&2; exit 2; }
SERIALS="$*"
for s in $SERIALS; do
	[ "$s" = emulator-5554 ] && [ "${E2E_ALLOW_5554:-}" != "yes-i-own-it" ] && { echo "refusing emulator-5554 (shared pibolt-api36)" >&2; exit 2; }
done

RUN="$SUITE/out/run-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RUN"
export E2E_OUT="$RUN"

# Expected seconds per area: longest first, each to the serial with the least work so far.
weight() {
	case "$1" in
	session) echo 420 ;; lifecycle) echo 300 ;; tools) echo 300 ;; providers) echo 200 ;; projects) echo 200 ;; files) echo 150 ;; extensions) echo 250 ;; *) echo 200 ;;
	esac
}
n=0
for s in $SERIALS; do n=$((n + 1)); eval "LOAD_$n=0; QUEUE_$n=''; SER_$n=$s"; done
for a in $(for a in $AREAS; do echo "$(weight "$a") $a"; done | sort -rn | awk '{print $2}'); do
	best=1
	i=1
	while [ $i -le $n ]; do
		eval "l=\$LOAD_$i; b=\$LOAD_$best"
		[ "$l" -lt "$b" ] && best=$i
		i=$((i + 1))
	done
	eval "LOAD_$best=\$((LOAD_$best + $(weight "$a"))); QUEUE_$best=\"\$QUEUE_$best $a\""
done

echo "run: $RUN"
i=1
while [ $i -le $n ]; do
	eval "s=\$SER_$i; q=\$QUEUE_$i"
	echo "  $s:$q"
	(
		mkdir -p "$RUN/$s"
		first=1
		for a in $q; do
			start=$(date +%s)
			inst=0
			[ $first = 1 ] && [ $INSTALL = 1 ] && inst=1
			SERIAL="$s" E2E_INSTALL=$inst bash "$SUITE/$a.sh" >"$RUN/$s/$a.log" 2>&1
			rc=$?
			printf '%s\t%s\t%s\t%s\n' "$a" "$s" "$rc" "$(($(date +%s) - start))" >>"$RUN/$s/areas.tsv"
			first=0
		done
	) &
	i=$((i + 1))
done
wait

# Table
printf '\n%-10s %-15s %-6s %6s  %-14s %s\n' AREA SERIAL RESULT SECS "CASES p/f/s" "FAILED CASES (screenshot)"
for f in "$RUN"/*/areas.tsv; do cat "$f"; done | sort | while IFS=$'\t' read -r a s rc secs; do
	r="$RUN/$s/$a/results.tsv"
	p=0 fl=0 sk=0
	if [ -f "$r" ]; then
		p=$(grep -c $'\tPASS\t' "$r")
		fl=$(grep -c $'\tFAIL\t' "$r")
		sk=$(grep -c $'\tSKIP\t' "$r")
	fi
	result=PASS
	[ "$rc" != 0 ] && result=FAIL
	[ "$rc" != 0 ] && [ ! -s "$r" ] && result=ERROR
	printf '%-10s %-15s %-6s %6s  %-14s' "$a" "$s" "$result" "$secs" "$p/$fl/$sk"
	if [ -f "$r" ]; then
		awk -F'\t' '$2=="FAIL"{printf " %s", $1}' "$r"
	fi
	[ "$result" = ERROR ] && printf ' see %s' "$RUN/$s/$a.log"
	echo
	[ -f "$r" ] && awk -F'\t' -v pad="$(printf '%41s' '')" '$2=="FAIL"{print pad "  " $1 ": " $4}' "$r"
done
echo
echo "logs: $RUN/<serial>/<area>.log, details: $RUN/<serial>/<area>/"
! grep -q -v $'\t0\t' "$RUN"/*/areas.tsv
