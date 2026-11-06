#!/usr/bin/env bash
# Bundled extensions (0.2.1): opensec-pi-todo and opensec-pi-subagents in the on-device engine, driven by
# fake-model-ext.mjs. The `todo` tool creates, starts and completes a task while the todo panel above the composer
# shows "Todos" and the counts, then hides once all are done and the agent is idle. A foreground `Agent` call shows a
# readable card and its result. After the session is closed, no extra engine processes are left.
# Needs the on-device engine.
FAKE_MODEL="$(cd "$(dirname "$0")" && pwd)/fake-model-ext.mjs"
. "$(dirname "$0")/lib.sh"
area_begin extensions

P=$(unique e2e-ext)

ensure_session() {
	ui_find "text=Message Pi…" >/dev/null && return 0
	go_home
	open_project "$P"
	if ui_find "re=^e2e-todo-create$" >/dev/null; then tap "re=^e2e-todo-create$"; else tap "text=New session"; fi
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the session did not become ready"
}

# Saves the visible texts as evidence: texts <name>.
texts() { dump_ui >/dev/null && ui texts >"$OUT_DIR/$CASE-$1.texts.txt"; }

# The todo panel: one node whose description reads "Todos, <done> of <total> done" (TalkBack); each row reads
# "To do: …", "In progress: …" or "Done: …".
PANEL="desc~=Todos,"

# run_ext <keyword> <text expected in the tool output>: the tool call, its result back to the model, the reply.
run_ext() {
	local kw="$1" want="$2"
	send_prompt "$kw"
	assert_fake_log '"event":"tool-call"' "\"script\":\"$kw\""
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"event":"tool-result"' "\"script\":\"$kw\""
	TOOL_OUT=$(fake_tool_output "$kw")
	assert_contains "$TOOL_OUT" "$want" "$kw: tool output Pi returned to the model"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=E2E-RESULT $kw" "$kw: no reply after the tool result"
}

case_setup() {
	needs_engine
	fresh_app
	# The app registers the bundled extensions in settings.json when it unpacks the engine.
	local end=$(($(now) + E2E_ENGINE_TIMEOUT)) settings
	until settings=$(dev_cat "$AGENT_REL/settings.json") && case "$settings" in *pi-extensions*) true ;; *) false ;; esac; do
		[ "$(now)" -ge "$end" ] && break
		sleep 2
	done
	printf '%s\n' "$settings" >"$OUT_DIR/settings.json"
	assert_contains "$settings" "opensec-pi-todo" "settings.json packages"
	assert_contains "$settings" "opensec-pi-subagents" "settings.json packages"
	printf '%s' "$settings" | tr -d ' \n' | grep -q '"defaultProvider":"fake"' || fail "settings.json lost the fake provider"
	mkproject "$P" README.md $'# ext\n'
	restart_app
	open_project "$P"
	new_session
}

case_tools_offered() {
	needs_engine
	ensure_session
	run_ext e2e-todo-create "Created #1"
	# The request that asked for the todo call listed the extension tools.
	assert_fake_log '"event":"tool-call"' '"script":"e2e-todo-create"' '"todo"' '"Agent"'
}

case_todo_panel_create() {
	needs_engine
	ensure_session
	fake_tool_output e2e-todo-create >/dev/null || run_ext e2e-todo-create "Created #1"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the turn did not settle"
	assert_visible "$PANEL" "no todo panel above the composer after the todo call"
	texts panel
	assert_visible "desc=Todos, 0 of 1 done" "the todo panel does not read 'Todos, 0 of 1 done'"
	assert_visible "desc=To do: E2E task one" "the task is not listed as to do"
	# The panel sits above the composer.
	local panel composer
	panel=$(ui_find "$PANEL") || fail "panel gone"
	composer=$(ui_find "text=Message Pi…") || fail "composer gone"
	set -- $panel
	local ptop=$4
	set -- $composer
	[ "$ptop" -lt "$4" ] || fail "the todo panel is not above the composer (panel top $ptop, composer top $4)"
	screenshot panel-created >/dev/null
}

case_todo_start() {
	needs_engine
	ensure_session
	run_ext e2e-todo-update "pending → in_progress"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the turn did not settle"
	assert_visible "$PANEL" "the todo panel went away while a task is in progress"
	assert_visible "desc=Todos, 0 of 1 done" "the todo panel count changed for an in-progress task"
	assert_visible "desc=In progress: E2E task one" "the task is not shown in progress"
	texts panel
	screenshot panel-started >/dev/null
}

case_todo_complete_hides() {
	needs_engine
	ensure_session
	run_ext e2e-todo-done "in_progress → completed"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the turn did not settle"
	WAIT=10 assert_gone "$PANEL" "the todo panel still shows after every task is done and the agent is idle"
	texts after
	screenshot panel-hidden >/dev/null
}

case_agent_foreground() {
	needs_engine
	ensure_session
	send_prompt "e2e-agent"
	assert_fake_log '"event":"tool-call"' '"script":"e2e-agent"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"event":"subagent-request"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"event":"tool-result"' '"script":"e2e-agent"'
	TOOL_OUT=$(fake_tool_output e2e-agent)
	assert_contains "$TOOL_OUT" "SUBAGENT-OK" "e2e-agent: the subagent's answer Pi returned to the model"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=E2E-RESULT e2e-agent" "e2e-agent: no reply after the tool result"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the turn did not settle"
	# A finished turn folds its steps; open the fold to see the card.
	ui_find "text~=E2E subagent" >/dev/null || tap "re=^Worked( for)?"
	assert_visible "text=E2E subagent" "the Agent card does not show the task description"
	assert_visible "text~=general-purpose" "the Agent card does not show the agent type"
	# The card's own line is the answer alone: no "Agent completed in …" headline, no "Agent ID:" resume line.
	# (The fake model's reply echoes the raw tool output in a fenced block; that is not the card.)
	assert_visible "text=SUBAGENT-OK hello from the subagent." "the Agent card does not show the subagent's cleaned result"
	texts card
	grep -q 'subagent_type\|run_in_background' "$OUT_DIR/$CASE-card.texts.txt" && fail "the Agent card shows raw arguments"
	screenshot agent-card >/dev/null
}

# Processes of the app's uid other than the app itself: "pid name" lines.
uid_procs() {
	local uid
	uid=$(app_uid)
	adb_ shell "ps -A -o PID,UID,NAME,ARGS" | tr -d '\r' | awk -v u="$uid" -v pkg="$PKG" '$2 == u && $3 != pkg && $3 !~ /^ps$/ {print $1, $3, $4}'
}

# Engines by design (LocalEngine.kt): one spare kept idle for fast opens, plus the live session's engine. Back from a
# session keeps it live (Home shows it); opening another session stops it, and an idle one becomes the spare.
case_close_no_leftovers() {
	needs_engine
	ensure_session
	uid_procs >"$OUT_DIR/procs-in-session.txt"
	log "processes in the session: $(tr '\n' ';' <"$OUT_DIR/procs-in-session.txt")"
	back
	hide_keyboard
	ui_find "text=Message Pi…" >/dev/null && back
	assert_visible "text=projects/$P" "back did not leave the session"
	# Close the session: a new one in the same project replaces it.
	tap "text=New session"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the new session did not become ready"
	sleep 8
	uid_procs >"$OUT_DIR/procs-after-close.txt"
	log "processes after close: $(tr '\n' ';' <"$OUT_DIR/procs-after-close.txt")"
	local engines others
	engines=$(grep -c 'libpibolt' "$OUT_DIR/procs-after-close.txt")
	others=$(grep -v 'libpibolt' "$OUT_DIR/procs-after-close.txt" | grep -c .)
	[ "$others" = 0 ] || fail "non-engine processes left after closing the session: $(tr '\n' ';' <"$OUT_DIR/procs-after-close.txt")"
	[ "$engines" -le 2 ] || fail "$engines engine processes after closing the session (expected the new session's and one spare): $(tr '\n' ';' <"$OUT_DIR/procs-after-close.txt")"
	# Leaving the app (force-stop) ends every engine.
	stop_app
	sleep 4
	uid_procs >"$OUT_DIR/procs-after-stop.txt"
	[ -s "$OUT_DIR/procs-after-stop.txt" ] && fail "processes outlived the app: $(tr '\n' ';' <"$OUT_DIR/procs-after-stop.txt")"
	return 0
}

run_case setup case_setup
run_case tools_offered case_tools_offered
run_case todo_panel_create case_todo_panel_create
run_case todo_start case_todo_start
run_case todo_complete_hides case_todo_complete_hides
run_case agent_foreground case_agent_foreground
run_case close_no_leftovers case_close_no_leftovers
run_case no_crash assert_no_crash
