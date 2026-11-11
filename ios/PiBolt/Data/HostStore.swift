import Foundation
import PiBoltProtocol

/// A computer this phone paired with. `id` is the host's public key (base64url).
struct PairedHost: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var endpoints: [String]
    var version: String = ""
    var cwd: String = ""
    var lastConnectedAt: Date?
    var pairedAt: Date = Date()
    /// The last connection reached the computer's background service (`host.service`).
    var service: Bool = false

    /// How this phone reaches it: "LAN", "Tailscale", "Tunnel", in that order, each once.
    var connections: [String] {
        var out: [String] = []
        for endpoint in endpoints {
            let label: String
            if endpoint.hasPrefix("wss://") {
                label = "Tunnel"
            } else if let host = URLComponents(string: endpoint)?.host, PairedHost.isTailscale(host) {
                label = "Tailscale"
            } else {
                label = "LAN"
            }
            if !out.contains(label) { out.append(label) }
        }
        return out.sorted { ["LAN", "Tailscale", "Tunnel"].firstIndex(of: $0)! < ["LAN", "Tailscale", "Tunnel"].firstIndex(of: $1)! }
    }

    /// 100.64.0.0/10, Tailscale's address range.
    static func isTailscale(_ host: String) -> Bool {
        let parts = host.split(separator: ".").compactMap { Int($0) }
        return parts.count == 4 && parts[0] == 100 && (64 ... 127).contains(parts[1])
    }
}

/// Paired computers in a small JSON file, most recently used first.
@MainActor
final class HostStore: ObservableObject {
    @Published private(set) var hosts: [PairedHost] = []
    private let file: URL

    init(file: URL = HostStore.defaultFile) {
        self.file = file
        if let data = try? Data(contentsOf: file), let decoded = try? JSONDecoder.iso.decode([PairedHost].self, from: data) {
            hosts = decoded
        }
    }

    nonisolated static var defaultFile: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("hosts.json")
    }

    func get(_ id: String) -> PairedHost? { hosts.first { $0.id == id } }

    /// Insert or replace, and move to the front.
    func upsert(_ host: PairedHost) {
        hosts.removeAll { $0.id == host.id }
        hosts.insert(host, at: 0)
        save()
    }

    func update(_ id: String, _ change: (inout PairedHost) -> Void) {
        guard let index = hosts.firstIndex(where: { $0.id == id }) else { return }
        change(&hosts[index])
        save()
    }

    func remove(_ id: String) {
        hosts.removeAll { $0.id == id }
        save()
    }

    func removeAll() {
        hosts = []
        save()
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONEncoder.iso.encode(hosts)
            try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            // The list is rebuilt by the next pairing; nothing to tell the user here.
        }
    }
}

extension JSONEncoder {
    static var iso: JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }
}

extension JSONDecoder {
    static var iso: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }
}
