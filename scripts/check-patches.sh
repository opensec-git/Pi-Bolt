#!/usr/bin/env bash
# Checks that patches/webkit.patch and patches/bun.patch apply to the commits sources.json pins, without a checkout: a partial
# clone fetches the commit's trees, and only the files a patch touches are fetched, as it is applied to an index.
# Usage: scripts/check-patches.sh [webkit|bun ...]      (default: both)
source "$(dirname "$0")/lib/common.sh"
need git
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
status=0
targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(webkit bun)
for name in "${targets[@]}"; do
	repository=$(source_field "$name" repository)
	commit=$(source_field "$name" commit)
	patch="$PIBOLT_ROOT/$(source_field "$name" patches)"
	dir="$WORK/$name"
	git init -q "$dir"
	git -C "$dir" remote add origin "$repository"
	git -C "$dir" fetch -q --depth 1 --filter=blob:none origin "$commit"
	GIT_INDEX_FILE="$WORK/$name.index" git -C "$dir" read-tree "$commit"
	if GIT_INDEX_FILE="$WORK/$name.index" git -C "$dir" apply --cached "$patch"; then
		log "$name: $(basename "$patch") applies to ${commit:0:12}"
	else
		warn "$name: $(basename "$patch") does not apply to ${commit:0:12}"
		status=1
	fi
done
exit $status
