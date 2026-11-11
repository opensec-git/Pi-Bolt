package opensec.pibolt.app

import opensec.pibolt.app.git.BranchStatus
import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.GitStatus
import opensec.pibolt.app.git.StatusEntry
import opensec.pibolt.app.ui.git.ChangesRules
import opensec.pibolt.app.ui.projects.ProjectBadges
import opensec.pibolt.app.ui.projects.ProjectNavRules
import opensec.pibolt.app.ui.projects.ProjectTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectNavTest {
    private fun status(n: Int) = GitStatus(BranchStatus(head = "main", oid = "abc"), List(n) { StatusEntry("f$it.kt", null, Change.Modified) })

    @Test
    fun tabsRoundTripTheirSavedIndex() {
        for (t in ProjectTab.entries) assertEquals(t, ProjectTab.of(t.index))
        // A stale or unknown saved value opens Sessions.
        assertEquals(ProjectTab.Sessions, ProjectTab.of(-1))
        assertEquals(ProjectTab.Sessions, ProjectTab.of(9))
        assertEquals(listOf("Sessions", "Files", "Changes", "Terminal"), ProjectTab.entries.map { it.label })
    }

    @Test
    fun changesBadgeCountsEveryChangedPath() {
        assertNull(ProjectNavRules.changesBadge(null), "not a repository, or not read yet")
        assertNull(ProjectNavRules.changesBadge(status(0)), "clean")
        assertEquals("1", ProjectNavRules.changesBadge(status(1)))
        val mixed = GitStatus(
            BranchStatus(head = "main", oid = "abc"),
            listOf(
                StatusEntry("a", Change.Modified, null),
                StatusEntry("b", null, Change.Untracked),
                StatusEntry("c", Change.Modified, Change.Modified),
                StatusEntry("d", Change.Conflicted, Change.Conflicted, conflicted = true),
            ),
        )
        assertEquals("4", ProjectNavRules.changesBadge(mixed), "a path staged and changed again counts once")
        assertEquals("99", ProjectNavRules.changesBadge(status(99)))
        assertEquals("99+", ProjectNavRules.changesBadge(status(100)))
    }

    @Test
    fun badgesAreSpokenAsWords() {
        val b = ProjectBadges(changes = "3", terminal = true, sessions = true)
        assertEquals("3 changed files", ProjectNavRules.badgeDescription(ProjectTab.Changes, b))
        assertEquals("1 changed file", ProjectNavRules.badgeDescription(ProjectTab.Changes, b.copy(changes = "1")))
        assertEquals("Shell running", ProjectNavRules.badgeDescription(ProjectTab.Terminal, b))
        assertEquals("Pi is working", ProjectNavRules.badgeDescription(ProjectTab.Sessions, b))
        assertNull(ProjectNavRules.badgeDescription(ProjectTab.Files, b))
        assertNull(ProjectNavRules.badgeDescription(ProjectTab.Changes, ProjectBadges()))
    }

    @Test
    fun barStepsAsideForTheKeyboardAndFullScreenViews() {
        assertTrue(ProjectNavRules.showBar(imeVisible = false, overlayOpen = false))
        assertFalse(ProjectNavRules.showBar(imeVisible = true, overlayOpen = false))
        assertFalse(ProjectNavRules.showBar(imeVisible = false, overlayOpen = true))
    }

    @Test
    fun chromeGivesWayToTheKeyboardOnlyInCompactHeight() {
        assertTrue(ProjectNavRules.showChrome(imeVisible = false, windowHeightDp = 360), "landscape, keyboard down")
        assertFalse(ProjectNavRules.showChrome(imeVisible = true, windowHeightDp = 360), "landscape, keyboard up")
        assertTrue(ProjectNavRules.showChrome(imeVisible = true, windowHeightDp = ProjectNavRules.COMPACT_HEIGHT_DP), "portrait keeps it")
        assertTrue(ProjectNavRules.showChrome(imeVisible = true, windowHeightDp = 880))
    }

    @Test
    fun commitBoxShowsOnceSomethingIsStaged() {
        assertFalse(ChangesRules.showCommit(0, ""))
        assertFalse(ChangesRules.showCommit(0, "   "))
        assertTrue(ChangesRules.showCommit(2, ""))
        // Unstaging everything mid-message keeps the box, so the text is not lost from view.
        assertTrue(ChangesRules.showCommit(0, "Fix the lexer"))
    }
}
