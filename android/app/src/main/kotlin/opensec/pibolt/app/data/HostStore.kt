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
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.str
import java.io.File

/** A computer this phone paired with. [id] is the host's public key (base64url). */
data class Host(
    val id: String,
    val name: String,
    val endpoints: List<String>,
    val version: String = "",
    val cwd: String = "",
    val lastConnectedAt: Long = 0,
    val pairedAt: Long = 0,
) {
    val hostKey: ByteArray get() = Base64Url.decode(id)

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
            )
        }
    }
}

/** Paired computers, in a small JSON file, most recently used first. */
class HostStore(context: Context) {
    private val file = File(context.filesDir, "hosts.json")
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()

    fun get(id: String): Host? = _hosts.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(host: Host) {
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
        save(_hosts.value.filterNot { it.id == id })
    }

    private fun save(hosts: List<Host>) {
        _hosts.value = hosts
        val text = JsonArray(hosts.map { it.toJson() }).toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun load(): List<Host> = runCatching {
        if (!file.exists()) return emptyList()
        (ProtocolJson.parseToJsonElement(file.readText()) as JsonArray).mapNotNull { (it as? JsonObject)?.let(Host::fromJson) }
    }.getOrDefault(emptyList())
}
