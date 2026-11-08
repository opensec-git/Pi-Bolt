package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.session.ChartData
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.TableData
import opensec.pibolt.protocol.session.VCell
import opensec.pibolt.protocol.session.VChart
import opensec.pibolt.protocol.session.VLocation
import opensec.pibolt.protocol.session.VTable
import opensec.pibolt.protocol.session.VColumn
import opensec.pibolt.protocol.session.VUnknown
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The kit's charts, tables, places, the tile slot and `get_pages` (docs/mobile-ui.md, kit 2). */
class MobileViewDataTest {
    private fun node(json: String) = MobileViews.node(ProtocolJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun `parses a chart with gaps, caps its series and refuses one without series`() {
        val chart = assertIs<VChart>(
            node("""{"type":"chart","id":"c","kind":"bar","title":"Builds","unit":"ms","labels":["Mon","Tue","Wed"],
                "series":[{"name":"A","values":[1,null,3]},{"name":"B","values":[2,2,2]},{"name":"C","values":[1]},{"name":"D","values":[1]},{"name":"E","values":[1]}]}"""),
        )
        assertEquals("bar", chart.kind)
        assertEquals(listOf(1.0, null, 3.0), chart.series[0].values)
        assertEquals(4, chart.series.size)
        assertEquals(listOf("Mon", "Tue", "Wed"), chart.labels)
        val spark = assertIs<VChart>(node("""{"type":"chart","kind":"sparkline","series":[{"name":"a","values":[1,2]},{"name":"b","values":[3]}]}"""))
        assertEquals(1, spark.series.size)
        assertEquals("line", assertIs<VChart>(node("""{"type":"chart","kind":"pie","series":[{"name":"a","values":[1]}]}""")).kind)
        assertEquals("Empty", assertIs<VUnknown>(node("""{"type":"chart","kind":"line","title":"Empty","series":[]}""")).text)
    }

    @Test
    fun `chart range starts bars at zero, honours min and max and centres a flat series`() {
        fun chart(kind: String, values: List<Double?>, min: Double? = null, max: Double? = null) =
            VChart(null, false, kind, null, listOf(opensec.pibolt.protocol.session.VSeries("s", values)), emptyList(), null, min, max, null)
        assertEquals(ChartData.Range(0.0, 9.0), ChartData.range(chart("bar", listOf(4.0, 9.0))))
        assertEquals(ChartData.Range(4.0, 9.0), ChartData.range(chart("line", listOf(4.0, null, 9.0))))
        assertEquals(ChartData.Range(-3.0, 0.0), ChartData.range(chart("area", listOf(-3.0, -1.0))))
        assertEquals(ChartData.Range(0.0, 100.0), ChartData.range(chart("line", listOf(40.0, 50.0), min = 0.0, max = 100.0)))
        val flat = ChartData.range(chart("line", listOf(5.0, 5.0)))
        assertTrue(flat.low < 5.0 && flat.high > 5.0)
        assertEquals(0.5, flat.fraction(5.0), 1e-9)
        assertEquals(1.0, ChartData.Range(0.0, 10.0).fraction(20.0))
    }

    @Test
    fun `chart summary reads last, low and high per series, or the author's summary`() {
        val build = assertIs<VChart>(node("""{"type":"chart","kind":"line","title":"Build time","unit":"s","series":[{"name":"main","values":[52,38,null,41]}]}"""))
        assertEquals("Line chart, Build time: last 41 s, low 38 s, high 52 s", ChartData.summary(build))
        val two = assertIs<VChart>(node("""{"type":"chart","kind":"bar","unit":"%","series":[{"name":"Pass","values":[90,95.5]},{"name":"Fail","values":[10,4.5]}]}"""))
        assertEquals("Bar chart: Pass: last 95.5%, low 90%, high 95.5%; Fail: last 4.5%, low 4.5%, high 10%", ChartData.summary(two))
        val own = assertIs<VChart>(node("""{"type":"chart","kind":"sparkline","summary":"Load is steady","series":[{"name":"x","values":[1]}]}"""))
        assertEquals("Load is steady", ChartData.summary(own))
        assertEquals("Sparkline, no data", ChartData.summary(assertIs<VChart>(node("""{"type":"chart","kind":"sparkline","series":[{"name":"x","values":[null]}]}"""))))
    }

    private val deps = """{"type":"table","id":"deps","filter":true,"sort":{"column":"size","desc":true},
        "columns":[{"id":"name","label":"Package","sortable":true},{"id":"size","label":"Size","kind":"number","sortable":true},
                   {"id":"updated","label":"Updated","kind":"date","sortable":true},{"id":"x","label":"X","kind":"weird"}],
        "rows":[{"id":"r1","cells":{"name":"undici","size":1532,"updated":"2026-10-30"},"action":"open"},
                {"id":"r2","cells":{"name":"chalk","size":44,"updated":"2026-09-12T10:00:00Z"}},
                {"id":"r3","cells":{"name":"Zod","size":null,"updated":"2026-11-01"}},
                {"cells":{"name":"no id"}}]}"""

    @Test
    fun `parses a table with typed columns and rows with ids`() {
        val table = assertIs<VTable>(node(deps))
        assertEquals(listOf("text", "number", "date", "text"), table.columns.map { it.kind })
        assertEquals(listOf("r1", "r2", "r3"), table.rows.map { it.id })
        assertEquals(VCell.Number(1532.0), table.rows[0].cells["size"])
        assertEquals(VCell.Empty, table.rows[2].cells["size"])
        assertEquals("open", table.rows[0].action)
        assertEquals("size", table.sortColumn)
        assertTrue(table.sortDesc && table.filter)
    }

    @Test
    fun `table rows sort by type with empty cells last, and filter on any cell`() {
        val table = assertIs<VTable>(node(deps))
        assertEquals(listOf("r1", "r2", "r3"), TableData.rows(table, "size", desc = true, filter = "").map { it.id })
        assertEquals(listOf("r2", "r1", "r3"), TableData.rows(table, "size", desc = false, filter = "").map { it.id })
        assertEquals(listOf("r2", "r1", "r3"), TableData.rows(table, "updated", desc = false, filter = "").map { it.id })
        assertEquals(listOf("r2", "r1", "r3"), TableData.rows(table, "name", desc = false, filter = "").map { it.id })
        assertEquals(listOf("r1", "r2", "r3"), TableData.rows(table, null, desc = false, filter = "").map { it.id })
        assertEquals(listOf("r2"), TableData.rows(table, null, desc = false, filter = " CHALK ").map { it.id })
        assertEquals(listOf("r1"), TableData.rows(table, null, desc = false, filter = "1532").map { it.id })
        assertEquals("size" to false, TableData.nextSort(null, false, "size"))
        assertEquals("size" to true, TableData.nextSort("size", false, "size"))
        assertEquals("name" to false, TableData.nextSort("size", true, "name"))
        assertEquals(java.time.Instant.parse("2026-10-30T00:00:00Z"), TableData.instant(VCell.Text("2026-10-30"), ZoneOffset.UTC))
        assertNull(TableData.instant(VCell.Text("yesterday")))
    }

    @Test
    fun `chart readings round to two decimals without saturating`() {
        assertEquals("41.5 s", ChartData.format(41.499, "s"))
        assertEquals("1000000000000000000", ChartData.format(1e18, null))
        assertEquals("-0.01%", ChartData.format(-0.0149, "%"))
        assertEquals("0", ChartData.format(0.0, null))
        assertEquals("–", ChartData.format(Double.POSITIVE_INFINITY, null))
        // Readings past 1e15 (Pi refuses them) are gaps, and a min or max past it is ignored: no bound can overflow.
        val chart = assertIs<VChart>(node("""{"type":"chart","kind":"line","max":1.7e308,"series":[{"name":"a","values":[1.7e308,1.7e308,5]}]}"""))
        assertEquals(listOf(null, null, 5.0), chart.series[0].values)
        assertNull(chart.max)
        val range = ChartData.range(chart)
        assertTrue(range.low.isFinite() && range.high.isFinite() && range.high > range.low)
    }

    @Test
    fun `table numbers keep their precision, as shown and as filtered`() {
        val column = VColumn("n", "N", "number", sortable = false)
        assertEquals("0.001", TableData.text(VCell.Number(0.001), column))
        assertEquals("123.4567", TableData.text(VCell.Number(123.4567), column))
        assertEquals("1500", TableData.text(VCell.Number(1500.0), column))
        assertEquals("-2.5", TableData.text(VCell.Number(-2.5), column))
    }

    @Test
    fun `location keeps coordinates only as a valid pair and builds geo links`() {
        val office = assertIs<VLocation>(node("""{"type":"location","name":"Office (HQ)","address":"1 Market St, San Francisco","lat":37.7936,"lon":-122.3958}"""))
        assertEquals("geo:37.7936,-122.3958?q=37.7936%2C-122.3958%28Office%20%20HQ%29", MobileViews.geoUri(office))
        val address = assertIs<VLocation>(node("""{"type":"location","name":"Cafe","address":"Rue de Rivoli 1, Paris","lat":91,"lon":2}"""))
        assertNull(address.lat)
        assertNull(address.lon)
        assertEquals("geo:0,0?q=Rue%20de%20Rivoli%201%2C%20Paris", MobileViews.geoUri(address))
        assertNull(MobileViews.geoUri(assertIs<VLocation>(node("""{"type":"location","name":"Nowhere"}"""))))
    }

    @Test
    fun `the tile slot is kept as a view of the session`() {
        val line = """{"type":"extension_ui_request","id":"u1","method":"setView","viewKey":"board:tile","slot":"tile","rev":1,"view":{"v":1,"title":"Board","body":[{"type":"text","text":"3 open"}]}}"""
        val state = SessionReducer.reduce(SessionState(), SessionReducer.parse(line))
        assertEquals(MobileViews.SLOT_TILE, state.views.getValue("board:tile").slot)
    }

    @Test
    fun `get_pages lists pages and tiles with their package`() {
        val data = ProtocolJson.parseToJsonElement(
            """{"pages":[{"key":"board:page","kind":"page","title":"Board","icon":"check","sourceInfo":{"source":"npm:mobile-ui-board","baseDir":"/x/board"}},
                {"key":"board:tile","kind":"tile","title":"Board","page":"board:page","sourceInfo":{"source":"npm:mobile-ui-board","path":"/x/board/index.ts"}},
                {"key":"bad","kind":"widget","title":"No"},{"key":"untitled","kind":"page"},{"key":"board:page","kind":"page","title":"Again"}]}""",
        ) as JsonObject
        val pages = MobileViews.pages(data)
        assertEquals(listOf("board:page", "board:tile"), pages.map { it.key })
        assertEquals("/x/board", pages[0].path)
        assertTrue(pages[1].isTile)
        assertEquals("board:page", pages[1].page)
        assertEquals("npm:mobile-ui-board", pages[1].source)
        assertEquals(emptyList(), MobileViews.pages(null))
    }
}
