package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.ToolKind
import opensec.pibolt.app.ui.session.collapseContext
import opensec.pibolt.app.ui.session.contextPercent
import opensec.pibolt.app.ui.session.diffStats
import opensec.pibolt.app.ui.session.exitCodeOf
import opensec.pibolt.app.ui.session.formatElapsed
import opensec.pibolt.app.ui.session.formatWorked
import opensec.pibolt.app.ui.session.head
import opensec.pibolt.app.ui.session.lineCount
import opensec.pibolt.app.ui.session.looksLikeCommand
import opensec.pibolt.app.ui.session.parseDiff
import opensec.pibolt.app.ui.session.tail
import opensec.pibolt.app.ui.session.thinkingChipLabel
import opensec.pibolt.app.ui.session.toolBody
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolFormatTest {
    private fun tool(name: String, status: ToolStatus = ToolStatus.Done, args: Map<String, String>? = null, argsText: String = "", result: String? = null, output: String = "") =
        ToolItem("tool:1", "1", name, args?.let { a -> JsonObject(a.mapValues { JsonPrimitive(it.value) }) }, argsText, status, output, result)

    @Test
    fun parsesPiEditDiffs() {
        val diff = " 1 keep\n-2 old line\n+2 new line\n+3 added\n   ...\n 9 tail"
        val lines = parseDiff(diff)
        assertEquals(listOf(DiffKind.Context, DiffKind.Del, DiffKind.Add, DiffKind.Add, DiffKind.Skip, DiffKind.Context), lines.map { it.kind })
        // A deletion carries its old-file number only.
        assertEquals("2", lines[1].old)
        assertEquals("", lines[1].number)
        assertEquals("old line", lines[1].text)
        assertEquals("new line", lines[2].text)
        val stats = diffStats(lines)
        assertEquals(2, stats.added)
        assertEquals(1, stats.removed)
    }

    @Test
    fun parsesPaddedNumbersAndUnifiedDiffs() {
        val pi = parseDiff("+ 7 x")
        assertEquals("7", pi[0].number)
        assertEquals("x", pi[0].text)
        val lines = parseDiff("@@ -1 +1 @@\n-plain\n+new")
        assertEquals(DiffKind.Hunk, lines[0].kind)
        assertEquals(DiffKind.Del, lines[1].kind)
        assertEquals("plain", lines[1].text)
        assertEquals("1", lines[1].old)
        assertEquals("1", lines[2].number)
    }

    @Test
    fun collapsesLongUnchangedRuns() {
        val ctx = (1..20).joinToString("\n") { " $it c$it" }
        val lines = parseDiff("$ctx\n+21 new\n$ctx")
        val collapsed = collapseContext(lines, keep = 3)
        assertEquals(DiffKind.Skip, collapsed.first().kind)
        assertEquals("17", collapsed.first().number)
        assertEquals(DiffKind.Skip, collapsed.last().kind)
        assertEquals(1 + 3 + 1 + 3 + 1, collapsed.size)
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:07", formatElapsed(7_400))
        assertEquals("2:41", formatElapsed(161_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
        assertEquals("12s", formatWorked(12_300))
        assertEquals("2m 5s", formatWorked(125_000))
        assertEquals("1s", formatWorked(10))
    }

    @Test
    fun contextAndLabels() {
        assertEquals(41, contextPercent(82_000, 200_000))
        assertEquals(100, contextPercent(300_000, 200_000))
        assertNull(contextPercent(10, null))
        assertEquals("Thinking off", thinkingChipLabel("off"))
        assertEquals("Thinking: high", thinkingChipLabel("high"))
    }

    @Test
    fun exitCodes() {
        assertEquals(0, exitCodeOf(tool("bash", result = "ok")))
        assertEquals(2, exitCodeOf(tool("bash", ToolStatus.Error, result = "boom\n\nCommand exited with code 2")))
        assertNull(exitCodeOf(tool("bash", ToolStatus.Running)))
    }

    @Test
    fun commandHeuristic() {
        assertTrue(looksLikeCommand("rm -rf build"))
        assertTrue(looksLikeCommand("/etc/hosts"))
        assertTrue(looksLikeCommand("$ git push"))
        assertFalse(looksLikeCommand("This runs the deploy script."))
    }

    @Test
    fun labelsAndBodies() {
        assertEquals(ToolKind.Bash, toolLabel(tool("bash", args = mapOf("command" to "ls -la\npwd"))).kind)
        assertEquals("ls -la", toolLabel(tool("bash", args = mapOf("command" to "ls -la\npwd"))).detail)
        // While arguments stream, the partial JSON is read.
        assertEquals("src/Main.kt", toolLabel(tool("read", ToolStatus.Streaming, argsText = "{\"path\":\"src/Main.kt")).detail)
        val output = (1..30).joinToString("\n") { "line $it" }
        assertEquals(tail(output, 6), toolBody(tool("bash", result = output), full = false, previewLines = 6)?.text)
        assertEquals(head(output, 6), toolBody(tool("read", result = output), full = false, previewLines = 6)?.text)
        assertEquals(30, lineCount(toolBody(tool("bash", result = output), full = true, previewLines = 6)!!.text))
        assertEquals(0, lineCount(""))
        assertEquals(2, lineCount("a\nb\n"))
    }
}
