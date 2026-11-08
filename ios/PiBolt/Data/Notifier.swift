import Foundation
import PiBoltProtocol
import UserNotifications

/// Local notifications (no push server: they come from this app while it still runs in the background, or from a
/// background refresh). One per computer and kind: a newer "finished" replaces the older one, as on Android. A tap
/// opens that computer's session (`onOpen`).
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    private let center: UNUserNotificationCenter?
    private var asked = false
    /// False: never ask (CI screenshots, where the prompt would cover the screen).
    var asksPermission = true
    /// A notification was tapped: the computer and the session it is about.
    var onOpen: ((_ hostId: String, _ sessionId: String?) -> Void)?

    /// `center` nil: notifications are not shown (unit tests, where the test host has no app bundle to post from).
    init(center: UNUserNotificationCenter? = .current()) {
        self.center = center
        super.init()
        center?.delegate = self
    }

    /// Asks once per launch, the first time it matters (a prompt is sent): iOS shows its prompt only the first time.
    func requestPermissionOnce() {
        guard asksPermission, !asked, let center else { return }
        asked = true
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func post(_ alerts: [SessionAlert]) {
        guard let center else { return }
        for alert in alerts {
            let content = UNMutableNotificationContent()
            content.title = alert.title
            content.body = alert.body
            content.sound = .default
            content.threadIdentifier = alert.hostId
            // Time Sensitive needs an entitlement that unsigned and sideloaded builds do not have.
            content.interruptionLevel = .active
            var info: [String: String] = ["hostId": alert.hostId]
            if let sessionId = alert.sessionId { info["sessionId"] = sessionId }
            content.userInfo = info
            center.add(UNNotificationRequest(identifier: alert.id, content: content, trigger: nil))
        }
    }

    /// The dialog was answered (here or on the computer): its "needs an answer" is stale.
    func clearAnswer(hostId: String) {
        let id = "\(hostId).answer"
        center?.removeDeliveredNotifications(withIdentifiers: [id])
        center?.removePendingNotificationRequests(withIdentifiers: [id])
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        // Posted just as the app came back to the screen: the session shows it already.
        [.list]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let hostId = info["hostId"] as? String else { return }
        let sessionId = info["sessionId"] as? String
        await MainActor.run { self.onOpen?(hostId, sessionId) }
    }
}
