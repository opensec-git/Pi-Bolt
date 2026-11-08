#!/usr/bin/env bash
# Prints the runtime's stamp: a hash of what the runtime is built from, the engine entries of sources.json (webkit, bun), the
# patches, the runtime build scripts and the linker-order hints they link with (profiles/runtime-*.hints). A release whose
# stamp is the previous release's uses that release's runtime again.
# Usage: scripts/runtime-stamp.sh [musl]
#   musl   the stamp of the musl runtime (linux-x64-musl): the one above with what only that runtime is built with
#          (scripts/build-runtime-musl.sh), so that a change to those changes the musl runtime's stamp only, and one to the
#          engine both.
set -euo pipefail
cd "$(dirname "$0")/.."
sum() { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi; }
stamp=$({
	python3 -c 'import json; s = json.load(open("sources.json")); print(json.dumps({"webkit": s["webkit"], "bun": s["bun"]}, sort_keys=True))'
	cat patches/webkit.patch patches/bun.patch scripts/build-runtime.sh scripts/build-runtime.ps1 scripts/toolchain/*.sh profiles/runtime-*.hints
} | sum | cut -c1-16)
case "${1:-}" in
"") echo "$stamp" ;;
musl) { echo "$stamp"; cat scripts/build-runtime-musl.sh; } | sum | cut -c1-16 ;;
*) echo "unknown runtime: $1" >&2; exit 1 ;;
esac
