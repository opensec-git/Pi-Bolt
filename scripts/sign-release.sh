#!/usr/bin/env bash
# Signs a release: SHA256SUMS.sig is the Ed25519 signature of SHA256SUMS. The installer checks it with the public key it carries
# (openssl pkeyutl -verify), so a download is known to be Pi-Bolt's and not only intact. The key pair is made once, by a
# maintainer, with:
#   openssl genpkey -algorithm ed25519 -out signing-key.pem && openssl pkey -in signing-key.pem -pubout -out keys/release.pub
# The publish workflow signs with the same key, a secret of the release environment; this script signs a draft by hand, before
# publish (docs/RELEASING.md). The public key is keys/release.pub, and copied into install.sh (RELEASE_KEY).
# Usage: scripts/sign-release.sh --key signing-key.pem dist/<version>
source "$(dirname "$0")/lib/common.sh"
need openssl

KEY=""
while [ $# -gt 1 ]; do
	case "$1" in
	--key) KEY="$2"; shift ;;
	*) die "unknown option $1" ;;
	esac
	shift
done
DIST="${1:-}"
[ -n "$KEY" ] && [ -f "$KEY" ] || die "--key FILE is required"
[ -f "$DIST/SHA256SUMS" ] || die "no SHA256SUMS in $DIST"
openssl pkeyutl -sign -inkey "$KEY" -rawin -in "$DIST/SHA256SUMS" -out "$DIST/SHA256SUMS.sig"
openssl pkeyutl -verify -pubin -inkey "$PIBOLT_ROOT/keys/release.pub" -rawin -in "$DIST/SHA256SUMS" -sigfile "$DIST/SHA256SUMS.sig" >/dev/null ||
	die "the signature does not verify with keys/release.pub: is --key the release key?"
log "signed $DIST/SHA256SUMS (SHA256SUMS.sig)"
