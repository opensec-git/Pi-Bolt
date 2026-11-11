package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensec.pibolt.protocol.rpc.ImageAttachment
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.rpc.imagesJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelInfoTest {
    private fun model(json: String) = ModelInfo.from(ProtocolJson.parseToJsonElement(json) as JsonObject)!!

    @Test
    fun inputModalitiesSayWhetherTheModelReadsImages() {
        val vision = model("""{"id":"claude-sonnet-4-5","name":"Sonnet","provider":"anthropic","input":["text","image"]}""")
        assertEquals(listOf("text", "image"), vision.input)
        assertTrue(vision.acceptsImages == true)

        val textOnly = model("""{"id":"deepseek","provider":"x","input":["text"]}""")
        assertFalse(textOnly.acceptsImages == true)
        assertEquals(false, textOnly.acceptsImages)

        // An older host that sends no input list: unknown.
        assertNull(model("""{"id":"m","provider":"x"}""").acceptsImages)
    }

    @Test
    fun imagesAreSentAsPiImageContent() {
        val json = imagesJson(listOf(ImageAttachment("QUJD", "image/jpeg"), ImageAttachment("REVG", "image/png")))
        assertEquals(2, json.size)
        val first = json[0] as JsonObject
        assertEquals("image", first["type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", first["data"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", first["mimeType"]!!.jsonPrimitive.content)
        assertEquals("image/png", (json[1] as JsonObject)["mimeType"]!!.jsonPrimitive.content)
    }
}
