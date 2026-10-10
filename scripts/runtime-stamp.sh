#!/usr/bin/env bash
# Prints the runtime's stamp: a hash of what the runtime is built from, the engine entries of sources.json (webkit, bun), the
# patches and the runtime build scripts. A release whose stamp is the previous release's uses that release's runtime again.
set -euo pipefail
cd "$(dirname "$0")/.."
sum() { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi; }
{
	python3 -c 'import json; s = json.load(open("sources.json")); print(json.dumps({"webkit": s["webkit"], "bun": s["bun"]}, sort_keys=True))'
	cat patches/webkit.patch patches/bun.patch scripts/build-runtime.sh scripts/toolchain/*.sh
} | sum | cut -c1-16
