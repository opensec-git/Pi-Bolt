package opensec.pibolt.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.app.cost.SpendLimits
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.str
import java.io.File
import java.util.concurrent.Executors

/**
 * Themes, stored by name. Pi-Bolt (id Graphite), Paper (light), Contrast, Lime and Moss are the app's own; Claude, Codex,
 * Vercel and GitHub are palettes in the spirit of those products' dark modes. A removed theme's name (Tide, Void) is
 * unknown to [SettingsCodec], so it falls back to the default.
 */
enum class ThemeId(val label: String) {
    /** The default: Pi-Bolt Desktop's palette. The id stays Graphite so saved settings keep it. */
    Graphite("Pi-Bolt"),
    Paper("Paper"),
    Contrast("Contrast"),
    Lime("Lime"),
    Moss("Moss"),
    Ember("Ember"),
    Claude("Claude"),
    Codex("Codex"),
    Vercel("Vercel"),
    GitHub("GitHub"),
}

/** [Theme] uses the theme's own accent (white for Pi-Bolt, Claude's coral, GitHub's blue, ink for the others). */
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
    /**
     * The bundled web tools (pi-web-access: search through Exa, read pages) in on-device sessions: chats and projects
     * (engine/ChatSpace.kt). Off: engines start without them.
     */
    val webSearch: Boolean = true,
    /** Voice mode says each reply's summary aloud (off: it is shown only). */
    val voiceSpeakReplies: Boolean = true,
    /** Voice mode's speech rate in percent: one of [VOICE_RATE_CHOICES]. */
    val voiceRate: Int = 100,
    /** The text-to-speech voice by its engine name; null is the engine's default. */
    val voiceName: String? = null,
    /** [voiceName] as the picker named it ("English (United States) 2"), for the Settings row. */
    val voiceLabel: String? = null,
    /** Settings > Agent > Spending: limits per session, day and month, and costs on list rows. */
    val spend: SpendLimits = SpendLimits(),
) {
    companion object {
        val VOICE_RATE_CHOICES = listOf(75, 100, 125, 150, 200)
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
        put("webSearch", s.webSearch)
        put("voiceSpeakReplies", s.voiceSpeakReplies)
        put("voiceRate", s.voiceRate)
        s.voiceName?.let { put("voiceName", it) }
        s.voiceLabel?.let { put("voiceLabel", it) }
        s.spend.session?.let { put("spendSession", it) }
        s.spend.day?.let { put("spendDay", it) }
        s.spend.month?.let { put("spendMonth", it) }
        put("spendInLists", s.spend.showInLists)
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
            webSearch = json.bool("webSearch") ?: d.webSearch,
            voiceSpeakReplies = json.bool("voiceSpeakReplies") ?: d.voiceSpeakReplies,
            voiceRate = json.int("voiceRate")?.takeIf { it in AppSettings.VOICE_RATE_CHOICES } ?: d.voiceRate,
            voiceName = json.str("voiceName")?.takeIf { it.isNotBlank() },
            voiceLabel = json.str("voiceLabel")?.takeIf { it.isNotBlank() && !json.str("voiceName").isNullOrBlank() },
            spend = SpendLimits(
                session = limit(json.double("spendSession")),
                day = limit(json.double("spendDay")),
                month = limit(json.double("spendMonth")),
                showInLists = json.bool("spendInLists") ?: d.spend.showInLists,
            ),
        )
    }

    /** A saved limit: positive and finite, else none. */
    private fun limit(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 }

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
