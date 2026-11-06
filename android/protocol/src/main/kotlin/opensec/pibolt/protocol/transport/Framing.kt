package opensec.pibolt.protocol.transport

import opensec.pibolt.protocol.noise.CipherState
import java.io.ByteArrayOutputStream

/**
 * Transport framing: each WebSocket binary frame is one Noise transport message whose plaintext is `flag || data`.
 * Flag 0x00 marks the last chunk of a message, 0x01 a chunk with more to follow. A chunk carries at most
 * [MAX_CHUNK_DATA] bytes. The concatenated data of a message is one UTF-8 JSON object.
 */
object Framing {
    const val MAX_CHUNK_DATA = 65000
    const val FLAG_LAST: Byte = 0x00
    const val FLAG_MORE: Byte = 0x01

    /** Split [data] into plaintext chunks (flag included). An empty message is one chunk with only the flag. */
    fun chunk(data: ByteArray, maxData: Int = MAX_CHUNK_DATA): List<ByteArray> {
        require(maxData > 0)
        if (data.isEmpty()) return listOf(byteArrayOf(FLAG_LAST))
        val chunks = ArrayList<ByteArray>((data.size + maxData - 1) / maxData)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + maxData, data.size)
            val chunk = ByteArray(1 + end - offset)
            chunk[0] = if (end == data.size) FLAG_LAST else FLAG_MORE
            data.copyInto(chunk, 1, offset, end)
            chunks += chunk
            offset = end
        }
        return chunks
    }

    /** Encrypt [message] into the frames to send, in order. The caller must send them in this order. */
    fun seal(cipher: CipherState, message: ByteArray, maxData: Int = MAX_CHUNK_DATA): List<ByteArray> =
        chunk(message, maxData).map { cipher.encrypt(it) }
}

class FramingException(message: String) : Exception(message)

/** Reassembles decrypted chunks into whole messages. Not thread safe: feed it from one receive loop. */
class Reassembler(private val maxMessageBytes: Int = 32 * 1024 * 1024) {
    private val buffer = ByteArrayOutputStream()

    /** Feed one decrypted chunk; returns the complete message when this chunk was the last one. */
    fun accept(plaintext: ByteArray): ByteArray? {
        if (plaintext.isEmpty()) throw FramingException("Empty chunk")
        val flag = plaintext[0]
        if (flag != Framing.FLAG_LAST && flag != Framing.FLAG_MORE) throw FramingException("Unknown chunk flag $flag")
        if (buffer.size() + plaintext.size - 1 > maxMessageBytes) throw FramingException("Message too large")
        buffer.write(plaintext, 1, plaintext.size - 1)
        if (flag == Framing.FLAG_MORE) return null
        val message = buffer.toByteArray()
        buffer.reset()
        return message
    }
}
