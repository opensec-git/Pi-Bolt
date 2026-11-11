package opensec.pibolt.app

import androidx.compose.ui.input.key.Key
import opensec.pibolt.app.ui.session.ComposerKeys
import opensec.pibolt.app.ui.session.ModelChipText
import opensec.pibolt.app.ui.session.ProjectTraits
import opensec.pibolt.app.ui.session.PromptHistory
import opensec.pibolt.app.ui.session.StarterPrompts
import opensec.pibolt.app.ui.session.thinkingChipLabel
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptHistoryTest {
    @Test
    fun upAndDownWalkTheHistoryAndGiveTheDraftBack() {
        val h = PromptHistory(listOf("newest", "middle", "oldest"))
        assertEquals("newest", h.older("my draft"))
        assertEquals("middle", h.older("newest"))
        assertEquals("oldest", h.older("middle"))
        assertNull(h.older("oldest"), "nothing before the oldest")
        assertEquals("middle", h.newer())
        assertEquals("newest", h.newer())
        assertEquals("my draft", h.newer())
        assertNull(h.newer(), "already at the draft")
    }

    @Test
    fun editingARecalledPromptEndsBrowsing() {
        val h = PromptHistory(listOf("a", "b"))
        assertEquals("a", h.older(""))
        h.edited("a and more")
        assertEquals(-1, h.index)
        assertEquals("a", h.older("a and more"))
        assertEquals("a and more", h.newer())
    }

    @Test
    fun emptyHistoryDoesNothing() {
        val h = PromptHistory(emptyList())
        assertNull(h.older("x"))
        assertNull(h.newer())
    }

    @Test
    fun newEntriesResetTheWalk() {
        val h = PromptHistory(listOf("a"))
        h.older("")
        h.update(listOf("b", "a"))
        assertEquals(-1, h.index)
        assertEquals("b", h.older(""))
    }

    @Test
    fun lineChecksKeepMultiLineEditing() {
        assertTrue(PromptHistory.onFirstLine("", 0))
        assertTrue(PromptHistory.onFirstLine("one\ntwo", 2))
        assertFalse(PromptHistory.onFirstLine("one\ntwo", 5))
        assertTrue(PromptHistory.onLastLine("one\ntwo", 5))
        assertFalse(PromptHistory.onLastLine("one\ntwo", 1))
        assertTrue(PromptHistory.onLastLine("one", 3))
    }

    @Test
    fun hardwareShortcuts() {
        fun a(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, running: Boolean = false, content: Boolean = false, first: Boolean = true, last: Boolean = true) =
            ComposerKeys.action(key, ctrl, shift, running, content, alt, first, last)
        assertEquals(ComposerKeys.Action.FollowUp, a(Key.Enter, alt = true, running = true, content = true))
        assertEquals(ComposerKeys.Action.RestoreQueue, a(Key.DirectionUp, alt = true))
        assertEquals(ComposerKeys.Action.OpenModels, a(Key.L, ctrl = true))
        assertEquals(ComposerKeys.Action.Search, a(Key.F, ctrl = true, shift = true))
        assertNull(a(Key.F, ctrl = true))
        assertEquals(ComposerKeys.Action.Paste, a(Key.V, ctrl = true))
        assertEquals(ComposerKeys.Action.HistoryOlder, a(Key.DirectionUp))
        assertNull(a(Key.DirectionUp, first = false), "Up inside a multi-line prompt moves the cursor")
        assertEquals(ComposerKeys.Action.HistoryNewer, a(Key.DirectionDown))
        assertNull(a(Key.DirectionDown, last = false))
        assertNull(a(Key.DirectionUp, shift = true), "Shift+Up selects text")
    }

    @Test
    fun thinkingChipSaysWhatTheLevelIs() {
        assertEquals("Thinking: medium", thinkingChipLabel("medium"))
        assertEquals("Thinking off", thinkingChipLabel("off"))
    }

    @Test
    fun runSettingsChipJoinsModelAndLevel() {
        val chip = ModelChipText.of("Sonnet 4.5", "high")
        assertEquals("Sonnet 4.5", chip.model)
        assertEquals("High", chip.level)
        assertEquals("Model: Sonnet 4.5, thinking high", chip.description)
        // Off and a model that does not reason show the model alone; TalkBack still hears "thinking off".
        assertNull(ModelChipText.of("Sonnet 4.5", "off").level)
        assertEquals("Model: Sonnet 4.5, thinking off", ModelChipText.of("Sonnet 4.5", "off").description)
        assertNull(ModelChipText.of("Fake", null).level)
        assertEquals("Model: Fake", ModelChipText.of("Fake", null).description)
        assertEquals("Model", ModelChipText.of(null, "low").model)
        assertEquals("Model: not set, thinking low", ModelChipText.of(" ", "low").description)
    }

    @Test
    fun startersFollowTheProject() {
        val code = StarterPrompts.pick(ProjectTraits(empty = false, git = true, tests = true))
        assertEquals(listOf("Inspect the project", "Run the tests", "Explain the structure", "Summarise recent commits"), code.map { it.label })
        val noGit = StarterPrompts.pick(ProjectTraits(empty = false, git = false, tests = false))
        assertEquals(listOf("Inspect the project", "Explain the structure", "Set up git"), noGit.map { it.label })
        val empty = StarterPrompts.pick(ProjectTraits(empty = true, git = false, tests = false))
        assertEquals(listOf("Start a new project", "Set up git"), empty.map { it.label })
        assertEquals(4, StarterPrompts.pick(null).size)
    }

    @Test
    fun traitsFromTheFolder() {
        val dir = Files.createTempDirectory("starters").toFile()
        try {
            assertEquals(ProjectTraits(empty = true, git = false, tests = false), StarterPrompts.traits(dir))
            File(dir, ".git").mkdir()
            File(dir, "package.json").writeText("{}")
            assertEquals(ProjectTraits(empty = false, git = true, tests = true), StarterPrompts.traits(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
