#!/usr/bin/env bash
# Write the corresponding source of the pack's GPL/LGPL parts to $PIBOLT_PACK_OUT/pi-bolt-android-engine-sources.tar
# (published as a release asset next to the APK): glibc + Termux's patches + our build scripts (LGPL-2.1), git
# (GPL-2), the Debian source packages of bash (GPL-3) and busybox (GPL-2), and curl. Uses the build's download cache.
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
WORK=${PIBOLT_PACK_WORK:-$HERE/.work}
OUT=${PIBOLT_PACK_OUT:-$HERE/out}
mkdir -p "$WORK/dl" "$OUT"
WORK=$(cd "$WORK" && pwd -P)
OUT=$(cd "$OUT" && pwd -P)
docker run --rm --platform linux/arm64 -v "$HERE:/engine:ro" -v "$WORK/dl:/dl" -v "$OUT:/out" pibolt-pack-builder bash -c '
set -euo pipefail
. /engine/versions.env
fetch() { # url sha256 dest
	[ -f "$3" ] && echo "$2  $3" | sha256sum -c --status - && return 0
	curl -fsSL --retry 3 -o "$3.part" "$1"
	echo "$2  $3.part" | sha256sum -c --quiet -
	mv "$3.part" "$3"
}
S=/tmp/pi-bolt-android-engine-sources
rm -rf $S && mkdir -p $S/debian $S/build
fetch "$GLIBC_URL" "$GLIBC_SHA256" "/dl/glibc-$GLIBC_VERSION.tar.xz"
fetch "$GIT_URL" "$GIT_SHA256" "/dl/git-$GIT_VERSION.tar.xz"
fetch "$CURL_URL" "$CURL_SHA256" "/dl/curl-$CURL_VERSION.tar.xz"
cp "/dl/glibc-$GLIBC_VERSION.tar.xz" "/dl/git-$GIT_VERSION.tar.xz" "/dl/curl-$CURL_VERSION.tar.xz" $S/
if [ ! -d "/dl/glibc-packages-$TERMUX_GLIBC_COMMIT" ]; then
	git clone -q "$TERMUX_GLIBC_REPO" /tmp/gp && git -C /tmp/gp checkout -q "$TERMUX_GLIBC_COMMIT"
	mv /tmp/gp "/dl/glibc-packages-$TERMUX_GLIBC_COMMIT"
fi
tar -C "/dl/glibc-packages-$TERMUX_GLIBC_COMMIT" -czf "$S/termux-glibc-packages-$TERMUX_GLIBC_COMMIT.tar.gz" \
	LICENSE.md gpkg/glibc
# Debian source packages for the GPL binaries taken from Debian (binNMU suffix +bN dropped, epoch dropped).
snapshot=$(sed -n "s/^@snapshot //p" /engine/userland/debs.lock)
for pkg in bash busybox; do
	ver=$(awk -v p=$pkg "\$1 == p {print \$2}" /engine/userland/debs.lock | sed -e "s/+b[0-9]*$//" -e "s/^[0-9]*://")
	pool="debian/pool/main/${pkg:0:1}/$pkg"
	dsc="${pkg}_$ver.dsc"
	curl -fsSL --retry 3 -o "$S/debian/$dsc" "$DEBIAN_MIRROR/$pool/$dsc" ||
		curl -fsSL --retry 3 -o "$S/debian/$dsc" "$DEBIAN_SNAPSHOT/debian/$snapshot/pool/main/${pkg:0:1}/$pkg/$dsc"
	awk "/^Checksums-Sha256:/{f=1; next} /^[^ ]/{f=0} f{print \$1, \$3}" "$S/debian/$dsc" | while read -r sha file; do
		fetch "$DEBIAN_MIRROR/$pool/$file" "$sha" "$S/debian/$file" 2>/dev/null ||
			fetch "$DEBIAN_SNAPSHOT/debian/$snapshot/pool/main/${pkg:0:1}/$pkg/$file" "$sha" "$S/debian/$file"
	done
done
# Our build scripts, Dockerfile, pins and notices (android/engine without build outputs).
tar -C /engine --exclude=./out --exclude=./.work -cf - . | tar -C $S/build -xf -
cat >$S/README.md <<EOF
# Pi-Bolt Android engine pack: corresponding source

- glibc-$GLIBC_VERSION.tar.xz: GNU C Library release (LGPL-2.1-or-later), unmodified tarball.
- termux-glibc-packages-$TERMUX_GLIBC_COMMIT.tar.gz: termux-pacman/glibc-packages gpkg/glibc at that commit: the
  Android patches and added source files applied to glibc (build scripts MIT, see LICENSE.md).
- build/: android/engine from the Pi-Bolt repository. build/glibc/build-glibc.sh applies the patches with the app
  prefix substituted for @TERMUX_PREFIX@, replaces execve with build/glibc/execve.c (ours, LGPL-2.1-or-later like
  glibc) and builds glibc; build/build-pack.sh builds the whole pack (Docker).
- git-$GIT_VERSION.tar.xz, curl-$CURL_VERSION.tar.xz: unmodified release tarballs, built by
  build/userland/build-userland.sh.
- debian/: Debian source packages of bash and busybox, whose Debian arm64 binaries are shipped (interpreter path
  set with patchelf, nothing else changed).

To rebuild: unpack build/ as android/engine in a checkout (or anywhere), put the tarballs in its .work/dl/, and run
build-pack.sh on an arm64 Linux host or Mac with Docker.
EOF
tar -C /tmp -cf /out/pi-bolt-android-engine-sources.tar pi-bolt-android-engine-sources
ls -la /out/pi-bolt-android-engine-sources.tar
tar -tvf /out/pi-bolt-android-engine-sources.tar | grep -v "/build/" | awk "{print \$3, \$6}"
'
