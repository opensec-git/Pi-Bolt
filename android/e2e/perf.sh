#!/bin/bash
# Performance measurements on one emulator (not a pass/fail area): cold start, session open (warm, cold, after
# background), first token, frame timing while streaming and while scrolling a 200-message session, memory, and
# idle wakeups. Writes $E2E_OUT/$SERIAL/perf/numbers.txt.
#
#   SERIAL=emulator-5570 APK=app-debug.apk [RELEASE_APK=app-release-signed.apk] ./perf.sh
#
# The app's data (fake provider, projects, a 200-message session) is set up with the debug APK (run-as); with
# RELEASE_APK, the release build signed with the same debug key is then installed over it (data kept) and measured.
# Timings come from logcat: PiBoltEngine "open ...: ready in N ms", PiBoltPerf "session ready" / "first token".
. "$(dirname "$0")/lib.sh"
FAKE_MODEL="$SUITE/fake-model-perf.mjs"
AREA=perf
OUT_DIR="$E2E_OUT/$SERIAL/perf"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
NUM="$OUT_DIR/numbers.txt"
: >"$NUM"
ROUNDS="${ROUNDS:-5}"
DATA=/data/data/$PKG
note() { printf '%s\n' "$*" | tee -a "$NUM" >&2; }
IDLE_S="${IDLE_S:-60}"

# A real phone: the fake model through adb reverse, and user 0 for pm/am (a work profile makes them pick user 10).
case "$SERIAL" in
emulator-*) PHONE= ;;
*)
	PHONE=1
	adb_ reverse "tcp:$FAKE_PORT" "tcp:$FAKE_PORT" >/dev/null
	install_apk() { log "installing $(basename "$APK")"; adb_ install -r -g --user 0 "$APK" >/dev/null && grant_permissions; }
	clear_data() { adb_ shell pm clear --user 0 "$PKG" >/dev/null; grant_permissions; run_as "mkdir -p files" >/dev/null; }
	launch_app() { adb_ shell am start --user 0 -W -n "$PKG/$ACTIVITY" >/dev/null; WAIT=30 wait_for "${APP_SCREENS[@]}" >/dev/null; }
	;;
esac

device_prepare
[ "${SKIP_INSTALL:-0}" = 1 ] || E2E_INSTALL=1 install_apk || exit 3
fake_model_start || exit 3
trap 'fake_model_stop' EXIT

# --- setup (debug build, run-as) -------------------------------------------------------------------------------
stop_app
clear_data
install_provider_config fake
mkproject perf-a "README.md" "# perf a"
mkproject perf-b "README.md" "# perf b"
mkproject perf-big "README.md" "# big"
for p in perf-a perf-b perf-big; do
	cwd="$DATA/files/home/projects/$p"
	dir="$AGENT_REL/sessions/--$(printf '%s' "${cwd#/}" | tr '/' '-')--"
	n=4
	[ $p = perf-big ] && n=200
	node "$SUITE/gen-session.mjs" "$cwd" $n "$OUT_DIR/$p.jsonl"
	dev_write "$dir/2026-01-01T00-00-00-000Z_$p.jsonl" <"$OUT_DIR/$p.jsonl"
done
# First start installs the prefix; let it finish before measuring.
launch_app
sleep 15
if [ -n "${RELEASE_APK:-}" ]; then
	stop_app
	log "installing the release build over the debug one"
	adb_ install -r ${PHONE:+--user 0} "$RELEASE_APK" >/dev/null || exit 3
	launch_app
	sleep 15
fi

logt() { adb_ logcat -d -s PiBoltEngine:I PiBoltPerf:I 2>/dev/null | tr -d '\r'; }

# --- cold start ------------------------------------------------------------------------------------------------
for i in $(seq "$ROUNDS"); do
	stop_app
	sleep 2
	adb_ logcat -c
	t0=$(python3 -c 'import time; print(int(time.time()*1000))')
	total=$(adb_ shell am start ${PHONE:+--user 0} -W -n "$PKG/$ACTIVITY" | tr -d '\r' | sed -n 's/^TotalTime: //p')
	WAIT=20 wait_for "text=perf-big" >/dev/null
	t1=$(python3 -c 'import time; print(int(time.time()*1000))')
	sleep 12
	spare=$(logt | sed -n 's/.*spare engine ready: cold spawn to first answer \([0-9]*\) ms.*/\1/p' | tail -1)
	note "cold_start_first_frame_ms $total"
	note "cold_start_home_content_ms_poll $((t1 - t0))"
	note "spare_engine_cold_spawn_ms ${spare:-none}"
done

# --- session opens ---------------------------------------------------------------------------------------------
open_timing() { logt | grep -E "open .*ready in|session ready" | tail -2 | tr '\n' ' '; }
wait_ready() { WAIT=60 wait_for "text=Message Pi…" >/dev/null; }
open_saved() {
	# Home -> project -> its saved session (title "Question 0: ...").
	go_home
	SWIPES=4 scroll_to "text=$1" >/dev/null
	tap "text=$1"
	tap "text~=Question 0"
	wait_ready
}
for i in $(seq "$ROUNDS"); do
	stop_app
	launch_app
	sleep 12 # the spare is up
	adb_ logcat -c
	open_saved perf-a
	note "open_resume_after_cold_start $(open_timing)"
	adb_ logcat -c
	open_saved perf-b
	note "open_resume_other_project $(open_timing)"
	adb_ logcat -c
	open_saved perf-a
	note "open_resume_back_to_first $(open_timing)"
	go_home
	tap "text=perf-b"
	adb_ logcat -c
	tap "text=New session"
	wait_ready
	note "open_new_session_same_project $(open_timing)"
	go_home
	tap "text=perf-a"
	adb_ logcat -c
	tap "text=New session"
	wait_ready
	note "open_new_session_other_project $(open_timing)"
	# Background for a minute (TRIM_MEMORY_BACKGROUND arrives), come back, open another project's session.
	adb_ shell input keyevent KEYCODE_HOME
	sleep 60
	adb_ logcat -c
	adb_ shell am start ${PHONE:+--user 0} -n "$PKG/$ACTIVITY" >/dev/null
	sleep 1
	open_saved perf-big
	note "open_after_background_60s $(open_timing)"
	note "spare_stopped_in_background $(adb_ logcat -d -b all 2>/dev/null | grep -c 'stopping the spare engine')"
done

# --- first token, streaming frames -----------------------------------------------------------------------------
open_saved perf-a
for i in $(seq "$ROUNDS"); do
	adb_ logcat -c
	send_prompt "hello $i"
	sleep 3
	note "first_token $(logt | grep 'first token' | tail -1 | sed 's/.*first token //')"
done
gfx() { adb_ shell dumpsys gfxinfo "$PKG" | tr -d '\r' | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile" | sed 's/^ *//' | tr '\n' ';'; }
adb_ shell dumpsys gfxinfo "$PKG" reset >/dev/null
send_prompt "perf-long please"
sleep 25
note "frames_streaming_long_reply $(gfx)"

# --- scrolling a 200-message session ---------------------------------------------------------------------------
open_saved perf-big
sleep 2
adb_ shell dumpsys gfxinfo "$PKG" reset >/dev/null
for i in 1 2 3 4 5 6; do
	adb_ shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 3 / 10)) $((SCREEN_W / 2)) $((SCREEN_H * 8 / 10)) 150
	sleep 0.4
done
for i in 1 2 3 4 5 6; do
	adb_ shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 8 / 10)) $((SCREEN_W / 2)) $((SCREEN_H * 3 / 10)) 150
	sleep 0.4
done
note "frames_scroll_200 $(gfx)"

# --- memory ----------------------------------------------------------------------------------------------------
pss=$(adb_ shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS|TOTAL RSS|^ *TOTAL " | head -2 | tr -s ' ' | tr '\n' ';')
note "app_meminfo $pss"
rss_of() { adb_ shell "cat /proc/$1/status 2>/dev/null" | tr -d '\r' | sed -n 's/^VmRSS: *//p'; }
note "app_rss $(rss_of "$(app_pid)")"
for pid in $(adb_ shell "ps -A -o PID,ARGS" | tr -d '\r' | grep libpibolt | grep -v grep | awk '{print $1}'); do
	note "engine_rss pid $pid $(rss_of "$pid")"
done

# --- idle wakeups ----------------------------------------------------------------------------------------------
# Context switches of every thread of the app process (and the engines) over 60 s with the session screen idle.
switches() {
	adb_ shell "for t in /proc/$1/task/*; do cat \$t/status; done 2>/dev/null" | tr -d '\r' | awk '/ctxt_switches/ {s += $2} END {print s + 0}'
}
idle_check() {
	local label="$1" pid a b engines ea eb
	pid=$(app_pid)
	engines=$(adb_ shell "ps -A -o PID,ARGS" | tr -d '\r' | grep libpibolt | grep -v grep | awk '{print $1}')
	a=$(switches "$pid")
	ea=0
	for e in $engines; do ea=$((ea + $(switches "$e"))); done
	sleep "$IDLE_S"
	b=$(switches "$pid")
	eb=0
	for e in $engines; do eb=$((eb + $(switches "$e"))); done
	note "idle_wakeups_${IDLE_S}s_$label app $((b - a)) engines $((eb - ea)) ($(echo $engines | wc -w | tr -d ' ') engines)"
}
hide_keyboard
idle_check session_screen
go_home
idle_check home
adb_ shell input keyevent KEYCODE_HOME
sleep 5
idle_check backgrounded
log "numbers in $NUM"
