#!/usr/bin/env bash
# Publishes each build of a release to npm as pi-bolt-PLATFORM-VARIANT@VERSION (pi-bolt-linux-x64, pi-bolt-darwin-arm64, ...;
# Windows' as @kushalkhemka/pi-bolt-win32-VARIANT, as npm refuses the unscoped names):
# a package that holds the release's .tar.xz (Windows: .zip), byte for byte. The installers download it from the npm registry,
# a CDN that is fast where GitHub's release downloads are slow, and check it against the release's SHA256SUMS on GitHub, as
# they do a download from GitHub. On Windows the pi-bolt package's install script (npm/install.cjs) downloads it the same
# way, by its URL in the registry: it is not a dependency of pi-bolt, so a package manager never installs it unchecked.
#
# Usage: scripts/publish-npm-builds.sh DIST [--pack OUT] [-- NPM PUBLISH OPTIONS]
#   DIST     dist/<version>, as package-release.sh makes it (the .tar.xz files and SHA256SUMS)
#   --pack   only make the packages (.tgz) in OUT, to test them
# A version that npm already has is skipped, so running it again is safe. With PIBOLT_NPM_EXISTING_ONLY=1 (the publish workflow:
# trusted publishing can only publish packages that exist), a package npm does not have yet is skipped, with a warning; it is
# published the first time by hand (docs/RELEASING.md), and a build npm refuses is skipped with a warning (the installers then
# download it from GitHub), so that the pi-bolt package is still published.
source "$(dirname "$0")/lib/common.sh"

DIST="${1:?usage: scripts/publish-npm-builds.sh DIST [--pack OUT] [-- npm publish options]}"
shift
PACK=""
if [ "${1:-}" = --pack ]; then
	PACK="$(abspath "${2:?--pack needs a folder}")"
	shift 2
fi
[ "${1:-}" != -- ] || shift
VERSION="$(basename "$(realpath "$DIST")")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "$DIST: the folder's name must be the version (dist/X.Y.Z)"
[ -f "$DIST/SHA256SUMS" ] || die "no SHA256SUMS in $DIST"
need npm

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
# Every build in DIST: those of each platform, put together for the release. (Windows' are .zip files: install.ps1 unpacks them
# with what Windows has.)
builds=()
for file in "$DIST"/pi-bolt-*.tar.xz "$DIST"/pi-bolt-win32-*.zip; do
	[ -f "$file" ] && builds+=("$(basename "$file")")
done
[ ${#builds[@]} -gt 0 ] || die "no pi-bolt-*.tar.xz or pi-bolt-win32-*.zip in $DIST"
# A platform's builds come together: all of them, or none (a missing one is a mistake, as it always was on Linux).
for set in "linux-x64 linux-x64-baseline linux-x64-jit" "linux-x64-musl linux-x64-musl-baseline linux-x64-musl-jit" "linux-arm64 linux-arm64-jit" "darwin-arm64 darwin-arm64-jit" "win32-x64 win32-x64-jit"; do
	ext=tar.xz
	case "$set" in win32-*) ext=zip ;; esac
	present=0
	for platform in $set; do [ -f "$DIST/pi-bolt-$platform.$ext" ] && present=$((present + 1)); done
	if [ "$present" -gt 0 ]; then
		for platform in $set; do [ -f "$DIST/pi-bolt-$platform.$ext" ] || die "no pi-bolt-$platform.$ext in $DIST"; done
	fi
done
for file in "${builds[@]}"; do
	name="${file%.tar.xz}"
	name="${name%.zip}"
	platform="${name#pi-bolt-}"
	case "$platform" in
	linux-arm64*) os=linux cpu=arm64 ;;
	linux-*) os=linux cpu=x64 ;;
	darwin-*) os=darwin cpu=arm64 ;;
	win32-*) os=win32 cpu=x64 ;;
	*) die "$file: not a build of a known platform" ;;
	esac
	pkg="$name"
	[ "$os" != win32 ] || pkg="@kushalkhemka/$name"
	check_sum "$DIST" "$file" || die "$file does not match SHA256SUMS"
	if [ -z "$PACK" ] && [ "$(npm view "$pkg@$VERSION" version 2>/dev/null)" = "$VERSION" ]; then
		log "npm already has $pkg@$VERSION"
		continue
	fi
	if [ -z "$PACK" ] && [ "${PIBOLT_NPM_EXISTING_ONLY:-}" = 1 ] && [ -z "$(npm view "$pkg" name 2>/dev/null)" ]; then
		warn "npm has no package $pkg yet: skipped (publish it once by hand, then give it trusted publishing)"
		continue
	fi
	dir="$STAGE/$name"
	mkdir -p "$dir"
	cp "$DIST/$file" "$dir/"
	cat >"$dir/package.json" <<EOF
{
	"name": "$pkg",
	"version": "$VERSION",
	"description": "The Pi-Bolt $VERSION executable ($platform) for its installer. Install Pi-Bolt with the pi-bolt package or $(if [ "$os" = win32 ]; then echo install.ps1; else echo install.sh; fi).",
	"homepage": "https://github.com/opensec-git/Pi-Bolt",
	"repository": {
		"type": "git",
		"url": "git+https://github.com/opensec-git/Pi-Bolt.git"
	},
	"license": "MIT",
	"author": "OpenSec",
	"os": ["$os"],
	"cpu": ["$cpu"],
	"files": ["$file"]
}
EOF
	cat >"$dir/README.md" <<EOF
# $pkg

\`$file\` of the [Pi-Bolt $VERSION release](https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v$VERSION), the same
bytes, for Pi-Bolt's installer to download from the npm registry. Nothing to install from here: use

$(if [ "$os" = win32 ]; then printf '```powershell\npowershell -c "irm https://pi-bolt.opensec.in/install.ps1 | iex"\n```'; else printf '```bash\ncurl -fsSL https://pi-bolt.opensec.in/install.sh | sh\n```'; fi)

or \`npm install -g pi-bolt\`. Both check the file against the release's \`SHA256SUMS\` on GitHub, and their signature.
EOF
	if [ -n "$PACK" ]; then
		mkdir -p "$PACK"
		tgz="$(cd "$dir" && npm pack --silent --pack-destination "$PACK")"
		log "packed $PACK/$tgz"
	elif (cd "$dir" && npm publish --access public "$@"); then
		log "published $pkg@$VERSION"
	elif [ "${PIBOLT_NPM_EXISTING_ONLY:-}" = 1 ]; then
		warn "npm did not publish $pkg@$VERSION: skipped (the installers download it from GitHub)"
		[ -z "${GITHUB_ACTIONS:-}" ] || echo "::warning title=npm::npm did not publish $pkg@$VERSION"
	else
		die "npm did not publish $pkg@$VERSION"
	fi
done
