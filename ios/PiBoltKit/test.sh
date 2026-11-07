#!/bin/sh
# Runs the package's tests. With only the command-line tools (no Xcode), the Swift Testing macros live in a plugin
# directory SwiftPM does not search: pass it explicitly.
set -e
cd "$(dirname "$0")"
plugins="$(xcode-select -p 2>/dev/null)/usr/lib/swift/host/plugins/testing"
if [ -d "$plugins" ] && xcode-select -p | grep -q CommandLineTools; then
	exec swift test -Xswiftc -plugin-path -Xswiftc "$plugins" "$@"
fi
exec swift test "$@"
