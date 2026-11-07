import Foundation
@testable import PiBoltProtocol
import Testing

struct PairingTests {
    let key = Data((0 ..< 32).map { UInt8($0) })
    let secret = Data((0 ..< 32).map { UInt8(255 - $0) })

    @Test func roundTrip() throws {
        let payload = PairingPayload(version: 1, hostName: "macbook", hostKey: key, secret: secret, endpoints: ["ws://192.168.1.20:48123/rc", "wss://quiet-fox.trycloudflare.com/rc"], expiresAt: 1_793_000_000)
        let text = payload.encode()
        #expect(text.hasPrefix("pibolt://pair#"))
        #expect(!text.contains("="))
        #expect(try PairingPayload.parse(text) == payload)
        #expect(try PairingPayload.parse("  \(text)\n") == payload)
    }

    @Test func parsesHostJson() throws {
        // As the host writes it: field order and extra fields do not matter.
        let json = #"{"x":1793000000,"e":["ws://10.0.0.2:1/rc","http://bad","wss://a.trycloudflare.com/rc"],"k":"\#(Base64Url.encode(key))","s":"\#(Base64Url.encode(secret))","n":"studio","v":1,"extra":true}"#
        let parsed = try PairingPayload.parse("pibolt://pair#" + Base64Url.encode(Data(json.utf8)))
        #expect(parsed.hostName == "studio")
        #expect(parsed.endpoints == ["ws://10.0.0.2:1/rc", "wss://a.trycloudflare.com/rc"])
        #expect(parsed.expiresAt == 1_793_000_000)
        #expect(parsed.isExpired(now: 1_793_000_000))
        #expect(!parsed.isExpired(now: 1_792_999_999))
    }

    @Test func rejectsBadCodes() {
        #expect(throws: PairingParseError.self) { try PairingPayload.parse("https://example.com") }
        #expect(throws: PairingParseError.self) { try PairingPayload.parse("pibolt://pair#!!!") }
        func code(_ json: String) -> String { "pibolt://pair#" + Base64Url.encode(Data(json.utf8)) }
        let k = Base64Url.encode(key)
        let s = Base64Url.encode(secret)
        #expect(throws: PairingParseError.self) { try PairingPayload.parse(code(#"{"v":2,"k":"\#(k)","s":"\#(s)","e":["ws://a/rc"],"x":1}"#)) }
        #expect(throws: PairingParseError.self) { try PairingPayload.parse(code(#"{"v":1,"k":"AAAA","s":"\#(s)","e":["ws://a/rc"],"x":1}"#)) }
        #expect(throws: PairingParseError.self) { try PairingPayload.parse(code(#"{"v":1,"k":"\#(k)","s":"\#(s)","e":[],"x":1}"#)) }
    }

    @Test func pairingCodesAreBoundedAndValidated() throws {
        let good = PairingPayload(version: 1, hostName: "host\u{1b}[31m", hostKey: Data(repeating: 1, count: 32), secret: Data(repeating: 2, count: 32), endpoints: [], expiresAt: 0)
        let endpoints = ["ws://192.168.1.2:47800/rc", "http://evil/rc", "ws://user:pw@h/rc", "wss://a.trycloudflare.com/rc", "ws:///rc", "ws://" + String(repeating: "x", count: 300)]
            + (1 ... 20).map { "ws://10.0.0.\($0):1/rc" }
        var withEndpoints = good
        withEndpoints.endpoints = endpoints
        let parsed = try PairingPayload.parse(withEndpoints.encode())
        #expect(parsed.endpoints.count == PairingPayload.maxEndpoints)
        #expect(Array(parsed.endpoints.prefix(2)) == ["ws://192.168.1.2:47800/rc", "wss://a.trycloudflare.com/rc"])
        #expect(parsed.hostName == "host[31m")
        #expect(throws: PairingParseError.self) { try PairingPayload.parse(pairPrefix + String(repeating: "A", count: PairingPayload.maxLinkChars)) }
        var bad = good
        bad.endpoints = ["http://x/rc"]
        #expect(throws: PairingParseError.self) { try PairingPayload.parse(bad.encode()) }
    }

    @Test func remoteEndpointsKeepTheConnectedOne() {
        let message: JSON = ["type": "remote_endpoints", "endpoints": ["ws://10.0.0.2:48123/rc", "http://bad", "wss://new.trycloudflare.com/rc", "ws://10.0.0.2:48123/rc"]]
        #expect(RemoteEndpoints.from(message, connected: "ws://10.0.0.2:48123/rc") == ["ws://10.0.0.2:48123/rc", "wss://new.trycloudflare.com/rc"])
        #expect(RemoteEndpoints.from(message, connected: "wss://old.trycloudflare.com/rc") == ["ws://10.0.0.2:48123/rc", "wss://new.trycloudflare.com/rc", "wss://old.trycloudflare.com/rc"])
        #expect(RemoteEndpoints.from(["endpoints": []], connected: nil) == nil)
        #expect(RemoteEndpoints.from(["type": "remote_endpoints"], connected: "ws://a/rc") == nil)
    }

    @Test func pairingGetsTimeBeforeItsSingleUseSecretIsLost() {
        var config = RemoteConfig(hostKey: Data(count: 32), endpoints: [], identity: .generate(), deviceName: "t", appVersion: "0")
        #expect(config.timeout(for: "ws://10.0.0.2:48123/rc") == 3)
        #expect(config.timeout(for: "wss://a.trycloudflare.com/rc") == 15)
        config.pairSecret = Data(count: 32)
        #expect(config.timeout(for: "ws://10.0.0.2:48123/rc") == 20)
    }
}
