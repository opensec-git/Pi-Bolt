#!/usr/bin/env bash
# Points https://pi-bolt.opensec.in/android, and the home page's Android tab, at an Android app release: writes android.json
# on the gh-pages branch with the APK's URL, size, SHA-256 and signing certificate digest. The facts come from the published
# release (the APK is downloaded and its checksum checked), so the site offers exactly what GitHub serves. The Android
# release workflow runs this after publishing; run it by hand to point the site at another release.
# Usage: scripts/publish-android-download.sh VERSION [--dry-run]   (the release android-vVERSION; --dry-run prints the JSON)
# Writes through GitHub's contents API: one commit on gh-pages, nothing rewritten, no checkout needed.
source "$(dirname "$0")/lib/common.sh"
need gh
need python3
VERSION="${1:-}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: publish-android-download.sh VERSION [--dry-run] (VERSION like 0.1.0)"
DRY_RUN="${2:-}"
REPO="${GITHUB_REPOSITORY:-opensec-git/Pi-Bolt}"
TAG="android-v$VERSION"
APK="pi-bolt-android-$VERSION.apk"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

gh release download "$TAG" --repo "$REPO" --dir "$WORK" --pattern "$APK" --pattern "$APK.sha256"
gh release view "$TAG" --repo "$REPO" --json body --jq .body > "$WORK/notes.md"
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
if [[ "$DRY_RUN" == "--dry-run" ]]; then
	cat "$WORK/android.json"
	exit 0
fi
# The current file's blob, if any: the API replaces a file only when told which version it replaces.
SHA="$(gh api "repos/$REPO/contents/android.json?ref=gh-pages" --jq .sha 2>/dev/null || true)"
if [[ -n "$SHA" ]] && [[ "$(gh api "repos/$REPO/contents/android.json?ref=gh-pages" --jq .content 2>/dev/null | base64 --decode 2>/dev/null)" == "$(cat "$WORK/android.json")" ]]; then
	log "gh-pages already offers Android app $VERSION"
	exit 0
fi
python3 - "$WORK/android.json" "$VERSION" "$SHA" > "$WORK/request.json" <<'PY'
import base64, json, sys
path, version, sha = sys.argv[1:]
request = {"branch": "gh-pages", "message": f"chore(pages): offer Android app {version}",
           "content": base64.b64encode(open(path, "rb").read()).decode()}
if sha:
    request["sha"] = sha
json.dump(request, sys.stdout)
PY
gh api -X PUT "repos/$REPO/contents/android.json" --input "$WORK/request.json" --jq .commit.sha >/dev/null
# android.html names a release too, as its fallback while android.json loads: point it at this one.
PAGE_SHA="$(gh api "repos/$REPO/contents/android.html?ref=gh-pages" --jq .sha 2>/dev/null || true)"
if [[ -n "$PAGE_SHA" ]]; then
	gh api "repos/$REPO/contents/android.html?ref=gh-pages" --jq .content | base64 --decode > "$WORK/android.html"
	python3 - "$WORK/android.html" "$WORK/android.json" "$PAGE_SHA" > "$WORK/page-request.json" <<'PY'
import base64, json, re, sys
page, release_path, sha = sys.argv[1:]
html = open(page, encoding="utf-8").read()
release = json.load(open(release_path))
version = release["version"]
mb = round(release["size"] / 1048576)
new = re.sub(r"releases/download/android-v[0-9.]+/pi-bolt-android-[0-9.]+\.apk", f"releases/download/android-v{version}/pi-bolt-android-{version}.apk", html)
new = re.sub(r"releases/tag/android-v[0-9.]+\">android-v[0-9.]+", f"releases/tag/android-v{version}\">android-v{version}", new)
new = re.sub(r"Pi-Bolt for Android · [0-9.]+", f"Pi-Bolt for Android · {version}", new)
new = re.sub(r"Version [0-9.]+ · about [0-9]+ MB", f"Version {version} · about {mb} MB", new)
if new != html:
    json.dump({"branch": "gh-pages", "message": f"chore(pages): Android page falls back to {version}",
               "content": base64.b64encode(new.encode("utf-8")).decode(), "sha": sha}, sys.stdout)
PY
	if [[ -s "$WORK/page-request.json" ]]; then
		gh api -X PUT "repos/$REPO/contents/android.html" --input "$WORK/page-request.json" --jq .commit.sha >/dev/null || warn "could not update android.html's fallback release"
	fi
fi
# A commit made with a workflow's GITHUB_TOKEN does not start a Pages build by itself: request one.
gh api -X POST "repos/$REPO/pages/builds" --jq .status >/dev/null 2>&1 || warn "could not request a GitHub Pages build; the site updates with the next one"
log "pi-bolt.opensec.in/android now offers Android app $VERSION"
