# Pi-Bolt for Android

A remote control for Pi-Bolt: pair the phone with `/remote-control` on a computer, then watch and drive the session
(prompts, steering, follow-ups, abort, model and thinking level, extension dialogs) from the phone. Protocol:
[docs/remote-control.md](../packages/coding-agent/docs/remote-control.md).

- `protocol/`: plain Kotlin/JVM. Noise IK (X25519 via BouncyCastle, AES-256-GCM, SHA-256), pairing codes, transport
  framing, `SessionBackend` + `RemoteBackend` (OkHttp WebSocket), the RPC client, and the session reducer.
- `app/`: the Compose app (`in.opensec.pibolt`, minSdk 26, target 36).

## Build

Needs JDK 21 and the Android SDK (platform 36), with the NDK and CMake versions pinned in `app/build.gradle.kts`
(`ndkVersion`, `externalNativeBuild.cmake.version`; they build the terminal's `libtermux.so`):
`sdkmanager "ndk;28.2.13676358" "cmake;3.22.1"`. Point `ANDROID_HOME` at the SDK, or write `sdk.dir=...` to
`android/local.properties`.

```sh
cd android
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as `in.opensec.pibolt.debug`, next to a release build.

## Test against a computer from the command line

`protocol` has a small client that pairs (or reconnects), prints every message as one JSON line, and sends a prompt:

```sh
./gradlew -q :protocol:run --args="'pibolt://pair#...' 'list the files here'"
```

Get the link with `/remote-control copy`. Options: `--steer` or `--follow-up` (when the agent is busy), `--quiet` (no
`message_update` lines), `--state DIR` (device key and paired hosts, default `~/.pibolt-rc-cli`). Without a prompt it
prints events until the connection ends.

## Updates

The app checks https://pi-bolt.opensec.in/android.json (or GitHub's newest `android-v*` release when the site is down)
once a day and from Settings > About, downloads the APK from the GitHub release, checks its SHA-256, signing
certificate (android.json's `certSha256` and the installed app's own), package name and versionCode, and installs it
with Android's package installer through a FileProvider (`app/src/main/kotlin/opensec/pibolt/app/update/`). Release builds accept only that URL and APKs
under `https://github.com/opensec-git/Pi-Bolt/releases/download/`.

To test against a local server, build a debug APK with both overrides (debug builds only):

```sh
PIBOLT_APP_VERSION=0.2.1 PIBOLT_UPDATE_MANIFEST_URL=http://10.0.2.2:8765/android.json \
PIBOLT_UPDATE_APK_PREFIX=http://10.0.2.2:8765/apk/ ./gradlew :app:assembleDebug
```

Serve a newer debug APK (`PIBOLT_APP_VERSION=0.2.9`) under `apk/` with an android.json whose `certSha256` is the debug
key's (`apksigner verify --print-certs`). The server must answer Range requests for the resume path.

## Startup and frame performance

The release APK ships a baseline profile and a startup profile (`app/src/release/generated/baselineProfiles/`):
ART compiles the code of the first minutes at install, and the startup classes sit first in the dex. Regenerate them
after UI changes, on an API 33+ emulator or device (one at a time; set `ANDROID_SERIAL` if several are attached):

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Measure cold start and scroll frames, without and with the profile:

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Results land in `baselineprofile/build/outputs/connected_android_test_additional_output/`. Emulator numbers are for
comparing builds; take absolute numbers on a phone.

### Frame timing

`e2e/frames.sh` measures the installed build on a phone, scenario by scenario (Home, a project's tabs, a
160-message transcript, a streamed reply, the keyboard and palette, a sheet, subagent cards, the Sources pill, the
question card), and prints each one's janky frames and p50/p90/p99 frame times. Its header lists what it needs. A
debug build is several times slower than the release build: compare builds of the same kind, and take numbers from
`benchmarkRelease` (R8, not debuggable, profileable, signed with the debug key so it installs over a debug build and
keeps its data). It has no `run-as`: set the phone up once with the debug build, then install it over that.

```bash
SERIAL=<serial> e2e/frames.sh                 # every scenario
SERIAL=<serial> e2e/frames.sh stream composer # some
./gradlew :app:assembleBenchmarkRelease
adb install -r app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk
SERIAL=<serial> FAKE_PORT=<the fake provider's port> e2e/frames.sh
```

Where the main thread's time goes: `nonMinifiedRelease` (as fast, with readable names) and simpleperf, while a
scenario runs.

```bash
adb shell simpleperf record --app in.opensec.pibolt -g --duration 10 -o /data/local/tmp/pb.data
```

Why a frame is slow: a Perfetto trace of the app's own sections (recompose, measure, draw) while a scenario runs.

```bash
adb shell perfetto -o /data/misc/perfetto-traces/pibolt.pftrace -t 10s -b 64mb sched freq gfx view input -a in.opensec.pibolt
```

Which composables skip: the Compose compiler reports (`app/build/compose-reports`, `app/build/compose-metrics`).
Classes from `:protocol` are declared stable in `app/compose-stability.conf`.

```bash
./gradlew :app:compileReleaseKotlin -Ppibolt.composeReports=true --rerun
```

## GitHub

The app signs in to GitHub with the OAuth device flow. That needs the client id of a GitHub OAuth App, which the
owner creates once: GitHub Settings > Developer settings > OAuth Apps > New OAuth App (any homepage and callback URL,
e.g. `https://pi-bolt.opensec.in`), then tick **Enable Device Flow** and copy the Client ID (it is public, not a
secret). Pass it at build time as the Gradle property `pibolt.githubClientId` or the environment variable
`PIBOLT_GITHUB_CLIENT_ID`; CI reads the repository variable `PIBOLT_GITHUB_CLIENT_ID` (Settings > Secrets and variables
> Actions > Variables). Without it the build is fine and Settings > GitHub offers only a personal access token.

The token is sealed with AES-GCM under a Keystore key and kept in the app's preferences. Git gets it through a
credential helper for `https://github.com` set by `GIT_CONFIG_*` environment variables (no file holds it), in the
app's own git and in the engine processes, so Pi's bash `git push` works too. A new token restarts only the idle
engine; open sessions pick it up when they restart.

## Sign a release

Without signing variables, `assembleRelease` makes `app-release-unsigned.apk`. To sign, make a keystore once and keep it
(and its passwords) out of the repository; losing it means users must uninstall to update:

```sh
keytool -genkeypair -v -keystore pibolt-release.keystore -alias pibolt -keyalg RSA -keysize 4096 -validity 10000
```

Local signed build:

```sh
ANDROID_KEYSTORE_PATH=$PWD/pibolt-release.keystore ANDROID_KEYSTORE_PASSWORD=... \
ANDROID_KEY_ALIAS=pibolt ANDROID_KEY_PASSWORD=... ./gradlew :app:assembleRelease
```

For CI (`.github/workflows/android.yml`, on `android-vX.Y.Z` tags) add four secrets to the `release` environment (the
same environment the npm release uses; it can require approval):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 < pibolt-release.keystore` (one line: `base64 -w0` on Linux) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `pibolt` |
| `ANDROID_KEY_PASSWORD` | the key password |

The workflow releases only tags on pi-bolt commits. Gradle builds the unsigned APK without the secrets; a separate
step signs it with `apksigner`, then `apksigner verify --print-certs` checks it, and the workflow publishes `pi-bolt-android-X.Y.Z.apk`
with its SHA-256 as a GitHub release of its own (title "Pi-Bolt Android X.Y.Z", signing certificate digest in the notes).
That release is never marked latest, so Pi-Bolt's installers and release checks are unaffected.
It then points https://pi-bolt.opensec.in/android at the new APK (`scripts/publish-android-download.sh`, which writes
`android.json` on gh-pages): that page and the home page's Android tab read it, and on an Android phone the page starts
the download by itself. To point the site at another release by hand: `scripts/publish-android-download.sh X.Y.Z`.
