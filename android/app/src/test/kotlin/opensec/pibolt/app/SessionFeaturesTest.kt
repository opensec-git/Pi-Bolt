package opensec.pibolt.app

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.data.QueueMode
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.ModelGroups
import opensec.pibolt.app.ui.session.TextRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.UserRow
import opensec.pibolt.app.ui.session.features.FileMentions
import opensec.pibolt.app.ui.session.features.ImageZoom
import opensec.pibolt.app.ui.session.features.MessageText
import opensec.pibolt.app.ui.session.features.SessionMarkdown
import opensec.pibolt.app.ui.session.features.SessionStats
import opensec.pibolt.app.ui.session.features.TranscriptSearch
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import java.nio.file.Files
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionFeaturesTest {
    private fun bash(key: String, command: String, result: String, status: ToolStatus = ToolStatus.Done) =
        ToolItem(key, key, "bash", JsonObject(mapOf("command" to JsonPrimitive(command))), status = status, result = result)

    private val items: List<ChatItem> = listOf(
        UserItem("m0", "find the parser bug"),
        ThinkingItem("m1.0", "The tokenizer looks wrong", streaming = false),
        bash("tool:a", "rg tokenize", "src/parser.ts: tokenize()"),
        AssistantTextItem("m1.2", "Fixed the **parser**.", streaming = false),
        UserItem("m2", "thanks"),
        AssistantTextItem("m3.0", "You're welcome.", streaming = false),
    )

    // @ mentions

    @Test
    fun mentionTokenIsTheWordAfterAnAtSign() {
        assertEquals(FileMentions.Token(5, 9, "src"), FileMentions.tokenAt("read @src", 9))
        assertEquals(FileMentions.Token(0, 1, ""), FileMentions.tokenAt("@", 1))
        assertEquals(FileMentions.Token(5, 14, "sr"), FileMentions.tokenAt("read @src/a.ts now", 8))
        assertNull(FileMentions.tokenAt("mail me@example.com", 19))
        assertNull(FileMentions.tokenAt("read @src now", 13))
        assertNull(FileMentions.tokenAt("no mention", 4))
    }

    @Test
    fun mentionInsertReplacesTheTokenAndMovesTheCursor() {
        val token = FileMentions.tokenAt("read @pa and", 8)!!
        assertEquals("read @src/parser.ts and" to 20, FileMentions.insert("read @pa and", token, "src/parser.ts"))
        val end = FileMentions.tokenAt("see @x", 6)!!
        assertEquals("see @\"my file.md\" " to 18, FileMentions.insert("see @x", end, "my file.md"))
    }

    @Test
    fun mentionRankPrefersTheFileName() {
        val paths = listOf("docs/parser-notes/readme.md", "src/parser.ts", "src/", "test/parser.test.ts", "src/lexer.ts")
        assertEquals("src/parser.ts", FileMentions.rank("parser", paths).first())
        assertEquals(listOf("src/lexer.ts"), FileMentions.rank("lexer", paths))
        assertEquals("src/", FileMentions.rank("", paths).first())
        assertTrue(FileMentions.rank("zzz", paths).isEmpty())
    }

    @Test
    fun walkListsFilesAndSkipsDependencyFolders() {
        val root = Files.createTempDirectory("mentions").toFile()
        try {
            root.resolve("src").mkdirs()
            root.resolve("src/a.ts").writeText("a")
            root.resolve("node_modules/x").mkdirs()
            root.resolve("node_modules/x/i.js").writeText("i")
            root.resolve(".git").mkdirs()
            root.resolve("README.md").writeText("r")
            assertEquals(listOf("README.md", "src/", "src/a.ts"), FileMentions.walk(root))
            assertEquals(2, FileMentions.walk(root, max = 2).size)
        } finally {
            root.deleteRecursively()
        }
    }

    // Statistics

    private val statsJson = ProtocolJson.parseToJsonElement(
        """{"sessionFile":"/s.jsonl","sessionId":"abc","userMessages":5,"assistantMessages":5,"toolCalls":12,"toolResults":12,"totalMessages":22,
           "tokens":{"input":50000,"output":10000,"cacheRead":40000,"cacheWrite":5000,"total":105000},"cost":0.45,
           "contextUsage":{"tokens":60000,"contextWindow":200000,"percent":30}}""",
    ).jsonObject

    @Test
    fun statsReadTheWholeSession() {
        val s = assertNotNull(SessionStats.from(statsJson))
        assertEquals(12, s.toolCalls)
        assertEquals(44, s.cacheHitPercent)
        assertEquals(0.3f, s.contextFraction)
        assertEquals("↑50k ↓10k R40k W5.0k $0.450", s.footer)
        assertEquals("30.0% of 200k", s.contextLabel)
    }

    @Test
    fun statsAfterACompactionHaveNoPercentYet() {
        val json = ProtocolJson.parseToJsonElement("""{"tokens":{"input":0,"output":0},"cost":0,"contextUsage":{"tokens":null,"contextWindow":128000,"percent":null}}""").jsonObject
        val s = SessionStats.from(json)!!
        assertNull(s.contextFraction)
        assertNull(s.cacheHitPercent)
        assertEquals("? of 128k", s.contextLabel)
        assertEquals("↑0 ↓0", s.footer)
    }

    @Test
    fun costIsShortAndHonest() {
        assertEquals("$0", SessionStats.formatCost(0.0))
        assertEquals("<$0.001", SessionStats.formatCost(0.0004))
        assertEquals("$0.452", SessionStats.formatCost(0.4521))
        assertEquals("$12.30", SessionStats.formatCost(12.3))
    }

    // Search

    @Test
    fun searchFindsTextToolsAndThinkingInOrder() {
        val hits = TranscriptSearch.search(items, "parser")
        assertEquals(listOf("m0", "tool:a", "m1.2"), hits.map { it.itemKey })
        assertTrue(hits.all { it.turnKey == "m0" })
        assertEquals(listOf("m1.0", "tool:a"), TranscriptSearch.search(items, "TOKENIZ").map { it.itemKey })
        assertTrue(TranscriptSearch.search(items, "  ").isEmpty())
    }

    @Test
    fun aHitInAFoldedTurnLandsOnTheFoldThenOnItsRow() {
        val hit = TranscriptSearch.search(items, "rg tokenize").single()
        val folded = TranscriptModel.rows(items, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        val at = TranscriptSearch.rowIndex(folded, hit)
        assertTrue(folded[at] is FoldRow)
        val open = TranscriptModel.rows(items, liveTurn = false, expanded = setOf("m0"), durations = emptyMap(), md = MdCache())
        assertTrue(open[TranscriptSearch.rowIndex(open, hit)] is ToolRow)
        val text = TranscriptSearch.search(items, "welcome").single()
        assertTrue(open[TranscriptSearch.rowIndex(open, text)] is TextRow)
        assertEquals(open.size - 3, TranscriptSearch.listIndex(open.size, 3))
    }

    @Test
    fun snippetCutsAroundTheMatch() {
        val text = "a".repeat(100) + "needle" + "b".repeat(200)
        val snippet = TranscriptSearch.snippet(text, 100, 6)
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…") && "needle" in snippet)
        assertEquals("short needle", TranscriptSearch.snippet("short\n needle", 7, 6))
    }

    // User shell commands

    @Test
    fun aShellCommandStaysVisibleAfterTheAnswer() {
        val withShell = items + bash("shell:1", "git status", "clean")
        val rows = TranscriptModel.rows(withShell, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(listOf("shell:1"), rows.filterIsInstance<ToolRow>().map { it.key })
        // History form (`bashExecution` messages) behaves the same.
        assertTrue(TranscriptModel.isUserShell(bash("m9.bash", "ls", "")))
        assertFalse(TranscriptModel.isUserShell(bash("tool:x", "ls", "")))
        // Only the agent's own steps fold.
        val fold = rows.filterIsInstance<FoldRow>().first()
        assertEquals(2, fold.steps)
    }

    // Export and message text

    @Test
    fun markdownExportHasMessagesToolsAndNotices() {
        val md = SessionMarkdown.render(items + NoticeItem("m4", NoticeKind.Compaction, "Compacted 120k tokens", "Summary"), "Fix parser", "anthropic/claude", Date(0))
        assertTrue(md.startsWith("# Fix parser\n\n_anthropic/claude · "))
        assertTrue("## User\n\nfind the parser bug" in md)
        assertTrue("## Pi\n\n**bash** `rg tokenize`" in md)
        assertTrue("```text\nsrc/parser.ts: tokenize()\n```" in md)
        assertTrue("> **Compacted 120k tokens**\n>\n> Summary" in md)
        assertFalse("tokenizer looks wrong" in md)
        assertTrue("tokenizer looks wrong" in SessionMarkdown.render(items, "x", null, includeThinking = true))
        assertEquals("fix-the-parser.md", SessionMarkdown.fileName("Fix the parser!", "md"))
        assertEquals("pi-session.jsonl", SessionMarkdown.fileName("???", "jsonl"))
    }

    @Test
    fun longToolOutputIsCutInTheExport() {
        val big = bash("tool:b", "cat log", "x".repeat(SessionMarkdown.MAX_OUTPUT + 10))
        val md = SessionMarkdown.render(listOf(UserItem("u", "go"), big), "t", null)
        assertTrue("… (10 more characters)" in md)
    }

    @Test
    fun plainTextDropsMarkdownButKeepsCode() {
        val md = "# Title\n\nSome **bold**, *italic*, `code` and [a link](https://x.dev).\n\n- one\n- two\n\n```kotlin\nval x = 1 // *not* emphasis\n```\n\n> quoted"
        assertEquals("Title\n\nSome bold, italic, code and a link (https://x.dev).\n\n• one\n• two\n\nval x = 1 // *not* emphasis\n\nquoted", MessageText.plain(md))
        assertEquals("snake_case_name stays", MessageText.plain("snake_case_name stays"))
    }

    @Test
    fun promptOfFindsTheMessageAReplyAnswers() {
        val repeated = listOf(UserItem("u1", "again"), AssistantTextItem("a1", "one", false), UserItem("u2", "again"), AssistantTextItem("a2", "two", false))
        assertEquals(UserItem("u2", "again") to 1, MessageText.promptOf(repeated, "a2#0"))
        assertEquals(UserItem("u1", "again") to 0, MessageText.promptOf(repeated, "a1"))
        assertNull(MessageText.promptOf(listOf(AssistantTextItem("a", "x", false)), "a"))
    }

    // Engine settings

    @Test
    fun agentDefaultsReadAndMergeSettingsJson() {
        val json = ProtocolJson.parseToJsonElement(
            """{"defaultProvider":"fake","defaultModel":"fake-model","steeringMode":"all","compaction":{"enabled":false,"reserveTokens":9000},"quietStartup":true}""",
        ).jsonObject
        val d = AgentDefaults.from(json)
        assertEquals(QueueMode.All, d.steeringMode)
        assertEquals(QueueMode.OneAtATime, d.followUpMode)
        assertFalse(d.autoCompaction)
        assertTrue(d.autoRetry)
        assertEquals("fake/fake-model", d.defaultModel)
        val merged = AgentDefaults.merge(json, d.copy(autoCompaction = true, defaultThinking = "high", followUpMode = QueueMode.All))
        assertEquals("high", merged["defaultThinkingLevel"]!!.jsonPrimitive.content)
        assertEquals("all", merged["followUpMode"]!!.jsonPrimitive.content)
        assertTrue(merged["compaction"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        // Everything else is kept, nested keys included.
        assertEquals("9000", merged["compaction"]!!.jsonObject["reserveTokens"]!!.jsonPrimitive.content)
        assertEquals("fake-model", merged["defaultModel"]!!.jsonPrimitive.content)
        assertTrue(merged["quietStartup"]!!.jsonPrimitive.boolean)
        assertEquals(AgentDefaults(), AgentDefaults.from(null))
    }

    // Image viewer and model picker

    @Test
    fun zoomStaysWithinLimitsAndPanStaysOnTheImage() {
        assertEquals(ImageZoom.MAX, ImageZoom.scale(5f, 3f))
        assertEquals(ImageZoom.MIN, ImageZoom.scale(1f, 0.5f))
        assertEquals(ImageZoom.DOUBLE_TAP, ImageZoom.toggle(1f))
        assertEquals(ImageZoom.MIN, ImageZoom.toggle(3f))
        assertEquals(Offset(50f, -100f), ImageZoom.clampOffset(Offset.Zero, Offset(500f, -500f), 2f, 100f, 200f))
        assertEquals(Offset.Zero, ImageZoom.clampOffset(Offset.Zero, Offset(10f, 10f), 1f, 100f, 200f))
    }

    @Test
    fun modelsGroupByMakerWithTheCurrentFirst() {
        fun m(provider: String, id: String) = ModelInfo(id, id, provider, reasoning = false, contextWindow = null)
        val models = listOf(
            m("openai", "gpt"),
            m("anthropic", "sonnet"),
            m("anthropic", "opus"),
            m("openrouter", "anthropic/claude-sonnet-4.5"),
            m("google", "gemini-2.5-pro"),
            m("openrouter", "google/gemma-3-27b-it"),
            m("zeta-labs", "z"),
        )
        val groups = ModelGroups.of(models, m("anthropic", "opus"))
        assertEquals(listOf("Current", "Anthropic", "Google", "OpenAI", "Zeta labs"), groups.map { it.first })
        // Claude through OpenRouter sits with Anthropic's own; Gemma with Gemini under Google.
        assertEquals(listOf("sonnet", "anthropic/claude-sonnet-4.5"), groups[1].second.map { it.id })
        assertEquals(listOf("gemini-2.5-pro", "google/gemma-3-27b-it"), groups[2].second.map { it.id })
        assertEquals(listOf("Anthropic", "Google", "OpenAI", "Zeta labs"), ModelGroups.of(models, null).map { it.first })
        assertEquals("Other", ModelGroups.title(m("", "mystery")))
    }

    @Test
    fun userRowsStayFirstInATurn() {
        val rows = TranscriptModel.rows(items, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertTrue(rows.first() is UserRow)
    }
}
