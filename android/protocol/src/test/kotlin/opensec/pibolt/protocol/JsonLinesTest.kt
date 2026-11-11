package opensec.pibolt.protocol

import opensec.pibolt.protocol.transport.JsonLines
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonLinesTest {
    private fun split(vararg chunks: String, max: Int = 1 shl 20): Pair<List<String>, List<Long>> {
        val records = mutableListOf<String>()
        val oversized = mutableListOf<Long>()
        val lines = JsonLines(maxRecord = max, onOversized = { oversized += it }, onRecord = { records += it })
        for (c in chunks) lines.feed(c.toByteArray(Charsets.UTF_8))
        lines.finish()
        return records to oversized
    }

    @Test
    fun splitsOnLineFeedOnly() {
        val (records, _) = split("{\"a\":\"x y z\"}\n{\"b\":1}\r\n")
        assertEquals(listOf("{\"a\":\"x y z\"}", "{\"b\":1}"), records)
    }

    @Test
    fun joinsRecordsAcrossChunksIncludingSplitCharacters() {
        val bytes = "{\"t\":\"héllo\"}\n{\"u\":2}".toByteArray(Charsets.UTF_8)
        val records = mutableListOf<String>()
        val lines = JsonLines(onRecord = { records += it })
        // One byte at a time splits the two-byte é too.
        for (b in bytes) lines.feed(byteArrayOf(b))
        lines.finish()
        assertEquals(listOf("{\"t\":\"héllo\"}", "{\"u\":2}"), records)
    }

    @Test
    fun skipsBlankLinesAndOversizedRecords() {
        val (records, oversized) = split("\n\n{\"ok\":1}\n", "{\"big\":\"${"x".repeat(100)}\"}\n{\"ok\":2}\n", max = 50)
        assertEquals(listOf("{\"ok\":1}", "{\"ok\":2}"), records)
        assertEquals(listOf(110L), oversized)
    }
}
