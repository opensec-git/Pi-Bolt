#!/usr/bin/env bash
# Records a training profile for a Pi version: which functions run and in which order (the executable lays them out together,
# so starting touches fewer pages), and the regular expressions Pi builds from strings at run time (compiled into the image like
# literals). A profile belongs to one Pi version and one entry point; scripts/build-pi.sh picks profiles/pi-<version> up.
# Usage: scripts/train-profile.sh [--pi DIR] [--plugins FILE] [--out DIR] [--with DIR]
#   --plugins FILE    train the build that has these extensions compiled in (scripts/build-pi.sh --plugins): give the profile its
#                     own --out, and pass it to build-pi.sh with --profile
#   --with DIR        merge in a profile this script recorded on another platform (with --out DIR there): what only that
#                     platform runs, and the regular expressions only it builds, go in this one too
source "$(dirname "$0")/lib/common.sh"
PI_DIR="$PIBOLT_PI"; PLUGINS=""; OUT=""; WITH=""
while [ $# -gt 0 ]; do
	case "$1" in
	--pi) PI_DIR="$2"; shift ;;
	--plugins) PLUGINS="$2"; shift ;;
	--out) OUT="$2"; shift ;;
	--with) WITH="$2"; shift ;;
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
# both: in one file (as scripts/build-pi.ps1 built Windows until Pi-Bolt 1.0.5) and split (scripts/build-pi.sh and
# scripts/build-pi.ps1). The one-file order goes first: merged the other way round, the one-file Windows build's `pi -p`
# measured 1-2 ms slower (macOS and Linux: the same either way).
BUILD_ARGS=(build --compile --no-compile-autoload-bunfig --no-compile-autoload-dotenv --compile-autoload-package-json
	--target="bun-$PIBOLT_PLATFORM" --bytecode --format=esm --compile-exec-argv=--smol)
for kind in whole split; do
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
# The strings' order of the profile this one replaces goes first: scripts\train-heap.ps1 records it from the prebuilt heap, which
# is what a start touches, and a string is named by its text, so it still holds (the heap order is recorded again after this).
ORDERS=()
if [ -f "$OUT/bytecode.order" ]; then
	{ echo v2; grep '^S ' "$OUT/bytecode.order" || true; } >"$TMP/strings.order"
	ORDERS+=("$TMP/strings.order")
fi
mkdir -p "$OUT"
ORDERS+=("$TMP/whole/bytecode.order" "$TMP/split/bytecode.order")
REGEXPS=("$TMP/whole/regexps.txt" "$TMP/split/regexps.txt")
if [ -n "$WITH" ]; then
	[ -f "$WITH/bytecode.order" ] && [ -f "$WITH/regexps.txt" ] || die "--with: $WITH is not a profile (bytecode.order, regexps.txt)"
	ORDERS+=("$WITH/bytecode.order")
	REGEXPS+=("$WITH/regexps.txt")
fi
python3 "$PIBOLT_ROOT/scripts/lib/merge_orders.py" "${ORDERS[@]}" >"$TMP/bytecode.order"
sort -u "${REGEXPS[@]}" >"$TMP/regexps.txt"
mv "$TMP/bytecode.order" "$OUT/bytecode.order"
mv "$TMP/regexps.txt" "$OUT/regexps.txt"
log "profile: $OUT ($(wc -l < "$OUT/regexps.txt") regular expressions)"
