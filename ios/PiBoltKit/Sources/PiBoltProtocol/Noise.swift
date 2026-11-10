import CryptoKit
import Foundation

/// Noise_IK_25519_AESGCM_SHA256, as in the Noise Protocol Framework (revision 34), on CryptoKit: X25519
/// (`Curve25519.KeyAgreement`), AES-256-GCM, SHA-256 and HMAC-SHA256. The same suite as Pi-Bolt
/// (packages/coding-agent/src/remote-control/noise.ts) and the Android app (android/protocol, `Noise.kt`).
public enum Noise {
    public static let protocolName = "Noise_IK_25519_AESGCM_SHA256"
    public static let dhLen = 32
    public static let hashLen = 32
    public static let tagLen = 16
    public static let maxMessageLen = 65535
}

public struct NoiseError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// An X25519 static or ephemeral key pair, raw 32-byte keys.
public struct KeyPair: Sendable, Equatable {
    public let privateKey: Data
    public let publicKey: Data

    public static func generate() -> KeyPair {
        let key = Curve25519.KeyAgreement.PrivateKey()
        return KeyPair(privateKey: key.rawRepresentation, publicKey: key.publicKey.rawRepresentation)
    }

    public static func fromPrivate(_ privateKey: Data) throws -> KeyPair {
        guard privateKey.count == Noise.dhLen else { throw NoiseError("A private key is 32 bytes") }
        let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        return KeyPair(privateKey: Data(privateKey), publicKey: key.publicKey.rawRepresentation)
    }
}

enum NoiseCrypto {
    static func dh(_ privateKey: Data, _ publicKey: Data) throws -> Data {
        guard publicKey.count == Noise.dhLen else { throw NoiseError("Bad public key length") }
        do {
            let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
            let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey)
            let shared = try priv.sharedSecretFromKeyAgreement(with: pub)
            return shared.withUnsafeBytes { Data($0) }
        } catch {
            throw NoiseError("X25519 agreement failed")
        }
    }

    static func hash(_ parts: Data...) -> Data {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    static func hmac(_ key: Data, _ parts: Data...) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key))
        for p in parts { mac.update(data: p) }
        return Data(mac.finalize())
    }

    /// HKDF as defined by Noise: two outputs of HASHLEN bytes.
    static func hkdf2(_ chainingKey: Data, _ ikm: Data) -> (Data, Data) {
        let tempKey = hmac(chainingKey, ikm)
        let o1 = hmac(tempKey, Data([1]))
        let o2 = hmac(tempKey, o1, Data([2]))
        return (o1, o2)
    }
}

/// A Noise CipherState over AES-256-GCM. The nonce is 4 zero bytes followed by the 64-bit counter, big-endian.
/// Not thread safe: one direction of one connection, used from one place.
public final class CipherState: @unchecked Sendable {
    private var key: SymmetricKey?
    private var nonce: UInt64 = 0

    init(key: Data? = nil) {
        self.key = key.map { SymmetricKey(data: $0) }
    }

    public var hasKey: Bool { key != nil }

    private static func nonceBytes(_ n: UInt64) -> AES.GCM.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        for i in 0 ..< 8 { bytes[11 - i] = UInt8(truncatingIfNeeded: n >> (8 * UInt64(i))) }
        return try! AES.GCM.Nonce(data: bytes)
    }

    public func encrypt(ad: Data = Data(), _ plaintext: Data) throws -> Data {
        guard let key else { return plaintext }
        guard nonce != UInt64.max else { throw NoiseError("Nonce exhausted") }
        let n = nonce
        nonce += 1
        let box = try AES.GCM.seal(plaintext, using: key, nonce: Self.nonceBytes(n), authenticating: ad)
        return box.ciphertext + box.tag
    }

    public func decrypt(ad: Data = Data(), _ ciphertext: Data) throws -> Data {
        guard let key else { return ciphertext }
        guard ciphertext.count >= Noise.tagLen else { throw NoiseError("Ciphertext too short") }
        guard nonce != UInt64.max else { throw NoiseError("Nonce exhausted") }
        let body = ciphertext.prefix(ciphertext.count - Noise.tagLen)
        let tag = ciphertext.suffix(Noise.tagLen)
        let plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(nonce: Self.nonceBytes(nonce), ciphertext: body, tag: tag)
            plaintext = try AES.GCM.open(box, using: key, authenticating: ad)
        } catch {
            throw NoiseError("Decryption failed")
        }
        // The counter only advances on success, as the spec requires.
        nonce += 1
        return plaintext
    }
}

final class SymmetricState {
    private(set) var cipher = CipherState()
    private(set) var ck: Data
    private(set) var h: Data

    init(protocolName: String) {
        let name = Data(protocolName.utf8)
        h = name.count <= Noise.hashLen ? name + Data(count: Noise.hashLen - name.count) : NoiseCrypto.hash(name)
        ck = h
    }

    func mixKey(_ ikm: Data) {
        let (newCk, tempK) = NoiseCrypto.hkdf2(ck, ikm)
        ck = newCk
        cipher = CipherState(key: tempK)
    }

    func mixHash(_ data: Data) {
        h = NoiseCrypto.hash(h, data)
    }

    func encryptAndHash(_ plaintext: Data) throws -> Data {
        let ciphertext = try cipher.encrypt(ad: h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    func decryptAndHash(_ ciphertext: Data) throws -> Data {
        let plaintext = try cipher.decrypt(ad: h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    func split() -> (CipherState, CipherState) {
        let (k1, k2) = NoiseCrypto.hkdf2(ck, Data())
        return (CipherState(key: k1), CipherState(key: k2))
    }
}

/// The two transport ciphers after a completed handshake, seen from one side.
public struct TransportCiphers: @unchecked Sendable {
    public let send: CipherState
    public let receive: CipherState
    public let handshakeHash: Data
}

/// The IK handshake:
/// ```
/// <- s
/// ...
/// -> e, es, s, ss
/// <- e, ee, se
/// ```
/// `ephemeral` is injectable only for test vectors.
public final class IkHandshake {
    private let initiator: Bool
    private let localStatic: KeyPair
    private let ephemeral: KeyPair?
    private let ss: SymmetricState
    private var localEphemeral: KeyPair?
    private var rs: Data?
    private var re: Data?
    private var step = 0

    /// The peer's static public key: known up front for the initiator, learned from message 1 by the responder.
    public var remoteStaticKey: Data? { rs }

    private init(initiator: Bool, localStatic: KeyPair, remoteStatic: Data?, prologue: Data, ephemeral: KeyPair?, protocolName: String) {
        self.initiator = initiator
        self.localStatic = localStatic
        self.ephemeral = ephemeral
        rs = remoteStatic
        ss = SymmetricState(protocolName: protocolName)
        ss.mixHash(prologue)
        ss.mixHash(initiator ? remoteStatic! : localStatic.publicKey)
    }

    public static func initiator(localStatic: KeyPair, remoteStatic: Data, prologue: Data, ephemeral: KeyPair? = nil, protocolName: String = Noise.protocolName) -> IkHandshake {
        IkHandshake(initiator: true, localStatic: localStatic, remoteStatic: remoteStatic, prologue: prologue, ephemeral: ephemeral, protocolName: protocolName)
    }

    public static func responder(localStatic: KeyPair, prologue: Data, ephemeral: KeyPair? = nil, protocolName: String = Noise.protocolName) -> IkHandshake {
        IkHandshake(initiator: false, localStatic: localStatic, remoteStatic: nil, prologue: prologue, ephemeral: ephemeral, protocolName: protocolName)
    }

    /// Initiator: build message 1.
    public func writeMessage1(_ payload: Data) throws -> Data {
        precondition(initiator && step == 0)
        let e = ephemeral ?? KeyPair.generate()
        localEphemeral = e
        var out = e.publicKey
        ss.mixHash(e.publicKey)
        ss.mixKey(try NoiseCrypto.dh(e.privateKey, rs!))
        out += try ss.encryptAndHash(localStatic.publicKey)
        ss.mixKey(try NoiseCrypto.dh(localStatic.privateKey, rs!))
        out += try ss.encryptAndHash(payload)
        step = 1
        return out
    }

    /// Responder: read message 1 and return its payload. `remoteStaticKey` is set afterwards.
    public func readMessage1(_ message: Data) throws -> Data {
        precondition(!initiator && step == 0)
        let m = Data(message)
        let sLen = Noise.dhLen + Noise.tagLen
        guard m.count >= Noise.dhLen + sLen + Noise.tagLen else { throw NoiseError("Handshake message 1 too short") }
        let reKey = m.subdata(in: 0 ..< Noise.dhLen)
        re = reKey
        ss.mixHash(reKey)
        ss.mixKey(try NoiseCrypto.dh(localStatic.privateKey, reKey))
        let remote = try ss.decryptAndHash(m.subdata(in: Noise.dhLen ..< Noise.dhLen + sLen))
        rs = remote
        ss.mixKey(try NoiseCrypto.dh(localStatic.privateKey, remote))
        let payload = try ss.decryptAndHash(m.subdata(in: Noise.dhLen + sLen ..< m.count))
        step = 1
        return payload
    }

    /// Responder: build message 2.
    public func writeMessage2(_ payload: Data) throws -> Data {
        precondition(!initiator && step == 1)
        let e = ephemeral ?? KeyPair.generate()
        localEphemeral = e
        var out = e.publicKey
        ss.mixHash(e.publicKey)
        ss.mixKey(try NoiseCrypto.dh(e.privateKey, re!))
        ss.mixKey(try NoiseCrypto.dh(e.privateKey, rs!))
        out += try ss.encryptAndHash(payload)
        step = 2
        return out
    }

    /// Initiator: read message 2 and return its payload.
    public func readMessage2(_ message: Data) throws -> Data {
        precondition(initiator && step == 1)
        let m = Data(message)
        guard m.count >= Noise.dhLen + Noise.tagLen else { throw NoiseError("Handshake message 2 too short") }
        let reKey = m.subdata(in: 0 ..< Noise.dhLen)
        re = reKey
        ss.mixHash(reKey)
        let e = localEphemeral!
        ss.mixKey(try NoiseCrypto.dh(e.privateKey, reKey))
        ss.mixKey(try NoiseCrypto.dh(localStatic.privateKey, reKey))
        let payload = try ss.decryptAndHash(m.subdata(in: Noise.dhLen ..< m.count))
        step = 2
        return payload
    }

    /// After message 2: the transport ciphers for this side.
    public func split() -> TransportCiphers {
        precondition(step == 2, "Handshake not complete")
        let (c1, c2) = ss.split()
        return initiator
            ? TransportCiphers(send: c1, receive: c2, handshakeHash: ss.h)
            : TransportCiphers(send: c2, receive: c1, handshakeHash: ss.h)
    }
}
