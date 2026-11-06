package opensec.pibolt.protocol

import opensec.pibolt.protocol.noise.IkHandshake
import opensec.pibolt.protocol.noise.KeyPair
import opensec.pibolt.protocol.noise.NoiseException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HandshakeTest {
    private val host = KeyPair.generate()
    private val phone = KeyPair.generate()

    private fun handshake(): Pair<IkHandshake, IkHandshake> {
        val initiator = IkHandshake.initiator(phone, host.publicKey, NOISE_PROLOGUE)
        val responder = IkHandshake.responder(host, NOISE_PROLOGUE)
        val m1 = initiator.writeMessage1("""{"device":"Pixel","app":"0.1.0"}""".toByteArray())
        assertEquals("""{"device":"Pixel","app":"0.1.0"}""", String(responder.readMessage1(m1)))
        assertContentEquals(phone.publicKey, responder.remoteStaticKey)
        val m2 = responder.writeMessage2("""{"host":"mac","paired":true}""".toByteArray())
        assertEquals("""{"host":"mac","paired":true}""", String(initiator.readMessage2(m2)))
        return initiator to responder
    }

    @Test
    fun initiatorAndResponderAgree() {
        val (initiator, responder) = handshake()
        val i = initiator.split()
        val r = responder.split()
        assertContentEquals(i.handshakeHash, r.handshakeHash)
        repeat(3) { n ->
            val up = i.send.encrypt("up $n".toByteArray())
            assertEquals("up $n", String(r.receive.decrypt(up)))
            val down = r.send.encrypt("down $n".toByteArray())
            assertEquals("down $n", String(i.receive.decrypt(down)))
        }
    }

    @Test
    fun wrongHostKeyFailsAtResponder() {
        val initiator = IkHandshake.initiator(phone, KeyPair.generate().publicKey, NOISE_PROLOGUE)
        val responder = IkHandshake.responder(host, NOISE_PROLOGUE)
        val m1 = initiator.writeMessage1(ByteArray(0))
        assertFailsWith<NoiseException> { responder.readMessage1(m1) }
    }

    @Test
    fun differentPrologueFails() {
        val initiator = IkHandshake.initiator(phone, host.publicKey, "other".toByteArray())
        val responder = IkHandshake.responder(host, NOISE_PROLOGUE)
        assertFailsWith<NoiseException> { responder.readMessage1(initiator.writeMessage1(ByteArray(0))) }
    }

    @Test
    fun tamperedTransportMessageFailsAndKeepsCounter() {
        val (initiator, responder) = handshake()
        val i = initiator.split()
        val r = responder.split()
        val good = i.send.encrypt("hello".toByteArray())
        val bad = good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<NoiseException> { r.receive.decrypt(bad) }
        assertEquals("hello", String(r.receive.decrypt(good)))
    }

    @Test
    fun replayedMessageFails() {
        val (initiator, responder) = handshake()
        val i = initiator.split()
        val r = responder.split()
        val first = i.send.encrypt("a".toByteArray())
        r.receive.decrypt(first)
        assertFailsWith<NoiseException> { r.receive.decrypt(first) }
    }
}
