package opensec.pibolt.app.ui.packages

import java.net.URI
import java.net.URLDecoder

/**
 * Package links from outside the app, as a `pi install` source (pure, unit-tested). Accepted:
 * - `pibolt://install?source=<source>` with an npm source (`npm:<name>[@version]`) or a git source on a known host;
 * - pi.dev package pages (`https://pi.dev/packages/<name>`) and npm package pages (`https://www.npmjs.com/package/<name>`,
 *   optionally `/v/<version>`): `npm:<name>`;
 * - GitHub, GitLab, Codeberg and Bitbucket repositories (`https://github.com/<owner>/<repo>`, optionally
 *   `/tree/<ref>`): `git:<host>/<owner>/<repo>[@ref]`;
 * - in shared text, also a copied `pi install <source>` command.
 * Local paths, ssh and plain http, other hosts and pages inside a repository are refused: a link never installs
 * anything by itself, but it should only ever propose a source the user can check.
 */
object PackageLinks {
    private val NPM_NAME = Regex("^(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$")
    private val NPM_VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9.+_~^<>=*|-]{0,63}$")
    private val GIT_HOSTS = setOf("github.com", "gitlab.com", "codeberg.org", "bitbucket.org")
    private val GIT_PART = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
    private val GIT_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$")
    private val URL_IN_TEXT = Regex("""(?:pibolt://install\S*|https://\S+)""")
    private val INSTALL_COMMAND = Regex("""(?:^|\s)pi\s+install\s+(?:-l\s+)?(\S+)""")
    private const val MAX = 2048

    /** The source a link proposes, or null when it is not a package link. */
    fun fromUri(link: String): String? {
        val text = link.trim().trimEnd('.', ',', ')', ']', '>', '"', '\'')
        if (text.isEmpty() || text.length > MAX) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        return when {
            scheme == "pibolt" && host == "install" -> query(uri.rawQuery, "source")?.let(::checkSource)
            scheme != "https" -> null
            host == "pi.dev" -> segments(uri).takeIf { it.firstOrNull() == "packages" }?.drop(1)?.let { npmFromPath(it, versioned = false) }
            host == "npmjs.com" -> segments(uri).takeIf { it.firstOrNull() == "package" }?.drop(1)?.let { npmFromPath(it, versioned = true) }
            host in GIT_HOSTS -> gitFromPath(host, segments(uri))
            else -> null
        }
    }

    /** The first package link (or `pi install <source>` command) in text shared from another app, or null. */
    fun fromSharedText(text: String?): String? {
        if (text.isNullOrBlank() || text.length > 8 * MAX) return null
        for (m in URL_IN_TEXT.findAll(text)) fromUri(m.value)?.let { return it }
        return INSTALL_COMMAND.find(text)?.groupValues?.get(1)?.let(::checkSource)
    }

    /** [source] when it is an npm source or a git source on a known host, normalized; null otherwise. */
    fun checkSource(source: String): String? {
        val s = source.trim()
        if (s.isEmpty() || s.length > 300) return null
        if (s.startsWith("https://")) return fromUri(s)
        if (s.startsWith("git:")) {
            val rest = s.removePrefix("git:")
            val ref = rest.substringAfter('@', "").ifEmpty { null }
            val parts = rest.substringBefore('@').removeSuffix(".git").split('/')
            if (parts.size != 3 || parts[0].lowercase() !in GIT_HOSTS) return null
            return gitSource(parts[0].lowercase(), parts[1], parts[2], ref)
        }
        val npm = s.removePrefix("npm:")
        // A bare name is accepted too (the install field adds `npm:`), but never a path or URL.
        if (npm.startsWith("/") || npm.startsWith(".") || ":" in npm) return null
        val at = npm.indexOf('@', startIndex = if (npm.startsWith("@")) 1 else 0)
        val name = if (at < 0) npm else npm.substring(0, at)
        val version = if (at < 0) null else npm.substring(at + 1)
        return npmSource(name, version)
    }

    /** An npm source's package name and version (null when unpinned); null for other sources. */
    fun npmParts(source: String): Pair<String, String?>? {
        if (!source.startsWith("npm:")) return null
        val s = source.removePrefix("npm:")
        val at = s.indexOf('@', startIndex = if (s.startsWith("@")) 1 else 0)
        return if (at < 0) s to null else s.substring(0, at) to s.substring(at + 1).ifEmpty { null }
    }

    /** A GitHub source's owner, repository and ref (null for the default branch); null for other sources. */
    fun githubParts(source: String): Triple<String, String, String?>? {
        val rest = source.takeIf { it.startsWith("git:github.com/") }?.removePrefix("git:github.com/") ?: return null
        val ref = rest.substringAfter('@', "").ifEmpty { null }
        val parts = rest.substringBefore('@').split('/')
        return if (parts.size == 2) Triple(parts[0], parts[1], ref) else null
    }

    private fun npmFromPath(parts: List<String>, versioned: Boolean): String? {
        val nameParts = if (parts.firstOrNull()?.startsWith("@") == true) 2 else 1
        if (parts.size < nameParts) return null
        val name = parts.take(nameParts).joinToString("/")
        val rest = parts.drop(nameParts)
        val version = when {
            rest.isEmpty() -> null
            versioned && rest.size == 2 && rest[0] == "v" -> rest[1]
            else -> return null
        }
        return npmSource(name, version)
    }

    private fun npmSource(name: String, version: String?): String? {
        if (name.length > 214 || !NPM_NAME.matches(name.lowercase())) return null
        if (version != null && !NPM_VERSION.matches(version)) return null
        return "npm:" + name + (version?.let { "@$it" } ?: "")
    }

    private fun gitFromPath(host: String, parts: List<String>): String? {
        if (parts.size < 2) return null
        val ref = when {
            parts.size == 2 -> null
            // GitHub and Codeberg: /tree/<ref>; GitLab: /-/tree/<ref>. A page inside the repository is not a package.
            parts.size == 4 && parts[2] == "tree" -> parts[3]
            parts.size == 5 && parts[2] == "-" && parts[3] == "tree" -> parts[4]
            else -> return null
        }
        return gitSource(host, parts[0], parts[1].removeSuffix(".git"), ref)
    }

    private fun gitSource(host: String, owner: String, repo: String, ref: String?): String? {
        if (!GIT_PART.matches(owner) || !GIT_PART.matches(repo)) return null
        if (ref != null && (!GIT_REF.matches(ref) || ".." in ref)) return null
        return "git:$host/$owner/$repo" + (ref?.let { "@$it" } ?: "")
    }

    private fun segments(uri: URI): List<String> =
        uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }

    private fun query(raw: String?, key: String): String? =
        raw.orEmpty().split('&').firstNotNullOfOrNull { pair ->
            val k = pair.substringBefore('=')
            if (k == key) runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() else null
        }
}
