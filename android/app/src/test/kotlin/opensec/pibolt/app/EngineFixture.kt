package opensec.pibolt.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson

/**
 * RPC events recorded from the real engine (Pi-Bolt 1.1.2 linux-arm64) with the bundled extensions, a fake model
 * calling their tools (test/resources/fixtures/engine/<name>.jsonl). The first line describes the run: its engine
 * flags (`args`), the tools the model was offered (`modelTools`) and the dialog answers the client sent (`answers`,
 * null for a cancel).
 */
object EngineFixture {
    fun lines(name: String): List<JsonObject> {
        val text = EngineFixture::class.java.classLoader!!.getResource("fixtures/engine/$name.jsonl")!!.readText()
        return text.lineSequence().filter { it.isNotBlank() }.map { ProtocolJson.parseToJsonElement(it) as JsonObject }.toList()
    }

    fun header(name: String): Header = Header(lines(name).first())

    /** The recorded events after the header. */
    fun events(name: String): List<JsonObject> = lines(name).drop(1)

    class Header(val json: JsonObject) {
        fun tools(key: String): List<String> = (json[key] as JsonArray).map { (it as JsonPrimitive).content }

        /** The answers in order; null is a cancel. */
        val answers: List<String?> get() = (json["answers"] as JsonArray).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}
