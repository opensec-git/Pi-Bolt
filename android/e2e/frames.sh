#!/bin/bash
# Frame timing on a phone, scenario by scenario (not a pass/fail area): Home, a project and its tabs, a 160-message
# transcript (code, tables, mermaid, tool steps), a streamed markdown reply, the keyboard and the command palette, a
# sheet, three subagent cards running, the Sources pill and the question card. Writes $OUT/frames.txt.
#
#   SERIAL=<adb serial> ./frames.sh [scenario ...]
#
# It measures the build that is installed; it installs nothing and clears no data. A debug build is set up through
# run-as; a non-debuggable one (benchmarkRelease) is not: set the phone up with the debug build first, then install it
# over that and pass the fake provider's port as FAKE_PORT. The app must already use the e2e fake provider
# (models.json's "fake" provider, as perf.sh sets it up): the fake model (fake-model-ext.mjs) is started on that
# provider's port and reached through `adb reverse`. A "smooth-bench" project with a long session is added
# (gen-long-session.mjs); remove it with REMOVE_BENCH=1. The Sources scenario needs the phone's network (pi-web-access
# searches for real).
#
# Each scenario resets `dumpsys gfxinfo`, runs, and records:
#   janky   frames that missed their deadline ("Janky frames"; the display's own refresh rate, 120 Hz on many phones)
#   p50/p90/p99  frame time from gfxinfo's histogram, leaving out its 4950ms bucket: some GPU drivers (the Xiaomi's)
#           never report a frame's GPU fence, and those frames land there; their count is printed as "+N unfenced".
# Raw `framestats` dumps stay next to frames.txt. Before every input the app must have the focus; else the script
# relaunches it and skips the step. Touch nothing else on the phone while it runs.
# FAKE_PORT as given (lib.sh fills in a default).
GIVEN_PORT=${FAKE_PORT:-}
. "$(dirname "$0")/lib.sh"
AREA=frames
OUT_DIR="$E2E_OUT/$SERIAL/frames"
mkdir -p "$OUT_DIR"
NUM="$OUT_DIR/frames.txt"
: >"$NUM"
FAKE_MODEL="$SUITE/fake-model-ext.mjs"
DATA=/data/data/$PKG
USER_ARG="--user ${ADB_USER:-0}"

focused() { adb_ shell dumpsys window | grep mCurrentFocus | grep -q "$PKG"; }
relaunch() { adb_ shell am start $USER_ARG -n "$PKG/$ACTIVITY" >/dev/null; sleep 2; }
# in <input args...>: one input event, only while the app has the focus.
in_() {
	if focused; then
		adb_ shell input "$@"
	else
		log "not focused, relaunching instead of: input $*"
		relaunch
		return 1
	fi
}
tap_on() {
	local xy
	xy=$(WAIT="${WAIT:-10}" wait_for "$@") || { log "nothing to tap for: $*"; return 1; }
	set -- $xy
	in_ tap "$1" "$2"
}
size() { adb_ shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1; }
read -r SCREEN_W SCREEN_H <<<"$(size)"
X=$((SCREEN_W / 2))
y() { echo $((SCREEN_H * $1 / 100)); }

reset() { adb_ shell dumpsys gfxinfo "$PKG" reset >/dev/null; }
record() {
	local raw="$OUT_DIR/$1.framestats.txt"
	adb_ shell dumpsys gfxinfo "$PKG" framestats | tr -d '\r' >"$raw"
	local janky hist
	janky=$(grep -m1 'Janky frames:' "$raw" | sed 's/^ *Janky frames: *//')
	hist=$(grep -m1 '^HISTOGRAM:' "$raw" | node -e '
		const h = require("fs").readFileSync(0, "utf8").trim().split(/\s+/).slice(1).map((x) => x.split("=")).map(([k, v]) => [parseInt(k), Number(v)]);
		const unk = h.filter(([k]) => k >= 4950).reduce((a, [, v]) => a + v, 0);
		const b = h.filter(([k]) => k < 4950);
		const n = b.reduce((a, [, v]) => a + v, 0);
		const p = (q) => { let c = 0; for (const [k, v] of b) { c += v; if (c >= q * n) return k; } return 0; };
		console.log(`frames ${n} (+${unk} unfenced) p50 ${p(0.5)}ms p90 ${p(0.9)}ms p99 ${p(0.99)}ms`);')
	printf '%-22s janky %-16s %s\n' "$1" "$janky" "$hist" | tee -a "$NUM"
}

# --- setup -----------------------------------------------------------------------------------------------------
if adb_ shell dumpsys package "$PKG" | grep -q 'flags=\[.*DEBUGGABLE'; then DEBUGGABLE=1; else DEBUGGABLE=0; fi
if [ "$DEBUGGABLE" = 1 ]; then
	port=$(run_as "cat $AGENT_REL/models.json" | tr -d '\r' | sed -n 's/.*"baseUrl":"http:\/\/127.0.0.1:\([0-9]*\)\/v1".*/\1/p' | head -1)
else
	port=$GIVEN_PORT
fi
[ -n "$port" ] || { echo "no fake provider port: set the app up as perf.sh does (a non-debuggable build needs FAKE_PORT)" >&2; exit 3; }
FAKE_PORT=$port
fake_model_start || exit 3
adb_ reverse "tcp:$FAKE_PORT" "tcp:$FAKE_PORT" >/dev/null
cleanup() {
	fake_model_stop
	adb_ reverse --remove "tcp:$FAKE_PORT" >/dev/null 2>&1
	if [ "${REMOVE_BENCH:-0}" = 1 ] && [ "$DEBUGGABLE" = 1 ]; then
		run_as "rm -rf files/home/projects/smooth-bench '$AGENT_REL/sessions/--data-data-$PKG-files-home-projects-smooth-bench--'"
	fi
}
trap cleanup EXIT
cwd="$DATA/files/home/projects/smooth-bench"
sessions="$AGENT_REL/sessions/--$(printf '%s' "${cwd#/}" | tr '/' '-')--"
if [ "$DEBUGGABLE" = 1 ] && ! dev_exists "$sessions/2026-01-01T00-00-00-000Z_long.jsonl"; then
	node "$SUITE/gen-long-session.mjs" "$cwd" 40 "$OUT_DIR/long.jsonl"
	run_as "mkdir -p files/home/projects/smooth-bench && echo '# smooth bench' > files/home/projects/smooth-bench/README.md"
	# Through stdin: the file (100 KB) is too long for dev_write's command line.
	run_as "mkdir -p '$sessions'"
	adb_ shell "run-as $PKG sh -c 'umask 077; cat > $sessions/2026-01-01T00-00-00-000Z_long.jsonl'" <"$OUT_DIR/long.jsonl"
fi
adb_ shell am force-stop $USER_ARG "$PKG"
relaunch
sleep 4

# Home through the app's own back arrows (one tap at a time, at most four), never a loop of system Back presses.
home_() {
	local i
	for i in 1 2 3 4; do
		dump_ui
		if ui find "text=Pi-Bolt" >/dev/null && ui find "desc=Settings" >/dev/null; then return 0; fi
		tap_on "desc=Project" "desc=Projects" "desc=Back" "desc=Close sheet" || relaunch
		sleep 1
	done
	ui_find "text=Pi-Bolt" >/dev/null
}
open_bench() {
	home_ || return 1
	tap_on "desc~=Projects," "text=Projects" || return 1
	sleep 0.8
	tap_on "text=smooth-bench" || return 1
	sleep 1.5
}
open_long() {
	open_bench || return 1
	tap_on "desc~=Turn 0: explain module 0" "text~=Turn 0: explain module 0" || return 1
	WAIT=60 wait_for "text=Message Pi…" >/dev/null
	sleep 1.5
}
type_send() {
	tap_on "text=Message Pi…" || return 1
	sleep 1.2
	in_ text "$(printf '%s' "$1" | sed 's/ /%s/g')" || return 1
	sleep 0.6
	tap_on "desc=Send" || return 1
}

# --- scenarios -------------------------------------------------------------------------------------------------
s_home() {
	home_ || return
	sleep 1
	reset
	for _ in 1 2 3 4; do
		in_ swipe $X "$(y 80)" $X "$(y 30)" 200
		sleep 0.6
		in_ swipe $X "$(y 30)" $X "$(y 80)" 200
		sleep 0.6
	done
	record home_scroll
	reset
	for tile in "desc~=Working," "desc~=Waiting for you,"; do
		tap_on "$tile" && sleep 1.2 && tap_on "desc=Close sheet" "desc=Close" && sleep 1
	done
	record home_tile_sheets
}
s_project() {
	home_ || return
	reset
	open_bench || return
	record project_open
	reset
	for _ in 1 2; do
		for tab in Files Changes Sessions; do
			tap_on "text=$tab" && sleep 1.2
		done
	done
	record project_tabs
	reset
	tap_on "desc~=Turn 0: explain module 0" "text~=Turn 0: explain module 0" && sleep 3
	record session_open_long
}
s_transcript() {
	open_long || return
	reset
	for _ in 1 2 3 4 5 6 7 8; do in_ swipe $X "$(y 30)" $X "$(y 72)" 120; sleep 0.5; done
	for _ in 1 2 3 4 5 6 7 8; do in_ swipe $X "$(y 72)" $X "$(y 30)" 120; sleep 0.5; done
	record transcript_fling
	reset
	for _ in 1 2 3 4 5 6 7 8 9 10; do in_ swipe $X "$(y 33)" $X "$(y 62)" 600; sleep 0.2; done
	record transcript_drag
}
s_stream() {
	open_long || return
	type_send "smooth-md stream" || return
	reset
	sleep 12
	record streaming_markdown
}
s_composer() {
	open_long || return
	reset
	tap_on "text=Message Pi…" && sleep 1.5
	record keyboard_open
	reset
	in_ text "/" && sleep 1.5
	record palette_open
	reset
	in_ keyevent KEYCODE_DEL && sleep 0.6
	focused && adb_ shell input keyevent KEYCODE_BACK
	sleep 1.5
	record keyboard_close
}
s_sheet() {
	open_long || return
	reset
	tap_on "desc~=Model:" && sleep 1.5
	record sheet_open
	reset
	in_ swipe $X "$(y 77)" $X "$(y 88)" 700
	sleep 1
	record sheet_drag
	ui_find "text=Model" >/dev/null && tap_on "desc=Close sheet" "desc=Close"
	sleep 1
}
s_agents() {
	open_long || return
	type_send "smooth-agents go" || return
	sleep 0.5
	reset
	sleep 9
	record subagents_running
}
s_sources() {
	open_long || return
	type_send "e2e-web-multi go" || return
	sleep 0.5
	reset
	sleep 10
	record sources_live
	reset
	tap_on "text=Sources" && sleep 1.5
	record sources_expand
}
s_question() {
	open_long || return
	type_send "e2e-ask go" || return
	sleep 0.5
	reset
	sleep 5
	record question_card
	reset
	sleep 5
	record question_card_idle
	tap_on "text=Cancel"
	sleep 1
}

ALL="home project transcript stream composer sheet agents sources question"
for s in ${*:-$ALL}; do
	log "scenario $s"
	"s_$s"
done
log "numbers in $NUM"
