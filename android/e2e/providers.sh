#!/usr/bin/env bash
# Model providers: the first-run key sheet with no keys, adding a key (a dummy string) hides it, the key is what Pi
# sends to the model, removing it in Settings, and "Later" leaving a quiet card on Home.
#
# The provider is the built-in "openai" one with an extra model "fake-model" at the fake server and no key in
# models.json (install_provider_config openai-key), so the key typed into the sheet is the only key Pi has.
. "$(dirname "$0")/lib.sh"
area_begin providers

KEY="e2e-dummy-key-not-real-$RANDOM"
P=$(unique e2e-prov)

# Where the app keeps provider credentials: Pi's auth.json (real engine) or the FakeEngine's id list.
auth_has() {
	if [ "$E2E_ENGINE" = real ]; then
		dev_exists "$AGENT_REL/auth.json" && dev_cat "$AGENT_REL/auth.json" | grep -q "\"$1\""
	else
		dev_exists files/dev-auth.txt && dev_cat files/dev-auth.txt | grep -qx "$1"
	fi
}

case_first_run_sheet() {
	stop_app
	clear_data
	install_provider_config openai-key
	launch_app
	assert_visible "text=Welcome to Pi-Bolt" "no first-run key sheet without keys"
	assert_visible "text=Connect a model"
	assert_visible "text=Later"
	assert_visible "text=OpenAI"
}

case_add_key_hides_sheet() {
	ui_find "text=Welcome to Pi-Bolt" >/dev/null || case_first_run_sheet
	# Search narrows the list.
	tap "text~=Search"
	type_text "openai"
	assert_visible "text=OpenAI"
	assert_gone "text=Anthropic" "search did not filter the providers"
	hide_keyboard
	tap "text=OpenAI"
	assert_visible "text~=Paste your OpenAI API key" "the key step did not open"
	assert_visible "text=Stored on this device only"
	tap "desc=API key"
	type_text "$KEY"
	# Obscured by default: the typed key is not on screen.
	ui_find "text~=$KEY" >/dev/null && fail "the key is shown in clear text before Show"
	hide_keyboard
	tap "text=Save key"
	assert_gone "text=Welcome to Pi-Bolt" "the sheet did not close after Save key"
	assert_gone "text~=Paste your OpenAI API key"
	assert_visible "text=Pi-Bolt"
	sleep 1
	auth_has openai || fail "the key was not stored for openai"
	if [ "$E2E_ENGINE" = real ]; then
		dev_cat "$AGENT_REL/auth.json" | grep -q "$KEY" || fail "auth.json does not hold the typed key"
		[ "$(run_as "stat -c %a '$AGENT_REL/auth.json'" | tr -d '\r')" = 600 ] || fail "auth.json is not mode 600"
	fi
	# No nudge card while a key exists, and no sheet after a restart.
	ui_find "text=Add key" >/dev/null && fail "the 'Connect a model' card shows although a key exists"
	restart_app
	sleep 1
	ui_find "text=Welcome to Pi-Bolt" >/dev/null && fail "the first-run sheet came back after a restart"
	return 0
}

case_key_reaches_model() {
	needs_engine
	go_home
	mkproject "$P"
	restart_app
	open_project "$P"
	new_session
	assert_visible "text~=Fake Model" "the composer's model is not openai/fake-model"
	send_prompt "hello key"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text~=Hello from the fake model" "no reply with the key from the sheet"
	assert_fake_log '"lastUser":"hello key"' "\"auth\":\"Bearer $KEY\""
}

case_remove_in_settings() {
	auth_has openai || fail "precondition: no openai key stored (add_key_hides_sheet failed)"
	go_home
	tap "desc=Settings"
	assert_visible "text=Model providers"
	assert_visible "text=OpenAI" "Settings does not list the stored provider"
	assert_visible "text=Key stored on this phone"
	tap "desc=Remove OpenAI"
	assert_visible "text=Remove OpenAI?" "no confirmation before removing a key"
	assert_visible "text~=it is not revoked at OpenAI"
	tap "text=Remove"
	assert_gone "text=Remove OpenAI?"
	assert_visible "text~=No provider yet" "Settings still lists a provider after Remove"
	sleep 1
	auth_has openai && fail "the key is still stored after Remove"
	tap "desc=Back"
	assert_visible "text=Add key" "Home does not offer 'Connect a model' once the only key is gone"
}

case_later_leaves_card() {
	stop_app
	clear_data
	install_provider_config openai-key
	launch_app
	assert_visible "text=Welcome to Pi-Bolt"
	tap "text=Later"
	assert_gone "text=Welcome to Pi-Bolt" "Later did not close the sheet"
	assert_visible "text=Add key" "no 'Connect a model' card after Later"
	restart_app
	sleep 1
	ui_find "text=Welcome to Pi-Bolt" >/dev/null && fail "the first-run sheet showed again after Later and a restart"
	assert_visible "text=Add key"
	# The card opens the same sheet without the welcome line.
	tap "text=Add key"
	assert_visible "text=Connect a model"
	ui_find "text=Welcome to Pi-Bolt" >/dev/null && fail "the card's sheet shows the first-run welcome"
	back
	return 0
}

run_case first_run_sheet case_first_run_sheet
run_case add_key_hides_sheet case_add_key_hides_sheet
run_case key_reaches_model case_key_reaches_model
run_case remove_in_settings case_remove_in_settings
run_case later_leaves_card case_later_leaves_card
run_case no_crash assert_no_crash
