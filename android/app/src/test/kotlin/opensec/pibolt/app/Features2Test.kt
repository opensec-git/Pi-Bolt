package opensec.pibolt.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.ui.packages.BuiltinExtension
import opensec.pibolt.app.ui.packages.McpServers
import opensec.pibolt.app.ui.packages.PackageList
import opensec.pibolt.app.ui.packages.PackageResources
import opensec.pibolt.app.ui.packages.PackageScope
import opensec.pibolt.app.ui.session.features.CommandPalette
import opensec.pibolt.app.ui.session.features.Builtin
import opensec.pibolt.app.ui.session.features2.AgentTuning
import opensec.pibolt.app.ui.session.features2.AppExtension
import opensec.pibolt.app.ui.session.features2.BrowsedSession
import opensec.pibolt.app.ui.session.features2.Features2
import opensec.pibolt.app.ui.session.features2.ModelScope
import opensec.pibolt.app.ui.session.features2.PiJsonFile
import opensec.pibolt.app.ui.session.features2.ProjectTrust
import opensec.pibolt.app.ui.session.features2.Resources
import opensec.pibolt.app.ui.session.features2.SessionBrowse
import opensec.pibolt.app.ui.session.features2.SessionSort
import opensec.pibolt.app.ui.session.features2.readAtMost
import opensec.pibolt.app.ui.session.tree.SessionTree
import opensec.pibolt.app.ui.session.tree.TreeFilter
import opensec.pibolt.app.ui.session.tree.TreeKind
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.SlashCommand
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Features2Test {
    private fun json(text: String) = ProtocolJson.parseToJsonElement(text).jsonObject

    @Test
    fun treeLoadErrorsAreErrorsNotAnEmptyList() {
        val data = json("""{"entries":[],"leafId":null}""")
        assertEquals(data to null, SessionTree.load(true, data, null))
        // Success without data would leave the sheet on "Reading the tree" forever.
        assertEquals(null to "Pi sent no session tree.", SessionTree.load(true, null, null))
        assertEquals(null to "Unknown command: get_entries", SessionTree.load(false, null, " Unknown command: get_entries "))
        assertEquals(null to "Pi did not answer.", SessionTree.load(false, null, "  "))
        assertEquals(null to "Pi did not answer.", SessionTree.load(false, null, null))
    }

    // Session tree

    private fun user(id: String, parent: String?, text: String, ts: String) =
        """{"type":"message","id":"$id","parentId":${parent?.let { "\"$it\"" } ?: "null"},"timestamp":"$ts","message":{"role":"user","content":"$text"}}"""

    private fun reply(id: String, parent: String, text: String, ts: String) =
        """{"type":"message","id":"$id","parentId":"$parent","timestamp":"$ts","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    /** u1 → a1 → { u2 → a2 (abandoned), u3 → a3 → label entry (leaf) }, u2 labeled, a1's label set then cleared. */
    private val forked = json(
        """{"leafId":"l2","entries":[${user("u1", null, "hello", "1")},${reply("a1", "u1", "hi", "2")},
            ${user("u2", "a1", "first try", "3")},${reply("a2", "u2", "A", "4")},
            ${user("u3", "a1", "second try", "5")},${reply("a3", "u3", "B", "6")},
            {"type":"label","id":"l1","parentId":"a3","timestamp":"7","targetId":"u2","label":"plan A"},
            {"type":"label","id":"l0","parentId":"l1","timestamp":"8","targetId":"a1","label":"x"},
            {"type":"label","id":"l2","parentId":"l0","timestamp":"9","targetId":"a1"}]}""",
    )

    @Test
    fun treeRowsIndentBranchesAndMarkTheActivePath() {
        val rows = SessionTree.rows(forked)
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3", "a3"), rows.map { it.id })
        assertEquals(listOf(0, 0, 1, 1, 1, 1), rows.map { it.depth })
        assertEquals(setOf("u1", "a1", "u3", "a3"), rows.filter { it.active }.map { it.id }.toSet())
        assertTrue(rows.single { it.id == "a3" }.leaf)
        assertEquals(2, rows.single { it.id == "a1" }.branches)
        assertEquals("plan A", rows.single { it.id == "u2" }.label)
        // Label entries are not rows, and a cleared label is gone.
        assertNull(rows.single { it.id == "a1" }.label)
        assertEquals(TreeKind.User, rows.first().kind)
        assertEquals("hello", rows.first().text)
    }

    @Test
    fun treeFiltersKeepTheLeaf() {
        assertEquals(listOf("u1", "u2", "u3", "a3"), SessionTree.rows(forked, TreeFilter.User).map { it.id })
        assertEquals(listOf("u2", "a3"), SessionTree.rows(forked, TreeFilter.Labeled).map { it.id })
        assertTrue(SessionTree.rows(null).isEmpty())
    }

    @Test
    fun treeHidesToolOnlyRepliesByDefaultAndNamesTheirTools() {
        val data = json(
            """{"leafId":"t1","entries":[${user("u1", null, "go", "1")},
              {"type":"message","id":"c1","parentId":"u1","timestamp":"2","message":{"role":"assistant","content":[{"type":"toolCall","name":"read"}]}},
              {"type":"message","id":"t1","parentId":"c1","timestamp":"3","message":{"role":"toolResult","toolName":"read","content":[]}}]}""",
        )
        assertEquals(listOf("u1", "t1"), SessionTree.rows(data).map { it.id })
        assertEquals("→ read", SessionTree.rows(data, TreeFilter.All).single { it.id == "c1" }.text)
    }

    @Test
    fun treeOfAVeryLongSessionDoesNotOverflowTheStack() {
        val entries = (0 until 5000).joinToString(",") { i -> user("e$i", if (i == 0) null else "e${i - 1}", "m$i", "z") }
        val rows = SessionTree.rows(json("""{"leafId":"e4999","entries":[$entries]}"""))
        assertEquals(5000, rows.size)
        assertTrue(rows.all { it.active && it.depth == 0 })
        assertTrue(rows.last().leaf)
    }

    // Packages

    @Test
    fun parsesPiListOutput() {
        val out = """
            |User packages:
            |  /data/data/in.opensec.pibolt/files/usr/share/pi-extensions/opensec-pi-todo
            |  npm:pi-hello@1.0.0 (filtered)
            |    /data/data/in.opensec.pibolt/files/home/.pi/agent/npm/node_modules/pi-hello
            |
            |Project packages:
            |  git:github.com/u/r
            |    /p/.pi/git/github.com/u/r
            |""".trimMargin()
        val list = PackageList.parseCli("\u001B[1m" + out + "\u001B[22m")
        assertEquals(3, list.size)
        assertEquals(PackageScope.User, list[0].scope)
        assertNull(list[0].installedPath)
        assertEquals("npm:pi-hello@1.0.0", list[1].source)
        assertTrue(list[1].filtered)
        assertEquals("/data/data/in.opensec.pibolt/files/home/.pi/agent/npm/node_modules/pi-hello", list[1].installedPath)
        assertEquals(PackageScope.Project, list[2].scope)
        assertEquals("git", list[2].kind)
        assertTrue(PackageList.parseCli("No packages installed.").isEmpty())
    }

    @Test
    fun switchesPackagesAndBuiltinsOnAndOffInSettings() {
        val settings = json("""{"theme":"dark","packages":["npm:a",{"source":"npm:b","skills":[]}]}""")
        val off = PackageList.setEnabled(settings, "npm:a", false)!!
        val listed = PackageList.fromSettings(off, PackageScope.User)
        assertTrue(listed.single { it.source == "npm:a" }.disabled)
        assertFalse(listed.single { it.source == "npm:b" }.disabled)
        assertTrue(listed.single { it.source == "npm:b" }.filtered)
        assertEquals("dark", off["theme"]!!.jsonPrimitive.content)
        val on = PackageList.setEnabled(off, "npm:a", true)!!
        assertEquals(JsonPrimitive("npm:a"), on["packages"]!!.jsonArray[0])
        assertNull(PackageList.setEnabled(settings, "npm:missing", false))

        val noMcp = PackageList.setBuiltin(settings, BuiltinExtension.Mcp.id, false)
        assertEquals(setOf("builtin:mcp"), PackageList.disabledBuiltins(noMcp))
        val back = PackageList.setBuiltin(noMcp, BuiltinExtension.Mcp.id, true)
        assertNull(back["extensions"])
    }

    @Test
    fun normalizesInstallSources() {
        assertEquals("npm:pi-foo", PackageList.normalizeSource("pi-foo"))
        assertEquals("npm:@scope/pi-foo@1.2.0", PackageList.normalizeSource(" @scope/pi-foo@1.2.0 "))
        assertEquals("git:github.com/u/r", PackageList.normalizeSource("github.com/u/r"))
        assertEquals("https://github.com/u/r", PackageList.normalizeSource("https://github.com/u/r"))
        assertNull(PackageList.normalizeSource("rm -rf /"))
        assertNull(PackageList.normalizeSource(""))
    }

    @Test
    fun findsWhatAPackageProvides() {
        val commands = json("""{"commands":[{"name":"todo","source":"extension","sourceInfo":{"path":"/x/todo/dist/index.js","baseDir":"/x/todo"}},{"name":"other","source":"extension","sourceInfo":{"path":"/y/z.js"}}]}""")
        val tools = json("""{"tools":[{"name":"todo_write","description":"Write todos\nmore","sourceInfo":{"path":"/x/todo/dist/index.js"}},{"name":"read","sourceInfo":{"path":"builtin:read"}}]}""")
        val got = PackageResources.of("/x/todo", commands, tools)
        assertEquals(listOf("/todo", "todo_write"), got.map { it.name })
        assertEquals("Write todos", got[1].description)
    }

    @Test
    fun parsesMcpListJsonAndBuildsAddArguments() {
        val report = McpServers.parse("note\n{\"servers\":[{\"name\":\"docs\",\"scope\":\"global\",\"enabled\":true,\"transport\":\"https://x/mcp\",\"state\":\"failed\",\"tools\":[],\"error\":\"401\"}],\"errors\":[]}")
        assertNotNull(report)
        assertEquals("failed", report.servers.single().state)
        assertEquals("401", report.servers.single().error)
        assertNull(McpServers.parse("no json"))
        assertEquals(listOf("mcp", "add", "docs", "--url", "https://x/mcp", "--bearer-token-env-var", "TOKEN"), McpServers.addArgs("docs", "https://x/mcp", "TOKEN"))
        assertEquals(listOf("mcp", "add", "fs", "--", "node", "server.js", "a b"), McpServers.addArgs("fs", "node server.js 'a b'"))
        assertNull(McpServers.addArgs("bad name", "x"))
    }

    // Session list

    private fun browsed(name: String?, preview: String, at: Long, bytes: Long) =
        BrowsedSession(SessionSummary("/s/$preview.jsonl", preview, name, preview, at, 2), bytes)

    @Test
    fun sortsAndFiltersSessions() {
        val list = listOf(browsed(null, "zebra task", 1, 500), browsed("Alpha", "fix tests", 3, 100), browsed(null, "beta work", 2, 900))
        assertEquals(listOf("fix tests", "beta work", "zebra task"), SessionBrowse.arrange(list, SessionSort.Recent, "").map { it.summary.preview })
        assertEquals(listOf("Alpha", "beta work", "zebra task"), SessionBrowse.arrange(list, SessionSort.Name, "").map { it.title })
        assertEquals(listOf(900L, 500L, 100L), SessionBrowse.arrange(list, SessionSort.Size, "").map { it.bytes })
        assertEquals(listOf("Alpha"), SessionBrowse.arrange(list, SessionSort.Recent, "ALP").map { it.title })
    }

    @Test
    fun searchesInsideSessionsAndRenamesThem() {
        val dir = Files.createTempDirectory("f2-sessions").toFile()
        val file = File(dir, "s.jsonl")
        file.writeText(
            """{"type":"session","version":3,"id":"x","timestamp":"t","cwd":"/p"}
            |${user("u1", null, "Where is the Parser defined?", "1")}
            |${reply("a1", "u1", "The parser lives in src/parser.ts; the parser is small.", "2")}
            |""".trimMargin(),
        )
        val s = BrowsedSession(SessionSummary(file.path, "x", null, "Where", 1, 2), file.length())
        val hits = SessionBrowse.search(listOf(s), "parser")
        assertEquals(1, hits.size)
        assertEquals(4, hits.single().count)
        assertTrue("Parser" in hits.single().snippet)
        assertTrue(SessionBrowse.search(listOf(s), "nothing here").isEmpty())

        assertEquals("a1", SessionBrowse.lastEntryId(file))
        assertTrue(SessionBrowse.rename(file, " My session ", Instant.parse("2026-01-02T03:04:05Z")))
        val last = json(file.readLines().last())
        assertEquals("session_info", last["type"]!!.jsonPrimitive.content)
        assertEquals("a1", last["parentId"]!!.jsonPrimitive.content)
        assertEquals("My session", last["name"]!!.jsonPrimitive.content)
        assertEquals("My session", SessionIndex(dir).summary(file)?.name)
        dir.deleteRecursively()
    }

    // Settings files

    @Test
    fun scopesModelsWithPatterns() {
        assertTrue(ModelScope.matches("anthropic/claude-*", "anthropic", "claude-sonnet-4"))
        assertTrue(ModelScope.matches("gpt-5", "openai", "GPT-5"))
        assertFalse(ModelScope.matches("anthropic/*", "openai", "gpt-5"))
        assertTrue(ModelScope.enabled(emptyList(), "a", "b"))
        val all = listOf("a" to "x", "a" to "y", "b" to "z")
        val off = ModelScope.toggle(emptyList(), "a", "y", false, all)
        assertEquals(listOf("a/x", "b/z"), off)
        assertEquals(listOf("a/x", "b/z", "a/y"), ModelScope.toggle(off, "a", "y", true, all))
        // A wildcard that covered the model is replaced by the other models it covered.
        assertEquals(listOf("a/x"), ModelScope.toggle(listOf("a/*"), "a", "y", false, all))
        val settings = ModelScope.write(json("{}"), listOf("a/x"))
        assertEquals(listOf("a/x"), ModelScope.read(settings))
        assertNull(ModelScope.write(settings, emptyList())["enabledModels"])
    }

    @Test
    fun readsAndWritesProjectTrust() {
        val trust = json("""{"/home/p":true,"/home/p/sub/inner":false}""")
        assertEquals(ProjectTrust.Decision("/home/p", true), ProjectTrust.decision(trust, "/home/p/sub"))
        assertEquals(false, ProjectTrust.decision(trust, "/home/p/sub/inner/deep")?.trusted)
        assertNull(ProjectTrust.decision(trust, "/other"))
        val set = ProjectTrust.set(trust, "/home/q", false)
        assertEquals(listOf("/home/p", "/home/p/sub/inner", "/home/q"), set.keys.toList())
        assertFalse("/home/p" in ProjectTrust.set(trust, "/home/p", null))

        val dir = Files.createTempDirectory("f2-trust").toFile()
        assertTrue(ProjectTrust.gatedResources(dir).isEmpty())
        File(dir, ".pi/skills").mkdirs()
        assertEquals(listOf(".pi/skills"), ProjectTrust.gatedResources(dir))
        dir.deleteRecursively()
    }

    @Test
    fun tuningWritesOnlySetNumbersAndKeepsOtherKeys() {
        val settings = json("""{"compaction":{"enabled":false,"reserveTokens":1000},"retry":{"enabled":true}}""")
        val t = AgentTuning.from(settings)
        assertEquals(1000L, t.reserveTokens)
        val next = t.copy(reserveTokens = null, thresholdPercent = 80, maxRetries = 5).applyTo(settings)
        assertEquals(json("""{"compaction":{"enabled":false,"thresholdPercent":80},"retry":{"enabled":true,"maxRetries":5}}"""), next)
        assertEquals(listOf("Threshold must be 1 to 100 %"), AgentTuning(thresholdPercent = 0).problems())
        assertEquals(json("{}"), PiJsonFile.set(json("""{"a":{"b":1}}"""), "a.b", null))
    }

    @Test
    fun updatesAJsonFileUnderTheLock() {
        val dir = Files.createTempDirectory("f2-json").toFile()
        val file = File(dir, "settings.json")
        file.writeText("﻿{\"keep\":1}")
        assertTrue(PiJsonFile.update(file) { PiJsonFile.set(it, "x.y", JsonPrimitive(2)) })
        assertEquals(json("""{"keep":1,"x":{"y":2}}"""), PiJsonFile.read(file))
        assertTrue(file.readText().startsWith("{\n  \"keep\": 1,"))
        assertFalse(File(dir, "settings.json.lock").exists())
        dir.deleteRecursively()
    }

    // Import, the helper extension, palette and resources

    @Test
    fun importsASessionIntoTheProject() {
        val agent = Files.createTempDirectory("f2-import").toFile()
        val index = SessionIndex(agent)
        val text = """{"type":"session","version":3,"id":"abc","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/Users/me/code"}
            |${user("u1", null, "hi", "1")}""".trimMargin()
        val file = Features2.importSession(index, "/data/data/x/files/home/projects/p", text, "2026-01-01T00-00-00-000Z_abc.jsonl").getOrThrow()
        assertEquals(index.sessionDir("/data/data/x/files/home/projects/p"), file.parentFile)
        val header = json(file.readLines().first())
        assertEquals("/data/data/x/files/home/projects/p", header["cwd"]!!.jsonPrimitive.content)
        assertEquals("abc", header["id"]!!.jsonPrimitive.content)
        assertEquals(2, file.readLines().size)
        // A second import does not overwrite the first.
        val again = Features2.importSession(index, "/data/data/x/files/home/projects/p", text, "2026-01-01T00-00-00-000Z_abc.jsonl").getOrThrow()
        assertTrue(again.name.endsWith("-2.jsonl"))
        assertTrue(Features2.importSession(index, "/p", "{\"type\":\"message\"}", null).isFailure)
        agent.deleteRecursively()
    }

    @Test
    fun installsTheHelperExtensionOnce() {
        val agent = Files.createTempDirectory("f2-ext").toFile()
        assertTrue(AppExtension.install(agent))
        val file = File(agent, "extensions/${AppExtension.FILE}")
        assertTrue(file.readText().contains("registerCommand(\"pibolt-app\""))
        val modified = file.lastModified()
        Thread.sleep(10)
        assertTrue(AppExtension.install(agent))
        assertEquals(modified, file.lastModified())
        agent.deleteRecursively()
    }

    @Test
    fun paletteHidesTheHelperAndOffersTheNewCommands() {
        val host = listOf(SlashCommand("pibolt-app", "helper", "extension"), SlashCommand("todo", null, "extension"))
        val names = CommandPalette.entries(host, local = true).map { it.name }
        assertFalse("pibolt-app" in names)
        assertTrue(listOf("tree", "packages", "mcp-servers", "skills", "trust", "reload", "scoped-models", "tuning", "import", "share", "bug").all { it in names })
        assertEquals(Builtin.Tree, CommandPalette.parse("/tree", host, local = true)?.builtin)
        assertNull(CommandPalette.parse("/tree", host, local = false))
        assertEquals("crash on send", CommandPalette.parse("/bug crash on send", host, local = false)?.args)
    }

    @Test
    fun listsSkillsAndPromptsWithTheirFiles() {
        val data = json(
            """{"commands":[{"name":"skill:review","description":"Review","source":"skill","sourceInfo":{"path":"/s/review/SKILL.md","scope":"user"}},
            |{"name":"fix","source":"prompt","sourceInfo":{"path":"/p/fix.md","scope":"project"}},{"name":"todo","source":"extension"}]}""".trimMargin(),
        )
        val list = Resources.from(data)
        assertEquals(listOf("fix", "skill:review"), list.map { it.name })
        assertEquals("/s/review/SKILL.md", list[1].path)
        assertTrue(Resources.from(JsonObject(mapOf("commands" to JsonArray(emptyList())))).isEmpty())
    }

    @Test
    fun importReadsUpToTheLimitAndRefusesMore() {
        val ok = ByteArray(200_000) { 'a'.code.toByte() }
        assertEquals(200_000, readAtMost(java.io.ByteArrayInputStream(ok), 200_000).size)
        assertTrue(readAtMost(java.io.ByteArrayInputStream(ByteArray(0)), 10).isEmpty())
        assertTrue(runCatching { readAtMost(java.io.ByteArrayInputStream(ok), 199_999) }.isFailure)
    }
}
