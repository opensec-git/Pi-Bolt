package opensec.pibolt.app.git

import androidx.compose.runtime.Immutable

/** A network operation's progress: `Receiving objects`, 45. [percent] is null for phases without a count. */
@Immutable
data class GitProgress(val phase: String, val percent: Int?) {
    val label: String get() = if (percent != null) "$phase · $percent%" else phase

    companion object {
        private val counted = Regex("""^(?:remote: )?([A-Za-z][A-Za-z ]+?):\s+(\d{1,3})% \(\d+/\d+\).*""")
        private val plain = Regex("""^(?:remote: )?(Enumerating objects|Counting objects|Compressing objects|Cloning into|Resolving deltas|Updating files)\b.*""")

        /** One stderr line of `git clone/fetch/pull/push --progress`, or null when it is not progress. */
        fun parse(line: String): GitProgress? {
            val l = line.trim()
            counted.matchEntire(l)?.let { return GitProgress(it.groupValues[1].trim(), it.groupValues[2].toInt().coerceIn(0, 100)) }
            plain.matchEntire(l)?.let { return GitProgress(it.groupValues[1].trim(), null) }
            return null
        }
    }
}

/** Git's stderr as a message for the user: the common failures in plain words, else git's own last error line. */
object GitErrors {
    fun explain(err: String, fallback: String = "Git failed"): String {
        val e = err.lowercase()
        return when {
            "could not read username" in e || "authentication failed" in e || "terminal prompts disabled" in e || "invalid username or password" in e ->
                "GitHub did not accept the credentials. Sign in again in Settings."
            "permission to" in e && "denied" in e || "the requested url returned error: 403" in e ->
                "Your GitHub account can't push to this repository. The token needs the repo scope and write access."
            "workflow" in e && "scope" in e ->
                "Pushing changes to .github/workflows needs a token with the workflow scope."
            "repository not found" in e || "the requested url returned error: 404" in e ->
                "Repository not found. Check the URL, or sign in to GitHub if it is private."
            "could not resolve host" in e || "couldn't resolve host" in e || "network is unreachable" in e || "failed to connect" in e || "connection timed out" in e ->
                "Could not reach the server. Check the network and try again."
            "non-fast-forward" in e || "[rejected]" in e && "fetch first" in e || "updates were rejected" in e ->
                "The remote has commits this branch doesn't have. Pull first, then push."
            "not possible to fast-forward" in e || "diverging branches" in e || "have diverged" in e ->
                "This branch and the remote have both changed. Ask Pi to merge or rebase them."
            "would be overwritten by" in e ->
                "Local changes would be overwritten. Commit or discard them first."
            "please tell me who you are" in e || "author identity unknown" in e || "empty ident name" in e ->
                "Git needs your name and email for commits."
            "nothing to commit" in e -> "Nothing staged to commit."
            "no configured push destination" in e || "does not appear to be a git repository" in e || "no such remote" in e ->
                "This project has no remote to push to or pull from."
            "not a git repository" in e -> "This project is not a git repository."
            "already exists and is not an empty directory" in e -> "The folder is not empty."
            "conflict" in e && "merge" in e -> "Merge conflicts: resolve them, or ask Pi to."
            else -> lastError(err) ?: fallback
        }
    }

    /** git's `fatal:` / `error:` line, else its last non-progress line. */
    private fun lastError(err: String): String? {
        val lines = err.lines().map { it.trim() }.filter { it.isNotEmpty() && GitProgress.parse(it) == null }
        val fatal = lines.lastOrNull { it.startsWith("fatal:") || it.startsWith("error:") }
        return (fatal ?: lines.lastOrNull())?.removePrefix("fatal:")?.removePrefix("error:")?.trim()?.replaceFirstChar { it.uppercase() }?.take(300)
    }
}
