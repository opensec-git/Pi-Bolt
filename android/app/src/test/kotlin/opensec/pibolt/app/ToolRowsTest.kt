package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.Ansi
import opensec.pibolt.app.ui.session.AnsiColor
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.LineClass
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.NEXT_EDIT
import opensec.pibolt.app.ui.session.ToolGroupRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.isEmptyResult
import opensec.pibolt.app.ui.session.isFailedStep
import opensec.pibolt.app.ui.session.isUnavailable
import opensec.pibolt.app.ui.session.lineClass
import opensec.pibolt.app.ui.session.newFileDiff
import opensec.pibolt.app.ui.session.parseDiff
import opensec.pibolt.app.ui.session.readText
import opensec.pibolt.app.ui.session.stepDuration
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.app.ui.session.turnSummary
import opensec.pibolt.app.ui.session.withoutExitLine
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRowsTest {
    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    private fun tool(id: String, name: String, status: ToolStatus = ToolStatus.Done, result: String? = null, vararg a: Pair<String, Any>) =
        ToolItem("tool:$id", id, name, args(*a), status = status, result = result)

    // ANSI

    @Test
    fun sgrColoursBecomeSpansAndTheCodesDisappear() {
        val parsed = Ansi.parse("\u001b[1;31mFAIL\u001b[0m parse \u001b[32mok\u001b[39m done")
        assertEquals("FAIL parse ok done", parsed.text)
        assertEquals(2, parsed.spans.size)
        val fail = parsed.spans[0]
        assertEquals(0 until 4, fail.start until fail.end)
        assertEquals(AnsiColor(1), fail.style.fg)
        assertTrue(fail.style.bold)
        val ok = parsed.spans[1]
        assertEquals("ok", parsed.text.substring(ok.start, ok.end))
        assertEquals(AnsiColor(2), ok.style.fg)
    }

    @Test
    fun extendedColoursBrightColoursAndResets() {
        val parsed = Ansi.parse("\u001b[38;5;214ma\u001b[38;2;255;0;128mb\u001b[94mc\u001b[mplain")
        assertEquals("abcplain", parsed.text)
        assertEquals(AnsiColor(214), parsed.spans[0].style.fg)
        assertEquals(AnsiColor(rgb = 0xFF0080), parsed.spans[1].style.fg)
        assertEquals(AnsiColor(12), parsed.spans[2].style.fg)
        assertEquals(3, parsed.spans.size)
        assertEquals(0xFFAF00, Ansi.paletteRgb(214))
        assertEquals(0x080808, Ansi.paletteRgb(232))
    }

    @Test
    fun otherEscapesAreDroppedAndCarriageReturnsRewindTheLine() {
        val parsed = Ansi.parse("\u001b]0;title\u0007\u001b[2Kprogress 10%\rprogress 100%\r\nnext\u001b[1A")
        assertEquals("progress 100%\nnext", parsed.text)
        assertTrue(parsed.spans.isEmpty())
        // A coloured run cut by the rewind keeps nothing past the line start.
        val cut = Ansi.parse("a\n\u001b[31mold\rnew")
        assertEquals("a\nnew", cut.text)
        assertEquals(1, cut.spans.size)
        assertEquals(2 until 5, cut.spans[0].start until cut.spans[0].end)
        // Plain text passes through untouched.
        assertTrue(Ansi.parse("plain\ntext").spans.isEmpty())
    }

    @Test
    fun anUnterminatedEscapeAtTheEndIsDropped() {
        assertEquals("ok", Ansi.parse("ok\u001b[3").text)
        assertEquals("ok", Ansi.parse("ok\u001b]8;;http").text)
    }

    @Test
    fun plainLinesAreClassedByWhatTheySayNotByTheExitCode() {
        assertEquals(LineClass.Fail, lineClass(" FAIL src/parse.test.ts"))
        assertEquals(LineClass.Fail, lineClass("Error: boom"))
        assertEquals(LineClass.Fail, lineClass("  ✗ depth guard"))
        assertEquals(LineClass.Pass, lineClass("ok tokenize"))
        assertEquals(LineClass.Pass, lineClass(" ✓ lexer"))
        assertNull(lineClass("running 3 tests"))
        assertNull(lineClass("no errors found"))
        assertEquals("line 1\nline 2", withoutExitLine("line 1\nline 2\n\nCommand exited with code 1"))
    }

    // Diffs

    @Test
    fun piDiffsGetOldAndNewNumbers() {
        // Pi numbers additions in the new file, deletions and context in the old one.
        val diff = """
             1 import x
            -2 const a = 1;
            +2 const a = 2;
            +3 const b = 3;
             3 run(a)
               ...
             9 end
            -10 gone
            +11 last
        """.trimIndent()
        val rows = parseDiff(diff)
        fun row(i: Int) = Triple(rows[i].kind, rows[i].old, rows[i].number)
        assertEquals(Triple(DiffKind.Context, "1", "1"), row(0))
        assertEquals(Triple(DiffKind.Del, "2", ""), row(1))
        assertEquals(Triple(DiffKind.Add, "", "2"), row(2))
        assertEquals(Triple(DiffKind.Add, "", "3"), row(3))
        // One line removed, two added: context after it is one further on in the new file.
        assertEquals(Triple(DiffKind.Context, "3", "4"), row(4))
        assertEquals(DiffKind.Skip, rows[5].kind)
        assertEquals(Triple(DiffKind.Context, "9", "10"), row(6))
        assertEquals(Triple(DiffKind.Del, "10", ""), row(7))
        assertEquals(Triple(DiffKind.Add, "", "11"), row(8))
        assertEquals("const a = 2;", rows[2].text)
    }

    @Test
    fun unifiedDiffsCountFromTheirHunksAndDropFileLines() {
        val diff = "--- a/src/p.ts\n+++ b/src/p.ts\n@@ -10,3 +10,4 @@ fun parse\n keep\n-old\n+new\n+more\n tail\n"
        val rows = parseDiff(diff)
        assertEquals(DiffKind.Hunk, rows[0].kind)
        assertEquals(listOf("10" to "10", "11" to "", "" to "11", "" to "12", "12" to "13"), rows.drop(1).map { it.old to it.number })
        assertFalse(rows.any { it.text.startsWith("++") || it.text.startsWith("--") })
    }

    @Test
    fun aMergedChangeNumbersEachEditFromScratch() {
        val first = "+1 a\n+2 b"
        val second = "-1 a\n+1 z\n 2 b"
        val rows = parseDiff(first + "\n" + NEXT_EDIT + "\n" + second)
        assertEquals("2", rows.last().number)
        assertEquals("2", rows.last().old)
        assertEquals(listOf("+1 x", "+2 ", "+3 y"), newFileDiff("x\n\ny\n").lines())
    }

    // Labels and step noise

    @Test
    fun verbsReadAsDoneAndReadsCarryTheirRange() {
        val read = tool("r", "read", ToolStatus.Done, "x", "path" to "src/app.ts", "offset" to 10, "limit" to 71)
        assertEquals("src/app.ts:10-80", toolLabel(read).detail)
        val bash = tool("b", "bash", ToolStatus.Done, "ok", "command" to "npm test")
        assertEquals("Ran", toolLabel(bash).past)
        assertEquals("Run", toolLabel(bash).verb)
        assertEquals("Edited", toolLabel(tool("e", "edit", a = arrayOf("path" to "a.ts"))).past)
        assertEquals("line\n", readText("line\n\n[Showing lines 1-1 of 9. Use offset=2 to continue.]") + "\n")
    }

    @Test
    fun durationsUnderASecondAreQuiet() {
        assertNull(stepDuration(12))
        assertNull(stepDuration(999))
        assertNull(stepDuration(null))
        assertEquals("1.4s", stepDuration(1_400))
    }

    @Test
    fun unavailableToolsAndEmptyListingsAreNotFailures() {
        val missing = tool("l", "ls", ToolStatus.Error, "Tool ls not found", "path" to ".")
        assertTrue(isUnavailable(missing))
        assertFalse(isFailedStep(missing))
        val empty = tool("f", "find", ToolStatus.Error, "No files found matching pattern", "pattern" to "*.kt")
        assertTrue(isEmptyResult(empty))
        assertFalse(isFailedStep(empty))
        assertTrue(isEmptyResult(tool("g", "grep", ToolStatus.Done, "No matches found", "pattern" to "x")))
        assertFalse(isEmptyResult(tool("g2", "grep", ToolStatus.Done, "src/a.ts:3: x not found here", "pattern" to "x").copy(result = "src/a.ts:3: " + "x".repeat(300))))
        val real = tool("b", "bash", ToolStatus.Error, "boom\n\nCommand exited with code 1", "command" to "make")
        assertTrue(isFailedStep(real))
    }

    // Row grouping

    private fun turnOf(vararg tools: ToolItem): List<ChatItem> = listOf(UserItem("u", "go"), *tools)

    @Test
    fun todoUpdatesFoldIntoOneRowAtAnyLength() {
        val todos = (1..7).map { tool("t$it", "todo", ToolStatus.Done, "ok", "action" to "update", "id" to it, "status" to "completed") }
        val items = turnOf(*todos.toTypedArray(), tool("b", "bash", ToolStatus.Done, "ok", "command" to "ls"))
        val rows = TranscriptModel.rows(items, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        val groups = rows.filterIsInstance<ToolGroupRow>()
        assertEquals(1, groups.size)
        assertEquals(7, groups[0].tools.size)
        assertEquals(1, rows.filterIsInstance<ToolRow>().size)
        // A lone todo update is a group too: it never reads as a step of its own.
        val one = TranscriptModel.rows(turnOf(todos[0]), liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(1, one.filterIsInstance<ToolGroupRow>().size)
        assertTrue(one.none { it is ToolRow })
    }

    @Test
    fun twoReadsStayRowsAndThreeGroup() {
        fun read(id: String) = tool(id, "read", ToolStatus.Done, "x", "path" to "$id.ts")
        val two = TranscriptModel.rows(turnOf(read("a"), read("b")), liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(2, two.filterIsInstance<ToolRow>().size)
        val three = TranscriptModel.rows(turnOf(read("a"), read("b"), read("c")), liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(1, three.filterIsInstance<ToolGroupRow>().size)
    }

    @Test
    fun anUnavailableToolThatPiWorkedAroundIsHidden() {
        val missing = tool("l", "ls", ToolStatus.Error, "Tool ls not found", "path" to ".")
        val bash = tool("b", "bash", ToolStatus.Done, "a.txt", "command" to "ls")
        val rows = TranscriptModel.rows(turnOf(missing, bash), liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(listOf("tool:b"), rows.filterIsInstance<ToolRow>().map { it.key })
        // Still the last step: it stays, as a quiet line.
        val alone = TranscriptModel.rows(turnOf(missing), liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(listOf("tool:l"), alone.filterIsInstance<ToolRow>().map { it.key })
    }

    @Test
    fun theFoldSaysWhatTheTurnDid() {
        val items = turnOf(
            tool("w", "write", ToolStatus.Done, "ok", "path" to "a.ts", "content" to "x\ny\n"),
            tool("e", "edit", ToolStatus.Done, "ok", "path" to "b.ts"),
            tool("b1", "bash", ToolStatus.Error, "1 failed\n\nCommand exited with code 1", "command" to "npm test"),
            tool("b2", "bash", ToolStatus.Done, "ok", "command" to "npm test"),
        )
        val fold = TranscriptModel.rows(items, liveTurn = false, expanded = emptySet(), durations = mapOf("u" to 72_000L), md = MdCache()).filterIsInstance<FoldRow>().single()
        // The edit has no diff, so only the write counts as a changed file.
        assertEquals("Edited 1 file · ran 2 commands", fold.summary)
        assertEquals(1, fold.failedSteps)
        // A failing test run the agent then dealt with is not a failed turn.
        assertFalse(fold.endedInFailure)
        val errored = TranscriptModel.rows(items + NoticeItem("n", NoticeKind.Error, "Error"), liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertTrue(errored.filterIsInstance<FoldRow>().single().endedInFailure)
    }

    @Test
    fun summariesCountDistinctReadsAndFallBackToSteps() {
        fun read(id: String, path: String) = tool(id, "read", ToolStatus.Done, "x", "path" to path)
        assertEquals("Read 2 files · 1 search", turnSummary(listOf(read("a", "x.ts"), read("b", "x.ts"), read("c", "y.ts"), tool("g", "grep", ToolStatus.Done, "", "pattern" to "x")), files = 0, thoughts = 0))
        assertEquals("Thought", turnSummary(emptyList(), files = 0, thoughts = 1))
        assertEquals("1 step", turnSummary(listOf(tool("t", "todo", ToolStatus.Done, "", "action" to "list")), files = 0, thoughts = 0))
    }

    @Test
    fun finishedRowsCompareEqualSoTheyCanSkip() {
        val items = turnOf(tool("b", "bash", ToolStatus.Done, "ok", "command" to "ls"))
        val md = MdCache()
        val a = TranscriptModel.rows(items, liveTurn = false, expanded = setOf("u"), durations = emptyMap(), md = md)
        val b = TranscriptModel.rows(items, liveTurn = false, expanded = setOf("u"), durations = emptyMap(), md = md)
        assertEquals(a, b)
        // The fold's files are the same instance across rebuilds (cached per turn), not merged again.
        assertTrue(a.filterIsInstance<FoldRow>().single().files === b.filterIsInstance<FoldRow>().single().files)
    }
}
