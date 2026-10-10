#!/usr/bin/env bash
# Runs the full benchmark suite behind the README and docs/BENCHMARKS.md, then renders the charts.
#
# Usage: bench/run-suite.sh RESULTS_DIR [--cpus LIST]
# Expects the builds of scripts/package-release.sh in out/ (pi-bolt, pi-bolt-jit), the plugin builds
# (scripts/build-pi.sh --plugins examples/plugins/plugins.ts --out out/pi-bolt-plugins, and --jit on --out out/pi-bolt-plugins-jit),
# the stock-Bun build (scripts/build-pi.sh --stable --out out/pi-stable) and, for Node, the Pi of this repository, built
# (scripts/prepare-pi.sh).
# Environment: PIBOLT_PI (the Pi tree Node runs; default this repository), PIBOLT_STABLE_PI (the stock-Bun executable; default
# out/pi-stable/pi), PIBOLT_COMPARE (more builds to run alongside, as name=command, separated by spaces: an earlier Pi-Bolt release,
# e.g. "pi-bolt-0.7.0=.work/base-070/pi-bolt-linux-x64/pi"; the report shows them after Pi-Bolt). Pi-Bolt's tree has changes to Pi of its own: to compare with Pi as released, point both at a build of the
# upstream tag (scripts/build-pi.sh --stable --pi <Pi checkout> --out out/pi-stable-upstream).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "${1:?results directory}")"; shift
CPUS=40-47
[ "$(uname -s)" = Darwin ] && CPUS="" # (macOS has no pinning to cores: the tools ignore --cpus there)
[ "${1:-}" = --cpus ] && CPUS="$2"
PI="${PIBOLT_PI:-$ROOT}"
NODE="node $PI/packages/coding-agent/dist/bundle/cli.js"
STABLE="${PIBOLT_STABLE_PI:-out/pi-stable/pi}"
COMPARE=(); COMPARE_NAMES=""
for spec in ${PIBOLT_COMPARE:-}; do COMPARE+=(--build "$spec"); COMPARE_NAMES+=",${spec%%=*}"; done
mkdir -p "$OUT"
cd "$ROOT"
{
	echo "date: $(date -u +%Y-%m-%dT%H:%MZ)"
	if [ "$(uname -s)" = Darwin ]; then
		echo "cpu: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, not pinned"
		echo "system: macOS $(sw_vers -productVersion) ($(uname -r))"
	else
		echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs), pinned to $CPUS"
		echo "kernel: $(uname -r)"
	fi
	echo "pi-bolt: $(cat VERSION), Pi $(out/pi-bolt/pi --version)"
	echo "bun: $(${PIBOLT_STABLE_BUN:-bun} --version)"
	echo "node: $(node --version)"
} >"$OUT/environment.txt"

python3 bench/benchmark.py --runs 21 --warmup 3 ${CPUS:+--cpus "$CPUS"} --out "$OUT/benchmark.jsonl" \
	--build pi-bolt=out/pi-bolt/pi ${COMPARE[@]+"${COMPARE[@]}"} --build bun="$STABLE" --build "node=$NODE" --build pi-bolt-jit=out/pi-bolt-jit/pi --baseline bun
for _ in 1 2 3; do
	python3 bench/long_session.py --prompts 75 --every 25 ${CPUS:+--cpus "$CPUS"} --out "$OUT/long.jsonl" \
		--build pi-bolt=out/pi-bolt/pi ${COMPARE[@]+"${COMPARE[@]}"} --build bun="$STABLE" --build "node=$NODE"
done
python3 bench/tmux_check.py --prompts 4 --rounds 5 ${CPUS:+--cpus "$CPUS"} --out "$OUT/tmux.jsonl" \
	--build pi-bolt=out/pi-bolt/pi ${COMPARE[@]+"${COMPARE[@]}"} --build bun="$STABLE" --build "node=$NODE" >/dev/null
python3 bench/plugin_bench.py --runs 5 ${CPUS:+--cpus "$CPUS"} --out "$OUT/plugins.jsonl" \
	--compiled pi-bolt=out/pi-bolt-plugins/pi --compiled pi-bolt-jit=out/pi-bolt-plugins-jit/pi \
	--runtime pi-bolt=out/pi-bolt/pi --runtime pi-bolt-jit=out/pi-bolt-jit/pi --runtime bun="$STABLE" \
	--none pi-bolt=out/pi-bolt/pi --none bun="$STABLE"
# (The charts of each platform have their own folder: Linux's are the README's.)
IMAGES=docs/images
[ "$(uname -s)" = Darwin ] && IMAGES=docs/images/darwin-arm64
mkdir -p "$IMAGES"
python3 bench/report.py "$OUT" --images "$IMAGES" --builds "pi-bolt$COMPARE_NAMES,bun,node" | tee "$OUT/summary.md"
