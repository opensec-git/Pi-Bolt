#!/bin/sh
# Pi-Bolt Desktop installer (macOS, Apple silicon).
#
#   curl -fsSL https://pi-bolt.opensec.in/install-desktop.sh | sh
#
#
# Downloads Pi-Bolt Desktop straight from the npm registry with curl (the registry as a CDN, fast in most places: no npm, Node
# or Bun needed for the app), checks it against the registry's sha512 integrity and the app against its SHA-256 in the
# package, and installs Pi-Bolt.app into /Applications (or ~/Applications). Run it again to update or uninstall. The optional
# pi-bolt-desktop command (--cli) is a Bun script, installed with `bun add -g` from the same download.
#
# The app is the private npm package @kushalkhemka/pi-bolt-desktop, so the registry wants a token that can read it: the
# installer takes PIBOLT_DESKTOP_NPM_TOKEN, NPM_TOKEN, or a token in ~/.bunfig.toml or ~/.npmrc. It sends the token to
# the registry only, in a header read from a file (never in a URL or on a command line), and never prints it. If the package
# can be read without a token (published publicly), none is needed.
#
# Options:
#   --cli         also install the pi-bolt-desktop command with Bun (asked when interactive; offers to install Bun)
#   --no-cli      do not install or update the command
#   --user        use ~/Applications instead of /Applications
#   --force       reinstall the same version, or replace a newer one
#   --open        open Pi-Bolt when done (asked when interactive)
#   --uninstall   remove the app and the command; --purge also removes the app's data in ~/Library
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
#   PIBOLT_DESKTOP_DIR           install to and look for the app only in this folder
#   BUN_INSTALL                  where Bun is (default: ~/.bun); --cli installs the command into its global packages

PKG="@kushalkhemka/pi-bolt-desktop"
PKG_SCOPE="@kushalkhemka"
PKG_PATH="@kushalkhemka%2fpi-bolt-desktop"
PKG_BASE="pi-bolt-desktop"
BUNDLE_ID="in.opensec.pibolt.desktop"
APP_NAME="Pi-Bolt.app"
AGENT_INSTALLER="https://pi-bolt.opensec.in/install.sh"
ACCEPT_PACKUMENT="application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8, */*"

ESC=$(printf '\033')
CR=$(printf '\r')
ETX=$(printf '\003')

main() {
	set -eu
	CLI=ask OPEN=ask UNINSTALL="" USER_FLAG="" FORCE="" PURGE=""
	YES="${PIBOLT_DESKTOP_YES:-0}"
	VERSION="${PIBOLT_DESKTOP_VERSION:-latest}"
	LOCAL_TGZ="${PIBOLT_DESKTOP_TGZ:-}"
	while [ $# -gt 0 ]; do
		case "$1" in
		--cli) CLI=yes ;;
		--no-cli) CLI=no ;;
		--user) USER_FLAG=1 ;;
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
	printf '%s  Pi-Bolt Desktop Installer%s\n%s  The Pi-Bolt agent as a native Mac app. Your sessions, plugins and settings, unchanged.%s\n\n' "$bold" "$reset" "$dim" "$reset"
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
	install_cli
	if ! agent_found; then
		printf '\nPi-Bolt Desktop runs the Pi-Bolt agent, which was not found. Install it with:\n\n  curl -fsSL %s | sh\n' "$AGENT_INSTALLER"
	fi
	offer_open
	printf '\nOpen it from Launchpad or Spotlight. Run this script again to update or uninstall it.\n'
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
Pi-Bolt Desktop installer (macOS, Apple silicon)

Usage: sh install.sh [options]        (or: curl -fsSL <URL> | sh -s -- [options])

  --cli         also install the pi-bolt-desktop command (with Bun: bun add -g)
  --no-cli      do not install or update the command
  --user        use ~/Applications instead of /Applications
  --force       reinstall the same version, or replace a newer one
  --open        open Pi-Bolt when done
  --uninstall   remove the app and the command (--purge: also its data in ~/Library)
  --version V   install version V (default: latest)
  -y, --yes     do not ask questions

Downloads $PKG from the npm registry with curl; the app needs no npm, Node or Bun.
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
	rm -rf "$TMP"
}

# --- Checks -----------------------------------------------------------------------------------------------------------

preflight() {
	status=0
	if [ "$(uname -s)" != Darwin ]; then
		printf 'error: Pi-Bolt Desktop runs on macOS only (this is %s).\n\n' "$(uname -s)"
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

# JSON is read with JavaScript for Automation, which every Mac has (no jq needed).
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
json() { osascript -l JavaScript -e "$JSON_JS" "$@" 2>/dev/null; }

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

# verify_integrity FILE: checks FILE against the registry's dist.integrity (sha512), or its dist.shasum (SHA-1) for a
# package so old that it has no integrity.
verify_integrity() {
	want=""
	for hash in $INTEGRITY; do
		case "$hash" in sha512-*) want=${hash#sha512-} ;; esac
	done
	if [ -n "$want" ]; then
		[ "$(sha512_base64 "$1")" = "$want" ] && return 0
	elif [ -n "$SHASUM" ]; then
		[ "$(shasum -a 1 "$1" | cut -d ' ' -f 1)" = "$SHASUM" ] && return 0
	else
		fail "the registry gives no checksum for $PKG@$SHOWN_VERSION. Nothing was installed."
	fi
	fail "integrity check failed: the download does not match the registry's checksum for $PKG@$SHOWN_VERSION. Nothing was installed."
}

# check_sum FILE: whether FILE (in the current folder) matches its line in SHA256SUMS. No line is a mismatch.
check_sum() {
	line=$(grep " $1\$" SHA256SUMS) && [ -n "$line" ] || return 1
	printf '%s\n' "$line" | shasum -a 256 -c --status - 2>/dev/null
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
	for p in "$HOME"/.pi-bolt/pi-bolt-darwin-*/pi; do [ -f "$p" ] && return 0; done
	return 1
}

# --- The command ------------------------------------------------------------------------------------------------------

# The pi-bolt-desktop command is a Bun script, installed with Bun (bun add -g) from the package just downloaded, so Bun needs no
# access to the registry. Bun's global package.json points at that .tgz, so it is kept here (one version at a time).
CLI_HOME="${XDG_DATA_HOME:-$HOME/.local/share}/pi-bolt-desktop"
BUN_HOME="${BUN_INSTALL:-$HOME/.bun}"
BUN_INSTALLER="https://bun.sh/install"

# find_bun: BUN, the bun executable (on PATH, or where Bun's installer puts it); fails if there is none.
find_bun() {
	BUN=$(command -v bun 2>/dev/null || true)
	[ -n "$BUN" ] || { [ -x "$BUN_HOME/bin/bun" ] && BUN="$BUN_HOME/bin/bun"; }
	[ -n "$BUN" ]
}

# Whether Bun has the command installed globally.
cli_installed() { [ -f "$BUN_HOME/install/global/node_modules/$PKG/package.json" ]; }

# install_bun: Bun's own installer (curl -fsSL https://bun.sh/install | bash), only with --yes or a yes at the prompt.
install_bun() {
	if [ "$YES" = 1 ]; then
		:
	elif has_tty && ask "The pi-bolt-desktop command runs on Bun, which is not installed. Install Bun now (curl -fsSL $BUN_INSTALLER | bash)?" y; then
		:
	else
		printf '\n%sThe pi-bolt-desktop command runs on Bun, which is not installed (the app does not need it). Install Bun with:%s\n\n  curl -fsSL %s | bash\n\n%sthen run this again with --cli.%s\n' "$dim" "$reset" "$BUN_INSTALLER" "$dim" "$reset"
		return 1
	fi
	command -v bash >/dev/null 2>&1 || fail "Bun's installer needs bash."
	printf '\nInstalling Bun (%s)\n' "$BUN_INSTALLER"
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL "$BUN_INSTALLER" | bash >"$TMP/bun.log" 2>&1 </dev/null || fail "Bun's installer failed." "$(tail -n 2 "$TMP/bun.log")"
	else
		wget -qO- "$BUN_INSTALLER" | bash >"$TMP/bun.log" 2>&1 </dev/null || fail "Bun's installer failed." "$(tail -n 2 "$TMP/bun.log")"
	fi
	find_bun || fail "Bun's installer ran, but there is no bun in $(tilde "$BUN_HOME/bin")."
	printf '  %s%s%s installed Bun %s %s(%s)%s\n' "$green" "$CHECK" "$reset" "$("$BUN" --version 2>/dev/null)" "$dim" "$(tilde "$BUN")" "$reset"
}

install_cli() {
	[ "$CLI" != no ] || return 0
	installed_before=0
	if cli_installed; then installed_before=1; fi
	if [ "$CLI" = ask ]; then
		if [ "$installed_before" = 1 ]; then
			CLI=yes
		elif has_tty && ask "Also install the pi-bolt-desktop command (install, update, open, doctor; runs on Bun)?" n; then
			CLI=yes
		else
			return 0
		fi
	fi
	find_bun || install_bun || return 0
	mkdir -p "$CLI_HOME" || fail "cannot create $(tilde "$CLI_HOME")."
	kept="$CLI_HOME/$PKG_BASE-$SHOWN_VERSION.tgz"
	if ! cp "$TGZ" "$kept.part" || ! mv "$kept.part" "$kept"; then fail "could not copy the package into $(tilde "$CLI_HOME")."; fi
	if ! BUN_INSTALL="$BUN_HOME" "$BUN" add -g "$kept" >"$TMP/cli.log" 2>&1; then
		fail "bun add -g $(tilde "$kept") failed." "$(grep -m 2 -i 'error' "$TMP/cli.log" || tail -n 2 "$TMP/cli.log")"
	fi
	for old in "$CLI_HOME/$PKG_BASE"-*.tgz; do
		[ "$old" = "$kept" ] || rm -f "$old"
	done
	if [ "$installed_before" = 1 ]; then word=Updated; else word=Installed; fi
	bin="$BUN_HOME/bin/pi-bolt-desktop"
	printf '%s the pi-bolt-desktop command %s(%s, Bun %s)%s.\n' "$word" "$dim" "$(tilde "$bin")" "$("$BUN" --version 2>/dev/null)" "$reset"
	if [ "$(command -v pi-bolt-desktop 2>/dev/null || true)" != "$bin" ]; then
		printf '%s%s is not on your PATH (Bun'"'"'s installer adds it to your shell'"'"'s config: restart your shell).%s\n' "$dim" "$(tilde "$BUN_HOME/bin")" "$reset"
	fi
}

remove_cli() {
	if cli_installed && find_bun; then
		BUN_INSTALL="$BUN_HOME" "$BUN" remove -g "$PKG" >"$TMP/cli.log" 2>&1 || fail "bun remove -g $PKG failed." "$(tail -n 2 "$TMP/cli.log")"
		printf '  %s%s%s removed the pi-bolt-desktop command\n' "$green" "$CHECK" "$reset"
		REMOVED=1
	fi
	if [ -d "$CLI_HOME" ]; then
		rm -rf "$CLI_HOME"
		REMOVED=1
	fi
}

# --- Uninstall --------------------------------------------------------------------------------------------------------

uninstall() {
	REMOVED=0
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
	printf '  %sPi-Bolt Desktop %s%s, for Apple silicon\n' "$amber" "$SHOWN_VERSION" "$reset"
	printf '  %sinstalls to%s  %s\n' "$dim" "$reset" "$(tilde "$(destination_dir)/$APP_NAME")"
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
		if [ -t 1 ] && has_tty && ask "Open Pi-Bolt now?" y; then OPEN=yes; else OPEN=no; fi
	fi
	[ "$OPEN" != yes ] || open_app "$DEST"
}

main "$@"
