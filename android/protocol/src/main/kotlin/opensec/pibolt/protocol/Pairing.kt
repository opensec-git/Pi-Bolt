package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.util.Base64

const val PROTOCOL_VERSION = 1
const val PAIR_PREFIX = "pibolt://pair#"
const val WS_SUBPROTOCOL = "pibolt-rc.1"
val NOISE_PROLOGUE: ByteArray = "pibolt-rc/1".toByteArray(Charsets.US_ASCII)

object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Accepts padded or unpadded base64url. */
    fun decode(text: String): ByteArray = decoder.decode(text.trim().trimEnd('='))
}

class PairingParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The pairing QR payload: `pibolt://pair#` + base64url(JSON). */
data class PairingPayload(
    val version: Int,
    val hostName: String,
    val hostKey: ByteArray,
    val secret: ByteArray,
    val endpoints: List<String>,
    /** Expiry of [secret], Unix seconds. */
    val expiresAt: Long,
) {
    val hostKeyId: String get() = Base64Url.encode(hostKey)

    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = nowSeconds >= expiresAt

    fun encode(): String {
        val json = buildJsonObject {
            put("v", version)
            put("n", hostName)
            put("k", Base64Url.encode(hostKey))
            put("s", Base64Url.encode(secret))
            putJsonArray("e") { endpoints.forEach { add(JsonPrimitive(it)) } }
            put("x", expiresAt)
        }
        return PAIR_PREFIX + Base64Url.encode(json.toString().toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean =
        other is PairingPayload && version == other.version && hostName == other.hostName &&
            hostKey.contentEquals(other.hostKey) && secret.contentEquals(other.secret) &&
            endpoints == other.endpoints && expiresAt == other.expiresAt

    override fun hashCode(): Int = hostKey.contentHashCode() * 31 + secret.contentHashCode()

    override fun toString(): String = "PairingPayload(v=$version, n=$hostName, k=$hostKeyId, e=$endpoints, x=$expiresAt)"

    companion object {
        /** True when [text] looks like a pairing link (from a QR code, a deep link or pasted text). */
        fun looksLikePairing(text: String): Boolean = text.trim().startsWith(PAIR_PREFIX, ignoreCase = true)

        /** A real pairing link is a few hundred characters; anything longer is refused before decoding. */
        const val MAX_LINK_CHARS = 4096

        /** Endpoints kept from a pairing code: LAN addresses plus a tunnel fit comfortably. */
        const val MAX_ENDPOINTS = 8
        private const val MAX_ENDPOINT_CHARS = 256
        private const val MAX_HOST_NAME_CHARS = 80

        /**
         * A usable endpoint: `ws://` or `wss://` with a host and no user info, at most [MAX_ENDPOINT_CHARS] characters.
         * Anything else in a pairing code (which any app or web page can hand us through a deep link) is dropped.
         */
        fun isValidEndpoint(endpoint: String): Boolean {
            if (endpoint.length > MAX_ENDPOINT_CHARS) return false
            val uri = try {
                URI(endpoint)
            } catch (_: Exception) {
                return false
            }
            return (uri.scheme == "ws" || uri.scheme == "wss") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }

        fun parse(text: String): PairingPayload {
            if (text.length > MAX_LINK_CHARS) throw PairingParseException("Pairing code is too long")
            val trimmed = text.trim()
            if (!trimmed.startsWith(PAIR_PREFIX, ignoreCase = true)) throw PairingParseException("Not a Pi-Bolt pairing code")
            val encoded = trimmed.substring(PAIR_PREFIX.length)
            val json = try {
                ProtocolJson.parseToJsonElement(String(Base64Url.decode(encoded), Charsets.UTF_8)) as? JsonObject
            } catch (e: Exception) {
                throw PairingParseException("Pairing code is damaged", e)
            } ?: throw PairingParseException("Pairing code is damaged")
            val version = json.int("v") ?: throw PairingParseException("Pairing code has no version")
            if (version != PROTOCOL_VERSION) throw PairingParseException("Pairing code version $version is not supported; update the app")
            val key = json.str("k")?.let { decodeKey(it, "host key") } ?: throw PairingParseException("Pairing code has no host key")
            val secret = json.str("s")?.let { decodeKey(it, "secret") } ?: throw PairingParseException("Pairing code has no secret")
            val endpoints = (json["e"] as? JsonArray)?.asSequence()
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.filter(::isValidEndpoint)
                ?.distinct()
                ?.take(MAX_ENDPOINTS)
                ?.toList()
                .orEmpty()
            if (endpoints.isEmpty()) throw PairingParseException("Pairing code has no address")
            return PairingPayload(
                version = version,
                // Shown in the app: no control characters, bounded length.
                hostName = json.str("n")?.filterNot { it.isISOControl() }?.trim()?.take(MAX_HOST_NAME_CHARS)
                    ?.takeIf { it.isNotBlank() } ?: "Computer",
                hostKey = key,
                secret = secret,
                endpoints = endpoints,
                expiresAt = json.long("x") ?: 0,
            )
        }

        private fun decodeKey(text: String, what: String): ByteArray {
            val bytes = try {
                Base64Url.decode(text)
            } catch (e: IllegalArgumentException) {
                throw PairingParseException("Pairing code $what is not base64url", e)
            }
            if (bytes.size != 32) throw PairingParseException("Pairing code $what must be 32 bytes")
            return bytes
        }
    }
}

/** `remote_endpoints`: the host's current addresses, sent after `remote_hello` and whenever they change. */
object RemoteEndpoints {
    const val TYPE = "remote_endpoints"

    /**
     * The endpoints to use from now on, or null when [message] is malformed or names no usable address. Keeps the
     * entries that pass [PairingPayload.isValidEndpoint], once each, at most [PairingPayload.MAX_ENDPOINTS]. The
     * endpoint this connection uses ([connected]) is kept too (last), so a list that leaves it out cannot strand the
     * phone.
     */
    fun from(message: JsonObject, connected: String?): List<String>? {
        val list = (message["endpoints"] as? JsonArray) ?: return null
        val valid = list.asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter(PairingPayload::isValidEndpoint)
            .distinct()
            .take(PairingPayload.MAX_ENDPOINTS)
            .toList()
        val keep = connected?.takeIf { it !in valid && PairingPayload.isValidEndpoint(it) }
        val endpoints = if (keep == null) valid else valid.take(PairingPayload.MAX_ENDPOINTS - 1) + keep
        return endpoints.ifEmpty { null }
    }
}
