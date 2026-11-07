package opensec.pibolt.app.ui.session.web

import java.net.IDN
import java.net.URI
import java.security.MessageDigest

/**
 * Which favicon a source gets, and where it is cached (pure; [Favicons] does the I/O).
 *
 * Privacy: the icon comes from the site itself, never a third-party favicon service (Google's, DuckDuckGo's), so no
 * one else learns which pages a chat read. Only over https; only for public host names (no IP address, localhost or
 * local-network name: a page on the LAN is never probed). First `https://<host>/favicon.ico`; when that is no image,
 * the first `<link rel="icon">` of the site's home page (read up to [MAX_HTML_BYTES]).
 */
object FaviconRules {
    const val MAX_ICON_BYTES = 256 * 1024
    const val MAX_HTML_BYTES = 64 * 1024

    /** The decoded icon's side: icons are stored at this size. */
    const val ICON_PX = 64

    /** The disk cache's limit; the least recently used icons go first. */
    const val DISK_LIMIT_BYTES = 4L * 1024 * 1024

    /** A host without an icon is tried again after this long. */
    const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000

    private val localSuffixes = listOf(".localhost", ".local", ".internal", ".lan", ".home", ".home.arpa", ".onion", ".test", ".invalid", ".example")
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** The host whose icon a page at [url] gets (ASCII, lowercase), or null when none is fetched for it. */
    fun iconHost(url: String): String? {
        val raw = WebTools.hostOf(url) ?: return null
        val host = runCatching { IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull() ?: return null
        if (host.isEmpty() || host.length > 253 || '.' !in host || ':' in host || host.startsWith("[")) return null
        if (ipv4.matches(host) || host.all { it.isDigit() || it == '.' }) return null
        if (host == "localhost" || localSuffixes.any { host.endsWith(it) }) return null
        if (!host.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return null
        return host
    }

    /** The first place to look: the site's /favicon.ico, over https. */
    fun iconUrl(host: String): String = "https://$host/favicon.ico"

    /** The site's home page, read for its `<link rel="icon">` when /favicon.ico has none. */
    fun homeUrl(host: String): String = "https://$host/"

    /** The cache file name of [host]'s icon (without extension): the first 32 hex digits of its SHA-256. */
    fun cacheKey(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private val linkTag = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attr = Regex("""([a-zA-Z:-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""")

    /**
     * The icon links of a home page, best first: `rel="icon"` / `"shortcut icon"`, then `apple-touch-icon`; resolved
     * against the page, https only, no SVG (not decodable) and no data: URL.
     */
    fun linkIcons(html: String, pageUrl: String): List<String> {
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        val icons = ArrayList<Pair<Int, String>>()
        for (tag in linkTag.findAll(html)) {
            val attrs = attr.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase() to (m.groups[3]?.value ?: m.groups[4]?.value ?: m.groups[5]?.value.orEmpty())
            }
            val rel = attrs["rel"]?.lowercase()?.split(Regex("\\s+")).orEmpty()
            val rank = when {
                "icon" in rel -> 0
                "apple-touch-icon" in rel || "apple-touch-icon-precomposed" in rel -> 1
                else -> continue
            }
            val href = attrs["href"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (attrs["type"]?.contains("svg", ignoreCase = true) == true) continue
            val resolved = runCatching { base.resolve(href.replace(" ", "%20")).toString() }.getOrNull() ?: continue
            if (!resolved.startsWith("https://") || resolved.substringBefore('?').endsWith(".svg", ignoreCase = true)) continue
            icons += rank to resolved
        }
        return icons.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** True when [bytes] start like an image Android decodes (PNG, ICO, GIF, JPEG, WebP, BMP): not an HTML error page. */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true
            at(0) == 0x00 && at(1) == 0x00 && (at(2) == 0x01 || at(2) == 0x02) && at(3) == 0x00 -> true
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true
            at(0) == 0xFF && at(1) == 0xD8 -> true
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true
            at(0) == 0x42 && at(1) == 0x4D -> true
            else -> false
        }
    }

    /** A redirect is followed only to another https URL, at most [MAX_REDIRECTS] times. */
    fun redirectTarget(from: String, location: String?): String? {
        location ?: return null
        val next = runCatching { URI(from).resolve(location.trim()).toString() }.getOrNull() ?: return null
        return next.takeIf { it.startsWith("https://") && iconHost(it) != null }
    }

    const val MAX_REDIRECTS = 3
}
