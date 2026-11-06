package opensec.pibolt.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.str
import java.io.File
import java.util.concurrent.Executors

/** Dark themes. Claude, Codex, Vercel and GitHub are palettes in the spirit of those products' dark modes. */
enum class ThemeId(val label: String) {
    Graphite("Graphite"),
    Void("Void"),
    Ember("Ember"),
    Claude("Claude"),
    Codex("Codex"),
    Vercel("Vercel"),
    GitHub("GitHub"),
}

/** [Theme] uses the theme's own accent (Claude's coral, GitHub's blue, ink for the others). */
enum class AccentId(val label: String) { Theme("Theme"), Ink("Ink"), Iris("Iris"), Glacier("Glacier") }

enum class Density(val label: String) { Comfortable("Comfortable"), Compact("Compact") }

enum class ReduceMotion(val label: String) { FollowSystem("Follow system"), On("On") }

/** App preferences. Every field has a default, so an old or damaged file still loads. */
data class AppSettings(
    val theme: ThemeId = ThemeId.Graphite,
    val accent: AccentId = AccentId.Theme,
    /** Transcript text size in steps on top of the system font scale: -1 … +2. */
    val textSize: Int = 0,
    val density: Density = Density.Comfortable,
    val reduceMotion: ReduceMotion = ReduceMotion.FollowSystem,
    val haptics: Boolean = true,
    val thinkingExpanded: Boolean = false,
    /** Lines of tool output shown before "Show N more": one of [TOOL_PREVIEW_CHOICES]. */
    val toolPreviewLines: Int = 12,
    /** The notification permission was asked once; it is not asked again (Settings links to the system page). */
    val notificationsAsked: Boolean = false,
    /** Look for a new version of the app on launch, at most once a day. */
    val autoUpdateCheck: Boolean = true,
) {
    companion object {
        val TOOL_PREVIEW_CHOICES = listOf(6, 12, 24)
        const val TEXT_SIZE_MIN = -1
        const val TEXT_SIZE_MAX = 2
    }
}

/** JSON form of [AppSettings]. Unknown names fall back to defaults; numbers are clamped. */
object SettingsCodec {
    fun encode(s: AppSettings): String = buildJsonObject {
        put("version", 1)
        put("theme", s.theme.name)
        put("accent", s.accent.name)
        put("textSize", s.textSize)
        put("density", s.density.name)
        put("reduceMotion", s.reduceMotion.name)
        put("haptics", s.haptics)
        put("thinkingExpanded", s.thinkingExpanded)
        put("toolPreviewLines", s.toolPreviewLines)
        put("notificationsAsked", s.notificationsAsked)
        put("autoUpdateCheck", s.autoUpdateCheck)
    }.toString()

    fun decode(text: String?): AppSettings {
        val json = text?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return AppSettings()
        val d = AppSettings()
        return AppSettings(
            theme = enumOr(json.str("theme"), d.theme),
            accent = enumOr(json.str("accent"), d.accent),
            textSize = (json.int("textSize") ?: d.textSize).coerceIn(AppSettings.TEXT_SIZE_MIN, AppSettings.TEXT_SIZE_MAX),
            density = enumOr(json.str("density"), d.density),
            reduceMotion = enumOr(json.str("reduceMotion"), d.reduceMotion),
            haptics = json.bool("haptics") ?: d.haptics,
            thinkingExpanded = json.bool("thinkingExpanded") ?: d.thinkingExpanded,
            toolPreviewLines = json.int("toolPreviewLines")?.takeIf { it in AppSettings.TOOL_PREVIEW_CHOICES } ?: d.toolPreviewLines,
            notificationsAsked = json.bool("notificationsAsked") ?: d.notificationsAsked,
            autoUpdateCheck = json.bool("autoUpdateCheck") ?: d.autoUpdateCheck,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}

/**
 * Preferences in `filesDir/settings.json`. Read synchronously at construction, so the first frame already has the
 * chosen theme; writes go to a background thread (tmp file + rename).
 */
class SettingsStore(context: Context) {
    private val file = File(context.filesDir, "settings.json")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        val text = SettingsCodec.encode(next)
        writer.execute {
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    private fun load(): AppSettings = SettingsCodec.decode(runCatching { if (file.exists()) file.readText() else null }.getOrNull())
}
