import Foundation
import PiBoltProtocol

/// One short look at a watched session from a background refresh when no session is open in this process (iOS ended
/// the app meanwhile): connect, read the computer's `remote_hello`, disconnect. What changed since the watch was saved
/// becomes alerts; `next` is the watch to keep (nil once the run ended with nothing to answer).
enum BackgroundCheck {
    struct Result {
        var alerts: [SessionAlert]
        var next: AlertWatch?
    }

    static func run(watch: AlertWatch, config: RemoteConfig, timeout: TimeInterval) async -> Result {
        let backend = RemoteBackend(config: config)
        let rpc = RpcClient(backend: backend)
        defer {
            rpc.stop()
            backend.shutdown()
        }
        let hello = await withTaskGroup(of: JSON?.self) { group -> JSON? in
            group.addTask {
                guard await backend.connect() else { return nil }
                for await event in rpc.events where event.type == "remote_hello" { return event }
                return nil
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        // Unreachable (another network, the computer asleep): keep watching, and say nothing yet.
        guard let hello else { return Result(alerts: [], next: watch) }
        return Result(
            alerts: SessionAlerts.missed(before: watch.before, hello: hello, hostId: watch.hostId, hostName: watch.hostName),
            next: watch.after(hello)
        )
    }
}
