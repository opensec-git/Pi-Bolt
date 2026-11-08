import Foundation
import PiBoltProtocol
import Security

/// This install's X25519 static key pair. The private key lives in the Keychain, readable only on this device after
/// the first unlock, and never in a backup: a restored backup on another phone is a new device that pairs again.
final class DeviceIdentity {
    private let service: String
    private let account = "device-key"
    private var cached: KeyPair?

    init(service: String = "in.opensec.pibolt.ios.identity") {
        self.service = service
    }

    func keyPair() -> KeyPair {
        if let cached { return cached }
        if let stored = load(), let pair = try? KeyPair.fromPrivate(stored) {
            cached = pair
            return pair
        }
        let pair = KeyPair.generate()
        store(pair.privateKey)
        cached = pair
        return pair
    }

    /// A new key: every computer has to pair again.
    func reset() {
        cached = nil
        SecItemDelete(query() as CFDictionary)
    }

    /// The first 8 characters of the public key, as `/remote-control devices` lists the phone.
    var keyId: String { String(Base64Url.encode(keyPair().publicKey).prefix(8)) }

    private func query() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func load() -> Data? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private func store(_ privateKey: Data) {
        SecItemDelete(query() as CFDictionary)
        var q = query()
        q[kSecValueData as String] = privateKey
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
