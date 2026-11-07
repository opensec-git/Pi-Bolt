#!/usr/bin/env bash
# The engine prefix setup (first-launch unpack, and an app update with the same pack), 0.6.0's code against this
# branch's, on a phone: app/src/test/.../bench/PrefixBench.kt run with app_process, reading the installed APK's assets.
# Nothing is installed: one dex in /data/local/tmp, removed afterwards, and a scratch directory there (about 100 MB
# while it runs).
#
#   android/tools/perf/prefix-bench.sh <serial> [rounds] [workers]       from android/, after ./gradlew :app:compileDebugUnitTestKotlin
set -euo pipefail
S=${1:?serial}
ROUNDS=${2:-2}
WORKERS=${3:-}
ADB=${ADB:-adb}
a() { "$ADB" -s "$S" "$@"; }
cd "$(dirname "$0")/../.."
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=${JAVA_HOME:+$JAVA_HOME/bin/}jar
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
APP=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
TEST=app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes
(cd "$APP" && "$JAR" cf "$WORK/app.jar" opensec/pibolt/app/engine/EnginePrefix*.class opensec/pibolt/app/engine/EngineManifest*.class \
	opensec/pibolt/app/engine/EnginePaths*.class opensec/pibolt/app/engine/AssetSource*.class opensec/pibolt/app/engine/BundledExtensions*.class \
	opensec/pibolt/app/data/SafeFiles*.class)
(cd "$TEST" && "$JAR" cf "$WORK/bench.jar" opensec/pibolt/app/bench/)
C=$HOME/.gradle/caches/modules-2/files-2.1
LIBS=$(find "$C/org.jetbrains.kotlin/kotlin-stdlib" "$C/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm" "$C/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm" \
	-name '*.jar' ! -name '*sources*' | grep -E 'kotlin-stdlib-2\.4\.21\.jar|serialization-(json|core)-jvm-1\.12\.0\.jar')
mkdir -p "$WORK/dex"
"$BT/d8" --release --min-api 31 --lib "$SDK/platforms/android-36/android.jar" --output "$WORK/dex" \
	"$WORK/app.jar" "$WORK/bench.jar" protocol/build/libs/protocol.jar $LIBS 2>/dev/null >/dev/null
APK=$(a shell pm path --user 0 in.opensec.pibolt | head -1 | sed 's/package://' | tr -d '\r')
a push -q "$WORK/dex/classes.dex" /data/local/tmp/pibolt-prefix-bench.dex
a shell "CLASSPATH=/data/local/tmp/pibolt-prefix-bench.dex app_process /system/bin opensec.pibolt.app.bench.PrefixBench $APK /data/local/tmp/pibolt-prefix-bench $ROUNDS $WORKERS"
a shell rm -rf /data/local/tmp/pibolt-prefix-bench /data/local/tmp/pibolt-prefix-bench.dex
