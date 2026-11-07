package opensec.pibolt.protocol.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Where a Pi session lives. The UI depends only on this and the RPC layer, so a session on a computer (over remote
 * control, [RemoteBackend]) and one running on the phone itself (stdio RPC, [LocalBackend]) look the same.
 */
interface SessionBackend {
    val state: StateFlow<ConnectionState>

    /**
     * Every JSON object from the session (events, responses, extension UI requests), in order. Backed by an unbounded
     * channel: collect it from exactly one place (the [opensec.pibolt.protocol.rpc.RpcClient]).
     */
    val incoming: Flow<JsonObject>

    /** Connect, or reconnect after a drop. Returns true when connected. Safe to call again after [close]. */
    suspend fun connect(): Boolean

    /** Queue one JSON object for the session. False when not connected. */
    fun send(message: JsonObject): Boolean

    /** Disconnect on purpose. */
    fun close()
}

data class HostHello(val name: String, val version: String, val paired: Boolean)

enum class DisconnectReason {
    /** The client closed the connection. */
    Closed,

    /** No endpoint answered. */
    Unreachable,

    /** The connection dropped after the handshake (network change, sleep, host crash). */
    Lost,

    /** 1001: Pi-Bolt stopped remote control. */
    HostStopped,

    /** 1012: the background service handed the port to the computer's terminal (`/remote-control`). Reconnect at once. */
    Handoff,

    /** 4400: the host could not read the handshake or a message. */
    Malformed,

    /** 4403: this phone is not paired (revoked, or the pairing code was expired or used). */
    Unauthorized,

    /** 4408: the handshake took too long. */
    HandshakeTimeout,

    /** 4429: too many failed handshakes from this address. */
    RateLimited,

    /** The on-device engine could not start, did not answer, or keeps stopping. The detail says why. */
    EngineFailed,
    ;

    /** Worth retrying automatically. */
    val retryable: Boolean get() = this == Unreachable || this == Lost || this == HandshakeTimeout || this == Handoff

    companion object {
        fun fromCloseCode(code: Int): DisconnectReason = when (code) {
            1000 -> Closed
            1001 -> HostStopped
            1012 -> Handoff
            4400 -> Malformed
            4403 -> Unauthorized
            4408 -> HandshakeTimeout
            4429 -> RateLimited
            else -> Lost
        }
    }
}

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val endpoint: String, val attempt: Int) : ConnectionState

    data class Connected(val endpoint: String, val host: HostHello) : ConnectionState

    data class Disconnected(val reason: DisconnectReason, val detail: String? = null) : ConnectionState
}
