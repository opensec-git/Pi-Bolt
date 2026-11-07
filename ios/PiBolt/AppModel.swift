import Foundation
import Observation
import PiBoltProtocol
import UIKit

/// The app's state across screens: this install's identity, the paired computers, and the one live session.
@MainActor
@Observable
final class AppModel {
    @ObservationIgnored let identity: DeviceIdentity
    @ObservationIgnored let hosts: HostStore
    /// The open session with a computer, if any.
    private(set) var live: SessionController?
    /// A pairing link waiting for the user's confirmation (from the camera, the clipboard or a `pibolt://` link).
    var pendingLink: PairingPayload?
    var toast: Toast?

    let deviceName: String
    let appVersion: String

    init(identity: DeviceIdentity = DeviceIdentity(), hosts: HostStore? = nil) {
        self.identity = identity
        self.hosts = hosts ?? HostStore()
        deviceName = UIDevice.current.name
        appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
    }

    /// A scanned, pasted or opened link: parsed and held for confirmation, or explained.
    func receive(link text: String) {
        do {
            let payload = try PairingPayload.parse(text)
            if hosts.get(payload.hostKeyId) == nil, payload.isExpired() {
                toast = Toast(text: "This code has expired. Run /remote-control or pi-bolt service pair for a new one.", kind: .error)
                return
            }
            pendingLink = payload
        } catch let error as PairingParseError {
            toast = Toast(text: error.description, kind: .error)
        } catch {
            toast = Toast(text: "Not a Pi-Bolt pairing code", kind: .error)
        }
    }

    /// Use a pairing code: pair with a new computer, or reconnect to a known one with the code's (possibly new)
    /// addresses. A known computer that no longer knows this phone is paired again with the code's secret.
    @discardableResult
    func pair(_ payload: PairingPayload) -> SessionController {
        let controller: SessionController
        if let known = hosts.get(payload.hostKeyId) {
            // Nothing in the stored host changes until the handshake proves the host key: the controller stores the
            // endpoints and name that worked once connected. The link's addresses are tried first.
            var endpoints = payload.endpoints
            for e in known.endpoints where !endpoints.contains(e) { endpoints.append(e) }
            controller = SessionController(app: self, hostId: known.id, hostName: known.name, endpoints: endpoints, pairSecret: nil, fallbackSecret: payload.isExpired() ? nil : payload.secret)
        } else {
            controller = SessionController(app: self, hostId: payload.hostKeyId, hostName: payload.hostName, endpoints: payload.endpoints, pairSecret: payload.secret)
        }
        replaceLive(controller)
        return controller
    }

    /// Open a session with a paired computer (the live one when it is that computer).
    @discardableResult
    func open(_ host: PairedHost) -> SessionController {
        if let live, live.hostId == host.id, !live.stopped { return live }
        let controller = SessionController(app: self, hostId: host.id, hostName: host.name, endpoints: host.endpoints, pairSecret: nil)
        replaceLive(controller)
        return controller
    }

    private func replaceLive(_ controller: SessionController) {
        live?.stop()
        live = controller
        controller.start()
    }

    func closeSession() {
        live?.stop()
        live = nil
    }

    /// Forget a computer on this phone. The computer still lists the phone until revoked in /remote-control devices.
    func forget(_ host: PairedHost) {
        if live?.hostId == host.id { closeSession() }
        hosts.remove(host.id)
    }

    /// A new device key: no paired computer knows it, so every computer is forgotten and has to be paired again.
    func resetDeviceKey() {
        closeSession()
        identity.reset()
        hosts.removeAll()
    }

    func appBecameActive() {
        live?.resume()
    }
}
