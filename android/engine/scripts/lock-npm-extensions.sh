#!/usr/bin/env bash
# Lock the runtime dependencies of the third-party extensions in npm/extensions.txt: writes npm/<name>.lock.json, an
# npm lockfile (v3) of the package's `dependencies` (its devDependencies dropped; peers are Pi's own modules and are
# never installed, as for Pi's own npm installs: --legacy-peer-deps). Nothing is resolved that was published after
# --before (default: 14 days ago). Fails on a pinned tarball whose integrity differs, and on a dependency with install
# scripts (the pack never runs them; one that needs them must be reviewed first). Run on the host; needs node and npm.
#
#   android/engine/scripts/lock-npm-extensions.sh [--before 2026-10-24T00:00:00Z]
set -euo pipefail
HERE=$(cd "$(dirname "$0")/.." && pwd)
BEFORE=$(node -e 'console.log(new Date(Date.now() - 14 * 86400e3).toISOString())')
[ "${1-}" = "--before" ] && BEFORE=$2
REGISTRY=https://registry.npmjs.org/
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
grep -v '^#' "$HERE/npm/extensions.txt" | while read -r name version integrity; do
	[ -n "$name" ] || continue
	dir="$WORK/${name//\//-}"
	mkdir -p "$dir/pack" "$dir/lock"
	tarball=$(cd "$dir/pack" && npm pack "$name@$version" --registry="$REGISTRY" --ignore-scripts --silent)
	got=$(node -e 'const c=require("crypto"),f=require("fs");console.log("sha512-"+c.createHash("sha512").update(f.readFileSync(process.argv[1])).digest("base64"))' "$dir/pack/$tarball")
	[ "$got" = "$integrity" ] || { echo "$name@$version: integrity $got != pinned $integrity" >&2; exit 1; }
	tar -xzf "$dir/pack/$tarball" -C "$dir/pack" package/package.json
	node -e '
		const fs = require("fs");
		const p = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
		const out = { name: p.name, version: p.version, license: p.license, dependencies: p.dependencies ?? {} };
		fs.writeFileSync(process.argv[2], JSON.stringify(out, null, 2) + "\n");
	' "$dir/pack/package/package.json" "$dir/lock/package.json"
	(cd "$dir/lock" && npm install --package-lock-only --ignore-scripts --legacy-peer-deps --omit=dev --no-audit --no-fund \
		--registry="$REGISTRY" --before="$BEFORE" --silent)
	node -e '
		const fs = require("fs");
		const lock = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
		const bad = Object.entries(lock.packages).filter(([k, v]) => k && v.hasInstallScript).map(([k]) => k);
		if (bad.length) { console.error(`${process.argv[2]}: dependencies with install scripts: ${bad.join(", ")}`); process.exit(1); }
		for (const [k, v] of Object.entries(lock.packages)) {
			if (k && (!v.resolved?.startsWith("https://registry.npmjs.org/") || !v.integrity?.startsWith("sha512-"))) {
				console.error(`${process.argv[2]}: ${k} is not a registry tarball with sha512 integrity`);
				process.exit(1);
			}
		}
		lock.before = process.argv[3];
		fs.writeFileSync(process.argv[4], JSON.stringify(lock, null, 2) + "\n");
		console.log(`${process.argv[2]}: ${Object.keys(lock.packages).length - 1} dependencies, none with install scripts`);
	' "$dir/lock/package-lock.json" "$name@$version" "$BEFORE" "$HERE/npm/$(echo "$name" | sed 's|^@||; s|/|-|').lock.json"
done
