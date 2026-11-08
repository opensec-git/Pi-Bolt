# Pi-Bolt for iOS

A remote control for Pi-Bolt on your computer. iOS does not let an app run Pi itself (no bundled engine processes, as
the Android app has), so the iPhone pairs with Pi-Bolt on a Mac, Linux or Windows computer and drives its sessions
there: the live transcript with streaming replies, tool calls and markdown, prompts, steering, stop, and the computer's
sessions. The protocol is the one the Android app speaks ([docs/remote-control.md](../packages/coding-agent/docs/remote-control.md)):
Noise_IK over a WebSocket, so a tunnel or a Wi-Fi network only ever carries ciphertext.

Requires iOS 17 or later.

## Install

The app is not in the App Store and is not signed by a developer account. Each release (`ios-vX.Y.Z` on the
[releases page](https://github.com/opensec-git/Pi-Bolt/releases)) carries an unsigned IPA,
`pi-bolt-ios-X.Y.Z-unsigned.ipa`, and its SHA-256. Check it before installing:

```sh
shasum -a 256 -c pi-bolt-ios-X.Y.Z-unsigned.ipa.sha256
```

iOS installs only signed apps, so a sideloading tool signs the IPA with **your own Apple ID** on the way in. A free
Apple ID works; the signature then lasts 7 days, and the tool renews it (or you install again). Use one of:

- **AltStore** (Classic, [altstore.io](https://altstore.io)): install AltServer on a Mac or Windows PC, install AltStore
  on the iPhone from it, then in AltStore: My Apps, `+`, pick the IPA. AltStore renews the app over Wi-Fi while AltServer
  runs on the same network. A free Apple ID allows 3 sideloaded apps at a time.
- **SideStore** ([sidestore.io](https://sidestore.io)): AltStore's fork that renews on the iPhone itself, without a
  computer after the first setup (it needs a pairing file and its VPN app; follow its guide). Then `+` and the IPA.
- **Sideloadly** ([sideloadly.io](https://sideloadly.io), Mac or Windows): connect the iPhone by cable, drop the IPA on
  Sideloadly, sign in with your Apple ID, Start. Install it again before the 7 days run out (Sideloadly can do it
  automatically while the computer runs).
- **TrollStore** ([github.com/opa334/TrollStore](https://github.com/opa334/TrollStore)): installs the IPA permanently,
  with no Apple ID and no renewal, but only on the iOS versions it supports (see its README: up to iOS 17.0, none later).
  This app needs iOS 17, so in practice that is an iPhone on iOS 17.0. Open the IPA with TrollStore and install.

On iOS 16 and later a sideloaded app needs **Developer Mode** (Settings, Privacy & Security, Developer Mode; the iPhone
restarts), and the first launch asks you to trust your Apple ID's certificate (Settings, General, VPN & Device
Management). Sideloading tools may give the app a bundle id of their own; pairing does not depend on it.

Every pull request that touches `ios/` also builds the unsigned IPA as a workflow artifact (`ios-ipa`), for testing a
change before it is released.

## Pair

1. On the computer, in a Pi-Bolt terminal, run `/remote-control`. Or install the background service once
   (`pi-bolt service install`) and run `pi-bolt service pair`, so the iPhone reaches the computer with no terminal open
   and can list and open its sessions.
2. In the app, tap `+` and scan the QR code. Without a camera (or in the Simulator): `/remote-control copy` (or
   `pi-bolt service pair --link`) and "Paste pairing link". Opening a `pibolt://pair#…` link also works.
3. Confirm. The first connection asks for Local Network access: allow it, or the app cannot reach the computer over
   Wi-Fi.

The code pairs one phone within 10 minutes. Afterwards the app reconnects by itself: on the local network, over
Tailscale, or through the computer's Cloudflare tunnel, in that order, and it learns the tunnel's new address whenever
it changes (`remote_endpoints`). `/remote-control devices` on the computer lists and revokes phones; the app's
identity circle on Home shows this phone's key id.

## What works (milestone 1)

- Pairing by QR code (AVFoundation) or pasted link; paired computers on Home with their status.
- The session: streaming text and thinking, one-line tool rows that open to arguments and output, markdown (headings,
  lists, tables, code blocks with copy), notices for errors, aborts and compactions.
- The pill composer: send, steer while Pi works (hold Send to queue a follow-up instead), stop, clear the queue.
- Sessions on a computer running the background service: list projects and sessions, open one, start a new one.
- Models: list and switch. Compaction. Extension dialogs (confirm, select, input, editor).
- Reconnects at once after a drop and then backs off (1 s to 30 s), retries as soon as a network comes back, re-checks
  the link when the app returns to the foreground, and pairs again with a scanned code's secret when a known computer
  no longer knows the phone.

Not yet (milestone 2): notifications when a run ends or a dialog waits while the app is in the background, image
attachments and voice input, the todo panel and subagent runs, `/` command completion, the review screens (`host.review`:
git diff, files), session rename, thinking level, and a light theme.

One platform difference: `URLSessionWebSocketTask` reports the 1012 close (the background service handing its port to a
terminal) as 1005, so the app treats a handoff as a dropped connection. Both reconnect at once; only the Android app's
"terminal took over" notice is missing.

## Layout

| Path | What |
|---|---|
| `PiBoltKit/` | Swift package, no UIKit: `PiBoltProtocol` (Noise_IK_25519_AESGCM_SHA256 on CryptoKit, pairing, framing, `RemoteBackend` on `URLSessionWebSocketTask`, `RpcClient`, the session reducer) and `PiBoltMarkdown` (the block parser the transcript renders). Ported from the Android app's `android/protocol`, with its tests. |
| `PiBolt/` | The SwiftUI app: `AppModel`, `SessionController` (reconnects, frame-batched reduction), the screens in `UI/`. |
| `PiBoltTests/` | The app's unit tests, plus a live session test that runs when CI hands it a pairing link. |
| `fixtures/rc-host.ts` | A real Pi-Bolt host for tests: Pi-Bolt's `RemoteControlServer` in front of sessions on the test suite's faux model (no provider, no key). `workspace-hooks.mjs` lets Node run it from source. |
| `project.yml` | The Xcode project, for [XcodeGen](https://github.com/yonaskolb/XcodeGen). `PiBolt.xcodeproj` is generated and not committed. |

## Develop

The protocol layer needs only the command-line tools (no Xcode):

```sh
npm ci --ignore-scripts && npm run generate:catalog-modules   # once, at the repository root: the fixture host
ios/PiBoltKit/test.sh                                         # swift test, with the Swift Testing plugin path the CLT need
```

`test.sh` runs the live tests against `fixtures/rc-host.ts` when the repository's `node_modules` is there
(`PIBOLT_LIVE_HOST=0` skips them, `=1` makes them mandatory, as in CI).

The app needs Xcode (16 or later) for the iOS SDK and the Simulator:

```sh
brew install xcodegen
cd ios && xcodegen generate && open PiBolt.xcodeproj
```

Xcode needs an Apple ID to download, and is about 40 GB with a Simulator runtime: install it on the T7 drive
(download it from developer.apple.com or with `xcodes`, move `Xcode.app` to the T7, then
`sudo xcode-select -s /Volumes/<T7>/Xcode.app` and `xcodebuild -downloadPlatform iOS`).
Running on your own iPhone from Xcode works with a free Apple ID (Personal Team, 7 days), with no IPA in between.

To try the app against a host without a model key: run the fixture host (`node --import ./ios/fixtures/workspace-hooks.mjs
ios/fixtures/rc-host.ts` at the repository root, which prints a pairing link) and paste the link in the Simulator, which
shares the Mac's `127.0.0.1`.

[`.github/workflows/ios.yml`](../.github/workflows/ios.yml) runs all of this on GitHub-hosted macOS runners: the package
tests, the app's tests in the Simulator against the fixture host, and the unsigned IPA. An `ios-vX.Y.Z` tag on a
`pi-bolt` commit publishes that IPA as a release (`MARKETING_VERSION` in `project.yml` must match the tag).
