package opensec.pibolt.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.app.engine.AssetSource
import opensec.pibolt.app.engine.AuthFile
import opensec.pibolt.app.engine.BundledExtensions
import opensec.pibolt.app.engine.EngineManifest
import opensec.pibolt.app.engine.EnginePaths
import opensec.pibolt.app.engine.EnginePrefix
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.engine.engineEnvironment
import opensec.pibolt.app.engine.engineUnavailableReason
import opensec.pibolt.app.engine.resolvConf
import opensec.pibolt.app.secrets.SecretEnv
import opensec.pibolt.app.terminal.shellEnvironment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import opensec.pibolt.app.engine.RuntimeStorage
import opensec.pibolt.app.engine.RuntimeStore
import opensec.pibolt.app.engine.formatBytes
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
          "package": [ "package.json", "theme/dark.json", "examples/.gitignore" ], "env": { "SHELL": "/system/bin/sh", "LD_PRELOAD": "no.so" },
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
        assertEquals(listOf("package.json", "theme/dark.json", "examples/.gitignore"), m.packageFiles)
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
        assertEquals("${File(root, "data/files/home/.local/bin").path}:${p.prefix.path}/bin:/system/bin", env["PATH"])
        assertEquals(File(root, "data/cache/xdg/npm").path, env["npm_config_cache"])
        assertEquals(File(root, "data/cache/xdg/pip").path, env["PIP_CACHE_DIR"])
        assertEquals(File(root, "data/files/home/.local").path, env["PYTHONUSERBASE"])
        // The pack's PATH still gets the user's bin dir in front.
        assertEquals("${p.userBase.path}/bin:/x/bin", engineEnvironment(p, emptyMap(), "UTC", mapOf("PATH" to "/x/bin"))["PATH"])
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
    fun esbuildBinaryPathIsThePacksLinkToNativeLibraryDirAndSecretsCannotOverrideIt() {
        val p = paths()
        // Not shipped (an engine-only pack): esbuild's npm package keeps its own binary, and a stale value goes.
        assertNull(engineEnvironment(p, mapOf("ESBUILD_BINARY_PATH" to "/x/esbuild"), "UTC")["ESBUILD_BINARY_PATH"])

        p.nativeLibDir.mkdirs()
        File(p.nativeLibDir, "libesbuild_exe.so").writeText("ELF")
        val m = EngineManifest.parse(
            manifestJson.replace(
                """{ "path": "lib/libc.so.6", "target": "libc_so_6.so" }""",
                """{ "path": "lib/libc.so.6", "target": "libc_so_6.so" }, { "path": "bin/esbuild", "target": "libesbuild_exe.so" }""",
            ),
        )
        assertTrue(EnginePrefix(p, MapAssets(mapOf("engine/files/ca-certificates.crt" to "CERTS"))).ensure(m))
        val env = engineEnvironment(p, emptyMap(), "UTC")
        // A stable path (esbuild's postinstall writes it into node_modules) that resolves to nativeLibraryDir's file.
        assertEquals(File(p.bin, "esbuild").path, env["ESBUILD_BINARY_PATH"])
        assertEquals(File(p.nativeLibDir, "libesbuild_exe.so").canonicalPath, File(env.getValue("ESBUILD_BINARY_PATH")).canonicalPath)
        // After an app update the link follows nativeLibraryDir; the variable stays the same.
        val moved = paths(File(root, "nlib2"))
        moved.nativeLibDir.mkdirs()
        File(moved.nativeLibDir, "libesbuild_exe.so").writeText("ELF")
        assertTrue(EnginePrefix(moved, MapAssets(mapOf("engine/files/ca-certificates.crt" to "CERTS"))).ensure(m))
        val updated = engineEnvironment(moved, emptyMap(), "UTC")
        assertEquals(env["ESBUILD_BINARY_PATH"], updated["ESBUILD_BINARY_PATH"])
        assertEquals(File(moved.nativeLibDir, "libesbuild_exe.so").canonicalPath, File(updated.getValue("ESBUILD_BINARY_PATH")).canonicalPath)

        // A project secret of that name is refused in the form and never replaces the value.
        assertNotNull(SecretEnv.nameProblem("ESBUILD_BINARY_PATH"))
        assertEquals(env["ESBUILD_BINARY_PATH"], SecretEnv.inject(env, mapOf("ESBUILD_BINARY_PATH" to "/evil/esbuild"))["ESBUILD_BINARY_PATH"])
        assertEquals(env["ESBUILD_BINARY_PATH"], shellEnvironment(env, mapOf("ESBUILD_BINARY_PATH" to "/evil/esbuild"))["ESBUILD_BINARY_PATH"])
    }

    @Test
    fun theTerminalShellGetsTheRuntimesPathTheGitHubHelperAndSecretsThatCannotOverrideThem() {
        val p = paths()
        val git = mapOf("GIT_CONFIG_COUNT" to "2", "PIBOLT_GITHUB_TOKEN" to "gho_x")
        val engine = engineEnvironment(p, emptyMap(), "UTC", mapOf("PATH" to "${p.prefix.path}/bin:/system/bin"), git)
        val secrets = mapOf("API_KEY" to "k", "PATH" to "/evil", "PYTHONUSERBASE" to "/evil", "GIT_CONFIG_COUNT" to "0", "PIBOLT_GITHUB_TOKEN" to "stolen")
        val shell = shellEnvironment(engine, secrets)
        // node, npm, python3 and pip in PREFIX/bin; npm -g and pip install commands in HOME/.local/bin, first.
        assertEquals("${p.userBase.path}/bin:${p.prefix.path}/bin:/system/bin", shell["PATH"])
        assertEquals(p.userBase.path, shell["PYTHONUSERBASE"])
        assertEquals("2", shell["GIT_CONFIG_COUNT"])
        assertEquals("gho_x", shell["PIBOLT_GITHUB_TOKEN"])
        assertEquals("k", shell["API_KEY"])
        assertEquals("xterm-256color", shell["TERM"])
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
        assertEquals(2, list[1].messageCount, "the saved system prompt is not a message")
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
        // The file is renamed into place before the lock is released: wait for the release (slow CI runners saw it held).
        withTimeout(5_000) { while (File(agent, "auth.json.lock").exists()) kotlinx.coroutines.delay(10) }

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

    @Test
    fun bundledExtensionsMergeIntoPisPackages() {
        val root = "/usr/share/pi-extensions"
        val todo = "$root/opensec-pi-todo"
        val agents = "$root/opensec-pi-subagents"
        fun json(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

        // No packages yet: both added, other settings kept.
        assertEquals(
            json("""{"theme":"dark","packages":["$todo","$agents"]}"""),
            BundledExtensions.merge(json("""{"theme":"dark"}"""), listOf(todo, agents), root),
        )
        // The user's packages stay; a duplicate and an older pack's extension go; the user's filtered entry is kept.
        assertEquals(
            json("""{"packages":["npm:foo@1",{"source":"$todo","extensions":["x"]},"./local","$agents"]}"""),
            BundledExtensions.merge(
                json("""{"packages":["npm:foo@1",{"source":"$todo","extensions":["x"]},"$root/opensec-pi-old","./local","$todo/"]}"""),
                listOf(todo, agents),
                root,
            ),
        )
        // Already registered, or packages Pi would reject: the file is left alone.
        assertNull(BundledExtensions.merge(json("""{"packages":["$todo","$agents"]}"""), listOf(todo, agents), root))
        assertNull(BundledExtensions.merge(json("""{"packages":"npm:foo"}"""), listOf(todo), root))
        assertNull(BundledExtensions.merge(json("""{}"""), emptyList(), root))
        // A pack without extensions drops the old ones.
        assertEquals(json("""{"packages":["npm:foo"]}"""), BundledExtensions.merge(json("""{"packages":["npm:foo","$todo"]}"""), emptyList(), root))
    }

    @Test
    fun prefixSetupCopiesAndRegistersBundledExtensions() {
        val p = paths()
        val m = EngineManifest.parse(
            manifestJson.replace(
                "\"env\":",
                "\"extensions\": [ { \"name\": \"opensec-pi-todo\", \"version\": \"2.13.1\", \"files\": [ \"package.json\", \"dist-bun/index.js\" ] } ], \"env\":",
            ),
        )
        assertEquals(listOf(EngineManifest.Extension("opensec-pi-todo", "2.13.1", listOf("package.json", "dist-bun/index.js"))), m.extensions)
        val assets = MapAssets(
            mapOf(
                "engine/files/ca-certificates.crt" to "CERTS",
                "engine/extensions/opensec-pi-todo/package.json" to "{\"name\":\"opensec-pi-todo\"}",
                "engine/extensions/opensec-pi-todo/dist-bun/index.js" to "export default () => {}",
            ),
        )
        p.agentDir.mkdirs()
        val settings = File(p.agentDir, "settings.json")
        val stale = File(p.extensionsDir, "opensec-pi-old").path
        settings.writeText("""{"defaultProvider":"fake","packages":["npm:foo@1","$stale"]}""")
        File(p.extensionsDir, "opensec-pi-old/index.js").apply { parentFile.mkdirs(); writeText("old") }

        assertTrue(EnginePrefix(p, assets).ensure(m))
        val todo = File(p.extensionsDir, "opensec-pi-todo")
        assertEquals("export default () => {}", File(todo, "dist-bun/index.js").readText())
        assertFalse(File(p.extensionsDir, "opensec-pi-old").exists())
        assertEquals(
            "{\n  \"defaultProvider\": \"fake\",\n  \"packages\": [\n    \"npm:foo@1\",\n    \"${todo.path}\"\n  ]\n}",
            settings.readText(),
        )
        assertFalse(File(p.agentDir, "settings.json.lock").exists())
        assertTrue(p.agentDir.listFiles()!!.none { it.name.endsWith(".tmp") })

        // An unreadable settings.json is left as it is.
        settings.writeText("{ not json")
        assertFalse(BundledExtensions.register(p.agentDir, listOf(todo.path), p.extensionsDir.path))
        assertEquals("{ not json", settings.readText())
    }

    @Test
    fun prefixSetupUnpacksAnExtensionsNodeModules() {
        val p = paths()
        // A third-party extension with runtime dependencies (android/engine/npm): a scoped package ships as "scope-pkg".
        val m = EngineManifest.parse(
            manifestJson.replace(
                "\"env\":",
                """"extensions": [ { "name": "juicesharp-rpiv-ask-user-question", "package": "@juicesharp/rpiv-ask-user-question",
                     "version": "2.15.0", "files": [ "package.json", "index.ts" ],
                     "modules": { "asset": "engine/modules/juicesharp-rpiv-ask-user-question.zip", "sha256": "x", "files": 2, "bytes": 9 } },
                   { "name": "opensec-pi-todo", "version": "2.13.1", "files": [ "package.json" ] } ], "env":""",
            ),
        )
        assertEquals(
            EngineManifest.Extension(
                "juicesharp-rpiv-ask-user-question", "2.15.0", listOf("package.json", "index.ts"),
                "engine/modules/juicesharp-rpiv-ask-user-question.zip", "@juicesharp/rpiv-ask-user-question", modulesSha256 = "x",
            ),
            m.extensions[0],
        )
        assertEquals(EngineManifest.Extension("opensec-pi-todo", "2.13.1", listOf("package.json")), m.extensions[1])
        assertFailsWith<IllegalArgumentException> { EngineManifest.parse(manifestJson.replace("\"env\":", """"extensions": [ { "name": "@a/b", "version": "1", "files": [] } ], "env":""")) }
        val assets = BytesAssets(
            mapOf(
                "engine/files/ca-certificates.crt" to "CERTS".toByteArray(),
                "engine/extensions/juicesharp-rpiv-ask-user-question/package.json" to "{}".toByteArray(),
                "engine/extensions/juicesharp-rpiv-ask-user-question/index.ts" to "export default 1".toByteArray(),
                "engine/extensions/opensec-pi-todo/package.json" to "{}".toByteArray(),
                "engine/modules/juicesharp-rpiv-ask-user-question.zip" to zip("@juicesharp/rpiv-config/index.ts" to "x", "@juicesharp/rpiv-config/_internal/a.js" to "y"),
            ),
        )
        assertTrue(EnginePrefix(p, assets).ensure(m))
        val ask = File(p.extensionsDir, "juicesharp-rpiv-ask-user-question")
        assertEquals("export default 1", File(ask, "index.ts").readText())
        assertEquals("x", File(ask, "node_modules/@juicesharp/rpiv-config/index.ts").readText())
        assertTrue(File(ask, "node_modules/@juicesharp/rpiv-config/_internal/a.js").isFile)
        assertFalse(File(p.extensionsDir, "opensec-pi-todo/node_modules").exists())
        val registered = File(p.agentDir, "settings.json").readText()
        assertTrue(ask.path in registered && File(p.extensionsDir, "opensec-pi-todo").path in registered, registered)
    }

    private class BytesAssets(private val files: Map<String, ByteArray>) : AssetSource {
        override fun open(path: String): InputStream = files[path]?.let(::ByteArrayInputStream) ?: throw FileNotFoundException(path)

        override fun list(path: String): List<String> = emptyList()
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val runtimesJson = manifestJson.replace(
        "\"env\":",
        """"trees": [ { "path": "lib/python3.14", "asset": "engine/trees/python.zip", "sha256": "x", "files": 3, "bytes": 10,
                       "executables": [ "bin-ish/run.py" ] } ],
          "runtimes": [ { "id": "node", "name": "Node.js", "version": "22.23.4", "commands": [ "node", "npm", "npx" ], "bytes": 150000000,
                          "details": { "npm": "10.9.4" } },
                        { "id": "python", "name": "Python", "version": "3.14.10", "commands": [ "python3", "pip" ], "bytes": 60000000,
                          "details": { "pip": "25.2" } } ],
          "env":""",
    ).replace(
        "{ \"path\": \"lib/ld-linux-aarch64.so.1\", \"target\": \"libld.so\" }",
        "{ \"path\": \"lib/ld-linux-aarch64.so.1\", \"target\": \"libld.so\" }, " +
            "{ \"path\": \"lib/python3.14/lib-dynload/_ssl.cpython-314-aarch64-linux-gnu.so\", \"target\": \"lib_ssl_cpython_314_aarch64_linux_gnu_so.so\" }",
    )

    @Test
    fun manifestParsesTreesAndRuntimes() {
        val m = EngineManifest.parse(runtimesJson)
        assertEquals(listOf(EngineManifest.Tree("lib/python3.14", "engine/trees/python.zip", listOf("bin-ish/run.py"), sha256 = "x")), m.trees)
        assertEquals(listOf("node", "python"), m.runtimes.map { it.id })
        assertEquals(EngineManifest.Runtime("node", "Node.js", "22.23.4", listOf("node", "npm", "npx"), 150_000_000, mapOf("npm" to "10.9.4")), m.runtimes[0])
        assertFailsWith<IllegalArgumentException> { EngineManifest.parse(runtimesJson.replace("\"lib/python3.14\"", "\"../python\"")) }
        // An older pack without them.
        assertEquals(emptyList(), EngineManifest.parse(manifestJson).trees)
        assertEquals(emptyList(), EngineManifest.parse(manifestJson).runtimes)
    }

    @Test
    fun prefixUnpacksRuntimeTreesWithTheirModuleLinks() {
        val p = paths()
        val m = EngineManifest.parse(runtimesJson)
        val assets = BytesAssets(
            mapOf(
                "engine/files/ca-certificates.crt" to "CERTS".toByteArray(),
                "engine/trees/python.zip" to zip("os.py" to "import sys", "_pyrepl/__init__.py" to "", "bin-ish/run.py" to "#!x"),
            ),
        )
        // Left from an older version: goes with the old tree.
        File(p.prefix, "lib/python3.14/__pycache__/old.pyc").apply { parentFile.mkdirs(); writeText("old") }
        assertTrue(EnginePrefix(p, assets).ensure(m))
        val lib = File(p.prefix, "lib/python3.14")
        assertEquals("import sys", File(lib, "os.py").readText())
        assertTrue(File(lib, "_pyrepl/__init__.py").isFile)
        assertTrue(File(lib, "bin-ish/run.py").canExecute())
        assertFalse(File(lib, "os.py").canExecute())
        assertFalse(File(lib, "__pycache__").exists())
        assertEquals(
            File(p.nativeLibDir, "lib_ssl_cpython_314_aarch64_linux_gnu_so.so").toPath(),
            Files.readSymbolicLink(File(lib, "lib-dynload/_ssl.cpython-314-aarch64-linux-gnu.so").toPath()),
        )
        assertTrue(File(p.prefix, "lib").listFiles()!!.none { it.name.endsWith(".unpack") })

        // A zip entry outside the tree is refused, and the setup is not marked done.
        val evil = BytesAssets(mapOf("engine/files/ca-certificates.crt" to "C".toByteArray(), "engine/trees/python.zip" to zip("../../escape.txt" to "x")))
        val moved = paths(File(root, "nlib2"))
        // (Another zip than the one unpacked: an unchanged one would be kept.)
        val evilManifest = EngineManifest.parse(runtimesJson.replace("\"sha256\": \"x\"", "\"sha256\": \"evil\""))
        assertFailsWith<IllegalArgumentException> { EnginePrefix(moved, evil).ensure(evilManifest) }
        assertFalse(File(p.prefix, "escape.txt").exists())
        assertFalse(moved.stamp.exists())
    }

    /** A pack with the python tree and an extension with node_modules, the zips' sha256 as given. */
    private fun keptManifest(treeSha: String, modulesSha: String) = EngineManifest.parse(
        runtimesJson.replace("\"sha256\": \"x\"", "\"sha256\": \"$treeSha\"").replace(
            "\"env\":",
            """"extensions": [ { "name": "web", "version": "1", "files": [ "index.ts" ],
                 "modules": { "asset": "engine/modules/web.zip", "sha256": "$modulesSha", "files": 1, "bytes": 1 } } ], "env":""",
        ),
    )

    @Test
    fun anAppUpdateWithTheSamePackKeepsTheUnpackedTreesAndRenewsTheLinks() {
        val p = paths()
        val assets = BytesAssets(
            mapOf(
                "engine/files/ca-certificates.crt" to "CERTS".toByteArray(),
                "engine/trees/python.zip" to zip("os.py" to "import sys", "bin-ish/run.py" to "#!x"),
                "engine/extensions/web/index.ts" to "export default 1".toByteArray(),
                "engine/modules/web.zip" to zip("dep/index.js" to "v1"),
            ),
        )
        val first = EnginePrefix(p, assets)
        assertTrue(first.ensure(keptManifest("t1", "m1")))
        assertEquals(emptyList(), first.kept)
        val lib = File(p.prefix, "lib/python3.14")
        val modules = File(p.extensionsDir, "web/node_modules")
        // Written while it runs: bytecode caches, an extension's own state. Kept with the tree they belong to.
        File(lib, "__pycache__/os.pyc").apply { parentFile.mkdirs(); writeText("cache") }
        File(modules, "dep/.cache").writeText("state")
        File(p.extensionsDir, "web/old.ts").writeText("an older version's file")

        // The update moves nativeLibraryDir; the zips are the same.
        val moved = paths(File(root, "nlib2"))
        val second = EnginePrefix(moved, assets)
        assertTrue(second.ensure(keptManifest("t1", "m1")))
        assertEquals(listOf("lib/python3.14", "share/pi-extensions/web/node_modules"), second.kept)
        assertEquals("cache", File(lib, "__pycache__/os.pyc").readText())
        assertEquals("state", File(modules, "dep/.cache").readText())
        assertTrue(File(lib, "bin-ish/run.py").canExecute())
        assertEquals(
            File(root, "nlib2/lib_ssl_cpython_314_aarch64_linux_gnu_so.so").toPath(),
            Files.readSymbolicLink(File(lib, "lib-dynload/_ssl.cpython-314-aarch64-linux-gnu.so").toPath()),
        )
        // The extension's own files are copied again, as on every pack change.
        assertFalse(File(p.extensionsDir, "web/old.ts").exists())
        assertEquals("export default 1", File(p.extensionsDir, "web/index.ts").readText())
        assertFalse(File(p.prefix, ".unpack").exists())

        // A changed zip is unpacked again, and what was in the old tree goes.
        val third = EnginePrefix(paths(File(root, "nlib3")), assets)
        assertTrue(third.ensure(keptManifest("t2", "m2")))
        assertEquals(emptyList(), third.kept)
        assertFalse(File(lib, "__pycache__").exists())
        assertFalse(File(modules, "dep/.cache").exists())
        assertEquals("v1", File(modules, "dep/index.js").readText())

        // An unpack without its marker (cut short, or removed) is never kept.
        File(lib, EnginePrefix.UNPACKED).delete()
        File(modules, EnginePrefix.UNPACKED).delete()
        val fourth = EnginePrefix(paths(File(root, "nlib4")), assets)
        assertTrue(fourth.ensure(keptManifest("t2", "m2")))
        assertEquals(emptyList(), fourth.kept)
        assertEquals("import sys", File(lib, "os.py").readText())

        // A pack without the sha256 (older) keeps nothing.
        val fifth = EnginePrefix(paths(File(root, "nlib5")), assets)
        assertTrue(fifth.ensure(EngineManifest.parse(runtimesJson.replace("\"sha256\": \"x\", ", ""))))
        assertEquals(emptyList(), fifth.kept)
    }

    @Test
    fun manyEntriesUnpackCompletelyAcrossWorkers() {
        val entries = (0 until 1000).map { i -> "d${i % 37}/sub${i % 5}/f$i.js" to "content $i" }.toTypedArray()
        val assets = BytesAssets(
            mapOf(
                "engine/files/ca-certificates.crt" to "CERTS".toByteArray(),
                "engine/trees/python.zip" to zip(*entries),
                "engine/extensions/web/index.ts" to "x".toByteArray(),
                "engine/modules/web.zip" to zip(*entries.take(300).toTypedArray()),
            ),
        )
        for (workers in listOf(1, 4)) {
            val p = EnginePaths(File(root, "usr$workers"), File(root, "data$workers"), File(root, "nlib"))
            assertTrue(EnginePrefix(p, assets, workers).ensure(keptManifest("t", "m")))
            for ((name, text) in entries) assertEquals(text, File(p.prefix, "lib/python3.14/$name").readText())
            for ((name, text) in entries.take(300)) assertEquals(text, File(p.extensionsDir, "web/node_modules/$name").readText())
        }
    }

    @Test
    fun runtimeStorageIsMeasuredAndOnlyCachesClear() {
        val p = paths()
        val m = EngineManifest.parse(runtimesJson)
        File(p.cache, "npm/_cacache/content/a").apply { parentFile.mkdirs(); writeBytes(ByteArray(1500)) }
        File(p.cache, "pip/http/b").apply { parentFile.mkdirs(); writeBytes(ByteArray(700)) }
        File(p.userBase, "lib/node_modules/semver/index.js").apply { parentFile.mkdirs(); writeBytes(ByteArray(300)) }
        File(p.userBase, "lib/python3.14/site-packages/six.py").apply { parentFile.mkdirs(); writeBytes(ByteArray(200)) }
        // A link is neither followed nor counted.
        Files.createSymbolicLink(File(p.cache, "npm/link").toPath(), File(p.userBase, "lib").toPath())
        val usage = RuntimeStore.usage(p, m.runtimes).associate { it.kind to it.bytes }
        assertEquals(
            mapOf(RuntimeStorage.Kind.NpmCache to 1500L, RuntimeStorage.Kind.PipCache to 700L, RuntimeStorage.Kind.NpmGlobal to 300L, RuntimeStorage.Kind.PipUser to 200L),
            usage,
        )
        assertTrue(RuntimeStore.clear(p, RuntimeStorage.Kind.NpmCache))
        assertFalse(File(p.cache, "npm").exists())
        assertTrue(File(p.userBase, "lib/node_modules/semver/index.js").exists())
        assertFailsWith<IllegalArgumentException> { RuntimeStore.clear(p, RuntimeStorage.Kind.PipUser) }
        // Only the kinds of shipped runtimes.
        assertEquals(listOf(RuntimeStorage.Kind.PipCache, RuntimeStorage.Kind.PipUser), RuntimeStore.usage(p, m.runtimes.filter { it.id == "python" }).map { it.kind })
    }

    @Test
    fun bytesReadLikeAndroidStorage() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("860 KB", formatBytes(860_000))
        assertEquals("4.2 MB", formatBytes(4_200_000))
        assertEquals("128 MB", formatBytes(127_600_000))
        assertEquals("1.3 GB", formatBytes(1_300_000_000))
    }
}
