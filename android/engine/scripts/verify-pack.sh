#!/usr/bin/env bash
# Run the built pack the way the app does, without a phone: a linux/arm64 Alpine container (musl, so no glibc on
# the system and no /bin/bash, like Android) with the jniLibs mounted read-only at a nativeLibraryDir-like path, the
# symlink farm created from manifest.json at the real PREFIX, and the engine and tools exec'd from there.
#   android/engine/scripts/verify-pack.sh [--engine-only]
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(cd "${PIBOLT_PACK_OUT:-$HERE/out}" && pwd -P)
NLD=/data/app/~~pack/in.opensec.pibolt-1/lib/arm64
SETUP=$(mktemp)
trap 'rm -f "$SETUP"' EXIT
# The app's setup step (CONTRACT.md "Runtime setup" 1-2) as a shell script.
python3 -I - "$OUT/assets/engine/manifest.json" "$NLD" >"$SETUP" <<'EOF'
import json, shlex, sys
m = json.load(open(sys.argv[1]))
nld, prefix = sys.argv[2], m["prefix"]
q = shlex.quote
print(f"mkdir -p {q(prefix)}/tmp {q(prefix)}/etc")
for l in m["links"]:
    p = f"{prefix}/{l['path']}"
    print(f"mkdir -p {q(p.rsplit('/', 1)[0])} && ln -sfn {q(nld + '/' + l['target'])} {q(p)}")
for f in m["files"]:
    p = f"{prefix}/{f['path']}"
    print(f"mkdir -p {q(p.rsplit('/', 1)[0])} && cp /apk-assets/{q(f['asset'])} {q(p)}")
print(f"echo {q(m['packFingerprint'])} > {q(prefix)}/.pack")
print("ENGINE_ENV='" + " ".join(f"{k}={v}" for k, v in sorted(m["env"].items())) + "'")
EOF
docker run --rm --platform linux/arm64 \
	-v "$OUT/jniLibs/arm64-v8a:$NLD:ro" -v "$OUT/assets:/apk-assets:ro" \
	-v "$SETUP:/setup.sh:ro" -v "$HERE/scripts/verify-in-container.sh:/verify.sh:ro" \
	alpine:3.22 sh /verify.sh "$NLD" "${1-}"
