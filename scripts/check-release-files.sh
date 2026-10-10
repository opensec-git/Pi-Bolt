#!/usr/bin/env bash
# Checks a release's files in DIR against its SHA256SUMS before it is signed or published:
# - the first line says the version ("# pi-bolt VERSION": install.ps1 and the npm package's Windows install refuse checksums of
#   another version);
# - every other line is a file in DIR with that checksum, and they are exactly the files scripts/release-files.txt names, no more
#   and none missing (RUNTIME_STAMP among them, so that the runtime a later release reuses is the signed one);
# - extensions.txt has at least one well-formed pin and nothing else: the installers skip a line they cannot read, so a bad one
#   would quietly offer nothing.
# Usage: scripts/check-release-files.sh DIR VERSION
set -euo pipefail
manifest="$(cd "$(dirname "$0")" && pwd)/release-files.txt"
dir=$1
version=$2
cd "$dir"
[ "$(head -n 1 SHA256SUMS)" = "# pi-bolt $version" ] || { echo "SHA256SUMS does not begin with '# pi-bolt $version'"; exit 1; }
tail -n +2 SHA256SUMS | sha256sum -c --strict --quiet -
listed=$(tail -n +2 SHA256SUMS | sed -E 's/^[0-9a-f]{64} [ *]//' | LC_ALL=C sort)
expected=$(grep -v -e '^#' -e '^$' "$manifest" | LC_ALL=C sort)
present=$(find . -maxdepth 1 -type f ! -name SHA256SUMS ! -name SHA256SUMS.sig -printf '%f\n' | LC_ALL=C sort)
if [ "$listed" != "$expected" ]; then
	echo "SHA256SUMS does not list the files of a release (scripts/release-files.txt):"
	diff <(printf '%s\n' "$expected") <(printf '%s\n' "$listed") || true
	exit 1
fi
if [ "$present" != "$expected" ]; then
	echo "the release's files are not those SHA256SUMS lists:"
	diff <(printf '%s\n' "$expected") <(printf '%s\n' "$present") || true
	exit 1
fi
pin='^[a-z0-9][a-z0-9._-]* [0-9]+\.[0-9]+\.[0-9]+ sha512-[A-Za-z0-9+/]{86}==$'
grep -v -e '^#' -e '^$' extensions.txt | grep -Evq "$pin" && { echo "extensions.txt has a line that is not a pin:"; grep -v -e '^#' -e '^$' extensions.txt | grep -Ev "$pin"; exit 1; }
grep -Eq "$pin" extensions.txt || { echo "extensions.txt pins no extension"; exit 1; }
echo "SHA256SUMS: $(printf '%s\n' "$listed" | wc -l) files, each as listed"
