#!/usr/bin/env bash
# Signs a release: SHA256SUMS.sig is the Ed25519 signature of SHA256SUMS, and SHA256SUMS.sshsig the same key's signature in
# OpenSSH's format. The installer checks one of them with the public key it carries (openssl pkeyutl -verify with OpenSSL 3,
# ssh-keygen -Y verify where there is none, as on macOS), so a download is known to be Pi-Bolt's and not only intact. The key pair is made once, by a
# maintainer, with:
#   openssl genpkey -algorithm ed25519 -out signing-key.pem && openssl pkey -in signing-key.pem -pubout -out keys/release.pub
# The publish workflow signs with the same key, a secret of the release environment; this script signs a draft by hand, before
# publish (docs/RELEASING.md). The public key is keys/release.pub, and copied into install.sh (RELEASE_KEY).
# Usage: scripts/sign-release.sh --key signing-key.pem dist/<version>
source "$(dirname "$0")/lib/common.sh"
need openssl
need node

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
node "$PIBOLT_ROOT/scripts/lib/sshsig.mjs" sign "$KEY" "$DIST/SHA256SUMS" "$DIST/SHA256SUMS.sshsig"
# (Checked here where ssh-keygen can verify: -Y is OpenSSH 8.1 and later; the publish workflow checks it in any case.)
probe=$(ssh-keygen -Y find-principals 2>&1 || true)
if command -v ssh-keygen >/dev/null 2>&1 && ! printf '%s' "$probe" | grep -qiE "illegal option|unknown option|invalid option"; then
	signers="$(mktemp)"
	printf 'pi-bolt namespaces="pi-bolt-release" %s\n' "$(node "$PIBOLT_ROOT/scripts/lib/sshsig.mjs" pubkey "$PIBOLT_ROOT/keys/release.pub")" >"$signers"
	ssh-keygen -Y verify -f "$signers" -I pi-bolt -n pi-bolt-release -s "$DIST/SHA256SUMS.sshsig" <"$DIST/SHA256SUMS" >/dev/null ||
		{ rm -f "$signers"; die "SHA256SUMS.sshsig does not verify with keys/release.pub"; }
	rm -f "$signers"
fi
log "signed $DIST/SHA256SUMS (SHA256SUMS.sig, SHA256SUMS.sshsig)"
