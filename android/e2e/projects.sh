#!/usr/bin/env bash
# Projects on Home: create an empty project, rename, pin, reopen after an app restart, delete with confirmation.
# UI only: none of this needs the engine except that the real engine's create also opens a session.
. "$(dirname "$0")/lib.sh"
area_begin projects

P=$(unique e2e-proj)
TITLE="Renamed E2E"

# The cases run in order on one project; each recreates it when an earlier case failed before making it.
ensure_project() {
	dev_exists "$PROJECTS_REL/$P" && return 0
	log "project missing (an earlier case failed): creating it directly"
	mkproject "$P"
	restart_app
}

card_more() { tap "desc=More" -- --row-of "text=$1"; }

case_create() {
	fresh_app
	tap "text=New project"
	assert_visible "text=A folder on this phone where Pi reads, edits and runs things." "New project sheet did not open"
	assert_visible "text=Empty folder"
	# The name field has focus when the sheet opens.
	type_text "$P"
	assert_visible "text=projects/$P" "the folder hint does not follow the name"
	tap "text=Create"
	assert_gone "text=A folder on this phone where Pi reads, edits and runs things." "the sheet stayed open after Create"
	assert_device_file "$PROJECTS_REL/$P"
	# Creating opens a new session in the folder.
	assert_visible "desc=Project" "creating did not open the project's session"
	go_home
	assert_visible "text=$P" "the new project is not on Home"
	# A second project with the same name (any case) is refused.
	tap "text=New project"
	type_text "$(printf '%s' "$P" | tr '[:lower:]' '[:upper:]')"
	assert_visible "text=A project with this name exists" "duplicate name accepted"
	hide_keyboard
	tap "text=Cancel"
	assert_gone "text=A project with this name exists"
}

case_rename() {
	ensure_project
	go_home
	card_more "$P"
	tap "text=Rename"
	assert_visible "text=Rename project"
	assert_visible "text=Changes the name shown in Pi-Bolt. The folder stays projects/$P."
	tap "text=$P"
	clear_field 80
	type_text "$TITLE"
	hide_keyboard
	tap "text=Rename"
	assert_gone "text=Rename project"
	assert_visible "text=$TITLE" "the card does not show the new title"
	assert_gone "text=$P" "the old title is still shown"
	# Only the shown title changes: the folder keeps its name.
	assert_device_file "$PROJECTS_REL/$P"
	assert_device_file files/projects.json "$TITLE"
}

# Our card is first: no other card's overflow button is above ours.
assert_first_card() {
	local mine first
	dump_ui
	mine=$(ui find "desc=More" --row-of "text=$1") || fail "no card for $1"
	first=$(ui find "desc=More" --index 0)
	assert_eq "$first" "$mine" "pinned project is not the first card"
}

case_pin() {
	ensure_project
	go_home
	local label="$TITLE"
	ui_find "text=$TITLE" >/dev/null || label="$P"
	card_more "$label"
	tap "text=Pin to top"
	assert_visible "desc=Pinned" "no pin mark after Pin to top"
	ui_find "desc=Pinned" -- --row-of "text=$label" >/dev/null || fail "the pin mark is not on $label's card"
	assert_first_card "$label"
	# The menu now offers Unpin.
	card_more "$label"
	assert_visible "text=Unpin"
	back
}

case_reopen_after_restart() {
	ensure_project
	restart_app
	assert_visible "text=$TITLE" "the title did not survive a restart"
	ui_find "desc=Pinned" -- --row-of "text=$TITLE" >/dev/null || fail "the pin did not survive a restart"
	assert_first_card "$TITLE"
	tap "text=$TITLE"
	assert_visible "text=projects/$P" "project screen did not open"
	assert_visible "desc=Pinned"
	assert_visible "text=New session"
	assert_visible "re=^Sessions"
	tap "desc=Projects"
	assert_visible "text=On this phone"
}

case_delete_with_confirm() {
	ensure_project
	go_home
	local label="$TITLE"
	ui_find "text=$TITLE" >/dev/null || label="$P"
	card_more "$label"
	tap "text=Delete"
	assert_visible "text=Delete $label?" "no delete confirmation"
	assert_visible "re=This deletes the folder projects/$P with"
	# Cancel keeps everything.
	tap "text=Cancel"
	assert_gone "text=Delete $label?"
	assert_device_file "$PROJECTS_REL/$P"
	assert_visible "text=$label"
	card_more "$label"
	tap "text=Delete"
	assert_visible "text=Delete $label?"
	tap "text=Delete"
	assert_gone "text=Delete $label?"
	assert_gone "text=$label" "the card is still on Home after Delete"
	assert_no_device_file "$PROJECTS_REL/$P"
	restart_app
	ui_find "text=$label" >/dev/null && fail "the deleted project came back after a restart"
	return 0
}

run_case create case_create
run_case rename case_rename
run_case pin case_pin
run_case reopen_after_restart case_reopen_after_restart
run_case delete_with_confirm case_delete_with_confirm
run_case no_crash assert_no_crash
