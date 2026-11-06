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

    /** The first prompt of a "Clone from git" project: git runs inside the engine, so Pi does the clone. */
    fun clonePrompt(url: String): String =
        "Clone ${url.trim()} into this folder: run `git clone ${url.trim()} .` here, then give me a short overview of the repository."

    /** Pinned first, then most recently active, then by title. */
    fun order(projects: List<ProjectEntry>): List<ProjectEntry> =
        projects.sortedWith(compareByDescending<ProjectEntry> { it.meta.pinned }.thenByDescending { it.lastActive }.thenBy { it.title.lowercase() })

    /** A session's line in lists: its name, else its first prompt, else a placeholder. */
    fun sessionTitle(s: SessionSummary): String =
        s.name?.takeIf { it.isNotBlank() } ?: s.preview?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Untitled session"

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
