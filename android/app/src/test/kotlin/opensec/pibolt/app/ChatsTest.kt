package opensec.pibolt.app

import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.ui.chats.ChatList
import opensec.pibolt.app.ui.chats.ChatSummary
import opensec.pibolt.app.ui.session.SessionParts
import opensec.pibolt.app.ui.session.features.Builtin
import opensec.pibolt.app.ui.session.features.CommandPalette
import opensec.pibolt.app.ui.session.features2.SessionBrowse
import opensec.pibolt.protocol.backend.SessionTarget
import opensec.pibolt.protocol.session.SlashCommand
import java.io.File
import java.nio.file.Files
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
    }
}
