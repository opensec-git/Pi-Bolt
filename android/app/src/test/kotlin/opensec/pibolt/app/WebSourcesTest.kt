package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.SourcesRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.app.ui.session.turnSummary
import opensec.pibolt.app.ui.session.web.SourceState
import opensec.pibolt.app.ui.session.web.WebTools
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Sources pill's data (ui/session/web/WebSources.kt) from real pi-web-access 0.44.0 results: the RPC events of the
 * engine with the bundled extension (fixtures/engine), replayed through the session reducer as the app receives them.
 */
class WebSourcesTest {
    /** The tool rows after each event of [name], by toolCallId, in call order. */
    private fun replay(name: String, upTo: (JsonObject) -> Boolean = { false }): List<ToolItem> {
        var state = SessionState(hello = true, items = listOf(UserItem("u1", "go")))
        for (event in EngineFixture.events(name)) {
            state = SessionReducer.reduce(state, event)
            if (upTo(event)) break
        }
        return state.allItems.filterIsInstance<ToolItem>()
    }

    private fun JsonObject.type() = (this["type"] as JsonPrimitive).content

    private fun JsonObject.tool() = (this["toolName"] as? JsonPrimitive)?.content

    @Test
    fun aSearchListsItsSourcesWithTitlesFromTheRealResult() {
        val search = replay("project-web-ask").first { it.name == "web_search" }
        assertEquals(ToolStatus.Done, search.status)
        val web = WebTools.of(listOf(search))
        assertEquals(
            listOf(
                "Pi Coding Agent" to "https://pi.dev/",
                "Use Pi in the terminal" to "https://pi.dev/docs/latest/usage",
                "Quickstart" to "https://pi.dev/docs/latest/quickstart",
                "Pi" to "https://pi.dev/docs/latest",
                "Command Line" to "https://pi.dev/docs/latest/cli",
            ),
            web.sources.map { it.title to it.url },
        )
        assertTrue(web.sources.all { it.host == "pi.dev" && it.domain == "pi.dev" && it.state == SourceState.Found })
        assertEquals(false, web.searching)
        assertNull(web.error)
        // The answer section also names each source ("Source: title (url)"): still five, not ten.
        assertEquals(5, WebTools.searchResults(search.result!!).size)
    }

    @Test
    fun severalQueriesGiveAllTheirSourcesOnce() {
        val multi = replay("project-web-ask").filter { it.name == "web_search" }[1]
        assertEquals(listOf("kotlin coroutines flow", "jetpack compose animation"), WebTools.queriesOf(multi))
        val web = WebTools.of(listOf(multi))
        assertEquals(10, web.sources.size)
        assertEquals("https://kotlinlang.org/docs/coroutines-flow.html", web.sources[0].url)
        assertEquals("Flows | Kotlin Documentation", web.sources[0].title)
        assertTrue(web.sources.any { it.host == "developer.android.com" }, web.sources.map { it.host }.toString())
    }

    @Test
    fun whileASearchRunsThePillHasItsQueryAndNoSources() {
        val running = replay("project-web-ask") { it.type() == "tool_execution_update" && it.tool() == "web_search" }
        val web = WebTools.of(running.filter(WebTools::isSourceTool))
        assertTrue(web.searching)
        assertEquals(listOf("Pi coding agent terminal"), web.queries)
        assertTrue(web.sources.isEmpty())
        assertEquals("Pi coding agent terminal", WebTools.pendingLabel(web))
    }

    @Test
    fun aPageBeingReadIsFetchingThenReadWithItsTitle() {
        var seenStart = false
        val reading = replay("project-web-ask") { e ->
            if (e.type() == "tool_execution_start" && e.tool() == "fetch_content") seenStart = true
            seenStart
        }.last()
        assertEquals(ToolStatus.Running, reading.status)
        assertEquals(listOf(SourceState.Fetching), WebTools.of(listOf(reading)).sources.map { it.state })

        val fetches = replay("project-web-ask").filter { it.name == "fetch_content" }
        val one = WebTools.of(listOf(fetches[0])).sources.single()
        assertEquals(SourceState.Read, one.state)
        assertEquals("Flows | Kotlin", one.title)
        assertEquals("kotlinlang.org", one.host)
    }

    @Test
    fun ofSeveralPagesTheOneThatFailedIsMarked() {
        val many = replay("project-web-ask").filter { it.name == "fetch_content" }[1]
        val web = WebTools.of(listOf(many))
        assertEquals(listOf("https://kotlinlang.org/docs/coroutines-flow.html", "https://nonexistent.invalid/x"), web.sources.map { it.url })
        assertEquals(listOf(SourceState.Read, SourceState.Failed), web.sources.map { it.state })
        assertEquals("Flows | Kotlin", web.sources[0].title)
        assertNull(web.error)
    }

    @Test
    fun aChatsSearchAndReadParseTheSame() {
        val tools = replay("chat-web-ask").filter(WebTools::isSourceTool)
        val web = WebTools.of(tools)
        // The page read was not among the search's results: it is added after them, read.
        assertEquals(6, web.sources.size)
        assertEquals(SourceState.Read, web.sources.last().state)
        assertEquals(5, web.sources.count { it.state == SourceState.Found })
    }

    @Test
    fun aRealModelsParallelSearchAndReadMakeOnePill() {
        // real-chat: the model called web_search and fetch_content in one message (both running at once).
        val running = replay("real-chat") { it.type() == "tool_execution_start" && it.tool() == "fetch_content" }.filter(WebTools::isSourceTool)
        val during = WebTools.of(running)
        assertTrue(during.searching && during.fetching)
        assertEquals(listOf("https://kotlinlang.org/docs/releases.html"), during.sources.map { it.url })
        val tools = replay("real-chat").filter(WebTools::isSourceTool)
        val web = WebTools.of(tools)
        assertEquals(
            listOf("devtalk.com", "daily.steinslab.io", "releases.fru.dev", "medium.com", "tech-insider.org", "kotlinlang.org"),
            web.sources.map { it.host },
        )
        assertEquals("JetBrains: Kotlin 2.4.21 (Oct 8, 2026)", web.sources[2].title)
        assertEquals(SourceState.Read to "Kotlin release process | Kotlin", web.sources.last().state to web.sources.last().title)
        val items: List<ChatItem> = listOf(UserItem("u1", "kotlin?")) + tools + AssistantTextItem("a", "Kotlin 2.4.21.", false)
        assertEquals(1, TranscriptModel.rows(items, true, emptySet(), emptyMap(), MdCache()).count { it is SourcesRow })
    }

    @Test
    fun aReadStillBeingWrittenIsNotAnEmptyResult() {
        // Xiaomi round: "No sources found" flashed while fetch_content's call was still being written (no URL yet).
        val writing = ToolItem("tool:f", "f", "fetch_content", null, argsText = "{\"ur", status = ToolStatus.Streaming)
        val web = WebTools.of(listOf(writing))
        assertTrue(web.active && web.sources.isEmpty() && !web.searching)
        assertNull(web.error)
        val done = WebTools.of(listOf(writing.copy(args = obj("url" to "https://pi.dev/"), status = ToolStatus.Error, result = "Error: Failed to resolve")))
        assertTrue(!done.active)
        // Finished: the failed page is listed as failed (not an empty pill).
        assertEquals(listOf(SourceState.Failed), done.sources.map { it.state })
    }

    @Test
    fun readingASearchResultMarksThatSourceInsteadOfAddingIt() {
        val search = replay("project-web-ask").first { it.name == "web_search" }
        val fetch = ToolItem("tool:f", "f", "fetch_content", obj("url" to "https://pi.dev/docs/latest/usage#install"), status = ToolStatus.Running)
        val web = WebTools.of(listOf(search, fetch))
        assertEquals(5, web.sources.size)
        assertEquals(SourceState.Fetching, web.sources[1].state)
        assertEquals("Use Pi in the terminal", web.sources[1].title)
        val failed = WebTools.of(listOf(search, fetch.copy(status = ToolStatus.Error, result = "Error: Failed to resolve")))
        assertEquals(SourceState.Failed, failed.sources[1].state)
    }

    @Test
    fun aSearchThatFailedAndSourceChecksQualityTags() {
        val failed = ToolItem("tool:s", "s", "web_search", obj("query" to "x"), status = ToolStatus.Error, result = "Error: No query provided.")
        assertEquals("No query provided.", WebTools.of(listOf(failed)).error)
        val check = ToolItem(
            "tool:c", "c", "source_check", obj("claim" to "Pi is minimal"), status = ToolStatus.Done,
            result = "# Source check: Pi is minimal\n\n## Sources\n1. [primary] Pi\n   https://pi.dev/\n2. [secondary] A blog\n   https://blog.example.org/pi\n",
        )
        assertEquals(listOf("Pi" to "https://pi.dev/", "A blog" to "https://blog.example.org/pi"), WebTools.searchResults(check.result!!))
        assertEquals(listOf("Pi is minimal"), WebTools.queriesOf(check))
    }

    @Test
    fun theTranscriptShowsConsecutiveWebStepsAsOnePillAndAFoldedTurnKeepsIt() {
        val tools = replay("project-web-ask")
        val search = tools.first { it.name == "web_search" }
        val fetch = tools.first { it.name == "fetch_content" }
        val items: List<ChatItem> = listOf(UserItem("u1", "find it"), search, AssistantTextItem("a0", "", false), fetch, AssistantTextItem("a1", "Here is what I found.", false))
        // Live: one pill for the two steps, the reply after it.
        val live = TranscriptModel.rows(items, true, emptySet(), emptyMap(), MdCache())
        val pills = live.filterIsInstance<SourcesRow>()
        assertEquals(1, pills.size)
        assertEquals(listOf(search, fetch), pills[0].tools)
        assertEquals(6, pills[0].sources.sources.size)
        assertTrue(live.none { it is ToolRow })
        // Finished and folded: the fold counts the tools, and the turn's sources stay visible after the answer.
        val folded = TranscriptModel.rows(items, false, emptySet(), emptyMap(), MdCache())
        val fold = folded.filterIsInstance<FoldRow>().single()
        assertEquals(2, fold.tools)
        assertEquals("Searched the web · read 1 page", fold.summary)
        val after = folded.filterIsInstance<SourcesRow>().single()
        assertEquals("sources:u1", after.key)
        assertTrue(folded.indexOf(after) > folded.indexOfFirst { it is opensec.pibolt.app.ui.session.TextRow })
        // Opened fold: the pill sits on its rail, and the turn's pill after the answer is not repeated.
        val open = TranscriptModel.rows(items, false, setOf("u1"), emptyMap(), MdCache(), openGroups = setOf("sources:${search.key}"))
        val inFold = open.filterIsInstance<SourcesRow>().single()
        assertTrue(inFold.rail != null && inFold.expanded)
        assertTrue(TranscriptModel.keysUnique(open))
    }

    @Test
    fun webStepsAreLabelled() {
        val tools = replay("project-web-ask")
        assertEquals("Search the web", toolLabel(tools[0]).verb)
        assertEquals("Pi coding agent terminal", toolLabel(tools[0]).detail)
        assertEquals("kotlin coroutines flow · jetpack compose animation", toolLabel(tools[1]).detail)
        assertEquals("https://kotlinlang.org/docs/coroutines-flow.html", toolLabel(tools[2]).detail)
        assertEquals("Asked", toolLabel(tools.last()).past)
        assertEquals("Which database should we use?", toolLabel(tools.last()).detail)
        assertEquals("Searched the web 2 times · read 2 pages", turnSummary(tools.filter(WebTools::isSourceTool), 0, 0))
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
}
