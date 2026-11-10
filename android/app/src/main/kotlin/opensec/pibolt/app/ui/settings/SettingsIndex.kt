package opensec.pibolt.app.ui.settings

/** The groups of the Settings screen, in screen order. */
enum class SettingsGroup(val title: String) {
    Models("Models & keys"),
    Appearance("Appearance"),
    Agent("Agent"),
    Projects("Projects & runtimes"),
    RemoteControl("Remote control"),
    GitHub("GitHub"),
    Advanced("Advanced"),
    About("About"),
}

/** One searchable setting: [keywords] are the other words a user might type for it. */
data class SettingEntry(val id: String, val group: SettingsGroup, val label: String, val keywords: String = "")

/** What the Settings search matches (unit-tested): every word of the query, in the group title, label or keywords. */
object SettingsIndex {
    val entries: List<SettingEntry> = listOf(
        SettingEntry("providers", SettingsGroup.Models, "Model providers", "api key keys login sign in anthropic openai gemini openrouter account remove add custom endpoint"),
        SettingEntry("default-model", SettingsGroup.Models, "Default model", "model start new session provider"),
        SettingEntry("thinking", SettingsGroup.Models, "Thinking level", "reasoning effort think new sessions"),
        SettingEntry("theme", SettingsGroup.Appearance, "Theme", "colour color dark light pi-bolt graphite paper white contrast lime moss ember claude codex vercel github look"),
        SettingEntry("accent", SettingsGroup.Appearance, "Accent", "colour color iris glacier ink"),
        SettingEntry("text-size", SettingsGroup.Appearance, "Text size", "font larger smaller zoom"),
        SettingEntry("density", SettingsGroup.Appearance, "Density", "compact comfortable spacing"),
        SettingEntry("web-search", SettingsGroup.Agent, "Web search", "internet web search browse fetch pages exa sources online"),
        SettingEntry("steering", SettingsGroup.Agent, "Steering messages", "queue steer while working"),
        SettingEntry("follow-up", SettingsGroup.Agent, "Follow-up messages", "queue after finishes"),
        SettingEntry("compaction", SettingsGroup.Agent, "Auto-compact", "context summarise summary full compaction"),
        SettingEntry("retry", SettingsGroup.Agent, "Auto-retry", "overloaded rate limit retries errors"),
        SettingEntry("skills", SettingsGroup.Agent, "Skill commands", "skills slash commands /skill"),
        SettingEntry("images", SettingsGroup.Agent, "Block images", "pictures photos attachments vision privacy"),
        SettingEntry("thinking-expanded", SettingsGroup.Agent, "Show thinking expanded", "reasoning collapse session display"),
        SettingEntry("tool-preview", SettingsGroup.Agent, "Tool output preview", "lines output tools session display"),
        SettingEntry("voice", SettingsGroup.Agent, "Voice mode", "voice speech speak replies read aloud tts text to speech rate speed talk hands-free conversation"),
        SettingEntry("spending", SettingsGroup.Agent, "Spending", "cost costs money budget limit limits cap caps dollars usd price spend daily monthly"),
        SettingEntry("runtimes", SettingsGroup.Projects, "Runtimes", "node nodejs javascript npm npx python python3 pip packages cache languages"),
        SettingEntry("packages", SettingsGroup.Projects, "Packages and extensions", "extensions packages npm git install skills prompts"),
        SettingEntry("mcp", SettingsGroup.Projects, "MCP servers", "mcp model context protocol servers tools"),
        SettingEntry("computers", SettingsGroup.RemoteControl, "Computers", "paired computer laptop desktop mac host pair scan qr code paste link connect forget"),
        SettingEntry("device-key", SettingsGroup.RemoteControl, "Device key", "security pairing reset revoke computer phone key"),
        SettingEntry("github", SettingsGroup.GitHub, "GitHub account", "git push pull clone pull request token sign in login personal access oauth"),
        SettingEntry("tuning", SettingsGroup.Advanced, "Retry and compaction", "tuning reserve tokens backoff delay"),
        SettingEntry("reduce-motion", SettingsGroup.Advanced, "Reduce motion", "animations animation accessibility motion"),
        SettingEntry("haptics", SettingsGroup.Advanced, "Haptics", "vibration vibrate feedback"),
        SettingEntry("notifications", SettingsGroup.Advanced, "Notifications", "alerts finished background permission"),
        SettingEntry("background", SettingsGroup.Advanced, "Run in background", "battery doze autostart miui sleep screen off"),
        SettingEntry("version", SettingsGroup.About, "Version", "about phone device"),
        SettingEntry("updates", SettingsGroup.About, "Updates", "update check for updates automatically install download new version"),
        SettingEntry("licences", SettingsGroup.About, "Licences", "licence license font inter jetbrains mono mermaid termux terminal apache mit open source logo icons lobe"),
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
