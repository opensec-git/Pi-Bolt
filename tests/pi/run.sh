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
		grep -m3 -i "^$name failed\|segmentation\|panic\|error" <<<"$last" | cut -c1-600 | sed 's/^/   /'
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

# #331: every module of an extension gets Pi's own modules by the names Pi gives them, not an older copy of Pi's package that
# npm or bun installed beside it: the JavaScript modules jiti hands to the runtime as they are too (an ES module of a "type":
# "module" package, imported statically or with import(); a .cjs, required), in node_modules or not.
sdk_case() { # FOLDER: a package folder with an older @earendil-works/pi-coding-agent in a node_modules above it
	local ext=$1
	mkdir -p "$ext/dist"
	printf '{"name": "sdk-alias", "type": "module"}\n' >"$ext/package.json"
	printf 'import { VERSION } from "@earendil-works/pi-coding-agent";\nexport { VERSION };\n' >"$ext/dist/static.js"
	printf 'export const VERSION = (await import("@earendil-works/pi-coding-agent")).VERSION;\n' >"$ext/dist/dynamic.js"
	printf 'exports.VERSION = require("@earendil-works/pi-coding-agent").VERSION;\n' >"$ext/dist/required.cjs"
	cat >"$ext/sdk-alias.ts" <<'TS'
import { createRequire } from "node:module";
import { VERSION } from "@earendil-works/pi-coding-agent";
import * as staticJs from "./dist/static.js";
const dynamicJs = await import("./dist/static.js");
const nested = await import("./dist/dynamic.js");
const requiredCjs = createRequire(import.meta.url)("./dist/required.cjs");
export default function () {
	console.log(
		`sdk-alias: host=${VERSION} static=${staticJs.VERSION} dynamic=${dynamicJs.VERSION} nested=${nested.VERSION} require=${requiredCjs.VERSION}`,
	);
	process.exit(0);
}
TS
}
old_sdk() { # NODE_MODULES: an @earendil-works/pi-coding-agent 0.0.1 in it, for import and for require
	local sdk="$1/@earendil-works/pi-coding-agent"
	mkdir -p "$sdk"
	printf '{"name": "@earendil-works/pi-coding-agent", "version": "0.0.1", "type": "module", "exports": {".": {"import": "./index.js", "require": "./index.cjs"}}}\n' >"$sdk/package.json"
	printf 'export const VERSION = "0.0.1";\n' >"$sdk/index.js"
	printf 'exports.VERSION = "0.0.1";\n' >"$sdk/index.cjs"
}
sdk_check() { # NAME FOLDER
	local name=$1 ext=$2 out host
	out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
		"$PI" -ne -e "$ext/sdk-alias.ts" --offline --no-session -p hi 2>&1 </dev/null)
	host=$(sed -n 's/^sdk-alias: host=\([^ ]*\) .*/\1/p' <<<"$out")
	if [ -n "$host" ] && [ "$host" != 0.0.1 ] &&
		grep -q "^sdk-alias: host=$host static=$host dynamic=$host nested=$host require=$host$" <<<"$out"; then
		echo "PASS $name"
	else
		echo "FAIL $name"
		grep -m3 "sdk-alias\|error\|rror:" <<<"$out" | cut -c1-300 | sed 's/^/   /'
		status=1
	fi
}
sdk="$home/extensions/sdk-alias"
old_sdk "$sdk/node_modules"
sdk_case "$sdk/node_modules/sdk-alias"
sdk_check "an extension's modules in node_modules get Pi's SDK, not the copy beside them (#331)" "$sdk/node_modules/sdk-alias"
sdk_case "$sdk/local"
sdk_check "an extension's modules outside node_modules get Pi's SDK, not the copy above them (#331)" "$sdk/local"
# #331: a library the extension uses keeps its own @sinclair/typebox (npm put one of another version beside it), as on Pi on
# Node, with import and with require; its imports of Pi's own packages get Pi's. The extension's own modules, imported, which
# find no @sinclair/typebox on disk, get Pi's (jiti loads them transformed then, as on Pi on Node).
lib="$sdk/node_modules/third-lib"
mkdir -p "$lib/node_modules/@sinclair/typebox"
printf '{"name": "@sinclair/typebox", "version": "0.34.0", "type": "module", "exports": {".": {"import": "./index.js", "require": "./index.cjs"}}}\n' \
	>"$lib/node_modules/@sinclair/typebox/package.json"
printf 'export const Origin = "0.34";\n' >"$lib/node_modules/@sinclair/typebox/index.js"
printf 'exports.Origin = "0.34";\n' >"$lib/node_modules/@sinclair/typebox/index.cjs"
printf '{"name": "third-lib", "type": "module", "exports": {".": {"import": "./esm.js", "require": "./cjs.cjs"}}}\n' >"$lib/package.json"
printf 'import * as tb from "@sinclair/typebox";\nexport { VERSION } from "@earendil-works/pi-coding-agent";\nexport const typebox = tb.Origin ?? "pi";\n' >"$lib/esm.js"
printf 'exports.typebox = require("@sinclair/typebox").Origin ?? "pi";\nexports.VERSION = require("@earendil-works/pi-coding-agent").VERSION;\n' >"$lib/cjs.cjs"
own="$sdk/node_modules/sdk-alias"
printf 'import * as tb from "@sinclair/typebox";\nexport const typebox = tb.Origin ?? (typeof tb.Type?.Object === "function" ? "pi" : "?");\n' >"$own/dist/typebox.js"
printf 'const tb = require("@sinclair/typebox");\nexports.typebox = tb.Origin ?? (typeof tb.Type?.Object === "function" ? "pi" : "?");\n' >"$own/dist/typebox.cjs"
cat >"$own/libs.ts" <<'TS'
import { createRequire } from "node:module";
import { VERSION } from "@earendil-works/pi-coding-agent";
import * as esm from "third-lib";
import * as own from "./dist/typebox.js";
import ownCjs from "./dist/typebox.cjs";
const cjs = createRequire(import.meta.url)("third-lib");
const sdk = (version: string) => (version === VERSION ? "pi" : version);
export default function () {
	console.log(
		`sdk-libs: lib-esm=${esm.typebox},${sdk(esm.VERSION)} lib-cjs=${cjs.typebox},${sdk(cjs.VERSION)} own=${own.typebox},${ownCjs.typebox}`,
	);
	process.exit(0);
}
TS
out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" -ne -e "$own/libs.ts" --offline --no-session -p hi 2>&1 </dev/null)
if grep -q "^sdk-libs: lib-esm=0.34,pi lib-cjs=0.34,pi own=pi,pi$" <<<"$out"; then
	echo "PASS a library keeps its own typebox and gets Pi's own packages; the extension's modules get Pi's typebox (#331)"
else
	echo "FAIL a library keeps its own typebox and gets Pi's own packages; the extension's modules get Pi's typebox (#331)"
	grep -m3 "sdk-libs\|rror" <<<"$out" | cut -c1-300 | sed 's/^/   /'
	status=1
fi
# #331: a library that imports pi-ai and one of the subpaths Pi has (session-resources) gets both from Pi, not the root from Pi
# and the subpath from the copy beside it. A subpath Pi does not give (utils/*) comes from that copy.
ai="$sdk/node_modules/@earendil-works/pi-ai"
mkdir -p "$ai/utils"
printf '{"name": "@earendil-works/pi-ai", "version": "0.0.1", "type": "module", "exports": {".": "./index.js", "./session-resources": "./session-resources.js", "./utils/*": "./utils/*.js"}}\n' >"$ai/package.json"
for module in index session-resources utils/retry; do printf 'export const Origin = "0.0.1";\n' >"$ai/$module.js"; done
sub="$sdk/node_modules/sub-lib"
mkdir -p "$sub"
printf '{"name": "sub-lib", "type": "module", "main": "index.js"}\n' >"$sub/package.json"
cat >"$sub/index.js" <<'JS'
import * as root from "@earendil-works/pi-ai";
import * as resources from "@earendil-works/pi-ai/session-resources";
import * as retry from "@earendil-works/pi-ai/utils/retry";
export const origins = [root, resources, retry].map((module) => module.Origin ?? "pi").join(",");
JS
cat >"$own/subpaths.ts" <<'TS'
import { origins } from "sub-lib";
export default function () {
	console.log(`sdk-subpaths: ${origins}`);
	process.exit(0);
}
TS
out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" -ne -e "$own/subpaths.ts" --offline --no-session -p hi 2>&1 </dev/null)
if grep -q "^sdk-subpaths: pi,pi,0.0.1$" <<<"$out"; then
	echo "PASS a library gets pi-ai and the subpaths Pi has from Pi, other subpaths from disk (#331)"
else
	echo "FAIL a library gets pi-ai and the subpaths Pi has from Pi, other subpaths from disk (#331)"
	grep -m3 "sdk-subpaths\|rror" <<<"$out" | cut -c1-300 | sed 's/^/   /'
	status=1
fi
# (A require() cannot wait for Pi's modules to load: one before any import gets them too.)
cat >"$sdk/local/require-first.ts" <<'TS'
import { createRequire } from "node:module";
export default function () {
	console.log(`require-first: ${createRequire(import.meta.url)("./dist/required.cjs").VERSION}`);
	process.exit(0);
}
TS
out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" -ne -e "$sdk/local/require-first.ts" --offline --no-session -p hi 2>&1 </dev/null)
if grep -q "^require-first: [0-9]" <<<"$out" && ! grep -q "^require-first: 0\.0\.1$" <<<"$out"; then
	echo "PASS a require() of Pi's SDK before any import gets Pi's (#331)"
else
	echo "FAIL a require() of Pi's SDK before any import gets Pi's (#331)"
	grep -m3 "require-first\|rror" <<<"$out" | cut -c1-300 | sed 's/^/   /'
	status=1
fi

# Without the JIT, a package that works only through bun:ffi does not load, so that its extension takes its Node.js alternative
# (pi-fff: @ff-labs/fff-bun, then @ff-labs/fff-node); with the JIT it loads.
ffi="$home/extensions/jit-only"
mkdir -p "$ffi/node_modules/@ff-labs/fff-bun" "$ffi/node_modules/@ff-labs/fff-node"
printf '{"name": "@ff-labs/fff-bun", "main": "index.js"}\n' >"$ffi/node_modules/@ff-labs/fff-bun/package.json"
printf 'module.exports = { backend: "bun" };\n' >"$ffi/node_modules/@ff-labs/fff-bun/index.js"
printf '{"name": "@ff-labs/fff-node", "main": "index.js"}\n' >"$ffi/node_modules/@ff-labs/fff-node/package.json"
printf 'module.exports = { backend: "node" };\n' >"$ffi/node_modules/@ff-labs/fff-node/index.js"
cat >"$ffi/jit-only.ts" <<'TS'
async function backend(): Promise<string> {
	try {
		return (await import("@ff-labs/fff-bun")).backend;
	} catch {
		return (await import("@ff-labs/fff-node")).backend;
	}
}
export default async function () {
	console.log(`jit-only: ${await backend()}`);
	process.exit(0);
}
TS
want=node; "$PI" --version | grep -q "JIT on" && want=bun
out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" -ne -e "$ffi/jit-only.ts" --offline --no-session -p hi 2>&1 </dev/null)
if grep -q "^jit-only: $want" <<<"$out"; then
	echo "PASS a package that needs bun:ffi: the $want backend"
else
	echo "FAIL a package that needs bun:ffi: expected the $want backend"
	grep -m3 -i "jit-only\|error" <<<"$out" | cut -c1-200 | sed 's/^/   /'
	status=1
fi

# An extension that needs the JIT (bun:ffi's dlopen): without it, the error says which extension and how to switch to the JIT
# build; with it, the extension loads. `pi-bolt doctor` reports it the same way, and the rest of the setup.
needs="$home/agent/extensions/needs-jit"
mkdir -p "$needs"
printf '{"name": "needs-jit", "version": "1.0.0"}\n' >"$needs/package.json"
cat >"$needs/index.ts" <<'TS'
import { dlopen } from "bun:ffi";
export default function () {
	dlopen(process.platform === "darwin" ? "libc.dylib" : "libc.so.6", { getpid: { returns: "i32", args: [] } });
	console.log("needs-jit: loaded");
}
TS
jit_on=""; "$PI" --version | grep -q "JIT on" && jit_on=1
out=$(env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" -ne -e "$needs/index.ts" --offline --no-session -p hi 2>&1 </dev/null)
if { [ -n "$jit_on" ] && grep -q "^needs-jit: loaded" <<<"$out"; } ||
	{ [ -z "$jit_on" ] && grep -q "bun:ffi requires the JIT\. needs-jit needs Pi-Bolt's JIT build (bun:ffi); switch with: .*PIBOLT_VARIANT=" <<<"$out"; }; then
	echo "PASS an extension that needs the JIT ($([ -n "$jit_on" ] && echo "loads" || echo "says so"))"
else
	echo "FAIL an extension that needs the JIT"
	grep -m3 -i "needs-jit\|error" <<<"$out" | cut -c1-300 | sed 's/^/   /'
	status=1
fi
out=$(cd "$home" && env -i HOME="$home" PATH=/usr/bin:/bin PI_CODING_AGENT_DIR="$home/agent" DO_NOT_TRACK=1 BUN_ENABLE_CRASH_REPORTING=0 \
	"$PI" doctor --offline 2>&1 </dev/null)
doctor_status=$?
real_home=$(cd "$home" && pwd -P)
if [ "$doctor_status" = 0 ] && grep -q "^Pi-Bolt doctor" <<<"$out" && grep -q "^  compiled code  *used" <<<"$out" &&
	{ { [ -n "$jit_on" ] && grep -q "run time  *~/agent/extensions/needs-jit/index.ts: JIT, loaded" <<<"$out"; } ||
		{ [ -z "$jit_on" ] && grep -q "needs-jit needs Pi-Bolt's JIT build" <<<"$out"; }; } &&
	! grep -q "$home\|$real_home" <<<"$out"; then
	echo "PASS pi-bolt doctor"
else
	echo "FAIL pi-bolt doctor (exit $doctor_status)"
	head -c 3000 <<<"$out" | sed 's/^/   /'
	status=1
fi
rm -rf "$needs"

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

# Pi-Bolt's grep and find tools, in-process: on by default, and not with FFF loaded.
python3 search-tools.py "$PI" || status=1
exit $status
