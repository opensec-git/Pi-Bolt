#!/usr/bin/env bash
# Measures two Pi-Bolt builds against each other with the drivers in drivers/ and writes the raw results to a folder.
# summarize.py turns that folder (one per OS) into the tables of README.md.
#
# Usage: measure.sh OLD NEW OUT [STEP...]
#   OLD, NEW  a build: its launcher (`pi`, or `pi-start` on Linux x86-64), or the folder of an extracted release archive
#             (then `pi-start` if it has one, else `pi`)
#   OUT       the folder for the raw results (created; results are appended to files already there)
#   STEP      any of: startup modes session restored idle special standard (default: all, in that order)
# Environment:
#   OLD_NAME, NEW_NAME  the builds' names in the results (default 1.2.1 and 1.3.0; summarize.py reads them from builds.txt)
#   EXT                 extensions for the "typical home with extensions" row: unpacked npm packages, separated by commas,
#                       for example the folders `npm pack opensec-pi-subagents opensec-pi-todo` gives once extracted.
#                       Without it that row is skipped.
#
# Every workload talks to a fake model on 127.0.0.1. Nothing is sent to a real provider. Needs python3 and openssl; tmux is
# not used. About 25 minutes on an M5 Mac; longer on a loaded machine.
#
# Example, on the official archives:
#   tar -xJf pi-bolt-darwin-arm64.tar.xz -C 121 && tar -xJf pi-bolt-darwin-arm64.tar.xz -C 130   (one archive per release)
#   EXT=ext/opensec-pi-subagents,ext/opensec-pi-todo ./measure.sh 121/pi-bolt-darwin-arm64 130/pi-bolt-darwin-arm64 mac
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
drivers="$here/drivers"
[ $# -ge 3 ] || { sed -n '2,24p' "$0"; exit 2; }

launcher() {
	local p="$1"
	if [ -d "$p" ]; then
		if [ -x "$p/pi-start" ]; then p="$p/pi-start"; else p="$p/pi"; fi
	fi
	[ -x "$p" ] || { echo "measure.sh: $1: no executable Pi launcher" >&2; exit 2; }
	case "$p" in *[[:space:]]*) echo "measure.sh: $p: a path with spaces" >&2; exit 2 ;; esac
	(cd "$(dirname "$p")" && echo "$PWD/$(basename "$p")")
}

old="$(launcher "$1")"; new="$(launcher "$2")"
mkdir -p "$3"; out="$(cd "$3" && pwd)"
shift 3
steps=("$@")
[ ${#steps[@]} -gt 0 ] || steps=(startup modes session restored idle special standard)
on=${OLD_NAME:-1.2.1}; nn=${NEW_NAME:-1.3.0}
builds=(--build "$on=$old" --build "$nn=$new")
reversed=(--build "$nn=$new" --build "$on=$old")

# A home of its own, as the drivers make their own Pi homes: nothing is read from or written to the real ~/.pi.
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export HOME="$scratch"

sum() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
note() { echo "=== $(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$out/progress.txt" >&2; }

{
	echo "date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
	echo "host: $(uname -srm)"
	if [ "$(uname)" = Darwin ]; then
		echo "cpu: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
		echo "power: $(pmset -g batt | sed -n 's/^Now drawing from //p' | tr -d "'")"
	else
		echo "cpu: $(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | head -1), $(nproc) threads"
		echo "load: $(cut -d' ' -f1-3 /proc/loadavg)"
	fi
	for b in "$on=$old" "$nn=$new"; do
		name="${b%%=*}"; path="${b#*=}"; dir="$(dirname "$path")"
		echo "build $name: $path"
		echo "  version: $("$path" --version </dev/null 2>&1 | head -1)"
		for f in pi pi-bin pi-start; do [ -f "$dir/$f" ] && echo "  sha256 $f: $(sum "$dir/$f" | cut -d' ' -f1)"; done
	done
	echo "steps: ${steps[*]}"
} >> "$out/builds.txt"

py() { python3 -I "$@"; }

for step in "${steps[@]}"; do
	case "$step" in
	startup)
		note "startup: blank home, launch to visible typing, 30 rounds"
		py "$drivers/empty.py" "${builds[@]}" --rounds 30 >> "$out/startup.jsonl" ;;
	modes)
		note "modes: headless, RPC and TUI single answer, 12 rounds"
		py "$drivers/answer.py" "${builds[@]}" --rounds 12 >> "$out/modes.jsonl" ;;
	session)
		note "session: 101 interactive tool prompts over HTTPS, 5 rounds"
		py "$drivers/replay.py" "${builds[@]}" --tls --rounds 5 --turns 101 >> "$out/session.jsonl" 2>> "$out/session.log" ;;
	restored)
		note "restored: 6,000 restored messages in RPC, 6 rounds"
		py "$drivers/resumed.py" "${builds[@]}" --entries 6000 --sentences 2 --rounds 6 >> "$out/restored.jsonl" ;;
	idle)
		note "idle: post-answer idle CPU and memory, 10 rounds of 15 s"
		py "$drivers/idle.py" "${builds[@]}" --rounds 10 --seconds 15 >> "$out/idle.txt" ;;
	special)
		note "special: get_entries / get_session_stats on 12,000 entries, 6 rounds"
		py "$drivers/rpc.py" "${builds[@]}" --rounds 6 >> "$out/special-rpc.jsonl"
		note "special: tree search, linear and branched, 6 rounds"
		py "$drivers/tree_filter.py" "${builds[@]}" --rounds 6 >> "$out/special-tree.jsonl"
		note "special: 8 Mi and 32 Mi nested edit preview, 6 pairs"
		py "$drivers/nested.py" "${builds[@]}" >> "$out/special-preview.jsonl" ;;
	standard)
		for home in blank realistic extensions; do
			args=(--runs 11 --scenarios startup,headless,interactive)
			case $home in
			blank) ;;
			realistic) args+=(--home realistic) ;;
			extensions)
				if [ -z "${EXT:-}" ]; then note "standard: no EXT, skipping the typical home with extensions"; continue; fi
				args+=(--home realistic --home-extensions "$EXT") ;;
			esac
			note "standard: benchmark.py, $home home, 11 runs"
			py "$drivers/benchmark.py" "${builds[@]}" "${args[@]}" --out "$out/standard-$home.jsonl" > "$out/standard-$home.txt" 2>&1
		done
		for r in 1 2 3 4 5; do
			note "standard: 60,000-character answer, round $r of 5"
			if [ $((r % 2)) = 1 ]; then b=("${builds[@]}"); else b=("${reversed[@]}"); fi
			py "$drivers/long_answer.py" "${b[@]}" --sizes 60000 >> "$out/standard-long-answer.txt"
		done
		for r in 1 2 3 4 5 6 7; do
			note "standard: resuming a 2,292-entry session in fullscreen, round $r of 7"
			if [ $((r % 2)) = 1 ]; then order=("$on=$old" "$nn=$new"); else order=("$nn=$new" "$on=$old"); fi
			for b in "${order[@]}"; do
				record="$(py "$drivers/measure_resume.py" "${b#*=}" 2292 fullscreen)"
				echo "{\"build\": \"${b%%=*}\", \"round\": $r, \"record\": $record}" >> "$out/standard-resume.jsonl"
			done
		done ;;
	*) echo "measure.sh: unknown step $step" >&2; exit 2 ;;
	esac
done
note "done"
