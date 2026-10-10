#!/usr/bin/env bash
# Builds Pi for scripts/build-pi.sh. Pi-Bolt is a fork of Pi: by default this is the Pi of this repository, built in place and
# offline. Its model catalog (packages/ai/src/providers/data) is part of the repository, so the build needs no network and
# gives the same Pi every time. --refresh-models first fetches the catalog again from the model providers (every provider must
# answer); commit what it changes. With --tag, it clones another Pi release from upstream and builds that instead: upstream Pi
# does not keep its catalog in git, so that build fetches it.
# Usage: scripts/prepare-pi.sh [--refresh-models] [--tag vX.Y.Z [--dir DIR]]      Needs Node.js 22.19+ with npm (git for --tag).
source "$(dirname "$0")/lib/common.sh"
need npm
TAG=""; DIR=""; REFRESH=""
while [ $# -gt 0 ]; do
	case "$1" in
	--tag) TAG="$2"; shift ;;
	--dir) DIR="$2"; shift ;;
	--refresh-models) REFRESH=1 ;;
	-h | --help) sed -n '2,7p' "$0"; exit 0 ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
BUILD=build:offline
if [ -n "$TAG" ]; then
	need git
	DIR="${DIR:-$PIBOLT_WORK/pi-${TAG#v}}"
	if [ ! -d "$DIR/.git" ]; then
		log "cloning Pi $TAG from $(source_field pi upstream)"
		git clone -q --depth 1 --branch "$TAG" "$(source_field pi upstream)" "$DIR"
	fi
	BUILD=build
else
	DIR="$PIBOLT_PI"
	[ -n "$REFRESH" ] && BUILD=build
fi
mkdir -p "$PIBOLT_WORK"
LOG="$PIBOLT_WORK/prepare-pi.log"
run() { # run STEP COMMAND...: the output goes to the log, and its end to the terminal when the step fails
	if ! (cd "$DIR" && "$@") >"$LOG" 2>&1; then
		tail -n 40 "$LOG" >&2
		[ "$*" = "npm run build" ] && [ -z "$TAG" ] &&
			warn "fetching the model catalog failed (a provider could not be reached?): without --refresh-models, the build uses the catalog in the repository"
		die "'$*' failed in $DIR (the whole output: $LOG)"
	fi
}
log "building Pi in $DIR$([ "$BUILD" = build ] && echo ", fetching the model catalog from the providers")"
run npm ci --ignore-scripts --no-audit --no-fund
run npm run "$BUILD"
log "Pi $(pi_version "$(pi_agent_dir "$DIR")") ready in $DIR"
