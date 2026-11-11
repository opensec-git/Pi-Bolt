#!/usr/bin/env bash
# Files tab: lists the project folder (folders first, dot files, item counts), walks into a folder and back, opens a
# text file and a binary file in the viewer. The last case needs the engine: a file Pi writes shows up in the list.
. "$(dirname "$0")/lib.sh"
area_begin files

P=$(unique e2e-files)

setup_project() {
	fresh_app
	mkproject "$P" \
		README.md $'# E2E readme\n\nline two of the readme\n' \
		src/main.txt $'fn main() {}\n' \
		.hidden $'x\n'
	# A small binary file (NUL bytes): the viewer must say so instead of showing garbage.
	printf '\000\001\002\003binary' | dev_write "$PROJECTS_REL/$P/blob.bin"
	restart_app
	open_project "$P"
}

# y coordinate of the first match
y_of() { ui_find "$1" | awk '{print $2}'; }

case_list() {
	setup_project
	tap "text=Files"
	assert_visible "text=README.md" "README.md not listed"
	assert_visible "text=src"
	assert_visible "text=.hidden" "dot files are not listed"
	assert_visible "text=blob.bin"
	ui_find "text=1 item" -- --row-of "text=src" >/dev/null || fail "the src folder does not say '1 item'"
	ui_find "re=^[0-9.]+ (B|KB)$" -- --row-of "text=README.md" >/dev/null || fail "README.md has no size"
	local src readme
	src=$(y_of "text=src")
	readme=$(y_of "text=README.md")
	[ "$src" -lt "$readme" ] || fail "folders are not listed before files (src y=$src, README.md y=$readme)"
}

case_folder_and_back() {
	ui_find "text=README.md" >/dev/null || { setup_project; tap "text=Files"; }
	tap "text=src"
	assert_visible "text=main.txt" "src/main.txt not listed inside src"
	assert_gone "text=README.md"
	# Back inside Files goes up a folder before it leaves the screen.
	back
	assert_visible "text=README.md" "back did not go up to the project root"
	assert_visible "text=projects/$P" "back left the project screen"
}

case_open_text_file() {
	ui_find "text=README.md" >/dev/null || { setup_project; tap "text=Files"; }
	tap "text=README.md"
	assert_visible "desc=Close" "the viewer did not open"
	assert_visible "text~=line two of the readme" "the viewer does not show the file's text"
	assert_visible "text=README.md"
	tap "desc=Close"
	assert_gone "text~=line two of the readme" "the viewer did not close"
	assert_visible "text=blob.bin"
}

case_open_binary_file() {
	ui_find "text=blob.bin" >/dev/null || { setup_project; tap "text=Files"; }
	tap "text=blob.bin"
	assert_visible "text~=Binary file" "binary file not reported as binary"
	tap "desc=Close"
	assert_gone "text~=Binary file"
}

case_file_written_by_pi() {
	needs_engine
	ui_find "text=New session" >/dev/null || setup_project
	new_session
	send_prompt "e2e-write"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=E2E-RESULT e2e-write" "no reply after the write command"
	tap "desc=Project"
	tap "text=Files"
	assert_visible "text=a.txt" "a.txt written by Pi is not listed"
	tap "text=a.txt"
	assert_visible "text~=hi"
	tap "desc=Close"
}

run_case list case_list
run_case folder_and_back case_folder_and_back
run_case open_text_file case_open_text_file
run_case open_binary_file case_open_binary_file
run_case file_written_by_pi case_file_written_by_pi
run_case no_crash assert_no_crash
