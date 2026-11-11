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
	BASH_CMD='echo shell=$BASH_VERSION && command -v git rg fd ls node python3 && git --version && node --version && python3 --version'
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

# The bundled extensions load (manifest extensions[], settings.json packages): pi-web-access registers /websearch, and
# nothing fails to load. (Their tools only show in a prompt's tool list: android/e2e covers those.)
t_extensions() {
	{
		printf '%s\n' '{"id":"1","type":"get_commands"}'
		sleep 8
	} | timeout 60 env -i $ENGINE_ENV HOME="$HOMEDIR" TZ=UTC PI_PACKAGE_DIR="$PKGDIR" "$NLD/libpibolt.so" \
		--mode rpc --no-session >/tmp/ext.txt 2>/tmp/ext.err
	grep -o '"name":"[^"]*","description":"[^"]\{0,40\}' /tmp/ext.txt | head -20
	cat /tmp/ext.err | head -20
	! grep -qi 'failed to load' /tmp/ext.txt /tmp/ext.err && grep -q '"name":"websearch"' /tmp/ext.txt
}
check "bundled extensions load (subagents, todo, web access, ask_user_question)" t_extensions

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

	# Language runtimes, with the app's extra environment (EngineEnvironment.kt): user bins first, caches in the cache dir.
	CACHE=$DATA/cache/xdg
	R() {
		env -i $ENGINE_ENV HOME="$HOMEDIR" TZ=UTC PI_PACKAGE_DIR="$PKGDIR" PATH="$HOMEDIR/.local/bin:$PREFIX/bin:/system/bin" \
			XDG_CACHE_HOME="$CACHE" npm_config_cache="$CACHE/npm" "$@"
	}
	check "node -e" R node -e 'console.log(process.version, process.arch, process.execPath); require("crypto").createHash("sha256"); new Intl.DateTimeFormat("en").format(0)'
	check "npm --version, npx --version" R bash -c 'npm --version && npx --version && npm config get prefix && npm config get cache'
	check "npm install over https, a bin script in node_modules (npm run, npx, npm exec)" R bash -c "
		set -e
		rm -rf '$HOMEDIR/projects/nodeapp' && mkdir -p '$HOMEDIR/projects/nodeapp' && cd '$HOMEDIR/projects/nodeapp'
		npm init -y >/dev/null
		npm pkg set scripts.v='semver 1.2.3 -i minor'
		npm install --no-audit --no-fund semver@7.6.3 is-number@7.0.0
		node -e 'console.log(require(\"is-number\")(5), require(\"semver\").valid(\"1.2.3\"))'
		head -1 node_modules/.bin/semver
		npm run -s v
		npx --no-install semver 2.0.0
		npm exec -- semver -r '>=1' 1.0.0"
	check "npm global install (HOME/.local)" R bash -c "npm install -g --no-audit --no-fund semver@7.6.3 >/dev/null && command -v semver && semver 3.0.0 && ls '$HOMEDIR/.local/lib/node_modules'"
	check "python3 -c 'import ssl, sqlite3'" R python3 -c 'import ssl, sqlite3, ctypes, lzma, bz2, zlib, decimal, uuid, json, sys; print(sys.version.split()[0], sys.prefix, ssl.OPENSSL_VERSION, sqlite3.sqlite_version); sqlite3.connect(":memory:").execute("select 1"); print(ctypes.CDLL(None))'
	check "pip --version" R bash -c 'pip --version && pip3 --version && python3 -m pip --version'
	check "python https (CA bundle)" R python3 -c 'import urllib.request; print(urllib.request.urlopen("https://pypi.org/simple/six/").status)'
	check "pip install over https (user site) + console script" R bash -c "
		set -e
		pip install --disable-pip-version-check -q six==1.16.0 cowsay==6.1
		python3 -c 'import six, site; print(six.__file__); print(site.USER_SITE)'
		command -v cowsay && head -1 \"\$(command -v cowsay)\" && cowsay -t hi | tail -2
		ls '$CACHE/pip' | head -3"
	check "python venv + pip inside it" R bash -c "
		set -e
		cd '$HOMEDIR/projects' && rm -rf venvtest && python3 -m venv venvtest
		venvtest/bin/python -c 'import sys; print(sys.prefix, sys.base_prefix)'
		venvtest/bin/pip --version
		venvtest/bin/pip install --disable-pip-version-check -q six==1.16.0 && venvtest/bin/python -c 'import six; print(six.__file__)'"
	# The exec emulation of glibc/execve.c (on a phone the kernel refuses exec in app data; here it would allow it).
	# Started from bash: the container's own env(1) is musl, which has no emulation.
	t_exec_shim() {
		set -e
		d=$HOMEDIR/execshim && rm -rf "$d" && mkdir -p "$d"
		cp "$(readlink -f "$PREFIX/bin/busybox")" "$d/busybox" && chmod +x "$d/busybox"
		exe=$(R bash -c "'$d/busybox' readlink /proc/self/exe")
		echo "ELF in app data runs via the loader: /proc/self/exe = $exe"
		case $exe in */libld.so) ;; *) return 1 ;; esac
		printf '#!/bin/sh\necho "sh=$BASH_VERSION"\n' >"$d/s1" && chmod +x "$d/s1"
		out=$(R bash -c "'$d/s1'") && echo "#!/bin/sh script runs PREFIX/bin/sh (bash): $out"
		[ "$out" != "sh=" ]
		printf '#!/usr/bin/env python3\nimport sys; print("env python3 ->", sys.executable)\n' >"$d/s2" && chmod +x "$d/s2"
		R bash -c "'$d/s2'"
		printf '#!/usr/bin/env node\nconsole.log("env node ->", process.version)\n' >"$d/s3" && chmod +x "$d/s3"
		R bash -c "cd '$d' && ./s3"
		printf 'echo no shebang: run by the shell\n' >"$d/s4" && chmod +x "$d/s4"
		R bash -c "'$d/s4'"
		chmod -x "$d/s1"
		if R bash -c "'$d/s1'" 2>/dev/null; then echo "non-executable file ran"; return 1; fi
		mkfifo "$d/fifo" && chmod +x "$d/fifo"
		# (Not from bash: on EACCES bash itself reads the file to explain why, which blocks on a FIFO anywhere.)
		timeout 10 env -i $ENGINE_ENV "$PREFIX/bin/python3" -c "
import subprocess
try:
    subprocess.run(['$d/fifo'])
    print('a FIFO ran')
except PermissionError as e:
    print('a FIFO is refused:', e.strerror)" | tee /tmp/fifo.txt
		grep -q refused /tmp/fifo.txt
		R python3 -c "import subprocess; print(subprocess.run(['$d/s2'], capture_output=True, text=True).stdout.strip())"
		R node -e "console.log(require('child_process').execFileSync('$d/s2').toString().trim())"
	}
	check "exec emulation for app data (glibc execve)" t_exec_shim
fi
echo "=== $([ $FAIL = 0 ] && echo ALL PASS || echo SOME FAILED)"
exit $FAIL
