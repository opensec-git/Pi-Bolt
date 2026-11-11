#!/usr/bin/env bash
# Sessions on the phone: open a project, new session, a streamed reply from the fake model, steer, abort, "Fork from
# here", resume an older session, and recover after the engine process is killed and after the app is killed.
# Everything after "open" needs the on-device engine.
. "$(dirname "$0")/lib.sh"
area_begin session

P=$(unique e2e-sess)
STEER="STEER-$RANDOM$RANDOM"

# Back in the session when an earlier case left the screen (or failed): Home -> project -> newest session.
ensure_session() {
	ui_find "text=Message Pi…" "text=Steer Pi…" >/dev/null && return 0
	go_home
	open_project "$P"
	if ui_find "re=^hello e2e$" >/dev/null; then tap "re=^hello e2e$"; else tap "text=New session"; fi
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the session did not become ready"
}

case_open() {
	fresh_app
	mkproject "$P"
	restart_app
	open_project "$P"
	assert_visible "text~=No sessions yet" "a new project should have no sessions"
	new_session
	# Empty session: the mark, the project name, "Start with" suggestions, the composer with the model chip.
	assert_visible "text=Start with"
	assert_visible "text=$P" "the top bar does not show the project title"
	assert_visible "desc~=Attach images" "composer bar missing"
}

case_prompt_and_streamed_reply() {
	needs_engine
	ensure_session
	fake_model_reachable || fail "the emulator cannot reach the fake model at $FAKE_HOST:$FAKE_PORT"
	assert_visible "text~=Fake Model" "the composer's model chip is not the fake model (models.json not picked up?)"
	send_prompt "hello e2e"
	assert_visible "re=^hello e2e$" "the prompt bubble is missing"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=Hello from the fake model" "no streamed reply from the fake model"
	assert_visible "text~=const answer = 42" "the reply's code block is missing"
	assert_fake_log '"event":"reply"' '"lastUser":"hello e2e"'
	# Settled: the composer is back to idle.
	assert_visible "text=Message Pi…"
}

case_steer() {
	needs_engine
	ensure_session
	send_prompt "e2e-steer please"
	assert_fake_log '"script":"e2e-steer"' '"event":"tool-call"'
	# While the tool runs the composer offers Steer (the default) and Follow-up.
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Steer Pi…" "the turn did not start running"
	tap "text=Steer Pi…"
	type_text "$STEER"
	# The Steer / Follow-up chip next to send toggles between the two; it starts on Steer.
	assert_visible "text=Steer" "no Steer / Follow-up choice while typing during a turn"
	tap "text=Steer"
	assert_visible "text=Follow-up"
	tap "text=Follow-up"
	assert_visible "text=Steer"
	tap_send
	hide_keyboard
	assert_fake_log '"event":"steer"' "$STEER"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=Steer received: $STEER" "the steer was not answered"
	# Pi delivers the steer with the finished tool's result, in the same request (the steer is the last message).
	assert_fake_log '"event":"steer"' "$STEER" "steer-tool-done"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the turn did not settle after the steer"
}

case_abort() {
	needs_engine
	ensure_session
	send_prompt "slow please"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=word3" "the slow reply did not start streaming"
	# Empty composer while running: the send button is Stop.
	tap_send
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Aborted" "no Aborted notice after Stop"
	assert_fake_log '"event":"aborted"' '"lastUser":"slow please"'
	assert_visible "text=Message Pi…"
	ui_find "text~=word199" >/dev/null && fail "the reply finished although it was stopped"
	return 0
}

case_fork_from_here() {
	needs_engine
	ensure_session
	SWIPES=8 scroll_up_to "re=^hello e2e$" || fail "the first prompt is not in the transcript"
	long_press "re=^hello e2e$"
	tap "text=Fork from here"
	assert_visible "text~=Forked into a new session" "no fork confirmation"
	local draft
	draft=$(field_text) || fail "no composer field"
	assert_contains "$draft" "hello e2e" "the forked message did not come back to the composer"
	# The fork starts before that message: the later turns are not in it.
	ui_find "text=Aborted" >/dev/null && fail "the fork still shows turns after the fork point"
	tap "re=^hello e2e$"
	key KEYCODE_MOVE_END
	type_text " forked"
	tap_send
	hide_keyboard
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=Hello from the fake model" "the forked session did not answer"
	assert_fake_log '"lastUser":"hello e2e forked"'
}

case_resume_old_session() {
	needs_engine
	ensure_session
	tap "desc=Project"
	assert_visible "text=projects/$P"
	assert_visible "text=Sessions · 2" "expected the original and the forked session"
	# The original session's title is its first prompt; the fork's is "hello e2e forked".
	tap "re=^hello e2e$"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Aborted" "resuming did not load the original session's transcript"
	ui_find "text~=Steer received" >/dev/null || SWIPES=6 scroll_up_to "text~=Steer received" || fail "the steer turn is missing from the resumed session"
	send_prompt "after resume"
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"lastUser":"after resume"'
	assert_fake_log '"lastUser":"after resume"' '"hello e2e"' '"slow please"'
}

case_engine_killed() {
	needs_engine
	ensure_session
	local before after
	before=$(engine_pids)
	kill_engine || fail "no engine process to kill"
	# Either the app restarts the engine by itself, or it shows "Engine stopped" with Restart (DESIGN.md).
	local end=$(($(now) + E2E_ENGINE_TIMEOUT)) restarted=0
	while [ "$(now)" -lt "$end" ]; do
		if ui_find "text=Engine stopped" >/dev/null; then
			log "banner: Engine stopped; tapping Restart"
			screenshot engine-stopped >/dev/null
			tap "text=Restart"
			restarted=1
			break
		fi
		after=$(engine_pids)
		if [ -n "$after" ] && [ "$after" != "$before" ] && ui_find "text=Message Pi…" >/dev/null; then
			log "engine restarted by itself: $before -> $after"
			restarted=2
			break
		fi
		sleep 1
	done
	[ $restarted = 0 ] && fail "after the engine was killed: no 'Engine stopped' banner and no new engine"
	WAIT=$E2E_ENGINE_TIMEOUT assert_gone "text=Engine stopped" "Restart did not bring the engine back"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	[ -n "$(engine_pids)" ] || fail "no engine process after the restart"
	send_prompt "after engine restart"
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"lastUser":"after engine restart"' '"hello e2e"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
}

case_app_killed() {
	needs_engine
	ensure_session
	local before after
	before=$(app_pid)
	background_and_kill
	[ -z "$(app_pid)" ] || fail "am kill did not stop the app process"
	[ -z "$(engine_pids)" ] || log "engine processes outlived the app: $(engine_pids)"
	launch_app
	after=$(app_pid)
	[ "$before" != "$after" ] || fail "the app process was not restarted"
	ensure_session
	send_prompt "after app kill"
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"lastUser":"after app kill"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
}

run_case open case_open
run_case prompt_and_streamed_reply case_prompt_and_streamed_reply
run_case steer case_steer
run_case abort case_abort
run_case fork_from_here case_fork_from_here
run_case resume_old_session case_resume_old_session
run_case engine_killed case_engine_killed
run_case app_killed case_app_killed
run_case no_crash assert_no_crash
