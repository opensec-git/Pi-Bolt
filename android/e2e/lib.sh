# shellcheck shell=bash
# Pi-Bolt Android on-device E2E helpers. Source from an area script; every helper acts on $SERIAL.
# Bash 3.2 compatible (macOS /bin/bash): no associative arrays, no ${x,,}, no mapfile.
#
# Environment:
#   SERIAL        adb serial (required), e.g. emulator-5556. emulator-5554 (the shared pibolt-api36) is refused.
#   APK           debug APK to install (default: $SUITE/build/app-debug.apk)
#   E2E_OUT       results root (default: $SUITE/out); an area writes to $E2E_OUT/$SERIAL/<area>/
#   E2E_ENGINE    real | stand-in (default: detected: real when the APK ships lib/arm64-v8a/libpibolt.so)
#   E2E_INSTALL   1 to (re)install the APK at area start (default: install only when missing)
#   FAKE_MODEL    fake model server script (default: $SUITE/fake-model-scripted.mjs; extensions.sh uses fake-model-ext.mjs)
#   FAKE_PORT     host port of this device's fake model (default: 13000 + emulator console port, so 18556 for 5556)
#   E2E_TIMEOUT   default wait in seconds (20); E2E_ENGINE_TIMEOUT for engine work (90)
#   E2E_SKIP_ENGINE=1  skip the cases that need the on-device engine (stand-in APK runs only the UI parts)

SUITE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG=in.opensec.pibolt
ACTIVITY=opensec.pibolt.app.MainActivity
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
APK="${APK:-$SUITE/build/app-debug.apk}"
E2E_OUT="${E2E_OUT:-$SUITE/out}"
FAKE_MODEL="${FAKE_MODEL:-$SUITE/fake-model-scripted.mjs}"
E2E_TIMEOUT="${E2E_TIMEOUT:-20}"
E2E_ENGINE_TIMEOUT="${E2E_ENGINE_TIMEOUT:-90}"
AGENT_REL=files/home/.pi/agent

if [ -z "${SERIAL:-}" ]; then
	echo "SERIAL is not set (e.g. SERIAL=emulator-5556 $0)" >&2
	exit 2
fi
if [ "$SERIAL" = "emulator-5554" ] && [ "${E2E_ALLOW_5554:-}" != "yes-i-own-it" ]; then
	echo "refusing emulator-5554: it is the shared pibolt-api36 used by the runtime agent" >&2
	exit 2
fi
case "$SERIAL" in
emulator-*) FAKE_PORT="${FAKE_PORT:-$((13000 + ${SERIAL#emulator-}))}" ;;
*) FAKE_PORT="${FAKE_PORT:-18765}" ;;
esac
# The emulator reaches the host's loopback at 10.0.2.2. A physical device would need `adb reverse` and 127.0.0.1.
FAKE_HOST="${FAKE_HOST:-10.0.2.2}"

if [ -z "${E2E_ENGINE:-}" ]; then
	if unzip -l "$APK" 2>/dev/null | grep -q 'lib/arm64-v8a/libpibolt.so'; then E2E_ENGINE=real; else E2E_ENGINE=stand-in; fi
fi
# Where project folders live: HOME/projects for the real engine (CONTRACT.md), dev-projects for the UI's FakeEngine.
if [ "$E2E_ENGINE" = real ]; then PROJECTS_REL=files/home/projects; else PROJECTS_REL=files/dev-projects; fi

adb_() { "$ADB" -s "$SERIAL" "$@"; }
now() { date +%s; }
log() { printf '[%s %s %s] %s\n' "$(date +%H:%M:%S)" "$SERIAL" "${AREA:-}${CASE:+/$CASE}" "$*" >&2; }

# ---------------------------------------------------------------------------------------------------------------
# Device shell as the app (debug builds only), with any quoting: the command travels base64-encoded.

run_as() {
	local b64
	b64=$(printf '%s' "$1" | base64 | tr -d '\n')
	adb_ shell "run-as $PKG sh -c \"\$(echo $b64 | base64 -d)\""
}

# Write stdin to a path relative to the app's data dir (parents created, mode 600).
dev_write() {
	local rel="$1" b64
	b64=$(base64 | tr -d '\n')
	run_as "mkdir -p \"\$(dirname '$rel')\" && echo '$b64' | base64 -d > '$rel' && chmod 600 '$rel'"
}

dev_exists() { [ "$(run_as "[ -e '$1' ] && echo yes || echo no" | tr -d '\r')" = yes ]; }
dev_cat() { run_as "cat '$1'" | tr -d '\r'; }

# ---------------------------------------------------------------------------------------------------------------
# Device and app setup

wait_boot() {
	local t=0
	timeout 300 "$ADB" -s "$SERIAL" wait-for-device || { echo "$SERIAL: wait-for-device failed" >&2; return 1; }
	until [ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
		sleep 2
		t=$((t + 2))
		[ $t -gt 300 ] && { echo "$SERIAL did not boot" >&2; return 1; }
	done
}

# Animations off: steadier dumps, and the app's "Reduce motion" follows the system setting (static spinners).
device_prepare() {
	wait_boot || return 1
	local k
	for k in window_animation_scale transition_animation_scale animator_duration_scale; do adb_ shell settings put global "$k" 0; done
	adb_ shell settings put system accelerometer_rotation 0
	adb_ shell settings put system user_rotation 0
	adb_ shell settings put secure show_ime_with_hard_keyboard 0 >/dev/null 2>&1
	adb_ shell svc power stayon true >/dev/null 2>&1
	adb_ shell input keyevent KEYCODE_WAKEUP
	adb_ shell wm dismiss-keyguard >/dev/null 2>&1
	SCREEN=$(adb_ shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*x[0-9]*\).*/\1/p' | tail -1)
	SCREEN_W=${SCREEN%x*}
	SCREEN_H=${SCREEN#*x}
}

install_apk() {
	[ -f "$APK" ] || { echo "APK not found: $APK" >&2; return 1; }
	log "installing $(basename "$APK") ($E2E_ENGINE engine)"
	local out
	if ! out=$(adb_ install -r -g "$APK" 2>&1); then
		# A release build or a newer version is installed (other signer, or a higher versionCode): replace it.
		case "$out" in
		*INSTALL_FAILED_VERSION_DOWNGRADE* | *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *signatures\ do\ not\ match*)
			log "replacing the installed $PKG ($(printf '%s' "$out" | grep -o 'INSTALL_FAILED_[A-Z_]*' | head -1))"
			adb_ uninstall "$PKG" >/dev/null 2>&1
			adb_ install -r -g "$APK" >/dev/null || return 1
			;;
		*) printf '%s\n' "$out" >&2; return 1 ;;
		esac
	fi
	# adb installs of a debug APK skip dex verification; the first start then verifies every dex file on the main
	# thread inside bindApplication, which on a loaded host exceeds the startup deadline (an ANR "failed to complete
	# startup"). Verify ahead of time instead.
	adb_ shell cmd package compile -m verify -f "$PKG" >/dev/null 2>&1
	grant_permissions
}

grant_permissions() {
	adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
	adb_ shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1
}

app_installed() { adb_ shell pm path "$PKG" 2>/dev/null | grep -q package:; }

app_uid() { adb_ shell pm list packages -U "$PKG" | tr -d '\r' | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p" | head -1; }

clear_data() {
	log "clearing app data"
	adb_ shell pm clear "$PKG" >/dev/null
	grant_permissions
	# pm clear leaves the data dir; files/ comes back on first start, so create it for run-as writes.
	run_as "mkdir -p files" >/dev/null
}

stop_app() { adb_ shell am force-stop "$PKG"; }

APP_SCREENS=("text=Pi-Bolt" "text=Connect a model" "any=Message Pi…" "desc=Project" "desc=Projects" "text=Settings")
launch_app() {
	adb_ shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
	WAIT=$((E2E_TIMEOUT + 10)) wait_for "${APP_SCREENS[@]}" >/dev/null && return 0
	# A loaded host can miss the system's 10 s process-start deadline (am_process_start_timeout): try once more.
	if adb_ logcat -d -b events 2>/dev/null | grep "am_process_start_timeout.*$PKG" | tail -1 | grep -q .; then
		log "process start timed out (host load); launching again"
		echo "am_process_start_timeout at $(date +%T) in ${CASE:-?}" >>"$OUT_DIR/start-timeouts.txt"
	fi
	adb_ shell am start -W -n "$PKG/$ACTIVITY" >/dev/null
	WAIT=$((E2E_TIMEOUT + 10)) wait_for "${APP_SCREENS[@]}" >/dev/null || fail "the app did not show a screen after launch"
}

restart_app() {
	stop_app
	launch_app
}

app_pid() { adb_ shell pidof "$PKG" | tr -d '\r'; }

# Engine processes: libpibolt.so exec'd from nativeLibraryDir (CONTRACT.md), owned by the app's uid.
engine_pids() { run_as "pgrep -f libpibolt.so" | tr -d '\r' | tr '\n' ' '; }

kill_engine() {
	local pids
	pids=$(engine_pids)
	log "killing engine pids: ${pids:-none}"
	[ -n "$pids" ] && run_as "kill -9 $pids" >/dev/null
	[ -n "$pids" ]
}

# Background the app (Home), then `am kill`: the system's "killed in the background" path, not force-stop.
background_and_kill() {
	adb_ shell input keyevent KEYCODE_HOME
	sleep 2
	adb_ shell am kill "$PKG"
	sleep 2
}

# ---------------------------------------------------------------------------------------------------------------
# Fake model provider: models.json in the engine's agent dir (HOME/.pi/agent), see CONTRACT.md.
#
# install_provider_config [fake|openai-key]
#   fake        provider "fake" with an inline dummy key, default model fake/fake-model; auth.json gets a dummy "fake"
#               entry so the first-run key sheet stays away.
#   openai-key  the built-in "openai" provider gains model fake-model at the fake server, with no key: the key comes
#               from auth.json, i.e. from the app's key sheet (providers.sh). No auth.json is written.

install_provider_config() {
	local mode="${1:-fake}" base="http://$FAKE_HOST:$FAKE_PORT/v1" model
	model='{"id":"fake-model","name":"Fake Model (e2e)","api":"openai-completions","reasoning":false,"input":["text"],"contextWindow":128000,"maxTokens":4096,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0}'
	log "provider config: $mode -> $base"
	if [ "$mode" = openai-key ]; then
		printf '{"providers":{"openai":{"models":[%s,"baseUrl":"%s"}]}}}\n' "$model" "$base" | dev_write "$AGENT_REL/models.json"
		printf '{"defaultProvider":"openai","defaultModel":"fake-model","quietStartup":true}\n' | dev_write "$AGENT_REL/settings.json"
		run_as "rm -f '$AGENT_REL/auth.json' files/dev-auth.txt" >/dev/null
	else
		printf '{"providers":{"fake":{"baseUrl":"%s","api":"openai-completions","apiKey":"e2e-dummy-not-a-key","models":[%s}]}}}\n' "$base" "$model" | dev_write "$AGENT_REL/models.json"
		printf '{"defaultProvider":"fake","defaultModel":"fake-model","quietStartup":true}\n' | dev_write "$AGENT_REL/settings.json"
		printf '{\n  "fake": {\n    "type": "api_key",\n    "key": "e2e-dummy-not-a-key"\n  }\n}' | dev_write "$AGENT_REL/auth.json"
		# The stand-in FakeEngine keeps provider ids in files/dev-auth.txt instead of auth.json.
		printf 'fake\n' | dev_write files/dev-auth.txt
	fi
}

fake_model_start() {
	FAKE_LOG="$OUT_DIR/fake-model-requests.jsonl"
	: >"$FAKE_LOG"
	local stale
	stale=$(lsof -ti "tcp:$FAKE_PORT" -sTCP:LISTEN 2>/dev/null)
	[ -n "$stale" ] && kill $stale 2>/dev/null && sleep 0.5
	node "$FAKE_MODEL" "$FAKE_PORT" "$FAKE_LOG" >"$OUT_DIR/fake-model.out" 2>&1 &
	FAKE_PID=$!
	local i=0
	while [ $i -lt 60 ]; do
		i=$((i + 1))
		curl -sf "http://127.0.0.1:$FAKE_PORT/health" >/dev/null 2>&1 && { log "fake model on host port $FAKE_PORT (pid $FAKE_PID)"; return 0; }
		sleep 0.3
	done
	echo "fake model did not start on $FAKE_PORT" >&2
	return 1
}

fake_model_stop() { [ -n "${FAKE_PID:-}" ] && kill "$FAKE_PID" 2>/dev/null; FAKE_PID=; }

# The device can open a TCP connection to the fake model.
fake_model_reachable() { adb_ shell "echo | nc -w 3 $FAKE_HOST $FAKE_PORT >/dev/null 2>&1 && echo ok" | grep -q ok; }

# Wait until one line of the fake model's request log contains every given string (ignoring case: the keyboard
# may capitalise the first letter of a prompt).
fake_log_has() {
	local lines s
	lines=$(cat "$FAKE_LOG" 2>/dev/null)
	for s in "$@"; do lines=$(printf '%s\n' "$lines" | grep -F -i -- "$s"); done
	[ -n "$lines" ]
}

# Output of the last tool result Pi sent for a script keyword (what the engine's bash printed).
fake_tool_output() {
	node -e 'const [f, k] = process.argv.slice(1); let out = null;
for (const l of require("fs").readFileSync(f, "utf8").split("\n")) { try { const e = JSON.parse(l); if (e.event === "tool-result" && e.script === k) out = e.output; } catch {} }
if (out === null) process.exit(1); process.stdout.write(out);' "$FAKE_LOG" "$1"
}

wait_fake_log() {
	local end
	end=$(($(now) + ${WAIT:-$E2E_ENGINE_TIMEOUT}))
	until fake_log_has "$@"; do
		[ "$(now)" -ge "$end" ] && return 1
		sleep 1
	done
}

# ---------------------------------------------------------------------------------------------------------------
# UI: uiautomator dump + ui.mjs queries. Queries: text=, text~=, desc=, desc~=, any=, any~=, re=

UI_XML=
dump_ui() {
	UI_XML="$OUT_DIR/.ui.xml"
	local i
	for i in 1 2 3 4; do
		adb_ exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierch*ary dumped to.*//' >"$UI_XML"
		if grep -q '<hierarchy' "$UI_XML"; then
			handle_system_dialogs && return 0
			continue
		fi
		sleep 0.5
	done
	return 1
}

# Dismisses dialogs that are not ours (a slow emulator's "System UI isn't responding", a permission prompt).
# An ANR dialog for Pi-Bolt itself is recorded (assert_no_crash fails on it) and then waited out.
handle_system_dialogs() {
	local ui="node $SUITE/ui.mjs"
	if $ui find "$UI_XML" "text~=isn't responding" >/dev/null; then
		if $ui find "$UI_XML" "text~=Pi-Bolt isn" >/dev/null; then
			log "ANR dialog for Pi-Bolt"
			echo "ANR dialog seen at $(date +%T) in ${CASE:-?}" >>"$OUT_DIR/anr-dialogs.txt"
			screenshot "anr-dialog" >/dev/null
		else
			log "dismissing a system 'not responding' dialog"
		fi
		tap_first "text=Wait"
		return 1
	fi
	if $ui find "$UI_XML" "text=Allow" >/dev/null && $ui find "$UI_XML" "re=^Don.t allow$" >/dev/null; then
		tap_first "text=Allow"
		return 1
	fi
	return 0
}

ui() { node "$SUITE/ui.mjs" "$1" "$UI_XML" "${@:2}"; }

# Taps the first match in the current dump, if any (no waiting, no failure).
tap_first() {
	local xy
	xy=$(ui find "$1") || return 1
	set -- $xy
	adb_ shell input tap "$1" "$2" >/dev/null
	sleep 1
}

# One dump; prints "x y l t r b" of the first query that matches. Extra find options after "--".
ui_find() {
	dump_ui || return 1
	local q opts=()
	while [ $# -gt 0 ]; do
		if [ "$1" = -- ]; then shift; opts=("$@"); break; fi
		q+=("$1"); shift
	done
	local query
	for query in "${q[@]}"; do ui find "$query" "${opts[@]}" && return 0; done
	return 1
}

# wait_for <query> [<query>...] [-- find options]: polls until one matches (WAIT seconds, default E2E_TIMEOUT).
wait_for() {
	local timeout="${WAIT:-$E2E_TIMEOUT}" end
	end=$(($(now) + timeout))
	while :; do
		ui_find "$@" && return 0
		[ "$(now)" -ge "$end" ] && return 1
		sleep 0.7
	done
}

wait_gone() {
	local timeout="${WAIT:-$E2E_TIMEOUT}" end
	end=$(($(now) + timeout))
	while :; do
		dump_ui || return 1
		local q found=0
		for q in "$@"; do ui find "$q" >/dev/null && found=1; done
		[ $found = 0 ] && return 0
		[ "$(now)" -ge "$end" ] && return 1
		sleep 0.7
	done
}

# tap <query> [<query>...] [-- find options]
tap() {
	local xy
	xy=$(wait_for "$@") || fail "nothing to tap for: $*"
	set -- $xy
	log "tap ($1,$2)"
	adb_ shell input tap "$1" "$2"
	sleep 0.4
}

long_press() {
	local xy
	xy=$(wait_for "$@") || fail "nothing to long-press for: $*"
	set -- $xy
	adb_ shell input swipe "$1" "$2" "$1" "$2" 900
	sleep 0.5
}

tap_xy() { adb_ shell input tap "$1" "$2"; sleep 0.4; }

# The composer's send / stop button (it has a click label but no text or description).
tap_send() {
	local xy end
	end=$(($(now) + ${WAIT:-$E2E_TIMEOUT}))
	until dump_ui && xy=$(ui send); do
		[ "$(now)" -ge "$end" ] && fail "no send button on screen"
		sleep 0.7
	done
	set -- $xy
	log "tap send/stop ($1,$2)"
	adb_ shell input tap "$1" "$2"
	sleep 0.4
}

# Text of the focused editable field (or the one matching a query).
field_text() { dump_ui && ui field "$@"; }

# Types into the focused field. Spaces become %s; everything else non-alphanumeric is escaped for the device shell.
type_text() {
	local esc
	esc=$(printf '%s' "$1" | sed -e 's/[^A-Za-z0-9._,:=+@-]/\\&/g' -e 's/\\ /%s/g')
	adb_ shell input text "$esc"
	sleep 0.3
}

# Clears the focused field (cursor to the end, then deletes).
clear_field() {
	local n="${1:-80}" keys="KEYCODE_MOVE_END" i=0
	while [ $i -lt "$n" ]; do keys="$keys KEYCODE_DEL"; i=$((i + 1)); done
	adb_ shell input keyevent $keys
}

key() { adb_ shell input keyevent "$@"; sleep 0.4; }
back() { key KEYCODE_BACK; }

keyboard_shown() { adb_ shell dumpsys input_method | grep -q 'mInputShown=true'; }
hide_keyboard() { keyboard_shown && back; return 0; }

swipe_up() { adb_ shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 7 / 10)) $((SCREEN_W / 2)) $((SCREEN_H * 3 / 10)) 300; sleep 0.6; }
swipe_down() { adb_ shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 3 / 10)) $((SCREEN_W / 2)) $((SCREEN_H * 7 / 10)) 300; sleep 0.6; }

# Scrolls down until the query shows (at most N swipes).
scroll_to() {
	local n=0
	while [ $n -lt "${SWIPES:-6}" ]; do
		ui_find "$@" >/dev/null && return 0
		swipe_up
		n=$((n + 1))
	done
	ui_find "$@" >/dev/null
}

# Scrolls up (content moves down) until the query shows.
scroll_up_to() {
	local n=0
	while [ $n -lt "${SWIPES:-6}" ]; do
		ui_find "$@" >/dev/null && return 0
		swipe_down
		n=$((n + 1))
	done
	ui_find "$@" >/dev/null
}

# Back until Home (title "Pi-Bolt" with the Settings action), relaunching if back left the app.
go_home() {
	local i
	for i in 1 2 3 4 5 6; do
		dump_ui
		if ui find "text=Pi-Bolt" >/dev/null && ui find "desc=Settings" >/dev/null && ! ui find "desc=Close sheet" >/dev/null; then return 0; fi
		if ! adb_ shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG"; then
			launch_app
			continue
		fi
		back
		sleep 0.5
	done
	ui_find "text=Pi-Bolt" >/dev/null || fail "could not get back to Home"
}

# ---------------------------------------------------------------------------------------------------------------
# Evidence

screenshot() {
	local name="$1" file
	file="$OUT_DIR/$(date +%H%M%S)-${CASE:-area}-$name.png"
	adb_ exec-out screencap -p >"$file" 2>/dev/null
	echo "$file"
}

# App log since the area started: the app's uid (UI + engine processes), plus crash buffer lines for it.
pull_logcat() {
	local file="${1:-$OUT_DIR/logcat.txt}" uid
	uid=$(app_uid)
	{
		[ -n "$uid" ] && adb_ logcat -d -v threadtime --uid="$uid"
		echo "----- crash buffer -----"
		adb_ logcat -d -b crash -v threadtime
	} >"$file" 2>/dev/null
	echo "$file"
}

# ---------------------------------------------------------------------------------------------------------------
# Assertions: each failure saves a screenshot, the visible texts and the log, then ends the case.

FAIL_SHOT=
fail() {
	local msg="$1"
	FAIL_SHOT=$(screenshot fail)
	dump_ui >/dev/null 2>&1 && ui texts >"${FAIL_SHOT%.png}.texts.txt" 2>/dev/null
	pull_logcat "${FAIL_SHOT%.png}.logcat.txt" >/dev/null
	echo "$FAIL_SHOT" >"$OUT_DIR/.last-fail-shot"
	printf 'FAIL [%s/%s] %s\n  screenshot: %s\n' "${AREA:-}" "${CASE:-}" "$msg" "$FAIL_SHOT" >&2
	exit 1
}

assert_visible() {
	local q="$1" msg="${2:-expected on screen: $1}"
	wait_for "$q" >/dev/null || fail "$msg"
}

assert_gone() {
	local q="$1" msg="${2:-expected gone: $1}"
	wait_gone "$q" || fail "$msg"
}

assert_eq() { [ "$1" = "$2" ] || fail "${3:-values differ}: expected '$2', got '$1'"; }
# Case-insensitive.
assert_contains() { case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in *"$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"*) ;; *) fail "${3:-missing text}: '$2' not in '$(printf '%s' "$1" | head -c 300)'" ;; esac; }

assert_device_file() {
	local rel="$1" want="${2:-}"
	dev_exists "$rel" || fail "missing on device: $rel"
	if [ -n "$want" ]; then
		local got
		got=$(dev_cat "$rel")
		assert_contains "$got" "$want" "content of $rel"
	fi
}

assert_no_device_file() { dev_exists "$1" && fail "should not exist on device: $1"; return 0; }

assert_fake_log() { wait_fake_log "$@" || fail "fake model never saw a request with: $*"; }

# No crash, ANR or fatal signal for the app (UI or engine processes) since the area started.
assert_no_crash() {
	local uid
	uid=$(app_uid)
	adb_ logcat -d -v threadtime --uid="$uid" 2>/dev/null | grep -E "FATAL EXCEPTION|Fatal signal|AndroidRuntime: Process" >"$OUT_DIR/.crash-lines.txt"
	adb_ logcat -d -b crash -v threadtime 2>/dev/null | grep -E "Process: $PKG|>>> .*($PKG|libpibolt)" >>"$OUT_DIR/.crash-lines.txt"
	adb_ logcat -d -b system,main -v brief 2>/dev/null | grep -E "ANR in $PKG" >>"$OUT_DIR/.crash-lines.txt"
	[ -s "$OUT_DIR/.crash-lines.txt" ] && fail "crash or ANR in logcat: $(head -3 "$OUT_DIR/.crash-lines.txt" | tr '\n' ' ')"
	[ -s "$OUT_DIR/anr-dialogs.txt" ] && fail "ANR dialog shown: $(head -1 "$OUT_DIR/anr-dialogs.txt")"
	return 0
}

# ---------------------------------------------------------------------------------------------------------------
# Areas and cases

needs_engine() {
	if [ "${E2E_SKIP_ENGINE:-0}" = 1 ]; then
		echo "SKIP" >"$OUT_DIR/.case-skip"
		exit 0
	fi
}

# run_case <name> <function>: runs the function in a subshell; a failed assertion ends only that case.
run_case() {
	local name="$1" fn="$2" start rc status shot
	start=$(now)
	rm -f "$OUT_DIR/.last-fail-shot" "$OUT_DIR/.case-skip"
	log "case $name"
	(
		CASE="$name"
		"$fn"
	)
	rc=$?
	status=PASS
	[ $rc != 0 ] && status=FAIL
	[ -f "$OUT_DIR/.case-skip" ] && status=SKIP
	shot=$(cat "$OUT_DIR/.last-fail-shot" 2>/dev/null)
	printf '%s\t%s\t%s\t%s\n' "$name" "$status" "$(($(now) - start))" "${shot:--}" >>"$OUT_DIR/results.tsv"
	log "case $name: $status ($(($(now) - start))s)"
	# A failed case may leave a sheet or the keyboard open: start the next one from a known state.
	if [ $status = FAIL ]; then hide_keyboard; fi
	return 0
}

area_begin() {
	AREA="$1"
	OUT_DIR="$E2E_OUT/$SERIAL/$AREA"
	rm -rf "$OUT_DIR"
	mkdir -p "$OUT_DIR"
	: >"$OUT_DIR/results.tsv"
	AREA_START=$(now)
	log "area $AREA on $SERIAL (engine: $E2E_ENGINE, fake model port $FAKE_PORT)"
	device_prepare || { log "device not ready, retrying in 30 s"; sleep 30; device_prepare; } || { echo "device $SERIAL not ready" >&2; exit 3; }
	if [ "${E2E_INSTALL:-0}" = 1 ] || ! app_installed; then install_apk || exit 3; fi
	adb_ logcat -b all -c 2>/dev/null
	fake_model_start || exit 3
	trap area_end EXIT
}

area_end() {
	local rc=0
	fake_model_stop
	stop_app
	adb_ shell settings put system user_rotation 0 >/dev/null 2>&1
	pull_logcat "$OUT_DIR/logcat.txt" >/dev/null
	echo "$(($(now) - AREA_START))" >"$OUT_DIR/seconds"
	grep -q $'\tFAIL\t' "$OUT_DIR/results.tsv" && rc=1
	printf '\n%s on %s: %s\n' "$AREA" "$SERIAL" "$([ $rc = 0 ] && echo PASS || echo FAIL)"
	column -t -s $'\t' "$OUT_DIR/results.tsv"
	exit $rc
}

# A fresh app with the fake provider and no projects of its own. Leaves the app on Home.
fresh_app() {
	stop_app
	clear_data
	install_provider_config "${1:-fake}"
	launch_app
	if [ "${1:-fake}" = fake ]; then
		# The first-run key sheet must not show: auth.json already has the "fake" key.
		sleep 1
		ui_find "text=Welcome to Pi-Bolt" >/dev/null && fail "first-run key sheet shown although auth.json has a key"
	fi
	return 0
}

# Creates a project folder directly (no UI), with optional files: mkproject <name> [<rel path> <content>]...
mkproject() {
	local name="$1"
	shift
	run_as "mkdir -p '$PROJECTS_REL/$name'" >/dev/null
	while [ $# -ge 2 ]; do
		printf '%s' "$2" | dev_write "$PROJECTS_REL/$name/$1"
		run_as "chmod 644 '$PROJECTS_REL/$name/$1'" >/dev/null
		shift 2
	done
}

unique() { printf '%s-%s%s' "$1" "$(date +%H%M%S)" "$((RANDOM % 100))"; }

# Home -> project card -> project screen.
open_project() {
	go_home
	SWIPES=4 scroll_to "text=$1" || fail "project $1 not on Home"
	tap "text=$1"
	assert_visible "text=projects/$1" "project screen for $1 did not open"
}

# On the project screen: New session, then wait for the composer to accept input (engine connected).
new_session() {
	tap "text=New session"
	WAIT=$E2E_ENGINE_TIMEOUT assert_visible "text=Message Pi…" "the session did not become ready (composer still not connected)"
}

# Types a prompt into the composer and sends it.
send_prompt() {
	tap "text=Message Pi…" "text=Steer Pi…" "text=Queue a follow-up…"
	type_text "$1"
	tap_send
	# A send that went out empties the composer; a failed one (engine not connected) puts the text back.
	local end=$(($(now) + 6)) left
	while :; do
		left=$(field_text 2>/dev/null | tr '[:upper:]' '[:lower:]')
		case "$left" in *"$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"*) ;; *) break ;; esac
		[ "$(now)" -ge "$end" ] && fail "the prompt was not sent: it is still (or again) in the composer"
		sleep 0.5
	done
	hide_keyboard
}
