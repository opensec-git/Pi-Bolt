package opensec.pibolt.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.RemoteBackend
import opensec.pibolt.protocol.backend.RemoteConfig
import opensec.pibolt.protocol.noise.CipherState
import opensec.pibolt.protocol.noise.IkHandshake
import opensec.pibolt.protocol.noise.KeyPair
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.transport.Framing
import opensec.pibolt.protocol.transport.Reassembler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal host written to docs/remote-control.md, to run the client end to end. */
private class TestHost(
    val key: KeyPair,
    val secret: ByteArray,
    val paired: MutableSet<String>,
    val hellos: MutableList<JsonObject> = CopyOnWriteArrayList(),
    val commands: MutableList<JsonObject> = CopyOnWriteArrayList(),
) : WebSocketListener() {
    private var noise: IkHandshake? = null
    private var send: CipherState? = null
    private var receive: CipherState? = null
    private val reassembler = Reassembler()
    private var ready = false

    override fun onOpen(webSocket: WebSocket, response: Response) {
        noise = IkHandshake.responder(key, NOISE_PROLOGUE)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        try {
            handle(webSocket, bytes)
        } catch (e: Exception) {
            webSocket.close(4400, null)
        }
    }

    private fun handle(webSocket: WebSocket, bytes: ByteString) {
        if (send == null) {
            val n = noise!!
            val hello = ProtocolJson.parseToJsonElement(String(n.readMessage1(bytes.toByteArray()))) as JsonObject
            hellos += hello
            val device = Base64Url.encode(n.remoteStaticKey!!)
            val pairing = hello.str("pair")?.let { Base64Url.decode(it).contentEquals(secret) } == true
            if (device !in paired && !pairing) {
                webSocket.close(4403, null)
                return
            }
            paired += device
            webSocket.send(n.writeMessage2("""{"host":"testhost","version":"1.1.1","paired":$pairing}""".toByteArray()).toByteString())
            val ciphers = n.split()
            send = ciphers.send
            receive = ciphers.receive
            return
        }
        val message = reassembler.accept(receive!!.decrypt(bytes.toByteArray())) ?: return
        val command = ProtocolJson.parseToJsonElement(String(message)) as JsonObject
        // Key confirmation (docs/remote-control.md): remote_hello only after the client's remote_ready.
        if (!ready) {
            if (command.type != "remote_ready") {
                webSocket.close(4400, null)
                return
            }
            ready = true
            sendJson(webSocket, """{"type":"remote_hello","protocol":1,"host":{"name":"testhost","version":"1.1.1","cwd":"/w"},"state":{"thinkingLevel":"low","isStreaming":false},"messages":[],"dialogs":[],"commands":[],"big":"${"x".repeat(100_000)}"}""")
            return
        }
        commands += command
        val id = command.str("id")
        when (command.type) {
            "prompt" -> {
                sendJson(webSocket, """{"id":"$id","type":"response","command":"prompt","success":true,"data":{"disposition":"started"}}""")
                sendJson(webSocket, """{"type":"agent_start"}""")
                sendJson(webSocket, """{"type":"agent_settled","aborted":false}""")
            }
            // The background service hands the port to a terminal's /remote-control.
            "test_handoff" -> webSocket.close(1012, null)
            else -> sendJson(webSocket, """{"id":"$id","type":"response","command":"${command.type}","success":false,"error":"Not available over remote control"}""")
        }
    }

    private fun sendJson(webSocket: WebSocket, json: String) {
        synchronized(this) {
            for (frame in Framing.seal(send!!, json.toByteArray())) webSocket.send(frame.toByteString())
        }
    }
}

class RemoteBackendTest {
    private val server = MockWebServer()
    private val hostKey = KeyPair.generate()
    private val secret = ByteArray(32) { 7 }
    private val paired = mutableSetOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun endpoint(): String {
        if (!server.started) server.start()
        return server.url("/rc").toString().replaceFirst("http", "ws")
    }

    private fun config(identity: KeyPair, pair: ByteArray?, endpoints: List<String>) =
        RemoteConfig(hostKey.publicKey, endpoints, identity, "Pixel Test", "0.1.0", pair, lanTimeoutMs = 3_000)

    @Test
    fun pairsThenReconnectsWithoutSecretAndRunsCommands(): Unit = runBlocking {
        // One listener per connection, as a host keeps one handshake state per socket.
        val host = TestHost(hostKey, secret, paired)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(TestHost(hostKey, secret, paired, host.hellos, host.commands)).build())
        val identity = KeyPair.generate()
        var pairedCalls = 0
        // The first endpoint refuses connections: the client moves on to the next one.
        val backend = RemoteBackend(config(identity, secret, listOf("ws://127.0.0.1:1/rc", endpoint())), onPaired = { pairedCalls++ })
        val rpc = RpcClient(backend, scope)
        val events = rpc.events

        assertTrue(backend.connect())
        val connected = assertIs<ConnectionState.Connected>(backend.state.value)
        assertEquals("testhost", connected.host.name)
        assertTrue(connected.host.paired)
        assertEquals(1, pairedCalls)
        assertNull(backend.config.pairSecret, "the single-use secret is dropped after pairing")
        assertEquals("Pixel Test", host.hellos[0].str("device"))
        assertEquals(Base64Url.encode(secret), host.hellos[0].str("pair"))

        val hello = withTimeout(5_000) { events.first() }
        assertEquals("remote_hello", hello.type)
        assertEquals(100_000, hello.str("big")!!.length)

        val response = rpc.prompt("hi", streamingBehavior = StreamingBehavior.FollowUp)
        assertTrue(response.success)
        assertEquals("started", response.dataObject?.str("disposition"))
        assertEquals("followUp", host.commands.last().str("streamingBehavior"))
        val after = withTimeout(5_000) { events.take(2).toList() }
        assertEquals(listOf("agent_start", "agent_settled"), after.map { it.type })

        val refused = rpc.request("bash") { put("command", "ls") }
        assertFalse(refused.success)
        assertEquals("Not available over remote control", refused.error)

        backend.close()
        assertEquals(DisconnectReason.Closed, (backend.state.value as ConnectionState.Disconnected).reason)

        // Reconnect: no secret this time, still accepted.
        assertTrue(backend.connect())
        assertNull(host.hellos[1].str("pair"))
        assertFalse((backend.state.value as ConnectionState.Connected).host.paired)
        backend.close()
        rpc.stop()
    }

    @Test
    fun unknownDeviceIsRefusedWith4403(): Unit = runBlocking {
        val host = TestHost(hostKey, secret, paired)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        val backend = RemoteBackend(config(KeyPair.generate(), ByteArray(32), listOf(endpoint(), "ws://127.0.0.1:1/rc")))
        assertFalse(backend.connect())
        val state = assertIs<ConnectionState.Disconnected>(backend.state.value)
        assertEquals(DisconnectReason.Unauthorized, state.reason)
        assertEquals(1, server.requestCount, "a refusal is final: the other endpoints are not tried")
    }

    @Test
    fun wrongHostKeyNeverConnects(): Unit = runBlocking {
        val host = TestHost(KeyPair.generate(), secret, paired)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        val backend = RemoteBackend(config(KeyPair.generate(), secret, listOf(endpoint())).copy(lanTimeoutMs = 1_500))
        assertFalse(backend.connect())
        assertIs<ConnectionState.Disconnected>(backend.state.value)
    }

    /** A host that accepts the WebSocket and never answers the handshake. */
    private class SilentHost : WebSocketListener() {
        val closed = CompletableDeferred<Unit>()

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(Unit)
        }
    }

    @Test
    fun cancellingAPendingConnectClosesItsSocket(): Unit = runBlocking {
        val host = SilentHost()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        val backend = RemoteBackend(config(KeyPair.generate(), secret, listOf(endpoint())).copy(lanTimeoutMs = 60_000))
        val job = scope.launch { backend.connect() }
        delay(500)
        job.cancelAndJoin()
        withTimeout(5_000) { host.closed.await() }
        assertNull(backend.lastEndpoint)
    }

    @Test
    fun closeWhileConnectingAbandonsTheHandshake(): Unit = runBlocking {
        val host = SilentHost()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        val backend = RemoteBackend(config(KeyPair.generate(), secret, listOf(endpoint())).copy(lanTimeoutMs = 60_000))
        val connecting = scope.async { backend.connect() }
        delay(500)
        backend.close()
        assertFalse(withTimeout(5_000) { connecting.await() })
        withTimeout(5_000) { host.closed.await() }
        assertEquals(DisconnectReason.Closed, (backend.state.value as ConnectionState.Disconnected).reason)
    }

    @Test
    fun anOversizedFrameBeforeTheHandshakeIsRefused(): Unit = runBlocking {
        val flooder = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.send(ByteArray(200_000).toByteString())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(flooder).build())
        val backend = RemoteBackend(config(KeyPair.generate(), secret, listOf(endpoint())))
        assertFalse(backend.connect())
        val state = assertIs<ConnectionState.Disconnected>(backend.state.value)
        assertEquals(DisconnectReason.Malformed, state.reason)
    }

    @Test
    fun closeCode1012EndsTheConnectionAsAHandoff(): Unit = runBlocking {
        val host = TestHost(hostKey, secret, paired)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        val backend = RemoteBackend(config(KeyPair.generate(), secret, listOf(endpoint())))
        assertTrue(backend.connect())
        assertTrue(backend.send(buildJsonObject { put("type", "test_handoff"); put("id", "h1") }))
        val end = withTimeout(5_000) { backend.state.first { it is ConnectionState.Disconnected } } as ConnectionState.Disconnected
        assertEquals(DisconnectReason.Handoff, end.reason)
        assertTrue(end.reason.retryable, "the app reconnects at once, to the same addresses")
        backend.close()
    }

    @Test
    fun subprotocolHeaderIsSent(): Unit = runBlocking {
        val host = TestHost(hostKey, secret, paired)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(host).build())
        val backend = RemoteBackend(config(KeyPair.generate(), secret, listOf(endpoint())))
        assertTrue(backend.connect())
        assertEquals("pibolt-rc.1", server.takeRequest().headers["Sec-WebSocket-Protocol"])
        assertContentEquals(hostKey.publicKey, backend.config.hostKey)
        backend.close()
    }

}
