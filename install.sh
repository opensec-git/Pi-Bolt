#!/bin/sh
# Pi-Bolt installer.
#
#   curl -fsSL https://pi-bolt.opensec.in/install.sh | sh
#
# Downloads a release from GitHub, verifies its SHA-256 checksum, installs it to ~/.pi-bolt and links `pi-bolt` into
# ~/.local/bin. Run it again to reinstall, update or uninstall.
#
# Environment:
#   PIBOLT_VERSION   a release tag such as bolt-v0.2.0 (default: the latest release)
#   PIBOLT_VARIANT   x64, x64-baseline or x64-jit (default: x64 on CPUs with AVX2, x64-baseline otherwise)
#   PIBOLT_INSTALL   where to install (default: ~/.pi-bolt)
#   PIBOLT_BIN_DIR   where to link the `pi-bolt` command (default: ~/.local/bin)
#   PIBOLT_YES=1     do not ask: take the default action and do not offer to start Pi-Bolt
#   PIBOLT_LAUNCHER=1  used by the npm package's first run: download only, no menu, link or prompts

ESC=$(printf '\033')
CR=$(printf '\r')
ETX=$(printf '\003')
REPO="https://github.com/opensec-git/Pi-Bolt"

main() {
	set -eu
	VERSION="${PIBOLT_VERSION:-latest}"
	INSTALL="${PIBOLT_INSTALL:-$HOME/.pi-bolt}"
	BIN_DIR="${PIBOLT_BIN_DIR:-$HOME/.local/bin}"
	LAUNCHER="${PIBOLT_LAUNCHER:-0}"
	setup_style

	check_file="$(mktemp)"
	preflight >"$check_file" 2>&1 &
	check_pid=$!
	version_file="$(mktemp)"
	resolve_version >"$version_file" 2>/dev/null &
	version_pid=$!
	logo_animation
	if wait "$check_pid"; then check_status=0; else check_status=$?; fi

	if [ "$LAUNCHER" = 1 ]; then
		printf '%s  Pi-Bolt%s\n%s  First run: getting the native executable%s\n\n' "$bold" "$reset" "$dim" "$reset"
	else
		printf '%s  Pi-Bolt Installer%s\n%s  Pi, compiled ahead of time. Native speed, no JIT.%s\n\n' "$bold" "$reset" "$dim" "$reset"
	fi
	cat "$check_file"
	rm -f "$check_file"
	[ "$check_status" -eq 0 ] || exit "$check_status"
	wait "$version_pid" 2>/dev/null || true
	SHOWN_VERSION=$(cat "$version_file")
	rm -f "$version_file"
	[ -n "$SHOWN_VERSION" ] || SHOWN_VERSION="$VERSION"

	VARIANT="${PIBOLT_VARIANT:-}"
	if [ -z "$VARIANT" ]; then
		if grep -qw avx2 /proc/cpuinfo 2>/dev/null; then VARIANT=x64; else VARIANT=x64-baseline; fi
	fi
	case "$VARIANT" in
	x64 | x64-baseline | x64-jit) ;;
	*) fail "PIBOLT_VARIANT must be x64, x64-baseline or x64-jit" ;;
	esac
	NAME="pi-bolt-linux-$VARIANT"
	if [ "$VERSION" = latest ]; then BASE="$REPO/releases/latest/download"; else BASE="$REPO/releases/download/$VERSION"; fi
	BASE="${PIBOLT_DOWNLOAD_BASE:-$BASE}"

	if [ "$LAUNCHER" = 1 ]; then
		install_release
		return 0
	fi

	EXISTING=""
	if [ -x "$INSTALL/$NAME/pi" ]; then EXISTING="$INSTALL/$NAME"; fi
	choose_action
	case "$ACTION" in
	none) exit 0 ;;
	uninstall)
		uninstall
		printf '\nPi-Bolt was uninstalled.\n'
		exit 0
		;;
	esac

	install_release
	mkdir -p "$BIN_DIR"
	ln -sfn "$INSTALL/$NAME/pi" "$BIN_DIR/pi-bolt"
	if [ "$ACTION" = reinstall ]; then word=reinstalled; else word=installed; fi
	printf '\nPi-Bolt %s was %s successfully %s(Pi %s)%s.\n' "$SHOWN_VERSION" "$word" "$dim" "$("$INSTALL/$NAME/pi" --version)" "$reset"
	if [ "$(command -v pi-bolt 2>/dev/null || true)" = "$BIN_DIR/pi-bolt" ]; then
		printf '\nRun it with: %spi-bolt%s\n' "$bold" "$reset"
	else
		path_hint
	fi
	offer_start
}

# --- Look -------------------------------------------------------------------------------------------------------------

setup_style() {
	reset="" dim="" bold="" cyan="" green="" red=""
	amber="" amber2="" blue="" blue2="" white=""
	FULL="#" EMPTY="-" FRAMES=4 CHECK="ok"
	if [ -t 1 ] && [ "${TERM:-}" != dumb ]; then
		reset="${ESC}[0m" dim="${ESC}[2m" bold="${ESC}[1m" cyan="${ESC}[36m" green="${ESC}[32m" red="${ESC}[31m"
		amber="${ESC}[38;2;247;192;74m" amber2="${ESC}[38;2;233;164;44m"
		blue="${ESC}[38;2;76;150;234m" blue2="${ESC}[38;2;42;120;214m" white="${ESC}[38;2;255;255;255m"
		if unicode_terminal; then FULL="█" EMPTY="░" FRAMES=10 CHECK="✓"; fi
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

# The bolt, on a grid of 8 x 9 cells (each cell two characters wide). Amber is the upper stroke and the left half of the
# step, blue the right half and the lower stroke; the last cell of each row is a shade darker, like the logo's facets.
UPPER="0,6 0,7 1,5 1,6 2,4 2,5 3,3 3,4 4,2 4,3 4,4"
LOWER="4,5 4,6 4,7 5,5 5,6 6,4 6,5 7,3 7,4 8,3"
UPPER_DARK="0,7 1,6 2,5 3,4"
LOWER_DARK="4,7 5,6 6,5 7,4"

has_cell() { # has_cell Y X "cells" [DY]: is (Y - DY, X) one of the cells
	want="$(($1 - ${4:-0})),$2"
	for c in $3; do [ "$c" = "$want" ] && return 0; done
	return 1
}

# draw_bolt UPPER_DY LOWER_DY MODE: MODE is color, white or none (nothing of that piece).
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
	trap - INT TERM
}

spinner() {
	if [ "$FRAMES" -eq 10 ]; then
		case $(($1 % 10)) in
		0) printf '⠋' ;; 1) printf '⠙' ;; 2) printf '⠹' ;; 3) printf '⠸' ;; 4) printf '⠼' ;;
		5) printf '⠴' ;; 6) printf '⠦' ;; 7) printf '⠧' ;; 8) printf '⠇' ;; *) printf '⠏' ;;
		esac
	else
		case $(($1 % 4)) in 0) printf '-' ;; 1) printf '\\' ;; 2) printf '|' ;; *) printf '/' ;; esac
	fi
}

# draw_progress STEP PERCENT LABEL: PERCENT is 0-100, or -1 when the total is not known (a moving comet instead).
draw_progress() {
	width=28
	bar=""
	i=0
	if [ "$2" -ge 0 ]; then
		filled=$(($2 * width / 100))
		while [ "$i" -lt "$width" ]; do
			if [ "$i" -lt "$filled" ]; then
				if [ "$i" -lt $((width / 2)) ]; then bar="$bar$amber$FULL"; else bar="$bar$blue$FULL"; fi
			else
				bar="$bar$dim$EMPTY"
			fi
			bar="$bar$reset"
			i=$((i + 1))
		done
	else
		head=$(($1 % (width + 6)))
		while [ "$i" -lt "$width" ]; do
			age=$((head - i))
			if [ "$age" -ge 0 ] && [ "$age" -lt 2 ]; then bar="$bar$white$FULL"
			elif [ "$age" -ge 2 ] && [ "$age" -lt 4 ]; then bar="$bar$amber$FULL"
			elif [ "$age" -ge 4 ] && [ "$age" -lt 6 ]; then bar="$bar$blue$FULL"
			else bar="$bar$dim$EMPTY"; fi
			bar="$bar$reset"
			i=$((i + 1))
		done
	fi
	printf '\r%s[K  %s%s%s %s %sInstalling Pi-Bolt%s %s' "$ESC" "$amber" "$(spinner "$1")" "$reset" "$bar" "$bold" "$reset" "$3"
}

finish_progress() { printf '\r%s[K' "$ESC"; [ -t 1 ] && printf '%s[?25h' "$ESC"; return 0; }

# shellcheck disable=SC2088 # a literal ~ for display
tilde() { case "$1" in "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;; *) printf '%s' "$1" ;; esac; }

# The release tag the latest download points to, as a version: bolt-v0.2.0 -> 0.2.0.
resolve_version() {
	if [ "$VERSION" != latest ]; then printf '%s' "${VERSION#bolt-v}"; return; fi
	command -v curl >/dev/null 2>&1 || return 0
	curl -fsSIL -o /dev/null -w '%{url_effective}' "$REPO/releases/latest" | sed -n 's#.*/tag/bolt-v##p'
}

mb() { awk -v b="$1" 'BEGIN { printf "%.1f", b / 1048576 }'; }

# --- Steps ------------------------------------------------------------------------------------------------------------

fail() {
	finish_progress
	printf '%serror:%s %s\n' "$red" "$reset" "$*" >&2
	exit 1
}

preflight() {
	status=0
	[ "$(uname -s)" = Linux ] || { printf 'error: Pi-Bolt runs on Linux only (this is %s).\n' "$(uname -s)"; status=1; }
	[ "$(uname -m)" = x86_64 ] || { printf 'error: Pi-Bolt runs on x86-64 only (this is %s).\n' "$(uname -m)"; status=1; }
	if ldd --version 2>&1 | grep -qi musl; then
		printf 'error: Pi-Bolt needs glibc; musl-based systems such as Alpine are not supported.\n'
		status=1
	fi
	for tool in tar sha256sum; do
		command -v "$tool" >/dev/null 2>&1 || { printf 'error: %s is required.\n' "$tool"; status=1; }
	done
	if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
		printf 'error: curl or wget is required.\n'
		status=1
	fi
	[ "$status" -eq 0 ] || printf '\n'
	return "$status"
}

fetch() { # fetch FILE URL
	if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$1" "$2"; else wget -q -O "$1" "$2"; fi
}

remote_size() {
	if command -v curl >/dev/null 2>&1; then
		curl -fsSIL "$1" 2>/dev/null | tr -d '\r' | awk 'tolower($1) == "content-length:" { n = $2 } END { print n + 0 }'
	else
		wget -q --spider -S "$1" 2>&1 | tr -d '\r' | awk 'tolower($1) == "content-length:" { n = $2 } END { print n + 0 }'
	fi
}

install_release() {
	TMP="$(mktemp -d)"
	trap 'rm -rf "$TMP"; finish_progress; exit 130' INT TERM
	[ -t 1 ] && printf '%s[?25l' "$ESC"
	url="$BASE/$NAME.tar.gz"
	total=$(remote_size "$url")
	if [ ! -t 1 ]; then
		# Not a terminal (a log, CI): plain lines instead of an animated bar.
		draw_progress() { printf '%s\n' "$3" | sed "s/${ESC}\\[[0-9;]*m//g"; }
		finish_progress() { :; }
		printf 'downloading %s (%s MB)\n' "$url" "$(mb "${total:-0}")"
	fi
	fetch "$TMP/$NAME.tar.gz" "$url" 2>"$TMP/fetch.log" &
	fetch_pid=$!
	step=0
	while [ -t 1 ] && kill -0 "$fetch_pid" 2>/dev/null; do
		got=0
		if [ -f "$TMP/$NAME.tar.gz" ]; then got=$(wc -c <"$TMP/$NAME.tar.gz"); fi
		if [ "$total" -gt 0 ]; then
			pct=$((got * 100 / total))
			[ "$pct" -le 100 ] || pct=100
			draw_progress "$step" "$pct" "${dim}downloading $(mb "$got") / $(mb "$total") MB$reset"
		else
			draw_progress "$step" -1 "${dim}downloading $(mb "$got") MB$reset"
		fi
		step=$((step + 1))
		sleep 0.08
	done
	wait "$fetch_pid" || fail "download failed: $url"
	draw_progress "$step" 100 "${dim}verifying checksum$reset"
	fetch "$TMP/SHA256SUMS" "$BASE/SHA256SUMS" || fail "download failed: $BASE/SHA256SUMS"
	(cd "$TMP" && grep " $NAME.tar.gz\$" SHA256SUMS | sha256sum -c --quiet - >/dev/null 2>&1) ||
		fail "checksum mismatch: the download is corrupt or incomplete"
	draw_progress "$((step + 1))" 100 "${dim}extracting$reset"
	tar -C "$TMP" -xzf "$TMP/$NAME.tar.gz" || fail "could not extract $NAME.tar.gz"
	draw_progress "$((step + 2))" 100 "${dim}checking the executable$reset"
	"$TMP/$NAME/pi" --version >/dev/null 2>&1 || fail "the downloaded executable does not run on this system"
	mkdir -p "$INSTALL"
	rm -rf "$INSTALL/$NAME.old"
	if [ -d "$INSTALL/$NAME" ]; then mv "$INSTALL/$NAME" "$INSTALL/$NAME.old"; fi
	mv "$TMP/$NAME" "$INSTALL/$NAME"
	rm -rf "$INSTALL/$NAME.old" "$TMP"
	trap - INT TERM
	finish_progress
	printf '  %s%s%s install complete %s(%s, %s MB)%s\n' "$green" "$CHECK" "$reset" "$dim" "linux-$VARIANT" "$(mb "${total:-0}")" "$reset"
}

uninstall() {
	for dir in "$INSTALL"/pi-bolt-linux-*; do
		[ -d "$dir" ] && rm -rf "$dir"
	done
	target=$(readlink "$BIN_DIR/pi-bolt" 2>/dev/null || true)
	case "$target" in
	"$INSTALL"/*) rm -f "$BIN_DIR/pi-bolt" ;;
	esac
	rmdir "$INSTALL" 2>/dev/null || true
}

# --- Questions --------------------------------------------------------------------------------------------------------

has_tty() { [ "${PIBOLT_YES:-0}" != 1 ] && (: <>/dev/tty) 2>/dev/null; }

read_key() {
	old=$(stty -g </dev/tty 2>/dev/null || true)
	stty -icanon -echo min 1 time 0 </dev/tty 2>/dev/null || true
	key=$(dd bs=1 count=1 2>/dev/null </dev/tty || true)
	[ -n "$old" ] && stty "$old" </dev/tty 2>/dev/null
	printf '%s' "$key"
}

choose_action() {
	if [ -n "$EXISTING" ]; then default=reinstall; else default=install; fi
	if [ -n "$EXISTING" ]; then
		printf '%sPi-Bolt is already installed at:%s\n\n  %s\n\n' "$bold" "$reset" "$(tilde "$EXISTING")"
	fi
	case "$VARIANT" in
	x64) cpu="for CPUs with AVX2" ;;
	x64-baseline) cpu="for any x86-64 CPU" ;;
	*) cpu="with the JIT on" ;;
	esac
	printf '%sInstallation:%s\n\n' "$bold" "$reset"
	printf '  %sPi-Bolt %s%s, linux-%s build %s\n' "$amber" "$SHOWN_VERSION" "$reset" "$VARIANT" "$cpu"
	printf '  %sinstalls to%s  %s\n' "$dim" "$reset" "$(tilde "$INSTALL/$NAME")"
	printf '  %scommand%s      %s\n\n' "$dim" "$reset" "$(tilde "$BIN_DIR/pi-bolt")"
	printf '%sChoose an action:%s\n\n' "$bold" "$reset"
	if [ -n "$EXISTING" ]; then
		printf '  %s%-4s%s %sReinstall Pi-Bolt%s %s(default)%s\n' "$cyan" y "$reset" "$green" "$reset" "$dim" "$reset"
		printf '  %s%-4s%s %sUninstall Pi-Bolt%s\n' "$cyan" u "$reset" "$red" "$reset"
	else
		printf '  %s%-4s%s %sInstall Pi-Bolt%s %s(default)%s\n' "$cyan" y "$reset" "$green" "$reset" "$dim" "$reset"
	fi
	printf '  %s%-4s%s %sDo nothing%s\n' "$cyan" n "$reset" "$dim" "$reset"
	if ! has_tty; then
		ACTION=$default
	else
		while :; do
			key=$(read_key)
			case "$key" in
			"" | " " | "$CR" | y | Y) ACTION=$default; break ;;
			u | U) if [ -n "$EXISTING" ]; then ACTION=uninstall; break; fi ;;
			n | N | "$ESC") ACTION=none; break ;;
			"$ETX") exit 130 ;;
			esac
			printf 'Please choose one of the listed keys.\n'
		done
	fi
	case "$ACTION" in
	install) printf '\nWill install Pi-Bolt.\n\n' ;;
	reinstall) printf '\nWill reinstall Pi-Bolt.\n\n' ;;
	uninstall) printf '\nWill uninstall Pi-Bolt.\n' ;;
	none) printf '\nChose to do nothing. Exiting.\n' ;;
	esac
}

shell_config_file() {
	case "$(basename "${SHELL:-sh}")" in
	zsh) printf '%s/.zshrc' "${ZDOTDIR:-$HOME}" ;;
	fish) printf '%s/.config/fish/config.fish' "$HOME" ;;
	bash) if [ -f "$HOME/.bashrc" ]; then printf '%s/.bashrc' "$HOME"; else printf '%s/.profile' "$HOME"; fi ;;
	*) printf '%s/.profile' "$HOME" ;;
	esac
}

path_hint() {
	if [ "$BIN_DIR" = "$HOME/.local/bin" ]; then expr="\$HOME/.local/bin"; else expr="$BIN_DIR"; fi
	if [ "$(basename "${SHELL:-sh}")" = fish ]; then line="fish_add_path \"$expr\""; else line="export PATH=\"$expr:\$PATH\""; fi
	config=$(shell_config_file)
	printf '\n%s is not on your PATH yet.\n' "$(tilde "$BIN_DIR")"
	if [ -f "$config" ] && grep -Fxq "$line" "$config"; then
		printf 'Your %s already adds it: restart your shell, then run pi-bolt.\n' "$(tilde "$config")"
		return 0
	fi
	if has_tty; then
		printf 'Add it to your PATH in %s now? [Y/n] ' "$(tilde "$config")"
		answer=$(head -n 1 </dev/tty || true)
		case "$answer" in
		n | N | no | NO) ;;
		*)
			mkdir -p "$(dirname "$config")"
			printf '\n# Pi-Bolt\n%s\n' "$line" >>"$config"
			printf 'Added it to %s. Restart your shell, or run:\n\n  %s\n' "$(tilde "$config")" "$line"
			return 0
			;;
		esac
	fi
	printf 'Add this to %s, then restart your shell:\n\n  %s\n' "$(tilde "$config")" "$line"
}

offer_start() {
	[ -t 1 ] && has_tty && [ "${PIBOLT_NO_START:-0}" != 1 ] || return 0
	printf '\nStart pi-bolt now? [Y/n] '
	answer=$(head -n 1 </dev/tty || true)
	case "$answer" in
	n | N | no | NO) return 0 ;;
	esac
	printf '\n'
	exec "$INSTALL/$NAME/pi" </dev/tty
}

main "$@"
