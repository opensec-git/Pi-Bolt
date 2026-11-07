package opensec.pibolt.app

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.runBlocking
import opensec.pibolt.app.ui.projects.FileEdit
import opensec.pibolt.app.ui.projects.ProjectFiles
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileEditTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    // PR #258 review: a rotation while editing restored `editing` but not the draft, so Save could write an empty file.
    @Test
    fun draftSurvivesActivityRecreation() {
        val saver = FileEdit.saver("/p/a.kt")
        val edit = FileEdit()
        edit.start("fun a() = 1\n")
        edit.draft = TextFieldValue("fun a() = 2\n")

        val saved = with(saver) { scope.save(edit) }
        assertEquals("/p/a.kt", saved, "only the key goes into the Bundle")
        val restored = saver.restore(saved!!)!!

        assertSame(edit, restored)
        assertTrue(restored.editing)
        assertEquals("fun a() = 2\n", restored.draft.text)
        assertTrue(restored.dirty)
    }

    @Test
    fun afterProcessDeathOrReleaseEditingStartsOver() {
        val saver = FileEdit.saver("/p/b.kt")
        val edit = FileEdit()
        edit.start("x")
        edit.draft = TextFieldValue("y")
        val saved = with(saver) { scope.save(edit) }!!
        FileEdit.release("/p/b.kt")

        val restored = saver.restore(saved)!!
        assertFalse(restored.editing, "no draft to show, so not editing (Save stays unavailable)")
        assertFalse(restored.dirty)
    }

    @Test
    fun dirtyComparesWithTheTextEditingStartedFrom() {
        val edit = FileEdit()
        assertFalse(edit.dirty)
        edit.start("a")
        assertFalse(edit.dirty)
        edit.draft = TextFieldValue("ab")
        assertTrue(edit.dirty)
        edit.stop()
        assertFalse(edit.editing)
        assertFalse(edit.dirty)
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
