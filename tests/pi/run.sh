#!/usr/bin/env bash
# Tests that need a built Pi (Pi's own ahead-of-time compiled code under an extension), not just the engine: crashes, and
# extensions that failed to load.
#
# Usage: tests/pi/run.sh [PI]     (default: out/pi-bolt/pi)
# Each test runs several times: the crashes they guard against depended on when the collector ran.
cd "$(dirname "$0")" || exit 1
PI="${1:-../../out/pi-bolt/pi}"
[ -x "$PI" ] || { echo "no Pi executable at $PI (scripts/build-pi.sh)"; exit 1; }
home=$(mktemp -d)
trap 'rm -rf "$home"' EXIT
# The extensions run from a directory of their own, as a user's do (~/.pi/agent/extensions): from this repository, a package
# with "type": "module" and node_modules, jiti would import them as they are rather than transform them.
mkdir -p "$home/extensions" && cp ./*.js "$home/extensions/"
status=0
run() {
	local name=$1 runs=$2; shift 2
	local failed=0 out
	for _ in $(seq "$runs"); do
		out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
			"$@" "$PI" -ne -e "$home/extensions/$name.js" --offline --no-session -p hi 2>&1 </dev/null)
		[ $? -eq 0 ] && grep -q "^$name: " <<<"$out" && ! grep -q "Failed to load extension" <<<"$out" || { failed=$((failed + 1)); last=$out; }
	done
	if [ "$failed" = 0 ]; then
		echo "PASS $name ($runs runs${*:+, $*})"
	else
		echo "FAIL $name: $failed of $runs runs${*:+ ($*)}"
		grep -m3 -i "segmentation\|panic\|error" <<<"$last" | sed 's/^/   /'
		status=1
	fi
}
run gc-end-stacks 5
run gc-end-stacks 5 BUN_JSC_collectContinuously=1
# (The first run transforms the extension; the others take it from the cache, which is the agent's own.)
run capture-stack 3
if ls "$home/agent/cache/jiti"/*capture-stack* >/dev/null 2>&1; then
	echo "PASS the transformed extension is kept in the agent directory"
else
	echo "FAIL the transformed extension is not in $home/agent/cache/jiti"
	status=1
fi
run builtin-modules 2
# An extension package's own dependencies resolve as on Node, through their package.json ("exports" here, as sharp finds its
# native module), with import and with require.
pkg="$home/extensions/package-deps"
mkdir -p "$pkg/node_modules/dep/lib"
printf '{"name": "dep", "exports": {".": {"require": "./lib/main.cjs"}}}\n' >"$pkg/node_modules/dep/package.json"
printf 'module.exports = { answer: 42 };\n' >"$pkg/node_modules/dep/lib/main.cjs"
cat >"$pkg/package-deps.ts" <<'TS'
import { createRequire } from "node:module";
import { answer } from "dep";
const require = createRequire(import.meta.url);
export default function () {
	console.log(`package-deps: import=${answer} require=${require("dep").answer}`);
	process.exit(0);
}
TS
out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" -ne -e "$pkg/package-deps.ts" --offline --no-session -p hi 2>&1 </dev/null)
if grep -q "^package-deps: import=42 require=42" <<<"$out"; then
	echo "PASS an extension's own packages resolve through their package.json"
else
	echo "FAIL an extension's own packages resolve through their package.json"
	grep -m3 -i "error\|cannot" <<<"$out" | cut -c1-200 | sed 's/^/   /'
	status=1
fi

# Programs started from Pi (on macOS through pi-spawn). The probe says where its code and stack are.
probe=
printf '#include <stdio.h>\nint main(void) { int x; printf("%%p/%%p\\n", (void*)main, (void*)&x); return 0; }\n' >"$home/probe.c"
cc -O1 -o "$home/probe" "$home/probe.c" 2>/dev/null && probe="$home/probe" || echo "(no C compiler: the address checks are skipped)"
run child-processes 3 ${probe:+PIBOLT_TEST_PROBE=$probe}

# Bedrock, the proxy agents and the OAuth flows are loaded when they are first used, with the builtin modules they import
# (node:http, https, net, tls). A request to Bedrock that can reach nothing (its endpoint and the proxy are a closed port of this
# machine) has to fail as a connection that is refused, not as code that is missing or broken.
bedrock() {
	local name=$1 out; shift
	out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 PI_OFFLINE=1 \
		AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1 "$@" \
		"$PI" -ne --no-session --provider amazon-bedrock --model amazon.nova-micro-v1:0 -p hi 2>&1 </dev/null)
	if grep -q "ECONNREFUSED\|ConnectionRefused\|onnection refused" <<<"$out" && ! grep -qi "is not defined\|cannot find module\|is not a function\|is not a constructor\|panic" <<<"$out"; then
		echo "PASS $name"
	else
		echo "FAIL $name"
		tail -3 <<<"$out" | cut -c1-200 | sed 's/^/   /'
		status=1
	fi
}
bedrock "Bedrock loads (the request is refused)" AWS_ENDPOINT_URL_BEDROCK_RUNTIME=http://127.0.0.1:1
bedrock "Bedrock loads behind a proxy (the proxy refuses)" AWS_ENDPOINT_URL_BEDROCK_RUNTIME=https://127.0.0.1:1 HTTPS_PROXY=http://127.0.0.1:1 https_proxy=http://127.0.0.1:1
exit $status
