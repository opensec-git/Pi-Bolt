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
#                     bytecode. baseline: code for any x86-64 CPU Bun runs on (Nehalem). Default: native. On Linux ARM64 the
#                     default is baseline, code for any ARMv8.0 CPU (native: the extensions this CPU has, such as LSE and
#                     JSCVT); on macOS there is only native, for every CPU with what Apple's M1 has.
#   --profile DIR     the training profile (bytecode order + regular expressions). Default: profiles/pi-<version> if there is one
#   --plugins FILE    compile Pi extensions into the executable: FILE is a manifest module whose default export is the list of
#                     extension factories (see docs/PLUGINS.md and examples/plugins/plugins.ts). Its folder is built along with it.
#   --plugins-from-installed
#                     compile in the extensions installed on this machine: the agent directory's extensions folder and the
#                     paths in its `extensions` setting, plus the packages `pi install` installed. The manifest is generated
#                     into $PIBOLT_WORK/plugins first (scripts/gen-plugin-manifest.mjs). NOT this repository's own
#                     .pi/extensions, which hold test extensions. Not with --plugins
#   --plugins-only LIST
#                     with --plugins-from-installed: only these extensions, by name, separated by commas
#   --plugins-exclude LIST
#                     with --plugins-from-installed: every extension except these, separated by commas
#   --plugins-agent-dir DIR
#                     with --plugins-from-installed: the agent directory to read. Default: ~/.pi/agent
#   --plugins-no-packages
#                     with --plugins-from-installed: leave out the packages `pi install` installed
#   --plugin-worker PATH
#                     a worker script a plugin starts, relative to the manifest's folder (repeatable)
#   --keep-bytecode   keep the bytecode in the prebuilt heap (by default it is left out, which is what lets the compiler inline
#                     Pi's own functions, and saves memory)
#   --stable          build with a stock Bun instead (PIBOLT_STABLE_BUN, default `bun`): the comparison build, no AOT
#   --kit DIR         also write the compile kit to DIR: the files this build read, its compile command and the training
#                     profile, for `pi-bolt compile` to build the same executable with extensions (scripts/make-kit.mjs).
#                     Not with --plugins
# Environment: PIBOLT_BUN (the Pi-Bolt runtime; default $PIBOLT_WORK/runtime/bun); PIBOLT_BUILD_LOG (a file for everything the
#              compiler prints, e.g. with BUN_JSC_verboseAOTCompilation=1)
source "$(dirname "$0")/lib/common.sh"

PI_DIR="$PIBOLT_PI"; OUT=""; JIT=off; CPU=""; PROFILE=""; PLUGINS=""; PLUGIN_WORKERS=(); KEEP_BYTECODE=""; STABLE=""
FROM_INSTALLED=""; PLUGINS_ONLY=""; PLUGINS_EXCLUDE=""; PLUGINS_AGENT_DIR=""; PLUGINS_NO_PACKAGES=""; KIT=""
while [ $# -gt 0 ]; do
	case "$1" in
	--pi) PI_DIR="$2"; shift ;;
	--out) OUT="$2"; shift ;;
	--jit) JIT="$2"; shift ;;
	--cpu) CPU="$2"; shift ;;
	--profile) PROFILE="$2"; shift ;;
	--plugins) PLUGINS="$2"; shift ;;
	--plugins-from-installed) FROM_INSTALLED=1 ;;
	--plugins-only) PLUGINS_ONLY="$2"; shift ;;
	--plugins-exclude) PLUGINS_EXCLUDE="$2"; shift ;;
	--plugins-agent-dir) PLUGINS_AGENT_DIR="$2"; shift ;;
	--plugins-no-packages) PLUGINS_NO_PACKAGES=1 ;;
	--plugin-worker) PLUGIN_WORKERS+=("$2"); shift ;;
	--keep-bytecode) KEEP_BYTECODE=1 ;;
	--stable) STABLE=1 ;;
	--kit) KIT="$2"; shift ;;
	-h | --help) sed -n '2,38p' "$0"; exit 0 ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
[ "$JIT" = on ] || [ "$JIT" = off ] || die "--jit takes on or off"
[ -n "$CPU" ] || { [ "$PIBOLT_PLATFORM" = linux-arm64 ] && CPU=baseline; } || CPU=native
[ "$CPU" = native ] || [ "$CPU" = baseline ] || die "--cpu takes native or baseline"
# (On macOS there is one build: every Apple silicon CPU has what the compiler uses, and the executable checks it at launch.)
[ "$CPU" = native ] || [ "$PIBOLT_OS" = linux ] || die "--cpu baseline is for Linux only"
[ -z "$FROM_INSTALLED" ] || [ -z "$PLUGINS" ] || die "--plugins-from-installed and --plugins are two ways to say the same thing: use one"
[ -z "$KIT" ] || { [ -z "$PLUGINS" ] && [ -z "$FROM_INSTALLED" ] && [ -z "$STABLE" ]; } || die "--kit is for a build of Pi alone (no plugins, not --stable)"
if [ -z "$FROM_INSTALLED" ]; then
	[ -z "$PLUGINS_ONLY" ] || die "--plugins-only needs --plugins-from-installed"
	[ -z "$PLUGINS_EXCLUDE" ] || die "--plugins-exclude needs --plugins-from-installed"
	[ -z "$PLUGINS_AGENT_DIR" ] || die "--plugins-agent-dir needs --plugins-from-installed"
	[ -z "$PLUGINS_NO_PACKAGES" ] || die "--plugins-no-packages needs --plugins-from-installed"
fi

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
# (Named as the archives are: on ARM64, baseline is the build.)
CPU_VARIANT=$PIBOLT_ARCH; [ "$CPU" = baseline ] && [ "$PIBOLT_ARCH" = x64 ] && CPU_VARIANT=$PIBOLT_ARCH-baseline; [ "$JIT" = on ] && CPU_VARIANT=$PIBOLT_ARCH-jit
OUT="$(abspath "${OUT:-$PIBOLT_ROOT/out/pi-bolt}")"
PROFILE="$(abspath "${PROFILE:-$PIBOLT_ROOT/profiles/pi-$VERSION}")"

# The manifest for this machine's extensions, generated before anything is built.
if [ -n "$FROM_INSTALLED" ]; then
	need node
	GEN_ARGS=()
	[ -z "$PLUGINS_ONLY" ] || GEN_ARGS+=(--only "$PLUGINS_ONLY")
	[ -z "$PLUGINS_EXCLUDE" ] || GEN_ARGS+=(--exclude "$PLUGINS_EXCLUDE")
	[ -z "$PLUGINS_AGENT_DIR" ] || GEN_ARGS+=(--agent-dir "$PLUGINS_AGENT_DIR")
	[ -z "$PLUGINS_NO_PACKAGES" ] || GEN_ARGS+=(--no-packages)
	log "the extensions installed on this machine:"
	PLUGINS="$(node "$PIBOLT_ROOT/scripts/gen-plugin-manifest.mjs" --out "$PIBOLT_WORK/plugins" "${GEN_ARGS[@]}")"
	[ -n "$PLUGINS" ] || die "no plugin manifest was generated"
fi

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
	# Pi's documentation and example extensions: Pi points the model (its system prompt) and the user (the first run, /login) at
	# them, next to the README.
	cp -R "$AGENT/docs" "$AGENT/examples" "$dir/"
	# Not the documentation's pictures (2.5 MB of PNG, about 4% of the download): the model reads the text, and a terminal
	# shows none of them (the one the docs show has its alt text).
	rm -rf "$dir/docs/images"
	# With Pi-Bolt's own that `pi-bolt doctor` points at (#63).
	cp "$PIBOLT_ROOT/docs/PLUGINS.md" "$PIBOLT_ROOT/docs/ACP.md" "$PIBOLT_ROOT/docs/TROUBLESHOOTING.md" "$dir/docs/"
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

# Which engine the runtime is (scripts/runtime-stamp.sh), for `pi-bolt doctor`: PIBOLT_RUNTIME_STAMP (the release workflow's),
# else the RUNTIME_STAMP that build-runtime.sh or fetch-runtime.sh put beside the runtime; unknown otherwise.
RUNTIME_STAMP="${PIBOLT_RUNTIME_STAMP:-$(cat "$(dirname "$BUN")/RUNTIME_STAMP" 2>/dev/null || true)}"
[[ "$RUNTIME_STAMP" =~ ^[0-9a-f]{16}$ ]] || RUNTIME_STAMP=""
# Everything the compiler prints, for when the build stops: the reason is often an "AOT: " line, which is not shown otherwise.
TEMP_DIR="${TMPDIR:-/tmp}"
BUILD_LOG="${PIBOLT_BUILD_LOG:-$(mktemp "${TEMP_DIR%/}/pibolt-build.XXXXXX")}"
log "Pi $VERSION, ahead of time: JIT $JIT, CPU $CPU, $([ -n "$KEEP_BYTECODE" ] && echo "bytecode kept" || echo "bytecode left out")$([ -n "$PLUGINS" ] && echo ", plugins from $PLUGINS") -> $OUT"
BUILD_ENV=(BUN_JSC_useJIT=0 BUN_STATIC_HEAP=1 BUN_AOT=1)
[ "$JIT" = off ] && BUILD_ENV+=(BUN_AOT_JIT=0)
[ "$CPU" = baseline ] && BUILD_ENV+=(BUN_AOT_CPU=baseline)
[ -z "$KEEP_BYTECODE" ] && BUILD_ENV+=(BUN_JSC_omitBytecodeFromStaticHeap=1)
# Loops get a fast copy, without slow paths, that exits to a generic copy when a check fails: hot loops (string scanning,
# number crunching, in Pi and in plugins) run several times faster. Policy 5 also splits loops whose calls the fast copy
# does away with or that index arrays (pi-tui's text measuring: 2x), for about 6 MB of code. BUN_JSC_useAOTLoopSplitting=0
# turns it off; BUN_JSC_aotLoopSplittingPolicy=3 limits it to loops that make no calls.
BUILD_ENV+=("BUN_JSC_useAOTLoopSplitting=${BUN_JSC_useAOTLoopSplitting:-1}")
BUILD_ENV+=("BUN_JSC_aotLoopSplittingPolicy=${BUN_JSC_aotLoopSplittingPolicy:-5}")
# The standard objects' own methods (Array.prototype.map, Math.floor, ...) are what they were when the realm was made: the
# compiler then inlines them, callbacks and all (forEach, map, filter, reduce: 5-10x in loops). Code that overwrites one of
# them gets a TypeError; adding methods is fine. BUN_JSC_useImmutableIntrinsics=0 turns it off (docs/PLUGINS.md).
BUILD_ENV+=("BUN_JSC_useImmutableIntrinsics=${BUN_JSC_useImmutableIntrinsics:-1}")
[ -n "$REGEXPS" ] && BUILD_ENV+=("BUN_JSC_aotRegExpsPath=$REGEXPS")
# The functions whose executables Pi's runs touch (scripts/train-heap.ps1, on Windows): made side by side in the prebuilt heap.
[ -f "$PROFILE/heap-functions.txt" ] && BUILD_ENV+=("BUN_STATIC_HEAP_FUNCTIONS_FIRST=$PROFILE/heap-functions.txt")
# No .env from the working directory (in both builds): Pi on Node never loads one into its environment, and looking for it
# in a large directory cost a millisecond or more at every start.
# Extensions resolve their own packages as on Node, which needs their package.json files (the "exports" and "main" of
# sharp, for one). Pi's own code still resolves nothing in the working directory (the runtime: tests/runtime, workdir).
# --splitting: what Pi imports with import() (the rest of Pi after `pi --version`, the interactive mode, the providers' APIs) is a
# chunk of its own, loaded when it is imported. One file is one module, and all of a module's function declarations are created
# before any of it runs: `pi --version` made every function of Pi (87 -> 73 M instructions on the M5 without them), `pi -p` every
# function of the interactive mode (-4 M; -12 M with the JIT on).
BUILD_ARGS=(build --compile --no-compile-autoload-bunfig --no-compile-autoload-dotenv --compile-autoload-package-json
	--target="bun-$PIBOLT_PLATFORM" --bytecode --format=esm --splitting "${ORDER_ARGS[@]}" --compile-exec-argv=--smol)
if ! (
	cd "$AGENT" || exit 1
	# What `pi --version` and `pi update` know themselves by (packages/coding-agent/src/pi-bolt.ts).
	env "${BUILD_ENV[@]}" "$BUN" "${BUILD_ARGS[@]}" \
		--define "PIBOLT_BUILD=\"$PIBOLT_VERSION $CPU_VARIANT jit-$JIT${RUNTIME_STAMP:+ $RUNTIME_STAMP}\"" \
		"${ENTRIES[@]}" --outfile "$OUT/pi" 2>&1 | tee "$BUILD_LOG" | grep -v "^AOT: " | tail -3
); then
	tail -n 12 "$BUILD_LOG" >&2
	die "the build stopped (above: the end of what the compiler printed; all of it: $BUILD_LOG)"
fi
[ -n "${PIBOLT_BUILD_LOG:-}" ] || rm -f "$BUILD_LOG"
# macOS: pi is a launcher that starts the executable, pi-bin, at its linked address at once (scripts/lib/darwin-launcher.c): it
# would otherwise start again itself, after a first load by dyld. The launcher also forks the helper that starts the programs
# Pi starts, through pi-spawn (scripts/lib/darwin-spawn.h).
if [ "$PIBOLT_OS" = darwin ]; then
	mv "$OUT/pi" "$OUT/pi-bin"
	CLANG=(xcrun clang -O2 -Wall -arch arm64 -mmacosx-version-min=13.0 -I"$PIBOLT_ROOT/scripts/lib")
	"${CLANG[@]}" -o "$OUT/pi" "$PIBOLT_ROOT/scripts/lib/darwin-launcher.c" "$PIBOLT_ROOT/scripts/lib/darwin-spawn-helper.c"
	"${CLANG[@]}" -o "$OUT/pi-spawn" "$PIBOLT_ROOT/scripts/lib/darwin-spawn-proxy.c"
	# A libSystem through which dyld binds pi-bin's imports in a fifth of the time (scripts/lib/darwin_libsystem.py). Only
	# for speed: without it pi-bin binds through /usr/lib/libSystem.B.dylib as before.
	python3 "$PIBOLT_ROOT/scripts/lib/darwin_libsystem.py" "$OUT"
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

# The compile kit: what this build read and how it was compiled, for `pi-bolt compile` (packages/coding-agent/src/pi-bolt-compile.ts).
if [ -n "$KIT" ]; then
	record="$(mktemp "${TEMP_DIR%/}/pibolt-kit.XXXXXX")"
	python3 -c 'import json, sys
argv = sys.argv[2:]
n = int(argv[0]); env = argv[1:1 + n]; argv = argv[1 + n:]
n = int(argv[0]); args = argv[1:1 + n]; entries = argv[1 + n:]
json.dump({"env": dict(e.split("=", 1) for e in env), "args": args, "entries": entries}, open(sys.argv[1], "w"), indent=1)' \
		"$record" "${#BUILD_ENV[@]}" "${BUILD_ENV[@]}" "${#BUILD_ARGS[@]}" "${BUILD_ARGS[@]}" "${ENTRIES[@]}"
	"$BUN" "$PIBOLT_ROOT/scripts/make-kit.mjs" --bun "$BUN" --pi "$(realpath "$PI_DIR")" --agent "$AGENT" --record "$record" \
		--profile "$PROFILE" --platform "$PIBOLT_PLATFORM" --version "$PIBOLT_VERSION" --out "$(abspath "$KIT")"
	rm -f "$record"
fi
