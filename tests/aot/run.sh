#!/usr/bin/env bash
# Correctness tests for the ahead-of-time engine. Each program is compiled ahead of time three times (JIT on, JIT off: the two
# kinds of Pi build; and JIT off with every operation compiled the compact way, as outside loops and in the generic copies of
# loops) and must print exactly what a stock Bun prints running its source.
#
# Usage: tests/aot/run.sh [test.mjs...]      (default: every test)
# Environment: PIBOLT_BUN (the Pi-Bolt runtime), PIBOLT_STABLE_BUN (the reference; default `bun`),
#              AOT_BUILD_ENV (extra variables for the compile step, e.g. "BUN_JSC_useAOTLoopSplitting=0")
source "$(dirname "$0")/../../scripts/lib/common.sh"
set +e

BUN="$(runtime_bun)"
STABLE="${PIBOLT_STABLE_BUN:-bun}"
need "$STABLE"
cd "$(dirname "$0")" || exit 1
OUT="$PIBOLT_WORK/tests/aot"
mkdir -p "$OUT"

tests=("$@")
[ ${#tests[@]} -eq 0 ] && tests=(liveness.mjs mapset.mjs realms.mjs workers.mjs spread-loops.mjs number-encoding.mjs helper-calls.mjs callbacks.mjs methods.mjs dictionaries.mjs variables.mjs polymorphic.mjs strings.mjs unicode-regexps.mjs builtins.mjs declined.mjs deferred-builtins.mjs wide-constants.mjs intl-locales.mjs)
status=0
for t in "${tests[@]}"; do
	name=${t%.mjs}
	extra=(); own=""
	[ "$name" = realms ] && extra=(realms-mod.mjs)
	"$STABLE" "$t" >"$OUT/$name.expected" 2>&1
	# A plain bytecode build, run once, records the order functions are first called in: the AOT build lays code out by it.
	"$BUN" build --compile --bytecode --format=esm --target="bun-$PIBOLT_PLATFORM" "$t" "${extra[@]}" --outfile "$OUT/$name-bytecode" >/dev/null 2>&1
	rm -f "$OUT/$name.order"
	BUN_BYTECODE_ORDER_OUT="$OUT/$name.order" "$OUT/$name-bytecode" >/dev/null 2>&1
	if [ "$name" = declined ]; then
		# Every function named `declined` is declined, as the compiler declines one it cannot compile. The build stops and says
		# which and why; told to go on, it builds a program whose compiled code calls them like any value.
		own="BUN_JSC_aotDeclineFunctionsNamed=declined BUN_JSC_allowAOTDeclinedFunctions=1"
		# shellcheck disable=SC2086 # AOT_BUILD_ENV is a list of words
		stopped=$(env BUN_JSC_aotDeclineFunctionsNamed=declined BUN_JSC_useAOTLoopSplitting=1 BUN_JSC_aotLoopSplittingPolicy=5 BUN_JSC_useImmutableIntrinsics=1 ${AOT_BUILD_ENV:-} BUN_AOT_JIT=0 BUN_JSC_useJIT=0 \
			BUN_STATIC_HEAP=1 BUN_AOT=1 BUN_JSC_omitBytecodeFromStaticHeap=1 BUN_ENABLE_CRASH_REPORTING=0 \
			"$BUN" build --compile --bytecode --format=esm --target="bun-$PIBOLT_PLATFORM" --bytecode-order="$OUT/$name.order" "$t" --outfile "$OUT/$name-stopped" 2>&1)
		code=$?
		if [ $code = 1 ] && grep -q 'the function .declined. .*cannot be compiled ahead of time' <<<"$stopped" && ! grep -qi "panic\|crashed" <<<"$stopped"; then
			echo "PASS $name (the build stops)"
		else
			echo "FAIL $name (the build stops): exit $code"
			tail -3 <<<"$stopped" | cut -c1-200
			status=1
		fi
	fi
	for mode in jit-on jit-off compact; do
		# shellcheck disable=SC2046,SC2086 # AOT_BUILD_ENV and the mode's settings are lists of words
		env BUN_JSC_useAOTLoopSplitting=1 BUN_JSC_aotLoopSplittingPolicy=5 BUN_JSC_useImmutableIntrinsics=1 $own ${AOT_BUILD_ENV:-} $([ $mode != jit-on ] && echo BUN_AOT_JIT=0) $([ $mode = compact ] && echo BUN_JSC_useAOTInlineFastPathsInLoops=0) BUN_JSC_useJIT=0 BUN_STATIC_HEAP=1 BUN_AOT=1 \
			BUN_JSC_omitBytecodeFromStaticHeap=1 \
			"$BUN" build --compile --bytecode --format=esm --target="bun-$PIBOLT_PLATFORM" --bytecode-order="$OUT/$name.order" \
			"$t" "${extra[@]}" --outfile "$OUT/$name-$mode" >"$OUT/$name-$mode.build" 2>&1
		built=$?
		BUN_STATIC_HEAP_VERBOSE=1 "$OUT/$name-$mode" >"$OUT/$name.$mode" 2>"$OUT/$name.$mode.err"
		used=$(grep -c "image registered: true" "$OUT/$name.$mode.err")
		expected=$(cat "$OUT/$name.expected")
		actual=$(cat "$OUT/$name.$mode")
		# The realms test also prints memory figures, which differ by design: compare the first column only.
		if [ "$name" = realms ]; then
			expected=$(cut -d' ' -f1 <<<"$expected")
			actual=$(cut -d' ' -f1 <<<"$actual")
		fi
		if [ "$used" = 1 ] && [ "$expected" = "$actual" ]; then
			echo "PASS $name ($mode)"
		else
			echo "FAIL $name ($mode): compiled code used: $([ "$used" = 1 ] && echo yes || echo no)"
			diff <(echo "$expected") <(echo "$actual") | head -5
			# (What the build said, and the program's errors: the reason is usually there.)
			echo "  build exit $built; its last lines:"
			tail -8 "$OUT/$name-$mode.build" | cut -c1-300
			tail -3 "$OUT/$name.$mode.err" | cut -c1-300
			status=1
		fi
	done
done
exit $status
