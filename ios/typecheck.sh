#!/bin/sh
# Typechecks the app (ios/PiBolt) and its tests (ios/PiBoltTests) without Xcode: against the Mac Catalyst variant of
# the command-line tools' macOS SDK, which has UIKit and the iOS SwiftUI. It catches most compile errors before CI; the
# iOS build in CI (.github/workflows/ios.yml) stays the reference, since a few iOS-only APIs differ.
#
# SwiftUI's macros (`@State` in the macOS 27 SDK) need a plugin only Xcode has, so the newest SDK without them is used:
# a macOS 26 SDK (`IOS_TYPECHECK_SDK` picks another).
set -e
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${IOS_TYPECHECK_SDK:-$(ls -d /Library/Developer/CommandLineTools/SDKs/MacOSX26.*.sdk 2>/dev/null | sort | tail -n 1)}"
[ -d "$sdk" ] || { echo "no macOS 26 SDK in the command-line tools; set IOS_TYPECHECK_SDK" >&2; exit 1; }
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
target=arm64-apple-ios17.0-macabi
fw="$sdk/System/iOSSupport/System/Library/Frameworks"
clt=/Library/Developer/CommandLineTools
common="-target $target -sdk $sdk -Fsystem $fw -swift-version 5 -I $out"
for m in PiBoltProtocol PiBoltMarkdown; do
	# shellcheck disable=SC2046,SC2086
	swiftc -emit-module -module-name $m $common -parse-as-library -emit-module-path "$out/$m.swiftmodule" $(find "$here/PiBoltKit/Sources/$m" -name '*.swift')
done
# shellcheck disable=SC2046,SC2086
swiftc -emit-module -module-name PiBolt $common -enable-testing -emit-module-path "$out/PiBolt.swiftmodule" $(find "$here/PiBolt" -name '*.swift')
# shellcheck disable=SC2086
swiftc -typecheck $common -F "$clt/Library/Developer/Frameworks" -I "$clt/Library/Developer/usr/lib" \
	-plugin-path "$clt/usr/lib/swift/host/plugins/testing" "$here"/PiBoltTests/*.swift
echo "typecheck ok"
