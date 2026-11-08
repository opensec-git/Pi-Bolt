import Foundation

public let protocolVersion = 1
public let pairPrefix = "pibolt://pair#"
public let wsSubprotocol = "pibolt-rc.1"
public let noisePrologue = Data("pibolt-rc/1".utf8)

public enum Base64Url {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Accepts padded or unpadded base64url; nil for anything else.
    public static func decode(_ text: String) -> Data? {
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("=") { s.removeLast() }
        guard !s.contains("+"), !s.contains("/"), s.count % 4 != 1 else { return nil }
        s = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }
}

public struct PairingParseError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// The pairing QR payload: `pibolt://pair#` + base64url(JSON) (docs/remote-control.md, "Pairing QR code").
public struct PairingPayload: Equatable, Sendable, Identifiable {
    public var version: Int
    public var hostName: String
    public var hostKey: Data
    public var secret: Data
    public var endpoints: [String]
    /// Expiry of `secret`, Unix seconds.
    public var expiresAt: Int64

    public init(version: Int, hostName: String, hostKey: Data, secret: Data, endpoints: [String], expiresAt: Int64) {
        self.version = version
        self.hostName = hostName
        self.hostKey = hostKey
        self.secret = secret
        self.endpoints = endpoints
        self.expiresAt = expiresAt
    }

    public var hostKeyId: String { Base64Url.encode(hostKey) }
    public var id: String { hostKeyId + Base64Url.encode(secret) }

    public func isExpired(now: Int64 = Int64(Date().timeIntervalSince1970)) -> Bool { now >= expiresAt }

    public func encode() -> String {
        let json: JSON = [
            "v": .number(Double(version)),
            "n": .string(hostName),
            "k": .string(Base64Url.encode(hostKey)),
            "s": .string(Base64Url.encode(secret)),
            "e": .array(endpoints.map(JSON.string)),
            "x": .number(Double(expiresAt)),
        ]
        return pairPrefix + Base64Url.encode(json.data)
    }

    /// True when `text` looks like a pairing link (from a QR code, a deep link or pasted text).
    public static func looksLikePairing(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix(pairPrefix)
    }

    /// A real pairing link is a few hundred characters; anything longer is refused before decoding.
    public static let maxLinkChars = 4096
    /// Endpoints kept from a pairing code: LAN addresses plus a tunnel fit comfortably.
    public static let maxEndpoints = 8
    static let maxEndpointChars = 256
    static let maxHostNameChars = 80

    /// A usable endpoint: `ws://` or `wss://` with a host and no user info, at most 256 characters. Anything else in a
    /// pairing code (which any app or web page can hand us through a link) is dropped.
    public static func isValidEndpoint(_ endpoint: String) -> Bool {
        guard endpoint.count <= maxEndpointChars, let url = URLComponents(string: endpoint) else { return false }
        guard let scheme = url.scheme, scheme == "ws" || scheme == "wss" else { return false }
        guard let host = url.host, !host.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        return url.user == nil && url.password == nil
    }

    public static func parse(_ text: String) throws -> PairingPayload {
        if text.count > maxLinkChars { throw PairingParseError("Pairing code is too long") }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.lowercased().hasPrefix(pairPrefix) else { throw PairingParseError("Not a Pi-Bolt pairing code") }
        let encoded = String(trimmed.dropFirst(pairPrefix.count))
        guard let bytes = Base64Url.decode(encoded), let json = try? JSON.parse(bytes), json.isObject else {
            throw PairingParseError("Pairing code is damaged")
        }
        guard let version = json.int("v") else { throw PairingParseError("Pairing code has no version") }
        guard version == protocolVersion else {
            throw PairingParseError("Pairing code version \(version) is not supported; update the app")
        }
        guard let k = json.str("k") else { throw PairingParseError("Pairing code has no host key") }
        let key = try decodeKey(k, "host key")
        guard let s = json.str("s") else { throw PairingParseError("Pairing code has no secret") }
        let secret = try decodeKey(s, "secret")
        var endpoints: [String] = []
        for e in json.strings("e") where isValidEndpoint(e) && !endpoints.contains(e) {
            endpoints.append(e)
            if endpoints.count == maxEndpoints { break }
        }
        if endpoints.isEmpty { throw PairingParseError("Pairing code has no address") }
        // Shown in the app: no control characters, bounded length.
        let name = json.str("n").map(cleanName) ?? ""
        return PairingPayload(
            version: version,
            hostName: name.isEmpty ? "Computer" : name,
            hostKey: key,
            secret: secret,
            endpoints: endpoints,
            expiresAt: json.long("x") ?? 0
        )
    }

    static func cleanName(_ raw: String) -> String {
        let kept = raw.unicodeScalars.filter { !(($0.value <= 0x1F) || ($0.value >= 0x7F && $0.value <= 0x9F)) }
        let trimmed = String(String.UnicodeScalarView(kept)).trimmingCharacters(in: .whitespacesAndNewlines)
        return String(trimmed.prefix(maxHostNameChars))
    }

    private static func decodeKey(_ text: String, _ what: String) throws -> Data {
        guard let bytes = Base64Url.decode(text) else { throw PairingParseError("Pairing code \(what) is not base64url") }
        guard bytes.count == 32 else { throw PairingParseError("Pairing code \(what) must be 32 bytes") }
        return bytes
    }
}

/// `remote_endpoints`: the host's current addresses, sent after `remote_hello` and whenever they change.
public enum RemoteEndpoints {
    public static let type = "remote_endpoints"

    /// The endpoints to use from now on, or nil when `message` is malformed or names no usable address. Keeps the
    /// entries that pass `isValidEndpoint`, once each, at most `maxEndpoints`. The endpoint this connection uses
    /// (`connected`) is kept too (last), so a list that leaves it out cannot strand the phone.
    public static func from(_ message: JSON, connected: String?) -> [String]? {
        guard let list = message.arr("endpoints") else { return nil }
        var valid: [String] = []
        for e in list.compactMap(\.stringValue) where PairingPayload.isValidEndpoint(e) && !valid.contains(e) {
            valid.append(e)
            if valid.count == PairingPayload.maxEndpoints { break }
        }
        var endpoints = valid
        if let keep = connected, !valid.contains(keep), PairingPayload.isValidEndpoint(keep) {
            endpoints = Array(valid.prefix(PairingPayload.maxEndpoints - 1)) + [keep]
        }
        return endpoints.isEmpty ? nil : endpoints
    }
}
