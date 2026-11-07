import Foundation
@testable import PiBoltProtocol
import Testing

extension String {
    var hexData: Data {
        var out = Data(capacity: count / 2)
        var i = startIndex
        while i < endIndex {
            let j = index(i, offsetBy: 2)
            out.append(UInt8(self[i ..< j], radix: 16)!)
            i = j
        }
        return out
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

/// The cacophony Noise_IK_25519_AESGCM_SHA256 vector, byte for byte (android/protocol NoiseVectorTest).
struct NoiseVectorTests {
    private func vector() throws -> JSON {
        let url = try #require(Bundle.module.url(forResource: "noise-ik-vectors", withExtension: "json"))
        let root = try JSON.parse(Data(contentsOf: url))
        return try #require(root.arr("vectors")?.first { $0.str("protocol_name") == Noise.protocolName })
    }

    @Test func reproducesCacophonyVector() throws {
        let v = try vector()
        func hex(_ key: String) -> Data { v.str(key)!.hexData }
        let initiator = IkHandshake.initiator(
            localStatic: try KeyPair.fromPrivate(hex("init_static")),
            remoteStatic: hex("init_remote_static"),
            prologue: hex("init_prologue"),
            ephemeral: try KeyPair.fromPrivate(hex("init_ephemeral"))
        )
        let responder = IkHandshake.responder(
            localStatic: try KeyPair.fromPrivate(hex("resp_static")),
            prologue: hex("resp_prologue"),
            ephemeral: try KeyPair.fromPrivate(hex("resp_ephemeral"))
        )
        #expect(v.str("init_remote_static") == (try KeyPair.fromPrivate(hex("resp_static"))).publicKey.hex)

        let messages = try #require(v.arr("messages"))
        #expect(messages.count == 6)

        let m0 = messages[0]
        let c0 = try initiator.writeMessage1(m0.str("payload")!.hexData)
        #expect(c0.hex == m0.str("ciphertext"))
        #expect(try responder.readMessage1(c0).hex == m0.str("payload"))
        #expect(responder.remoteStaticKey?.hex == (try KeyPair.fromPrivate(hex("init_static"))).publicKey.hex)

        let m1 = messages[1]
        let c1 = try responder.writeMessage2(m1.str("payload")!.hexData)
        #expect(c1.hex == m1.str("ciphertext"))
        #expect(try initiator.readMessage2(c1).hex == m1.str("payload"))

        let i = initiator.split()
        let r = responder.split()
        #expect(i.handshakeHash.hex == v.str("handshake_hash"))
        #expect(r.handshakeHash.hex == v.str("handshake_hash"))

        // Transport messages alternate: initiator to responder, then back.
        for (n, m) in messages.dropFirst(2).enumerated() {
            let payload = m.str("payload")!.hexData
            let fromInitiator = n % 2 == 0
            let ciphertext = try (fromInitiator ? i.send : r.send).encrypt(payload)
            #expect(ciphertext.hex == m.str("ciphertext"), "transport message \(n + 2)")
            let plaintext = try (fromInitiator ? r.receive : i.receive).decrypt(ciphertext)
            #expect(plaintext.hex == m.str("payload"))
        }
    }
}

struct HandshakeTests {
    let host = KeyPair.generate()
    let phone = KeyPair.generate()

    private func handshake() throws -> (IkHandshake, IkHandshake) {
        let initiator = IkHandshake.initiator(localStatic: phone, remoteStatic: host.publicKey, prologue: noisePrologue)
        let responder = IkHandshake.responder(localStatic: host, prologue: noisePrologue)
        let m1 = try initiator.writeMessage1(Data(#"{"device":"Pixel","app":"0.1.0"}"#.utf8))
        #expect(String(decoding: try responder.readMessage1(m1), as: UTF8.self) == #"{"device":"Pixel","app":"0.1.0"}"#)
        #expect(responder.remoteStaticKey == phone.publicKey)
        let m2 = try responder.writeMessage2(Data(#"{"host":"mac","paired":true}"#.utf8))
        #expect(String(decoding: try initiator.readMessage2(m2), as: UTF8.self) == #"{"host":"mac","paired":true}"#)
        return (initiator, responder)
    }

    @Test func initiatorAndResponderAgree() throws {
        let (initiator, responder) = try handshake()
        let i = initiator.split()
        let r = responder.split()
        #expect(i.handshakeHash == r.handshakeHash)
        for n in 0 ..< 3 {
            let up = try i.send.encrypt(Data("up \(n)".utf8))
            #expect(String(decoding: try r.receive.decrypt(up), as: UTF8.self) == "up \(n)")
            let down = try r.send.encrypt(Data("down \(n)".utf8))
            #expect(String(decoding: try i.receive.decrypt(down), as: UTF8.self) == "down \(n)")
        }
    }

    @Test func wrongHostKeyFailsAtResponder() throws {
        let initiator = IkHandshake.initiator(localStatic: phone, remoteStatic: KeyPair.generate().publicKey, prologue: noisePrologue)
        let responder = IkHandshake.responder(localStatic: host, prologue: noisePrologue)
        let m1 = try initiator.writeMessage1(Data())
        #expect(throws: NoiseError.self) { try responder.readMessage1(m1) }
    }

    @Test func differentPrologueFails() throws {
        let initiator = IkHandshake.initiator(localStatic: phone, remoteStatic: host.publicKey, prologue: Data("other".utf8))
        let responder = IkHandshake.responder(localStatic: host, prologue: noisePrologue)
        let m1 = try initiator.writeMessage1(Data())
        #expect(throws: NoiseError.self) { try responder.readMessage1(m1) }
    }

    @Test func tamperedTransportMessageFailsAndKeepsCounter() throws {
        let (initiator, responder) = try handshake()
        let i = initiator.split()
        let r = responder.split()
        let good = try i.send.encrypt(Data("hello".utf8))
        var bad = good
        bad[bad.startIndex] ^= 1
        #expect(throws: NoiseError.self) { try r.receive.decrypt(bad) }
        #expect(String(decoding: try r.receive.decrypt(good), as: UTF8.self) == "hello")
    }

    @Test func replayedMessageFails() throws {
        let (initiator, responder) = try handshake()
        let i = initiator.split()
        let r = responder.split()
        let first = try i.send.encrypt(Data("a".utf8))
        _ = try r.receive.decrypt(first)
        #expect(throws: NoiseError.self) { try r.receive.decrypt(first) }
    }
}

struct FramingTests {
    @Test func smallMessageIsOneLastChunk() {
        let chunks = Framing.chunk(Data("{}".utf8))
        #expect(chunks == [Data([0, UInt8(ascii: "{"), UInt8(ascii: "}")])])
    }

    @Test func emptyMessageIsOnlyTheFlag() {
        #expect(Framing.chunk(Data()) == [Data([0])])
    }

    @Test func exactBoundaries() {
        #expect(Framing.chunk(Data(count: 65000)).count == 1)
        let two = Framing.chunk(Data(count: 65001))
        #expect(two.count == 2)
        #expect(two[0].first == Framing.flagMore)
        #expect(two[0].count == 65001)
        #expect(two[1].first == Framing.flagLast)
        #expect(two[1].count == 2)
    }

    @Test func reassemblesInOrder() throws {
        var generator = SystemRandomNumberGenerator()
        let data = Data((0 ..< 200_000).map { _ in UInt8.random(in: 0 ... 255, using: &generator) })
        let chunks = Framing.chunk(data)
        #expect(chunks.count == 4)
        let reassembler = Reassembler()
        for c in chunks.dropLast() { #expect(try reassembler.accept(c) == nil) }
        #expect(try reassembler.accept(chunks.last!) == data)
        // Ready for the next message.
        #expect(try reassembler.accept(Data([0, UInt8(ascii: "x")])) == Data("x".utf8))
    }

    @Test func rejectsBadFlagAndOversize() {
        #expect(throws: FramingError.self) { try Reassembler().accept(Data([2, 1])) }
        #expect(throws: FramingError.self) { try Reassembler().accept(Data()) }
        var big = Data(count: 12)
        big[0] = 1
        #expect(throws: FramingError.self) { try Reassembler(maxMessageBytes: 10).accept(big) }
    }

    @Test func sealedFramesFitANoiseMessage() throws {
        let host = KeyPair.generate()
        let i = IkHandshake.initiator(localStatic: KeyPair.generate(), remoteStatic: host.publicKey, prologue: noisePrologue)
        let r = IkHandshake.responder(localStatic: host, prologue: noisePrologue)
        _ = try r.readMessage1(try i.writeMessage1(Data()))
        _ = try i.readMessage2(try r.writeMessage2(Data()))
        let send = i.split().send
        let receive = r.split().receive
        let letters = Array("abcdefghijklmnopqrstuvwxyz")
        let text = "{\"text\":\"" + String((0 ..< 150_000).map { letters[$0 % 26] }) + "\"}"
        let json = Data(text.utf8)
        let frames = try Framing.seal(send, json)
        #expect(frames.count == 3)
        #expect(frames.allSatisfy { $0.count <= Noise.maxMessageLen })
        let reassembler = Reassembler()
        var out: Data?
        for f in frames { out = try reassembler.accept(try receive.decrypt(f)) }
        #expect(out == json)
    }
}
