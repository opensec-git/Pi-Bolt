package opensec.pibolt.app

import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.ui.chats.ChatList
import opensec.pibolt.app.ui.chats.ChatModel
import opensec.pibolt.app.ui.chats.ChatSummary
import opensec.pibolt.app.ui.chats.ChatWelcome
import opensec.pibolt.app.ui.session.SessionParts
import opensec.pibolt.app.ui.session.features.Builtin
import opensec.pibolt.app.ui.session.features.CommandPalette
import opensec.pibolt.app.ui.session.features2.SessionBrowse
import opensec.pibolt.protocol.backend.SessionTarget
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.app.ui.session.tree.SessionTree
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.session.SlashCommand
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain chats (engine.ChatSpace, ui/chats): their engine's arguments, their own listing, and the screen without project parts. */
class ChatsTest {
    private val home = Files.createTempDirectory("home").toFile()
    private val agentDir = File(home, ".pi/agent")
    private val projects = File(home, "projects")

    @AfterTest
    fun cleanUp() {
        home.deleteRecursively()
    }

    private fun session(dir: File, id: String, cwd: String, at: Long, vararg lines: String): File =
        File(dir.apply { mkdirs() }, "2026-11-07T10-00-00-000Z_$id.jsonl").apply {
            writeText((listOf("""{"type":"session","version":3,"id":"$id","timestamp":"2026-11-07T10:00:00.000Z","cwd":"$cwd"}""") + lines).joinToString("\n", postfix = "\n"))
            setLastModified(at)
        }

    private fun user(text: String) = """{"type":"message","id":"u${text.length}","message":{"role":"user","content":"$text"}}"""

    private fun assistant(text: String) = """{"type":"message","id":"a${text.length}","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun aChatEngineRunsWithoutToolsInTheChatsFolderAndSessionFolder() {
        val chats = ChatSpace.dir(home)
        assertEquals(File(home, ".pibolt/chats"), chats)
        assertFalse(chats.path.startsWith(projects.path), "the chats' folder is never a project")
        val args = ChatSpace.engineArgs(SessionTarget.Fresh, chats.path, home, agentDir)
        assertEquals(listOf("--mode", "rpc"), args.take(2))
        assertTrue("--no-tools" in args)
        assertEquals(File(agentDir, "chats").path, args[args.indexOf("--session-dir") + 1])
        assertEquals(ChatSpace.SYSTEM_PROMPT, args[args.indexOf("--system-prompt") + 1])
        // A saved chat opens by its file, still without tools.
        val resume = ChatSpace.engineArgs(SessionTarget.Resume("/s/chat.jsonl"), chats.path + "/", home, agentDir)
        assertEquals("/s/chat.jsonl", resume[resume.indexOf("--session") + 1])
        assertTrue("--no-tools" in resume)
    }

    @Test
    fun aProjectEngineKeepsItsTools() {
        val args = ChatSpace.engineArgs(SessionTarget.Continue, File(projects, "chats").path, home, agentDir)
        assertEquals(listOf("--mode", "rpc", "--continue"), args)
        assertFalse(ChatSpace.isChat(File(projects, "chats").path, home))
        assertFalse(ChatSpace.isChat(home.path, home))
        assertTrue(ChatSpace.isChat(File(home, ".pibolt/./chats").path, home))
    }

    @Test
    fun chatsAndProjectSessionsAreListedApart() {
        val index = SessionIndex(agentDir)
        val project = File(projects, "app").apply { mkdirs() }
        val chats = ChatSpace.dir(home).apply { mkdirs() }
        session(index.sessionDir(project.absolutePath), "p1", project.path, 1_000_000, user("fix the build"))
        session(ChatSpace.sessionDir(agentDir), "c1", chats.path, 2_000_000, user("trip ideas for Lisbon"), assistant("Here are some"))
        session(ChatSpace.sessionDir(agentDir), "c2", chats.path, 3_000_000, user("explain monads"))
        assertEquals(listOf("p1"), index.list(project).map { it.id })
        assertEquals(listOf("c2", "c1"), index.listIn(listOf(ChatSpace.sessionDir(agentDir))).map { it.id })
        // The chats' folder has no sessions of its own in Pi's per-folder layout: a chat is never a project's session.
        assertTrue(index.list(chats).isEmpty())
    }

    @Test
    fun renameAndDeleteAChat() {
        val index = SessionIndex(agentDir)
        val file = session(ChatSpace.sessionDir(agentDir), "c1", ChatSpace.dir(home).path, 2_000_000, user("trip ideas"), assistant("Sure"))
        val before = ChatSummary.of(assertNotNull(index.summary(file)))
        assertEquals("trip ideas", before.title)
        assertEquals(2, before.messageCount)
        assertTrue(SessionBrowse.rename(file, "  Lisbon  "))
        val after = ChatSummary.of(assertNotNull(index.summary(file)))
        assertEquals("Lisbon", after.title)
        assertEquals(2, after.messageCount)
        assertTrue(file.delete())
        assertTrue(index.listIn(listOf(ChatSpace.sessionDir(agentDir))).isEmpty())
    }

    @Test
    fun theSavedSystemPromptIsNotAMessage() {
        // The engine saves the system prompt as a role "system" message: 3 prompts read "7 messages" on the Xiaomi.
        val index = SessionIndex(agentDir)
        val system = """{"type":"message","id":"s1","message":{"role":"system","content":"","sections":{"preamble":"You are Pi"}}}"""
        val file = session(ChatSpace.sessionDir(agentDir), "c1", ChatSpace.dir(home).path, 2_000_000, system, user("trip ideas"), assistant("Sure"))
        assertEquals(2, assertNotNull(index.summary(file)).messageCount)
        // Off Pi's usual layout (parsed in full), the same.
        val spaced = system.replace("{\"type\"", "{ \"type\"")
        val other = session(ChatSpace.sessionDir(agentDir), "c2", ChatSpace.dir(home).path, 2_000_000, spaced, user("hi"))
        assertEquals(1, assertNotNull(index.summary(other)).messageCount)
        // Only the system prompt: no conversation, so not listed.
        session(ChatSpace.sessionDir(agentDir), "c3", ChatSpace.dir(home).path, 2_000_000, system)
        assertEquals(0, index.listIn(listOf(ChatSpace.sessionDir(agentDir))).first { it.id == "c3" }.messageCount)
    }

    @Test
    fun chatRowsAndSearch() {
        val now = 10 * 3_600_000L
        val a = ChatSummary("/a", "Trip ideas for Lisbon", now - 2 * 3_600_000L, 12)
        val b = ChatSummary("/b", "Explain monads", now - 30_000L, 1)
        assertEquals("2h · 12 messages", ChatList.meta(a, now))
        assertEquals("now · 1 message", ChatList.meta(b, now))
        assertEquals(listOf(a), ChatList.filter(listOf(b, a), " lisbon TRIP "))
        assertEquals(listOf(b, a), ChatList.filter(listOf(b, a), ""))
        assertEquals("New chat", ChatSpace.title(null, null))
        assertEquals("New chat", ChatSpace.title("  ", "  "))
        assertEquals("one line", ChatSpace.title(null, "one\n line"))
        assertEquals("Named", ChatSpace.title("Named", "prompt"))
    }

    @Test
    fun aChatHidesTheProjectOnlyParts() {
        val chat = SessionParts.of(local = true, chat = true)
        assertFalse(chat.files || chat.shell || chat.preview || chat.projectStarters || chat.folder || chat.subtitle)
        assertEquals(SessionParts.PROJECT, SessionParts.of(local = true, chat = false))
        val remote = SessionParts.of(local = false, chat = false)
        assertFalse(remote.files || remote.shell || remote.preview)
        assertTrue(remote.folder && remote.subtitle)
    }

    @Test
    fun aChatPaletteHasNoToolCommands() {
        val host = listOf(
            SlashCommand("fix-tests", "Fix failing tests", "prompt"),
            SlashCommand("skill:pdf", "Read PDF files", "skill"),
            SlashCommand("agents", "Manage agents", "extension"),
        )
        val entries = CommandPalette.entries(host, local = true, chat = true)
        val builtins = entries.mapNotNull { it.builtin }.toSet()
        for (b in listOf(Builtin.Shell, Builtin.Packages, Builtin.Mcp, Builtin.Trust, Builtin.Skills, Builtin.Import)) assertTrue(b !in builtins, "$b")
        for (b in listOf(Builtin.New, Builtin.Resume, Builtin.Fork, Builtin.Tree, Builtin.Compact, Builtin.Name, Builtin.Model, Builtin.Thinking, Builtin.Copy, Builtin.Export, Builtin.Share)) assertTrue(b in builtins, "$b")
        assertEquals(listOf("fix-tests"), entries.filter { it.builtin == null }.map { it.name })
        assertNull(CommandPalette.parse("!ls", host, local = true, chat = true))
        assertNull(CommandPalette.parse("/packages", host, local = true, chat = true))
        assertEquals(Builtin.Fork, CommandPalette.parse("/fork", host, local = true, chat = true)?.builtin)
        assertEquals("focus", CommandPalette.parse("/compact focus", host, local = true, chat = true)?.args)
        // A project session is unchanged.
        assertEquals(Builtin.Shell, CommandPalette.parse("!ls", host, local = true)?.builtin)
        // A chat's commands speak of chats, a project's of sessions.
        assertEquals("Start a new chat", entries.first { it.builtin == Builtin.New }.description)
        assertEquals("Open another chat", entries.first { it.builtin == Builtin.Resume }.description)
        assertEquals("Start a new session", CommandPalette.entries(host, local = true).first { it.builtin == Builtin.New }.description)
        assertTrue(entries.none { it.builtin != null && "session" in it.description }, entries.filter { "session" in it.description }.joinToString { it.name })
    }

    @Test
    fun aNewChatGreetsByTheTimeOfDay() {
        assertEquals("Good morning", ChatWelcome.greeting(5))
        assertEquals("Good morning", ChatWelcome.greeting(11))
        assertEquals("Good afternoon", ChatWelcome.greeting(12))
        assertEquals("Good evening", ChatWelcome.greeting(17))
        assertEquals("Good evening", ChatWelcome.greeting(22))
        assertEquals("Hello", ChatWelcome.greeting(23))
        assertEquals("Hello", ChatWelcome.greeting(0))
    }

    @Test
    fun chatSuggestionsAreEverydayTopicsThatRotateDaily() {
        val day = LocalDate.of(2026, 11, 7).toEpochDay()
        val today = ChatWelcome.pick(day)
        assertEquals(ChatWelcome.COUNT, today.size)
        assertEquals(ChatWelcome.COUNT, today.map { it.topic }.toSet().size, "one suggestion per topic")
        assertEquals(today, ChatWelcome.pick(day), "fixed within a day")
        assertTrue(ChatWelcome.pick(day + 1) != today, "new ones the next day")
        // Over a few days every suggestion comes up, and none is about a project.
        val seen = (0 until ChatWelcome.POOL.size).flatMap { ChatWelcome.pick(day + it) }.toSet()
        assertEquals(ChatWelcome.POOL.toSet(), seen)
        for (s in ChatWelcome.POOL) {
            assertFalse(Regex("(?i)project|file|test|commit|repo").containsMatchIn(s.prompt), s.prompt)
            // The prompt ends where the user goes on typing.
            assertTrue(s.prompt.endsWith(" ") || s.prompt.endsWith("\n"), s.prompt)
        }
        assertEquals(ChatWelcome.COUNT, ChatWelcome.pick(-3).size)
    }

    @Test
    fun theChatsListIsSectionedByDay() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
        val now = at(2026, 11, 7, 9)
        fun chat(name: String, time: Long) = ChatSummary("/$name", name, time, 2)
        val chats = listOf(
            chat("a", at(2026, 11, 7, 1)),
            chat("b", at(2026, 11, 6, 23)),
            chat("c", at(2026, 11, 3, 12)),
            chat("d", at(2026, 10, 20, 12)),
            chat("e", at(2026, 9, 1, 12)),
            chat("f", at(2025, 12, 31, 12)),
        )
        val sections = ChatList.sections(chats, now, zone)
        assertEquals(listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days"), sections.take(4).map { it.first })
        // Older ones by month; this year's months carry no year.
        assertEquals(Month.SEPTEMBER.getDisplayName(TextStyle.FULL, Locale.getDefault()), sections[4].first)
        assertEquals(Month.DECEMBER.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " 2025", sections[5].first)
        assertEquals(chats, sections.flatMap { it.second }, "order and every chat kept")
        assertTrue(ChatList.sections(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun aNewChatStartsOnTheLastChatsModel() {
        val kimi = ChatModel("opencode-go", "kimi-k3", "max")
        fun model(provider: String, id: String) = ModelInfo(id, id, provider, reasoning = true, contextWindow = null)
        // Pi's default after a cold start: switch to the remembered model.
        assertEquals(kimi, ChatModel.switchTo(kimi, model("fake", "fake-model")))
        assertEquals(kimi, ChatModel.switchTo(kimi, null))
        // Already on it (a warm engine kept it), or nothing remembered: no switch.
        assertNull(ChatModel.switchTo(kimi, model("opencode-go", "kimi-k3")))
        assertNull(ChatModel.switchTo(null, model("fake", "fake-model")))
        // The same id from another provider is another model.
        assertEquals(kimi, ChatModel.switchTo(kimi, model("moonshot", "kimi-k3")))
    }

    @Test
    fun reusingAnEmptyChatCarriesTheModelWithoutWaitingForASwitch() {
        // The engine handed back the live chat, already new, ready and empty: nothing to leave, the carry runs at once.
        assertNull(ChatModel.leaving("s1", reused = true, readyEmpty = true))
        // The live engine moves on to a new session (same controller, the old one had messages): wait to leave s1.
        assertEquals("s1", ChatModel.leaving("s1", reused = true, readyEmpty = false))
        // Another engine (a parked or new one): wait for it to leave nothing it shares with s1.
        assertEquals("s1", ChatModel.leaving("s1", reused = false, readyEmpty = true))
        assertNull(ChatModel.leaving(null, reused = false, readyEmpty = false))
    }

    @Test
    fun regenerateFindsItsPromptOnTheActiveBranch() {
        // Send "hello" (u1), regenerate it (the chat rewinds before u1 and sends it again: u2, a branch beside u1), then
        // send "hello" again (u3). The transcript shows u2 and u3; Pi's fork list also has u1, on the abandoned branch.
        fun user(id: String, parent: String?) =
            """{"type":"message","id":"$id","parentId":${parent?.let { "\"$it\"" } ?: "null"},"message":{"role":"user","content":[{"type":"text","text":"hello"}]}}"""
        fun reply(id: String, parent: String) = """{"type":"message","id":"$id","parentId":"$parent","message":{"role":"assistant","content":[{"type":"text","text":"Hi!"}]}}"""
        val entries = listOf(user("u1", null), reply("a1", "u1"), user("u2", null), reply("a2", "u2"), user("u3", "a2"), reply("a3", "u3"))
        val data = ProtocolJson.parseToJsonElement("""{"entries":[${entries.joinToString(",")}],"leafId":"a3"}""") as JsonObject
        // Regenerating the second visible "hello" (the newest copy) targets u3, not u2 (what counting u1 picked).
        assertEquals("u3", SessionTree.rewindTarget(data, "hello", fromEnd = 0))
        // The first visible one is u2; u1 is on no visible branch.
        assertEquals("u2", SessionTree.rewindTarget(data, " hello ", fromEnd = 1))
        assertNull(SessionTree.rewindTarget(data, "hello", fromEnd = 2))
        assertNull(SessionTree.rewindTarget(data, "goodbye", fromEnd = 0))
        assertNull(SessionTree.rewindTarget(null, "hello", fromEnd = 0))
    }
}
