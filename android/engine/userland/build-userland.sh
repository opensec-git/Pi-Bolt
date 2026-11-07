#!/usr/bin/env bash
# Userland for the pack, laid out as PREFIX in /stage/userland/root (bin/, libexec/, lib/, share/, etc/), plus the
# licences in /stage/userland/licenses. Runs in the builder container (docker/Dockerfile), linux/arm64.
#  - Debian trixie packages pinned in debs.lock: bash, busybox, ripgrep, fd, shared libraries, CA certificates,
#    terminfo.
#  - curl (libcurl + CLI) and git built from source for PREFIX: exec path, templates, system config, /bin/sh and the
#    CA bundle are compiled in, TLS is OpenSSL (Debian's libssl3, pinned).
# assemble.py later ships only what is needed, renames ELFs to lib*.so, sets PT_INTERP and drops RPATH.
set -euo pipefail
. /engine/versions.env
LOCK=/engine/userland/debs.lock
OUT=/stage/userland
ROOT=$OUT/root
B=/b
rm -rf "$B" "$OUT" && mkdir -p "$B/debs" "$ROOT"/{bin,lib,etc/ssl/certs,share/terminfo} "$OUT/licenses" /dl/debs

fetch() { # url sha256 dest [fallback-url]
	[ -f "$3" ] && echo "$2  $3" | sha256sum -c --status - && return 0
	curl -fsSL --retry 3 -o "$3.part" "$1" || { [ -n "${4-}" ] && curl -fsSL --retry 3 -o "$3.part" "$4"; }
	echo "$2  $3.part" | sha256sum -c --quiet -
	mv "$3.part" "$3"
}

# ---- Debian packages ----------------------------------------------------------------------------------------------
SNAPSHOT=$(sed -n 's/^@snapshot //p' "$LOCK")
declare -A ROLE
while read -r pkg _ver role path sha; do
	case $pkg in '#'* | '@'* | '') continue ;; esac
	archive=debian
	case $path in pool/updates/*) archive=debian-security ;; esac
	deb=/dl/debs/$(basename "$path")
	fetch "$DEBIAN_MIRROR/$archive/$path" "$sha" "$deb" "$DEBIAN_SNAPSHOT/$archive/$SNAPSHOT/$path"
	ROLE[$pkg]=$role
	mkdir -p "$B/debs/$pkg"
	dpkg-deb -x "$deb" "$B/debs/$pkg"
	if [ "$role" != dev ] && [ -f "$B/debs/$pkg/usr/share/doc/$pkg/copyright" ]; then
		cp "$B/debs/$pkg/usr/share/doc/$pkg/copyright" "$OUT/licenses/$pkg.copyright"
	fi
	case $role in dev | lib) echo "$deb" >>"$B/dpkg-install.list" ;; esac
done <"$LOCK"
# libgcc-s1 has no copyright file of its own: gcc-14-base's covers it.
# Build curl and git against the pinned library versions that ship.
xargs dpkg -i --force-downgrade --force-depends <"$B/dpkg-install.list" >/dev/null
{
	echo "# Debian packages in this pack (trixie, arm64; sources: https://snapshot.debian.org)"
	grep -v '^[#@]' "$LOCK" | awk '$3 != "dev" {print $1, $2}'
} >"$OUT/licenses/debian-packages.txt"

d() { echo "$B/debs/$1"; }
cp "$(d bash)/usr/bin/bash" "$ROOT/bin/bash"
ln -s bash "$ROOT/bin/sh"
cp "$(d ripgrep)/usr/bin/rg" "$ROOT/bin/rg"
cp "$(d fd-find)/usr/bin/fdfind" "$ROOT/bin/fd"
ln -s fd "$ROOT/bin/fdfind"
cp "$(d busybox)/usr/bin/busybox" "$ROOT/bin/busybox"
skip=" $(grep -v '^#' /engine/userland/busybox-skip.txt | tr '\n' ' ') "
for applet in $("$ROOT/bin/busybox" --list); do
	case $skip in *" $applet "*) continue ;; esac
	[ -e "$ROOT/bin/$applet" ] || ln -s busybox "$ROOT/bin/$applet"
done
for pkg in "${!ROLE[@]}"; do
	[ "${ROLE[$pkg]}" = lib ] || continue
	for f in "$(d "$pkg")"/usr/lib/aarch64-linux-gnu/*.so.*; do
		[ -f "$f" ] && [ ! -L "$f" ] || continue
		soname=$(readelf -dW "$f" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
		cp "$f" "$ROOT/lib/${soname:-$(basename "$f")}"
	done
done

# CA bundle: Mozilla's roots as Debian ships them (update-ca-certificates concatenates the same files).
cat "$(d ca-certificates)"/usr/share/ca-certificates/mozilla/*.crt >"$ROOT/etc/ssl/certs/ca-certificates.crt"
# terminfo: what a terminal pane or TERM from a host needs.
for t in xterm-256color xterm xterm-color screen screen-256color tmux tmux-256color vt100 vt220 linux dumb ansi; do
	f=$(find "$(d ncurses-base)" "$(d ncurses-term)" -path "*/terminfo/${t:0:1}/$t" | head -1)
	[ -n "$f" ] || { echo "terminfo $t not found" >&2; exit 1; }
	mkdir -p "$ROOT/share/terminfo/${t:0:1}"
	cp -L "$f" "$ROOT/share/terminfo/${t:0:1}/$t"
done

# ---- curl and git from source -------------------------------------------------------------------------------------
fetch "$CURL_URL" "$CURL_SHA256" "/dl/curl-$CURL_VERSION.tar.xz"
fetch "$GIT_URL" "$GIT_SHA256" "/dl/git-$GIT_VERSION.tar.xz"
# Installed into the real PREFIX inside this throwaway container, so git links against it like on the phone.
mkdir -p "$PREFIX/bin" && ln -sf /bin/sh "$PREFIX/bin/sh"
cd "$B" && tar xf "/dl/curl-$CURL_VERSION.tar.xz" && cd "curl-$CURL_VERSION"
CFLAGS="-O2" ./configure --prefix="$PREFIX" --disable-static --enable-shared \
	--with-openssl --with-nghttp2 --with-zlib --enable-ipv6 --enable-threaded-resolver \
	--with-ca-bundle="$PREFIX/etc/ssl/certs/ca-certificates.crt" --without-ca-path \
	--without-libpsl --without-brotli --without-zstd --without-libidn2 --without-librtmp --without-libssh2 \
	--without-libssh --without-gssapi --without-ngtcp2 --without-nghttp3 --disable-ldap --disable-ldaps \
	--disable-manual --disable-docs >"$B/curl-configure.log" 2>&1 || { tail -30 "$B/curl-configure.log"; exit 1; }
make -j"$(nproc)" >"$B/curl-make.log" 2>&1 || { tail -30 "$B/curl-make.log"; exit 1; }
make install >/dev/null
cp COPYING "$OUT/licenses/curl.COPYING"

cd "$B" && tar xf "/dl/git-$GIT_VERSION.tar.xz" && cd "git-$GIT_VERSION"
GIT_MAKE=(prefix="$PREFIX" sysconfdir="$PREFIX/etc" SHELL_PATH="$PREFIX/bin/sh" CURLDIR="$PREFIX" CURL_CONFIG="$PREFIX/bin/curl-config"
	CFLAGS="-O2" NO_RUST=1 NO_PERL=1 NO_PYTHON=1 NO_TCLTK=1 NO_GETTEXT=1 NO_EXPAT=1 NO_GITWEB=1 NO_INSTALL_HARDLINKS=1
	SKIP_DASHED_BUILT_INS=1 INSTALL_SYMLINKS=1 DEFAULT_PAGER=less DEFAULT_EDITOR=vi)
make -j"$(nproc)" "${GIT_MAKE[@]}" all >"$B/git-make.log" 2>&1 || { tail -30 "$B/git-make.log"; exit 1; }
make "${GIT_MAKE[@]}" install >"$B/git-install.log" 2>&1 || { tail -30 "$B/git-install.log"; exit 1; }
cp COPYING "$OUT/licenses/git.COPYING"

cp "$PREFIX/bin/curl" "$ROOT/bin/curl"
cp -L "$PREFIX/lib/libcurl.so.4" "$ROOT/lib/libcurl.so.4"
cp "$PREFIX/bin/git" "$ROOT/bin/git"
for x in git-upload-pack git-receive-pack git-upload-archive; do
	ln -s git "$ROOT/bin/$x"
done
mkdir -p "$ROOT/libexec" "$ROOT/share/git-core"
cp -a "$PREFIX/libexec/git-core" "$ROOT/libexec/"
cp -a "$PREFIX/share/git-core/templates" "$ROOT/share/git-core/"
# Not shipped: Perl/Python commands (stubs with NO_PERL/NO_PYTHON), servers (daemon, http-backend, git-shell), imap.
(cd "$ROOT/libexec/git-core" && rm -f git-archimport git-cvsexportcommit git-cvsimport git-cvsserver git-instaweb \
	git-p4 git-send-email git-svn git-daemon git-http-backend git-shell git-imap-send scalar)
# Symlinks to the main git binary (builtins) point at bin/git: one shipped copy.
for f in "$ROOT"/libexec/git-core/*; do
	if [ -d "$f" ]; then
		continue
	elif [ -L "$f" ]; then
		t=$(readlink -f "$f")
		case $t in "$PREFIX/bin/git") ln -sfn ../../bin/git "$f" ;; "$PREFIX"/libexec/git-core/*) ln -sfn "$(basename "$t")" "$f" ;; esac
	elif cmp -s "$f" "$PREFIX/bin/git"; then
		ln -sfn ../../bin/git "$f"
	fi
done
strip --strip-unneeded "$ROOT/bin/git" "$ROOT/bin/curl" "$ROOT/lib/libcurl.so.4"
for f in "$ROOT"/libexec/git-core/*; do
	if [ -f "$f" ] && [ ! -L "$f" ] && file -b "$f" | grep -q ELF; then strip --strip-unneeded "$f"; fi
done
echo "userland: $(find "$ROOT" -type f | wc -l) files, $(find "$ROOT" -type l | wc -l) links, $(du -sh "$ROOT" | cut -f1)"
