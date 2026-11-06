package opensec.pibolt.app

import opensec.pibolt.app.data.SafeFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFilesTest {
    // Release review: deleteRecursively followed a link in a cloned repo out of the project and deleted its target.
    @Test
    fun deleteTreeRemovesLinksNotTheirTargets() {
        val root = Files.createTempDirectory("safefiles").toFile()
        try {
            val keep = File(root, "other-project").apply { mkdirs() }
            File(keep, "keep.txt").writeText("keep")
            val project = File(root, "project").apply { mkdirs() }
            File(project, "a.txt").writeText("a")
            File(project, "sub").mkdirs()
            Files.createSymbolicLink(File(project, "sub/up").toPath(), root.toPath())
            Files.createSymbolicLink(File(project, "file-link").toPath(), File(keep, "keep.txt").toPath())
            val linkProject = File(root, "link-project")
            Files.createSymbolicLink(linkProject.toPath(), keep.toPath())

            assertEquals(1, SafeFiles.countFiles(project, cap = 100, skipDir = ".git"))
            assertFalse(SafeFiles.isRealDirectory(linkProject))
            assertTrue(SafeFiles.deleteTree(project))
            assertTrue(SafeFiles.deleteTree(linkProject))

            assertFalse(project.exists())
            assertFalse(Files.exists(linkProject.toPath(), LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", File(keep, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
