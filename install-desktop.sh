#!/bin/sh
# Pi-Bolt Desktop installer (macOS on Apple silicon, Linux on x86-64).
#
#   curl -fsSL https://pi-bolt.opensec.in/install-desktop.sh | sh
#
#
# Downloads Pi-Bolt Desktop straight from the npm registry with curl (the registry as a CDN, fast in most places: no npm, Node
# or Bun needed for the app), checks it against the registry's sha512 integrity and the app against its SHA-256 in the
# package, and installs it. Run it again to update or uninstall. The app includes the Pi-Bolt agent, so nothing else is
# needed. The optional pi-bolt-desktop command (--cli) runs on the Bun runtime inside that bundled agent: no Bun install.
#
# On a Mac it installs Pi-Bolt.app into /Applications (or ~/Applications) from @kushalkhemka/pi-bolt-desktop. On Linux it
# installs from @kushalkhemka/pi-bolt-desktop-linux-x64, by default for this user only and without root: the AppImage is
# extracted (no FUSE needed) into ~/.local/share/pi-bolt-desktop/app, with a launcher in ~/.local/bin/pi-bolt-desktop-app,
# an entry in the applications menu and an icon. The Pi-Bolt agent comes from Pi-Bolt's own installer (offered below).
#
# The app is a private npm package, so the registry wants a token that can read it: the installer takes
# PIBOLT_DESKTOP_NPM_TOKEN, NPM_TOKEN, or a token in ~/.bunfig.toml or ~/.npmrc. It sends the token to the registry only, in a
# header read from a file (never in a URL or on a command line), and never prints it. If the package can be read without a
# token (published publicly), none is needed.
#
# Options:
#   --cli         also install the pi-bolt-desktop command (asked when interactive)
#   --agent       also install the standalone Pi-Bolt agent (the pi-bolt command) when it is missing; the app has its own
#   --no-agent    do not install the standalone Pi-Bolt agent
#   --no-cli      do not install or update the command
#   --user        macOS: use ~/Applications instead of /Applications; Linux: the user install (the default)
#   --force       reinstall the same version, or replace a newer one
#   --open        open Pi-Bolt when done (asked when interactive)
#   --uninstall   remove the app and the command; --purge also removes the app's data (~/Library, or ~/.local/share,
#                 ~/.config and ~/.cache on Linux)
#   --version V   install version V (default: latest)
#   -y, --yes     do not ask: take the default action
#
# Environment:
#   PIBOLT_DESKTOP_VERSION       same as --version
#   PIBOLT_DESKTOP_YES=1         same as --yes
#   PIBOLT_DESKTOP_NPM_TOKEN     the npm token to read the package with (else NPM_TOKEN, BUN_CONFIG_TOKEN, NPM_CONFIG_TOKEN,
#                                the @kushalkhemka scope's or the registry's token in ~/.bunfig.toml, else ~/.npmrc)
#   PIBOLT_DESKTOP_NPM_REGISTRY  the npm registry or mirror to use (also PIBOLT_NPM_REGISTRY; default: the @kushalkhemka
#                                registry in ~/.bunfig.toml or ~/.npmrc, BUN_CONFIG_REGISTRY, else https://registry.npmjs.org)
#   PIBOLT_DESKTOP_CONNECTIONS   connections to download over at once (default: 4; 1 for a single connection)
#   PIBOLT_DESKTOP_TGZ           install from this local package .tgz instead of the registry (offline, testing)
#   PIBOLT_DESKTOP_DIR           macOS: install to and look for the app only in this folder; Linux: the folder for the app
#                                instead of ~/.local/share/pi-bolt-desktop (it goes into its app/ folder)
#   BUN_INSTALL                  where an older Bun-based install of the command is removed from (default: ~/.bun)
#   PIBOLT_DESKTOP_AGENT_INSTALLER  Pi-Bolt's installer to run for the agent (default: https://pi-bolt.opensec.in/install.sh)

PKG="@kushalkhemka/pi-bolt-desktop"
PKG_SCOPE="@kushalkhemka"
PKG_PATH="@kushalkhemka%2fpi-bolt-desktop"
PKG_BASE="pi-bolt-desktop"
BUNDLE_ID="in.opensec.pibolt.desktop"
APP_NAME="Pi-Bolt.app"
AGENT_INSTALLER="${PIBOLT_DESKTOP_AGENT_INSTALLER:-https://pi-bolt.opensec.in/install.sh}"
ACCEPT_PACKUMENT="application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8, */*"

ESC=$(printf '\033')
CR=$(printf '\r')
ETX=$(printf '\003')

# setup_platform: OS (Darwin or Linux), and on Linux the Linux package and where the user install goes.
setup_platform() {
	OS=$(uname -s 2>/dev/null || echo unknown)
	NATIVE=Mac PLATFORM_LABEL="Apple silicon"
	[ "$OS" = Linux ] || return 0
	PKG="@kushalkhemka/pi-bolt-desktop-linux-x64"
	PKG_PATH="@kushalkhemka%2fpi-bolt-desktop-linux-x64"
	PKG_BASE="pi-bolt-desktop-linux-x64"
	NATIVE=desktop PLATFORM_LABEL="Linux x86-64"
	DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
	APP_HOME="${PIBOLT_DESKTOP_DIR:-$DATA_HOME/pi-bolt-desktop}"
	APP_HOME="${APP_HOME%/}"
	APP_DIR="$APP_HOME/app"
	BIN_DIR="$HOME/.local/bin"
	LAUNCHER="$BIN_DIR/pi-bolt-desktop-app"
	DESKTOP_FILE="$DATA_HOME/applications/pi-bolt-desktop.desktop"
	ICON_THEME="$DATA_HOME/icons/hicolor"
	ICON_FILE="$ICON_THEME/256x256/apps/pi-bolt-desktop.png"
}

main() {
	set -eu
	setup_platform
	CLI=ask AGENT=ask OPEN=ask UNINSTALL="" USER_FLAG="" FORCE="" PURGE="" MODE=user
	YES="${PIBOLT_DESKTOP_YES:-0}"
	VERSION="${PIBOLT_DESKTOP_VERSION:-latest}"
	LOCAL_TGZ="${PIBOLT_DESKTOP_TGZ:-}"
	while [ $# -gt 0 ]; do
		case "$1" in
		--cli) CLI=yes ;;
		--no-cli) CLI=no ;;
		--agent) AGENT=yes ;;
		--no-agent) AGENT=no ;;
		--user) USER_FLAG=1 ;;
		--deb | --rpm) usage_error "$1 is no longer offered: the default install (for this user, no root) works on every distribution" ;;
		--force) FORCE=1 ;;
		--open) OPEN=yes ;;
		--no-open) OPEN=no ;;
		--uninstall) UNINSTALL=1 ;;
		--purge) PURGE=1 ;;
		--version) [ $# -ge 2 ] || usage_error "--version needs a version"; VERSION=$2; shift ;;
		--version=*) VERSION=${1#--version=} ;;
		-y | --yes) YES=1 ;;
		-h | --help) usage; exit 0 ;;
		*) usage_error "unknown option $1 (see --help)" ;;
		esac
		shift
	done
	case "$VERSION" in "" | *[!0-9A-Za-z.+-]*) usage_error "not a version: $VERSION" ;; esac
	[ -z "$PURGE" ] || [ -n "$UNINSTALL" ] || usage_error "--purge goes with --uninstall"
	[ -z "$LOCAL_TGZ" ] || [ -f "$LOCAL_TGZ" ] || usage_error "PIBOLT_DESKTOP_TGZ: no such file: $LOCAL_TGZ"
	setup_style

	TMP=$(mktemp -d "${TMPDIR:-/tmp}/pi-bolt-desktop.XXXXXX")
	STAGE="" DEST="" PIDS=""
	trap cleanup EXIT
	trap 'finish_progress; exit 130' INT TERM
	: >"$TMP/none.curl"
	: >"$TMP/none.wgetrc"
	: >"$TMP/fetch.log"

	setup_registry
	preflight >"$TMP/preflight" 2>&1 &
	check_pid=$!
	resolve_pid=""
	if [ -z "$UNINSTALL" ]; then
		find_token
		resolve >/dev/null 2>&1 &
		resolve_pid=$!
	fi
	logo_animation
	if wait "$check_pid"; then check_status=0; else check_status=$?; fi
	printf '%s  Pi-Bolt Desktop Installer%s\n%s  The Pi-Bolt agent as a native %s app. Your sessions, plugins and settings, unchanged.%s\n\n' "$bold" "$reset" "$dim" "$NATIVE" "$reset"
	cat "$TMP/preflight"
	[ "$check_status" -eq 0 ] || exit "$check_status"

	find_installed
	if [ -n "$UNINSTALL" ]; then
		uninstall
		exit 0
	fi

	wait "$resolve_pid" 2>/dev/null || true
	read_resolved
	choose_action
	case "$ACTION" in
	uninstall)
		uninstall
		exit 0
		;;
	none)
		# Nothing to install, but the command may still be wanted, and the app opened.
		[ -z "$EXISTING" ] || install_agent
		if [ "$CLI" = yes ]; then
			fetch_package
			finish_progress
			install_cli
		fi
		[ "$OPEN" != yes ] || [ -z "$EXISTING" ] || open_app "$EXISTING"
		exit 0
		;;
	esac

	refuse_if_running
	fetch_package
	install_app
	finish_progress
	printf '  %s%s%s install complete %s(%s, %s MB from %s, %s)%s\n' "$green" "$CHECK" "$reset" "$dim" "$APP_VERSION" "$(mb "$SIZE")" "$FROM" "$SIGNATURE" "$reset"
	case "$ACTION" in
	update) word=updated ;;
	reinstall) word=reinstalled ;;
	*) word=installed ;;
	esac
	was=""
	[ -z "$INSTALLED_VERSION" ] || [ "$INSTALLED_VERSION" = "$APP_VERSION" ] || was=", was $INSTALLED_VERSION"
	printf '\nPi-Bolt Desktop %s was %s successfully %s(%s%s)%s.\n' "$APP_VERSION" "$word" "$dim" "$(tilde "$DEST")" "$was" "$reset"
	printf '%s\n' "$OTHERS" | while IFS= read -r other; do
		[ -z "$other" ] || printf '%sNote: another copy is at %s.%s\n' "$dim" "$(tilde "$other")" "$reset"
	done
	warn_missing_libraries
	install_agent
	install_cli
	offer_open
	printf '\n%s Run this script again to update or uninstall it.\n' "$(open_hint)"
}

# --- Look -------------------------------------------------------------------------------------------------------------

setup_style() {
	reset="" dim="" bold="" cyan="" green="" red=""
	amber="" amber2="" blue="" blue2="" white=""
	FULL="#" EMPTY="-" FRAMES=4 CHECK="ok" EIGHTHS=0
	if [ -t 1 ] && [ "${TERM:-}" != dumb ]; then
		if [ -z "${NO_COLOR:-}" ]; then
			reset="${ESC}[0m" dim="${ESC}[2m" bold="${ESC}[1m" cyan="${ESC}[36m" green="${ESC}[32m" red="${ESC}[31m"
			amber="${ESC}[38;2;247;192;74m" amber2="${ESC}[38;2;233;164;44m"
			blue="${ESC}[38;2;76;150;234m" blue2="${ESC}[38;2;42;120;214m" white="${ESC}[38;2;255;255;255m"
		fi
		if unicode_terminal; then FULL="█" EMPTY="░" FRAMES=10 CHECK="✓" EIGHTHS=1; fi
	fi
}

unicode_terminal() {
	case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
	*UTF-8* | *utf-8* | *UTF8* | *utf8*) return 0 ;;
	esac
	case "${TERM_PROGRAM:-}" in
	Apple_Terminal | iTerm.app | vscode | WezTerm | ghostty) return 0 ;;
	esac
	return 1
}

# The bolt, on a grid of 8 x 9 cells (each cell two characters wide), as in Pi-Bolt's installer.
UPPER="0,6 0,7 1,5 1,6 2,4 2,5 3,3 3,4 4,2 4,3 4,4"
LOWER="4,5 4,6 4,7 5,5 5,6 6,4 6,5 7,3 7,4 8,3"
UPPER_DARK="0,7 1,6 2,5 3,4"
LOWER_DARK="4,7 5,6 6,5 7,4"

has_cell() { # has_cell Y X "cells" [DY]: is (Y - DY, X) one of the cells
	want="$(($1 - ${4:-0})),$2"
	for c in $3; do [ "$c" = "$want" ] && return 0; done
	return 1
}

# draw_bolt UPPER_DY LOWER_DY MODE: MODE is color or white; a DY of "off" leaves that piece out.
draw_bolt() {
	frame=""
	y=0
	while [ "$y" -le 8 ]; do
		frame="$frame  "
		x=1
		while [ "$x" -le 8 ]; do
			cell="  "
			if [ "$1" != off ] && has_cell "$y" "$x" "$UPPER" "$1"; then
				if [ "$3" = white ]; then cell="${white}██"
				elif has_cell "$y" "$x" "$UPPER_DARK" "$1"; then cell="${amber2}██"
				else cell="${amber}██"; fi
			elif [ "$2" != off ] && has_cell "$y" "$x" "$LOWER" "$2"; then
				if [ "$3" = white ]; then cell="${white}██"
				elif has_cell "$y" "$x" "$LOWER_DARK" "$2"; then cell="${blue2}██"
				else cell="${blue}██"; fi
			fi
			frame="$frame$cell$reset"
			x=$((x + 1))
		done
		frame="$frame
"
		y=$((y + 1))
	done
	printf '%s' "$frame"
}

logo_animation() {
	if [ ! -t 1 ] || [ "${TERM:-}" = dumb ]; then
		printf '\n'
		draw_bolt 0 0 color | sed 's/██/##/g'
		printf '\n'
		return
	fi
	printf '%s[?25l\n' "$ESC"
	trap 'printf "%s%s[?25h\n" "$reset" "$ESC"; exit 130' INT TERM
	up="${ESC}[9A"
	first=1
	show() { if [ "$first" = 1 ]; then first=0; else printf '%s' "$up"; fi; draw_bolt "$@"; sleep "$SPEED"; }
	SPEED=0.06
	for dy in -6 -4 -2 -1 0; do show "$dy" off color; done
	for dy in 6 4 2 1 0; do show 0 "$dy" color; done
	SPEED=0.07
	show 0 0 white
	show 0 0 color
	show 0 0 white
	SPEED=0.35
	show 0 0 color
	printf '%s[?25h\n' "$ESC"
	trap 'finish_progress; exit 130' INT TERM
}

spinner() {
	if [ "$FRAMES" -eq 10 ]; then
		case $(($1 % 10)) in
		0) printf '⠋' ;; 1) printf '⠙' ;; 2) printf '⠹' ;; 3) printf '⠸' ;; 4) printf '⠼' ;;
		5) printf '⠴' ;; 6) printf '⠦' ;; 7) printf '⠧' ;; 8) printf '⠇' ;; *) printf '⠏' ;;
		esac
	else
		case $(($1 % 4)) in 0) printf '-' ;; 1) printf '%s' "\\" ;; 2) printf '|' ;; *) printf '/' ;; esac
	fi
}

# draw_progress STEP FRACTION LABEL: FRACTION is 0-10000 (hundredths of a percent), or -1 when the total is not known (a
# moving comet instead). The bar fills in eighths of a cell, so that it moves however slow the download is.
BAR_WIDTH=28
draw_progress() {
	if [ ! -t 1 ]; then
		# Not a terminal (a log, CI): plain lines instead of an animated bar.
		printf '%s\n' "$3" | sed "s/${ESC}\\[[0-9;]*m//g"
		return 0
	fi
	bar=""
	i=0
	if [ "$2" -ge 0 ]; then
		eighths=$(($2 * BAR_WIDTH * 8 / 10000))
		while [ "$i" -lt "$BAR_WIDTH" ]; do
			if [ "$i" -lt $((BAR_WIDTH / 2)) ]; then color=$amber; else color=$blue; fi
			left=$((eighths - i * 8))
			if [ "$left" -ge 8 ]; then
				bar="$bar$color$FULL"
			elif [ "$left" -gt 0 ] && [ "$EIGHTHS" = 1 ]; then
				bar="$bar$color$(eighth "$left")"
			else
				bar="$bar$dim$EMPTY"
			fi
			bar="$bar$reset"
			i=$((i + 1))
		done
	else
		head=$(($1 % (BAR_WIDTH + 6)))
		while [ "$i" -lt "$BAR_WIDTH" ]; do
			age=$((head - i))
			if [ "$age" -ge 0 ] && [ "$age" -lt 2 ]; then bar="$bar$white$FULL"
			elif [ "$age" -ge 2 ] && [ "$age" -lt 4 ]; then bar="$bar$amber$FULL"
			elif [ "$age" -ge 4 ] && [ "$age" -lt 6 ]; then bar="$bar$blue$FULL"
			else bar="$bar$dim$EMPTY"; fi
			bar="$bar$reset"
			i=$((i + 1))
		done
	fi
	printf '\r%s[K  %s%s%s %s %sInstalling Pi-Bolt Desktop%s %s' "$ESC" "$amber" "$(spinner "$1")" "$reset" "$bar" "$bold" "$reset" "$3"
}

# A cell filled N eighths from the left.
eighth() {
	case $1 in 1) printf '▏' ;; 2) printf '▎' ;; 3) printf '▍' ;; 4) printf '▌' ;; 5) printf '▋' ;; 6) printf '▊' ;; *) printf '▉' ;; esac
}

finish_progress() { if [ -t 1 ]; then printf '\r%s[K%s[?25h' "$ESC" "$ESC"; fi; return 0; }

# shellcheck disable=SC2088 # a literal ~ for display
tilde() { case "$1" in "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;; *) printf '%s' "$1" ;; esac; }

mb() { awk -v b="$1" 'BEGIN { printf "%.1f", b / 1048576 }'; }

usage() {
	cat <<EOF
Pi-Bolt Desktop installer (macOS on Apple silicon, Linux on x86-64)

Usage: sh install.sh [options]        (or: curl -fsSL <URL> | sh -s -- [options])

  --cli         also install the pi-bolt-desktop command (no Bun needed)
  --no-cli      do not install or update the command
  --agent       also install the standalone Pi-Bolt agent if it is missing
  --no-agent    do not install the Pi-Bolt agent
  --user        macOS: use ~/Applications instead of /Applications (Linux: the default user install)
  --force       reinstall the same version, or replace a newer one
  --open        open Pi-Bolt when done
  --uninstall   remove the app and the command (--purge: also its settings and data)
  --version V   install version V (default: latest)
  -y, --yes     do not ask questions

Downloads $PKG from the npm registry with curl; the app needs no npm, Node or Bun.
On Linux the default install is for this user, without root: the AppImage, extracted into ~/.local/share/pi-bolt-desktop.
The package is private: set NPM_TOKEN to a token with read access, or keep one in ~/.npmrc or ~/.bunfig.toml.
Run it again to update or uninstall.
EOF
}
usage_error() { printf 'install.sh: %s\n' "$1" >&2; exit 2; }

fail() {
	finish_progress
	printf '%serror:%s %s\n' "$red" "$reset" "$1" >&2
	shift
	for line in "$@"; do printf '  %s%s%s\n' "$dim" "$line" "$reset" >&2; done
	exit 1
}

# shellcheck disable=SC2016 # backquotes for the reader, not a command
PRIVATE_MESSAGE='This package is private. Set NPM_TOKEN to a registry token with read access, or add it to ~/.npmrc or ~/.bunfig.toml.'

cleanup() {
	# shellcheck disable=SC2086 # a list of process IDs
	[ -z "$PIDS" ] || kill $PIDS 2>/dev/null || true
	# Interrupted in the middle of the swap: put the old copy back.
	if [ -n "$STAGE" ] && [ -d "$STAGE/previous.app" ] && [ ! -e "$DEST" ]; then mv "$STAGE/previous.app" "$DEST" 2>/dev/null || true; fi
	[ -z "$STAGE" ] || rm -rf "$STAGE"
	[ -z "${PKG_TMP:-}" ] || rm -rf "$PKG_TMP"
	rm -rf "$TMP"
}

# --- Checks -----------------------------------------------------------------------------------------------------------

preflight() {
	status=0
	if [ "$OS" = Linux ]; then
		preflight_linux
		return
	fi
	if [ "$(uname -s)" != Darwin ]; then
		printf 'error: Pi-Bolt Desktop runs on macOS (Apple silicon) and Linux (x86-64) only (this is %s).\n\n' "$(uname -s)"
		return 1
	fi
	# Apple silicon, also from a shell that runs under Rosetta.
	if [ "$(sysctl -n hw.optional.arm64 2>/dev/null || true)" != 1 ]; then
		printf 'error: Pi-Bolt Desktop needs a Mac with Apple silicon (M1 or later); this Mac has an Intel CPU.\n'
		status=1
	fi
	macos=$(sw_vers -productVersion 2>/dev/null || echo 0)
	if [ "${macos%%.*}" -lt 13 ] 2>/dev/null; then
		printf 'error: Pi-Bolt Desktop needs macOS 13 (Ventura) or later (this Mac has %s).\n' "$macos"
		status=1
	fi
	if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
		printf 'error: curl or wget is required.\n'
		status=1
	fi
	for tool in tar shasum ditto codesign osascript xattr; do
		command -v "$tool" >/dev/null 2>&1 || { printf 'error: %s is required.\n' "$tool"; status=1; }
	done
	if ! command -v openssl >/dev/null 2>&1 && ! command -v xxd >/dev/null 2>&1; then
		printf 'error: openssl or xxd is required.\n'
		status=1
	fi
	[ "$status" -eq 0 ] || printf '\n'
	return "$status"
}

# --- The registry -----------------------------------------------------------------------------------------------------

# The npm config files to look in, as npm and Bun do: NPM_CONFIG_USERCONFIG instead of ~/.npmrc when it is set.
npmrc_files() {
	userconfig="${NPM_CONFIG_USERCONFIG:-${npm_config_userconfig:-}}"
	if [ -n "$userconfig" ]; then printf '%s\n' "$userconfig"; fi
	printf '%s\n' "$HOME/.npmrc"
}

# Bun's global config files: $XDG_CONFIG_HOME/.bunfig.toml, ~/.bunfig.toml.
bunfig_files() {
	if [ -n "${XDG_CONFIG_HOME:-}" ]; then printf '%s\n' "$XDG_CONFIG_HOME/.bunfig.toml"; fi
	printf '%s\n' "$HOME/.bunfig.toml"
}

# expand_env VALUE: VALUE, or the variable it names ($VAR or ${VAR}, as .npmrc and bunfig.toml allow). Fails if empty.
expand_env() {
	value=$1
	# shellcheck disable=SC2016 # a literal $
	case "$value" in
	'${'*'}') name=${value#??} name=${name%?} ;;
	'$'*) name=${value#?} ;;
	*) name="" ;;
	esac
	if [ -n "$name" ]; then
		case "$name" in *[!A-Za-z0-9_]*) return 1 ;; esac
		value=$(printenv "$name" || true)
	fi
	[ -n "$value" ] || return 1
	printf '%s' "$value"
}

# npmrc_value FILE KEY: the value of KEY in the npm config FILE (the last one), unquoted, with a ${VAR} in it expanded.
npmrc_value() {
	[ -f "$1" ] || return 1
	raw=$(awk -v key="$2" '
		{ line = $0; sub(/\r$/, "", line); sub(/^[ \t]+/, "", line) }
		index(line, key) == 1 {
			rest = substr(line, length(key) + 1)
			if (rest !~ /^[ \t]*=/) next
			sub(/^[ \t]*=[ \t]*/, "", rest); sub(/[ \t]+$/, "", rest)
			if (rest ~ /^".*"$/ || rest ~ /^'\''.*'\''$/) rest = substr(rest, 2, length(rest) - 2)
			v = rest
		}
		END { if (v != "") print v }' "$1")
	expand_env "$raw"
}

# bunfig_value FILE WHERE FIELD: FIELD (url or token) of a registry in Bun's config FILE, with a $VAR in it expanded. WHERE
# is scope (the @kushalkhemka entry of [install.scopes], inline or as its own table) or default ([install] registry). A
# default registry given as a plain string has a url only.
bunfig_value() {
	[ -f "$1" ] || return 1
	raw=$(awk -v where="$2" -v f="$3" '
		function field(s, name,   v) {
			if (!match(s, name "[ \t]*=[ \t]*\"[^\"]*\"")) return ""
			v = substr(s, RSTART, RLENGTH); sub(/^[^"]*"/, "", v); sub(/"$/, "", v); return v
		}
		{ line = $0; sub(/\r$/, "", line); sub(/^[ \t]+/, "", line) }
		line ~ /^#/ { next }
		line ~ /^\[/ { section = line; gsub(/[ \t"]/, "", section); next }
		where == "scope" && section == "[install.scopes]" && line ~ /^"?@?kushalkhemka"?[ \t]*=/ { s = line; sub(/^[^=]*=/, "", s); v = field(s, f) }
		where == "scope" && (section == "[install.scopes.@kushalkhemka]" || section == "[install.scopes.kushalkhemka]") && line ~ ("^" f "[ \t]*=") { v = field(line, f) }
		where == "default" && section == "[install]" && line ~ /^registry[ \t]*=/ {
			s = line; sub(/^registry[ \t]*=[ \t]*/, "", s)
			if (s ~ /^"/) { if (f == "url") { v = s; sub(/^"/, "", v); sub(/".*/, "", v) } } else v = field(s, f)
		}
		where == "default" && section == "[install.registry]" && line ~ ("^" f "[ \t]*=") { v = field(line, f) }
		END { if (v != "") print v }' "$1")
	expand_env "$raw"
}

# The registry to download from: PIBOLT_DESKTOP_NPM_REGISTRY, PIBOLT_NPM_REGISTRY, the @kushalkhemka registry in Bun's or
# npm's config, BUN_CONFIG_REGISTRY or NPM_CONFIG_REGISTRY, else registry.npmjs.org. REGISTRY_KEY is its "nerf dart"
# (//host/path/), the key .npmrc keeps its token under.
setup_registry() {
	REGISTRY="${PIBOLT_DESKTOP_NPM_REGISTRY:-${PIBOLT_NPM_REGISTRY:-}}"
	if [ -z "$REGISTRY" ]; then
		while IFS= read -r file; do
			REGISTRY=$(bunfig_value "$file" scope url) && break
		done <<EOF
$(bunfig_files)
EOF
	fi
	if [ -z "$REGISTRY" ]; then
		while IFS= read -r file; do
			REGISTRY=$(npmrc_value "$file" "$PKG_SCOPE:registry") && break
		done <<EOF
$(npmrc_files)
EOF
	fi
	REGISTRY="${REGISTRY:-${BUN_CONFIG_REGISTRY:-${NPM_CONFIG_REGISTRY:-${npm_config_registry:-https://registry.npmjs.org}}}}"
	REGISTRY="${REGISTRY%/}"
	case "$REGISTRY" in
	http://* | https://*) ;;
	*) fail "the npm registry must be an http(s) URL: $REGISTRY" ;;
	esac
	REGISTRY_KEY="//${REGISTRY#*://}/"
}

# find_token: the token to read the package with, from PIBOLT_DESKTOP_NPM_TOKEN, NPM_TOKEN, BUN_CONFIG_TOKEN or
# NPM_CONFIG_TOKEN, Bun's ~/.bunfig.toml (the @kushalkhemka scope, or the default registry when it is this one), or .npmrc (what
# the registry token file; Bun reads it too). It goes into curl and wget config files in $TMP (private to this user), never into a
# URL or onto a command line, where `ps` would show it. TOKEN_FROM says where it came from, for messages; the token is not
# shown.
find_token() {
	TOKEN="" TOKEN_FROM=""
	for var in PIBOLT_DESKTOP_NPM_TOKEN NPM_TOKEN BUN_CONFIG_TOKEN NPM_CONFIG_TOKEN; do
		TOKEN=$(printenv "$var" || true)
		if [ -n "$TOKEN" ]; then TOKEN_FROM=$var; break; fi
	done
	if [ -z "$TOKEN" ]; then
		while IFS= read -r file; do
			if TOKEN=$(bunfig_value "$file" scope token); then TOKEN_FROM=$(tilde "$file"); break; fi
			url=$(bunfig_value "$file" default url || true)
			if { [ -z "$url" ] || [ "$(origin "${url%/}")" = "$(origin "$REGISTRY")" ]; } && TOKEN=$(bunfig_value "$file" default token); then
				TOKEN_FROM=$(tilde "$file")
				break
			fi
		done <<EOF
$(bunfig_files)
EOF
	fi
	if [ -z "$TOKEN" ]; then
		while IFS= read -r file; do
			if TOKEN=$(npmrc_value "$file" "$REGISTRY_KEY:_authToken"); then TOKEN_FROM=$(tilde "$file"); break; fi
		done <<EOF
$(npmrc_files)
EOF
	fi
	[ -n "$TOKEN" ] || return 0
	case "$TOKEN" in *[!A-Za-z0-9._~+/=-]*) fail "the npm token from $TOKEN_FROM has characters a token does not have." ;; esac
	(
		umask 077
		printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" >"$TMP/auth.curl"
		printf 'header = Authorization: Bearer %s\n' "$TOKEN" >"$TMP/auth.wgetrc"
	)
	TOKEN=1
}

# origin URL: scheme://host[:port], to send the token only where it belongs.
origin() { printf '%s' "$1" | sed -E 's#^([A-Za-z][A-Za-z0-9+.-]*://[^/?#]+).*#\1#'; }

# auth_for URL: 1 if URL is on the registry and there is a token (or the registry needs none: then 0).
auth_for() { if [ "$AUTH" = 1 ] && [ "$(origin "$1")" = "$(origin "$REGISTRY")" ]; then echo 1; else echo 0; fi; }

curl_config() { if [ "$1" = 1 ]; then printf '%s' "$TMP/auth.curl"; else printf '%s' "$TMP/none.curl"; fi; }
wget_config() { if [ "$1" = 1 ]; then printf '%s' "$TMP/auth.wgetrc"; else printf '%s' "$TMP/none.wgetrc"; fi; }

# get AUTH ACCEPT FILE URL: GETs URL into FILE (with the token when AUTH is 1); prints the HTTP status, 000 if no answer.
get() {
	if command -v curl >/dev/null 2>&1; then
		curl -sSL --retry 2 --connect-timeout 20 -K "$(curl_config "$1")" -H "Accept: $2" -o "$3" -w '%{http_code}' "$4" 2>>"$TMP/fetch.log" || true
	else
		WGETRC=$(wget_config "$1") wget -S -t 3 -T 20 --header="Accept: $2" -O "$3" "$4" 2>&1 |
			awk '$1 ~ /^HTTP\// { c = $2 } END { print (c == "" ? "000" : c) }'
	fi
}

# fetch FILE URL AUTH
fetch() {
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL --retry 2 --connect-timeout 20 -K "$(curl_config "$3")" -o "$1" "$2"
	else
		WGETRC=$(wget_config "$3") wget -q -t 3 -T 20 -O "$1" "$2"
	fi
}

# JSON is read with JavaScript for Automation, which every Mac has (no jq needed); on Linux with python3, else Bun, else awk
# (json_linux).
#   json FILE packument WANT   "ok", then the version WANT names (a dist-tag such as latest, or a version), its dist.tarball,
#                              dist.integrity and dist.shasum, one per line; or "missing", then the latest version
#   json FILE field NAME       the top-level string NAME
# shellcheck disable=SC2016 # JavaScript, not shell
JSON_JS='ObjC.import("Foundation");
function run(argv) {
	var text = $.NSString.stringWithContentsOfFileEncodingError(argv[0], $.NSUTF8StringEncoding, null);
	if (text.isNil()) throw new Error("cannot read " + argv[0]);
	var doc = JSON.parse(ObjC.unwrap(text));
	var clean = function (v) { return typeof v === "string" ? v.replace(/[\r\n]/g, "") : ""; };
	if (argv[1] === "field") return clean(doc[argv[2]]);
	var tags = doc["dist-tags"] || {}, versions = doc.versions || {};
	var version = Object.prototype.hasOwnProperty.call(tags, argv[2]) ? tags[argv[2]] : argv[2];
	var entry = Object.prototype.hasOwnProperty.call(versions, version) ? versions[version] : null;
	if (!entry || !entry.dist) return "missing\n" + clean(tags.latest);
	return ["ok", clean(version), clean(entry.dist.tarball), clean(entry.dist.integrity), clean(entry.dist.shasum)].join("\n");
}'
json() {
	if [ "$OS" = Linux ]; then json_linux "$@"; return; fi
	osascript -l JavaScript -e "$JSON_JS" "$@" 2>/dev/null
}

# resolve: which version to install and where it is, worked out in the background while the logo draws. Writes, one per
# line, to $TMP/resolved: a status (ok, private, denied, offline, missing, http-CODE, bad), the version, the tarball's URL, its
# integrity and shasum, and 1 if the registry wants the token (0 if the package reads without one).
resolve() {
	auth=0
	if [ -n "$LOCAL_TGZ" ]; then
		if tar -xzOf "$LOCAL_TGZ" package/package.json >"$TMP/package.json" 2>/dev/null &&
			[ "$(json "$TMP/package.json" field name)" = "$PKG" ] && v=$(json "$TMP/package.json" field version) && [ -n "$v" ]; then
			resolved ok "$v" "" "" "" 0
		else
			resolved bad
		fi
		return 0
	fi
	[ -z "$TOKEN" ] || auth=1
	code=$(get "$auth" "$ACCEPT_PACKUMENT" "$TMP/packument.json" "$REGISTRY/$PKG_PATH")
	if [ "$auth" = 1 ]; then
		case "$code" in
		401 | 403 | 404)
			# A token the registry does not accept fails even for a public package: try without it.
			if [ "$(get 0 "$ACCEPT_PACKUMENT" "$TMP/packument.json" "$REGISTRY/$PKG_PATH")" = 200 ]; then auth=0 code=200; fi
			;;
		esac
	else
		auth=0
	fi
	case "$code" in
	200) ;;
	401 | 403 | 404)
		if [ "$auth" = 1 ]; then resolved denied; else resolved private; fi
		return 0
		;;
	000) resolved offline; return 0 ;;
	*) resolved "http-$code"; return 0 ;;
	esac
	if ! json "$TMP/packument.json" packument "$VERSION" >"$TMP/fields"; then
		resolved bad
		return 0
	fi
	{ read -r status; read -r v; read -r tarball; read -r integrity; read -r shasum; } <"$TMP/fields" || true
	if [ "$status" != ok ]; then
		resolved missing "$v"
		return 0
	fi
	resolved ok "$v" "$tarball" "$integrity" "$shasum" "$auth"
}

resolved() {
	printf '%s\n' "$1" "${2:-}" "${3:-}" "${4:-}" "${5:-}" "${6:-0}" >"$TMP/resolved.part"
	mv "$TMP/resolved.part" "$TMP/resolved"
}

read_resolved() {
	R_STATUS=bad
	{ read -r R_STATUS; read -r SHOWN_VERSION; read -r TARBALL; read -r INTEGRITY; read -r SHASUM; read -r AUTH; } 2>/dev/null <"$TMP/resolved" || true
	case "$R_STATUS" in
	ok) ;;
	private) fail "$PRIVATE_MESSAGE" ;;
	denied) fail "This package is private, and the token from $TOKEN_FROM was not accepted. Set NPM_TOKEN to a registry token with read access, or add it to ~/.npmrc or ~/.bunfig.toml." ;;
	offline) fail "could not reach $REGISTRY." "$(tail -n 1 "$TMP/fetch.log" 2>/dev/null || true)" ;;
	missing)
		if [ -n "$SHOWN_VERSION" ]; then fail "$PKG has no version $VERSION (the latest is $SHOWN_VERSION)."; fi
		fail "$PKG has no version $VERSION."
		;;
	http-*) fail "$REGISTRY answered ${R_STATUS#http-} for $PKG." ;;
	*)
		if [ -n "$LOCAL_TGZ" ]; then fail "PIBOLT_DESKTOP_TGZ: $LOCAL_TGZ is not a $PKG package."; fi
		fail "$REGISTRY sent something that is not $PKG's package information."
		;;
	esac
	case "$SHOWN_VERSION" in "" | *[!0-9A-Za-z.+-]*) fail "the registry names a version that is not one: $SHOWN_VERSION" ;; esac
	if [ -z "$LOCAL_TGZ" ]; then
		# Where the tarball is: as the registry says, else where npm keeps it. http(s) only (curl would also read file:// URLs).
		[ -n "$TARBALL" ] || TARBALL=$(npm_url)
		case "$TARBALL" in
		http://* | https://*) ;;
		*) fail "the registry gives $PKG's tarball an address that is not http(s): $TARBALL" ;;
		esac
	fi
}

# Where npm keeps a version's tarball: $REGISTRY/@scope/name/-/name-VERSION.tgz.
npm_url() { printf '%s/%s/-/%s-%s.tgz' "$REGISTRY" "$PKG" "$PKG_BASE" "$SHOWN_VERSION"; }

# npm_probe URL AUTH: prints "FINAL SIZE 1": where the tarball ends up after redirects, and its size. The registry sends no
# size for a HEAD request, so this asks for the first byte and reads the size from the reply. Fails if it cannot tell.
npm_probe() {
	command -v curl >/dev/null 2>&1 || return 1
	curl -fsSL -r 0-0 -D - -o /dev/null -K "$(curl_config "$2")" -w 'url %{url_effective}\n' "$1" 2>/dev/null | tr -d '\r' | awk '
		tolower($1) == "content-range:" { split($3, a, "/"); n = a[2] }
		$1 == "url" { u = $2 }
		END { if (u == "" || n + 0 == 0) exit 1; print u, n + 0, 1 }'
}

# --- Download ---------------------------------------------------------------------------------------------------------

# start_download URL FINAL SIZE RANGES AUTH FINAL_AUTH: starts fetching into $TMP/part.N in the background, the process IDs in
# $PIDS. A big file from a server that sends parts comes in PIBOLT_DESKTOP_CONNECTIONS parts at once (default 4): over a long
# distance, one connection is limited by the round trip and several are faster.
start_download() {
	PIDS=""
	rm -f "$TMP"/part.*
	connections=${PIBOLT_DESKTOP_CONNECTIONS:-4}
	if command -v curl >/dev/null 2>&1 && [ "$4" = 1 ] && [ "$2" != - ] && [ "$3" -ge 16777216 ] && [ "$connections" -gt 1 ]; then
		chunk=$((($3 + connections - 1) / connections))
		n=0
		while [ "$n" -lt "$connections" ]; do
			from=$((n * chunk))
			to=$((from + chunk - 1))
			[ "$to" -lt "$3" ] || to=$(($3 - 1))
			curl -fsS --retry 2 -K "$(curl_config "$6")" -r "$from-$to" -o "$TMP/part.$n" "$2" 2>>"$TMP/fetch.log" &
			PIDS="$PIDS $!"
			n=$((n + 1))
		done
	else
		fetch "$TMP/part.0" "$1" "$5" 2>>"$TMP/fetch.log" &
		PIDS=$!
	fi
}

running() {
	for pid in $PIDS; do kill -0 "$pid" 2>/dev/null && return 0; done
	return 1
}

all_succeeded() { # true if every process in $PIDS succeeded
	ok=0
	for pid in $PIDS; do wait "$pid" || ok=1; done
	return "$ok"
}

# shellcheck disable=SC2012 # the installer's own file names
received() { ls -ln "$TMP"/part.* 2>/dev/null | awk '{ n += $5 } END { print n + 0 }'; }

centiseconds() { perl -MTime::HiRes=time -e 'printf "%d", time * 100' 2>/dev/null || echo 0; }

# rate_and_eta GOT TOTAL CENTISECONDS: "3.2 MB/s, 12s left", from the average so far.
rate_and_eta() {
	[ "$3" -ge 50 ] && [ "$1" -gt 0 ] || return 0
	awk -v got="$1" -v total="$2" -v t="$3" 'BEGIN {
		rate = got / (t / 100)
		printf "  %.1f MB/s", rate / 1048576
		if (total > got) {
			left = int((total - got) / rate + 0.5)
			if (left >= 60) printf ", %dm %02ds left", left / 60, left % 60; else printf ", %ds left", left
		}
	}'
}

# download URL FINAL SIZE RANGES FILE AUTH FINAL_AUTH: fetches URL into FILE with the progress bar, in parts if the server
# allows; false if it could not.
download() {
	[ -t 1 ] || printf 'downloading %s (%s MB)\n' "$1" "$(mb "$3")"
	start_download "$1" "$2" "$3" "$4" "$6" "$7"
	began=$(centiseconds)
	while [ -t 1 ] && running; do
		got=$(received)
		extra=$(rate_and_eta "$got" "$3" $(($(centiseconds) - began)))
		if [ "$3" -gt 0 ]; then
			fraction=$((got * 10000 / $3))
			[ "$fraction" -le 10000 ] || fraction=10000
			draw_progress "$step" "$fraction" "${dim}downloading $(mb "$got") / $(mb "$3") MB$extra$reset"
		else
			draw_progress "$step" -1 "${dim}downloading $(mb "$got") MB$extra$reset"
		fi
		step=$((step + 1))
		sleep 0.08
	done
	if ! all_succeeded || { [ "$PIDS" != "${PIDS% *}" ] && [ "$(received)" != "$3" ]; }; then
		# The parts did not all arrive, or not as asked for (a proxy may send the whole file for each): once more, in one piece.
		[ "$PIDS" = "${PIDS% *}" ] && return 1
		start_download "$1" - 0 0 "$6" "$7"
		while [ -t 1 ] && running; do
			draw_progress "$step" -1 "${dim}downloading $(mb "$(received)") MB, again in one piece$reset"
			step=$((step + 1))
			sleep 0.08
		done
		all_succeeded || return 1
	fi
	PIDS=""
	rm -f "$5"
	n=0
	while [ -f "$TMP/part.$n" ]; do
		cat "$TMP/part.$n" >>"$5"
		rm -f "$TMP/part.$n"
		n=$((n + 1))
	done
}

# sha512_base64 FILE: the file's SHA-512 as npm's integrity writes it (base64 of the digest).
sha512_base64() {
	if command -v openssl >/dev/null 2>&1; then
		openssl dgst -sha512 -binary "$1" | openssl base64 -A
	else
		shasum -a 512 "$1" | cut -d ' ' -f 1 | xxd -r -p | base64
	fi
}

# sha512_matches FILE BASE64: whether FILE's SHA-512 is BASE64. On Linux with sha512sum (compared in hex: no xxd needed).
sha512_matches() {
	if [ "$OS" = Linux ] && command -v sha512sum >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1; then
		[ "$(sha512sum "$1" | cut -d ' ' -f 1)" = "$(printf '%s' "$2" | base64 -d 2>/dev/null | od -An -v -tx1 | tr -d ' \n')" ]
	else
		[ "$(sha512_base64 "$1")" = "$2" ]
	fi
}

sha1_hex() { if command -v shasum >/dev/null 2>&1; then shasum -a 1 "$1"; else sha1sum "$1"; fi | cut -d ' ' -f 1; }

# verify_integrity FILE: checks FILE against the registry's dist.integrity (sha512), or its dist.shasum (SHA-1) for a
# package so old that it has no integrity.
verify_integrity() {
	want=""
	for hash in $INTEGRITY; do
		case "$hash" in sha512-*) want=${hash#sha512-} ;; esac
	done
	if [ -n "$want" ]; then
		sha512_matches "$1" "$want" && return 0
	elif [ -n "$SHASUM" ]; then
		[ "$(sha1_hex "$1")" = "$SHASUM" ] && return 0
	else
		fail "the registry gives no checksum for $PKG@$SHOWN_VERSION. Nothing was installed."
	fi
	fail "integrity check failed: the download does not match the registry's checksum for $PKG@$SHOWN_VERSION. Nothing was installed."
}

# check_sum FILE: whether FILE (in the current folder) matches its line in SHA256SUMS. No line is a mismatch.
check_sum() {
	line=$(grep " $1\$" SHA256SUMS) && [ -n "$line" ] || return 1
	if [ "$OS" = Linux ] && command -v sha256sum >/dev/null 2>&1; then
		printf '%s\n' "$line" | sha256sum -c --status - 2>/dev/null
	else
		printf '%s\n' "$line" | shasum -a 256 -c --status - 2>/dev/null
	fi
}

# fetch_package: the package .tgz in $TGZ (downloaded and checked against the registry's integrity, or PIBOLT_DESKTOP_TGZ),
# unpacked in $TMP/pkg/package with the app checked against SHA256SUMS and unpacked in $TMP/app.
fetch_package() {
	step=0
	if [ -t 1 ]; then printf '%s[?25l' "$ESC"; fi
	if [ -n "$LOCAL_TGZ" ]; then
		TGZ=$LOCAL_TGZ
		FROM=$(tilde "$LOCAL_TGZ")
	else
		TGZ="$TMP/$PKG_BASE-$SHOWN_VERSION.tgz"
		FROM=npm
		[ "$REGISTRY" = https://registry.npmjs.org ] || FROM=$(origin "$REGISTRY")
		auth=$(auth_for "$TARBALL")
		npm_probe "$TARBALL" "$auth" >"$TMP/plan" 2>/dev/null &
		PIDS=$!
		while [ -t 1 ] && running; do
			draw_progress "$step" -1 "${dim}connecting$reset"
			step=$((step + 1))
			sleep 0.08
		done
		if all_succeeded && read -r final size ranges <"$TMP/plan"; then :; else final=- size=0 ranges=0; fi
		PIDS=""
		# After a redirect to another host (a CDN), the token stays behind.
		final_auth=$auth
		[ "$final" = - ] || [ "$(origin "$final")" = "$(origin "$TARBALL")" ] || final_auth=0
		if ! download "$TARBALL" "$final" "$size" "$ranges" "$TGZ" "$auth" "$final_auth"; then
			if grep -Eq 'error: (401|403|404)' "$TMP/fetch.log"; then
				if [ "$auth" = 1 ]; then
					fail "This package is private, and the token from $TOKEN_FROM was not accepted. Set NPM_TOKEN to a registry token with read access, or add it to ~/.npmrc or ~/.bunfig.toml."
				fi
				fail "$PRIVATE_MESSAGE"
			fi
			fail "download failed: $TARBALL" "$(tail -n 1 "$TMP/fetch.log" 2>/dev/null || true)"
		fi
		draw_progress "$step" 10000 "${dim}verifying integrity$reset"
		verify_integrity "$TGZ"
	fi
	SIZE=$(wc -c <"$TGZ" | tr -d ' ')
	draw_progress "$step" 10000 "${dim}extracting$reset"
	rm -rf "$TMP/pkg" "$TMP/app"
	mkdir "$TMP/pkg" "$TMP/app"
	tar -xzf "$TGZ" -C "$TMP/pkg" 2>/dev/null || fail "could not unpack $(basename "$TGZ"): it is not an npm package."
	PKGDIR="$TMP/pkg/package"
	if [ "$OS" = Linux ]; then
		check_linux_package
		return
	fi
	[ -f "$PKGDIR/package.json" ] && [ -f "$PKGDIR/app/$APP_NAME.tar.gz" ] && [ -f "$PKGDIR/app/SHA256SUMS" ] ||
		fail "$(basename "$TGZ") is not a complete Pi-Bolt Desktop package."
	[ "$(json "$PKGDIR/package.json" field name)" = "$PKG" ] && [ "$(json "$PKGDIR/package.json" field version)" = "$SHOWN_VERSION" ] ||
		fail "$(basename "$TGZ") is not $PKG $SHOWN_VERSION."
	draw_progress "$step" 10000 "${dim}verifying checksum$reset"
	(cd "$PKGDIR/app" && check_sum "$APP_NAME.tar.gz") ||
		fail "$APP_NAME.tar.gz does not match its SHA-256 in SHA256SUMS: the package is damaged or was altered. Nothing was installed."
	tar -xzf "$PKGDIR/app/$APP_NAME.tar.gz" -C "$TMP/app" 2>/dev/null && [ -d "$TMP/app/$APP_NAME" ] ||
		fail "could not unpack $APP_NAME.tar.gz."
	id=$(plist "$TMP/app/$APP_NAME" CFBundleIdentifier)
	[ "$id" = "$BUNDLE_ID" ] || fail "the packaged app is not Pi-Bolt (bundle id ${id:-missing}). Nothing was installed."
	APP_VERSION=$(plist "$TMP/app/$APP_NAME" CFBundleShortVersionString)
	[ -n "$APP_VERSION" ] || APP_VERSION=$SHOWN_VERSION
}

# --- The app ----------------------------------------------------------------------------------------------------------

plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1/Contents/Info.plist" 2>/dev/null || true; }

# The folders the app may be in, the preferred one first.
search_dirs() {
	if [ -n "${PIBOLT_DESKTOP_DIR:-}" ]; then printf '%s\n' "$PIBOLT_DESKTOP_DIR"
	elif [ -n "$USER_FLAG" ]; then printf '%s\n' "$HOME/Applications"
	else printf '%s\n' /Applications "$HOME/Applications"; fi
}

# EXISTING: the installed Pi-Bolt.app (the first found), INSTALLED_VERSION its version, OTHERS any further copies.
find_installed() {
	EXISTING="" INSTALLED_VERSION="" OTHERS=""
	if [ "$OS" = Linux ]; then
		find_installed_linux
		return
	fi
	old_ifs=$IFS
	IFS='
'
	for dir in $(search_dirs); do
		[ -d "$dir/$APP_NAME" ] || continue
		if [ -z "$EXISTING" ]; then EXISTING="$dir/$APP_NAME"; else OTHERS="$OTHERS$dir/$APP_NAME
"; fi
	done
	IFS=$old_ifs
	[ -n "$EXISTING" ] || return 0
	id=$(plist "$EXISTING" CFBundleIdentifier)
	[ "$id" = "$BUNDLE_ID" ] || fail "$(tilde "$EXISTING") is another app (${id:-no bundle id}); not touching it."
	INSTALLED_VERSION=$(plist "$EXISTING" CFBundleShortVersionString)
}

# version_cmp A B: 1 if A is newer than B, 0 if the same, -1 if older. A prerelease (1.2.0-beta.1) is older than its release.
version_cmp() {
	awk -v a="$1" -v b="$2" 'BEGIN {
		split(a, ap, "-"); split(b, bp, "-")
		pa = substr(a, length(ap[1]) + 2); pb = substr(b, length(bp[1]) + 2)
		na = split(ap[1], x, "."); nb = split(bp[1], y, ".")
		n = na > nb ? na : nb
		for (i = 1; i <= n; i++) { d = (x[i] + 0) - (y[i] + 0); if (d != 0) { print (d > 0 ? 1 : -1); exit } }
		if (pa == pb) print 0; else if (pa == "") print 1; else if (pb == "") print -1; else print (pa > pb ? 1 : -1)
	}'
}

# is_running APP: whether Pi-Bolt runs from APP (its executable's path, as a pattern with the special characters escaped).
is_running() {
	pattern=$(printf '%s' "$1/Contents/MacOS/" | sed 's/[][\\.*^$+?(){}|]/\\&/g')
	pgrep -f "^$pattern" >/dev/null 2>&1
}

refuse_if_running() {
	[ -n "$EXISTING" ] || return 0
	if [ "$OS" = Linux ]; then
		refuse_if_running_linux
		return
	fi
	if is_running "$EXISTING"; then
		finish_progress
		printf '%serror:%s Pi-Bolt %s is running from %s. Quit it (Cmd-Q), then run this again.\n' "$red" "$reset" "$INSTALLED_VERSION" "$(tilde "$EXISTING")" >&2
		exit 3
	fi
}

destination_dir() {
	if [ -n "${PIBOLT_DESKTOP_DIR:-}" ]; then printf '%s' "$PIBOLT_DESKTOP_DIR"
	elif [ -n "$USER_FLAG" ]; then printf '%s' "$HOME/Applications"
	elif [ -n "$EXISTING" ]; then dirname "$EXISTING"
	elif [ -w /Applications ]; then printf '%s' /Applications
	else printf '%s' "$HOME/Applications"; fi
}

# install_app: puts $TMP/app/Pi-Bolt.app in place. It is copied (ditto) next to the destination, on the same volume, made
# ready there (quarantine flag off, code signature verified, or signed ad hoc only if it does not verify), then swapped in: the
# old copy moves aside first and comes back if the new one cannot take its place.
install_app() {
	if [ "$OS" = Linux ]; then
		install_user_linux
		return
	fi
	dir=$(destination_dir)
	DEST="$dir/$APP_NAME"
	draw_progress "$step" 10000 "${dim}installing into $(tilde "$dir")$reset"
	mkdir -p "$dir" 2>/dev/null || fail "cannot create $(tilde "$dir")."
	if ! STAGE=$(mktemp -d "$dir/.Pi-Bolt.app.install-XXXXXX" 2>/dev/null); then
		STAGE=""
		if [ -n "$USER_FLAG" ] || [ -n "${PIBOLT_DESKTOP_DIR:-}" ]; then fail "cannot write to $(tilde "$dir")."; fi
		fail "cannot write to $(tilde "$dir")." "Run it with --user to install to ~/Applications."
	fi
	fresh="$STAGE/$APP_NAME"
	ditto "$TMP/app/$APP_NAME" "$fresh" || fail "could not copy the app into $(tilde "$dir")."
	xattr -dr com.apple.quarantine "$fresh" 2>/dev/null || true
	draw_progress "$step" 10000 "${dim}checking the code signature$reset"
	if codesign --verify --deep --strict "$fresh" >/dev/null 2>&1; then
		SIGNATURE="signature verified"
	else
		if ! codesign --force --deep --sign - "$fresh" >/dev/null 2>&1 || ! codesign --verify --deep --strict "$fresh" >/dev/null 2>&1; then
			fail "the app's code signature does not verify, and signing it ad hoc failed. Nothing was installed."
		fi
		SIGNATURE="signed ad hoc on this Mac"
	fi
	if [ -e "$DEST" ]; then
		id=$(plist "$DEST" CFBundleIdentifier)
		[ "$id" = "$BUNDLE_ID" ] || fail "$(tilde "$DEST") is another app (${id:-no bundle id}); not replacing it."
		if is_running "$DEST"; then
			finish_progress
			printf '%serror:%s Pi-Bolt is running from %s. Quit it (Cmd-Q), then run this again.\n' "$red" "$reset" "$(tilde "$DEST")" >&2
			exit 3
		fi
		mv "$DEST" "$STAGE/previous.app" || fail "could not move the old $(tilde "$DEST") aside."
		if ! mv "$fresh" "$DEST"; then
			mv "$STAGE/previous.app" "$DEST" || true
			fail "could not put the new app at $(tilde "$DEST"); the old one is back."
		fi
	else
		mv "$fresh" "$DEST" || fail "could not put the app at $(tilde "$DEST")."
	fi
	rm -rf "$STAGE"
	STAGE=""
}

open_app() {
	if [ "$OS" = Linux ]; then
		open_app_linux "$1"
		return
	fi
	if open "$1" >/dev/null 2>&1; then
		printf '\nOpened %s.\n' "$(tilde "$1")"
	else
		printf '\n%sCould not open %s.%s\n' "$dim" "$(tilde "$1")" "$reset"
	fi
}

agent_found() {
	[ -n "${PI_BOLT_BIN:-}" ] && [ -f "$PI_BOLT_BIN" ] && return 0
	command -v pi-bolt >/dev/null 2>&1 && return 0
	for p in "$HOME/.pi-bolt/pi" "$HOME/.pi-bolt/pi-bolt" "$HOME/.local/bin/pi-bolt"; do [ -f "$p" ] && return 0; done
	if [ "$OS" = Linux ]; then
		for p in "$HOME"/.pi-bolt/pi-bolt-linux-*/pi; do [ -f "$p" ] && return 0; done
	else
		for p in "$HOME"/.pi-bolt/pi-bolt-darwin-*/pi; do [ -f "$p" ] && return 0; done
	fi
	return 1
}

# --- Linux ------------------------------------------------------------------------------------------------------------

# The app's executable is pi-bolt-desktop: also its window class (StartupWMClass) and its icon's name, as in the .deb and .rpm,
# which install the system package pi-bolt.
LINUX_BIN="pi-bolt-desktop"
SYSTEM_BIN="/usr/bin/pi-bolt-desktop"
SYSTEM_PKG="pi-bolt"
# Written into the files the user install adds (and checked before replacing or removing one); the version of the extracted
# app is in VERSION_FILE inside it.
MARKER="Pi-Bolt Desktop, installed by install-desktop.sh (run it with --uninstall to remove)"
VERSION_FILE=".pi-bolt-desktop-version"

preflight_linux() {
	case "$(uname -m)" in
	x86_64 | amd64) ;;
	*)
		printf 'error: Pi-Bolt Desktop for Linux needs an x86-64 (amd64) CPU; this one is %s.\n' "$(uname -m)"
		status=1
		;;
	esac
	if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
		printf 'error: curl or wget is required.\n'
		status=1
	fi
	for tool in tar gzip; do
		command -v "$tool" >/dev/null 2>&1 || { printf 'error: %s is required.\n' "$tool"; status=1; }
	done
	if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
		printf 'error: sha256sum (coreutils) is required.\n'
		status=1
	fi
	if ! { command -v sha512sum >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1 && command -v od >/dev/null 2>&1; } &&
		! command -v openssl >/dev/null 2>&1; then
		printf 'error: sha512sum and base64 (coreutils), or openssl, are required.\n'
		status=1
	fi
	[ "$status" -eq 0 ] || printf '\n'
	return "$status"
}

# json_linux: json (see there) with python3, else Bun, else awk.
# shellcheck disable=SC2016 # Python, JavaScript and awk, not shell
JSON_PY='import json, sys
def clean(v): return v.replace("\r", "").replace("\n", "") if isinstance(v, str) else ""
doc = json.load(open(sys.argv[1], encoding="utf-8"))
if not isinstance(doc, dict): sys.exit(1)
if sys.argv[2] == "field":
	print(clean(doc.get(sys.argv[3])))
	sys.exit(0)
tags = doc.get("dist-tags") if isinstance(doc.get("dist-tags"), dict) else {}
versions = doc.get("versions") if isinstance(doc.get("versions"), dict) else {}
version = tags[sys.argv[3]] if sys.argv[3] in tags else sys.argv[3]
entry = versions.get(version) if isinstance(version, str) else None
if not isinstance(entry, dict) or not isinstance(entry.get("dist"), dict):
	print("missing\n" + clean(tags.get("latest")))
else:
	dist = entry["dist"]
	print("\n".join(["ok", clean(version), clean(dist.get("tarball")), clean(dist.get("integrity")), clean(dist.get("shasum"))]))'
# shellcheck disable=SC2016
JSON_BUN='const [file, mode, want] = process.argv.slice(2);
const doc = JSON.parse(require("fs").readFileSync(file, "utf8"));
const clean = (v) => (typeof v === "string" ? v.replace(/[\r\n]/g, "") : "");
const own = (o, k) => o !== null && typeof o === "object" && Object.prototype.hasOwnProperty.call(o, k);
if (doc === null || typeof doc !== "object" || Array.isArray(doc)) process.exit(1);
if (mode === "field") {
	console.log(clean(doc[want]));
} else {
	const tags = doc["dist-tags"] || {}, versions = doc.versions || {};
	const version = own(tags, want) ? tags[want] : want;
	const entry = own(versions, version) ? versions[version] : null;
	if (!entry || !entry.dist) console.log("missing\n" + clean(tags.latest));
	else console.log(["ok", clean(version), clean(entry.dist.tarball), clean(entry.dist.integrity), clean(entry.dist.shasum)].join("\n"));
}'
# A small JSON parser in POSIX awk, for systems with neither python3 nor Bun: it reads the whole document (rejecting what is
# not JSON), keeps its strings by their path, and answers the same questions as above.
# shellcheck disable=SC2016
JSON_AWK='
function bad_json() { bad = 1 }
function skip() { while (pos <= n && index(" \t\r\n", substr(text, pos, 1)) > 0) pos++ }
function str(   c, out) {
	pos++
	out = ""
	while (pos <= n) {
		c = substr(text, pos, 1)
		if (c == "\"") { pos++; return out }
		if (c == "\\") {
			pos++
			c = substr(text, pos, 1)
			if (c == "n") c = "\n"
			else if (c == "t") c = "\t"
			else if (c == "r") c = "\r"
			else if (c == "b" || c == "f") c = ""
			else if (c == "u") { c = "?"; pos += 4 }
		}
		out = out c
		pos++
	}
	bad_json()
	return ""
}
function value(path,   c, k, i, start) {
	if (bad) return
	if (++depth > 64) { bad_json(); return }
	skip()
	c = substr(text, pos, 1)
	if (c == "{") {
		pos++
		skip()
		if (substr(text, pos, 1) == "}") pos++
		else while (!bad) {
			skip()
			if (substr(text, pos, 1) != "\"") { bad_json(); return }
			k = str()
			skip()
			if (substr(text, pos, 1) != ":") { bad_json(); return }
			pos++
			value(path SUBSEP k)
			skip()
			c = substr(text, pos, 1)
			pos++
			if (c == "}") break
			if (c != ",") { bad_json(); return }
		}
	} else if (c == "[") {
		pos++
		skip()
		if (substr(text, pos, 1) == "]") pos++
		else for (i = 0; !bad; i++) {
			value(path SUBSEP i)
			skip()
			c = substr(text, pos, 1)
			pos++
			if (c == "]") break
			if (c != ",") { bad_json(); return }
		}
	} else if (c == "\"") {
		strings[path] = str()
	} else {
		start = pos
		while (pos <= n && index(",:]}[{\" \t\r\n", substr(text, pos, 1)) == 0) pos++
		if (substr(text, start, pos - start) !~ /^(true|false|null|-?[0-9][0-9.eE+-]*)$/) { bad_json(); return }
	}
	depth--
}
function clean(v) { gsub(/[\r\n]/, "", v); return v }
{ text = text $0 "\n" }
END {
	n = length(text)
	pos = 1
	skip()
	if (substr(text, pos, 1) != "{") exit 1
	value("")
	skip()
	if (bad || pos <= n) exit 1
	if (mode == "field") { print clean(strings[SUBSEP want]); exit 0 }
	t = SUBSEP "dist-tags" SUBSEP want
	version = (t in strings) ? strings[t] : want
	d = SUBSEP "versions" SUBSEP version SUBSEP "dist" SUBSEP
	if (!((d "tarball") in strings) && !((d "integrity") in strings) && !((d "shasum") in strings)) {
		print "missing"
		print clean(strings[SUBSEP "dist-tags" SUBSEP "latest"])
		exit 0
	}
	print "ok"
	print clean(version)
	print clean(strings[d "tarball"])
	print clean(strings[d "integrity"])
	print clean(strings[d "shasum"])
}'
json_linux() {
	if [ "${PIBOLT_DESKTOP_JSON:-}" != awk ] && [ "${PIBOLT_DESKTOP_JSON:-}" != bun ] && command -v python3 >/dev/null 2>&1; then
		python3 -c "$JSON_PY" "$@" 2>/dev/null
	elif [ "${PIBOLT_DESKTOP_JSON:-}" != awk ] && find_bun; then
		printf '%s\n' "$JSON_BUN" >"$TMP/json.js"
		"$BUN" "$TMP/json.js" "$@" 2>/dev/null
	else
		LC_ALL=C awk -v mode="$2" -v want="$3" "$JSON_AWK" "$1" 2>/dev/null
	fi
}

has_display() { [ "$OS" != Linux ] || [ -n "${DISPLAY:-}" ] || [ -n "${WAYLAND_DISPLAY:-}" ]; }

on_path() { case ":$PATH:" in *":$1:"*) return 0 ;; esac; return 1; }

open_hint() {
	if [ "$OS" != Linux ]; then
		printf 'Open it from Launchpad or Spotlight.'
	elif on_path "$BIN_DIR"; then
		printf 'Open it from your applications menu, or run %s.' "$(basename "$LAUNCHER")"
	else
		printf 'Open it from your applications menu, or run %s.' "$(tilde "$LAUNCHER")"
	fi
}

# install_target: where the app goes, for the summary before installing.
install_target() {
	if [ "$OS" != Linux ]; then
		tilde "$(destination_dir)/$APP_NAME"
	else
		printf '%s %s(for this user, no root needed)%s' "$(tilde "$APP_DIR")" "$dim" "$reset"
	fi
}

# The user install's files are ours when they hold the marker (the launcher, the menu entry) or the version file (the app).
ours() { [ -f "$1" ] && grep -qF "$MARKER" "$1" 2>/dev/null; }
app_ours() { [ -f "$1/$VERSION_FILE" ] || { [ -e "$1/AppRun" ] && [ -f "$1/usr/bin/$LINUX_BIN" ]; }; }

# system_version: the version of the system package pi-bolt (from dpkg or rpm), empty when it is not installed.
system_version() {
	v=""
	if command -v dpkg-query >/dev/null 2>&1; then
		# shellcheck disable=SC2016 # dpkg-query's format, not shell
		v=$(dpkg-query -W -f='${Status}|${Version}' "$SYSTEM_PKG" 2>/dev/null || true)
		case "$v" in *" installed|"*) v=${v#*|} ;; *) v="" ;; esac
	fi
	if [ -z "$v" ] && command -v rpm >/dev/null 2>&1; then
		v=$(rpm -q --qf '%{VERSION}' "$SYSTEM_PKG" 2>/dev/null) || v=""
	fi
	printf '%s' "$v"
}

# The command that removes the system package, for messages.
system_remove_command() {
	if command -v dpkg-query >/dev/null 2>&1 && dpkg-query -W "$SYSTEM_PKG" >/dev/null 2>&1; then printf 'sudo apt remove %s' "$SYSTEM_PKG"
	elif command -v dnf >/dev/null 2>&1; then printf 'sudo dnf remove %s' "$SYSTEM_PKG"
	elif command -v yum >/dev/null 2>&1; then printf 'sudo yum remove %s' "$SYSTEM_PKG"
	elif command -v zypper >/dev/null 2>&1; then printf 'sudo zypper remove %s' "$SYSTEM_PKG"
	else printf 'sudo apt remove %s' "$SYSTEM_PKG"; fi
}

# EXISTING: the install this run updates (the user install, or with --deb/--rpm the system package's executable), with
# INSTALLED_VERSION; OTHERS: the other kind, if it is there too.
find_installed_linux() {
	SYSTEM_VERSION=$(system_version)
	user_app=""
	if [ -e "$APP_DIR" ]; then
		app_ours "$APP_DIR" || fail "$(tilde "$APP_DIR") is not a Pi-Bolt Desktop install; not touching it."
		user_app=$APP_DIR
	fi
	if [ "$MODE" = user ]; then
		if [ -n "$user_app" ]; then
			EXISTING=$user_app
			INSTALLED_VERSION=$(head -n 1 "$APP_DIR/$VERSION_FILE" 2>/dev/null || true)
		fi
		[ -z "$SYSTEM_VERSION" ] || OTHERS="$SYSTEM_BIN (the system package $SYSTEM_PKG $SYSTEM_VERSION)"
	else
		if [ -n "$SYSTEM_VERSION" ]; then EXISTING=$SYSTEM_BIN INSTALLED_VERSION=$SYSTEM_VERSION; fi
		[ -z "$user_app" ] || OTHERS="$user_app (the user install; --uninstall removes it)"
	fi
}

# is_running_linux PATH: whether a process runs the executable PATH, or one under PATH when it ends in /. It reads where the
# processes' /proc/PID/exe links point (exact, whatever their command lines say), else asks pgrep.
is_running_linux() {
	if [ -d /proc/self ]; then
		# shellcheck disable=SC2012 # ls -l shows where each link points
		ls -l /proc/[0-9]*/exe 2>/dev/null | P="$1" awk '
			{ i = index($0, " -> "); if (i == 0) next; exe = substr($0, i + 4); sub(/ \(deleted\)$/, "", exe) }
			(ENVIRON["P"] ~ /\/$/ && index(exe, ENVIRON["P"]) == 1) || exe == ENVIRON["P"] { found = 1; exit }
			END { exit !found }'
	else
		pgrep -f -- "$1" >/dev/null 2>&1
	fi
}

refuse_if_running_linux() {
	if [ "$EXISTING" = "$SYSTEM_BIN" ]; then running=$SYSTEM_BIN; else running="$EXISTING/"; fi
	if is_running_linux "$running"; then
		finish_progress
		printf '%serror:%s Pi-Bolt %s is running from %s. Quit it, then run this again.\n' "$red" "$reset" "$INSTALLED_VERSION" "$(tilde "$EXISTING")" >&2
		exit 3
	fi
}

# check_linux_package: the download is the Linux package of this version, and the file this run installs (and the icon)
# matches its SHA-256 in SHA256SUMS.
check_linux_package() {
	ARTIFACT=Pi-Bolt.AppImage
	[ -f "$PKGDIR/package.json" ] && [ -f "$PKGDIR/app/$ARTIFACT" ] && [ -f "$PKGDIR/app/SHA256SUMS" ] ||
		fail "$(basename "$TGZ") is not a complete Pi-Bolt Desktop package for Linux."
	[ "$(json "$PKGDIR/package.json" field name)" = "$PKG" ] && [ "$(json "$PKGDIR/package.json" field version)" = "$SHOWN_VERSION" ] ||
		fail "$(basename "$TGZ") is not $PKG $SHOWN_VERSION."
	draw_progress "$step" 10000 "${dim}verifying checksum$reset"
	(cd "$PKGDIR/app" && check_sum "$ARTIFACT") ||
		fail "$ARTIFACT does not match its SHA-256 in SHA256SUMS: the package is damaged or was altered. Nothing was installed."
	if [ "$MODE" = user ]; then
		{ [ -f "$PKGDIR/app/pi-bolt-desktop.png" ] && (cd "$PKGDIR/app" && check_sum pi-bolt-desktop.png); } ||
			fail "pi-bolt-desktop.png does not match its SHA-256 in SHA256SUMS: the package is damaged or was altered. Nothing was installed."
	fi
	APP_VERSION=$SHOWN_VERSION
	SIGNATURE="checksums verified"
}

# sh_quote S: S in single quotes, for a shell script.
sh_quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# desktop_quote S: S as one quoted argument of a desktop entry's Exec key: the spec's quoting (\ before " ` $ and \), then
# its string escape (\ as \\), and % as %%.
desktop_quote() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\\\\\/g' -e 's/[$`"]/\\\\&/g' -e 's/%/%%/g')"; }

# put FILE: moves FILE.part, which the caller wrote, into place as FILE.
put() { mv -f "$1.part" "$1"; }

# refresh_menus: tells the desktop about the new or removed menu entry and icon, with the tools for it where they are installed.
refresh_menus() {
	if command -v update-desktop-database >/dev/null 2>&1 && [ -d "$DATA_HOME/applications" ]; then
		update-desktop-database -q "$DATA_HOME/applications" >/dev/null 2>&1 || true
	fi
	if command -v gtk-update-icon-cache >/dev/null 2>&1 && [ -d "$ICON_THEME" ]; then
		touch "$ICON_THEME" 2>/dev/null || true
		gtk-update-icon-cache -q -t -f "$ICON_THEME" >/dev/null 2>&1 || true
	fi
}

# install_user_linux: extracts the AppImage (--appimage-extract: no FUSE needed) next to the destination, then swaps it in
# as on a Mac: the old copy moves aside first and comes back if the new one cannot take its place. Then the launcher, the
# menu entry and the icon.
install_user_linux() {
	DEST=$APP_DIR
	for f in "$LAUNCHER" "$DESKTOP_FILE"; do
		[ ! -e "$f" ] || ours "$f" || fail "$(tilde "$f") is not Pi-Bolt Desktop's; not replacing it."
	done
	draw_progress "$step" 10000 "${dim}extracting the AppImage into $(tilde "$APP_HOME")$reset"
	mkdir -p "$APP_HOME" 2>/dev/null || fail "cannot create $(tilde "$APP_HOME")."
	if ! STAGE=$(mktemp -d "$APP_HOME/.app.install-XXXXXX" 2>/dev/null); then
		STAGE=""
		fail "cannot write to $(tilde "$APP_HOME")."
	fi
	appimage="$PKGDIR/app/Pi-Bolt.AppImage"
	chmod 755 "$appimage" 2>/dev/null || true
	if ! (cd "$STAGE" && "$appimage" --appimage-extract >"$TMP/extract.log" 2>&1); then
		# The temporary folder may be mounted noexec: run it from the destination's folder instead.
		rm -rf "$STAGE/squashfs-root"
		if ! cp "$appimage" "$STAGE/Pi-Bolt.AppImage" || ! chmod 755 "$STAGE/Pi-Bolt.AppImage" ||
			! (cd "$STAGE" && ./Pi-Bolt.AppImage --appimage-extract >"$TMP/extract.log" 2>&1); then
			fail "could not extract the AppImage. Nothing was installed." "$(tail -n 2 "$TMP/extract.log" 2>/dev/null || true)"
		fi
		rm -f "$STAGE/Pi-Bolt.AppImage"
	fi
	fresh="$STAGE/squashfs-root"
	[ -e "$fresh/AppRun" ] && [ -f "$fresh/usr/bin/$LINUX_BIN" ] ||
		fail "the AppImage does not hold Pi-Bolt (no usr/bin/$LINUX_BIN). Nothing was installed."
	printf '%s\n' "$SHOWN_VERSION" >"$fresh/$VERSION_FILE"
	chmod 755 "$fresh"
	if [ -e "$DEST" ]; then
		app_ours "$DEST" || fail "$(tilde "$DEST") is not a Pi-Bolt Desktop install; not replacing it."
		if is_running_linux "$DEST/"; then
			finish_progress
			printf '%serror:%s Pi-Bolt is running from %s. Quit it, then run this again.\n' "$red" "$reset" "$(tilde "$DEST")" >&2
			exit 3
		fi
		mv "$DEST" "$STAGE/previous.app" || fail "could not move the old $(tilde "$DEST") aside."
		if ! mv "$fresh" "$DEST"; then
			mv "$STAGE/previous.app" "$DEST" || true
			fail "could not put the new app at $(tilde "$DEST"); the old one is back."
		fi
	else
		mv "$fresh" "$DEST" || fail "could not put the app at $(tilde "$DEST")."
	fi
	rm -rf "$STAGE"
	STAGE=""

	draw_progress "$step" 10000 "${dim}adding it to the applications menu$reset"
	mkdir -p "$BIN_DIR" "$(dirname "$DESKTOP_FILE")" "$(dirname "$ICON_FILE")" || fail "cannot create the folders in ~/.local."
	{
		printf '#!/bin/sh\n# %s\n' "$MARKER"
		printf 'exec %s "$@"\n' "$(sh_quote "$DEST/AppRun")"
	} >"$LAUNCHER.part" || fail "could not write $(tilde "$LAUNCHER")."
	{ chmod 755 "$LAUNCHER.part" && put "$LAUNCHER"; } || fail "could not write $(tilde "$LAUNCHER")."
	{
		printf '# %s\n' "$MARKER"
		printf '[Desktop Entry]\nType=Application\nName=Pi-Bolt\nGenericName=Coding Agent\n'
		printf 'Comment=Desktop app for the Pi-Bolt coding agent\n'
		printf 'Exec=%s\n' "$(desktop_quote "$LAUNCHER")"
		printf 'Icon=%s\nTerminal=false\nCategories=Development;IDE;\nKeywords=pi;agent;coding;ai;\n' "$LINUX_BIN"
		printf 'StartupWMClass=%s\nStartupNotify=true\n' "$LINUX_BIN"
	} >"$DESKTOP_FILE.part" || fail "could not write $(tilde "$DESKTOP_FILE")."
	{ chmod 644 "$DESKTOP_FILE.part" && put "$DESKTOP_FILE"; } || fail "could not write $(tilde "$DESKTOP_FILE")."
	{ cp "$PKGDIR/app/pi-bolt-desktop.png" "$ICON_FILE.part" && chmod 644 "$ICON_FILE.part" && put "$ICON_FILE"; } ||
		fail "could not write $(tilde "$ICON_FILE")."
	refresh_menus
}

# missing_libraries: the system libraries the user install needs that this system does not have. An AppImage carries
# WebKitGTK and GTK but, by design, not what every desktop has (X11 and xcb, Wayland, fontconfig, freetype, harfbuzz, GBM...),
# so this is empty on a desktop and lists them on a minimal system (a server, a container).
missing_libraries() {
	[ "$OS" = Linux ] && [ "$MODE" = user ] && command -v ldd >/dev/null 2>&1 || return 0
	ldd "$APP_DIR/usr/bin/$LINUX_BIN" 2>/dev/null | awk '$2 == "=>" && $3 == "not" { print $1 }' | sort -u | tr '\n' ' ' | sed 's/ $//'
}

warn_missing_libraries() {
	libs=$(missing_libraries)
	[ -n "$libs" ] || return 0
	printf '\n%sNote:%s Pi-Bolt needs libraries this system does not have: %s\n' "$bold" "$reset" "$libs"
	printf '%sEvery desktop system has them; on a server or in a container, install them with your package manager.%s\n' "$dim" "$reset"
}

open_app_linux() {
	if [ "$1" = "$SYSTEM_BIN" ]; then cmd=$SYSTEM_BIN; else cmd="$1/AppRun"; fi
	if ! has_display; then
		printf '\n%sNo display here (neither DISPLAY nor WAYLAND_DISPLAY is set): open Pi-Bolt from your desktop.%s\n' "$dim" "$reset"
		return 0
	fi
	(cd / && nohup "$cmd" >/dev/null 2>&1 </dev/null &)
	printf '\nOpened Pi-Bolt.\n'
}

# Where Pi-Bolt keeps its settings and web data on Linux: the XDG folders named after the app's identifier.
linux_data_paths() {
	printf '%s\n' "$DATA_HOME/$BUNDLE_ID" "${XDG_CONFIG_HOME:-$HOME/.config}/$BUNDLE_ID" "${XDG_CACHE_HOME:-$HOME/.cache}/$BUNDLE_ID"
}

uninstall_linux() {
	if [ -e "$APP_DIR" ]; then
		app_ours "$APP_DIR" || fail "$(tilde "$APP_DIR") is not a Pi-Bolt Desktop install; not removing it."
		if is_running_linux "$APP_DIR/"; then
			printf '%serror:%s Pi-Bolt is running from %s. Quit it, then run this again.\n' "$red" "$reset" "$(tilde "$APP_DIR")" >&2
			exit 3
		fi
		rm -rf "$APP_DIR" || fail "could not remove $(tilde "$APP_DIR")."
		printf '  %s%s%s removed %s\n' "$green" "$CHECK" "$reset" "$(tilde "$APP_DIR")"
		REMOVED=1
	fi
	for f in "$LAUNCHER" "$DESKTOP_FILE"; do
		ours "$f" || continue
		rm -f "$f" && printf '  %s%s%s removed %s\n' "$green" "$CHECK" "$reset" "$(tilde "$f")"
		REMOVED=1
	done
	if [ "$REMOVED" = 1 ] && [ -f "$ICON_FILE" ]; then
		rm -f "$ICON_FILE" && printf '  %s%s%s removed %s\n' "$green" "$CHECK" "$reset" "$(tilde "$ICON_FILE")"
	fi
	[ "$REMOVED" = 0 ] || refresh_menus
	remove_cli
	[ -n "${PIBOLT_DESKTOP_DIR:-}" ] || rmdir "$APP_HOME" 2>/dev/null || true
	kept=""
	while IFS= read -r p; do
		[ -e "$p" ] || continue
		if [ -n "$PURGE" ]; then
			rm -rf "$p" && printf '  %s%s%s removed %s\n' "$green" "$CHECK" "$reset" "$(tilde "$p")"
			REMOVED=1
		else
			kept="$kept $(tilde "$p")"
		fi
	done <<EOF
$(linux_data_paths)
EOF
	[ -z "$kept" ] || printf '  %sKept your settings and data in%s (run with --uninstall --purge to remove them).%s\n' "$dim" "$kept" "$reset"
	SYSTEM_VERSION=$(system_version)
	[ "$REMOVED" = 0 ] || printf '\nPi-Bolt Desktop was uninstalled.\n'
	if [ -n "$SYSTEM_VERSION" ]; then
		also=""
		[ "$REMOVED" = 0 ] || { printf '\n'; also="also "; }
		printf 'Pi-Bolt Desktop %s is %sinstalled system-wide, as the package %s. Remove it with:\n\n  %s\n' "$SYSTEM_VERSION" "$also" "$SYSTEM_PKG" "$(system_remove_command)"
	elif [ "$REMOVED" = 0 ]; then
		printf 'Pi-Bolt Desktop is not installed (not in %s, and no system package %s). Nothing to remove.\n' "$(tilde "$APP_DIR")" "$SYSTEM_PKG"
	fi
}

# --- The agent --------------------------------------------------------------------------------------------------------

# The app includes a Pi-Bolt agent. The standalone agent (the pi-bolt command, with its own updates in ~/.pi-bolt) is
# optional: install_agent runs Pi-Bolt's installer for it with --agent, or, for an app build without a bundled agent, with
# --yes or a yes at the prompt. It is not asked again (PIBOLT_YES) and does not start Pi-Bolt. If it
# does not finish, the app stays installed and the command to install the agent is printed.
install_agent() {
	agent_found && return 0
	# The app carries its own agent: install the standalone one only when asked for (--agent).
	if [ "$AGENT" != yes ]; then
		r=$(cli_runtime)
		[ -z "$r" ] || [ ! -x "$r" ] || return 0
	fi
	if [ "$AGENT" = no ]; then
		agent_hint
		return 0
	elif [ "$AGENT" = yes ] || [ "$YES" = 1 ]; then
		:
	elif ! has_tty || ! ask "Pi-Bolt Desktop runs the Pi-Bolt agent, which is not installed. Install it now?" y; then
		agent_hint
		return 0
	fi
	printf '\nInstalling the Pi-Bolt agent %s(%s)%s\n\n' "$dim" "$AGENT_INSTALLER" "$reset"
	if ! fetch "$TMP/agent-install.sh" "$AGENT_INSTALLER" 0 2>>"$TMP/fetch.log"; then
		printf '%sCould not download %s.%s\n' "$red" "$AGENT_INSTALLER" "$reset"
		agent_hint
		return 0
	fi
	if PIBOLT_YES=1 PIBOLT_NO_START=1 sh "$TMP/agent-install.sh" </dev/null && agent_found; then
		printf '\n%s%s%s Pi-Bolt Desktop will run this Pi-Bolt.\n' "$green" "$CHECK" "$reset"
	else
		printf '\n%sThe Pi-Bolt installer did not finish. Pi-Bolt Desktop is installed, but it needs the agent.%s\n' "$red" "$reset"
		agent_hint
	fi
}

agent_hint() {
	printf '\nPi-Bolt Desktop runs the Pi-Bolt agent, which is not installed. Install it with:\n\n  curl -fsSL %s | sh\n' "$AGENT_INSTALLER"
}

# --- The command ------------------------------------------------------------------------------------------------------

# The pi-bolt-desktop command is a Bun script. It needs no Bun install: a small launcher in ~/.local/bin runs it on the Bun
# runtime inside the app's bundled Pi-Bolt agent (BUN_BE_BUN=1), and falls back to a bun on PATH. Its files live in
# CLI_DIR (a subfolder, so removing the command never touches an app installed next to it).
CLI_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/pi-bolt-desktop/cli"
CLI_BIN="$HOME/.local/bin/pi-bolt-desktop"
BUN_HOME="${BUN_INSTALL:-$HOME/.bun}"

# find_bun: BUN, a bun the user already has (only a fallback JSON parser on Linux; never installed by this script).
find_bun() {
	BUN=$(command -v bun 2>/dev/null || true)
	[ -n "$BUN" ] || { [ -x "$BUN_HOME/bin/bun" ] && BUN="$BUN_HOME/bin/bun"; }
	[ -n "$BUN" ]
}

# Whether the command is installed (this launcher, or an older Bun global install).
cli_installed() { [ -f "$CLI_DIR/bin/pi-bolt-desktop.js" ] || [ -f "$BUN_HOME/install/global/node_modules/$PKG/package.json" ]; }

# cli_runtime: the Pi-Bolt agent whose Bun runtime runs the command: on a Mac the one inside the app just installed; on
# Linux the agent installed by Pi-Bolt's installer (the Linux app does not carry one).
cli_runtime() {
	case "$OS" in
	Darwin) printf '%s' "$DEST/Contents/Resources/resources/agent/pi" ;;
	*) for r in "$HOME/.pi-bolt/pi-bolt-linux-x64/pi" "$HOME/.pi-bolt/pi-bolt-linux-x64-baseline/pi" "$(command -v pi-bolt 2>/dev/null || true)"; do
		[ -n "$r" ] && [ -x "$r" ] && { printf '%s' "$r"; return 0; }
	done ;;
	esac
	return 0 # none yet: callers test the result (and set -e must not stop the script here)
}

install_cli() {
	[ "$CLI" != no ] || return 0
	installed_before=0
	if cli_installed; then installed_before=1; fi
	if [ "$CLI" = ask ]; then
		if [ "$installed_before" = 1 ]; then
			CLI=yes
		elif has_tty && ask "Also install the pi-bolt-desktop command (install, update, open, doctor)?" n; then
			CLI=yes
		else
			return 0
		fi
	fi
	[ -f "$PKGDIR/bin/pi-bolt-desktop.js" ] || fail "the package has no pi-bolt-desktop command."
	# The package's layout without the app archive: the command then installs and updates the app from the registry.
	mkdir -p "$CLI_DIR/bin" "$(dirname "$CLI_BIN")" || fail "cannot create $(tilde "$CLI_DIR")."
	if ! { cp "$PKGDIR/bin/pi-bolt-desktop.js" "$CLI_DIR/bin/pi-bolt-desktop.js.part" && mv "$CLI_DIR/bin/pi-bolt-desktop.js.part" "$CLI_DIR/bin/pi-bolt-desktop.js" &&
		cp "$PKGDIR/package.json" "$CLI_DIR/package.json"; }; then
		fail "could not write the command into $(tilde "$CLI_DIR")."
	fi
	runtime=$(cli_runtime)
	printf '%s\n' "$runtime" >"$CLI_DIR/runtime"
	cat >"$CLI_BIN.part" <<'PIBOLT_CLI'
#!/bin/sh
# pi-bolt-desktop: runs on the Bun runtime inside the Pi-Bolt agent (bundled in the Mac app; installed by Pi-Bolt's
# installer on Linux), so no Bun install is needed.
d="${XDG_DATA_HOME:-$HOME/.local/share}/pi-bolt-desktop/cli"
js="$d/bin/pi-bolt-desktop.js"
[ -f "$js" ] || { echo "pi-bolt-desktop: $js is missing; reinstall with: curl -fsSL https://pi-bolt.opensec.in/install-desktop.sh | sh -s -- --cli" >&2; exit 1; }
for r in "${PIBOLT_DESKTOP_RUNTIME:-}" "$(cat "$d/runtime" 2>/dev/null)" /Applications/Pi-Bolt.app/Contents/Resources/resources/agent/pi "$HOME/Applications/Pi-Bolt.app/Contents/Resources/resources/agent/pi" "$HOME/.pi-bolt/pi-bolt-linux-x64/pi" "$HOME/.pi-bolt/pi-bolt-linux-x64-baseline/pi" "$(command -v pi-bolt 2>/dev/null)"; do
	[ -n "$r" ] && [ -x "$r" ] && BUN_BE_BUN=1 exec "$r" "$js" "$@"
done
command -v bun >/dev/null 2>&1 && exec bun "$js" "$@"
echo "pi-bolt-desktop: no Pi-Bolt runtime found (the Mac app carries one; on Linux it is the Pi-Bolt agent). Install with:" >&2
echo "  curl -fsSL https://pi-bolt.opensec.in/install-desktop.sh | sh" >&2
exit 1
PIBOLT_CLI
	if ! { chmod 755 "$CLI_BIN.part" && mv "$CLI_BIN.part" "$CLI_BIN"; }; then fail "could not write $(tilde "$CLI_BIN")."; fi
	# An older install through Bun (bun add -g) would shadow the launcher on PATH: remove it.
	if [ -f "$BUN_HOME/install/global/node_modules/$PKG/package.json" ] && [ -x "$BUN_HOME/bin/bun" ]; then
		BUN_INSTALL="$BUN_HOME" "$BUN_HOME/bin/bun" remove -g "$PKG" >/dev/null 2>&1 || true
	fi
	rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/pi-bolt-desktop/$PKG_BASE"-*.tgz 2>/dev/null || true
	if [ "$installed_before" = 1 ]; then word=Updated; else word=Installed; fi
	if [ -n "$runtime" ] && [ -x "$runtime" ]; then how="runs on the Pi-Bolt agent's runtime"; else how="runs once the Pi-Bolt agent is installed"; fi
	printf '%s the pi-bolt-desktop command %s(%s, %s)%s.\n' "$word" "$dim" "$(tilde "$CLI_BIN")" "$how" "$reset"
	case ":$PATH:" in
	*":$(dirname "$CLI_BIN"):"*) ;;
	*) printf '%sAdd %s to your PATH to run it by name.%s\n' "$dim" "$(tilde "$(dirname "$CLI_BIN")")" "$reset" ;;
	esac
}

remove_cli() {
	if [ -f "$CLI_BIN" ] && grep -q 'pi-bolt-desktop: runs on the Bun runtime' "$CLI_BIN" 2>/dev/null; then
		rm -f "$CLI_BIN"
		REMOVED=1
	fi
	if [ -d "$CLI_DIR" ]; then
		rm -rf "$CLI_DIR"
		REMOVED=1
	fi
	if [ -f "$BUN_HOME/install/global/node_modules/$PKG/package.json" ] && [ -x "$BUN_HOME/bin/bun" ]; then
		BUN_INSTALL="$BUN_HOME" "$BUN_HOME/bin/bun" remove -g "$PKG" >/dev/null 2>&1 || true
		REMOVED=1
	fi
	[ "$REMOVED" != 1 ] || printf '  %s%s%s removed the pi-bolt-desktop command\n' "$green" "$CHECK" "$reset"
}

# --- Uninstall --------------------------------------------------------------------------------------------------------

uninstall() {
	REMOVED=0
	if [ "$OS" = Linux ]; then
		uninstall_linux
		return
	fi
	apps=""
	[ -z "$EXISTING" ] || apps="$EXISTING
$OTHERS"
	old_ifs=$IFS
	IFS='
'
	for app in $apps; do
		id=$(plist "$app" CFBundleIdentifier)
		[ "$id" = "$BUNDLE_ID" ] || fail "$(tilde "$app") is another app (${id:-no bundle id}); not removing it."
		if is_running "$app"; then
			printf '%serror:%s Pi-Bolt is running from %s. Quit it (Cmd-Q), then run this again.\n' "$red" "$reset" "$(tilde "$app")" >&2
			exit 3
		fi
	done
	for app in $apps; do
		rm -rf "$app" || fail "could not remove $(tilde "$app")."
		printf '  %s%s%s removed %s\n' "$green" "$CHECK" "$reset" "$(tilde "$app")"
		REMOVED=1
	done
	IFS=$old_ifs
	remove_cli
	lib="$HOME/Library"
	if [ -n "$PURGE" ]; then
		for p in "$lib/Application Support/$BUNDLE_ID" "$lib/Caches/$BUNDLE_ID" "$lib/WebKit/$BUNDLE_ID" \
			"$lib/HTTPStorages/$BUNDLE_ID" "$lib/HTTPStorages/$BUNDLE_ID.binarycookies" "$lib/Logs/$BUNDLE_ID" \
			"$lib/Saved Application State/$BUNDLE_ID.savedState" "$lib/Preferences/$BUNDLE_ID.plist"; do
			[ -e "$p" ] || continue
			rm -rf "$p" && printf '  %s%s%s removed %s\n' "$green" "$CHECK" "$reset" "$(tilde "$p")"
			REMOVED=1
		done
	elif [ -d "$lib/Application Support/$BUNDLE_ID" ]; then
		printf '  %sKept your settings and sessions in ~/Library (run with --uninstall --purge to remove them).%s\n' "$dim" "$reset"
	fi
	if [ "$REMOVED" = 1 ]; then
		printf '\nPi-Bolt Desktop was uninstalled.\n'
	else
		where=$(search_dirs | while IFS= read -r d; do tilde "$d"; echo; done | awk 'NR > 1 { printf " or " } { printf "%s", $0 }')
		printf 'Pi-Bolt Desktop is not installed (not in %s). Nothing to remove.\n' "$where"
	fi
}

# --- Questions --------------------------------------------------------------------------------------------------------

has_tty() { [ "$YES" != 1 ] && (: <>/dev/tty) 2>/dev/null; }

read_key() {
	old=$(stty -g </dev/tty 2>/dev/null || true)
	stty -icanon -echo min 1 time 0 </dev/tty 2>/dev/null || true
	key=$(dd bs=1 count=1 2>/dev/null </dev/tty || true)
	[ -n "$old" ] && stty "$old" </dev/tty 2>/dev/null
	printf '%s' "$key"
}

# ask QUESTION DEFAULT(y|n): reads the answer from the terminal (stdin is the script under curl | sh).
ask() {
	if [ "$2" = y ]; then hint="[Y/n]"; else hint="[y/N]"; fi
	printf '\n%s %s ' "$1" "$hint"
	answer=$(head -n 1 </dev/tty || true)
	case "$answer" in
	y | Y | yes | YES) return 0 ;;
	n | N | no | NO) return 1 ;;
	*) [ "$2" = y ] ;;
	esac
}

choose_action() {
	cmp=1
	[ -z "$EXISTING" ] || cmp=$(version_cmp "$SHOWN_VERSION" "${INSTALLED_VERSION:-0}")
	# y: what installing does here; the default is what happens without a question.
	if [ -z "$EXISTING" ]; then yes_action=install
	elif [ "$cmp" -gt 0 ]; then yes_action=update
	elif [ "$cmp" -eq 0 ] || [ -n "$FORCE" ]; then yes_action=reinstall
	else yes_action=""; fi
	if [ -z "$EXISTING" ] || [ "$cmp" -gt 0 ] || [ -n "$FORCE" ]; then default=$yes_action; else default=none; fi

	if [ -n "$EXISTING" ]; then
		if [ "$cmp" -eq 0 ]; then
			printf '%sPi-Bolt Desktop %s is installed and up to date at:%s\n\n  %s\n\n' "$bold" "$INSTALLED_VERSION" "$reset" "$(tilde "$EXISTING")"
		else
			printf '%sPi-Bolt Desktop %s is installed at:%s\n\n  %s\n\n' "$bold" "${INSTALLED_VERSION:-(unknown version)}" "$reset" "$(tilde "$EXISTING")"
		fi
	fi
	if [ -n "$LOCAL_TGZ" ]; then source="$(tilde "$LOCAL_TGZ")"; elif [ "$AUTH" = 1 ]; then source="$REGISTRY (private, token from $TOKEN_FROM)"; else source="$REGISTRY"; fi
	printf '%sInstallation:%s\n\n' "$bold" "$reset"
	printf '  %sPi-Bolt Desktop %s%s, for %s\n' "$amber" "$SHOWN_VERSION" "$reset" "$PLATFORM_LABEL"
	printf '  %sinstalls to%s  %s\n' "$dim" "$reset" "$(install_target)"
	printf '  %sfrom%s         %s\n\n' "$dim" "$reset" "$source"

	if has_tty; then
		printf '%sChoose an action:%s\n\n' "$bold" "$reset"
		case "$yes_action" in
		install) label="Install Pi-Bolt Desktop" ;;
		update) label="Update to Pi-Bolt Desktop $SHOWN_VERSION" ;;
		reinstall) if [ "$cmp" -lt 0 ]; then label="Replace it with Pi-Bolt Desktop $SHOWN_VERSION (older)"; else label="Reinstall Pi-Bolt Desktop"; fi ;;
		esac
		if [ -n "$yes_action" ]; then
			mark=""
			[ "$default" != "$yes_action" ] || mark=" ${dim}(default)$reset"
			printf '  %s%-4s%s %s%s%s%s\n' "$cyan" y "$reset" "$green" "$label" "$reset" "$mark"
		fi
		[ -z "$EXISTING" ] || printf '  %s%-4s%s %sUninstall Pi-Bolt Desktop%s\n' "$cyan" u "$reset" "$red" "$reset"
		mark=""
		[ "$default" != none ] || mark=" ${dim}(default)$reset"
		printf '  %s%-4s%s %sDo nothing%s%s\n' "$cyan" n "$reset" "$dim" "$reset" "$mark"
		while :; do
			key=$(read_key)
			case "$key" in
			"" | " " | "$CR") ACTION=$default; break ;;
			y | Y) if [ -n "$yes_action" ]; then ACTION=$yes_action; break; fi ;;
			u | U) if [ -n "$EXISTING" ]; then ACTION=uninstall; break; fi ;;
			n | N | "$ESC") ACTION=none; break ;;
			"$ETX") exit 130 ;;
			esac
			printf 'Please choose one of the listed keys.\n'
		done
		printf '\n'
	else
		ACTION=$default
	fi
	case "$ACTION" in
	install) printf 'Will install Pi-Bolt Desktop.\n\n' ;;
	update) printf 'Will update Pi-Bolt Desktop.\n\n' ;;
	reinstall) printf 'Will reinstall Pi-Bolt Desktop.\n\n' ;;
	uninstall) printf 'Will uninstall Pi-Bolt Desktop.\n\n' ;;
	none)
		if [ "$cmp" -eq 0 ]; then
			printf 'Pi-Bolt Desktop %s is up to date. %s(--force reinstalls it.)%s\n' "$INSTALLED_VERSION" "$dim" "$reset"
		elif [ "$cmp" -lt 0 ] && [ -z "$FORCE" ]; then
			printf 'Pi-Bolt Desktop %s is newer than %s: kept it. %s(--force replaces it.)%s\n' "$INSTALLED_VERSION" "$SHOWN_VERSION" "$dim" "$reset"
		else
			printf 'Chose to do nothing. Exiting.\n'
		fi
		;;
	esac
}

offer_open() {
	if [ "$OPEN" = ask ]; then
		if [ -t 1 ] && has_tty && has_display && ask "Open Pi-Bolt now?" y; then OPEN=yes; else OPEN=no; fi
	fi
	[ "$OPEN" != yes ] || open_app "$DEST"
}

main "$@"
