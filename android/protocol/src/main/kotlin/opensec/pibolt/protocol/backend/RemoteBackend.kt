package opensec.pibolt.protocol.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.NOISE_PROLOGUE
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.WS_SUBPROTOCOL
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.noise.CipherState
import opensec.pibolt.protocol.noise.IkHandshake
import opensec.pibolt.protocol.noise.KeyPair
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.transport.Framing
import opensec.pibolt.protocol.transport.Reassembler
import java.util.concurrent.TimeUnit

data class RemoteConfig(
    /** Host static X25519 public key from the pairing code. */
    val hostKey: ByteArray,
    /** WebSocket endpoints in preference order: LAN first, then the tunnel. */
    val endpoints: List<String>,
    /** This install's static key pair. */
    val identity: KeyPair,
    val deviceName: String,
    val appVersion: String,
    /** The pairing secret, only while this phone is not yet paired with the host. */
    val pairSecret: ByteArray? = null,
    /** Connect plus handshake budget for a LAN (`ws://`) endpoint. */
    val lanTimeoutMs: Long = 3_000,
    /** Connect plus handshake budget for a tunnel (`wss://`) endpoint. */
    val tunnelTimeoutMs: Long = 15_000,
) {
    fun timeoutFor(endpoint: String): Long = if (endpoint.startsWith("wss://")) tunnelTimeoutMs else lanTimeoutMs

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * A session on a computer, over remote control: WebSocket + Noise IK + chunked transport (docs/remote-control.md).
 *
 * [onPaired] runs once the host confirms that this connection paired the phone, so the caller can store the host and
 * stop sending the (single use) secret.
 */
class RemoteBackend(
    config: RemoteConfig,
    private val client: OkHttpClient = defaultClient(),
    private val onPaired: (HostHello) -> Unit = {},
) : SessionBackend {
    @Volatile
    var config: RemoteConfig = config
        private set

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val inbox = Channel<JsonObject>(Channel.UNLIMITED)
    override val incoming: Flow<JsonObject> = inbox.receiveAsFlow()

    private val connectMutex = Mutex()
    private val sendLock = Any()

    @Volatile
    private var live: Connection? = null

    /** The connection whose handshake is in progress; [close] abandons it. */
    @Volatile
    private var pending: Connection? = null

    /** Bumped by [close], so a connect that was under way when the backend closed gives up. */
    @Volatile
    private var generation = 0

    @Volatile
    private var attempt = 0

    /** The endpoint of the current or last connection. */
    @Volatile
    var lastEndpoint: String? = null
        private set

    override suspend fun connect(): Boolean = connectMutex.withLock {
        if (live?.isOpen == true) return true
        val cfg = config
        val startGeneration = generation
        attempt++
        var lastFailure: ConnectionState.Disconnected = ConnectionState.Disconnected(DisconnectReason.Unreachable, "No address to try")
        for (endpoint in cfg.endpoints) {
            _state.value = ConnectionState.Connecting(endpoint, attempt)
            // A stored or pasted address OkHttp cannot parse is unreachable, not a crash.
            val connection = try {
                Connection(endpoint, cfg)
            } catch (e: IllegalArgumentException) {
                lastFailure = ConnectionState.Disconnected(DisconnectReason.Unreachable, "$endpoint is not a valid address")
                continue
            }
            pending = connection
            // A socket that does not become the live connection is closed on every path: timeout, refusal,
            // close() while connecting, and cancellation of the caller (which skips the timeout branch).
            var adopted = false
            try {
                val result = withTimeoutOrNull(cfg.timeoutFor(endpoint)) { connection.handshake.await() }
                if (generation != startGeneration) return false
                when (result) {
                    null -> lastFailure = ConnectionState.Disconnected(DisconnectReason.Unreachable, "$endpoint did not answer")
                    is AttemptResult.Ok -> {
                        if (!connection.isOpen) {
                            lastFailure = ConnectionState.Disconnected(DisconnectReason.Lost, "$endpoint closed right after the handshake")
                            continue
                        }
                        live = connection
                        adopted = true
                        lastEndpoint = endpoint
                        if (cfg.pairSecret != null) config = cfg.copy(pairSecret = null)
                        if (result.hello.paired) onPaired(result.hello)
                        _state.value = ConnectionState.Connected(endpoint, result.hello)
                        return true
                    }
                    is AttemptResult.Failed -> {
                        lastFailure = ConnectionState.Disconnected(result.reason, result.detail)
                        // Every endpoint reaches the same host: an authorization or protocol refusal will not change.
                        if (!result.reason.retryable) break
                    }
                }
            } finally {
                if (pending === connection) pending = null
                if (!adopted) connection.abandon()
            }
        }
        _state.value = lastFailure
        false
    }

    override fun send(message: JsonObject): Boolean {
        val connection = live ?: return false
        return connection.send(message.toString().toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        generation++
        pending?.abandon()
        pending = null
        val connection = live
        live = null
        connection?.closeByClient()
        _state.value = ConnectionState.Disconnected(DisconnectReason.Closed)
    }

    /** Replace the endpoints, for example after a new pairing code from the same host shows a new tunnel address. */
    fun updateEndpoints(endpoints: List<String>) {
        config = config.copy(endpoints = endpoints)
    }

    private sealed interface AttemptResult {
        data class Ok(val hello: HostHello) : AttemptResult

        data class Failed(val reason: DisconnectReason, val detail: String?) : AttemptResult
    }

    private inner class Connection(val endpoint: String, cfg: RemoteConfig) : WebSocketListener() {
        val handshake = CompletableDeferred<AttemptResult>()
        private val noise = IkHandshake.initiator(cfg.identity, cfg.hostKey, NOISE_PROLOGUE)
        private val clientHello = buildJsonObject {
            put("device", cfg.deviceName)
            put("app", cfg.appVersion)
            cfg.pairSecret?.let { put("pair", Base64Url.encode(it)) }
        }
        private var sendCipher: CipherState? = null
        private var receiveCipher: CipherState? = null
        private val reassembler = Reassembler()

        @Volatile
        private var closedByClient = false

        @Volatile
        private var ended = false
        private val socket: WebSocket = client.newWebSocket(
            Request.Builder().url(endpoint).header("Sec-WebSocket-Protocol", WS_SUBPROTOCOL).build(),
            this,
        )

        val isOpen: Boolean get() = !ended && sendCipher != null

        fun send(data: ByteArray): Boolean {
            val cipher = sendCipher ?: return false
            if (ended) return false
            // Encrypt and enqueue under one lock so frame order matches nonce order.
            synchronized(sendLock) {
                for (frame in Framing.seal(cipher, data)) {
                    if (!socket.send(frame.toByteString())) return false
                }
            }
            return true
        }

        fun closeByClient() {
            closedByClient = true
            ended = true
            socket.close(1000, null)
        }

        fun abandon() {
            closedByClient = true
            ended = true
            socket.cancel()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            try {
                val message1 = noise.writeMessage1(clientHello.toString().toByteArray(Charsets.UTF_8))
                webSocket.send(message1.toByteString())
            } catch (e: Exception) {
                fail(webSocket, 4400, DisconnectReason.Malformed, e.message)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Size checks before any work: handshake message 2 is a key, a tag and a small JSON payload; a transport
            // frame is at most one Noise message (65535 bytes). An endpoint that is not the host cannot make us buffer more.
            val limit = if (receiveCipher == null) MAX_HANDSHAKE_REPLY else MAX_NOISE_MESSAGE
            if (bytes.size > limit) {
                fail(webSocket, 4400, DisconnectReason.Malformed, "Frame too large")
                return
            }
            val data = bytes.toByteArray()
            if (receiveCipher == null) {
                val hello = try {
                    val payload = noise.readMessage2(data)
                    val ciphers = noise.split()
                    val json = ProtocolJson.parseToJsonElement(String(payload, Charsets.UTF_8)) as? JsonObject ?: JsonObject(emptyMap())
                    sendCipher = ciphers.send
                    receiveCipher = ciphers.receive
                    HostHello(
                        name = json.str("host") ?: "Computer",
                        version = json.str("version") ?: "",
                        paired = json.bool("paired") ?: false,
                    )
                } catch (e: Exception) {
                    fail(webSocket, 4400, DisconnectReason.Malformed, "Bad handshake reply: ${e.message}")
                    return
                }
                // Key confirmation: the host sends remote_hello and accepts commands only after this first transport
                // message, so a replayed handshake message 1 gets nothing.
                if (!send(READY_MESSAGE)) {
                    fail(webSocket, 4400, DisconnectReason.Lost, "Could not confirm the handshake")
                    return
                }
                handshake.complete(AttemptResult.Ok(hello))
                return
            }
            try {
                val plaintext = receiveCipher!!.decrypt(data)
                val message = reassembler.accept(plaintext) ?: return
                val json = ProtocolJson.parseToJsonElement(String(message, Charsets.UTF_8))
                if (json is JsonObject) inbox.trySend(json)
            } catch (e: Exception) {
                fail(webSocket, 4400, DisconnectReason.Malformed, "Bad message: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            fail(webSocket, 4400, DisconnectReason.Malformed, "Text frame")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code.coerceIn(1000, 4999).takeIf { it != 1005 && it != 1006 } ?: 1000, null)
            ended(DisconnectReason.fromCloseCode(code), reason.ifBlank { null })
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended(DisconnectReason.fromCloseCode(code), reason.ifBlank { null })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = if (handshake.isCompleted) DisconnectReason.Lost else DisconnectReason.Unreachable
            ended(reason, t.message ?: t.javaClass.simpleName)
        }

        private fun fail(webSocket: WebSocket, code: Int, reason: DisconnectReason, detail: String?) {
            webSocket.close(code, null)
            ended(reason, detail)
        }

        private fun ended(reason: DisconnectReason, detail: String?) {
            if (!handshake.isCompleted) {
                ended = true
                handshake.complete(AttemptResult.Failed(reason, detail))
                return
            }
            if (ended) return
            ended = true
            if (live === this) {
                live = null
                if (!closedByClient) _state.value = ConnectionState.Disconnected(reason, detail)
            }
        }
    }

    companion object {
        private const val MAX_HANDSHAKE_REPLY = 4096
        private const val MAX_NOISE_MESSAGE = 65535
        private val READY_MESSAGE = """{"type":"remote_ready"}""".toByteArray(Charsets.UTF_8)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
