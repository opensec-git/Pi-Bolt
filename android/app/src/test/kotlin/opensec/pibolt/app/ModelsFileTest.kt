package opensec.pibolt.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensec.pibolt.app.engine.CustomEndpoint
import opensec.pibolt.app.engine.CustomProvider
import opensec.pibolt.app.engine.ModelsFile
import opensec.pibolt.app.ui.providers.Brand
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.protocol.ProtocolJson
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsFileTest {
    private val ollama = CustomEndpoint("custom-192-168-1-20-11434", "http://192.168.1.20:11434/v1", "qwen2.5-coder:7b")

    private fun obj(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    private fun JsonObject.provider(id: String) = (this["providers"] as JsonObject)[id] as JsonObject

    private fun JsonObject.modelIds(id: String) = (provider(id)["models"] as JsonArray).map { (it as JsonObject)["id"]!!.jsonPrimitive.content }

    @Test
    fun mergeIntoEmptyFileWritesTheProviderAsPiExpects() {
        val out = ModelsFile.merge(ModelsFile.parse(null), ollama, keyless = true)
        assertEquals(ModelsFile.SCHEMA, out["\$schema"]!!.jsonPrimitive.content)
        val p = out.provider(ollama.id)
        assertEquals("http://192.168.1.20:11434/v1", p["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("openai-completions", p["api"]!!.jsonPrimitive.content)
        assertEquals(ModelsFile.NO_KEY, p["apiKey"]!!.jsonPrimitive.content)
        assertEquals(listOf("qwen2.5-coder:7b"), out.modelIds(ollama.id))
    }

    @Test
    fun aRealKeyIsNeverWrittenToModelsJson() {
        val out = ModelsFile.merge(ModelsFile.parse(""), ollama, keyless = false)
        assertNull(out.provider(ollama.id)["apiKey"])
        // A later keyed save drops the placeholder; a user's own apiKey stays.
        val keyless = ModelsFile.merge(out, ollama, keyless = true)
        assertNull(ModelsFile.merge(keyless, ollama, keyless = false).provider(ollama.id)["apiKey"])
        val own = obj("""{"providers":{"x":{"baseUrl":"http://a/v1","apiKey":"${'$'}MY_KEY","models":[{"id":"m"}]}}}""")
        val merged = ModelsFile.merge(own, CustomEndpoint("x", "http://a/v1", "n"), keyless = false)
        assertEquals("\$MY_KEY", merged.provider("x")["apiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun otherProvidersSchemaOverridesAndUnknownFieldsAreKept() {
        val before = obj(
            """{"${'$'}schema":"x","providers":{"anthropic":{"baseUrl":"https://proxy/v1"},"fake":{"baseUrl":"http://h/v1","api":"openai-completions","models":[{"id":"fake-model","contextWindow":128000}]}},"modelOverrides":{"openai":{"gpt":{"name":"G"}}},"extra":[1,2]}""",
        )
        val out = ModelsFile.merge(before, ollama, keyless = true)
        assertEquals("x", out["\$schema"]!!.jsonPrimitive.content)
        assertEquals(before["modelOverrides"], out["modelOverrides"])
        assertEquals(before["extra"], out["extra"])
        assertEquals(before.provider("anthropic"), out.provider("anthropic"))
        assertEquals(before.provider("fake"), out.provider("fake"))
        assertTrue(ollama.id in (out["providers"] as JsonObject))
    }

    @Test
    fun sameProviderGainsAModelOrReplacesTheOneWithItsId() {
        val first = ModelsFile.merge(JsonObject(emptyMap()), ollama, keyless = true)
        val second = ModelsFile.merge(first, ollama.copy(modelId = "llama3.2"), keyless = true)
        assertEquals(listOf("qwen2.5-coder:7b", "llama3.2"), second.modelIds(ollama.id))
        // The same id again: one entry, its other fields kept.
        val withMeta = obj("""{"providers":{"p":{"baseUrl":"http://a/v1","models":[{"id":"m","contextWindow":32000},{"id":"k"}]}}}""")
        val again = ModelsFile.merge(withMeta, CustomEndpoint("p", "http://b/v1", "m", displayName = "Box"), keyless = true)
        assertEquals(listOf("k", "m"), again.modelIds("p"))
        val m = (again.provider("p")["models"] as JsonArray).map { it as JsonObject }.first { it["id"]!!.jsonPrimitive.content == "m" }
        assertEquals("32000", m["contextWindow"]!!.jsonPrimitive.content)
        assertEquals("http://b/v1", again.provider("p")["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("Box", again.provider("p")["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun damagedFileIsRefusedNotWiped() {
        assertFailsWith<ModelsFile.DamagedFile> { ModelsFile.parse("{\"providers\": {") }
        assertFailsWith<ModelsFile.DamagedFile> { ModelsFile.parse("[1,2]") }
        val dir = Files.createTempDirectory("models").toFile()
        val file = File(dir, "models.json").apply { writeText("{ not json") }
        runBlocking {
            assertFailsWith<ModelsFile.DamagedFile> { ModelsFile.update(dir) { ModelsFile.merge(it, ollama, keyless = true) } }
        }
        assertEquals("{ not json", file.readText())
        dir.deleteRecursively()
    }

    @Test
    fun updateWritesAtomicallyAndRemoveKeepsTheRest() {
        val dir = Files.createTempDirectory("models").toFile()
        File(dir, "models.json").writeText("""{"providers":{"fake":{"baseUrl":"http://h/v1","models":[{"id":"f"}]}}}""")
        runBlocking {
            ModelsFile.update(dir) { ModelsFile.merge(it, ollama, keyless = true) }
            assertEquals(setOf("fake", ollama.id), ModelsFile.custom(dir).map { it.id }.toSet())
            ModelsFile.update(dir) { ModelsFile.remove(it, ollama.id) }
            assertEquals(listOf("fake"), ModelsFile.custom(dir).map { it.id })
        }
        assertEquals(listOf("models.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test
    fun customProvidersAreEndpointsWithModels() {
        val root = obj("""{"providers":{"anthropic":{"baseUrl":"https://proxy/v1"},"a":{"name":"Box","baseUrl":"http://a/v1","models":[{"id":"m"}]},"b":"bad"}}""")
        assertEquals(listOf(CustomProvider("a", "Box", "http://a/v1", listOf("m"))), ModelsFile.customProviders(root))
    }

    @Test
    fun urlsAndIds() {
        assertNull(ModelsFile.urlError("http://localhost:11434/v1"))
        assertNull(ModelsFile.urlError("https://proxy.example.com/v1/"))
        assertNotNull(ModelsFile.urlError(""))
        assertNotNull(ModelsFile.urlError("localhost:11434"))
        assertNotNull(ModelsFile.urlError("ftp://host/v1"))
        assertNotNull(ModelsFile.urlError("http:///v1"))
        assertEquals("https://proxy.example.com/v1", ModelsFile.cleanUrl(" https://proxy.example.com/v1/ "))
        assertEquals("custom-localhost-11434", ModelsFile.providerId("http://localhost:11434/v1", null, emptySet()))
        assertEquals("custom-proxy-example-com", ModelsFile.providerId("https://proxy.example.com/v1", " ", emptySet()))
        assertEquals("home-ollama", ModelsFile.providerId("http://x/v1", "Home Ollama", emptySet()))
        assertEquals("custom-openai", ModelsFile.providerId("http://x/v1", "OpenAI", setOf("openai")))
    }

    @Test
    fun catalogMarksAndNamesAreHuman() {
        // Each provider shows its own mark (ProviderLogosTest); only OpenCode's two plans share one.
        val marks = ProviderCatalog.all.groupBy { it.brand }.filterValues { it.size > 1 }.mapValues { (_, ps) -> ps.map { it.id } }
        assertEquals(mapOf(Brand.OpenCode to listOf("opencode", "opencode-go")), marks)
        assertEquals(listOf(Brand.Anthropic, Brand.OpenAI, Brand.Gemini, Brand.OpenRouter), ProviderCatalog.popular.map { it.brand })
        assertEquals(ProviderCatalog.all.size, ProviderCatalog.popular.size + ProviderCatalog.more.size)
        assertEquals("Fake", ProviderCatalog.name("fake"))
        assertEquals("Amazon bedrock", ProviderCatalog.name("amazon-bedrock"))
        assertEquals(Brand.Generic, ProviderCatalog.brand("fake"))
        ProviderCatalog.registerCustom(listOf(CustomProvider("custom-h-1", null, "http://my.host:1/v1", listOf("m")), CustomProvider("n", "Box", "http://b/v1", listOf("m"))))
        assertEquals("my.host", ProviderCatalog.name("custom-h-1"))
        assertEquals("Box", ProviderCatalog.name("n"))
        ProviderCatalog.registerCustom(emptyList())
        assertFalse(ProviderCatalog.name("n") == "Box")
    }

    @Test
    fun aSecondModelForAServerWithAKeyKeepsThatKey() {
        // The first model was added with a key (auth.json holds it, models.json has no apiKey); a second model for the
        // same server with the optional key left empty must not swap the real key for the placeholder.
        val withKey = ModelsFile.merge(ModelsFile.parse(null), ollama, keyless = false)
        assertTrue(ModelsFile.hasSavedKey(withKey, ollama.id))
        val second = ModelsFile.merge(withKey, ollama.copy(modelId = "llama3.1:8b"), keyless = !ModelsFile.hasSavedKey(withKey, ollama.id))
        assertNull(second.provider(ollama.id)["apiKey"])
        assertEquals(listOf("qwen2.5-coder:7b", "llama3.1:8b"), second.modelIds(ollama.id))

        // A keyless server stays keyless; an unknown one has no saved key.
        val keyless = ModelsFile.merge(ModelsFile.parse(null), ollama, keyless = true)
        assertFalse(ModelsFile.hasSavedKey(keyless, ollama.id))
        assertFalse(ModelsFile.hasSavedKey(keyless, "custom-other"))
    }
}
