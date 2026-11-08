#!/usr/bin/env bash
# The module-state snapshot prototype (patches/experimental/bun-module-snapshot.patch, applied to the runtime): builds Pi with
# every module wrapped, audits it headless and interactive, selects the modules to keep, and takes their snapshot into
# OUTDIR/pi-bin.msnap (replayed by OUTDIR/pi). SKIP_AUDIT=1 reuses OUTDIR.audit.
# Usage: bench/snapshot/snap.sh OUTDIR WRAPPED_BUNDLE_DIR
#   WRAPPED_BUNDLE_DIR: the same entries built without --compile, for the modules' dependencies:
#   (cd packages/coding-agent && BUN_MODULE_SNAPSHOT_WRAP=1 $PIBOLT_WORK/runtime/bun build --target=bun --format=esm --splitting \
#    ./dist/bun/cli.js ./src/utils/image-resize-worker.ts --outdir DIR)
# Environment: PIBOLT_WORK (the runtime, as for scripts/build-pi.sh), JITARG (e.g. "--jit on").
set -euo pipefail
OUT=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); BUNDLE=$2
T=$(cd "$(dirname "$0")" && pwd); B=$(dirname "$T"); ROOT=$(dirname "$B")
cd "$ROOT"
mkdir -p "$OUT.audit"
if [ -z "${SKIP_AUDIT:-}" ]; then
	BUN_MODULE_SNAPSHOT_WRAP=1 scripts/build-pi.sh ${JITARG:-} --out "$OUT" > "$OUT.build.log" 2>&1
	tail -1 "$OUT.build.log"
	python3 "$T/audit_run.py" "$B" "$OUT/pi" "$OUT.audit/h.jsonl" headless >/dev/null
	python3 "$T/audit_run.py" "$B" "$OUT/pi" "$OUT.audit/i.jsonl" interactive >/dev/null
fi
python3 "$T/select.py" "$BUNDLE" "$OUT.audit/selected.txt" "$OUT.audit/h.jsonl" "$OUT.audit/i.jsonl"
python3 "$T/audit_run.py" "$B" "$OUT/pi" "$OUT.audit/s.jsonl" interactive "BUN_MODULE_SNAPSHOT_OUT=$OUT/pi-bin.msnap" "BUN_MODULE_SNAPSHOT_MODULES=$OUT.audit/selected.txt" >/dev/null
echo "in the snapshot: $(grep -c '"ok":true' "$OUT.audit/s.jsonl" || true) modules"
grep '"ok":false' "$OUT.audit/s.jsonl" | python3 -c "
import sys, json, collections
c = collections.Counter(json.loads(l)['why'][:80] for l in sys.stdin)
for k, v in c.most_common(12): print(v, k)"
python3 "$T/smoke.py" "$OUT/pi" BUN_MODULE_SNAPSHOT_CHECK=1
