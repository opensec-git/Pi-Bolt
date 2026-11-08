package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.SettingsCodec
import opensec.pibolt.app.engine.PageCatalog
import opensec.pibolt.app.engine.PageRules
import opensec.pibolt.app.engine.PageRules.Decision
import opensec.pibolt.app.ui.session.views.tileBody
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.PageInfo
import opensec.pibolt.protocol.session.VChart
import opensec.pibolt.protocol.session.VKv
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Package pages and Home tiles on the phone (docs/mobile-ui.md#package-pages-and-home-tiles). */
class PagesTest {
    @Test
    fun `the page engine runs while held, never starts on low memory, and stops later when released`() {
        assertEquals(Decision.Start, PageRules.decide(holds = 1, running = false, lowMemory = false))
        assertEquals(Decision.Refuse, PageRules.decide(holds = 2, running = false, lowMemory = true))
        assertEquals(Decision.Keep, PageRules.decide(holds = 1, running = true, lowMemory = true))
        assertEquals(Decision.StopLater, PageRules.decide(holds = 0, running = true, lowMemory = false))
        assertEquals(Decision.Idle, PageRules.decide(holds = 0, running = false, lowMemory = true))
        assertEquals(30_000L, PageRules.STOP_DELAY_MS)
    }

    @Test
    fun `memory pressure stops an engine that only serves tiles, an open page only when critical`() {
        assertTrue(PageRules.stopsOnTrim(setOf(PageRules.HOLD_HOME, PageRules.HOLD_LIST), critical = false))
        assertFalse(PageRules.stopsOnTrim(setOf(PageRules.HOLD_HOME, PageRules.HOLD_PAGE + "board:page"), critical = false))
        assertTrue(PageRules.stopsOnTrim(setOf(PageRules.HOLD_PAGE + "board:page"), critical = true))
    }

    private fun settings(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    @Test
    fun `the page list cache is keyed by the packages, the builtins and the engine`() {
        val a = PageCatalog.key(settings("""{"packages":["npm:a"],"theme":"dark"}"""), "1.1.6")
        assertEquals(a, PageCatalog.key(settings("""{"packages":["npm:a"],"theme":"light"}"""), "1.1.6"))
        assertNotEquals(a, PageCatalog.key(settings("""{"packages":["npm:a","npm:b"]}"""), "1.1.6"))
        assertNotEquals(a, PageCatalog.key(settings("""{"packages":[{"source":"npm:a","extensions":[]}]}"""), "1.1.6"))
        assertNotEquals(a, PageCatalog.key(settings("""{"packages":["npm:a"],"extensions":["-builtin:mcp"]}"""), "1.1.6"))
        assertNotEquals(a, PageCatalog.key(settings("""{"packages":["npm:a"]}"""), "1.1.7"))
        // Pi-Bolt's own package list is merged into what the engine loads.
        assertNotEquals(a, PageCatalog.key(settings("""{"packages":["npm:a"],"piBolt":{"packages":["npm:m"]}}"""), "1.1.6"))
        assertEquals(a, PageCatalog.key(settings("""{"packages":["npm:a"],"piBolt":{"theme":"x"}}"""), "1.1.6"))
        assertEquals(PageCatalog.key(null, ""), PageCatalog.key(settings("{}"), ""))
        // A package updated in place changes only what is installed.
        assertNotEquals(a, PageCatalog.key(settings("""{"packages":["npm:a"],"theme":"dark"}"""), "1.1.6", "npm 2 10\n"))
    }

    @Test
    fun `the install stamp follows the npm lockfile and git checkouts`() {
        val dir = kotlin.io.path.createTempDirectory("pages").toFile()
        try {
            assertEquals("", PageCatalog.installStamp(dir))
            File(dir, "npm").mkdirs()
            File(dir, "npm/package-lock.json").apply { writeText("{}"); setLastModified(1_000_000L) }
            val checkout = File(dir, "git/github.com/x/ci/.git").apply { mkdirs() }
            File(checkout, "index").apply { writeText("a"); setLastModified(2_000_000L) }
            val first = PageCatalog.installStamp(dir)
            assertEquals("npm 1000000 2\ngit ${File("git/github.com/x/ci").path} 2000000 1\n", first)
            File(checkout, "index").apply { writeText("ab"); setLastModified(3_000_000L) }
            assertNotEquals(first, PageCatalog.installStamp(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private val pages = listOf(
        PageInfo("board:page", "page", "Board", "check", "Open tasks", null, "npm:mobile-ui-board", "/h/node_modules/mobile-ui-board"),
        PageInfo("board:tile", "tile", "Board", null, null, "board:page", null, "/h/node_modules/mobile-ui-board/index.ts"),
        PageInfo("ci:tile", "tile", "CI", null, null, null, "git:github.com/x/ci", null),
    )

    @Test
    fun `the cache round-trips and is used only for the same packages`() {
        val text = PageCatalog.encode(PageCatalog.Cached("k1", pages))
        val back = PageCatalog.decode(text)
        assertEquals(pages, back?.pages)
        assertEquals(pages, PageCatalog.valid(back, "k1"))
        assertNull(PageCatalog.valid(back, "k2"))
        assertNull(PageCatalog.decode("not json"))
    }

    @Test
    fun `pages go under their package by source or by its installed folder`() {
        assertEquals(listOf("board:page", "board:tile"), PageCatalog.ofPackage(pages, "npm:mobile-ui-board", "/h/node_modules/mobile-ui-board/").map { it.key })
        assertEquals(listOf("board:page"), PageCatalog.ofPackage(pages, "npm:mobile-ui-board", null).map { it.key })
        assertEquals(emptyList(), PageCatalog.ofPackage(pages, "npm:mobile-ui", "/h/node_modules/mobile-ui").map { it.key })
        assertEquals(listOf("ci:tile"), PageCatalog.ofPackage(pages, "git:github.com/x/ci", "/elsewhere").map { it.key })
    }

    @Test
    fun `Home shows only the tiles turned on that still exist`() {
        assertEquals(emptyList(), PageCatalog.shownTiles(pages, emptySet()))
        assertEquals(listOf("ci:tile"), PageCatalog.shownTiles(pages, setOf("ci:tile", "gone:tile", "board:page")).map { it.key })
        // Toggling drops tiles of removed packages, so they do not hold Home's slots.
        assertEquals(setOf("ci:tile", "board:tile"), PageCatalog.toggleTile(pages, setOf("ci:tile", "gone:tile"), "board:tile", on = true))
        assertEquals(emptySet(), PageCatalog.toggleTile(pages, setOf("ci:tile", "gone:tile"), "ci:tile", on = false))
    }

    @Test
    fun `Home tiles are off by default and saved when turned on`() {
        assertEquals(emptySet(), AppSettings().homeTiles)
        val on = AppSettings(homeTiles = setOf("ci:tile", "board:tile"))
        assertEquals(on.homeTiles, SettingsCodec.decode(SettingsCodec.encode(on)).homeTiles)
        assertFalse("homeTiles" in SettingsCodec.encode(AppSettings()))
        val many = (1..20).joinToString(",") { "\"t$it\"" }
        assertEquals(AppSettings.MAX_HOME_TILES, SettingsCodec.decode("""{"homeTiles":[$many,3]}""").homeTiles.size)
    }

    @Test
    fun `a tile keeps text, chips, rows, progress, four key-values and charts as sparklines`() {
        val view = MobileViews.parse(
            ProtocolJson.parseToJsonElement(
                """{"v":1,"title":"Board","body":[
                {"type":"text","text":"3 open"},{"type":"chip","label":"ok"},{"type":"button","id":"b","label":"Go"},
                {"type":"kv","items":[{"key":"a","value":"1"},{"key":"b","value":"2"},{"key":"c","value":"3"},{"key":"d","value":"4"},{"key":"e","value":"5"}]},
                {"type":"chart","kind":"bar","series":[{"name":"x","values":[1,2]},{"name":"y","values":[3,4]}]},
                {"type":"text","text":"hidden","hidden":true},{"type":"progress","value":0.5},{"type":"table","columns":[{"id":"a","label":"A"}],"rows":[]}]}""",
            ) as JsonObject,
        )
        val body = tileBody(view.body)
        assertEquals(5, body.size)
        assertEquals(4, assertIs<VKv>(body[2]).items.size)
        val chart = assertIs<VChart>(body[3])
        assertEquals("sparkline", chart.kind)
        assertEquals(1, chart.series.size)
    }
}
