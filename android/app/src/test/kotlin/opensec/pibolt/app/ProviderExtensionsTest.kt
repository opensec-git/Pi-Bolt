package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.engine.IdlePool
import opensec.pibolt.app.engine.ProviderLogin
import opensec.pibolt.app.engine.ProviderReport
import opensec.pibolt.app.engine.ProviderRules
import opensec.pibolt.app.ui.providers.Brand
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.session.features2.AppExtension
import opensec.pibolt.protocol.ProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider extensions (Pi packages that call `pi.registerProvider`): the engine's report, Settings' list, "connected". */
class ProviderExtensionsTest {
    private fun obj(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    /** What the helper extension writes on an engine before 1.1.7: extension providers and those with a usable model. */
    private val helper = obj(
        """
        {"ok": true, "providers": [
          {"id": "acme", "name": "Acme AI", "extension": true, "configured": false,
           "apiKey": {"name": "API key", "env": ["ACME_API_KEY"]},
           "models": [{"id": "acme-chat", "name": "Acme Chat", "reasoning": false, "input": ["text"], "contextWindow": 128000}],
           "available": []},
          {"id": "sso", "name": "SSO Corp", "extension": true, "configured": false,
           "oauth": {"name": "SSO Corp login", "loginLabel": "Sign in with SSO"},
           "models": [{"id": "sso-chat", "name": "SSO Chat"}], "available": []},
          {"id": "local-llm", "name": "Local LLM", "extension": true, "configured": true,
           "apiKey": {"name": "API key", "env": []},
           "models": [{"id": "tiny", "name": "Tiny", "reasoning": true, "input": ["text", "image"], "contextWindow": 32768}],
           "available": ["tiny"]},
          {"id": "llama.cpp", "name": "llama.cpp", "extension": true, "configured": false,
           "apiKey": {"name": "llama.cpp server", "env": []}, "models": [], "available": []},
          {"id": "anthropic", "name": "Anthropic", "extension": false, "configured": true,
           "apiKey": {"name": "Anthropic API key", "env": []},
           "models": [{"id": "claude-x", "name": "Claude X"}], "available": ["claude-x"]}
        ]}
        """,
    )

    private val catalog = ProviderCatalog.ids
    private val report = assertNotNull(ProviderReport.from(helper, complete = false))

    @Test
    fun `the helper's report parses the key, its variable, sign-in, models and the usable ones`() {
        val acme = assertNotNull(report["acme"])
        assertTrue(acme.acceptsKey)
        assertEquals(listOf("ACME_API_KEY"), acme.keyEnv)
        assertFalse(acme.canLogin)
        assertFalse(acme.usable)
        assertEquals(128000L, acme.models.single().contextWindow)
        val sso = assertNotNull(report["sso"])
        assertFalse(sso.acceptsKey)
        assertTrue(sso.canLogin)
        assertEquals("Sign in with SSO", sso.loginLabel)
        assertEquals(listOf("tiny"), report["local-llm"]?.available)
        assertFalse(report.complete)
    }

    @Test
    fun `the report survives the cache`() {
        val again = ProviderReport.from(ProtocolJson.parseToJsonElement(report.toJson().toString()) as JsonObject)
        assertEquals(report, again)
    }

    @Test
    fun `get_providers data parses the same way`() {
        val rpc = ProviderReport.from(
            obj("""{"providers": [{"id": "acme", "name": "Acme AI", "origin": "extension", "extension": true, "configured": true, "credential": "api_key", "authSource": "stored", "apiKey": {"name": "API key", "env": ["ACME_API_KEY"]}, "models": [{"id": "acme-chat", "name": "Acme Chat", "reasoning": false, "input": ["text"], "contextWindow": 128000}], "available": ["acme-chat"]}]}"""),
            complete = true,
        )
        assertNotNull(rpc)
        assertTrue(rpc.complete)
        assertEquals("extension", rpc["acme"]?.origin)
        assertEquals(listOf("acme-chat"), rpc.availableModels.map { it.id })
        assertEquals("acme", rpc.availableModels.single().provider)
    }

    @Test
    fun `only an extension provider with a usable model counts as connected`() {
        // No key anywhere: the keyless local provider from a package is enough for Home to show the composer.
        assertEquals(setOf("local-llm", "anthropic"), ProviderRules.connected(emptySet(), report, catalog))
        val waiting = ProviderReport(report.providers.filter { it.id in setOf("acme", "sso") }, complete = false)
        assertEquals(emptySet(), ProviderRules.connected(emptySet(), waiting, catalog))
    }

    @Test
    fun `stored keys count, before the engine answers and for providers the app knows`() {
        assertEquals(setOf("openai", "acme"), ProviderRules.connected(setOf("openai", "acme"), null, catalog))
        // The engine lists acme (its key not checked yet, say): it stays connected.
        assertTrue("acme" in ProviderRules.connected(setOf("acme"), report, catalog))
        // A custom endpoint's placeholder key counts too.
        assertTrue("custom-host" in ProviderRules.connected(setOf("custom-host"), report, catalog + "custom-host"))
    }

    @Test
    fun `a key for a provider no package registers any more is not a connected model`() {
        assertFalse("gone" in ProviderRules.connected(setOf("gone", "openai"), report, catalog))
        assertTrue("openai" in ProviderRules.connected(setOf("gone", "openai"), report, catalog))
    }

    @Test
    fun `extension providers exclude the catalog's built-ins and custom endpoints`() {
        // Pi's own llama.cpp provider (no models, no key it names, no sign-in) is not offered either.
        val list = ProviderRules.extensionProviders(report, catalog, custom = setOf("local-llm"))
        assertEquals(listOf("acme", "sso"), list.map { it.id })
    }

    @Test
    fun `Settings lists stored providers and extension providers once each, by name`() {
        val rows = ProviderRules.rows(setOf("openai", "acme"), report, catalog, emptySet(), ProviderCatalog::name)
        assertEquals(listOf("acme", "local-llm", "openai", "sso"), rows.map { it.id })
        val acme = rows.first { it.id == "acme" }
        assertEquals("Acme AI", acme.name)
        assertEquals(ProviderRules.Kind.Extension, acme.kind)
        assertTrue(acme.stored)
        val sso = rows.first { it.id == "sso" }
        assertFalse(sso.stored)
        assertFalse(sso.connected)
        assertTrue(sso.needsSetup)
        val local = rows.first { it.id == "local-llm" }
        assertTrue(local.connected)
        assertFalse(local.needsSetup)
        assertEquals(ProviderRules.Kind.BuiltIn, rows.first { it.id == "openai" }.kind)
    }

    @Test
    fun `an extension provider's note says what it needs`() {
        assertEquals("Needs a key (ACME_API_KEY)", ProviderRules.extensionNote(report["acme"]!!, stored = false))
        assertEquals("Sign in to use it", ProviderRules.extensionNote(report["sso"]!!, stored = false))
        assertEquals("1 model", ProviderRules.extensionNote(report["local-llm"]!!, stored = false))
    }

    @Test
    fun `extension providers get their own names, and a mark from their id or name`() {
        ProviderCatalog.registerExtensions(report.providers)
        assertEquals("Acme AI", ProviderCatalog.name("acme"))
        assertEquals("Anthropic", ProviderCatalog.name("anthropic"))
        assertEquals(Brand.Generic, ProviderCatalog.brand("acme"))
        ProviderCatalog.registerExtensions(listOf(report["acme"]!!.copy(id = "acme-gateway", name = "Acme Ollama")))
        assertEquals(Brand.Ollama, ProviderCatalog.brand("acme-gateway"))
        ProviderCatalog.registerExtensions(emptyList())
    }

    @Test
    fun `the login flow follows the helper's dialogs and steps`() {
        var s = ProviderLogin.State()
        s = ProviderLogin.reduce(s, obj("""{"type": "extension_ui_request", "id": "d1", "method": "select", "title": "How?", "options": ["Browser", "Code"]}"""))
        assertEquals("d1", s.dialog?.id)
        assertEquals(listOf("Browser", "Code"), s.dialog?.options)
        s = ProviderLogin.reduce(s, obj("""{"type": "extension_ui_dismissed", "id": "d1"}"""))
        assertNull(s.dialog)
        val status = """{"type": "extension_ui_request", "id": "x", "method": "setStatus", "statusKey": "${AppExtension.LOGIN_STATUS}", "statusText": "{\"type\":\"auth_url\",\"url\":\"https://login.example.com/a?b=1\",\"instructions\":\"Approve\"}"}"""
        s = ProviderLogin.reduce(s, obj(status))
        val open = assertIs<ProviderLogin.Step.OpenUrl>(s.step)
        assertEquals("https://login.example.com/a?b=1", open.url)
        assertEquals("Approve", open.instructions)
        val device = """{"type": "extension_ui_request", "id": "y", "method": "setStatus", "statusKey": "${AppExtension.LOGIN_STATUS}", "statusText": "{\"type\":\"device_code\",\"userCode\":\"AB-12\",\"verificationUri\":\"https://example.com/device\"}"}"""
        s = ProviderLogin.reduce(s, obj(device))
        assertEquals(ProviderLogin.Step.DeviceCode("AB-12", "https://example.com/device"), s.step)
        val progress = """{"type": "extension_ui_request", "id": "z", "method": "setStatus", "statusKey": "${AppExtension.LOGIN_STATUS}", "statusText": "{\"type\":\"progress\",\"message\":\"Exchanging the code\"}"}"""
        s = ProviderLogin.reduce(s, obj(progress))
        assertEquals("Exchanging the code", s.progress)
    }

    @Test
    fun `a login step never opens a link that is not a web page, and other statuses are not steps`() {
        val bad = """{"type": "extension_ui_request", "id": "x", "method": "setStatus", "statusKey": "${AppExtension.LOGIN_STATUS}", "statusText": "{\"type\":\"auth_url\",\"url\":\"intent://evil\"}"}"""
        assertNull(ProviderLogin.reduce(ProviderLogin.State(), obj(bad)).step)
        val other = """{"type": "extension_ui_request", "id": "x", "method": "setStatus", "statusKey": "todo", "statusText": "{\"type\":\"auth_url\",\"url\":\"https://a.example\"}"}"""
        assertNull(ProviderLogin.reduce(ProviderLogin.State(), obj(other)).step)
    }

    @Test
    fun `the login can be answered, declined and cancelled`() {
        val sent = mutableListOf<JsonObject>()
        var stopped = false
        val login = ProviderLogin("sso")
        login.attach(send = { sent += it; true }, stop = { stopped = true })
        login.on(obj("""{"type": "extension_ui_request", "id": "d1", "method": "input", "title": "Paste the code"}"""))
        login.answer("d1", "123456")
        assertNull(login.state.value.dialog)
        assertEquals("""{"type":"extension_ui_response","id":"d1","value":"123456"}""", sent.last().toString())
        login.on(obj("""{"type": "extension_ui_request", "id": "d2", "method": "input", "title": "Again"}"""))
        login.decline("d2")
        assertEquals("""{"type":"extension_ui_response","id":"d2","cancelled":true}""", sent.last().toString())
        login.cancel()
        assertTrue(stopped)
        assertTrue(login.state.value.finished)
        assertEquals(ProviderLogin.CANCELLED, login.state.value.error)
        assertFalse(login.state.value.succeeded)
    }

    @Test
    fun `the helper extension has the providers and login actions and no Kotlin template left in it`() {
        val source = AppExtension.SOURCE
        assertTrue("action === \"providers\"" in source)
        assertTrue("action === \"login\"" in source)
        assertTrue("\"${AppExtension.LOGIN_STATUS}\"" in source)
        assertTrue(source.lineSequence().first().startsWith("// pibolt-app v2:"))
        // The `$NAME` reader survived the raw string.
        assertTrue("""raw.replaceAll("$$", "")""" in source)
    }

    @Test
    fun `the spare can be looked at and taken`() {
        val pool = IdlePool<String>(maxParked = 1) { null }
        assertNull(pool.fresh())
        pool.addFresh("spare", "/home/projects")
        assertEquals("spare", pool.fresh()?.engine)
        assertEquals("spare", pool.takeFresh()?.engine)
        assertNull(pool.fresh())
        assertFalse(pool.hasFresh())
    }
}
