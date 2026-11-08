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
    private func model() -> AppModel {
        AppModel(identity: DeviceIdentity(service: "in.opensec.pibolt.ios.tests.\(UUID().uuidString)"), hosts: HostStore(file: FileManager.default.temporaryDirectory.appendingPathComponent("hosts-\(UUID().uuidString).json")))
    }

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
        let app = AppModel(identity: DeviceIdentity(service: "in.opensec.pibolt.ios.tests.\(UUID().uuidString)"), hosts: HostStore(file: FileManager.default.temporaryDirectory.appendingPathComponent("hosts-\(UUID().uuidString).json")))
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

        // Reopening the stored computer reconnects without the secret.
        app.closeSession()
        let again = app.open(try #require(app.hosts.get(payload.hostKeyId)))
        #expect(await waitUntil { again.link.isConnected && again.state.items.count >= 4 })
        app.closeSession()
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
