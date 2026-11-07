package opensec.pibolt.app.update

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str

/** What update.json keeps between launches. */
data class UpdateFile(
    val lastCheckAt: Long = 0,
    /** The newest release seen by the last successful check (may be the installed version). */
    val latest: UpdateInfo? = null,
    /** Later was chosen for this version: Home does not offer it again. */
    val snoozedVersion: String? = null,
    /** The version that last ran, to say "Updated to X" once after an update. */
    val lastRunVersion: String? = null,
)

/** JSON form of [UpdateFile]. Damaged content falls back to defaults; [UpdateInfo] is validated again on load. */
object UpdateFileCodec {
    fun encode(f: UpdateFile): String = buildJsonObject {
        put("version", 1)
        put("lastCheckAt", f.lastCheckAt)
        f.snoozedVersion?.let { put("snoozedVersion", it) }
        f.lastRunVersion?.let { put("lastRunVersion", it) }
        f.latest?.let { i ->
            put(
                "latest",
                buildJsonObject {
                    put("version", i.version)
                    put("apk", i.apk)
                    i.page?.let { put("page", it) }
                    put("size", i.size)
                    put("sha256", i.sha256)
                    put("certSha256", i.certSha256)
                },
            )
        }
    }.toString()

    fun decode(text: String?, source: UpdateSource): UpdateFile {
        val json = text?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return UpdateFile()
        return UpdateFile(
            lastCheckAt = json.long("lastCheckAt") ?: 0,
            latest = json.obj("latest")?.let { UpdateRules.parseManifest(it.toString(), source).getOrNull() },
            snoozedVersion = json.str("snoozedVersion")?.let(AppVersion::normalize),
            lastRunVersion = json.str("lastRunVersion")?.let(AppVersion::normalize),
        )
    }
}
