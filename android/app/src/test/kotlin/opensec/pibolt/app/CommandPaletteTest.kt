package opensec.pibolt.app

import androidx.compose.ui.input.key.Key
import opensec.pibolt.app.ui.session.ComposerKeys
import opensec.pibolt.app.ui.session.compactionLabel
import opensec.pibolt.app.ui.session.features.Builtin
import opensec.pibolt.app.ui.session.features.CommandPalette
import opensec.pibolt.app.ui.session.features.CommandSource
import opensec.pibolt.app.ui.session.features.Fuzzy
import opensec.pibolt.app.ui.session.features.ShellCommand
import opensec.pibolt.protocol.session.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPaletteTest {
    private val host = listOf(
        SlashCommand("fix-tests", "Fix failing tests", "prompt"),
        SlashCommand("skill:pdf", "Read PDF files", "skill"),
        SlashCommand("todo", "Show the todo list", "extension", argumentHint = "[filter]"),
    )

    @Test
    fun remoteSessionsHideTheCommandsThatNeedTheEngine() {
        val remote = CommandPalette.entries(host, local = false).mapNotNull { it.builtin }
        assertTrue(Builtin.New !in remote && Builtin.Fork !in remote && Builtin.Shell !in remote)
        assertTrue(Builtin.Compact in remote && Builtin.Session in remote)
        val local = CommandPalette.entries(host, local = true).mapNotNull { it.builtin }
        assertEquals(Builtin.entries.toSet(), local.toSet())
    }

    @Test
    fun hostCommandsKeepTheirSourceAndHint() {
        val entries = CommandPalette.entries(host, local = true)
        assertEquals(CommandSource.Prompt, entries.first { it.name == "fix-tests" }.source)
        assertEquals(CommandSource.Skill, entries.first { it.name == "skill:pdf" }.source)
        assertEquals("[filter]", entries.first { it.name == "todo" }.hint)
    }

    @Test
    fun aHostCommandShadowsABuiltinOfTheSameName() {
        val entries = CommandPalette.entries(host + SlashCommand("compact", "Custom compaction", "extension"), local = true)
        assertEquals(1, entries.count { it.name == "compact" })
        assertEquals(CommandSource.Extension, entries.first { it.name == "compact" }.source)
        assertNull(CommandPalette.parse("/compact now", host + SlashCommand("compact", null, "extension"), local = true))
    }

    @Test
    fun filterIsFuzzyAndClosesOnASpace() {
        val entries = CommandPalette.entries(host, local = true)
        assertEquals(entries.size.coerceAtMost(CommandPalette.LIMIT), CommandPalette.filter("/", entries).size)
        assertEquals("fix-tests", CommandPalette.filter("/ft", entries).first().name)
        assertEquals("compact", CommandPalette.filter("/comp", entries).first().name)
        assertTrue(CommandPalette.filter("/compact ", entries).isEmpty())
        assertTrue(CommandPalette.filter("hello", entries).isEmpty())
        assertTrue(CommandPalette.filter("/zzzz", entries).isEmpty())
    }

    @Test
    fun filterAlsoFindsCommandsByTheirDescription() {
        val entries = CommandPalette.entries(host, local = true)
        assertTrue(CommandPalette.filter("/failing", entries).any { it.name == "fix-tests" })
    }

    @Test
    fun hintShowsWhileArgumentsAreTyped() {
        val entries = CommandPalette.entries(host, local = true)
        assertEquals("[instructions]", CommandPalette.hintFor("/compact focus on", entries)?.hint)
        assertEquals("[filter]", CommandPalette.hintFor("/todo ", entries)?.hint)
        assertNull(CommandPalette.hintFor("/compact", entries))
        assertNull(CommandPalette.hintFor("/fix-tests now", entries))
    }

    @Test
    fun parseFindsBuiltinsAndTheirArguments() {
        assertEquals(Builtin.Compact, CommandPalette.parse("/compact keep the API notes", host, local = false)?.builtin)
        assertEquals("keep the API notes", CommandPalette.parse("/compact keep the API notes", host, local = false)?.args)
        assertEquals(Builtin.Session, CommandPalette.parse("  /session  ", host, local = false)?.builtin)
        assertNull(CommandPalette.parse("/fix-tests", host, local = true))
        assertNull(CommandPalette.parse("/new", host, local = false))
        assertEquals(Builtin.New, CommandPalette.parse("/new", host, local = true)?.builtin)
        assertNull(CommandPalette.parse("please /compact", host, local = true))
        assertNull(CommandPalette.parse("/compacted", host, local = true))
    }

    @Test
    fun bangRunsTheShellOnThisPhoneOnly() {
        val call = assertNotNull(CommandPalette.parse("!ls -la", host, local = true))
        assertEquals(Builtin.Shell, call.builtin)
        assertEquals(ShellCommand("ls -la", excludeFromContext = false), ShellCommand.parse(call.args))
        assertEquals(ShellCommand("git status", excludeFromContext = true), ShellCommand.parse(CommandPalette.parse("!!git status", host, local = true)!!.args))
        assertNull(ShellCommand.parse("  "))
        assertNull(ShellCommand.parse("!"))
        assertNull(CommandPalette.parse("!ls", host, local = false))
    }

    @Test
    fun fuzzyPrefersPrefixesBoundariesAndShortNames() {
        val a = assertNotNull(Fuzzy.score("comp", "compact"))
        val b = assertNotNull(Fuzzy.score("comp", "skill:compose"))
        assertTrue(a > b)
        // Scattered: a character at a word start counts more than one mid-word.
        assertTrue(Fuzzy.score("ft", "fix-tests")!! > Fuzzy.score("ft", "fixatests")!!)
        assertNull(Fuzzy.score("xyz", "compact"))
        assertEquals(listOf("session", "skill:session-notes"), Fuzzy.rank("session", listOf("skill:session-notes", "session", "new"), 5) { it })
        assertEquals(0, Fuzzy.score("", "anything"))
    }

    @Test
    fun hardwareKeysMatchTheTerminal() {
        assertEquals(ComposerKeys.Action.Send, ComposerKeys.action(Key.Enter, ctrl = true, shift = false, running = false, hasContent = true))
        assertNull(ComposerKeys.action(Key.Enter, ctrl = false, shift = false, running = false, hasContent = true))
        assertEquals(ComposerKeys.Action.Stop, ComposerKeys.action(Key.Escape, ctrl = false, shift = false, running = true, hasContent = false))
        assertNull(ComposerKeys.action(Key.Escape, ctrl = false, shift = false, running = true, hasContent = true))
        assertEquals(ComposerKeys.Action.CycleModel, ComposerKeys.action(Key.P, ctrl = true, shift = false, running = false, hasContent = false))
        assertEquals(ComposerKeys.Action.CycleThinking, ComposerKeys.action(Key.Tab, ctrl = false, shift = true, running = false, hasContent = false))
    }

    @Test
    fun compactionSaysWhyItRuns() {
        assertEquals("Auto-compacting", compactionLabel("threshold"))
        assertEquals("Context full, compacting", compactionLabel("overflow"))
        assertEquals("Compacting", compactionLabel("manual"))
        assertEquals("Compacting", compactionLabel(null))
    }
}
