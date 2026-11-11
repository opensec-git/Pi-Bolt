package opensec.pibolt.app

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import opensec.pibolt.app.git.github.AesGcmBox
import opensec.pibolt.app.git.github.KeyValue
import opensec.pibolt.app.ui.packages.CatalogException
import opensec.pibolt.app.ui.packages.Npmrc
import opensec.pibolt.app.ui.packages.NpmrcEntry
import opensec.pibolt.app.ui.packages.NpmrcRun
import opensec.pibolt.app.ui.packages.PackageCatalog
import opensec.pibolt.app.ui.packages.RegistryConfig
import opensec.pibolt.app.ui.packages.RegistryStore
import opensec.pibolt.app.ui.packages.loadDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.KeyGenerator

class PrivateRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val token = "npm_FAKEtoken0123456789abcdefABCDEF"

    @Test
    fun rendersScopesTokensAndRegistries() {
        val text = Npmrc.render(
            listOf(
                NpmrcEntry("https://npm.example.com/api/npm/", "@company", token),
                NpmrcEntry("https://npm.pkg.github.com", "@gh", "ghp_abcdefghijklmnopqrstuvwxyz0123"),
                NpmrcEntry("https://mirror.example.org:8443/repo", null, "eyJhbGciOi.payload_part-x.sig+/="),
                NpmrcEntry(Npmrc.DEFAULT_REGISTRY, null, null),
            ),
        )
        assertEquals(
            """
            # Written by Pi-Bolt for one install; removed when it ends.
            @company:registry=https://npm.example.com/api/npm/
            //npm.example.com/api/npm/:_authToken=$token
            @gh:registry=https://npm.pkg.github.com/
            //npm.pkg.github.com/:_authToken=ghp_abcdefghijklmnopqrstuvwxyz0123
            registry=https://mirror.example.org:8443/repo/
            //mirror.example.org:8443/repo/:_authToken=eyJhbGciOi.payload_part-x.sig+/=
            """.trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun valuesThatCouldInjectAreRefused() {
        // A token or URL that could add a line, a comment, JSON or an environment reference never reaches the file.
        for (bad in listOf("abc\nregistry=https://evil", "abc def12345", "\${HOME}token1", "tok;en12345", "tok#en12345", "\"quoted12345\"", "short")) {
            assertNotNull(bad, Npmrc.tokenProblem(bad))
            assertFalse(Npmrc.render(listOf(NpmrcEntry("https://r.example.com/", "@c", bad))).contains("_authToken"))
        }
        for (bad in listOf("https://r.example.com/\nregistry=https://evil", "https://r.example.com/\${X}", "ftp://r.example.com/", "http://r.example.com/", "https://u:p@r.example.com/", "https://r.example.com/?a=1", "not a url")) {
            assertNotNull(bad, Npmrc.urlProblem(bad))
            assertEquals("# Written by Pi-Bolt for one install; removed when it ends.\n", Npmrc.render(listOf(NpmrcEntry(bad, "@c", token))))
        }
        assertNull(Npmrc.urlProblem("http://127.0.0.1:4873/"))
        assertNull(Npmrc.urlProblem("http://localhost:4873"))
        assertEquals("@company", Npmrc.normalizeScope(" Company "))
        assertNull(Npmrc.normalizeScope(""))
        assertNotNull(Npmrc.scopeProblem("@bad scope"))
        assertNotNull(Npmrc.scopeProblem("@a/b"))
        assertNull(Npmrc.scopeProblem("@my-co.pkgs"))
        assertEquals("//r.example.com/", Npmrc.nerfDart("https://R.example.com"))
        assertEquals("//127.0.0.1:4873/sub/", Npmrc.nerfDart("http://127.0.0.1:4873/sub"))
    }

    private class MapKv : KeyValue {
        val map = HashMap<String, String>()

        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private fun box(): AesGcmBox {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmBox { key }
    }

    @Test
    fun storeSealsTheTokenAndNeverShowsIt() {
        val kv = MapKv()
        val store = RegistryStore(kv, box())
        assertNull(store.config())
        assertTrue(store.entries().isEmpty())
        store.save(RegistryConfig("https://npm.example.com/", "@company"))
        store.saveToken(token)
        assertTrue(store.hasToken)
        assertEquals(token, store.token())
        // At rest the token is ciphertext; toString and the entries' toString never carry it.
        assertTrue(kv.map.values.none { token in it })
        assertFalse(token in store.toString())
        assertFalse(token in store.entries().toString())
        assertEquals(listOf(NpmrcEntry("https://npm.example.com/", "@company", token)), store.entries())
        assertThrows(IllegalArgumentException::class.java) { store.saveToken("bad token") }
        store.removeToken()
        assertFalse(store.hasToken)
        assertNull(store.token())
        store.clear()
        assertNull(store.config())
        // A token sealed with another key (a reset Keystore) reads as none.
        val other = RegistryStore(kv, box())
        store.saveToken(token)
        assertNull(other.token())
    }

    @Test
    fun aNewRegistryDropsTheOldToken() {
        val store = RegistryStore(MapKv(), box())
        store.save(RegistryConfig("https://npm.company.com/", "@company"))
        store.saveToken(token)
        store.save(RegistryConfig("https://npm.company.com/", "@other"))
        assertEquals(token, store.token())
        store.save(RegistryConfig("https://mirror.example.org/", "@company"))
        assertNull(store.token())
        assertFalse(store.hasToken)
    }

    @Test
    fun urlsAreCanonical() {
        assertNotNull(Npmrc.urlProblem("https://npm.example.com:99999/"))
        assertNotNull(Npmrc.urlProblem("https://npm.example.com:0/"))
        assertNull(Npmrc.urlProblem("https://npm.example.com:8443/"))
        assertEquals("https://npm.example.com/api/", Npmrc.normalizeUrl(" HTTPS://NPM.Example.com/api "))
        assertEquals("http://127.0.0.1:4873/", Npmrc.normalizeUrl("http://127.0.0.1:4873"))
    }

    @Test
    fun privateSearchPagesPastFilteredResults() = runBlocking {
        val server = MockWebServer()
        // Page one: 1 of ours among 3; the registry has 5 in all, so Show more continues from its offset 3.
        server.enqueue(MockResponse.Builder().body("""{"objects":[{"package":{"name":"@co/a","version":"1"}},{"package":{"name":"@x/b","version":"1"}},{"package":{"name":"@x/c","version":"1"}}],"total":5}""").build())
        server.enqueue(MockResponse.Builder().body("""{"objects":[{"package":{"name":"@co/d","version":"1"}},{"package":{"name":"@x/e","version":"1"}}],"total":5}""").build())
        server.start()
        try {
            // An upper-case host in the saved URL still gets the token (requests are compared parsed).
            val base = server.url("/").toString().trimEnd('/').replace("localhost", "LOCALHOST")
            val private = PackageCatalog(OkHttpClient(), registry = base, authToken = token, private = true)
            val first = private.search("", scope = "@co")
            assertEquals(listOf("@co/a"), first.entries.map { it.name })
            assertEquals(3, first.nextFrom)
            assertTrue(first.hasMore)
            val second = private.search("", from = first.nextFrom, scope = "@co")
            assertEquals(listOf("@co/d"), second.entries.map { it.name })
            assertFalse(second.hasMore)
            assertEquals("Bearer $token", server.takeRequest().headers["Authorization"])
            assertEquals("3", server.takeRequest().url.queryParameter("from"))
        } finally {
            server.close()
        }
    }

    @Test
    fun configServesItsScope() {
        val scoped = RegistryConfig("https://npm.example.com/", "@company")
        assertTrue(scoped.serves("@company/pi-x"))
        assertFalse(scoped.serves("pi-x"))
        assertFalse(scoped.serves("@companyx/pi"))
        assertTrue(RegistryConfig("https://mirror.example.com/", null).serves("pi-x"))
        assertFalse(RegistryConfig(Npmrc.DEFAULT_REGISTRY, null).serves("pi-x"))
    }

    @Test
    fun npmrcFileIsPrivateAndGoneAfterTheRun() = runBlocking {
        val dir = tmp.newFolder("no_backup")
        var seen: File? = null
        val out = NpmrcRun.with(dir, listOf(NpmrcEntry("https://npm.example.com/", "@company", token))) { env ->
            val f = File(env.getValue(NpmrcRun.ENV))
            seen = f
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
            assertTrue(f.readText().contains(":_authToken=$token"))
            "npm output with $token in it"
        }
        assertFalse(seen!!.exists())
        assertEquals("npm output with [token] in it", Npmrc.redact(out, token))
        // A failing run still removes it.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { NpmrcRun.with(dir, listOf(NpmrcEntry("https://npm.example.com/", null, token))) { env -> seen = File(env.getValue(NpmrcRun.ENV)); error("npm failed") } }
        }
        assertFalse(seen!!.exists())
        // No registry: no file, no environment.
        assertEquals(emptyMap<String, String>(), NpmrcRun.with(dir, emptyList()) { it })
    }

    @Test
    fun privateCatalogSendsTheTokenOnlyToItsRegistry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("""{"objects":[{"package":{"name":"@company/pi-a","version":"1.0.0"}},{"package":{"name":"other","version":"1"}}],"total":2}""").build())
        server.enqueue(MockResponse.Builder().body("""{"name":"@company/pi-a","version":"1.0.0","pi":{"themes":["./t.json"]}}""").build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        server.start()
        try {
            val base = server.url("/npm").toString().trimEnd('/')
            val private = PackageCatalog(OkHttpClient(), registry = base, authToken = token, private = true)
            val page = private.search("", scope = "@company")
            assertEquals(listOf("@company/pi-a"), page.entries.map { it.name })
            val search = server.takeRequest()
            assertEquals("Bearer $token", search.headers["Authorization"])
            assertEquals("company", search.url.queryParameter("text"))

            // A private package's detail comes from the registry alone, with no public lookups.
            val detail = loadDetail(PackageCatalog(OkHttpClient(), registry = "http://127.0.0.1:9"), "npm:@company/pi-a", null, RegistryConfig("$base/", "@company") to private)
            assertNull(detail.error)
            assertTrue(detail.fromPrivate)
            assertNull(detail.weeklyDownloads)
            assertEquals("/npm/@company%2Fpi-a/latest", server.takeRequest().target)

            // No search API: install by name.
            assertEquals(PackageCatalog.NO_SEARCH, assertThrows(CatalogException::class.java) { runBlocking { private.search("x", scope = "@company") } }.message)
            val refused = assertThrows(CatalogException::class.java) { runBlocking { private.manifest("@company/pi-b") } }
            assertEquals(401, refused.status)
            assertFalse(refused.message!!.contains(token))

            // A public catalog never sends it.
            val public = PackageCatalog(OkHttpClient(), registry = base)
            server.enqueue(MockResponse.Builder().body("""{"objects":[],"total":0}""").build())
            public.search("")
            server.takeRequest()
            server.takeRequest()
            assertNull(server.takeRequest().headers["Authorization"])
        } finally {
            server.close()
        }
    }
}
