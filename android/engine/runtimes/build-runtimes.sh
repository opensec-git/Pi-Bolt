#!/usr/bin/env bash
# Language runtimes for the pack, laid out as PREFIX in /stage/runtimes/root, licences in /stage/runtimes/licenses.
# Runs in the builder container (docker/Dockerfile), linux/arm64.
#  - Node.js: the official linux-arm64 release (glibc 2.28+, libstdc++ from debs.lock), with npm. Not corepack, headers
#    or man pages. npm's builtin config puts global installs under HOME/.local.
#  - CPython: built from the release tarball for PREFIX (sys.prefix compiled in), against the pinned Debian libraries
#    (OpenSSL, SQLite, libffi, bzip2, xz, mpdecimal, ncurses, uuid), with pip from ensurepip. No tkinter, no readline
#    (GPL; the 3.13+ REPL edits lines without it), no test suite or IDLE.
# assemble.py ships the executables and extension modules from jniLibs and the library trees as zip assets.
set -euo pipefail
. /engine/versions.env
LOCK=/engine/userland/debs.lock
OUT=/stage/runtimes
ROOT=$OUT/root
B=/b
rm -rf "$B" "$OUT" && mkdir -p "$B" "$ROOT"/{bin,lib} "$OUT/licenses" /dl/debs

fetch() { # url sha256 dest [fallback-url]
	[ -f "$3" ] && echo "$2  $3" | sha256sum -c --status - && return 0
	curl -fsSL --retry 3 -o "$3.part" "$1" || { [ -n "${4-}" ] && curl -fsSL --retry 3 -o "$3.part" "$4"; }
	echo "$2  $3.part" | sha256sum -c --quiet -
	mv "$3.part" "$3"
}

# Build against the library versions that ship (debs.lock lib + dev packages).
SNAPSHOT=$(sed -n 's/^@snapshot //p' "$LOCK")
: >"$B/dpkg.list"
while read -r pkg _ver role path sha; do
	case $pkg in '#'* | '@'* | '') continue ;; esac
	case $role in dev | lib) ;; *) continue ;; esac
	archive=debian
	case $path in pool/updates/*) archive=debian-security ;; esac
	deb=/dl/debs/$(basename "$path")
	fetch "$DEBIAN_MIRROR/$archive/$path" "$sha" "$deb" "$DEBIAN_SNAPSHOT/$archive/$SNAPSHOT/$path"
	echo "$deb" >>"$B/dpkg.list"
done <"$LOCK"
xargs dpkg -i --force-downgrade --force-depends <"$B/dpkg.list" >/dev/null 2>&1

wrapper() { # name command...: an executable script in bin/ (shipped from jniLibs; shebang PREFIX/bin/sh)
	local f=$ROOT/bin/$1
	shift
	printf '#!%s/bin/sh\nexec %s "$@"\n' "$PREFIX" "$*" >"$f"
	chmod 0755 "$f"
}

# ---- Node.js ------------------------------------------------------------------------------------------------------
fetch "$NODE_URL" "$NODE_SHA256" "/dl/node-v$NODE_VERSION-linux-arm64.tar.xz"
tar xf "/dl/node-v$NODE_VERSION-linux-arm64.tar.xz" -C "$B"
N=$B/node-v$NODE_VERSION-linux-arm64
cp "$N/bin/node" "$ROOT/bin/node"
mkdir -p "$ROOT/lib/node_modules"
cp -a "$N/lib/node_modules/npm" "$ROOT/lib/node_modules/npm"
rm -f "$ROOT"/lib/node_modules/npm/bin/*.cmd "$ROOT"/lib/node_modules/npm/bin/*.ps1 "$ROOT"/lib/node_modules/npm/bin/node-gyp-bin/*.cmd
# npm's builtin config (lowest precedence after defaults): global installs and bins under HOME/.local, scripts run by
# the pack's bash, no update check.
cat >"$ROOT/lib/node_modules/npm/npmrc" <<EOF
prefix=\${HOME}/.local
script-shell=$PREFIX/bin/sh
update-notifier=false
EOF
wrapper npm "$PREFIX/bin/node" "$PREFIX/lib/node_modules/npm/bin/npm-cli.js"
wrapper npx "$PREFIX/bin/node" "$PREFIX/lib/node_modules/npm/bin/npx-cli.js"
cp "$N/LICENSE" "$OUT/licenses/node.LICENSE"
cp "$ROOT/lib/node_modules/npm/LICENSE" "$OUT/licenses/npm.LICENSE"
strip --strip-unneeded "$ROOT/bin/node"

# ---- CPython ------------------------------------------------------------------------------------------------------
fetch "$PYTHON_URL" "$PYTHON_SHA256" "/dl/Python-$PYTHON_VERSION.tar.xz"
PY=python${PYTHON_VERSION%.*}
tar xf "/dl/Python-$PYTHON_VERSION.tar.xz" -C "$B"
cd "$B/Python-$PYTHON_VERSION"
# Installed into the real PREFIX inside this throwaway container: sys.prefix, the stdlib path and pip's shebangs name it.
mkdir -p "$PREFIX/bin" && ln -sf /bin/sh "$PREFIX/bin/sh"
CFLAGS="-O2" ./configure --prefix="$PREFIX" --build=aarch64-linux-gnu \
	--with-ensurepip=install --with-system-libmpdec --without-readline --disable-test-modules \
	--with-openssl=/usr --with-ssl-default-suites=openssl --with-tzpath="$PREFIX/share/zoneinfo" \
	>"$B/python-configure.log" 2>&1 || { tail -40 "$B/python-configure.log"; exit 1; }
make -j"$(nproc)" >"$B/python-make.log" 2>&1 || { tail -40 "$B/python-make.log"; exit 1; }
make install >"$B/python-install.log" 2>&1 || { tail -40 "$B/python-install.log"; exit 1; }
# Every extension module the build meant to have must be there (a missing -dev package only warns at build time).
"$PREFIX/bin/$PY" -c 'import ssl, sqlite3, ctypes, bz2, lzma, zlib, decimal, curses, uuid, hashlib, json, venv, ensurepip; print(ssl.OPENSSL_VERSION, sqlite3.sqlite_version)'
cp LICENSE "$OUT/licenses/python.LICENSE"

cp "$PREFIX/bin/$PY" "$ROOT/bin/$PY"
ln -s "$PY" "$ROOT/bin/python3"
ln -s "$PY" "$ROOT/bin/python"
wrapper pip "$PREFIX/bin/$PY" -m pip
ln -s pip "$ROOT/bin/pip3"
cp -a "$PREFIX/lib/$PY" "$ROOT/lib/$PY"
L=$ROOT/lib/$PY
rm -rf "$L"/{test,idlelib,tkinter,turtledemo,turtle.py,__phello__} "$L"/config-*/libpython*.a "$L"/config-*/*.o
find "$L" -name __pycache__ -type d -prune -exec rm -rf {} +
find "$L" -name '*.exe' -delete
strip --strip-unneeded "$ROOT/bin/$PY" "$L"/lib-dynload/*.so
# Outside a virtualenv pip installs into the user site (HOME/.local), not into the pack's stdlib tree. A venv's own
# sys.prefix has no pip.conf, so this does not apply there.
printf '[install]\nuser = true\n' >"$ROOT/pip.conf"
cp "$L"/site-packages/pip-*.dist-info/licenses/LICENSE.txt "$OUT/licenses/pip.LICENSE" 2>/dev/null ||
	cp "$L"/site-packages/pip-*.dist-info/LICENSE.txt "$OUT/licenses/pip.LICENSE"

echo "runtimes: node $("$N/bin/node" --version), $("$PREFIX/bin/$PY" --version), $(find "$ROOT" -type f | wc -l) files, $(du -sh "$ROOT" | cut -f1)"
