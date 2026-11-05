#!/usr/bin/env bash
# Sets Pi-Bolt's version everywhere it is written: VERSION, npm/package.json, npm/bin/pi-bolt, and (with --pi) the Pi version in
# sources.json. Without --version, the last number goes up by one, from 0 to 9, and after 9 the middle one does (1.0.8 -> 1.0.9
# -> 1.1.0), a new Pi version included.
# Usage: scripts/bump-version.sh [--version X.Y.Z] [--pi X.Y.Z]
source "$(dirname "$0")/lib/common.sh"

NEW=""; PI=""
while [ $# -gt 0 ]; do
	case "$1" in
	--version) NEW="$2"; shift ;;
	--pi) PI="$2"; shift ;;
	-h | --help) sed -n '2,5p' "$0"; exit 0 ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
cd "$PIBOLT_ROOT" || exit 1
CURRENT="$(cat VERSION)"
if [ -z "$NEW" ]; then
	IFS=. read -r major minor patch <<<"$CURRENT"
	if [ "$patch" -ge 9 ]; then NEW="$major.$((minor + 1)).0"; else NEW="$major.$minor.$((patch + 1))"; fi
fi
[[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "not a version: $NEW"
echo "$NEW" >VERSION
# (Not sed -i, which takes its argument differently in macOS's sed.)
perl -pi -e "s/^VERSION=.*/VERSION=$NEW/" npm/bin/pi-bolt
python3 - "$NEW" "$PI" <<'PY'
import json, sys
new, pi = sys.argv[1], sys.argv[2]
p = json.load(open("npm/package.json")); p["version"] = new
open("npm/package.json", "w").write(json.dumps(p, indent="\t") + "\n")
if pi:
    s = json.load(open("sources.json"))
    s["pi"]["tag"] = "v" + pi
    s["pi"].pop("commit", None)
    s["pi"]["profile"] = "profiles/pi-" + pi
    open("sources.json", "w").write(json.dumps(s, indent=2) + "\n")
PY
log "Pi-Bolt $CURRENT -> $NEW${PI:+ (Pi $PI)}"
