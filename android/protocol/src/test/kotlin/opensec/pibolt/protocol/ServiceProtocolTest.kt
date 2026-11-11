package opensec.pibolt.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.HostHello
import opensec.pibolt.protocol.backend.SessionBackend
import opensec.pibolt.protocol.rpc.RemoteProject
import opensec.pibolt.protocol.rpc.RemoteSessionInfo
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.rpc.RpcResponse
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The background service's additions to the protocol (docs/remote-control.md, "Background service"). */
class ServiceProtocolTest {
    private fun json(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    @Test
    fun endpointsKeepValidOnesInOrderOnceEach() {
        val message = json("""{"type":"remote_endpoints","endpoints":["ws://192.168.1.20:48123/rc","http://evil/rc","ws://user:pw@h/rc",7,"ws://192.168.1.20:48123/rc","wss://new-fox.trycloudflare.com/rc"]}""")
        assertEquals(
            listOf("ws://192.168.1.20:48123/rc", "wss://new-fox.trycloudflare.com/rc"),
            RemoteEndpoints.from(message, connected = "ws://192.168.1.20:48123/rc"),
        )
    }

    @Test
    fun endpointsKeepTheOneInUse() {
        // A quick tunnel restarted: the list names the new tunnel, not the LAN address this phone is connected on.
        val message = json("""{"type":"remote_endpoints","endpoints":["wss://new-fox.trycloudflare.com/rc"]}""")
        assertEquals(
            listOf("wss://new-fox.trycloudflare.com/rc", "ws://10.0.0.2:48123/rc"),
            RemoteEndpoints.from(message, connected = "ws://10.0.0.2:48123/rc"),
        )
        assertEquals(listOf("wss://new-fox.trycloudflare.com/rc"), RemoteEndpoints.from(message, connected = null))
    }

    @Test
    fun endpointsAreCappedWithRoomForTheOneInUse() {
        val many = (1..20).joinToString(",") { "\"ws://10.0.0.$it:1/rc\"" }
        val message = json("""{"type":"remote_endpoints","endpoints":[$many]}""")
        val kept = RemoteEndpoints.from(message, connected = null)!!
        assertEquals(PairingPayload.MAX_ENDPOINTS, kept.size)
        assertEquals("ws://10.0.0.1:1/rc", kept.first())
        val withCurrent = RemoteEndpoints.from(message, connected = "wss://t.trycloudflare.com/rc")!!
        assertEquals(PairingPayload.MAX_ENDPOINTS, withCurrent.size)
        assertEquals("wss://t.trycloudflare.com/rc", withCurrent.last())
        // Already in the list: not added twice.
        assertEquals(kept, RemoteEndpoints.from(message, connected = "ws://10.0.0.3:1/rc"))
    }

    @Test
    fun malformedEndpointsMessagesAreIgnored() {
        assertNull(RemoteEndpoints.from(json("""{"type":"remote_endpoints"}"""), "ws://a/rc"))
        assertNull(RemoteEndpoints.from(json("""{"type":"remote_endpoints","endpoints":"ws://a/rc"}"""), "ws://a/rc"))
        assertNull(RemoteEndpoints.from(json("""{"type":"remote_endpoints","endpoints":{"a":1}}"""), null))
        assertNull(RemoteEndpoints.from(json("""{"type":"remote_endpoints","endpoints":["http://x/rc",null]}"""), null))
        // Nothing valid sent, but the connection in use stays usable.
        assertEquals(listOf("ws://a/rc"), RemoteEndpoints.from(json("""{"type":"remote_endpoints","endpoints":[]}"""), "ws://a/rc"))
    }

    @Test
    fun helloCarriesTheServiceFlag() {
        val service = SessionReducer.reduce(SessionState(), json("""{"type":"remote_hello","protocol":1,"host":{"name":"m","version":"1.2.0","cwd":"/w","service":true},"state":{},"messages":[],"dialogs":[],"commands":[]}"""))
        assertTrue(service.host!!.service)
        val terminal = SessionReducer.reduce(SessionState(), json("""{"type":"remote_hello","protocol":1,"host":{"name":"m","version":"1.2.0","cwd":"/w"},"state":{},"messages":[],"dialogs":[],"commands":[]}"""))
        assertFalse(terminal.host!!.service)
        val odd = SessionReducer.reduce(SessionState(), json("""{"type":"remote_hello","protocol":1,"host":{"name":"m","service":"yes"},"state":{},"messages":[],"dialogs":[],"commands":[]}"""))
        assertFalse(odd.host!!.service)
    }

    @Test
    fun helloCarriesTheParallelSessionsCapability() {
        // A service that keeps one runtime per session: switching never stops a running one.
        val parallel = SessionReducer.reduce(SessionState(), json("""{"type":"remote_hello","protocol":1,"host":{"name":"m","version":"1.1.5","cwd":"/w","service":true,"parallelSessions":true},"state":{},"messages":[],"dialogs":[],"commands":[]}"""))
        assertTrue(parallel.host!!.parallelSessions)
        // An older service (or a terminal) says nothing: the app refuses to switch it mid-turn.
        val old = SessionReducer.reduce(SessionState(), json("""{"type":"remote_hello","protocol":1,"host":{"name":"m","version":"1.1.4","cwd":"/w","service":true},"state":{},"messages":[],"dialogs":[],"commands":[]}"""))
        assertFalse(old.host!!.parallelSessions)
        val odd = SessionReducer.reduce(SessionState(), json("""{"type":"remote_hello","protocol":1,"host":{"name":"m","parallelSessions":"yes"},"state":{},"messages":[],"dialogs":[],"commands":[]}"""))
        assertFalse(odd.host!!.parallelSessions)
    }

    @Test
    fun closeCode1012IsAHandoffWorthRetryingAtOnce() {
        assertEquals(DisconnectReason.Handoff, DisconnectReason.fromCloseCode(1012))
        assertTrue(DisconnectReason.Handoff.retryable)
        assertEquals(DisconnectReason.HostStopped, DisconnectReason.fromCloseCode(1001))
    }

    /** Answers each command from [answers] by type, and records what was sent. */
    private class ScriptedBackend(private val answers: Map<String, String>) : SessionBackend {
        val sent = mutableListOf<JsonObject>()
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected("ws://a/rc", HostHello("m", "1.2.0", false)))
        private val inbox = Channel<JsonObject>(Channel.UNLIMITED)
        override val incoming: Flow<JsonObject> = inbox.receiveAsFlow()

        override suspend fun connect() = true

        override fun send(message: JsonObject): Boolean {
            sent += message
            val type = message.type!!
            val data = answers[type] ?: return true
            inbox.trySend(ProtocolJson.parseToJsonElement("""{"id":"${message.str("id")}","type":"response","command":"$type","success":true,"data":$data}""") as JsonObject)
            return true
        }

        override fun close() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun listProjectsAndSessionsRoundTrip(): Unit = runBlocking {
        val backend = ScriptedBackend(
            mapOf(
                "list_projects" to """{"projects":[{"cwd":"/Users/me/app","name":"app","sessions":3,"updatedAt":"2026-11-07T10:00:00.000Z"},{"name":"no cwd"},"junk",{"cwd":"/Users/me/lib","sessions":1,"updatedAt":"bad"}]}""",
                "list_sessions" to """{"sessions":[{"path":"/s/a.jsonl","id":"a","cwd":"/Users/me/app","name":"Fix tests","preview":"run the tests","updatedAt":"2026-11-07T09:00:00.000Z","messageCount":12},{"path":"/s/b.jsonl","id":"b","cwd":"/Users/me/app","preview":"hello","updatedAt":"2026-11-06T09:00:00.000Z","messageCount":1},{"id":"no path"}]}""",
                "new_session" to """{"cancelled":false}""",
                "switch_session" to """{"cancelled":false}""",
            ),
        )
        val rpc = RpcClient(backend, scope)
        val projects = RemoteProject.listFrom(rpc.listProjects())
        assertEquals(
            listOf(
                RemoteProject("/Users/me/app", "app", 3, "2026-11-07T10:00:00.000Z"),
                RemoteProject("/Users/me/lib", "lib", 1, "bad"),
            ),
            projects,
        )
        assertEquals(Instant.parse("2026-11-07T10:00:00.000Z").toEpochMilli(), projects[0].updatedAtMs)
        assertEquals(0, projects[1].updatedAtMs)

        val sessions = RemoteSessionInfo.listFrom(rpc.listSessions("/Users/me/app"))
        assertEquals(2, sessions.size)
        assertEquals("Fix tests", sessions[0].title)
        assertEquals(12, sessions[0].messageCount)
        assertEquals("hello", sessions[1].title)
        assertNull(sessions[1].name)
        // An older service sends no running/open flags.
        assertFalse(sessions[0].running || sessions[0].open)
        assertEquals("/Users/me/app", backend.sent.last().str("cwd"))

        assertTrue(rpc.newSession(cwd = "/Users/me/app").success)
        assertEquals("new_session", backend.sent.last().type)
        assertEquals("/Users/me/app", backend.sent.last().str("cwd"))
        assertTrue(rpc.switchSession(sessions[0].path).success)
        assertEquals("/s/a.jsonl", backend.sent.last().str("sessionPath"))

        // No cwd: the field is left out, so the service uses the open session's folder.
        rpc.listSessions()
        assertFalse("cwd" in backend.sent.last())
        rpc.stop()
    }

    @Test
    fun sessionsSayWhichRunOnTheComputer() {
        val response = RpcResponse("list_sessions", true, json("""{"sessions":[{"path":"/s/a.jsonl","id":"a","cwd":"/w","preview":"x","updatedAt":"","messageCount":2,"running":true,"open":true},{"path":"/s/b.jsonl","id":"b","cwd":"/w","preview":"y","updatedAt":"","messageCount":1,"open":true},{"path":"/s/c.jsonl","id":"c","cwd":"/w","preview":"z","updatedAt":"","messageCount":1,"running":"yes"}]}"""), null)
        val sessions = RemoteSessionInfo.listFrom(response)
        assertEquals(listOf(true to true, false to true, false to false), sessions.map { it.running to it.open })
    }

    @Test
    fun failedOrEmptyAnswersListNothing() {
        assertEquals(emptyList(), RemoteProject.listFrom(RpcResponse.failure("list_projects", "Not available over remote control")))
        assertEquals(emptyList(), RemoteSessionInfo.listFrom(RpcResponse("list_sessions", true, null, null)))
    }
}
