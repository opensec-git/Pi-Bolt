#!/usr/bin/env bash
# Records a training profile for a Pi version: which functions run and in which order (the executable lays them out together,
# so starting touches fewer pages), and the regular expressions Pi builds from strings at run time (compiled into the image like
# literals). A profile belongs to one Pi version and one entry point; scripts/build-pi.sh picks profiles/pi-<version> up.
# Usage: scripts/train-profile.sh [--pi DIR] [--plugins FILE] [--out DIR]
#   --plugins FILE    train the build that has these extensions compiled in (scripts/build-pi.sh --plugins): give the profile its
#                     own --out, and pass it to build-pi.sh with --profile
source "$(dirname "$0")/lib/common.sh"
PI_DIR="$PIBOLT_PI"; PLUGINS=""; OUT=""
while [ $# -gt 0 ]; do
	case "$1" in
	--pi) PI_DIR="$2"; shift ;;
	--plugins) PLUGINS="$2"; shift ;;
	--out) OUT="$2"; shift ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
BUN="$(runtime_bun)"
AGENT="$(pi_agent_dir "$PI_DIR")"
VERSION="$(pi_version "$AGENT")"
OUT="$(abspath "${OUT:-$PIBOLT_ROOT/profiles/pi-$VERSION}")"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP" "$AGENT/.pibolt-plugins"' EXIT
ENTRY=""
[ -n "$PLUGINS" ] && ENTRY="$(stage_plugins "$AGENT" "$PLUGINS")"
mapfile -t ENTRIES < <(pi_entries "$AGENT" "$ENTRY")

# A function is named by its text, and splitting changes the text of what reaches into another chunk: a profile recorded on one
# kind of build names about a fifth of the functions the other kind runs first by names it does not have. So the sessions run on
# both, built as the release builds them: split (scripts/build-pi.sh, macOS and Linux) and in one file (scripts/build-pi.ps1).
BUILD_ARGS=(build --compile --no-compile-autoload-bunfig --no-compile-autoload-dotenv --compile-autoload-package-json
	--target="bun-$PIBOLT_PLATFORM" --bytecode --format=esm --compile-exec-argv=--smol)
for kind in split whole; do
	log "a bytecode build of Pi $VERSION to train with ($kind)"
	mkdir -p "$TMP/$kind"
	args=("${BUILD_ARGS[@]}")
	[ "$kind" = split ] && args+=(--splitting)
	(cd "$AGENT" && "$BUN" "${args[@]}" "${ENTRIES[@]}" --outfile "$TMP/$kind/pi" >/dev/null)
	# (What the session opens that reads files from beside the executable: the themes the settings list.)
	mkdir -p "$TMP/$kind/theme" && cp "$AGENT"/src/modes/interactive/theme/*.json "$TMP/$kind/theme/"
	log "training sessions ($kind)"
	python3 "$PIBOLT_ROOT/scripts/lib/train_session.py" "$TMP/$kind/pi" "$TMP/$kind/bytecode.order" "$TMP/$kind/regexps.txt"
done
mkdir -p "$OUT"
python3 "$PIBOLT_ROOT/scripts/lib/merge_orders.py" "$TMP/split/bytecode.order" "$TMP/whole/bytecode.order" >"$OUT/bytecode.order"
sort -u "$TMP/split/regexps.txt" "$TMP/whole/regexps.txt" >"$OUT/regexps.txt"
log "profile: $OUT ($(wc -l < "$OUT/regexps.txt") regular expressions)"
