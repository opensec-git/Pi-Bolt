#!/bin/sh
# Inside the Alpine container of verify-pack.sh. $1 = nativeLibraryDir, $2 = --engine-only or empty.
NLD=$1
MODE=$2
DATA=/data/data/in.opensec.pibolt
HOMEDIR=$DATA/files/home
PKGDIR=$DATA/files/pkg
. /setup.sh
PREFIX=$(sed -n 's/.*"prefix": "\(.*\)",/\1/p' /apk-assets/engine/manifest.json)
# Android stand-ins: /system/bin/sh (mksh there, busybox ash here), DNS from the network, side files copied.
mkdir -p /system/bin && ln -sf /bin/sh /system/bin/sh
cp /etc/resolv.conf "$PREFIX/etc/resolv.conf"
printf '127.0.0.1 localhost\n::1 localhost\n' >"$PREFIX/etc/hosts"
mkdir -p "$HOMEDIR/projects" "$PKGDIR"
cp -r /apk-assets/engine/package/. "$PKGDIR/"
cd "$HOMEDIR/projects" || exit 1

E() { # run with the app's environment only
	env -i $ENGINE_ENV HOME="$HOMEDIR" TZ=UTC PI_PACKAGE_DIR="$PKGDIR" "$@"
}
FAIL=0
check() { # name command...: run, print output, record failure
	name=$1
	shift
	echo "--- $name"
	if out=$("$@" 2>&1); then
		printf '%s\n' "$out" | head -n 12
		echo "PASS $name"
	else
		printf '%s\n' "$out" | tail -n 20
		echo "FAIL $name"
		FAIL=1
	fi
}
ms() { awk '{printf "%d", $1 * 1000}' /proc/uptime; }

echo "nativeLibraryDir $NLD; prefix $PREFIX; system: $(ls /lib/ld-* 2>/dev/null) (no glibc); /bin/bash: $(ls /bin/bash 2>&1 | tail -c 30)"
t0=$(ms)
check "engine --version" E "$NLD/libpibolt.so" --version
echo "time: $(($(ms) - t0)) ms (first run)"
t0=$(ms)
E "$NLD/libpibolt.so" --version >/dev/null
echo "time: $(($(ms) - t0)) ms (warm)"
t_loader() {
	env -i LD_DEBUG=libs "$NLD/libpibolt.so" --version >/tmp/ld.txt 2>&1
	if grep -E 'calling init|trying file' /tmp/ld.txt | grep -v "$PREFIX/lib/"; then return 1; fi
	grep 'calling init' /tmp/ld.txt | sed 's/^ *[0-9]*: *//'
}
check "loader resolves libraries from PREFIX/lib only" t_loader
t_aot() { ! E "$NLD/libpibolt.so" --version 2>&1 | grep 'ahead-of-time compiled code is not used'; }
check "AOT code in use (no fallback notice)" t_aot

if [ "$MODE" = --engine-only ]; then
	BASH_CMD='echo hi from $0'
else
	BASH_CMD='echo shell=$BASH_VERSION && command -v git rg fd ls && git --version'
fi
t_rpc() {
	{
		printf '%s\n' '{"id":"1","type":"get_state"}'
		printf '%s\n' "{\"id\":\"2\",\"type\":\"bash\",\"command\":\"$BASH_CMD\"}"
		sleep 6
	} | timeout 60 env -i $ENGINE_ENV HOME="$HOMEDIR" TZ=UTC PI_PACKAGE_DIR="$PKGDIR" "$NLD/libpibolt.so" \
		--mode rpc --no-session >/tmp/rpc.txt 2>/tmp/rpc.err
	cut -c1-300 /tmp/rpc.txt
	cat /tmp/rpc.err
	grep -q '"command":"get_state","success":true' /tmp/rpc.txt && grep -q '"command":"bash","success":true' /tmp/rpc.txt
}
t0=$(ms)
check "rpc get_state + bash" t_rpc
echo "time: $(($(ms) - t0)) ms (includes a fixed 6 s wait before stdin closes)"
grep -o '"output":"[^"]*"' /tmp/rpc.txt | head -3

if [ "$MODE" != --engine-only ]; then
	check "bash -c" E bash -c 'echo "bash $BASH_VERSION"; set -o pipefail; printf "a\nb\n" | wc -l; [[ $(echo x) == x ]] && echo ok'
	check "sh is bash" E sh -c 'echo "$BASH_VERSION"'
	check "busybox applets" E bash -c '
		set -e
		cd "$(mktemp -d)"
		printf "b\na\nc\n" >f.txt
		ls -la f.txt; sort f.txt | head -1; sed -n 2p f.txt; awk "NR==3" f.txt; grep -c a f.txt
		find . -name "*.txt" | xargs cat | wc -l
		tar czf f.tgz f.txt && tar tzf f.tgz; gzip -k f.txt && gunzip -c f.txt.gz | wc -c
		printf "a\nb\n" >g.txt; diff f.txt g.txt || true
		echo zip; unzip 2>&1 | head -1; less --help 2>&1 | head -1; vi --help 2>&1 | head -1
		ls "'"$PREFIX"'/bin" | wc -l'
	check "git clone over https" E bash -c "cd '$HOMEDIR/projects' && rm -rf Hello-World && git clone -q https://github.com/octocat/Hello-World && git -C Hello-World log --oneline -1 && git -C Hello-World status --short --branch"
	check "git commit + hooks dir + template" E bash -c "cd '$HOMEDIR/projects' && rm -rf t && git init -q t && cd t && ls .git/hooks | head -3 && echo x >x && git add x && git -c user.name=t -c user.email=t@t commit -qm init && git log --oneline && git diff HEAD~0 --stat"
	check "rg" E bash -c "rg --version | head -1 && rg -n 'Hello' '$HOMEDIR/projects/Hello-World' && rg --files '$HOMEDIR/projects/Hello-World' | wc -l"
	check "fd" E bash -c "fd --version && fd -H . '$HOMEDIR/projects/Hello-World' --max-depth 1 | head -5"
	check "curl https" E bash -c "curl -sS -o /dev/null -w '%{http_code} %{ssl_verify_result}\n' https://github.com/"
	check "terminfo" E bash -c "ls '$PREFIX/share/terminfo'/x/xterm-256color && TERM=xterm-256color bash -ic 'exit' </dev/null"
	check "git alias, submodule script, sh shebang" E bash -c "cd '$HOMEDIR/projects/t' && git -c alias.hi='!echo alias via \$0' hi && git submodule status && head -1 '$PREFIX/libexec/git-core/git-submodule'"
	t_noenv() { # paths compiled in: no GIT_*, SSL_CERT_FILE or TERMINFO needed
		env -i PATH="$PREFIX/bin:/system/bin" HOME="$HOMEDIR" TMPDIR="$PREFIX/tmp" bash -c "
			cd '$HOMEDIR/projects' && rm -rf hw2 && git clone -q https://github.com/octocat/Hello-World hw2 && git -C hw2 rev-parse --short HEAD
			curl -sS -o /dev/null -w 'curl %{http_code}\n' https://github.com/
			git --exec-path; git var GIT_SHELL_PATH 2>/dev/null || true"
	}
	check "git/curl without the env vars" t_noenv
fi
echo "=== $([ $FAIL = 0 ] && echo ALL PASS || echo SOME FAILED)"
exit $FAIL
