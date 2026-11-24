#!/usr/bin/env bash
# Measures three Pi-Bolt builds against each other with the drivers in drivers/ and writes the raw results to a folder.
# summarize.py turns that folder (one per OS) into the tables of README.md.
#
# Usage: measure.sh A B C OUT [STEP...]
#   A, B, C   a build: its launcher (`pi`, or `pi-start` on Linux x86-64), or the folder of an extracted release archive
#             (then `pi-start` if it has one, else `pi`)
#   OUT       the folder for the raw results (created; results are appended to files already there)
#   STEP      any of: startup modes session restored idle special grep standard (default: all, in that order);
#             extensions runs only the "typical home with extensions" row of standard
# Environment:
#   NAMES  the builds' names in the results, separated by commas (default 1.2.1,1.3.0,1.3.1; summarize.py reads them from
#          builds.txt, the first is the baseline)
#   EXT    extensions for the "typical home with extensions" row: unpacked npm packages, separated by commas, for example
#          the folders `npm pack opensec-pi-subagents opensec-pi-todo` gives once extracted. Without it that row is skipped.
#          Keep them outside a checkout of this repository: with its node_modules above them, the extensions' imports
#          resolve to the packages there, and the row measures loading those.
#   GREP_TREE  the source tree the grep step searches, for example `git archive <commit> packages | tar -x -C DIR` (DIR).
#              Without it that step is skipped.
#
# Order: the drivers that take the builds' order from the command line (all but empty.py and answer.py, which go through
# every order themselves) run once per rotation of the three builds (A B C, B C A, C A B); those that reverse the order
# every other round then cover all six orders. long_answer.py and measure_resume.py run once per order.
#
# Every workload talks to a fake model on 127.0.0.1. Nothing is sent to a real provider. Needs python3 and openssl; tmux is
# not used. About an hour on an M5 Mac; longer on a loaded machine.
#
# Example, on the official archives and a local build:
#   EXT=ext/opensec-pi-subagents,ext/opensec-pi-todo ./measure.sh 121/pi-bolt-darwin-arm64 130/pi-bolt-darwin-arm64 b131 mac
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
drivers="$here/drivers"
[ $# -ge 4 ] || { sed -n '2,25p' "$0"; exit 2; }

launcher() {
	local p="$1"
	if [ -d "$p" ]; then
		if [ -x "$p/pi-start" ]; then p="$p/pi-start"; else p="$p/pi"; fi
	fi
	[ -x "$p" ] || { echo "measure.sh: $1: no executable Pi launcher" >&2; exit 2; }
	case "$p" in *[[:space:]]*) echo "measure.sh: $p: a path with spaces" >&2; exit 2 ;; esac
	(cd "$(dirname "$p")" && echo "$PWD/$(basename "$p")")
}

paths=("$(launcher "$1")" "$(launcher "$2")" "$(launcher "$3")")
mkdir -p "$4"; out="$(cd "$4" && pwd)"
shift 4
steps=("$@")
[ ${#steps[@]} -gt 0 ] || steps=(startup modes session restored idle special grep standard)
IFS=, read -r -a names <<< "${NAMES:-1.2.1,1.3.0,1.3.1}"
[ ${#names[@]} = 3 ] || { echo "measure.sh: NAMES needs three names" >&2; exit 2; }
specs=("${names[0]}=${paths[0]}" "${names[1]}=${paths[1]}" "${names[2]}=${paths[2]}")

# --build arguments for the builds in the order given by indices into specs.
order() { local i; for i in "$@"; do printf '%s\0' --build "${specs[$i]}"; done; }
rotations=("0 1 2" "1 2 0" "2 0 1")
permutations=("0 1 2" "2 1 0" "1 2 0" "0 2 1" "2 0 1" "1 0 2")
builds=(); while IFS= read -r -d '' a; do builds+=("$a"); done < <(order 0 1 2)

# A home of its own, as the drivers make their own Pi homes: nothing is read from or written to the real ~/.pi.
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
export HOME="$scratch"

sum() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
note() { echo "=== $(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$out/progress.txt" >&2; }
load() { if [ "$(uname)" = Darwin ]; then sysctl -n vm.loadavg | tr -d '{}' | xargs; else cut -d' ' -f1-3 /proc/loadavg; fi; }

{
	echo "date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
	echo "host: $(uname -srm)"
	if [ "$(uname)" = Darwin ]; then
		echo "cpu: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
		echo "power: $(pmset -g batt | sed -n 's/^Now drawing from //p' | tr -d "'")"
	else
		echo "cpu: $(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | head -1), $(nproc) threads"
	fi
	echo "load: $(load)"
	for b in "${specs[@]}"; do
		name="${b%%=*}"; path="${b#*=}"; dir="$(dirname "$path")"
		echo "build $name: $path"
		echo "  version: $("$path" --version </dev/null 2>&1 | head -1)"
		for f in pi pi-bin pi-start; do [ -f "$dir/$f" ] && echo "  sha256 $f: $(sum "$dir/$f" | cut -d' ' -f1)"; done
	done
	echo "steps: ${steps[*]}"
} >> "$out/builds.txt"

py() { python3 -I "$@"; }

# Runs a driver once per rotation of the builds: per_rotation SCRIPT [ARG...] (the --build arguments come first).
per_rotation() {
	local r b
	for r in "${rotations[@]}"; do
		b=(); while IFS= read -r -d '' a; do b+=("$a"); done < <(order $r)
		py "$@" "${b[@]}"
	done
}

# benchmark.py in one home (blank, realistic or extensions), 4 runs per rotation.
bench_home() {
	local args=(--runs 4 --scenarios startup,headless,interactive --baseline "${names[0]}")
	case $1 in
	blank) ;;
	realistic) args+=(--home realistic) ;;
	extensions)
		if [ -z "${EXT:-}" ]; then note "standard: no EXT, skipping the typical home with extensions"; return; fi
		args+=(--home realistic --home-extensions "$EXT") ;;
	esac
	note "standard: benchmark.py, $1 home, 4 runs per rotation"
	per_rotation "$drivers/benchmark.py" "${args[@]}" --out "$out/standard-$1.jsonl" >> "$out/standard-$1.txt" 2>&1
}

for step in "${steps[@]}"; do
	note "load $(load)"
	case "$step" in
	startup)
		note "startup: blank home, launch to visible typing, 30 rounds"
		py "$drivers/empty.py" "${builds[@]}" --rounds 30 >> "$out/startup.jsonl" ;;
	modes)
		note "modes: headless, RPC and TUI single answer, 12 rounds"
		py "$drivers/answer.py" "${builds[@]}" --rounds 12 >> "$out/modes.jsonl" ;;
	session)
		note "session: 101 interactive tool prompts over HTTPS, 6 rounds, rotating order"
		py "$drivers/replay.py" "${builds[@]}" --tls --rotate-order --rounds 6 --turns 101 >> "$out/session.jsonl" 2>> "$out/session.log" ;;
	restored)
		note "restored: 6,000 restored messages in RPC, 2 rounds per rotation"
		per_rotation "$drivers/resumed.py" --entries 6000 --sentences 2 --rounds 2 >> "$out/restored.jsonl" ;;
	idle)
		note "idle: post-answer idle CPU and memory, 4 rounds of 15 s per rotation"
		per_rotation "$drivers/idle.py" --rounds 4 --seconds 15 >> "$out/idle.txt" ;;
	special)
		note "special: get_entries / get_session_stats on 12,000 entries, 2 rounds per rotation"
		per_rotation "$drivers/rpc.py" --rounds 2 >> "$out/special-rpc.jsonl"
		note "special: tree search, linear and branched, 2 rounds per rotation"
		per_rotation "$drivers/tree_filter.py" --rounds 2 >> "$out/special-tree.jsonl"
		note "special: 8 Mi and 32 Mi nested edit preview, 6 pairs per rotation"
		per_rotation "$drivers/nested.py" >> "$out/special-preview.jsonl" ;;
	grep)
		if [ -z "${GREP_TREE:-}" ]; then note "grep: no GREP_TREE, skipping"; continue; fi
		note "grep: 7 searches with the grep tool over $GREP_TREE, 12 rounds"
		echo "grep tree: $GREP_TREE, $(find "$GREP_TREE" -type f | wc -l | tr -d ' ') files" >> "$out/builds.txt"
		py "$drivers/grep_tree.py" "${builds[@]}" --tree "$GREP_TREE" --rounds 12 >> "$out/grep.jsonl" ;;
	extensions) bench_home extensions ;;
	standard)
		for home in blank realistic extensions; do bench_home $home; done
		r=0
		for p in "${permutations[@]}"; do
			r=$((r + 1))
			note "standard: 60,000-character answer, order $r of 6"
			b=(); while IFS= read -r -d '' a; do b+=("$a"); done < <(order $p)
			py "$drivers/long_answer.py" "${b[@]}" --sizes 60000 >> "$out/standard-long-answer.txt"
		done
		r=0
		for p in "${permutations[@]}"; do
			r=$((r + 1))
			note "standard: resuming a 2,292-entry session in fullscreen, order $r of 6"
			for i in $p; do
				b="${specs[$i]}"
				record="$(py "$drivers/measure_resume.py" "${b#*=}" 2292 fullscreen)"
				echo "{\"build\": \"${b%%=*}\", \"round\": $r, \"record\": $record}" >> "$out/standard-resume.jsonl"
			done
		done ;;
	*) echo "measure.sh: unknown step $step" >&2; exit 2 ;;
	esac
done
note "load $(load)"
note "done"
