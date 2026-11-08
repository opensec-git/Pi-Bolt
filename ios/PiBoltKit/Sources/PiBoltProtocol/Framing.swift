import Foundation

/// Transport framing: each WebSocket binary frame is one Noise transport message whose plaintext is `flag || data`.
/// Flag 0x00 marks the last chunk of a message, 0x01 a chunk with more to follow. A chunk carries at most
/// `maxChunkData` bytes. The concatenated data of a message is one UTF-8 JSON object.
public enum Framing {
    public static let maxChunkData = 65000
    public static let flagLast: UInt8 = 0x00
    public static let flagMore: UInt8 = 0x01

    /// Split `data` into plaintext chunks (flag included). An empty message is one chunk with only the flag.
    public static func chunk(_ data: Data, maxData: Int = maxChunkData) -> [Data] {
        precondition(maxData > 0)
        let bytes = Data(data)
        if bytes.isEmpty { return [Data([flagLast])] }
        var chunks: [Data] = []
        chunks.reserveCapacity((bytes.count + maxData - 1) / maxData)
        var offset = 0
        while offset < bytes.count {
            let end = min(offset + maxData, bytes.count)
            var chunk = Data(capacity: 1 + end - offset)
            chunk.append(end == bytes.count ? flagLast : flagMore)
            chunk.append(bytes.subdata(in: offset ..< end))
            chunks.append(chunk)
            offset = end
        }
        return chunks
    }

    /// Encrypt `message` into the frames to send, in order. The caller must send them in this order.
    public static func seal(_ cipher: CipherState, _ message: Data, maxData: Int = maxChunkData) throws -> [Data] {
        try chunk(message, maxData: maxData).map { try cipher.encrypt($0) }
    }
}

public struct FramingError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// Reassembles decrypted chunks into whole messages. Not thread safe: feed it from one receive loop.
public final class Reassembler {
    private let maxMessageBytes: Int
    private var buffer = Data()

    public init(maxMessageBytes: Int = 32 * 1024 * 1024) {
        self.maxMessageBytes = maxMessageBytes
    }

    /// Feed one decrypted chunk; returns the complete message when this chunk was the last one.
    public func accept(_ plaintext: Data) throws -> Data? {
        guard let flag = plaintext.first else { throw FramingError("Empty chunk") }
        guard flag == Framing.flagLast || flag == Framing.flagMore else { throw FramingError("Unknown chunk flag \(flag)") }
        guard buffer.count + plaintext.count - 1 <= maxMessageBytes else { throw FramingError("Message too large") }
        buffer.append(plaintext.dropFirst())
        if flag == Framing.flagMore { return nil }
        let message = buffer
        buffer = Data()
        return message
    }
}
