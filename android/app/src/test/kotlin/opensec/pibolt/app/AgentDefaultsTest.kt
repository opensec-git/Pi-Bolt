package opensec.pibolt.app

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.protocol.ProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings > Pi on this phone: the default model, retries, skill commands and image blocking in settings.json. */
class AgentDefaultsTest {
    private fun json(text: String) = ProtocolJson.parseToJsonElement(text).jsonObject

    @Test
    fun readsTheNewKeysWithDefaults() {
        val d = AgentDefaults.from(json("""{"retry":{"enabled":true,"maxRetries":5},"enableSkillCommands":false,"images":{"blockImages":true,"autoResize":false}}"""))
        assertEquals(5, d.maxRetries)
        assertFalse(d.skillCommands)
        assertTrue(d.blockImages)
        val empty = AgentDefaults.from(json("{}"))
        assertEquals(3, empty.maxRetries)
        assertTrue(empty.skillCommands)
        assertFalse(empty.blockImages)
    }

    @Test
    fun mergeKeepsNestedKeysAndWritesTheNewOnes() {
        val src = json("""{"retry":{"enabled":true,"baseDelayMs":2000},"images":{"autoResize":false}}""")
        val merged = AgentDefaults.merge(src, AgentDefaults.from(src).copy(maxRetries = 10, blockImages = true, skillCommands = false))
        val retry = merged["retry"]!!.jsonObject
        assertEquals("10", retry["maxRetries"]!!.jsonPrimitive.content)
        assertEquals("2000", retry["baseDelayMs"]!!.jsonPrimitive.content)
        assertTrue(merged["images"]!!.jsonObject["blockImages"]!!.jsonPrimitive.boolean)
        assertFalse(merged["images"]!!.jsonObject["autoResize"]!!.jsonPrimitive.boolean)
        assertFalse(merged["enableSkillCommands"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun defaultModelSplitsAtTheFirstSlashOnlyWhenChanged() {
        val src = json("""{"defaultProvider":"fake","defaultModel":"fake-model"}""")
        val picked = AgentDefaults.merge(src, AgentDefaults.from(src).copy(defaultModel = "openrouter/anthropic/claude-sonnet"))
        assertEquals("openrouter", picked["defaultProvider"]!!.jsonPrimitive.content)
        assertEquals("anthropic/claude-sonnet", picked["defaultModel"]!!.jsonPrimitive.content)
        // Unchanged: a model-only default that contains a slash is left alone.
        val modelOnly = json("""{"defaultModel":"anthropic/claude-x"}""")
        val kept = AgentDefaults.merge(modelOnly, AgentDefaults.from(modelOnly))
        assertEquals("anthropic/claude-x", kept["defaultModel"]!!.jsonPrimitive.content)
        assertFalse("defaultProvider" in kept)
    }
}
