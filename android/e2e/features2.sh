#!/usr/bin/env bash
# features2: session tree (/tree, labels), the sessions sheet (sort, search inside messages, rename, delete),
# packages, MCP servers, skills, trust, /reload, scoped models, retry and compaction, the retry banner's
# "Stop retrying", /import, /bug and the notification deep link. Needs the on-device engine.
FAKE_MODEL="${FAKE_MODEL:-$(dirname "$0")/fake-model-f2.mjs}"
. "$(dirname "$0")/lib.sh"
area_begin features2

P=$(unique e2e-f2)
SHOTS="${F2_SHOTS:-}"

shot() {
	local f
	f=$(screenshot "$1")
	[ -n "$SHOTS" ] && cp "$f" "$SHOTS/$1.png"
	return 0
}

ensure_session() {
	ui_find "text=Message Pi…" >/dev/null && return 0
	go_home
	open_project "$P"
	tap "re=^second e2e" "re=^branch e2e" "re=^hello e2e" "text=New session"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the session did not become ready"
}

close_sheet() {
	back
	sleep 0.6
	ui_find "text=Message Pi…" >/dev/null || back
	return 0
}

case_setup() {
	needs_engine
	fresh_app
	mkproject "$P" README.md "f2 project" ".pi/skills/review/SKILL.md" $'---\nname: review\ndescription: Review the change\n---\n# Review\nRead the diff.'
	printf -- '---\ndescription: Say hello in a template\n---\nSay hello to $1.\n' | dev_write "$AGENT_REL/prompts/hello-tpl.md"
	run_as "mkdir -p '$AGENT_REL/skills/demo'" >/dev/null
	printf -- '---\nname: demo\ndescription: A demo skill for e2e\n---\n# Demo\nDo the demo.\n' | dev_write "$AGENT_REL/skills/demo/SKILL.md"
	restart_app
	open_project "$P"
	new_session
	send_prompt "hello e2e"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=Hello from the fake model"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	send_prompt "second e2e"
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"lastUser":"second e2e"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	assert_device_file "$AGENT_REL/extensions/pibolt-app.js"
}

case_tree_branch_and_label() {
	needs_engine
	ensure_session
	send_prompt "/tree"
	WAIT=30 assert_visible "text=Session tree"
	assert_visible "desc~=User: hello e2e"
	shot tree-linear
	tap "desc~=User: second e2e"
	tap "text=Continue from before this prompt"
	# The prompt comes back to the composer; the transcript shows only the branch up to there.
	WAIT=30 assert_visible "text=second e2e" "the prompt did not come back to the composer"
	clear_field
	type_text "branch e2e"
	tap_send
	hide_keyboard
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"lastUser":"branch e2e"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	# Pi got only the earlier branch: no "second e2e" in its request.
	local req
	req=$(grep '"lastUser":"branch e2e"' "$FAKE_LOG" | tail -1)
	assert_contains "$req" '"hello e2e"'
	case "$req" in *'"second e2e"'*) fail "the abandoned prompt reached the model" ;; esac
	send_prompt "/tree"
	WAIT=30 assert_visible "text~=1 fork"
	long_press "desc~=User: hello e2e"
	WAIT=10 assert_visible "text=Label this entry"
	type_text "plan-a"
	tap "text=Save"
	WAIT=30 assert_visible "text=plan-a" "the label is not shown in the tree"
	shot tree-forked-labeled
	tap "text=Labeled"
	assert_visible "text=plan-a"
	shot tree-filter-labeled
	close_sheet
}

case_sessions_sheet() {
	needs_engine
	ensure_session
	send_prompt "/new"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	send_prompt "unique zebra words"
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"lastUser":"unique zebra words"'
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	send_prompt "/resume"
	WAIT=20 assert_visible "text=Sessions"
	assert_visible "text~=hello e2e"
	tap "text=Size"
	shot sessions-sort-size
	tap "text=Messages"
	tap "text=Search inside messages"
	type_text "branch"
	WAIT=20 assert_visible "text~=1 match"
	hide_keyboard
	shot sessions-search-messages
	tap "text=Names"
	clear_field
	hide_keyboard
	tap "desc~=Actions for hello e2e"
	tap "text=Rename"
	type_text "Tree demo"
	tap "text=Rename"
	WAIT=20 assert_visible "text=Tree demo" "the renamed session is not listed under its new name"
	tap "desc=Actions for Tree demo"
	tap "text=Delete"
	WAIT=10 assert_visible "text=Delete this session?"
	shot sessions-delete-confirm
	tap "text=Delete"
	WAIT=20 assert_gone "text=Tree demo" "the deleted session is still listed"
	close_sheet
}

case_packages() {
	needs_engine
	ensure_session
	send_prompt "/packages"
	WAIT=60 assert_visible "text~=opensec-pi-todo"
	assert_visible "text~=opensec-pi-subagents"
	assert_visible "text=Code mode"
	shot packages-list
	tap "text~=opensec-pi-todo"
	WAIT=40 assert_visible "text=todo" "the todo tool is not listed for the todo package"
	shot packages-detail-todo
	tap "text=Load this package"
	WAIT=20 assert_visible "text=Reload"
	run_as "cat $AGENT_REL/settings.json" | grep -q '"skills": \[\]' || fail "settings.json does not switch the package off"
	shot packages-switched-off
	tap "text~=opensec-pi-todo"
	WAIT=20 tap "text=Load this package"
	WAIT=20 assert_visible "text=Reload"
	run_as "cat $AGENT_REL/settings.json" | grep -q '"skills": \[\]' && fail "the package was not switched back on"
	tap "text=Reload"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
}

case_mcp() {
	needs_engine
	ensure_session
	send_prompt "/mcp-servers"
	WAIT=60 assert_visible "text=No MCP servers yet."
	tap "text=Add a server"
	tap "text~=Name (letters"
	type_text "e2edocs"
	tap "text~=https://example.com/mcp"
	type_text "http://$FAKE_HOST:$FAKE_PORT/mcp"
	hide_keyboard
	tap "text=Add"
	WAIT=90 assert_visible "text=e2edocs"
	shot mcp-list
	run_as "cat $AGENT_REL/mcp.json" | grep -q e2edocs || fail "mcp.json has no e2edocs"
	tap "desc=Remove e2edocs"
	tap "text=Remove"
	WAIT=90 assert_visible "text=No MCP servers yet."
	close_sheet
}

case_skills() {
	needs_engine
	ensure_session
	send_prompt "/skills"
	WAIT=20 assert_visible "text=/skill:demo"
	shot skills-list
	tap "text=/skill:demo"
	WAIT=10 assert_visible "text~=Do the demo"
	shot skills-view
	back
	sleep 0.5
	tap "text=Prompt templates"
	assert_visible "text=/hello-tpl"
	tap "text=Use"
	WAIT=10 assert_visible "text~=/hello-tpl" "Use did not put the template in the composer"
	clear_field
	hide_keyboard
}

case_trust() {
	needs_engine
	ensure_session
	send_prompt "/trust"
	WAIT=20 assert_visible "text~=Needs trust: .pi/skills"
	assert_visible "text=Not trusted"
	tap "text=Trust"
	WAIT=10 assert_visible "text=Trusted"
	run_as "cat $AGENT_REL/trust.json" | grep -q "projects/$P\": true" || fail "trust.json has no decision for the project"
	shot trust-trusted
	tap "text=Reload"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	# Trusted and reloaded: the project's own skill is there.
	send_prompt "/skills"
	WAIT=20 assert_visible "text=/skill:review" "the project skill did not load after trust and reload"
	shot skills-after-trust
	close_sheet
}

case_scoped_models_and_tuning() {
	needs_engine
	ensure_session
	send_prompt "/scoped-models"
	WAIT=20 assert_visible "text=Models in the picker"
	shot scoped-models
	close_sheet
	send_prompt "/tuning"
	WAIT=20 assert_visible "text=Retry and compaction"
	tap "text=Default: 90"
	type_text "85"
	tap "text=Default: 3"
	type_text "5"
	hide_keyboard
	shot tuning
	tap "text=Save"
	sleep 1
	local s
	s=$(run_as "cat $AGENT_REL/settings.json")
	assert_contains "$s" '"thresholdPercent": 85'
	assert_contains "$s" '"maxRetries": 5'
}

case_stop_retry() {
	needs_engine
	ensure_session
	send_prompt "e2e-overload please"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=Retrying 1/" "no retry banner"
	shot retry-banner
	tap "text=Stop retrying"
	WAIT=$E2E_ENGINE_TIMEOUT assert_gone "text=Stop retrying"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
	local n
	n=$(grep -c '"event":"overloaded"' "$FAKE_LOG")
	sleep 8
	[ "$(grep -c '"event":"overloaded"' "$FAKE_LOG")" = "$n" ] || fail "Pi kept retrying after Stop retrying"
}

case_import() {
	needs_engine
	ensure_session
	printf '{"type":"session","version":3,"id":"f2import-0000","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/Users/someone/code"}\n{"type":"message","id":"aa000001","parentId":null,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"user","content":"imported hello","timestamp":1767225601000}}\n' >"$OUT_DIR/f2-import.jsonl"
	adb_ push "$OUT_DIR/f2-import.jsonl" /sdcard/Download/f2-import.jsonl >/dev/null
	adb_ shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/f2-import.jsonl >/dev/null 2>&1
	send_prompt "/import"
	sleep 2
	ui_find "text=f2-import.jsonl" >/dev/null || { tap "desc=Show roots" && tap "text=Downloads"; }
	WAIT=20 assert_visible "text=f2-import.jsonl"
	shot import-picker
	tap "text=f2-import.jsonl"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "re=^imported hello$" "the imported session did not open"
	shot import-opened
}

case_bug_and_share() {
	needs_engine
	ensure_session
	send_prompt "/bug the tree sheet test"
	WAIT=10 assert_visible "text=Report a bug"
	assert_visible "text~=App: Pi-Bolt Android"
	shot bug-sheet
	close_sheet
	send_prompt "/share"
	WAIT=10 assert_visible "text=Share as Markdown"
	close_sheet
}

case_notification_deep_link() {
	needs_engine
	ensure_session
	local file
	file=$(run_as "ls -t $AGENT_REL/sessions/*$P*/*.jsonl" | tr -d '\r' | sed -n 2p)
	[ -n "$file" ] || fail "no older session file"
	go_home
	# What a session notification's tap sends (SessionAlerts.openIntent): project and session file.
	adb_ shell am start -n "$PKG/$ACTIVITY" -f 0x24000000 --es opensec.pibolt.session.project "$P" --es opensec.pibolt.session.file "/data/data/$PKG/$file" >/dev/null
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the deep link did not open the session"
	shot deep-link-opened
}

run_case setup case_setup
run_case tree_branch_and_label case_tree_branch_and_label
run_case sessions_sheet case_sessions_sheet
run_case packages case_packages
run_case mcp case_mcp
run_case skills case_skills
run_case trust case_trust
run_case scoped_models_and_tuning case_scoped_models_and_tuning
run_case stop_retry case_stop_retry
run_case import case_import
run_case bug_and_share case_bug_and_share
run_case notification_deep_link case_notification_deep_link
run_case no_crash assert_no_crash
