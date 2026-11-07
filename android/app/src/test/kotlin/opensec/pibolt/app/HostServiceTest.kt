package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.ProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The background service's flag on a paired computer, in hosts.json. */
class HostServiceTest {
    private fun parse(text: String) = Host.fromJson(ProtocolJson.parseToJsonElement(text) as JsonObject)!!

    @Test
    fun aFileWrittenBeforeTheFlagReadsAsNoService() {
        val host = parse("""{"id":"k","name":"MacBook","endpoints":["ws://10.0.0.2:48123/rc"],"version":"1.1.1","cwd":"/w","lastConnectedAt":5,"pairedAt":1}""")
        assertFalse(host.service)
        assertEquals(listOf("ws://10.0.0.2:48123/rc"), host.endpoints)
    }

    @Test
    fun theFlagRoundTripsAndIsLeftOutWhenFalse() {
        val host = Host(id = "k", name = "MacBook", endpoints = listOf("ws://10.0.0.2:48123/rc", "wss://a.trycloudflare.com/rc"), service = true)
        val json = host.toJson()
        assertEquals(true.toString(), json["service"].toString())
        assertEquals(host, Host.fromJson(json))
        val plain = host.copy(service = false).toJson()
        assertFalse("service" in plain)
        assertEquals(host.copy(service = false), Host.fromJson(plain))
    }

    @Test
    fun aMalformedFlagIsNoService() {
        assertFalse(parse("""{"id":"k","name":"M","endpoints":[],"service":"true"}""").service)
        assertTrue(parse("""{"id":"k","name":"M","endpoints":[],"service":true}""").service)
    }

    @Test
    fun handoffNoticeNamesTheTerminal() {
        assertTrue(SessionController.HANDOFF_NOTICE.startsWith("The computer's terminal took over"))
    }
}
