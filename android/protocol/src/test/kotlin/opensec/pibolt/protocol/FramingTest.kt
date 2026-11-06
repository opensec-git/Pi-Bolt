package opensec.pibolt.protocol

import opensec.pibolt.protocol.noise.IkHandshake
import opensec.pibolt.protocol.noise.KeyPair
import opensec.pibolt.protocol.noise.Noise
import opensec.pibolt.protocol.transport.Framing
import opensec.pibolt.protocol.transport.FramingException
import opensec.pibolt.protocol.transport.Reassembler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {
    @Test
    fun smallMessageIsOneLastChunk() {
        val chunks = Framing.chunk("{}".toByteArray())
        assertEquals(1, chunks.size)
        assertContentEquals(byteArrayOf(0, '{'.code.toByte(), '}'.code.toByte()), chunks[0])
    }

    @Test
    fun emptyMessageIsOnlyTheFlag() {
        assertContentEquals(byteArrayOf(0), Framing.chunk(ByteArray(0)).single())
    }

    @Test
    fun exactBoundaries() {
        assertEquals(1, Framing.chunk(ByteArray(65000)).size)
        val two = Framing.chunk(ByteArray(65001))
        assertEquals(2, two.size)
        assertEquals(Framing.FLAG_MORE, two[0][0])
        assertEquals(65001, two[0].size)
        assertEquals(Framing.FLAG_LAST, two[1][0])
        assertEquals(2, two[1].size)
    }

    @Test
    fun reassemblesInOrder() {
        val data = Random(1).nextBytes(200_000)
        val chunks = Framing.chunk(data)
        assertEquals(4, chunks.size)
        val reassembler = Reassembler()
        chunks.dropLast(1).forEach { assertNull(reassembler.accept(it)) }
        assertContentEquals(data, reassembler.accept(chunks.last()))
        // Ready for the next message.
        assertContentEquals("x".toByteArray(), reassembler.accept(byteArrayOf(0, 'x'.code.toByte())))
    }

    @Test
    fun rejectsBadFlagAndOversize() {
        assertFailsWith<FramingException> { Reassembler().accept(byteArrayOf(2, 1)) }
        assertFailsWith<FramingException> { Reassembler().accept(ByteArray(0)) }
        val small = Reassembler(maxMessageBytes = 10)
        assertFailsWith<FramingException> { small.accept(ByteArray(12).also { it[0] = 1 }) }
    }

    @Test
    fun sealedFramesFitANoiseMessage() {
        val host = KeyPair.generate()
        val i = IkHandshake.initiator(KeyPair.generate(), host.publicKey, NOISE_PROLOGUE)
        val r = IkHandshake.responder(host, NOISE_PROLOGUE)
        r.readMessage1(i.writeMessage1(ByteArray(0)))
        i.readMessage2(r.writeMessage2(ByteArray(0)))
        val send = i.split().send
        val receive = r.split().receive
        val json = buildString { append("{\"text\":\""); repeat(150_000) { append('a' + it % 26) }; append("\"}") }.toByteArray()
        val frames = Framing.seal(send, json)
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size <= Noise.MAX_MESSAGE_LEN })
        val reassembler = Reassembler()
        var out: ByteArray? = null
        for (f in frames) out = reassembler.accept(receive.decrypt(f))
        assertContentEquals(json, out)
    }
}
