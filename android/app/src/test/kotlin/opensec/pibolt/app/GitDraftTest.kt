package opensec.pibolt.app

import opensec.pibolt.app.ui.git.PiDraft
import kotlin.test.Test
import kotlin.test.assertEquals

class GitDraftTest {
    @Test
    fun draftsComeOutOfTheirFence() {
        assertEquals("Fix the login crash\n\nThe token was read too early.", PiDraft.clean("Here it is:\n\n```text\nFix the login crash\n\nThe token was read too early.\n```\nWant changes?"))
        assertEquals("Add tests", PiDraft.clean("  Add tests  "))
        assertEquals("Open fence", PiDraft.clean("```\nOpen fence"))
    }

    @Test
    fun pullRequestDraftSplitsTitleAndBody() {
        assertEquals("Add the Changes tab" to "- status\n- diffs", PiDraft.splitTitle("# Add the Changes tab\n\n- status\n- diffs"))
        assertEquals("One line" to "", PiDraft.splitTitle("Title: One line"))
    }
}
