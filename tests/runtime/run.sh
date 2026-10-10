#!/usr/bin/env bash
# Tests of the Pi-Bolt runtime itself (the patched Bun), for what Pi and its extensions rely on:
#   stack       Error.captureStackTrace() and the default Error.prepareStackTrace on objects that are not Errors
#   keepalive   a connection waits in fetch's keep-alive pool for 4 seconds, or for as long as the server's Keep-Alive header
#               says less 2 seconds, and one that has waited longer is not used again: a connection that went dead while it
#               waited (no FIN, no RST) is not what the next request is written to
#   workdir     a compiled executable's embedded code resolves nothing in the directory it is started in, where a repository
#               could supply a package or a native module for it to run; absolute paths and built-in modules still resolve
# Usage: tests/runtime/run.sh        Environment: PIBOLT_BUN (the runtime; default $PIBOLT_WORK/runtime/bun);
#   PIBOLT_RUNTIME_OLDER=1 skips workdir (CI, while the released runtime is an older engine)
source "$(dirname "$0")/../../scripts/lib/common.sh"
set +e
BUN="$(runtime_bun)"
cd "$(dirname "$0")" || exit 1
status=0
check() { # check NAME EXPECTED ACTUAL
	if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected '$2', got '$3'"; status=1; fi
}

if [ "$("$BUN" stack.mjs 2>&1)" = "$(cat stack.expected)" ]; then echo "PASS stack"; else echo "FAIL stack"; "$BUN" stack.mjs 2>&1 | diff stack.expected - | head -5; status=1; fi

if [ -n "${PIBOLT_RUNTIME_OLDER:-}" ]; then
	echo "SKIP workdir: the runtime is an older engine than this commit's"
else
workdir=$(mktemp -d)
# Built as Pi is (scripts/build-pi.sh): package.json files are read, so a planted package with a "main" would resolve too.
"$BUN" build --compile --compile-autoload-package-json workdir/app.mjs --outfile "$workdir/app" >/dev/null
mkdir -p "$workdir/repo/node_modules/planted" "$workdir/repo/node_modules/planted-pkg/lib"
echo 'module.exports = "PLANTED";' > "$workdir/repo/node_modules/planted/index.js"
echo '{"name": "planted-pkg", "main": "lib/main.mjs"}' > "$workdir/repo/node_modules/planted-pkg/package.json"
echo 'export default "PLANTED";' > "$workdir/repo/node_modules/planted-pkg/lib/main.mjs"
echo 'export default "absolute";' > "$workdir/repo/planted-file.mjs"
check "workdir: embedded code resolves nothing in the working directory" \
	"embedded=ok bare=not-found package=not-found relative=not-found require=not-found resolve=not-found paths=found builtin=function node-builtin=function absolute=absolute" \
	"$(cd "$workdir/repo" && "$workdir/app" 2>&1)"
rm -rf "$workdir"
fi

port() { python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
servers=()
serve() { # serve PORT ARGS...: starts a server and waits for it to listen
	python3 keepalive-server.py "$@" &
	servers+=($!)
	for _ in $(seq 50); do (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return; sleep 0.1; done
}
trap 'kill "${servers[@]}" 2>/dev/null' EXIT

plain=$(port); serve "$plain"
# Used again within 4 seconds; a new connection after 7.
check "keepalive: reuse, then a new connection" "1 1 1 2 2" "$("$BUN" keepalive.mjs "http://127.0.0.1:$plain/" 300 1500 7000 300)"
# With the default raised, the same idle time keeps the connection.
check "keepalive: BUN_CONFIG_HTTP_KEEPALIVE_TIMEOUT" "1 1" "$(BUN_CONFIG_HTTP_KEEPALIVE_TIMEOUT=30 "$BUN" keepalive.mjs "http://127.0.0.1:$plain/" 7000)"
hinted=$(port); serve "$hinted" --hint 12
# The server keeps it for 12 seconds: used again after 7, not after 11.
check "keepalive: the server's Keep-Alive timeout" "1 1 2" "$("$BUN" keepalive.mjs "http://127.0.0.1:$hinted/" 7000 11000)"
silent=$(port); serve "$silent" --silent-after 5
# The connection is dead after 5 idle seconds and nothing says so: the request after 7 must not wait on it.
check "keepalive: a connection that went dead while idle" "1 2" "$("$BUN" keepalive.mjs "http://127.0.0.1:$silent/" 7000)"
exit $status
