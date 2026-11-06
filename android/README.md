# Pi-Bolt for Android

A remote control for Pi-Bolt: pair the phone with `/remote-control` on a computer, then watch and drive the session
(prompts, steering, follow-ups, abort, model and thinking level, extension dialogs) from the phone. Protocol:
[docs/remote-control.md](../packages/coding-agent/docs/remote-control.md).

- `protocol/`: plain Kotlin/JVM. Noise IK (X25519 via BouncyCastle, AES-256-GCM, SHA-256), pairing codes, transport
  framing, `SessionBackend` + `RemoteBackend` (OkHttp WebSocket), the RPC client, and the session reducer.
- `app/`: the Compose app (`in.opensec.pibolt`, minSdk 26, target 36).

## Build

Needs JDK 21 and the Android SDK (platform 36). Point `ANDROID_HOME` at the SDK, or write `sdk.dir=...` to
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

For CI (`.github/workflows/android.yml`, on `android-vX.Y.Z` tags) add four repository secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 < pibolt-release.keystore` (one line: `base64 -w0` on Linux) |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `pibolt` |
| `ANDROID_KEY_PASSWORD` | the key password |

The workflow builds the signed APK, runs `apksigner verify --print-certs`, and publishes `pi-bolt-android-X.Y.Z.apk`
with its SHA-256 as a GitHub release of its own (title "Pi-Bolt Android X.Y.Z", signing certificate digest in the notes).
That release is never marked latest, so Pi-Bolt's installers and release checks are unaffected.
