package opensec.pibolt.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.str

/** A computer this phone paired with. [id] is the host's public key (base64url). */
data class Host(
    val id: String,
    val name: String,
    val endpoints: List<String>,
    val version: String = "",
    val cwd: String = "",
    val lastConnectedAt: Long = 0,
    val pairedAt: Long = 0,
    /** The name given on this phone (Rename on Home); blank uses the computer's own [name]. Connecting keeps it. */
    val alias: String = "",
    /** The last connection reached the computer's background service (`host.service`), not a terminal's `/remote-control`. */
    val service: Boolean = false,
) {
    val hostKey: ByteArray get() = Base64Url.decode(id)

    /** What Home shows: the name given here, else the computer's. */
    val displayName: String get() = alias.trim().ifEmpty { name }

    /** How this phone reaches it, from its endpoints: "LAN", "Tailscale", "Tunnel", in that order, each once. */
    fun connections(): List<String> = Connections.of(endpoints)

    val hasLan: Boolean get() = endpoints.any { it.startsWith("ws://") }
    val hasTunnel: Boolean get() = endpoints.any { it.startsWith("wss://") }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("endpoints", JsonArray(endpoints.map(::JsonPrimitive)))
        put("version", version)
        put("cwd", cwd)
        put("lastConnectedAt", lastConnectedAt)
        put("pairedAt", pairedAt)
        if (alias.isNotBlank()) put("alias", alias)
        if (service) put("service", true)
    }

    companion object {
        fun fromJson(json: JsonObject): Host? {
            val id = json.str("id") ?: return null
            return Host(
                id = id,
                name = json.str("name") ?: "Computer",
                endpoints = json.arr("endpoints")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                version = json.str("version").orEmpty(),
                cwd = json.str("cwd").orEmpty(),
                lastConnectedAt = json.long("lastConnectedAt") ?: 0,
                pairedAt = json.long("pairedAt") ?: 0,
                alias = json.str("alias").orEmpty(),
                service = json.bool("service") == true,
            )
        }
    }
}

/**
 * Paired computers, in a small JSON file, most recently used first. The file is read on a background thread:
 * [hosts] is empty for the first few ms after launch; lookups and changes wait for that read.
 */
class HostStore(context: Context) {
    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()
    private val file = BackgroundFile(context, "hosts.json") { text -> _hosts.value = parse(text) }

    fun get(id: String): Host? {
        file.awaitLoaded()
        return _hosts.value.firstOrNull { it.id == id }
    }

    @Synchronized
    fun upsert(host: Host) {
        file.awaitLoaded()
        val others = _hosts.value.filterNot { it.id == host.id }
        save((listOf(host) + others).sortedByDescending { it.lastConnectedAt })
    }

    @Synchronized
    fun update(id: String, change: (Host) -> Host) {
        val current = get(id) ?: return
        upsert(change(current))
    }

    @Synchronized
    fun remove(id: String) {
        file.awaitLoaded()
        save(_hosts.value.filterNot { it.id == id })
    }

    private fun save(hosts: List<Host>) {
        _hosts.value = hosts
        file.write(JsonArray(hosts.map { it.toJson() }).toString())
    }

    private fun parse(text: String?): List<Host> = runCatching {
        text ?: return emptyList()
        (ProtocolJson.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(Host::fromJson) }
    }.getOrDefault(emptyList())
}

/** Which networks a computer's endpoints use. */
object Connections {
    /**
     * Tailscale: a host on `*.ts.net` or in 100.64.0.0/10 (Tailscale's CGNAT range); a tunnel: any other `wss://`; LAN:
     * any other `ws://`.
     */
    fun of(endpoints: List<String>): List<String> {
        val kinds = endpoints.map { kind(it) }.toSet()
        return listOf("LAN", "Tailscale", "Tunnel").filter { it in kinds }
    }

    fun kind(endpoint: String): String {
        val host = endpoint.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val cgnat = octets.size == 4 && octets[0] == 100 && octets[1] in 64..127
        return when {
            host.endsWith(".ts.net") || cgnat -> "Tailscale"
            endpoint.startsWith("wss://") -> "Tunnel"
            else -> "LAN"
        }
    }
}
