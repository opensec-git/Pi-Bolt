import Foundation
@testable import PiBolt
import PiBoltProtocol
import Testing

@MainActor
struct HostStoreTests {
    private func tempFile() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("hosts-\(UUID().uuidString).json")
    }

    @Test func keepsHostsAcrossLaunchesMostRecentFirst() {
        let file = tempFile()
        let store = HostStore(file: file)
        store.upsert(PairedHost(id: "a", name: "studio", endpoints: ["ws://192.168.1.2:48123/rc"]))
        store.upsert(PairedHost(id: "b", name: "laptop", endpoints: ["wss://x.trycloudflare.com/rc"]))
        store.update("a") { $0.service = true }
        store.upsert(store.get("a")!)
        let reopened = HostStore(file: file)
        #expect(reopened.hosts.map(\.id) == ["a", "b"])
        #expect(reopened.get("a")?.service == true)
        reopened.remove("a")
        #expect(HostStore(file: file).hosts.map(\.id) == ["b"])
    }

    @Test func connectionsNameHowThePhoneReachesTheComputer() {
        let host = PairedHost(id: "a", name: "m", endpoints: ["wss://t.trycloudflare.com/rc", "ws://100.101.1.2:48123/rc", "ws://192.168.1.2:48123/rc", "ws://10.0.0.2:48123/rc"])
        #expect(host.connections == ["LAN", "Tailscale", "Tunnel"])
        #expect(!PairedHost.isTailscale("100.10.0.1"))
    }
}

@MainActor
struct PairingLinkTests {
    private func model() -> AppModel { testModel() }

    private func link(expiresAt: Int64) -> String {
        PairingPayload(version: 1, hostName: "studio", hostKey: KeyPair.generate().publicKey, secret: Data(repeating: 7, count: 32), endpoints: ["ws://127.0.0.1:1/rc"], expiresAt: expiresAt).encode()
    }

    @Test func aValidLinkWaitsForConfirmation() {
        let app = model()
        app.receive(link: link(expiresAt: Int64(Date().timeIntervalSince1970) + 600))
        #expect(app.pendingLink?.hostName == "studio")
        #expect(app.toast == nil)
    }

    @Test func anExpiredCodeFromAnUnknownComputerIsExplained() {
        let app = model()
        app.receive(link: link(expiresAt: 0))
        #expect(app.pendingLink == nil)
        #expect(app.toast?.text.contains("expired") == true)
        app.receive(link: "https://example.com")
        #expect(app.toast?.text == "Not a Pi-Bolt pairing code")
    }

    @Test func theDeviceKeyIsStableUntilReset() {
        let identity = DeviceIdentity(service: "in.opensec.pibolt.ios.tests.\(UUID().uuidString)")
        let first = identity.keyPair()
        #expect(identity.keyPair() == first)
        identity.reset()
        #expect(identity.keyPair() != first)
        identity.reset()
    }
}

/// The whole app path against a real Pi-Bolt host: CI starts ios/fixtures/rc-host.ts on the Mac and hands its pairing
/// link to the Simulator as `PIBOLT_PAIRING_URI` (xcodebuild passes `TEST_RUNNER_PIBOLT_PAIRING_URI`). Skipped without it.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["PIBOLT_PAIRING_URI"] != nil))
struct LiveSessionTests {
    private func waitUntil(_ timeout: TimeInterval = 20, _ condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return condition()
    }

    @Test func pairsSendsAPromptAndShowsTheStreamedReply() async throws {
        let uri = try #require(ProcessInfo.processInfo.environment["PIBOLT_PAIRING_URI"])
        let app = testModel()
        app.receive(link: uri)
        let payload = try #require(app.pendingLink)
        let controller = app.pair(payload)
        #expect(await waitUntil(40) { controller.link.isConnected && controller.state.hello }, "\(controller.link)")
        // The computer is stored once the handshake proved its key.
        #expect(app.hosts.get(payload.hostKeyId)?.name == "fixture-host")
        #expect(controller.state.host?.service == true)

        controller.send("hello from the simulator")
        #expect(await waitUntil {
            !controller.state.isRunning && controller.state.items.contains { $0.assistantText?.text == "Echo: hello from the simulator. Some **bold** and `code`." }
        })

        controller.send("use the tool")
        #expect(await waitUntil { !controller.state.isRunning && controller.state.items.contains { $0.tool?.result == "HELLO" } })

        // A subagent run: its card's run, then its own conversation streamed while its screen is open
        // (`subagent_subscribe`, the fixture host's `Agent` tool runs a child session as the subagents extension does).
        #expect(controller.state.host?.streamsSubagents == true)
        controller.send("run a subagent")
        #expect(await waitUntil { !controller.state.subagents.isEmpty })
        let key = try #require(controller.state.subagents.values.first?.key)
        controller.watchSubagent(key, true)
        #expect(await waitUntil(30) {
            controller.state.subagents[key]?.transcript?.items.contains { $0.assistantText?.text == "Subagent found two files." } == true
        }, "\(String(describing: controller.state.subagents[key]))")
        #expect(await waitUntil { !controller.state.isRunning && controller.state.subagents[key]?.status == .done })
        let run = try #require(controller.state.subagents[key])
        #expect(run.type == "general-purpose")
        #expect(run.result == "Subagent found two files.")
        #expect(run.transcript?.items.contains { $0.tool?.name == "shout" && $0.tool?.status == .done } == true)
        controller.watchSubagent(key, false)

        // Review: the working tree of the session's folder (a git repository on the fixture host), read-only.
        #expect(controller.state.host?.review == true)
        let status = try RemoteGitStatus.from(await controller.request(ReviewRequest.status().type, ReviewRequest.status().fields))
        #expect(status.repo)
        #expect(status.entries.contains { $0.path == "README.md" && $0.worktree == "M" })
        let diffRequest = ReviewRequest.diff("README.md", staged: false)
        let diff = try RemoteDiff.from(await controller.request(diffRequest.type, diffRequest.fields))
        #expect(diff.diff.split(separator: "\n").contains { $0.hasPrefix("+") && !$0.hasPrefix("+++") })
        let secret = ReviewRequest.readFile(".env")
        let refused = await controller.request(secret.type, secret.fields)
        #expect(throws: ReviewError.self) { try RemoteFile.from(refused) }

        // Each session shown is in Recents.
        #expect(app.recents.sessions.first?.hostId == payload.hostKeyId)

        // Reopening the stored computer reconnects without the secret.
        app.closeSession()
        let again = app.open(try #require(app.hosts.get(payload.hostKeyId)))
        #expect(await waitUntil { again.link.isConnected && again.state.items.count >= 4 })
        app.closeSession()
    }
}

/// An app model on temporary files, posting no notifications.
@MainActor
func testModel() -> AppModel {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pibolt-tests-\(UUID().uuidString)")
    return AppModel(
        identity: DeviceIdentity(service: "in.opensec.pibolt.ios.tests.\(UUID().uuidString)"),
        hosts: HostStore(file: dir.appendingPathComponent("hosts.json")),
        recents: RecentStore(file: dir.appendingPathComponent("recents.json")),
        notifier: Notifier(center: nil),
        watchFile: dir.appendingPathComponent("watch.json")
    )
}

@MainActor
struct RecentStoreTests {
    @Test func keepsRecentsAcrossLaunchesAndForgetsAComputer() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("recents-\(UUID().uuidString).json")
        let store = RecentStore(file: file)
        let entry = RecentSession(hostId: "a", hostName: "studio", sessionId: "s1", sessionFile: "/f.jsonl", title: "Fix it", cwd: "/w/app", updatedAt: Date(timeIntervalSince1970: 1_800_000_000), service: true)
        store.record(entry)
        store.record(RecentSession(hostId: "b", hostName: "laptop", sessionId: "s2", sessionFile: nil, title: "Other", cwd: "/w", updatedAt: Date(), service: false))
        #expect(RecentStore(file: file).sessions.map(\.sessionId) == ["s2", "s1"])
        #expect(RecentStore(file: file).sessions.last == entry)
        store.removeHost("b")
        #expect(RecentStore(file: file).sessions.map(\.sessionId) == ["s1"])
    }
}

/// The transcript's inline markdown on iOS itself (the package tests run on macOS, whose parser can differ): reply
/// links other than http, https and mailto are text (PR #274 review: a code span inside a link kept it live).
struct ReplyLinkTests {
    @Test(arguments: ["[`run`](shortcuts://run-shortcut?name=x)", "[**call**](tel:5551234)", "<pibolt://pair#eyJ2IjoxfQ>", "[go][r]\n\n[r]: sms:5551234"])
    func refusedSchemesAreNotLinks(payload: String) {
        #expect(inline(payload).runs.allSatisfy { $0.link == nil })
    }

    @Test func webLinksStay() {
        #expect(inline("[`docs`](https://example.com)").runs.contains { $0.link != nil })
    }
}
