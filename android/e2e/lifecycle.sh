#!/usr/bin/env bash
# Lifecycle around a running turn: rotate mid-stream, background and foreground, the foreground-service notification
# while the turn runs (and gone once it settles), and no ANR or crash in logcat. Needs the on-device engine.
. "$(dirname "$0")/lib.sh"
area_begin lifecycle

P=$(unique e2e-life)

ensure_session() {
	ui_find "text=Message Pi…" "text=Steer Pi…" >/dev/null && return 0
	go_home
	open_project "$P"
	tap "text=New session"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the session did not become ready"
}

# Highest "wordN" of the slow reply on screen (-1 when none).
last_word() {
	dump_ui >/dev/null || { echo -1; return; }
	ui texts | grep -o 'word[0-9]*' | sed 's/word//' | sort -n | tail -1 | grep . || echo -1
}

# Waits until the slow reply shows a word past N.
wait_word_past() {
	local n="$1" end w
	end=$(($(now) + ${WAIT:-$E2E_ENGINE_TIMEOUT}))
	while :; do
		w=$(last_word)
		[ "$w" -gt "$n" ] && { echo "$w"; return 0; }
		[ "$(now)" -ge "$end" ] && return 1
		sleep 1
	done
}

# The ongoing notification of SessionService: channel "session", text "Running"/"Working".
fgs_notification() { adb_ shell dumpsys notification --noredact | tr -d '\r' | grep -A40 "pkg=$PKG" | grep -E "channel=session|android.text=String \((Running|Working)" | head -2; }
fgs_running() { adb_ shell dumpsys activity services "$PKG" | tr -d '\r' | grep -q 'isForeground=true'; }

case_setup() {
	fresh_app
	mkproject "$P"
	restart_app
	open_project "$P"
	new_session
}

case_rotate_mid_stream() {
	needs_engine
	ensure_session
	local pid w
	pid=$(app_pid)
	send_prompt "slow please"
	w=$(wait_word_past 2) || fail "the slow reply did not start streaming"
	screenshot "before-rotate" >/dev/null
	adb_ shell settings put system user_rotation 1
	sleep 3
	screenshot "landscape" >/dev/null
	assert_visible "text=Steer Pi…" "the session screen lost its running turn after rotating"
	w=$(wait_word_past "$((w + 3))") || fail "the stream stopped after rotating to landscape"
	adb_ shell settings put system user_rotation 0
	sleep 3
	wait_word_past "$((w + 3))" >/dev/null || fail "the stream stopped after rotating back"
	assert_eq "$(app_pid)" "$pid" "the app process changed while rotating"
	ui_find "text=Engine stopped" >/dev/null && fail "the engine stopped while rotating"
	return 0
}

case_notification_while_running() {
	needs_engine
	ensure_session
	ui_find "text=Steer Pi…" >/dev/null || send_prompt "slow please"
	wait_word_past 0 >/dev/null || fail "no turn running"
	local n
	n=$(fgs_notification)
	[ -n "$n" ] || fail "no ongoing notification while a turn runs"
	fgs_running || fail "SessionService is not a foreground service while a turn runs"
}

case_background_foreground() {
	needs_engine
	ensure_session
	ui_find "text=Steer Pi…" >/dev/null || send_prompt "slow please"
	local before after pid
	before=$(wait_word_past 0) || fail "no turn running"
	pid=$(app_pid)
	key KEYCODE_HOME
	sleep 8
	fgs_running || fail "SessionService is not in the foreground while the app is in the background"
	[ -n "$(fgs_notification)" ] || fail "no ongoing notification while backgrounded mid-turn"
	[ -n "$(engine_pids)" ] || fail "the engine process died in the background"
	# Back to the app: the same process, the same session, the stream moved on meanwhile.
	adb_ shell am start -n "$PKG/$ACTIVITY" >/dev/null
	assert_visible "text=Steer Pi…" "the running session is not shown after coming back"
	after=$(wait_word_past "$((before + 10))") || fail "the reply did not advance while the app was in the background (frozen?)"
	assert_eq "$(app_pid)" "$pid" "the app process was replaced while in the background"
	log "words: $before before Home, $after after coming back"
}

case_notification_gone_when_settled() {
	needs_engine
	ensure_session
	if ui_find "text=Steer Pi…" >/dev/null; then tap_send; fi
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the turn did not settle after Stop"
	local end=$(($(now) + 15))
	while fgs_running; do
		[ "$(now)" -ge "$end" ] && fail "SessionService stays in the foreground after the turn settled"
		sleep 1
	done
	return 0
}

case_tool_turn_in_background() {
	needs_engine
	ensure_session
	send_prompt "e2e-long-tool"
	assert_fake_log '"script":"e2e-long-tool"' '"event":"tool-call"'
	key KEYCODE_HOME
	# The 20 s tool finishes and Pi sends its result while nothing of the app is visible.
	WAIT=60 assert_fake_log '"event":"tool-result"' '"script":"e2e-long-tool"' "tick 20"
	adb_ shell am start -n "$PKG/$ACTIVITY" >/dev/null
	hide_keyboard
	# The reply (its 20-line output block can push its first line off screen), settled, with the reply actions.
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Ask again" "the reply that arrived in the background is not shown as finished"
	assert_visible "text~=tick 20"
	assert_visible "text=Message Pi…"
}

case_no_anr_or_crash() {
	assert_no_crash
	grep -h 'ANR\|not responding' "$OUT_DIR"/*.texts.txt 2>/dev/null | grep -q 'Pi-Bolt' && fail "a Pi-Bolt ANR dialog was on screen"
	return 0
}

run_case setup case_setup
run_case rotate_mid_stream case_rotate_mid_stream
run_case notification_while_running case_notification_while_running
run_case background_foreground case_background_foreground
run_case notification_gone_when_settled case_notification_gone_when_settled
run_case tool_turn_in_background case_tool_turn_in_background
run_case no_anr_or_crash case_no_anr_or_crash
