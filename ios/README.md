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

1. On the computer, turn remote control on once with `pi-bolt remote-control enable` (it is off by default). Then, in a
   Pi-Bolt terminal, run `/remote-control`. Or install the background service once
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

## What works

- Pairing by QR code (AVFoundation) or pasted link; paired computers on Home with their status, and **Recents**: the
  sessions this phone showed last, on any computer. A tap opens that computer and, with its background service, that
  very session.
- The session: streaming text and thinking, one-line tool rows that open to arguments and output, markdown (headings,
  lists, tables, code blocks with copy), notices for errors, aborts and compactions.
- The pill composer: send, steer while Pi works (hold Send to queue a follow-up instead), stop, clear the queue.
  - **Images**: `+` attaches up to 4 from the photo library, the camera or the clipboard. Each is scaled to 2000 px on
    its long edge and encoded again as JPEG (or a small transparent PNG), so no location or camera metadata leaves the
    phone. A model that does not read images is said so before anything is sent.
  - **Voice**: the microphone dictates into the composer (the Speech framework, on the iPhone when it supports that,
    otherwise Apple's servers, as the keyboard's dictation does). Nothing is sent until you send.
  - **`/` commands**: typing `/` lists the computer's commands (`get_commands`: extensions, prompt templates, skills)
    and the app's own `/compact [instructions]`, `/name <name>`, `/model`, `/thinking [level]` and `/copy`, with each
    command's usage while its arguments are typed.
- **Todo panel**: the todo extension's list over the composer, folded to the task in progress.
- **Subagents**: an `Agent` call shows as a card with its task, what it does now and its metrics; background runs and
  their notifications too. A run's screen shows its own conversation live from Pi-Bolt 1.1.5 on (`subagent_subscribe`,
  `features: ["subagent_stream"]`); with an older Pi-Bolt it says so and shows the task, progress and answer.
- **Review** (`host.review`): the computer's working tree, read-only. Changes (git status, staged and unstaged, each
  file's diff), History (the log, each commit's patch) and Files (folders and text files). The computer refuses files
  that look like secrets ([docs/remote-control.md](../packages/coding-agent/docs/remote-control.md#review)).
- Sessions on a computer running the background service: list projects and sessions, open one, start a new one.
- Models: list and switch. Thinking level. Rename the session. Compaction. Extension dialogs (confirm, select, input,
  editor).
- Light and dark themes, following the system.
- Reconnects at once after a drop and then backs off (1 s to 30 s), retries as soon as a network comes back, re-checks
  the link when the app returns to the foreground, and pairs again with a scanned code's secret when a known computer
  no longer knows the phone.

## Notifications and the background

The app posts a local notification when a run finishes or stops, or when Pi waits for an answer, while the app is not
on the screen. A tap opens that session. There is no push server, so this works only while iOS lets the app run:

- **Right after you leave the app**, iOS gives it about 30 seconds. The connection stays up meanwhile, so a short run
  that ends or asks something then is announced at once.
- **After that**, iOS suspends the app and the connection with it. With Background App Refresh on (Settings, General,
  Background App Refresh), iOS wakes the app now and then to reconnect briefly and post what changed. iOS decides when:
  often after 15 minutes or more, less often when the app is rarely used, and never in Low Power Mode.
- **When you come back**, the app reconnects at once and shows everything that happened.

So a long run that ends while the phone sits in a pocket may be announced late, or only when you open the app. The
computer's terminal is never affected: the run goes on there either way.

The milestone 1 not-yet list is done. The mobile UI kit ([docs/mobile-ui.md](../packages/coding-agent/docs/mobile-ui.md),
Pi-Bolt 1.1.6) comes in 0.2.0. Until then the app does not declare `views`, so the computer sends it no views; the
sheets and snapshot fields that reach every phone are ignored, and sheets fall back to dialogs when no phone renders
views.

One platform difference: `URLSessionWebSocketTask` reports the 1012 close (the background service handing its port to a
terminal) as 1005, so the app treats a handoff as a dropped connection. Both reconnect at once; only the Android app's
"terminal took over" notice is missing.

## Layout

| Path | What |
|---|---|
| `PiBoltKit/` | Swift package, no UIKit: `PiBoltProtocol` (Noise_IK_25519_AESGCM_SHA256 on CryptoKit, pairing, framing, `RemoteBackend` on `URLSessionWebSocketTask`, `RpcClient`, the session reducer with todos and subagent runs, the review commands' parsers and a diff parser, the `/` command list, image encoding, the alert rules and Recents) and `PiBoltMarkdown` (the block parser the transcript renders). Ported from the Android app's `android/protocol` and its app-side rules, with their tests. |
| `PiBolt/` | The SwiftUI app: `AppModel` (also the background refresh), `SessionController` (reconnects, frame-batched reduction, subagent streams), `Notifier`, `Dictation`, the screens in `UI/` (review in `UI/Review/`). |
| `PiBoltTests/` | The app's unit tests, plus a live session test that runs when CI hands it a pairing link. |
| `fixtures/rc-host.ts` | A real Pi-Bolt host for tests: Pi-Bolt's `RemoteControlServer` in front of sessions on the test suite's faux model (no provider, no key), in a small git repository (for review), with an `Agent` tool that runs a child session as the subagents extension does (for subagent streams). `workspace-hooks.mjs` lets Node run it from source. |
| `project.yml` | The Xcode project, for [XcodeGen](https://github.com/yonaskolb/XcodeGen). `PiBolt.xcodeproj` is generated and not committed. |

## Develop

The protocol layer needs only the command-line tools (no Xcode):

```sh
npm ci --ignore-scripts && npm run generate:catalog-modules   # once, at the repository root: the fixture host
ios/PiBoltKit/test.sh                                         # swift test, with the Swift Testing plugin path the CLT need
```

`test.sh` runs the live tests against `fixtures/rc-host.ts` when the repository's `node_modules` is there
(`PIBOLT_LIVE_HOST=0` skips them, `=1` makes them mandatory, as in CI).

`ios/typecheck.sh` typechecks the app and its tests with the command-line tools too, against the macOS SDK's Mac
Catalyst variant (UIKit and the iOS SwiftUI). It catches most compile errors before CI; the iOS build there stays the
reference.

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
