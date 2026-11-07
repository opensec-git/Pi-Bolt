package opensec.pibolt.app.secrets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** One project secret. [toString] masks the value so it never lands in a log or a crash report by accident. */
data class Secret(val name: String, val value: String) {
    override fun toString(): String = "Secret($name=${SecretEnv.MASK})"
}

/**
 * Per-project secrets (.env style), sealed with [SecretBox] in the app's private storage, outside the project folder
 * (never committed, never seen by the agent's file tools). One file per project, named by a hash of its path.
 * A project without secrets has no file, so reading its environment touches neither the disk nor the keystore.
 * Thread-safe; blocking file and keystore I/O: call off the main thread.
 */
class ProjectSecrets(private val dir: File, private val box: SecretBox) {
    @Synchronized
    fun read(project: File): List<Secret> {
        val file = fileFor(project)
        if (!file.isFile) return emptyList()
        val plain = box.open(file.readBytes(), keyOf(project).toByteArray())
        val json = Json.parseToJsonElement(plain.decodeToString()) as JsonObject
        return json.map { (name, value) -> Secret(name, value.jsonPrimitive.content) }
    }

    /** Replaces the project's secrets; an empty list deletes its file. */
    @Synchronized
    fun write(project: File, secrets: List<Secret>) {
        val file = fileFor(project)
        if (secrets.isEmpty()) {
            file.delete()
            return
        }
        for (s in secrets) require(SecretEnv.nameProblem(s.name) == null) { "invalid secret name ${s.name}" }
        val json = JsonObject(secrets.associate { it.name to JsonPrimitive(it.value) })
        val sealed = box.seal(json.toString().encodeToByteArray(), keyOf(project).toByteArray())
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(sealed)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Adds [name] or replaces its value, keeping the order of the others. */
    @Synchronized
    fun put(project: File, name: String, value: String) {
        val current = read(project)
        val next = if (current.any { it.name == name }) current.map { if (it.name == name) Secret(name, value) else it } else current + Secret(name, value)
        write(project, next)
    }

    @Synchronized
    fun remove(project: File, name: String) = write(project, read(project).filterNot { it.name == name })

    /** The project was deleted: its secrets go too, so a new project at the same path starts without them. */
    fun forget(project: File) = write(project, emptyList())

    fun hasAny(project: File): Boolean = fileFor(project).isFile

    /** The variables to add to a process started for [project]; empty when it has none or its file cannot be opened. */
    fun environment(project: File): Map<String, String> =
        if (!hasAny(project)) emptyMap() else runCatching { read(project) }.getOrDefault(emptyList()).associate { it.name to it.value }

    /**
     * Whether an engine started in [from] may serve a session in [to]: its environment holds [from]'s secrets from
     * its start, so only when it is the same project or neither project has any.
     */
    fun engineFits(from: String?, to: File): Boolean = from == null || keyOf(File(from)) == keyOf(to) || (!hasAny(File(from)) && !hasAny(to))

    private fun fileFor(project: File) = File(dir, sha256(keyOf(project)) + ".bin")

    companion object {
        /**
         * The project's identity: its absolute path, with the per-user data path (`/data/user/0/<id>`) written as the
         * engine writes it (`/data/data/<id>`), so the UI and the engine name the same project the same way.
         */
        fun keyOf(project: File): String {
            val path = project.absoluteFile.normalize().path.trimEnd('/')
            return if (path.startsWith(USER0)) DATA + path.removePrefix(USER0) else path
        }

        // The two spellings of user 0's data directory, as in EnginePaths.
        @Suppress("SdCardPath")
        private const val USER0 = "/data/user/0/"

        @Suppress("SdCardPath")
        private const val DATA = "/data/data/"

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
