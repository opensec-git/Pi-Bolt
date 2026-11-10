#!/usr/bin/env bash
# Rebuild glibc for the app prefix with termux-pacman's Android recipe (gpkg/glibc/build.sh, ported to run outside
# Termux's build system). Runs inside the builder container (docker/Dockerfile), linux/arm64.
#   /engine  this directory's parent (read-only)   /dl  download cache   /stage  output (/stage/glibc)
# Our changes to Termux's recipe: @TERMUX_PREFIX@ and @TERMUX_PREFIX_CLASSICAL@ are both the app PREFIX, the
# pkgversion names the app, systemtap probes are off, and execve is ours (glibc/execve.c). Everything else is Termux's
# patches and files unchanged.
set -euo pipefail
. /engine/versions.env
OUT=/stage/glibc
APP_BASE_DIR=$(dirname "$PREFIX") # .../files

fetch() { # url sha256 file
	[ -f "/dl/$3" ] && echo "$2  /dl/$3" | sha256sum -c --status - && return 0
	curl -fsSL --retry 3 -o "/dl/$3.part" "$1"
	echo "$2  /dl/$3.part" | sha256sum -c --quiet -
	mv "/dl/$3.part" "/dl/$3"
}

fetch "$GLIBC_URL" "$GLIBC_SHA256" "glibc-$GLIBC_VERSION.tar.xz"
if [ ! -d "/dl/glibc-packages-$TERMUX_GLIBC_COMMIT" ]; then
	rm -rf /dl/glibc-packages.tmp && mkdir -p /dl/glibc-packages.tmp
	git -C /dl/glibc-packages.tmp init -q
	git -C /dl/glibc-packages.tmp fetch -q --depth 1 "$TERMUX_GLIBC_REPO" "$TERMUX_GLIBC_COMMIT"
	git -C /dl/glibc-packages.tmp -c advice.detachedHead=false checkout -q FETCH_HEAD
	[ "$(git -C /dl/glibc-packages.tmp rev-parse HEAD)" = "$TERMUX_GLIBC_COMMIT" ]
	mv /dl/glibc-packages.tmp "/dl/glibc-packages-$TERMUX_GLIBC_COMMIT"
fi
PKG=/dl/glibc-packages-$TERMUX_GLIBC_COMMIT/gpkg/glibc

rm -rf /b && mkdir -p /b && cd /b
tar xf "/dl/glibc-$GLIBC_VERSION.tar.xz"
SRC=/b/glibc-$GLIBC_VERSION
cd "$SRC"
for p in $(ls "$PKG"/*.patch | sort); do
	sed -e "s%@TERMUX_PREFIX_CLASSICAL@%$PREFIX%g" -e "s%@TERMUX_PREFIX@%$PREFIX%g" "$p" | patch --silent -p1
done

# Ours: execve runs scripts and programs in the app's data directory, which Android refuses to exec (glibc/execve.c).
sed -i '/^execve[[:space:]]/d' sysdeps/unix/sysv/linux/syscalls.list
sed -e "s%@PREFIX@%$PREFIX%g" -e "s%@APP_DATA@%$(dirname "$APP_BASE_DIR")%g" /engine/glibc/execve.c >sysdeps/unix/sysv/linux/execve.c

# termux_step_pre_configure (aarch64 only)
rm sysdeps/unix/sysv/linux/*/clone3.S
rm sysdeps/unix/sysv/linux/x86_64/configure*
cp "$PKG"/{shm{at,ctl,dt,get}.c,mprotect.c,syscall.c,fakesyscall*.h,fake_epoll_pwait2.c,setfs{u,g}id.c} sysdeps/unix/sysv/linux/
cp "$PKG"/{android_passwd_group.*,android_system_user_ids.h} nss/
bash "$PKG/gen-android-ids.sh" "$APP_BASE_DIR" nss/android_ids.h "$PKG/android_system_user_ids.h"
cp "$PKG/syslog.c" misc/
cp "$PKG"/shmem-android.* sysvipc/
arch=aarch64
mv sysdeps/unix/sysv/linux/$arch/syscall.S sysdeps/unix/sysv/linux/$arch/syscallS.S
h=sysdeps/unix/sysv/linux/$arch/disabled-syscall.h
{
	for j in $(jq -r '.[] | .[]' "$PKG/fakesyscall.json"); do
		grep "#define __NR_${j} " sysdeps/unix/sysv/linux/$arch/arch-syscall.h || true
		sed -i "/#define __NR_${j} /d" sysdeps/unix/sysv/linux/$arch/arch-syscall.h
	done
} >>"$h"
{
	echo -e "\n#define DISABLED_SYSCALL_WITH_FAKESYSCALL \\"
	IFS=$'\n'
	for j in $(jq -r '. | keys | .[]' "$PKG/fakesyscall.json"); do
		need_return=false
		for z in $(jq -r '."'"${j}"'" | .[]' "$PKG/fakesyscall.json"); do
			if grep -q "^#define __NR_${z} " "$h"; then
				echo -e "\tcase __NR_${z}: \\"
				need_return=true
			elif [[ ${z} =~ ^[0-9]+$ ]]; then
				echo -e "\tcase ${z}: \\"
				need_return=true
			fi
		done
		[ "$need_return" = true ] && echo -e "\t\treturn ${j}; \\"
	done
	unset IFS
} >>"$h"
sed -i '$ s| \\||' "$h"
for i in /dev/stderr:/proc/self/fd/2 /dev/stdin:/proc/self/fd/0 /dev/stdout:/proc/self/fd/1; do
	for j in $(grep -s -r -l "${i%%:*}" "$SRC"); do sed -i "s|${i%%:*}|${i//*:}|g" "$j"; done
done

mkdir -p /b/build && cd /b/build
{
	echo "slibdir=$PREFIX/lib"
	echo "rtlddir=$PREFIX/lib"
	echo "sbindir=$PREFIX/bin"
	echo "rootsbindir=$PREFIX/bin"
} >configparms
export TERMUX_ARCH=aarch64 TERMUX_REAL_ARCH=aarch64
CFLAGS="-O2 -pipe" "$SRC/configure" \
	--prefix="$PREFIX" --libdir="$PREFIX/lib" --libexecdir="$PREFIX/lib" --includedir="$PREFIX/include" \
	--host=aarch64-linux-gnu --build=aarch64-linux-gnu --target=aarch64-linux-gnu \
	--with-pkgversion="GNU libc for Android/$APP_ID" \
	--enable-bind-now --enable-fortify-source --disable-multi-arch --enable-stack-protector=strong \
	--disable-nscd --disable-profile --disable-werror --disable-default-pie \
	--enable-memory-tagging >/b/configure.log 2>&1 || { tail -40 /b/configure.log; exit 1; }
make -O -j"$(nproc)" >/b/make.log 2>&1 || { grep -n -E 'Error|error:' /b/make.log | head -40; tail -40 /b/make.log; exit 1; }
make install DESTDIR=/b/dest >/b/install.log 2>&1 || { tail -40 /b/install.log; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT/lib"
# Every runtime shared object (real files, by soname). The pack ships only the ones some shipped ELF needs.
for f in /b/dest"$PREFIX"/lib/*.so.*; do
	case $(basename "$f") in *.so.*[!0-9.]*) continue ;; esac
	cp -L "$f" "$OUT/lib/"
done
chmod 0755 "$OUT"/lib/*
strip --strip-debug "$OUT"/lib/*
ls -la "$OUT/lib"
