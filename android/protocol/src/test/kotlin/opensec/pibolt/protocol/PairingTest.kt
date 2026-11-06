package opensec.pibolt.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTest {
    private val key = ByteArray(32) { it.toByte() }
    private val secret = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun roundTrip() {
        val payload = PairingPayload(1, "macbook", key, secret, listOf("ws://192.168.1.20:48123/rc", "wss://quiet-fox.trycloudflare.com/rc"), 1793000000)
        val text = payload.encode()
        assertTrue(text.startsWith("pibolt://pair#"))
        assertFalse(text.contains("="))
        assertEquals(payload, PairingPayload.parse(text))
        assertEquals(payload, PairingPayload.parse("  $text\n"))
    }

    @Test
    fun parsesHostJson() {
        // As the host writes it: field order and extra fields do not matter.
        val json = """{"x":1793000000,"e":["ws://10.0.0.2:1/rc","http://bad","wss://a.trycloudflare.com/rc"],"k":"${Base64Url.encode(key)}","s":"${Base64Url.encode(secret)}","n":"studio","v":1,"extra":true}"""
        val parsed = PairingPayload.parse("pibolt://pair#" + Base64Url.encode(json.toByteArray()))
        assertEquals("studio", parsed.hostName)
        assertEquals(listOf("ws://10.0.0.2:1/rc", "wss://a.trycloudflare.com/rc"), parsed.endpoints)
        assertEquals(1793000000, parsed.expiresAt)
        assertTrue(parsed.isExpired(1793000000))
        assertFalse(parsed.isExpired(1792999999))
    }

    @Test
    fun rejectsBadCodes() {
        assertFailsWith<PairingParseException> { PairingPayload.parse("https://example.com") }
        assertFailsWith<PairingParseException> { PairingPayload.parse("pibolt://pair#!!!") }
        fun code(json: String) = "pibolt://pair#" + Base64Url.encode(json.toByteArray())
        val k = Base64Url.encode(key)
        val s = Base64Url.encode(secret)
        assertFailsWith<PairingParseException> { PairingPayload.parse(code("""{"v":2,"k":"$k","s":"$s","e":["ws://a/rc"],"x":1}""")) }
        assertFailsWith<PairingParseException> { PairingPayload.parse(code("""{"v":1,"k":"AAAA","s":"$s","e":["ws://a/rc"],"x":1}""")) }
        assertFailsWith<PairingParseException> { PairingPayload.parse(code("""{"v":1,"k":"$k","s":"$s","e":[],"x":1}""")) }
    }

    @Test
    fun pairingCodesAreBoundedAndValidated() {
        val good = PairingPayload(1, "host\u001b[31m", ByteArray(32) { 1 }, ByteArray(32) { 2 }, emptyList(), 0)
        val endpoints = listOf("ws://192.168.1.2:47800/rc", "http://evil/rc", "ws://user:pw@h/rc", "wss://a.trycloudflare.com/rc", "ws:///rc", "ws://" + "x".repeat(300)) +
            (1..20).map { "ws://10.0.0.$it:1/rc" }
        val parsed = PairingPayload.parse(good.copy(endpoints = endpoints).encode())
        assertEquals(PairingPayload.MAX_ENDPOINTS, parsed.endpoints.size)
        assertEquals(listOf("ws://192.168.1.2:47800/rc", "wss://a.trycloudflare.com/rc"), parsed.endpoints.take(2))
        assertEquals("host[31m", parsed.hostName)
        assertFailsWith<PairingParseException> { PairingPayload.parse(PAIR_PREFIX + "A".repeat(PairingPayload.MAX_LINK_CHARS)) }
        assertFailsWith<PairingParseException> { PairingPayload.parse(good.copy(endpoints = listOf("http://x/rc")).encode()) }
    }
}
