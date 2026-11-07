package opensec.pibolt.app.ui.projects

import androidx.compose.runtime.Immutable
import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.engine.SessionSummary

/** One project folder as Home lists it. */
@Immutable
data class ProjectEntry(
    /** Folder name in the engine's projects dir; also the project's id. */
    val name: String,
    val path: String,
    val meta: ProjectMeta,
    val lastSession: SessionSummary?,
    val sessionCount: Int,
) {
    val title: String get() = meta.title ?: name

    /** The newest of "opened here" and "a session changed". */
    val lastActive: Long get() = maxOf(meta.lastOpenedAt, lastSession?.updatedAt ?: 0)
}

/** Pure rules for project folders, names and git URLs (unit-tested). */
object ProjectRules {
    const val MAX_NAME = 64

    /** Why [name] cannot be a new folder in a projects dir that holds [existing], or null when it can. */
    fun nameError(name: String, existing: Collection<String>): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Give the project a name"
            n.length > MAX_NAME -> "At most $MAX_NAME characters"
            n == "." || n == ".." -> "Not a usable folder name"
            n.startsWith(".") -> "Can't start with a dot"
            n.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } -> "No slashes or control characters"
            existing.any { it.equals(n, ignoreCase = true) } -> "A project with this name exists"
            else -> null
        }
    }

    private val gitUrl = Regex("""^(https?://|ssh://|git://|git@)[^\s'"`;|&$<>\\(){}]+$""")

    /** A clone URL this app passes to `git clone` (http(s), ssh, git, scp-like `git@host:path`), with no shell syntax. */
    fun isGitUrl(url: String): Boolean {
        val u = url.trim()
        if (!gitUrl.matches(u)) return false
        if (u.startsWith("git@")) return u.contains(':') && u.substringAfter(':').isNotBlank()
        return u.substringAfter("://").substringBefore('/').isNotBlank() && u.substringAfter("://").contains('/')
    }

    /** The folder name `git clone` would pick: the last path part without `.git`. */
    fun repoName(url: String): String {
        val path = url.trim().trimEnd('/').let { if (it.startsWith("git@")) it.substringAfter(':') else it.substringAfter("://") }
        val last = path.substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        return last.filter { it.isLetterOrDigit() || it in "-_." }.trimStart('.').take(MAX_NAME)
    }

    /** The first prompt of a "Clone from git" project, once the app has cloned it. */
    fun clonePrompt(url: String): String =
        "I cloned ${url.trim()} into this folder. Give me a short overview of the repository."

    /** Pinned first, then most recently active, then by title. */
    fun order(projects: List<ProjectEntry>): List<ProjectEntry> =
        projects.sortedWith(compareByDescending<ProjectEntry> { it.meta.pinned }.thenByDescending { it.lastActive }.thenBy { it.title.lowercase() })

    /** A session's line in lists: its name, else its first prompt, else a placeholder. */
    fun sessionTitle(s: SessionSummary): String =
        s.name?.takeIf { it.isNotBlank() } ?: s.preview?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Untitled session"

    /** A session written to after it was last looked at here. Never-seen sessions are not unread (no dots on upgrade). */
    fun unread(s: SessionSummary, seenAt: Long?): Boolean = seenAt != null && s.updatedAt > seenAt + UNREAD_SLACK_MS

    /** Writes that land just after a look (the session closing) do not make it unread. */
    const val UNREAD_SLACK_MS = 3_000L

    /**
     * Home's "Continue": each project's newest session (subagent sessions are never listed, see SessionIndex), the live
     * project's first, then the most recently changed; projects without sessions are left out; at most [limit].
     * Projects in [running] are left out too: Home's "Running" section already shows them, with what they do.
     */
    fun continueList(projects: List<ProjectEntry>, liveProject: String?, limit: Int = 3, running: Set<String> = emptySet()): List<ProjectEntry> =
        projects
            .filter { it.lastSession != null && it.name !in running }
            .sortedWith(compareByDescending<ProjectEntry> { it.name == liveProject }.thenByDescending { it.lastSession?.updatedAt ?: 0 }.thenBy { it.title.lowercase() })
            .take(limit)

    /**
     * The Home pill starts a chat rather than a session in a project when there is no project, or when a chat changed
     * last ([lastChatAt]: the newest chat's write) after any project was worked in ([lastProjectAt]: the newest
     * [ProjectEntry.lastActive]). The user's pick in the chip wins over this default.
     */
    fun pillDefaultsToChat(noProjects: Boolean, lastChatAt: Long?, lastProjectAt: Long?): Boolean =
        noProjects || (lastChatAt != null && lastChatAt > (lastProjectAt ?: 0L))

    /**
     * What the Home pill's + offers: with Chat picked, a chat's own actions (a photo, the clipboard's image when it holds
     * one, voice, New chat), each opening a new chat with the draft; with a project, [PillPlus.NewProject] (the sheet
     * with New project, Clone from git and Import a zip).
     */
    fun pillPlus(chat: Boolean, clipboardImage: Boolean): List<PillPlus> =
        if (chat) listOfNotNull(PillPlus.Photos, PillPlus.PasteImage.takeIf { clipboardImage }, PillPlus.Voice, PillPlus.NewChat) else listOf(PillPlus.NewProject)

    /**
     * The focus card's title for a session: its name, else the first line of its latest prompt, else [fallback] (the
     * project). Blank values count as missing.
     */
    fun focusTitle(name: String?, lastPrompt: String?, fallback: String): String =
        name?.trim()?.takeIf { it.isNotEmpty() }
            ?: lastPrompt?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: fallback

    /** Two letters for a project tile: initials of the first two words ("calc-parser" -> "CP"), else its first two letters. */
    fun monogram(title: String): String {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val letters = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}"
            words.size == 1 -> words[0].take(2)
            else -> title.take(2)
        }
        return letters.uppercase().ifBlank { "?" }
    }

    /**
     * "3 sessions · 2h", "1 session · now", or "No sessions yet" for a project that has never had a session. The age is
     * [agoShort], as on Home's other rows (Recents "notes · 2h", Chats "2h · 4 messages").
     */
    fun projectMeta(entry: ProjectEntry, now: Long): String =
        projectMetaParts(entry, now).let { (lead, age) -> if (age == null) lead else "$lead · $age" }

    /**
     * [projectMeta] in two parts for the card: the part that may be cut ("3 sessions") and the age that never is
     * ("2h", "opened 3d"), or null when there is no age to show.
     */
    fun projectMetaParts(entry: ProjectEntry, now: Long): Pair<String, String?> {
        if (entry.sessionCount == 0) return if (entry.meta.lastOpenedAt > 0) "No sessions" to "opened ${agoShort(entry.meta.lastOpenedAt, now)}" else "No sessions yet" to null
        val count = if (entry.sessionCount == 1) "1 session" else "${entry.sessionCount} sessions"
        return count to agoShort(entry.lastActive, now)
    }

    /**
     * The short time at a list row's end (Pi-Bolt Desktop's `relShort`): "now", "5m", "3h", "2d", "3w", "4mo". TalkBack
     * reads [ago] instead.
     */
    fun agoShort(at: Long, now: Long): String {
        if (at <= 0) return "–"
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            s < 60 * 86_400 -> "${s / (7 * 86_400)}w"
            else -> "${s / (30 * 86_400)}mo"
        }
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago", then a date-free "5 wk ago" / "3 mo ago". */
    fun ago(at: Long, now: Long): String {
        if (at <= 0) return "never"
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 7 * 86_400 -> "${s / 86_400} d ago"
            s < 60 * 86_400 -> "${s / (7 * 86_400)} wk ago"
            else -> "${s / (30 * 86_400)} mo ago"
        }
    }
}

/** An action behind the Home pill's + ([ProjectRules.pillPlus]). */
enum class PillPlus { Photos, PasteImage, Voice, NewChat, NewProject }
