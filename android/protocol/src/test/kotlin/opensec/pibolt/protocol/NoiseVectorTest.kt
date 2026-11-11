package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.noise.IkHandshake
import opensec.pibolt.protocol.noise.KeyPair
import opensec.pibolt.protocol.noise.Noise
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cacophony Noise_IK_25519_AESGCM_SHA256 vector, byte for byte. */
class NoiseVectorTest {
    private val vector: JsonObject by lazy {
        val text = javaClass.getResource("/noise-ik-vectors.json")!!.readText()
        val root = ProtocolJson.parseToJsonElement(text) as JsonObject
        (root["vectors"] as JsonArray).map { it as JsonObject }.single { it.str("protocol_name") == Noise.PROTOCOL_NAME }
    }

    @Test
    fun reproducesCacophonyVector() {
        val v = vector
        val initiator = IkHandshake.initiator(
            localStatic = KeyPair.fromPrivate(v.str("init_static")!!.hex()),
            remoteStatic = v.str("init_remote_static")!!.hex(),
            prologue = v.str("init_prologue")!!.hex(),
            ephemeral = KeyPair.fromPrivate(v.str("init_ephemeral")!!.hex()),
        )
        val responder = IkHandshake.responder(
            localStatic = KeyPair.fromPrivate(v.str("resp_static")!!.hex()),
            prologue = v.str("resp_prologue")!!.hex(),
            ephemeral = KeyPair.fromPrivate(v.str("resp_ephemeral")!!.hex()),
        )
        assertEquals(v.str("init_remote_static"), KeyPair.fromPrivate(v.str("resp_static")!!.hex()).publicKey.hex())

        val messages = (v["messages"] as JsonArray).map { it as JsonObject }
        assertEquals(6, messages.size)

        val m0 = messages[0]
        val c0 = initiator.writeMessage1(m0.str("payload")!!.hex())
        assertEquals(m0.str("ciphertext"), c0.hex())
        assertEquals(m0.str("payload"), responder.readMessage1(c0).hex())
        assertEquals(KeyPair.fromPrivate(v.str("init_static")!!.hex()).publicKey.hex(), responder.remoteStaticKey!!.hex())

        val m1 = messages[1]
        val c1 = responder.writeMessage2(m1.str("payload")!!.hex())
        assertEquals(m1.str("ciphertext"), c1.hex())
        assertEquals(m1.str("payload"), initiator.readMessage2(c1).hex())

        val i = initiator.split()
        val r = responder.split()
        assertEquals(v.str("handshake_hash"), i.handshakeHash.hex())
        assertEquals(v.str("handshake_hash"), r.handshakeHash.hex())

        // Transport messages alternate: initiator to responder, then back.
        for ((n, m) in messages.drop(2).withIndex()) {
            val payload = m.str("payload")!!.hex()
            val fromInitiator = n % 2 == 0
            val ciphertext = if (fromInitiator) i.send.encrypt(payload) else r.send.encrypt(payload)
            assertEquals(m.str("ciphertext"), ciphertext.hex(), "transport message ${n + 2}")
            val plaintext = if (fromInitiator) r.receive.decrypt(ciphertext) else i.receive.decrypt(ciphertext)
            assertEquals(m.str("payload"), plaintext.hex())
        }
    }
}
