package opensec.pibolt.app.ui.settings

/** The groups of the Settings screen, in screen order. */
enum class SettingsGroup(val title: String) {
    Providers("Model providers"),
    Agent("Pi on this phone"),
    GitHub("GitHub"),
    Appearance("Appearance"),
    Motion("Motion and haptics"),
    SessionDisplay("Session display"),
    Notifications("Notifications"),
    Security("Security"),
    About("About"),
}

/** One searchable setting: [keywords] are the other words a user might type for it. */
data class SettingEntry(val id: String, val group: SettingsGroup, val label: String, val keywords: String = "")

/** What the Settings search matches (unit-tested): every word of the query, in the group title, label or keywords. */
object SettingsIndex {
    val entries: List<SettingEntry> = listOf(
        SettingEntry("providers", SettingsGroup.Providers, "Model providers", "api key login sign in anthropic openai gemini openrouter account remove add"),
        SettingEntry("default-model", SettingsGroup.Agent, "Default model", "model start new session provider"),
        SettingEntry("thinking", SettingsGroup.Agent, "Thinking level for new sessions", "reasoning effort think"),
        SettingEntry("steering", SettingsGroup.Agent, "Steering messages", "queue steer while working"),
        SettingEntry("follow-up", SettingsGroup.Agent, "Follow-up messages", "queue after finishes"),
        SettingEntry("compaction", SettingsGroup.Agent, "Auto-compact", "context summarise summary full compaction"),
        SettingEntry("retry", SettingsGroup.Agent, "Auto-retry", "overloaded rate limit retries errors"),
        SettingEntry("skills", SettingsGroup.Agent, "Skill commands", "skills slash commands /skill"),
        SettingEntry("images", SettingsGroup.Agent, "Block images", "pictures photos attachments vision privacy"),
        SettingEntry("engine-links", SettingsGroup.Agent, "Packages, MCP servers, retry and compaction", "extensions packages npm git mcp servers tools tuning reserve tokens backoff"),
        SettingEntry("github", SettingsGroup.GitHub, "GitHub account", "git push pull clone pull request token sign in login personal access oauth"),
        SettingEntry("theme", SettingsGroup.Appearance, "Theme", "colour color dark graphite void ember claude codex vercel github look"),
        SettingEntry("accent", SettingsGroup.Appearance, "Accent", "colour color iris glacier ink"),
        SettingEntry("text-size", SettingsGroup.Appearance, "Text size", "font larger smaller zoom"),
        SettingEntry("density", SettingsGroup.Appearance, "Density", "compact comfortable spacing"),
        SettingEntry("reduce-motion", SettingsGroup.Motion, "Reduce motion", "animations animation accessibility"),
        SettingEntry("haptics", SettingsGroup.Motion, "Haptics", "vibration vibrate feedback"),
        SettingEntry("thinking-expanded", SettingsGroup.SessionDisplay, "Show thinking expanded", "reasoning collapse"),
        SettingEntry("tool-preview", SettingsGroup.SessionDisplay, "Tool output preview", "lines output tools"),
        SettingEntry("notifications", SettingsGroup.Notifications, "Notifications", "alerts finished background permission"),
        SettingEntry("device-key", SettingsGroup.Security, "Device key", "remote control pairing reset revoke computer"),
        SettingEntry("version", SettingsGroup.About, "Version", "about phone device"),
        SettingEntry("updates", SettingsGroup.About, "Updates", "update check install download new version"),
        SettingEntry("licences", SettingsGroup.About, "Licences", "licence license font inter jetbrains mono open source"),
    )

    /** The ids to show for [query]; null shows everything (a blank query). */
    fun visible(query: String): Set<String>? {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return entries.filter { e ->
            val text = "${e.group.title} ${e.label} ${e.keywords}".lowercase()
            words.all { it in text }
        }.mapTo(LinkedHashSet()) { it.id }
    }

    /** The groups that have something to show. */
    fun groups(visible: Set<String>?): List<SettingsGroup> =
        SettingsGroup.entries.filter { g -> visible == null || entries.any { it.group == g && it.id in visible } }
}
