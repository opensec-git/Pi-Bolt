#!/usr/bin/env bash
# Checks a release's files in DIR against its SHA256SUMS: the first line says the version ("# pi-bolt VERSION": install.ps1 and the
# npm package's Windows install refuse checksums of another version), every other line is a file in DIR with that checksum, and
# every file in DIR other than SHA256SUMS, SHA256SUMS.sig and RUNTIME_STAMP has a line. extensions.txt must be one of them: without
# it the installers offer no extensions. What a release holds is whatever was built (docs/RELEASING.md), not a fixed count.
# Usage: scripts/check-release-files.sh DIR VERSION
set -euo pipefail
dir=$1
version=$2
cd "$dir"
[ "$(head -n 1 SHA256SUMS)" = "# pi-bolt $version" ] || { echo "SHA256SUMS does not begin with '# pi-bolt $version'"; exit 1; }
tail -n +2 SHA256SUMS | sha256sum -c --strict --quiet -
listed=$(tail -n +2 SHA256SUMS | sed -E 's/^[0-9a-f]{64} [ *]//' | LC_ALL=C sort)
present=$(find . -maxdepth 1 -type f ! -name SHA256SUMS ! -name SHA256SUMS.sig ! -name RUNTIME_STAMP -printf '%f\n' | LC_ALL=C sort)
if [ "$listed" != "$present" ]; then
	echo "SHA256SUMS does not list exactly the release's files:"
	diff <(printf '%s\n' "$listed") <(printf '%s\n' "$present") || true
	exit 1
fi
printf '%s\n' "$listed" | grep -qx extensions.txt || { echo "the release has no extensions.txt"; exit 1; }
echo "SHA256SUMS: $(printf '%s\n' "$listed" | wc -l) files, each as listed"
