#!/usr/bin/env bash
# Build the on-device engine pack: android/engine/out/{jniLibs/arm64-v8a, assets/engine}. See CONTRACT.md.
# Needs Docker on a linux/arm64 host (or an arm64 Mac). Everything is pinned in versions.env and userland/debs.lock.
#
#   android/engine/build-pack.sh                 full pack
#   android/engine/build-pack.sh --engine-only   glibc + engine only (no userland)
#
# Env: PIBOLT_PACK_WORK (download cache + stage, default android/engine/.work),
#      PIBOLT_PACK_OUT (default android/engine/out). Stages are cached by a hash of their inputs.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${PIBOLT_PACK_WORK:-$HERE/.work}
OUT=${PIBOLT_PACK_OUT:-$HERE/out}
IMAGE=pibolt-pack-builder
ENGINE_ONLY=false
[ "${1-}" = "--engine-only" ] && ENGINE_ONLY=true
mkdir -p "$WORK/dl" "$WORK/stage" "$OUT"
WORK=$(cd "$WORK" && pwd -P)
OUT=$(cd "$OUT" && pwd -P)

hash_of() { cat "$@" | { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-64; }

echo "== builder image"
docker build -q --platform linux/arm64 -t "$IMAGE" "$HERE/docker" >/dev/null

run() { # command...: run in the builder with /engine, /extensions.txt (the extension pins), /dl, /stage, /out mounted
	docker run --rm --platform linux/arm64 \
		-v "$HERE:/engine:ro" -v "$HERE/../../extensions.txt:/extensions.txt:ro" \
		-v "$WORK/dl:/dl" -v "$WORK/stage:/stage" -v "$OUT:/out" \
		"$IMAGE" "$@"
}

# On Linux the container writes as root; hand its output back to this user, so the stage keys can be written and later
# steps (verify-pack.sh, Gradle, the CI artifact upload) own what they read. Docker Desktop maps ownership already.
own() { run chown -R "$(id -u):$(id -g)" /stage /out /dl; }

stage() { # name script [inputs...]: run a stage unless its inputs are unchanged
	local name=$1 script=$2
	shift 2
	local key
	key=$(hash_of "$HERE/versions.env" "$HERE/docker/Dockerfile" "$HERE/$script" "$@")
	if [ "$(cat "$WORK/stage/$name/.key" 2>/dev/null)" = "$key" ]; then
		echo "== $name: cached"
		return
	fi
	echo "== $name"
	local t0=$SECONDS
	run bash "/engine/$script"
	own
	echo "$key" >"$WORK/stage/$name/.key"
	echo "== $name: $((SECONDS - t0))s"
}

stage glibc glibc/build-glibc.sh "$HERE/glibc/execve.c"
if [ "$ENGINE_ONLY" = false ]; then
	stage userland userland/build-userland.sh "$HERE/userland/debs.lock" "$HERE"/userland/*.txt
	stage runtimes runtimes/build-runtimes.sh "$HERE/userland/debs.lock"
	stage esbuild runtimes/build-esbuild.sh "$HERE/runtimes/esbuild-host-versions.patch"
fi
echo "== assemble"
if [ "$ENGINE_ONLY" = true ]; then
	run python3 -I /engine/scripts/assemble.py --engine-only
else
	run python3 -I /engine/scripts/assemble.py
fi
own
du -sh "$OUT/jniLibs/arm64-v8a" "$OUT/assets/engine"
