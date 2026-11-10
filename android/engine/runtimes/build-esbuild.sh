#!/usr/bin/env bash
# esbuild for the pack: /stage/esbuild/root/bin/esbuild (shipped as libesbuild_exe.so, PREFIX/bin/esbuild), licences in
# /stage/esbuild/licenses. Runs in the builder container (docker/Dockerfile), linux/arm64.
#  - Built from the pinned release's source with the pinned Go, the way esbuild builds its own @esbuild/android-arm64
#    package (GOOS=android, CGO_ENABLED=0, same flags): a PIE whose interpreter is Android's /system/bin/linker64 and
#    that needs no library. The kernel runs it from nativeLibraryDir like any app executable; Go's android port avoids
#    the system calls Android's seccomp filter kills (faccessat2, pidfd before Android 12).
#  - esbuild-host-versions.patch: one binary serves the JS API of esbuild 0.19.0 to 0.28.x (CONTRACT.md, esbuild).
# The app points every project's esbuild package at it with ESBUILD_BINARY_PATH (EngineEnvironment.kt).
set -euo pipefail
. /engine/versions.env
OUT=/stage/esbuild
B=/b
rm -rf "$B" "$OUT" && mkdir -p "$B" "$OUT/root/bin" "$OUT/licenses"

fetch() { # url sha256 dest
	[ -f "$3" ] && echo "$2  $3" | sha256sum -c --status - && return 0
	curl -fsSL --retry 3 -o "$3.part" "$1"
	echo "$2  $3.part" | sha256sum -c --quiet -
	mv "$3.part" "$3"
}

fetch "$GO_URL" "$GO_SHA256" "/dl/go$GO_VERSION.linux-arm64.tar.gz"
fetch "$ESBUILD_URL" "$ESBUILD_SHA256" "/dl/esbuild-$ESBUILD_VERSION.tar.gz"
tar xzf "/dl/go$GO_VERSION.linux-arm64.tar.gz" -C "$B"
tar xzf "/dl/esbuild-$ESBUILD_VERSION.tar.gz" -C "$B"
S=$B/esbuild-$ESBUILD_VERSION
patch -d "$S" -p1 --quiet <"/engine/runtimes/esbuild-host-versions.patch"
grep -qx "$GO_VERSION" "$S/go.version" || { echo "esbuild $ESBUILD_VERSION is built with Go $(cat "$S/go.version"), not $GO_VERSION"; exit 1; }

# Modules (golang.org/x/sys) come from the Go proxy, checked against esbuild's go.sum; cached in /dl.
export PATH=$B/go/bin:$PATH GOROOT=$B/go GOTOOLCHAIN=local GOFLAGS=-mod=readonly GOMODCACHE=/dl/gomod \
	GOCACHE=$B/gocache GOPATH=$B/gopath CGO_ENABLED=0
cd "$S"
go test ./cmd/esbuild -run 'TestCompatibleHostVersion|TestHostBefore|TestVersionForWorkingDirectory'
GOOS=android GOARCH=arm64 go build "-ldflags=-s -w -buildid=" -buildvcs=false -trimpath -o "$OUT/root/bin/esbuild" ./cmd/esbuild
readelf -lW "$OUT/root/bin/esbuild" | grep -q 'program interpreter: /system/bin/linker64'
[ -z "$(readelf -dW "$OUT/root/bin/esbuild" | grep NEEDED)" ] || { echo "esbuild needs shared libraries"; exit 1; }

# The handshake, with a linux build of the same source (the android one needs Android's linker): the binary answers
# with the host's version for a supported host and refuses others.
GOOS=linux GOARCH=arm64 go build -trimpath -o "$B/esbuild-linux" ./cmd/esbuild
[ "$("$B/esbuild-linux" --version)" = "$ESBUILD_VERSION" ]
for host in 0.19.0 0.21.5 0.25.12 0.28.2; do
	got=$("$B/esbuild-linux" --service="$host" </dev/null | tail -c +5)
	[ "$got" = "$host" ] || { echo "host $host: handshake answered '$got'"; exit 1; }
done
for host in 0.18.20 0.29.0; do
	if "$B/esbuild-linux" --service="$host" </dev/null 2>/dev/null; then echo "host $host was accepted"; exit 1; fi
done

cp "$S/LICENSE.md" "$OUT/licenses/esbuild.LICENSE"
cp "$B/go/LICENSE" "$OUT/licenses/go.LICENSE"
cp /dl/gomod/golang.org/x/sys@*/LICENSE "$OUT/licenses/golang-x-sys.LICENSE"
chmod 0755 "$OUT/root/bin/esbuild"
echo "esbuild: $ESBUILD_VERSION (go $GO_VERSION, android/arm64), $(du -h "$OUT/root/bin/esbuild" | cut -f1)"
