import Foundation

/// A local notification about a session, while the app is not in front: its run ended, or Pi waits for an answer.
/// `id` replaces the previous alert of the same kind for the same computer, as the Android app's notification ids do.
public struct SessionAlert: Equatable, Sendable {
    public enum Kind: String, Sendable {
        case finished, stopped, needsAnswer
    }

    public var kind: Kind
    public var hostId: String
    public var title: String
    public var body: String
    /// The session it is about, so a tap opens that one.
    public var sessionId: String?

    public var id: String { "\(hostId).\(kind == .needsAnswer ? "answer" : "settled")" }
}

/// Which alerts an event raises, as the Android app's `SessionController.sideEffects` decides them; the texts of its
/// `Notifications`. Pure, so the app's controller and its background refresh share it.
public enum SessionAlerts {
    /// The alerts `event` raises when it reaches a session showing `before`.
    public static func alerts(before: SessionState, event: JSON, hostId: String, hostName: String) -> [SessionAlert] {
        switch event.type {
        case "agent_settled":
            guard before.isRunning else { return [] }
            let last = before.allItems.last(where: { $0.assistantText != nil })?.assistantText?.text ?? ""
            return [settled(hostId: hostId, hostName: hostName, lastText: last, aborted: event.bool("aborted") == true, sessionId: before.sessionId)]
        case "extension_ui_request":
            guard let dialog = SessionReducer.dialogFrom(event) else { return [] }
            return [needsAnswer(hostId: hostId, hostName: hostName, dialog: dialog, sessionId: before.sessionId)]
        default:
            return []
        }
    }

    /// The alerts a reconnect's `remote_hello` owes for what happened while the link was down (`missedWhileAway`):
    /// the run that ended, with the snapshot's own last reply, and each dialog that opened.
    public static func missed(before: SessionState, hello: JSON, hostId: String, hostName: String) -> [SessionAlert] {
        let events = SessionReducer.missedWhileAway(before, hello)
        guard !events.isEmpty else { return [] }
        var after = SessionReducer.fromHello(hello)
        after.isRunning = true
        return events.flatMap { alerts(before: after, event: $0, hostId: hostId, hostName: hostName) }
    }

    public static func settled(hostId: String, hostName: String, lastText: String, aborted: Bool, sessionId: String?) -> SessionAlert {
        let first = lastText.split(separator: "\n").lazy.map { $0.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty }
        return SessionAlert(
            kind: aborted ? .stopped : .finished,
            hostId: hostId,
            title: aborted ? "Pi stopped on \(hostName)" : "Pi finished on \(hostName)",
            body: first.map { String($0.prefix(200)) } ?? "Tap to see the result",
            sessionId: sessionId
        )
    }

    public static func needsAnswer(hostId: String, hostName: String, dialog: DialogRequest, sessionId: String?) -> SessionAlert {
        let parts = [dialog.title, dialog.message ?? ""].map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        let body = parts.isEmpty ? "Tap to answer" : String(parts.joined(separator: "\n").prefix(1000))
        return SessionAlert(kind: .needsAnswer, hostId: hostId, title: "Pi needs an answer on \(hostName)", body: body, sessionId: sessionId)
    }
}

/// What a session showed when the app went to the background, kept on disk so a background refresh (a new process,
/// possibly) can reconnect, read the computer's `remote_hello` and tell what changed: the run ended, or a dialog opened.
public struct AlertWatch: Codable, Equatable, Sendable {
    public var hostId: String
    public var hostName: String
    public var sessionId: String
    public var running: Bool
    public var dialogIds: [String]
    public var savedAt: Date

    public init(hostId: String, hostName: String, sessionId: String, running: Bool, dialogIds: [String], savedAt: Date = Date()) {
        self.hostId = hostId
        self.hostName = hostName
        self.sessionId = sessionId
        self.running = running
        self.dialogIds = dialogIds
        self.savedAt = savedAt
    }

    /// A watch for `state`, or nil when nothing could change that is worth an alert later (no session; idle with no
    /// dialog open and nothing running).
    public static func of(_ state: SessionState, hostId: String, hostName: String) -> AlertWatch? {
        guard state.hello, let sessionId = state.sessionId, state.isRunning || !state.dialogs.isEmpty else { return nil }
        return AlertWatch(hostId: hostId, hostName: hostName, sessionId: sessionId, running: state.isRunning, dialogIds: state.dialogs.map(\.id))
    }

    /// The state `missedWhileAway` compares a new snapshot with.
    public var before: SessionState {
        var s = SessionState()
        s.hello = true
        s.sessionId = sessionId
        s.isRunning = running
        s.dialogs = dialogIds.map { DialogRequest(id: $0, kind: .confirm, title: "") }
        return s
    }

    /// After `hello`: still worth watching (running, or a dialog open), with what it shows now.
    public func after(_ hello: JSON) -> AlertWatch? {
        let state = SessionReducer.fromHello(hello)
        guard state.sessionId == sessionId else { return nil }
        return AlertWatch.of(state, hostId: hostId, hostName: hostName)
    }
}
