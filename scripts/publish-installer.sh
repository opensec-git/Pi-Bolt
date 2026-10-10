#!/usr/bin/env bash
# Publishes install.sh, install.ps1 (Windows: `irm https://pi-bolt.opensec.in/install.ps1 | iex`) and site/ (the home page, the
# crash-report page, the logo and the page's font) to the gh-pages branch, which GitHub Pages serves at https://pi-bolt.opensec.in.
# Usage: scripts/publish-installer.sh [REMOTE]     (default remote: origin). Adds a commit to gh-pages; never rewrites it.
source "$(dirname "$0")/lib/common.sh"
need git
REMOTE="${1:-origin}"
WORKTREE="$(mktemp -d)"
trap 'git -C "$PIBOLT_ROOT" worktree remove --force "$WORKTREE" 2>/dev/null || true' EXIT
git -C "$PIBOLT_ROOT" fetch -q "$REMOTE" gh-pages
git -C "$PIBOLT_ROOT" worktree add -q --detach "$WORKTREE" FETCH_HEAD
cp "$PIBOLT_ROOT/install.sh" "$WORKTREE/install.sh"
cp "$PIBOLT_ROOT/install.ps1" "$WORKTREE/install.ps1"
cp "$PIBOLT_ROOT"/site/* "$WORKTREE/"
git -C "$WORKTREE" add install.sh install.ps1 "$WORKTREE"/*.html "$WORKTREE"/*.svg "$WORKTREE"/*.woff2 "$WORKTREE"/*.txt
# (Staged first, so that a file gh-pages does not have yet counts as a change.)
if git -C "$WORKTREE" diff --cached --quiet; then
	log "gh-pages already has these installers and site"
	exit 0
fi
git -C "$WORKTREE" commit -q -m "chore(pages): update the installers and site"
git -C "$WORKTREE" push -q "$REMOTE" HEAD:gh-pages
log "published install.sh and install.ps1 to $REMOTE gh-pages"
