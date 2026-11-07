#!/bin/sh
# Runs octet (github.com/skaft-software/octet) as the bench tools run Pi: Pi's arguments in, octet's out, against the run's fake
# model. OCTET_BIN: the octet executable. PI_CODING_AGENT_DIR: the run's Pi home, where harness.pi_home writes octet's settings
# (octet-home/.octet: a custom provider for the fake model, automatic compaction and cache warming off). Full access (unsafe_host,
# octet's default). On Windows the tools start octet directly instead (harness.octet_argv).
# Example: bench/benchmark.py --build "octet=env OCTET_BIN=<octet> bench/octet.sh"
export HOME="$PI_CODING_AGENT_DIR/octet-home" OCTET_OFFLINE=true
# (Pi's --model fake/fake-model is dropped: the settings name the model. octet takes --no-session only with -p.)
case "$1" in
	--version) exec "$OCTET_BIN" --version ;;
	-p) shift; prompt=""; for a; do prompt="$a"; done; exec "$OCTET_BIN" -p --no-session "$prompt" ;;
	*) exec "$OCTET_BIN" ;;
esac
