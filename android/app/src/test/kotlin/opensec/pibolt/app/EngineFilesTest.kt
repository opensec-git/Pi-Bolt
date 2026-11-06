package opensec.pibolt.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import opensec.pibolt.app.engine.AssetSource
import opensec.pibolt.app.engine.AuthFile
import opensec.pibolt.app.engine.EngineManifest
import opensec.pibolt.app.engine.EnginePaths
import opensec.pibolt.app.engine.EnginePrefix
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.engine.engineEnvironment
import opensec.pibolt.app.engine.engineUnavailableReason
import opensec.pibolt.app.engine.resolvConf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineFilesTest {
    private val root: File = Files.createTempDirectory("engine-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private val manifestJson = """
        { "version": 1, "engine": "1.1.1", "glibc": "2.44", "prefix": "/data/data/in.opensec.pibolt/files/usr",
          "links": [ { "path": "lib/libc.so.6", "target": "libc_so_6.so" }, { "path": "lib/ld-linux-aarch64.so.1", "target": "libld.so" } ],
          "files": [ { "path": "etc/ssl/certs/ca-certificates.crt", "asset": "engine/files/ca-certificates.crt" } ],
          "package": [ "package.json", "theme/dark.json" ], "env": { "SHELL": "/system/bin/sh", "LD_PRELOAD": "no.so" },
          "packFingerprint": "abc123" }
    """.trimIndent()

    private class MapAssets(private val files: Map<String, String>) : AssetSource {
        override fun open(path: String): InputStream = files[path]?.let { ByteArrayInputStream(it.toByteArray()) } ?: throw FileNotFoundException(path)

        override fun list(path: String): List<String> =
            files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct()
    }

    private fun paths(nativeLibDir: File = File(root, "nlib")) = EnginePaths(File(root, "usr"), File(root, "data"), nativeLibDir)

    @Test
    fun manifestParsesAndRejectsPathsOutsideThePrefix() {
        val m = EngineManifest.parse(manifestJson)
        assertEquals("1.1.1", m.engine)
        assertEquals(EngineManifest.Link("lib/libc.so.6", "libc_so_6.so"), m.links.first())
        assertEquals("abc123", m.packFingerprint)
        assertEquals(listOf("package.json", "theme/dark.json"), m.packageFiles)
        assertEquals(mapOf("SHELL" to "/system/bin/sh"), m.env)
        assertFailsWith<IllegalArgumentException> { EngineManifest.parse(manifestJson.replace("lib/libc.so.6", "../escape")) }
        assertFailsWith<IllegalArgumentException> { EngineManifest.parse(manifestJson.replace("libc_so_6.so", "/abs/libc.so")) }
    }

    @Test
    fun prefixSetupLinksCopiesAndSkipsWhenUnchanged() {
        val p = paths()
        val assets = MapAssets(
            mapOf(
                "engine/files/ca-certificates.crt" to "CERTS",
                "engine/package/package.json" to "{\"name\":\"pi-bolt\"}",
                "engine/package/theme/dark.json" to "{}",
            ),
        )
        val prefix = EnginePrefix(p, assets)
        val m = EngineManifest.parse(manifestJson)
        assertTrue(prefix.ensure(m))
        val libc = File(p.prefix, "lib/libc.so.6").toPath()
        assertTrue(Files.isSymbolicLink(libc))
        assertEquals(File(p.nativeLibDir, "libc_so_6.so").toPath(), Files.readSymbolicLink(libc))
        assertEquals("/system/bin/sh", Files.readSymbolicLink(File(p.bin, "sh").toPath()).toString())
        assertEquals("CERTS", File(p.prefix, "etc/ssl/certs/ca-certificates.crt").readText())
        assertEquals("{}", File(p.packageDir, "theme/dark.json").readText())
        assertTrue(p.projects.isDirectory)
        assertTrue(p.tmp.isDirectory)
        assertFalse(prefix.ensure(m))

        // An app update moves nativeLibraryDir: every link is made again, stale ones go.
        File(p.prefix, "lib/stale.so").toPath().let { Files.createSymbolicLink(it, File("/nowhere").toPath()) }
        val moved = paths(File(root, "nlib2"))
        assertTrue(EnginePrefix(moved, assets).ensure(m))
        assertEquals(File(root, "nlib2/libc_so_6.so").toPath(), Files.readSymbolicLink(libc))
        assertFalse(Files.exists(File(p.prefix, "lib/stale.so").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun environmentFollowsTheContract() {
        val p = paths()
        val env = engineEnvironment(p, mapOf("ANDROID_ROOT" to "/system", "LD_PRELOAD" to "x.so", "LD_LIBRARY_PATH" to "/x"), "Asia/Kolkata")
        assertEquals("/system", env["ANDROID_ROOT"])
        assertNull(env["LD_PRELOAD"])
        assertNull(env["LD_LIBRARY_PATH"])
        assertEquals(File(root, "data/files/home").path, env["HOME"])
        assertEquals("${p.prefix.path}/bin:/system/bin", env["PATH"])
        assertEquals("/system/bin/sh", env["SHELL"])
        assertEquals(p.packageDir.path, env["PI_PACKAGE_DIR"])
        assertEquals("Asia/Kolkata", env["TZ"])
        assertEquals(File(root, "data/cache/xdg").path, env["XDG_CACHE_HOME"])
        assertEquals(File(root, "data/files/home/.config").path, env["XDG_CONFIG_HOME"])
        assertTrue(env.values.none { it.startsWith("/sdcard") || it.startsWith("/storage") })
        assertNull(env["SSL_CERT_FILE"])
        File(p.prefix, "etc/ssl/certs").mkdirs()
        File(p.prefix, "etc/ssl/certs/ca-certificates.crt").writeText("x")
        File(p.bin, "bash").apply { parentFile.mkdirs(); writeText("") }
        val withPack = engineEnvironment(p, emptyMap(), "UTC")
        assertEquals(File(p.prefix, "etc/ssl/certs/ca-certificates.crt").path, withPack["SSL_CERT_FILE"])
        assertEquals(File(p.bin, "bash").path, withPack["SHELL"])
        assertEquals("/x/bash", engineEnvironment(p, emptyMap(), "UTC", mapOf("SHELL" to "/x/bash"))["SHELL"])
    }

    @Test
    fun resolvConfListsAtMostThreeServers() {
        assertEquals(
            "# Written by Pi-Bolt from the active network's DNS servers\nnameserver 10.0.2.3\nnameserver fe80::1%wlan0\n" +
                "nameserver 8.8.8.8\nsearch lan example.com\noptions timeout:2 attempts:2\n",
            resolvConf(listOf("10.0.2.3", "fe80::1%wlan0", "8.8.8.8", "1.1.1.1"), "lan, example.com"),
        )
    }

    @Test
    fun availabilityNamesTheReason() {
        val m = EngineManifest.parse(manifestJson)
        val ok = engineUnavailableReason("in.opensec.pibolt", listOf("arm64-v8a"), 36, m, true, "/data/user/0/in.opensec.pibolt", "/data/user/0/in.opensec.pibolt")
        assertNull(ok)
        assertNotNull(engineUnavailableReason("in.opensec.pibolt", listOf("x86_64"), 36, m, true, "/d", "/d")).let { assertTrue("ARM" in it) }
        assertNotNull(engineUnavailableReason("in.opensec.pibolt", listOf("arm64-v8a"), 28, m, true, "/d", "/d"))
        assertNotNull(engineUnavailableReason("in.opensec.pibolt", listOf("arm64-v8a"), 36, null, true, "/d", "/d")).let { assertTrue("does not include" in it) }
        assertNotNull(engineUnavailableReason("in.opensec.pibolt.debug", listOf("arm64-v8a"), 36, m, true, "/d", "/d")).let { assertTrue("another app id" in it) }
        assertNotNull(
            engineUnavailableReason("in.opensec.pibolt", listOf("arm64-v8a"), 36, m, true, "/data/user/10/in.opensec.pibolt", "/data/user/0/in.opensec.pibolt"),
        ).let { assertTrue("main profile" in it) }
    }

    @Test
    fun sessionsAreListedNewestFirstFromPisLayout() {
        val agent = File(root, "agent")
        val index = SessionIndex(agent)
        val project = File(root, "projects/demo").apply { mkdirs() }
        val dir = index.sessionDir(project.absolutePath)
        assertEquals("--" + project.absolutePath.removePrefix("/").replace('/', '-') + "--", dir.name)
        dir.mkdirs()
        val older = File(dir, "2026-11-01T10-00-00-000Z_aaa.jsonl")
        older.writeText(
            """{"type":"session","version":3,"id":"aaa","timestamp":"2026-11-01T10:00:00.000Z","cwd":"${project.path}"}
{"type":"message","id":"1","parentId":null,"timestamp":"x","message":{"role":"system","content":"","sections":{}}}
{"type":"message","id":"2","parentId":"1","timestamp":"x","message":{"role":"user","content":[{"type":"text","text":"Fix   the\nbuild"}],"timestamp":1}}
{"type":"message","id":"3","parentId":"2","timestamp":"x","message":{"role":"assistant","content":[{"type":"text","text":"Done"}]}}
{"type":"session_info","id":"4","parentId":"3","timestamp":"x","name":"Build fix"}
""",
        )
        older.setLastModified(1_000_000)
        val newer = File(dir, "2026-11-02T10-00-00-000Z_bbb.jsonl")
        newer.writeText(
            """{"type":"session","version":3,"id":"bbb","timestamp":"2026-11-02T10:00:00.000Z","cwd":"${project.path}"}
{"type":"message","id":"1","parentId":null,"timestamp":"x","message":{"role":"user","content":"plain string prompt"}}
""",
        )
        newer.setLastModified(2_000_000)
        File(dir, "notes.txt").writeText("not a session")
        File(dir, "broken.jsonl").writeText("garbage\n")

        val list = index.list(project)
        assertEquals(listOf("bbb", "aaa"), list.map { it.id })
        assertEquals("plain string prompt", list[0].preview)
        assertNull(list[0].name)
        assertEquals(1, list[0].messageCount)
        assertEquals("Build fix", list[1].name)
        assertEquals("Fix the build", list[1].preview)
        assertEquals(3, list[1].messageCount)
        assertEquals(1_000_000, list[1].updatedAt)
        assertEquals(newer.path, index.newest(project))
    }

    @Test
    fun authFileWritesLikePiAndKeepsOtherEntries() = runBlocking {
        val agent = File(root, "agent")
        agent.mkdirs()
        val file = File(agent, "auth.json")
        file.writeText("""{"anthropic":{"type":"oauth","access":"a","refresh":"r","expires":1}}""")
        val auth = AuthFile(agent, scope)
        auth.refresh()
        withTimeout(5_000) { auth.configured.first { it == setOf("anthropic") } }
        auth.setApiKey("openai", "  sk-test  ")
        withTimeout(5_000) { auth.configured.first { it == setOf("anthropic", "openai") } }
        withTimeout(5_000) { while (!file.readText().contains("openai")) kotlinx.coroutines.delay(10) }
        assertEquals(
            "{\n  \"anthropic\": {\n    \"type\": \"oauth\",\n    \"access\": \"a\",\n    \"refresh\": \"r\",\n    \"expires\": 1\n  },\n" +
                "  \"openai\": {\n    \"type\": \"api_key\",\n    \"key\": \"sk-test\"\n  }\n}",
            file.readText(),
        )
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertFalse(File(agent, "auth.json.lock").exists())

        auth.remove("anthropic")
        withTimeout(5_000) { while (file.readText().contains("anthropic")) kotlinx.coroutines.delay(10) }
        assertEquals(setOf("openai"), auth.configured.value)
    }

    @Test
    fun authFileWaitsForPisLockAndBreaksAStaleOne() {
        val agent = File(root, "agent").apply { mkdirs() }
        val lock = File(agent, "auth.json.lock").apply { mkdir() }
        lock.setLastModified(System.currentTimeMillis() - 60_000)
        val auth = AuthFile(agent, scope)
        assertNotNull(auth.modify { it + ("x" to kotlinx.serialization.json.JsonPrimitive(1)) })
        assertFalse(lock.exists())
        // A fresh lock held by Pi: give up after the retries rather than write under it.
        lock.mkdir()
        assertNull(auth.modify { it })
        lock.delete()
    }
}
