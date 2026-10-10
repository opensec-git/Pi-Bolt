#!/usr/bin/env bash
# Builds Pi as a single executable with every function compiled ahead of time.
#
# Usage: scripts/build-pi.sh [options]
#   --pi DIR          a built Pi tree (scripts/prepare-pi.sh). Default: this repository, which is a fork of Pi
#   --out DIR         where to put the executable and its assets. Default: out/pi-bolt
#   --jit on|off      run with the JIT on (code that is not compiled ahead of time, such as extensions loaded at run time, gets
#                     JIT-compiled) or off (least memory; the default)
#   --cpu native|baseline
#                     native: code for this CPU's instruction set (AVX2 class); on a CPU without it the executable falls back to
#                     bytecode. baseline: code for any x86-64 CPU Bun runs on (Nehalem). Default: native. (x86-64 only: on
#                     ARM64 the code is for every CPU with what Apple's M1 has.)
#   --profile DIR     the training profile (bytecode order + regular expressions). Default: profiles/pi-<version> if there is one
#   --plugins FILE    compile Pi extensions into the executable: FILE is a manifest module whose default export is the list of
#                     extension factories (see docs/PLUGINS.md and examples/plugins/plugins.ts). Its folder is built along with it.
#   --plugin-worker PATH
#                     a worker script a plugin starts, relative to the manifest's folder (repeatable)
#   --keep-bytecode   keep the bytecode in the prebuilt heap (by default it is left out, which is what lets the compiler inline
#                     Pi's own functions, and saves memory)
#   --stable          build with a stock Bun instead (PIBOLT_STABLE_BUN, default `bun`): the comparison build, no AOT
# Environment: PIBOLT_BUN (the Pi-Bolt runtime; default $PIBOLT_WORK/runtime/bun); PIBOLT_BUILD_LOG (a file for everything the
#              compiler prints, e.g. with BUN_JSC_verboseAOTCompilation=1)
source "$(dirname "$0")/lib/common.sh"

PI_DIR="$PIBOLT_PI"; OUT=""; JIT=off; CPU=native; PROFILE=""; PLUGINS=""; PLUGIN_WORKERS=(); KEEP_BYTECODE=""; STABLE=""
while [ $# -gt 0 ]; do
	case "$1" in
	--pi) PI_DIR="$2"; shift ;;
	--out) OUT="$2"; shift ;;
	--jit) JIT="$2"; shift ;;
	--cpu) CPU="$2"; shift ;;
	--profile) PROFILE="$2"; shift ;;
	--plugins) PLUGINS="$2"; shift ;;
	--plugin-worker) PLUGIN_WORKERS+=("$2"); shift ;;
	--keep-bytecode) KEEP_BYTECODE=1 ;;
	--stable) STABLE=1 ;;
	-h | --help) sed -n '2,26p' "$0"; exit 0 ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
[ "$JIT" = on ] || [ "$JIT" = off ] || die "--jit takes on or off"
[ "$CPU" = native ] || [ "$CPU" = baseline ] || die "--cpu takes native or baseline"
# (On ARM64 there is one build: every Apple silicon CPU has what the compiler uses, and the executable checks it at launch.)
[ "$CPU" = native ] || [ "$PIBOLT_ARCH" = x64 ] || die "--cpu baseline is for x86-64 only"

AGENT="$(pi_agent_dir "$PI_DIR")"
VERSION="$(pi_version "$AGENT")"
# The executable is made from Pi's built output (each package's dist), not from its sources: a source changed since its package
# was built would be left out.
for package in "$(dirname "$AGENT")"/*/; do
	[ -d "$package/src" ] && [ -d "$package/dist" ] || continue
	built="$(find "$package/dist" -maxdepth 1 -name '*.js' -print -quit)"
	[ -n "$built" ] || continue
	stale="$(find "$package/src" -name '*.ts' ! -name '*.d.ts' -newer "$built" -print -quit)"
	[ -z "$stale" ] || die "Pi's sources changed since it was built ($stale): build it again (scripts/prepare-pi.sh, or npm run build:offline in $(dirname "$(dirname "$AGENT")"))"
done
PIBOLT_VERSION="$(cat "$PIBOLT_ROOT/VERSION")"
CPU_VARIANT=$PIBOLT_ARCH; [ "$CPU" = baseline ] && CPU_VARIANT=$PIBOLT_ARCH-baseline; [ "$JIT" = on ] && CPU_VARIANT=$PIBOLT_ARCH-jit
OUT="$(abspath "${OUT:-$PIBOLT_ROOT/out/pi-bolt}")"
PROFILE="$(abspath "${PROFILE:-$PIBOLT_ROOT/profiles/pi-$VERSION}")"

ENTRY=""
if [ -n "$PLUGINS" ]; then
	trap 'rm -rf "$AGENT/.pibolt-plugins"' EXIT
	ENTRY="$(stage_plugins "$AGENT" "$PLUGINS")"
fi
mapfile -t ENTRIES < <(pi_entries "$AGENT" "$ENTRY")
# Workers a plugin starts (new Worker(new URL("./worker.ts", import.meta.url))) are entry points of their own.
for worker in "${PLUGIN_WORKERS[@]}"; do
	[ -n "$PLUGINS" ] || die "--plugin-worker needs --plugins"
	[ -f "$(dirname "$PLUGINS")/$worker" ] || die "--plugin-worker: $(dirname "$PLUGINS")/$worker not found"
	ENTRIES+=("./.pibolt-plugins/src/$worker")
done

stage_assets() {
	local dir="$1" root
	root="$(realpath "$PI_DIR")"
	cp "$AGENT/package.json" "$AGENT/README.md" "$AGENT/CHANGELOG.md" "$dir/"
	mkdir -p "$dir/theme" "$dir/assets" "$dir/export-html/vendor" "$dir/native/$PIBOLT_OS/prebuilds"
	cp "$AGENT"/src/modes/interactive/theme/*.json "$dir/theme/"
	cp "$AGENT"/src/modes/interactive/assets/* "$dir/assets/"
	cp "$AGENT/src/core/export-html/template.html" "$dir/export-html/"
	[ -f "$AGENT/src/core/export-html/template.css" ] && cp "$AGENT/src/core/export-html/template.css" "$AGENT/src/core/export-html/template.js" "$dir/export-html/"
	cp "$AGENT"/src/core/export-html/vendor/*.js "$dir/export-html/vendor/" 2>/dev/null || true
	cp "$root/node_modules/@silvia-odwyer/photon-node/photon_rs_bg.wasm" "$dir/"
	cp -R "$root/packages/tui/native/$PIBOLT_OS/prebuilds/$PIBOLT_PLATFORM" "$dir/native/$PIBOLT_OS/prebuilds/"
}

rm -rf "$OUT" && mkdir -p "$OUT"
if [ -n "$STABLE" ]; then
	BUN="${PIBOLT_STABLE_BUN:-bun}"
	need "$BUN"
	log "Pi $VERSION with stock Bun $("$BUN" --version) (bytecode, no AOT) -> $OUT"
	# Named Pi-Bolt as the builds it is compared with are (PRODUCT_NAME, in messages and the window title), on stock Bun.
	(cd "$AGENT" && "$BUN" build --compile --no-compile-autoload-bunfig --no-compile-autoload-dotenv --target="bun-$PIBOLT_PLATFORM" --bytecode --format=esm \
		--define "PIBOLT_BUILD=\"$PIBOLT_VERSION $PIBOLT_ARCH-stable jit-on\"" "${ENTRIES[@]}" --outfile "$OUT/pi" >/dev/null)
	stage_assets "$OUT"
	log "done: $OUT/pi"
	exit 0
fi

BUN="$(runtime_bun)"
ORDER_ARGS=(); REGEXPS=""
if [ -f "$PROFILE/bytecode.order" ]; then
	ORDER_ARGS=(--bytecode-order="$PROFILE/bytecode.order")
	[ -f "$PROFILE/regexps.txt" ] && REGEXPS="$PROFILE/regexps.txt"
else
	warn "no training profile at $PROFILE: building without one (run scripts/train-profile.sh for the fastest startup)"
fi

log "Pi $VERSION, ahead of time: JIT $JIT, CPU $CPU, $([ -n "$KEEP_BYTECODE" ] && echo "bytecode kept" || echo "bytecode left out")$([ -n "$PLUGINS" ] && echo ", plugins from $PLUGINS") -> $OUT"
(
	cd "$AGENT" || exit 1
	export BUN_JSC_useJIT=0 BUN_STATIC_HEAP=1 BUN_AOT=1
	[ "$JIT" = off ] && export BUN_AOT_JIT=0
	[ "$CPU" = baseline ] && export BUN_AOT_CPU=baseline
	[ -z "$KEEP_BYTECODE" ] && export BUN_JSC_omitBytecodeFromStaticHeap=1
	# Loops get a fast copy, without slow paths, that exits to a generic copy when a check fails: hot loops (string scanning,
	# number crunching, in Pi and in plugins) run several times faster. Policy 5 also splits loops whose calls the fast copy
	# does away with or that index arrays (pi-tui's text measuring: 2x), for about 6 MB of code. BUN_JSC_useAOTLoopSplitting=0
	# turns it off; BUN_JSC_aotLoopSplittingPolicy=3 limits it to loops that make no calls.
	export BUN_JSC_useAOTLoopSplitting="${BUN_JSC_useAOTLoopSplitting:-1}"
	export BUN_JSC_aotLoopSplittingPolicy="${BUN_JSC_aotLoopSplittingPolicy:-5}"
	# The standard objects' own methods (Array.prototype.map, Math.floor, ...) are what they were when the realm was made: the
	# compiler then inlines them, callbacks and all (forEach, map, filter, reduce: 5-10x in loops). Code that overwrites one of
	# them gets a TypeError; adding methods is fine. BUN_JSC_useImmutableIntrinsics=0 turns it off (docs/PLUGINS.md).
	export BUN_JSC_useImmutableIntrinsics="${BUN_JSC_useImmutableIntrinsics:-1}"
	[ -n "$REGEXPS" ] && export BUN_JSC_aotRegExpsPath="$REGEXPS"
	# The functions whose executables Pi's runs touch (scripts/train-heap.ps1, on Windows): made side by side in the prebuilt heap.
	[ -f "$PROFILE/heap-functions.txt" ] && export BUN_STATIC_HEAP_FUNCTIONS_FIRST="$PROFILE/heap-functions.txt"
	# No .env from the working directory (in both builds): Pi on Node never loads one into its environment, and looking for it
	# in a large directory cost a millisecond or more at every start.
	# What `pi --version` and `pi update` know themselves by (packages/coding-agent/src/pi-bolt.ts).
	# Extensions resolve their own packages as on Node, which needs their package.json files (the "exports" and "main" of
	# sharp, for one). Pi's own code still resolves nothing in the working directory (the runtime: tests/runtime, workdir).
	"$BUN" build --compile --no-compile-autoload-bunfig --no-compile-autoload-dotenv --compile-autoload-package-json \
		--target="bun-$PIBOLT_PLATFORM" --bytecode --format=esm "${ORDER_ARGS[@]}" \
		--define "PIBOLT_BUILD=\"$PIBOLT_VERSION $CPU_VARIANT jit-$JIT\"" \
		--compile-exec-argv=--smol "${ENTRIES[@]}" --outfile "$OUT/pi" 2>&1 | tee "${PIBOLT_BUILD_LOG:-/dev/null}" | grep -v "^AOT: " | tail -3
)
# macOS: pi is a launcher that starts the executable, pi-bin, at its linked address at once (scripts/lib/darwin-launcher.c): it
# would otherwise start again itself, after a first load by dyld. The launcher also forks the helper that starts the programs
# Pi starts, through pi-spawn (scripts/lib/darwin-spawn.h).
if [ "$PIBOLT_OS" = darwin ]; then
	mv "$OUT/pi" "$OUT/pi-bin"
	CLANG=(xcrun clang -O2 -Wall -arch arm64 -mmacosx-version-min=13.0 -I"$PIBOLT_ROOT/scripts/lib")
	"${CLANG[@]}" -o "$OUT/pi" "$PIBOLT_ROOT/scripts/lib/darwin-launcher.c" "$PIBOLT_ROOT/scripts/lib/darwin-spawn-helper.c"
	"${CLANG[@]}" -o "$OUT/pi-spawn" "$PIBOLT_ROOT/scripts/lib/darwin-spawn-proxy.c"
fi
stage_assets "$OUT"
printf 'Pi-Bolt %s (Pi %s), %s-%s, JIT %s, built %s\n' "$PIBOLT_VERSION" "$VERSION" "$PIBOLT_OS" "$CPU_VARIANT" "$JIT" "$(date -u +%Y-%m-%d)" >"$OUT/pi-bolt.txt"

check=$(BUN_STATIC_HEAP_VERBOSE=1 "$OUT/pi" --version 2>&1)
grep -q "image registered: true" <<<"$check" || die "the executable does not use its compiled code:"$'\n'"$check"
executable="$OUT/pi"; [ -f "$OUT/pi-bin" ] && executable="$OUT/pi-bin"
# macOS: the parts of pi-bin a start reads, for the launcher to ask for at once when pi-bin is not in memory (a start after a
# restart or an update: 65 ms rather than 180). Only advice: without it Pi starts as before.
if [ "$PIBOLT_OS" = darwin ]; then
	python3 "$PIBOLT_ROOT/scripts/lib/darwin_hot_pages.py" "$OUT" || { rm -f "$OUT/pi-bin.hot"; log "no pi-bin.hot (see above)"; }
fi
log "done: $OUT/pi ($(du -h "$executable" | cut -f1), Pi $(tail -1 <<<"$check"))"
