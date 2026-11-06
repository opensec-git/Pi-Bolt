package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

        fun parse(text: String): PairingPayload {
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
            val endpoints = (json["e"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.filter { it.startsWith("ws://") || it.startsWith("wss://") }
                .orEmpty()
            if (endpoints.isEmpty()) throw PairingParseException("Pairing code has no address")
            return PairingPayload(
                version = version,
                hostName = json.str("n")?.takeIf { it.isNotBlank() } ?: "Computer",
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
