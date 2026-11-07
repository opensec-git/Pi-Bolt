#!/usr/bin/env bash
# Points https://pi-bolt.opensec.in/android, and the home page's Android tab, at an Android app release: writes android.json
# on the gh-pages branch with the APK's URL, size, SHA-256 and signing certificate digest, and points android.html's fallback
# at the same release. The facts come from the published release (the APK is downloaded and its checksum checked), so the
# site offers exactly what GitHub serves. The Android release workflow runs this after publishing; run it by hand to point
# the site at another release, or to rebuild the site when gh-pages is right but the site still serves an older version.
# Usage: scripts/publish-android-download.sh VERSION [--dry-run]
#   (the release android-vVERSION; --dry-run prints the JSON and which files would change, and writes nothing)
# Both files go in one commit through GitHub's Git Data API (blobs, a tree, a commit, then the ref), with no checkout: two
# commits seconds apart start two Pages builds that cancel each other, and the site kept the old version. The ref moves
# only if gh-pages is still the commit this started from (no force), so a concurrent change fails this run instead of
# being lost. Then it waits until the site serves the new files, requesting a Pages build only if the push's own build
# does not deliver them (a second build of the same commit is what collides), at most twice.
# PAGES_POLL_SECONDS (5) and PAGES_POLL_TRIES (24) set how long each wait for the site lasts.
source "$(dirname "$0")/lib/common.sh"
need gh
need python3
need curl
VERSION="${1:-}"
DRY_RUN="${2:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && ( -z "$DRY_RUN" || "$DRY_RUN" == "--dry-run" ) ]] ||
	die "usage: publish-android-download.sh VERSION [--dry-run] (VERSION like 0.1.0)"
REPO="${GITHUB_REPOSITORY:-opensec-git/Pi-Bolt}"
TAG="android-v$VERSION"
APK="pi-bolt-android-$VERSION.apk"
PAGES_POLL_SECONDS="${PAGES_POLL_SECONDS:-5}"
PAGES_POLL_TRIES="${PAGES_POLL_TRIES:-24}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# field NAME: a value from the JSON object on stdin ("object.sha" reaches into nested objects); empty when missing.
field() {
	python3 -c '
import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].split("."):
    value = value.get(key) if isinstance(value, dict) else None
print("" if value is None else value)' "$1"
}

gh release download "$TAG" --repo "$REPO" --dir "$WORK" --pattern "$APK" --pattern "$APK.sha256"
gh release view "$TAG" --repo "$REPO" --json body | field body > "$WORK/notes.md"
python3 - "$WORK" "$APK" "$VERSION" "$TAG" "$REPO" > "$WORK/android.json" <<'PY'
import hashlib, json, os, re, sys
work, apk, version, tag, repo = sys.argv[1:]
path = os.path.join(work, apk)
digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
published = open(path + ".sha256").read().split()[0]
if digest != published:
    sys.exit(f"{apk}: SHA-256 {digest} does not match the release's {published}")
# The release notes carry `apksigner verify --print-certs` output: "... certificate SHA-256 digest: <hex>".
cert = re.search(r"SHA-256 digest:\s*([0-9a-f]{64})", open(os.path.join(work, "notes.md")).read())
if not cert:
    sys.exit(f"{tag}: no signing certificate digest in the release notes")
json.dump({
    "version": version,
    "apk": f"https://github.com/{repo}/releases/download/{tag}/{apk}",
    "page": f"https://github.com/{repo}/releases/tag/{tag}",
    "size": os.path.getsize(path),
    "sha256": digest,
    "certSha256": cert.group(1),
}, sys.stdout, indent=2)
print()
PY
[[ "$DRY_RUN" == "--dry-run" ]] && cat "$WORK/android.json"

# gh-pages as it is now. Everything below is read from this commit, and the ref moves only if it is still this commit.
HEAD="$(gh api "repos/$REPO/git/ref/heads/gh-pages" | field object.sha)"
[[ -n "$HEAD" ]] || die "could not read the gh-pages branch of $REPO"
mkdir "$WORK/current" "$WORK/next"
for name in android.json android.html; do
	gh api -H "Accept: application/vnd.github.raw+json" "repos/$REPO/contents/$name?ref=$HEAD" > "$WORK/current/$name" 2>/dev/null ||
		rm -f "$WORK/current/$name"
done
[[ -f "$WORK/current/android.html" ]] || warn "gh-pages has no android.html (or it could not be read): only android.json changes"
# The files that change go to next/: android.json, and android.html, which names a release too, as its fallback while
# android.json loads.
python3 - "$WORK" <<'PY'
import json, os, re, sys
work = sys.argv[1]
def read(*parts):
    try:
        return open(os.path.join(work, *parts), "rb").read()
    except FileNotFoundError:
        return None
release_bytes = read("android.json")
if read("current", "android.json") != release_bytes:
    open(os.path.join(work, "next", "android.json"), "wb").write(release_bytes)
page = read("current", "android.html")
if page is not None:
    html = page.decode("utf-8")
    release = json.loads(release_bytes)
    version = release["version"]
    mb = round(release["size"] / 1048576)
    new = re.sub(r"releases/download/android-v[0-9.]+/pi-bolt-android-[0-9.]+\.apk", f"releases/download/android-v{version}/pi-bolt-android-{version}.apk", html)
    new = re.sub(r"releases/tag/android-v[0-9.]+\">android-v[0-9.]+", f"releases/tag/android-v{version}\">android-v{version}", new)
    new = re.sub(r"Pi-Bolt for Android · [0-9.]+", f"Pi-Bolt for Android · {version}", new)
    new = re.sub(r"Version [0-9.]+ · about [0-9]+ MB", f"Version {version} · about {mb} MB", new)
    if new != html:
        open(os.path.join(work, "next", "android.html"), "wb").write(new.encode("utf-8"))
PY
mapfile -t CHANGED < <(ls "$WORK/next")

# The site serves FILE (in FROM) now; a query string per try gets past the CDN's cache.
serves() {
	local name="$1" from="$2" try="$3"
	curl -fsSL --max-time 30 "${SITE%/}/$name?v=${COMMIT:0:12}-$try" -o "$WORK/live" 2>/dev/null && cmp -s "$WORK/live" "$from/$name"
}
# Waits, bounded, until the site serves every file of `$@` (names in FROM, the first argument).
wait_for_site() {
	local from="$1" try name ok
	shift
	for ((try = 1; try <= PAGES_POLL_TRIES; try++)); do
		ok=1
		for name in "$@"; do serves "$name" "$from" "$REQUESTS-$try" || { ok=0; break; }; done
		((ok)) && return 0
		sleep "$PAGES_POLL_SECONDS"
	done
	return 1
}
REQUESTS=0
request_build() {
	REQUESTS=$((REQUESTS + 1))
	gh api -X POST "repos/$REPO/pages/builds" >/dev/null || warn "could not request a GitHub Pages build"
}
SITE="${PAGES_URL:-$(gh api "repos/$REPO/pages" | field html_url)}"
[[ -n "$SITE" ]] || die "could not read the GitHub Pages address of $REPO"

if ((${#CHANGED[@]} == 0)); then
	COMMIT="$HEAD"
	mapfile -t PUBLISHED < <(ls "$WORK/current")
	# gh-pages is right; the site may not be (its build failed). One look, then a build if it serves something else.
	if PAGES_POLL_SECONDS=0 PAGES_POLL_TRIES=1 wait_for_site "$WORK/current" "${PUBLISHED[@]}"; then
		log "gh-pages and $SITE already offer Android app $VERSION"
		exit 0
	fi
	if [[ "$DRY_RUN" == "--dry-run" ]]; then
		log "dry run: gh-pages already offers Android app $VERSION, $SITE does not yet: would request a Pages build"
		exit 0
	fi
	log "gh-pages already offers Android app $VERSION, but $SITE does not serve it yet: requesting a Pages build"
	for attempt in 1 2; do
		request_build
		wait_for_site "$WORK/current" "${PUBLISHED[@]}" && { log "$SITE now offers Android app $VERSION"; exit 0; }
		warn "Pages build $attempt did not deliver android.json"
	done
	die "$SITE still serves an older android.json; see https://github.com/$REPO/actions/workflows/pages/pages-build-deployment"
fi
if [[ "$DRY_RUN" == "--dry-run" ]]; then
	log "dry run: would commit ${CHANGED[*]} on gh-pages (${HEAD:0:7})"
	exit 0
fi

BASE_TREE="$(gh api "repos/$REPO/git/commits/$HEAD" | field tree.sha)"
[[ -n "$BASE_TREE" ]] || die "could not read gh-pages commit $HEAD"
ENTRIES=()
for name in "${CHANGED[@]}"; do
	python3 -c 'import base64, json, sys; json.dump({"content": base64.b64encode(open(sys.argv[1], "rb").read()).decode(), "encoding": "base64"}, sys.stdout)' \
		"$WORK/next/$name" > "$WORK/blob.json"
	BLOB="$(gh api -X POST "repos/$REPO/git/blobs" --input "$WORK/blob.json" | field sha)"
	[[ -n "$BLOB" ]] || die "could not upload $name"
	ENTRIES+=("$name=$BLOB")
done
python3 - "$BASE_TREE" "${ENTRIES[@]}" > "$WORK/tree.json" <<'PY'
import json, sys
base, *entries = sys.argv[1:]
json.dump({"base_tree": base, "tree": [
    {"path": path, "mode": "100644", "type": "blob", "sha": sha}
    for path, sha in (entry.split("=", 1) for entry in entries)
]}, sys.stdout)
PY
TREE="$(gh api -X POST "repos/$REPO/git/trees" --input "$WORK/tree.json" | field sha)"
[[ -n "$TREE" ]] || die "could not create the gh-pages tree"
python3 -c 'import json, sys; json.dump({"message": sys.argv[1], "tree": sys.argv[2], "parents": [sys.argv[3]]}, sys.stdout)' \
	"chore(pages): offer Android app $VERSION" "$TREE" "$HEAD" > "$WORK/commit.json"
COMMIT="$(gh api -X POST "repos/$REPO/git/commits" --input "$WORK/commit.json" | field sha)"
[[ -n "$COMMIT" ]] || die "could not create the gh-pages commit"
# Not a force update: GitHub refuses it unless gh-pages is still HEAD (the new commit's parent).
python3 -c 'import json, sys; json.dump({"sha": sys.argv[1], "force": False}, sys.stdout)' "$COMMIT" > "$WORK/ref.json"
gh api -X PATCH "repos/$REPO/git/refs/heads/gh-pages" --input "$WORK/ref.json" >/dev/null ||
	die "gh-pages changed while this ran (it is no longer ${HEAD:0:7}); nothing was published. Run it again."
log "gh-pages ${COMMIT:0:7}: ${CHANGED[*]} offer Android app $VERSION"

# The push starts a Pages build by itself. Requesting another for the same commit at once makes the two cancel each
# other's deployment, so a build is requested only when the site does not serve the new files after a while.
wait_for_site "$WORK/next" "${CHANGED[@]}" && { log "$SITE now offers Android app $VERSION"; exit 0; }
for attempt in 1 2; do
	warn "$SITE does not serve ${CHANGED[*]} of ${COMMIT:0:7} yet: requesting a Pages build ($attempt of 2)"
	request_build
	wait_for_site "$WORK/next" "${CHANGED[@]}" && { log "$SITE now offers Android app $VERSION"; exit 0; }
done
die "$SITE still serves older files than gh-pages ${COMMIT:0:7}; see https://github.com/$REPO/actions/workflows/pages/pages-build-deployment"
