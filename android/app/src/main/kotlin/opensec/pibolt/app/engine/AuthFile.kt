package opensec.pibolt.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Pi's auth.json in the agent dir, read and written the way Pi does (packages/coding-agent/src/core/auth-storage.ts):
 * an object of provider id to credential, `{ "type": "api_key", "key": "..." }` for keys, written as
 * `JSON.stringify(data, null, 2)` with mode 0600, under Pi's lock (proper-lockfile: a directory `auth.json.lock`,
 * stale after 10 s). Other providers' entries, OAuth ones included, are kept as they are.
 *
 * Reads and writes run on [scope] (an I/O dispatcher): nothing here touches the disk on the caller's thread.
 */
class AuthFile(private val agentDir: File, private val scope: CoroutineScope) : ProviderAuth {
    private val file = File(agentDir, "auth.json")
    private val lockDir = File(agentDir, "auth.json.lock")
    private val mutex = Mutex()
    private val _configured = MutableStateFlow<Set<String>>(emptySet())
    override val configured: StateFlow<Set<String>> = _configured.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Re-read the file (Pi may have changed it). */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                _configured.value = read().keys
                _loaded.value = true
            }
        }
    }

    override fun setApiKey(provider: String, key: String) {
        val trimmed = key.trim()
        require(provider.isNotBlank()) { "provider" }
        if (trimmed.isEmpty()) return remove(provider)
        _configured.value = _configured.value + provider
        scope.launch {
            mutex.withLock {
                val next = modify { it + (provider to buildJsonObject { put("type", "api_key"); put("key", trimmed) }) }
                // Not written (lock busy, file unreadable): show what is really on disk, so the key does not look saved.
                _configured.value = next?.keys ?: read().keys
            }
        }
    }

    override fun remove(provider: String) {
        _configured.value = _configured.value - provider
        scope.launch {
            mutex.withLock {
                val next = modify { it - provider }
                _configured.value = next?.keys ?: read().keys
            }
        }
    }

    /** Current contents; an absent or unreadable file is empty. Blocking. */
    fun read(): Map<String, JsonElement> = runCatching {
        if (!file.isFile) return emptyMap()
        ProtocolJson.parseToJsonElement(file.readText().removePrefix(BOM)) as? JsonObject ?: emptyMap()
    }.getOrDefault(emptyMap())

    /** Apply [change] under Pi's lock. Returns the written contents, or null when the file could not be read or written. */
    fun modify(change: (Map<String, JsonElement>) -> Map<String, JsonElement>): Map<String, JsonElement>? {
        if (!agentDir.isDirectory) {
            agentDir.mkdirs()
            setMode(agentDir, "rwx------")
        }
        if (!lock()) return null
        try {
            val current: Map<String, JsonElement> = if (file.isFile) {
                // A file Pi cannot parse is left alone rather than replaced.
                ProtocolJson.parseToJsonElement(file.readText().removePrefix(BOM)) as? JsonObject ?: return null
            } else {
                emptyMap()
            }
            val next = change(current)
            val temp = File(agentDir, "auth.json.${System.nanoTime()}.tmp")
            temp.writeText(PrettyJson.encodeToString(JsonObject.serializer(), JsonObject(next)))
            setMode(temp, "rw-------")
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return next
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } finally {
            lockDir.delete()
        }
    }

    private fun lock(): Boolean {
        repeat(LOCK_ATTEMPTS) {
            if (lockDir.mkdir()) return true
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.exists() && age > STALE_MS) lockDir.delete() else Thread.sleep(LOCK_RETRY_MS)
        }
        return false
    }

    private fun setMode(target: File, mode: String) {
        runCatching { Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(mode)) }
    }

    private companion object {
        const val LOCK_ATTEMPTS = 50
        const val LOCK_RETRY_MS = 20L
        const val STALE_MS = 10_000L

        /** Pi strips a byte-order mark before parsing; so does this. */
        val BOM = Char(0xFEFF).toString()

        /** Same text as `JSON.stringify(value, null, 2)`. */
        val PrettyJson = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
