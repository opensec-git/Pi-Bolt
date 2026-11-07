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
# Runtime trees first: the links of their extension modules go inside them.
for t in m.get("trees", []):
    p = f"{prefix}/{t['path']}"
    print(f"rm -rf {q(p)} && mkdir -p {q(p)} && unzip -q -o /apk-assets/{q(t['asset'])} -d {q(p)}")
    if t["executables"]:
        print(f"(cd {q(p)} && chmod 755 " + " ".join(q(x) for x in t["executables"]) + ")")
for l in m["links"]:
    p = f"{prefix}/{l['path']}"
    print(f"mkdir -p {q(p.rsplit('/', 1)[0])} && ln -sfn {q(nld + '/' + l['target'])} {q(p)}")
for f in m["files"]:
    p = f"{prefix}/{f['path']}"
    print(f"mkdir -p {q(p.rsplit('/', 1)[0])} && cp /apk-assets/{q(f['asset'])} {q(p)}")
# Bundled extensions: their files, their node_modules zip, and the settings.json packages entries (EnginePrefix).
exts = []
for e in m.get("extensions", []):
    p = f"{prefix}/share/pi-extensions/{e['name']}"
    print(f"rm -rf {q(p)} && mkdir -p {q(p)} && cp -r /apk-assets/engine/extensions/{q(e['name'])}/. {q(p)}/")
    if "modules" in e:
        print(f"mkdir -p {q(p)}/node_modules && unzip -q -o /apk-assets/{q(e['modules']['asset'])} -d {q(p)}/node_modules")
    exts.append(p)
print('mkdir -p "$HOMEDIR/.pi/agent" && printf %s ' + q(json.dumps({"packages": exts})) + ' > "$HOMEDIR/.pi/agent/settings.json"')
print(f"echo {q(m['packFingerprint'])} > {q(prefix)}/.pack")
print("ENGINE_ENV='" + " ".join(f"{k}={v}" for k, v in sorted(m["env"].items())) + "'")
EOF
docker run --rm --platform linux/arm64 \
	-v "$OUT/jniLibs/arm64-v8a:$NLD:ro" -v "$OUT/assets:/apk-assets:ro" \
	-v "$SETUP:/setup.sh:ro" -v "$HERE/scripts/verify-in-container.sh:/verify.sh:ro" \
	alpine:3.22 sh /verify.sh "$NLD" "${1-}"
