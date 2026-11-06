#!/usr/bin/env bash
# Tools on the phone: the scripted fake model asks for bash tool calls (ls, echo > file, git init/add/commit, rg, fd).
# Each case checks the command and its output in the transcript, the output Pi sent back to the model, and the
# files on the device via run-as. Needs the on-device engine (bash, git, rg, fd from the userland pack).
. "$(dirname "$0")/lib.sh"
area_begin tools

P=$(unique e2e-tools)
DIR="$PROJECTS_REL/$P"

ensure_session() {
	ui_find "text=Message Pi…" >/dev/null && return 0
	go_home
	open_project "$P"
	if ui_find "re=^Sessions · [1-9]" >/dev/null && ui_find "re=^e2e-ls$" >/dev/null; then tap "re=^e2e-ls$"; else tap "text=New session"; fi
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the session did not become ready"
}

# run_tool <keyword> <text expected in the output>: sends the keyword, waits for the tool round trip and the reply.
run_tool() {
	local kw="$1" want="$2"
	send_prompt "$kw"
	assert_fake_log '"event":"tool-call"' "\"script\":\"$kw\""
	WAIT=$E2E_ENGINE_TIMEOUT assert_fake_log '"event":"tool-result"' "\"script\":\"$kw\""
	TOOL_OUT=$(fake_tool_output "$kw")
	assert_contains "$TOOL_OUT" "$want" "$kw: tool output Pi returned to the model"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=E2E-RESULT $kw" "$kw: no reply after the tool result"
	assert_visible "text~=$want" "$kw: the output is not shown in the transcript"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "$kw: the turn did not settle"
}

case_setup() {
	fresh_app
	mkproject "$P" seed.txt $'seed\n'
	restart_app
	open_project "$P"
	new_session
}

case_ls() {
	needs_engine
	ensure_session
	run_tool e2e-ls seed.txt
	# A finished turn folds its steps ("Worked for 1s · 1 step"); opened, the tool row names the command.
	tap "text~=Worked for"
	assert_visible "text~=ls -la" "the bash tool row does not show its command"
	tap "text~=ls -la"
	assert_visible "text~=seed.txt"
}

case_echo_to_file() {
	needs_engine
	ensure_session
	run_tool e2e-write hi
	assert_device_file "$DIR/a.txt" hi
}

case_git_commit() {
	needs_engine
	ensure_session
	run_tool e2e-git " x"
	case "$TOOL_OUT" in *fatal* | *error* | *"not found"*) fail "git reported a problem: $TOOL_OUT" ;; esac
	assert_device_file "$DIR/.git/HEAD" "ref: refs/heads/"
	assert_device_file "$DIR/.git/logs/HEAD" "commit (initial): x"
	# The project header reads the branch from .git/HEAD.
	tap "desc=Project"
	assert_visible "desc=Branch" "the project screen shows no branch for a git repository"
	assert_visible "re=^(master|main)$"
	tap "re=^e2e-ls$"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…"
}

case_rg() {
	needs_engine
	ensure_session
	dev_exists "$DIR/a.txt" || printf 'hi\n' | dev_write "$DIR/a.txt"
	run_tool e2e-rg "a.txt:hi"
}

case_fd() {
	needs_engine
	ensure_session
	dev_exists "$DIR/a.txt" || printf 'hi\n' | dev_write "$DIR/a.txt"
	run_tool e2e-fd "a.txt"
	case "$TOOL_OUT" in *seed.txt*) fail "fd a matched seed.txt: $TOOL_OUT" ;; esac
	return 0
}

run_case setup case_setup
run_case ls case_ls
run_case echo_to_file case_echo_to_file
run_case git_commit case_git_commit
run_case rg case_rg
run_case fd case_fd
run_case no_crash assert_no_crash
