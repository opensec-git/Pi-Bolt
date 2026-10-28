#!/bin/bash
# Fetches every program of the forks benchmark for Linux x86-64, checks each against its release's checksums (and signature
# where the release has one), and unpacks two copies of each into out/<name> and out/<name>2 (the second copy: the noise
# floor). Writes what it fetched to results/environment.txt. GH_TOKEN only for `gh attestation verify` (PiG).
set -euo pipefail
mkdir -p .work results out
E=results/environment.txt
dl() { curl -fsSL --retry 3 -o "$2" "$1"; }

# Pi-Bolt 1.0.6 as released: SHA256SUMS signed with keys/release.pub.
d=.work/bolt; mkdir -p $d
for f in pi-bolt-linux-x64.tar.xz SHA256SUMS SHA256SUMS.sig; do dl "https://github.com/opensec-git/Pi-Bolt/releases/download/bolt-v1.0.6/$f" "$d/$f"; done
openssl pkeyutl -verify -pubin -inkey keys/release.pub -rawin -in $d/SHA256SUMS -sigfile $d/SHA256SUMS.sig
[ "$(head -n 1 $d/SHA256SUMS)" = "# pi-bolt 1.0.6" ]
(cd $d && grep -E " pi-bolt-linux-x64\.tar\.xz\$" SHA256SUMS | sha256sum -c -)
for n in bolt bolt2; do mkdir -p out/$n && tar -C out/$n --strip-components=1 -xJf $d/pi-bolt-linux-x64.tar.xz; done
echo "bolt: Pi-Bolt 1.0.6 pi-bolt-linux-x64.tar.xz (SHA256SUMS signature and sum verified)" >>$E

# Stock Pi 1.4.0 from npm, no install scripts, on Node.
for n in stockpi stockpi2; do
  mkdir -p out/$n && (cd out/$n && npm init -y >/dev/null && npm install --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent@1.4.0 >/dev/null)
done
echo "stockpi: @earendil-works/pi-coding-agent@1.4.0 (npm, --ignore-scripts) on Node $(node --version)" >>$E

# PiG 0.5.3: SHA256SUMS and its sigstore bundle (build provenance).
d=.work/pig; mkdir -p $d; a=pig-0.5.3-linux-amd64.tar.gz
for f in $a $a.sigstore.json SHA256SUMS; do dl "https://github.com/MichaelKinsy/PiG/releases/download/v0.5.3/$f" "$d/$f"; done
(cd $d && grep " $a\$" SHA256SUMS | sha256sum -c -)
gh attestation verify $d/$a --bundle $d/$a.sigstore.json --repo MichaelKinsy/PiG >/dev/null
for n in pig pig2; do mkdir -p out/$n && tar -C out/$n --strip-components=1 -xzf $d/$a; done
echo "pig: $a (SHA256SUMS and sigstore bundle verified)" >>$E

# pi_agent_rust 0.8.0: SHA256SUMS and minisign signatures (key minisign.pub of the repository at v0.8.0).
d=.work/pirust; mkdir -p $d; a=pi-linux-amd64.tar.xz
for f in $a $a.minisig SHA256SUMS SHA256SUMS.minisig; do dl "https://github.com/Dicklesworthstone/pi_agent_rust/releases/download/v0.8.0/$f" "$d/$f"; done
P=RWTQGPeLsnm9G7VFdFWkkcRi3wJK/PqsYxWC+oLNN74W9IjBxRU1Xu70
minisign -Vm $d/SHA256SUMS -P $P >/dev/null && minisign -Vm $d/$a -P $P >/dev/null
(cd $d && grep " $a\$" SHA256SUMS | sha256sum -c -)
for n in pirust pirust2; do mkdir -p out/$n && tar -C out/$n -xJf $d/$a; done
echo "pirust: pi_agent_rust $a v0.8.0 (SHA256SUMS and minisign verified)" >>$E

# oh-my-pi 18.13.2: SHA256SUMS.txt.
d=.work/omp; mkdir -p $d
for f in omp-linux-x64 SHA256SUMS.txt; do dl "https://github.com/can1357/oh-my-pi/releases/download/v18.13.2/$f" "$d/$f"; done
(cd $d && grep -E " \*?omp-linux-x64\$" SHA256SUMS.txt | sed 's/ \*/  /' | sha256sum -c -)
for n in omp omp2; do mkdir -p out/$n && install -m 755 $d/omp-linux-x64 out/$n/omp; done
echo "omp: oh-my-pi omp-linux-x64 v18.13.2 (SHA256SUMS.txt verified)" >>$E

# rpi 0.11.0 (bigfish1913/pi-rust): the archive's .sha256.
d=.work/rpi; mkdir -p $d; a=rpi-v0.11.0-x86_64-unknown-linux-gnu.tar.gz
for f in $a $a.sha256; do dl "https://github.com/bigfish1913/pi-rust/releases/download/v0.11.0/$f" "$d/$f"; done
(cd $d && sha256sum -c $a.sha256)
for n in rpi rpi2; do mkdir -p out/$n && tar -C out/$n -xzf $d/$a; done
echo "rpi: $a (sha256 verified)" >>$E

# OpenCode 2.2.0: SHA256SUMS.
d=.work/opencode; mkdir -p $d; a=opencode-linux-x64.tar.gz
for f in $a SHA256SUMS; do dl "https://github.com/anomalyco/opencode/releases/download/v2.2.0/$f" "$d/$f"; done
(cd $d && grep " $a\$" SHA256SUMS | sha256sum -c -)
for n in opencode opencode2; do mkdir -p out/$n && tar -C out/$n -xzf $d/$a; done
echo "opencode: $a v2.2.0 (SHA256SUMS verified)" >>$E

find out -maxdepth 2 -type f \( -name pi -o -name pig -o -name omp -o -name rpi -o -name opencode \) | sort
