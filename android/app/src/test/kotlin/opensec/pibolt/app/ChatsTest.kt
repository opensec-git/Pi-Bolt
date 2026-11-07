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
    fun aChatEngineGetsOnlyTheWebAndQuestionToolsInTheChatsFolderAndSessionFolder() {
        val chats = ChatSpace.dir(home)
        assertEquals(File(home, ".pibolt/chats"), chats)
        assertFalse(chats.path.startsWith(projects.path), "the chats' folder is never a project")
        val args = ChatSpace.engineArgs(SessionTarget.Fresh, chats.path, home, agentDir)
        assertEquals(listOf("--mode", "rpc"), args.take(2))
        assertFalse("--no-tools" in args)
        assertEquals("web_search,fetch_content,get_search_content,ask_user_question", args[args.indexOf("--tools") + 1])
        // An allowlist keeps MCP tools: chats connect no MCP server.
        assertTrue("--no-mcp" in args)
        assertEquals(File(agentDir, "chats").path, args[args.indexOf("--session-dir") + 1])
        val prompt = args[args.indexOf("--system-prompt") + 1]
        assertEquals(ChatSpace.systemPrompt(webSearch = true), prompt)
        assertTrue("search the web" in prompt && "ask_user_question" in prompt && "no shell" in prompt && "no files" in prompt, prompt)
        // A saved chat opens by its file, with the same tools.
        val resume = ChatSpace.engineArgs(SessionTarget.Resume("/s/chat.jsonl"), chats.path + "/", home, agentDir)
        assertEquals("/s/chat.jsonl", resume[resume.indexOf("--session") + 1])
        assertEquals(args[args.indexOf("--tools") + 1], resume[resume.indexOf("--tools") + 1])
    }

    @Test
    fun webSearchOffLeavesAChatOnlyTheQuestionTool() {
        val args = ChatSpace.engineArgs(SessionTarget.Fresh, ChatSpace.dir(home).path, home, agentDir, webSearch = false)
        assertEquals("ask_user_question", args[args.indexOf("--tools") + 1])
        val prompt = args[args.indexOf("--system-prompt") + 1]
        assertTrue("no internet access" in prompt && "web_search" !in prompt, prompt)
        assertFalse("--exclude-tools" in args)
    }

    @Test
    fun aProjectEngineKeepsItsToolsAndDropsTheWebToolsWhenWebSearchIsOff() {
        val dir = File(projects, "chats").path
        assertEquals(listOf("--mode", "rpc", "--continue"), ChatSpace.engineArgs(SessionTarget.Continue, dir, home, agentDir))
        assertEquals(
            listOf("--mode", "rpc", "--continue", "--exclude-tools", "web_search,fetch_content,get_search_content,source_check"),
            ChatSpace.engineArgs(SessionTarget.Continue, dir, home, agentDir, webSearch = false),
        )
        assertFalse(ChatSpace.isChat(File(projects, "chats").path, home))
        assertFalse(ChatSpace.isChat(home.path, home))
        assertTrue(ChatSpace.isChat(File(home, ".pibolt/./chats").path, home))
    }

    /**
     * What the engine registered with these flags: the tool lists the model saw in real runs of Pi-Bolt 1.1.2 with the
     * bundled extensions (test/resources/fixtures/engine, recorded with android/e2e's fake model).
     */
    @Test
    fun theRecordedEnginesRegisteredExactlyTheseTools() {
        val chat = EngineFixture.header("chat-web-ask")
        assertEquals(ChatSpace.chatTools(webSearch = true), chat.tools("modelTools"))
        assertEquals(ChatSpace.chatTools(webSearch = true).joinToString(","), chat.tools("args").let { it[it.indexOf("--tools") + 1] })
        val ours = ChatSpace.chatArgs(agentDir)
        assertEquals(ours.filter { it.startsWith("--") }, chat.tools("args").drop(2).filter { it.startsWith("--") })
        for (builtin in listOf("bash", "read", "edit", "write", "grep", "find")) assertFalse(builtin in chat.tools("modelTools"), builtin)
        val project = EngineFixture.header("project-web-ask").tools("modelTools")
        assertTrue(project.containsAll(listOf("bash", "read", "edit", "write") + ChatSpace.PROJECT_WEB_TOOLS + ChatSpace.ASK_TOOL), project.toString())
        val off = EngineFixture.header("project-web-off")
        assertEquals(ChatSpace.projectArgs(webSearch = false), off.tools("args").drop(2))
        assertTrue(off.tools("modelTools").none { it in ChatSpace.PROJECT_WEB_TOOLS } && ChatSpace.ASK_TOOL in off.tools("modelTools") && "bash" in off.tools("modelTools"))
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
