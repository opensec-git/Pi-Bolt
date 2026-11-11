package opensec.pibolt.app

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.runBlocking
import opensec.pibolt.app.ui.projects.ProjectFiles
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import opensec.pibolt.app.ui.projects.editor.EditorTabsHolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorRecreationTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    // PR #258 review: a rotation while editing lost the unsaved draft.
    @Test
    fun draftsSurviveActivityRecreation() {
        val holder = EditorTabsHolder()
        val saver = EditorTabs.saver(holder)
        val tabs = EditorTabs().also { holder.tabs = it }
        val tab = tabs.open("src/a.kt")
        tab.startEditing("fun a() = 1\n")
        tab.value = TextFieldValue("fun a() = 2\n")

        val saved = with(saver) { scope.save(tabs) }!!
        assertEquals(listOf("src/a.kt"), saved, "only the paths go into the Bundle")
        val restored = saver.restore(saved)!!

        assertSame(tabs, restored)
        val back = restored.find("src/a.kt")!!
        assertTrue(back.editing)
        assertEquals("fun a() = 2\n", back.value.text)
        assertTrue(back.dirty)
    }

    // Bug wave 0.6.0: "Ask Pi about the selection" (or the terminal, the secrets, a session) opened over the project and
    // the way back lost the draft: the screen left composition, saved its tabs, then released them as if closed for good.
    @Test
    fun draftsSurviveASessionOpenedOverTheProject() {
        val holder = EditorTabsHolder()
        val tabs = EditorTabs().also { holder.tabs = it }
        tabs.open("notes.md").apply {
            startEditing("a")
            value = TextFieldValue("ab")
        }
        // The screen leaves composition (its state is saved), then comes back from the back stack.
        val saved = with(EditorTabs.saver(holder)) { scope.save(tabs) }!!
        val back = EditorTabs.saver(holder).restore(saved)!!

        assertSame(tabs, back)
        assertEquals("ab", back.find("notes.md")!!.value.text)
        assertTrue(back.find("notes.md")!!.dirty)
    }

    @Test
    fun afterProcessDeathTabsReopenFromPaths() {
        val tabs = EditorTabs()
        tabs.open("b.kt").startEditing("x")
        val saved = with(EditorTabs.saver(EditorTabsHolder().also { it.tabs = tabs })) { scope.save(tabs) }!!

        // A new process: a new holder, nothing kept in memory.
        val holder = EditorTabsHolder()
        val restored = EditorTabs.saver(holder).restore(saved)!!
        assertNotSame(tabs, restored)
        assertSame(restored, holder.tabs)
        val back = restored.find("b.kt")!!
        assertFalse(back.editing, "no draft to show, so not editing")
        assertFalse(back.dirty)
    }

    // PR #258 review: saving through a temporary file and a rename dropped the executable bit of gradlew and scripts.
    @Test
    fun writeKeepsFilePermissions() = runBlocking {
        val dir = Files.createTempDirectory("pibolt-write").toFile()
        try {
            val script = dir.resolve("gradlew")
            script.writeText("#!/bin/sh\necho old\n")
            Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))

            assertTrue(ProjectFiles.write(script, "#!/bin/sh\necho new\n").isSuccess)

            assertEquals("#!/bin/sh\necho new\n", script.readText())
            assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(script.toPath())))
            assertFalse(dir.resolve(".gradlew.pibolt-tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
