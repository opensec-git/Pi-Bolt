package opensec.pibolt.app.ui.session.web

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import java.net.URI

/** Where a source is: found by a search, being read (`fetch_content`), read, or it could not be read. */
enum class SourceState { Found, Fetching, Read, Failed }

/** One web page a search found or the model read: its [url], [host] (for the favicon and the domain line) and [title]. */
@Immutable
data class WebSource(val url: String, val host: String, val title: String?, val state: SourceState) {
    /** The domain as shown: the host without "www.". */
    val domain: String get() = host.removePrefix("www.")
}

/**
 * What a run of web steps found, for the Sources pill: the [sources] in order (search results, then pages read that no
 * search listed), the [queries] of searches still running ([searching]), and [error], the reason when every step failed.
 */
@Immutable
data class WebSources(
    val sources: List<WebSource>,
    val searching: Boolean,
    val queries: List<String>,
    val error: String? = null,
    /** A step is still being written or running: nothing is final yet (no "No sources found" meanwhile). */
    val active: Boolean = searching,
) {
    val fetching: Boolean get() = sources.any { it.state == SourceState.Fetching }
}

/**
 * The bundled pi-web-access (0.44.0) tools as the transcript shows them: `web_search`, `source_check` and
 * `fetch_content` become one Sources pill per run of consecutive calls ([of]). Everything is read from what the app
 * already has: the call's arguments, the result text and `details` (live, and from a saved session alike).
 *
 * Result formats (web-tool-core.ts):
 * - a search lists its sources as `N. <title>\n   <url>` (source_check: `N. [quality] <title>\n   <url>`), after the
 *   provider's answer; an answer-only provider names them as `Source: <title> (<url>)` lines.
 * - one page read: the page text; `details.title`, `details.urls`; an error result is `Error: <reason>`.
 * - several pages: `## Fetched URLs` and one line per URL in call order, `- <title> (<n> chars)` or
 *   `- <url>: Error - <reason>`.
 */
object WebTools {
    const val SEARCH = "web_search"
    const val FETCH = "fetch_content"
    const val SOURCE_CHECK = "source_check"

    /** A tool that shows as sources. */
    fun isSourceTool(item: ToolItem): Boolean = item.name == SEARCH || item.name == FETCH || item.name == SOURCE_CHECK

    private val numbered = Regex("""(?m)^\d+\.\s+(?:\[[^\]\n]*]\s+)?(.+?)\s*\n {3}(https?://\S+)\s*$""")
    private val sourceLine = Regex("""(?m)^Source:\s*(.*)\s+\((https?://[^\s)]+)\)\s*$""")
    private val fetchedLine = Regex("""^- (.*) \((\d+) chars\)$""")

    /** The sources of a run of web steps (in call order). */
    fun of(tools: List<ToolItem>): WebSources {
        val out = LinkedHashMap<String, WebSource>()
        val queries = ArrayList<String>()
        var searching = false
        val errors = ArrayList<String>()
        var anyOk = false
        fun add(url: String, title: String?, state: SourceState) {
            val host = hostOf(url) ?: return
            val key = keyOf(url)
            val old = out[key]
            out[key] = when {
                old == null -> WebSource(url, host, title?.takeIf { it.isNotBlank() }, state)
                // Read later: the search's entry takes the read's state, and the page's own title when it had none.
                state != SourceState.Found -> old.copy(state = state, title = old.title ?: title?.takeIf { it.isNotBlank() })
                else -> old
            }
        }
        var active = false
        for (item in tools) {
            if (item.isActive) active = true
            when (item.name) {
                SEARCH, SOURCE_CHECK -> when {
                    item.isActive -> {
                        searching = true
                        queries += queriesOf(item)
                    }
                    item.status == ToolStatus.Done -> {
                        anyOk = true
                        for ((title, url) in searchResults(item.result.orEmpty())) add(url, title, SourceState.Found)
                    }
                    else -> errors += errorOf(item)
                }
                FETCH -> {
                    val urls = fetchUrls(item)
                    if (item.isActive) {
                        for (url in urls) add(url, null, SourceState.Fetching)
                    } else {
                        val outcome = fetchOutcome(item, urls)
                        if (outcome.any { !it.failed }) anyOk = true else errors += errorOf(item)
                        for (o in outcome) add(o.url, o.title, if (o.failed) SourceState.Failed else SourceState.Read)
                    }
                }
            }
        }
        val error = if (!anyOk && !active && errors.isNotEmpty()) errors.first() else null
        return WebSources(out.values.toList(), searching, queries.distinct(), error, active)
    }

    /** A search result's sources: (title, url), each URL once, in the result's order. */
    fun searchResults(text: String): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (m in numbered.findAll(text)) if (seen.add(keyOf(m.groupValues[2]))) out += m.groupValues[1].trim() to m.groupValues[2]
        for (m in sourceLine.findAll(text)) if (seen.add(keyOf(m.groupValues[2]))) out += m.groupValues[1].trim() to m.groupValues[2]
        return out
    }

    /** The queries of a search call: `queries`, else `query` (for source_check, its `claim`). */
    fun queriesOf(item: ToolItem): List<String> {
        val args = item.args ?: return emptyList()
        val list = (args["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return (list ?: listOfNotNull(args.str("query") ?: args.str("claim"))).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The pages a `fetch_content` call reads: `urls`, else `url`, else the result's `details.urls`. */
    fun fetchUrls(item: ToolItem): List<String> {
        val args = item.args
        val list = (args?.get("urls") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val fromArgs = list?.takeIf { it.isNotEmpty() } ?: listOfNotNull(args?.str("url"))
        return fromArgs.ifEmpty { item.details?.arr("urls")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty() }
    }

    data class Fetched(val url: String, val title: String?, val failed: Boolean)

    /** How each page of a finished `fetch_content` call went. */
    fun fetchOutcome(item: ToolItem, urls: List<String> = fetchUrls(item)): List<Fetched> {
        val text = item.result.orEmpty()
        if (urls.size <= 1) {
            val url = urls.firstOrNull() ?: return emptyList()
            val failed = item.status != ToolStatus.Done || item.details?.str("error") != null
            return listOf(Fetched(url, item.details?.str("title"), failed))
        }
        val lines = text.substringAfter("## Fetched URLs", "").lineSequence().map { it.trim() }.filter { it.startsWith("- ") }.toList()
        return urls.mapIndexed { i, url ->
            val line = lines.getOrNull(i)
            when {
                text.contains("- $url: Error - ") || text.contains("### $url\nError:") -> Fetched(url, null, true)
                line != null -> Fetched(url, fetchedLine.find(line)?.groupValues?.get(1)?.takeIf { it != url }, false)
                else -> Fetched(url, null, item.status != ToolStatus.Done)
            }
        }
    }

    private fun errorOf(item: ToolItem): String =
        item.details?.str("error") ?: item.result?.lineSequence()?.firstOrNull()?.removePrefix("Error: ")?.trim()?.takeIf { it.isNotEmpty() } ?: "Failed"

    /** The host of an http(s) URL, lowercase; null for anything else. */
    fun hostOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        // A host with non-ASCII letters (an IDN) is no URI host: take it from the authority.
        val host = uri.host ?: uri.authority?.substringAfterLast('@')?.let { if (it.startsWith("[")) it else it.substringBefore(':') }
        return host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }

    /** A URL as one source: without its fragment and trailing slash, host in lowercase. */
    fun keyOf(url: String): String {
        val noFragment = url.trim().substringBefore('#')
        val uri = runCatching { URI(noFragment) }.getOrNull()
        val host = uri?.host?.lowercase()
        val rebuilt = if (uri != null && host != null) noFragment.replaceFirst(uri.rawAuthority ?: host, uri.rawAuthority?.lowercase() ?: host) else noFragment
        return rebuilt.trimEnd('/')
    }

    /** What the pill names before the first source arrives: the first query, else the first page's domain. */
    fun pendingLabel(sources: WebSources): String = when {
        sources.queries.isNotEmpty() -> sources.queries.first()
        else -> sources.sources.firstOrNull()?.domain.orEmpty()
    }
}
