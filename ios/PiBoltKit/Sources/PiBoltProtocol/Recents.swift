import Foundation

/// A session this phone showed, for Home's Recents: which computer, which session, what it was about, and when.
/// `sessionFile` is what the background service's `switch_session` takes, so a tap can open that very session.
public struct RecentSession: Codable, Equatable, Sendable, Identifiable {
    public var hostId: String
    public var hostName: String
    public var sessionId: String
    public var sessionFile: String?
    public var title: String
    public var cwd: String
    public var updatedAt: Date
    /// The computer's background service had it open: a tap can switch to it.
    public var service: Bool

    public var id: String { "\(hostId)/\(sessionId)" }

    public init(hostId: String, hostName: String, sessionId: String, sessionFile: String?, title: String, cwd: String, updatedAt: Date, service: Bool) {
        self.hostId = hostId
        self.hostName = hostName
        self.sessionId = sessionId
        self.sessionFile = sessionFile
        self.title = title
        self.cwd = cwd
        self.updatedAt = updatedAt
        self.service = service
    }

    /// The entry for the session `state` shows, or nil before its snapshot. Titled by its name, else its first
    /// prompt, else "New session".
    public static func of(_ state: SessionState, hostId: String, hostName: String, now: Date = Date()) -> RecentSession? {
        guard state.hello, let sessionId = state.sessionId, !sessionId.isEmpty else { return nil }
        let name = state.sessionName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let firstPrompt: String? = state.items.lazy.compactMap { item -> String? in
            if case let .user(u) = item { return u.text } else { return nil }
        }.first
        let prompt = firstPrompt?.split(separator: "\n").first.map { String($0.trimmingCharacters(in: .whitespaces).prefix(120)) }
        let title = (name?.isEmpty == false ? name : nil) ?? (prompt?.isEmpty == false ? prompt : nil) ?? "New session"
        return RecentSession(hostId: hostId, hostName: hostName, sessionId: sessionId, sessionFile: state.sessionFile, title: title,
                             cwd: state.host?.cwd ?? "", updatedAt: now, service: state.host?.service == true)
    }

    /// The folder's last path component, for a row's subtitle.
    public var folder: String {
        let trimmed = cwd.hasSuffix("/") || cwd.hasSuffix("\\") ? String(cwd.dropLast()) : cwd
        return trimmed.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? trimmed
    }
}

public enum Recents {
    /// Entries kept; Home shows the first few.
    public static let cap = 20

    /// `list` with `entry` first (replacing the same session's older entry), at most `cap`.
    public static func record(_ list: [RecentSession], _ entry: RecentSession) -> [RecentSession] {
        var out = [entry]
        for e in list where e.id != entry.id { out.append(e) }
        return Array(out.prefix(cap))
    }

    /// `list` without the entries of computer `hostId` (forgotten on this phone).
    public static func removing(_ list: [RecentSession], hostId: String) -> [RecentSession] {
        list.filter { $0.hostId != hostId }
    }
}
