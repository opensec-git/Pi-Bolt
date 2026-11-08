import Foundation
import PiBoltProtocol

/// Home's Recents: sessions this phone showed, newest first, in a small JSON file next to the paired computers.
/// Titles and folder names stay on this phone; nothing here is a secret, but the file is protected like hosts.json.
@MainActor
final class RecentStore: ObservableObject {
    @Published private(set) var sessions: [RecentSession] = []
    private let file: URL

    init(file: URL = RecentStore.defaultFile) {
        self.file = file
        if let data = try? Data(contentsOf: file), let decoded = try? JSONDecoder.iso.decode([RecentSession].self, from: data) {
            sessions = decoded
        }
    }

    nonisolated static var defaultFile: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("recents.json")
    }

    func record(_ entry: RecentSession) {
        sessions = Recents.record(sessions, entry)
        save()
    }

    func remove(_ entry: RecentSession) {
        sessions.removeAll { $0.id == entry.id }
        save()
    }

    func removeHost(_ hostId: String) {
        sessions = Recents.removing(sessions, hostId: hostId)
        save()
    }

    func removeAll() {
        sessions = []
        save()
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder.iso.encode(sessions).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            // Recents fill again as sessions are opened; nothing to tell the user.
        }
    }
}
