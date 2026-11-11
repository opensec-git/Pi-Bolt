package opensec.pibolt.app

import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.diagram.DiagramGate
import opensec.pibolt.app.ui.diagram.DiagramGate.Outcome
import opensec.pibolt.app.ui.diagram.DiagramKey
import opensec.pibolt.app.ui.diagram.DiagramText
import opensec.pibolt.app.ui.diagram.DiagramView
import opensec.pibolt.app.ui.diagram.JsonText
import opensec.pibolt.app.ui.diagram.MermaidTheme
import opensec.pibolt.app.ui.theme.PiColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagramTest {
    // ---- JSON encoding of the diagram source

    @Test
    fun quoteEscapesEverythingThatCouldEndTheString() {
        assertEquals("\"a\\\"b\\\\c\"", JsonText.quote("a\"b\\c"))
        assertEquals("\"line1\\nline2\\r\\t\"", JsonText.quote("line1\nline2\r\t"))
        assertEquals("\"\\u0000\\u001f\"", JsonText.quote("\u0000\u001f"))
        assertEquals("\"\\u2028\\u2029\"", JsonText.quote("\u2028\u2029"))
    }

    @Test
    fun quoteNeverEmitsAScriptCloseTag() {
        val quoted = JsonText.quote("A --> B</script><script>alert(1)</script>")
        assertFalse(quoted.contains("</script"), quoted)
        assertFalse(quoted.contains('<'), quoted)
    }

    @Test
    fun quoteOfHostileSourceStaysOneStringLiteral() {
        // Text that would break out of a hand-built '...' or "..." literal: the result must still be exactly one
        // JSON string, so every quote inside it is escaped.
        val source = "graph TD\nA[\"x\"] --> B');window.PiBoltDiagram.result(1,1,1,'','','','');//\n\"); alert(1); (\""
        val quoted = JsonText.quote(source)
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""))
        val inner = quoted.substring(1, quoted.length - 1)
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '\\') {
                i += 2
                continue
            }
            assertNotEquals('"', inner[i], "unescaped quote at $i in $quoted")
            assertFalse(inner[i] == '\n', "raw newline in $quoted")
            i++
        }
        assertEquals(source, decode(quoted))
    }

    @Test
    fun quoteKeepsUnicodeAndPairsButEscapesLoneSurrogates() {
        assertEquals("\"Ünïcödé 图 😀\"", JsonText.quote("Ünïcödé 图 😀"))
        assertEquals("\"a\\ud800b\"", JsonText.quote("a\uD800b"))
        assertEquals("\"\\udc00\"", JsonText.quote("\uDC00"))
    }

    @Test
    fun objectIsValidJsonInOrder() {
        val json = JsonText.obj(mapOf("scale" to 2.5f, "maxWidth" to 900, "dark" to true, "name" to "a\"b", "vars" to mapOf("x" to "#ffffff")))
        assertEquals("{\"scale\":2.5,\"maxWidth\":900,\"dark\":true,\"name\":\"a\\\"b\",\"vars\":{\"x\":\"#ffffff\"}}", json)
    }

    // ---- Cache key

    @Test
    fun keyIsStableAndHex() {
        val a = DiagramKey.of("graph TD\nA-->B", "{}", "inline")
        assertEquals(a, DiagramKey.of("graph TD\nA-->B", "{}", "inline"))
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun keyChangesWithSourceThemeAndSize() {
        val base = DiagramKey.of("graph TD\nA-->B", "{\"t\":1}", "inline 900")
        assertNotEquals(base, DiagramKey.of("graph TD\nA-->C", "{\"t\":1}", "inline 900"))
        assertNotEquals(base, DiagramKey.of("graph TD\nA-->B", "{\"t\":2}", "inline 900"))
        assertNotEquals(base, DiagramKey.of("graph TD\nA-->B", "{\"t\":1}", "inline 901"))
        assertNotEquals(base, DiagramKey.of("graph TD\nA-->B", "{\"t\":1}", "full 900"))
    }

    @Test
    fun keyPartsCannotBeShiftedIntoEachOther() {
        assertNotEquals(DiagramKey.of("ab", "c", ""), DiagramKey.of("a", "bc", ""))
        assertNotEquals(DiagramKey.of("a", "", "b"), DiagramKey.of("a", "b", ""))
    }

    @Test
    fun keyDiffersPerAppTheme() {
        val keys = ThemeId.entries.map { theme ->
            DiagramKey.of("pie\n\"a\": 1", MermaidTheme.of(PiColors.of(theme, AccentId.Theme)).json, "inline")
        }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ---- Fence-closed and debounce logic

    @Test
    fun closedFencesAndCachedDiagramsRenderAtOnce() {
        assertEquals(0L, DiagramGate.delayBeforeRender(closed = true, cached = false))
        assertEquals(0L, DiagramGate.delayBeforeRender(closed = false, cached = true))
        assertEquals(0L, DiagramGate.delayBeforeRender(closed = null, cached = true))
    }

    @Test
    fun openOrUnknownFencesWaitForAPause() {
        assertEquals(300L, DiagramGate.DEBOUNCE_MS)
        assertEquals(DiagramGate.DEBOUNCE_MS, DiagramGate.delayBeforeRender(closed = false, cached = false))
        assertEquals(DiagramGate.DEBOUNCE_MS, DiagramGate.delayBeforeRender(closed = null, cached = false))
    }

    @Test
    fun viewWhileWaiting() {
        assertEquals(DiagramView.Skeleton, DiagramGate.view(null, hasStaleImage = false, closed = false))
        assertEquals(DiagramView.ImageUpdating, DiagramGate.view(null, hasStaleImage = true, closed = false))
        assertEquals(DiagramView.Skeleton, DiagramGate.view(null, hasStaleImage = false, closed = null))
    }

    @Test
    fun drawnSourceShowsTheImage() {
        assertEquals(DiagramView.Image, DiagramGate.view(Outcome.Drawn, hasStaleImage = false, closed = false))
        assertEquals(DiagramView.Image, DiagramGate.view(Outcome.Drawn, hasStaleImage = true, closed = true))
    }

    @Test
    fun errorsInAnOpenFenceWaitForMoreText() {
        // A half-written diagram usually fails to parse; keep the last drawing (or the skeleton) instead.
        assertEquals(DiagramView.Skeleton, DiagramGate.view(Outcome.Failed, hasStaleImage = false, closed = false))
        assertEquals(DiagramView.ImageUpdating, DiagramGate.view(Outcome.Failed, hasStaleImage = true, closed = false))
        assertEquals(DiagramView.Error, DiagramGate.view(Outcome.Failed, hasStaleImage = true, closed = true))
        assertEquals(DiagramView.Error, DiagramGate.view(Outcome.Failed, hasStaleImage = false, closed = null))
    }

    /** Replays a stream of source edits through the debounce: only sources followed by a 300 ms pause render. */
    @Test
    fun debounceRendersOnlyAfterAPause() {
        // (time in ms, source) as tokens arrive; the stream pauses after "B" and the fence closes after "C".
        val edits = listOf(0L to "graph", 40L to "graph TD", 80L to "graph TD\nA", 120L to "graph TD\nA-->B", 900L to "graph TD\nA-->B-->C")
        val rendered = ArrayList<String>()
        for ((i, edit) in edits.withIndex()) {
            val (at, source) = edit
            val due = at + DiagramGate.delayBeforeRender(closed = null, cached = false)
            val next = edits.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            if (due <= next) rendered += source
        }
        assertEquals(listOf("graph TD\nA-->B", "graph TD\nA-->B-->C"), rendered)
    }

    // ---- Texts and theme

    @Test
    fun talkBackDescription() {
        assertEquals("Diagram: Flowchart, Start", DiagramText.description("flowchart-v2", "Start"))
        assertEquals("Diagram: Sequence diagram, Alice", DiagramText.description("sequence", "  Alice \n"))
        assertEquals("Diagram: Pie chart", DiagramText.description("pie", ""))
        assertEquals("Diagram: Diagram, x", DiagramText.description("somethingNew", "x"))
    }

    @Test
    fun kindFromSourceSkipsFrontMatterAndComments() {
        assertEquals("flowchart", DiagramText.kindFromSource("%% a comment\ngraph TD\nA-->B"))
        assertEquals("sequence", DiagramText.kindFromSource("---\ntitle: Hi\n---\nsequenceDiagram\nA->>B: hi"))
        assertEquals("state", DiagramText.kindFromSource("stateDiagram-v2\n[*] --> A"))
        assertEquals("er", DiagramText.kindFromSource("erDiagram\nA ||--o{ B : has"))
        assertEquals("mindmap", DiagramText.kindFromSource("mindmap\n  root"))
        assertEquals("", DiagramText.kindFromSource("  \n"))
    }

    @Test
    fun errorLineIsTheFirstMeaningfulLine() {
        val message = "Parse error on line 2:\ngraph TD A-->\n-------------^\nExpecting 'AMP', got 'EOF'"
        assertEquals("Parse error on line 2, unexpected end of input", DiagramText.errorLine(message))
        assertEquals("Parse error on line 5, unexpected PIPE", DiagramText.errorLine("Parse error on line 5:\n...\n---^\nExpecting 'SQE', 'PE', got 'PIPE'"))
        assertEquals("Parse error on line 5", DiagramText.errorLine("Parse error on line 5:\n...\nExpecting 'SQE', got '1'"))
        assertEquals("No diagram type detected matching given configuration for text: hello", DiagramText.errorLine("No diagram type detected matching given configuration for text: hello"))
        assertEquals(160, DiagramText.errorLine("x".repeat(500)).length)
    }

    @Test
    fun themeVariablesAreOpaqueHexForEveryTheme() {
        for (theme in ThemeId.entries) {
            val vars = MermaidTheme.variables(PiColors.of(theme, AccentId.Theme))
            for ((k, v) in vars) {
                if (k in setOf("fontFamily", "fontSize", "pieStrokeWidth", "pieOuterStrokeWidth", "pieOpacity", "dropShadow", "useGradient")) continue
                assertTrue(Regex("#[0-9a-f]{6}").matches(v as String), "$theme $k = $v")
            }
            assertEquals(MermaidTheme.hex(PiColors.of(theme, AccentId.Theme).ink1), vars["primaryTextColor"])
            // Mermaid's darkMode follows the canvas: on for the dark themes, off for Paper.
            val dark = !PiColors.of(theme, AccentId.Theme).isLight
            assertTrue(MermaidTheme.of(PiColors.of(theme, AccentId.Theme)).json.contains("\"dark\":$dark"), "$theme dark = $dark")
        }
    }

    /** A minimal JSON string decoder, to prove [JsonText.quote] round-trips. */
    private fun decode(quoted: String): String {
        val out = StringBuilder()
        var i = 1
        while (i < quoted.length - 1) {
            val c = quoted[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            when (val e = quoted[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    out.append(quoted.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }
}
