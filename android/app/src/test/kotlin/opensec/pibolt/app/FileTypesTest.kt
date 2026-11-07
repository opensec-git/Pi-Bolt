package opensec.pibolt.app

import opensec.pibolt.app.ui.projects.FileEntry
import opensec.pibolt.app.ui.projects.FileKind
import opensec.pibolt.app.ui.projects.FileMatch
import opensec.pibolt.app.ui.projects.FileSearch
import opensec.pibolt.app.ui.projects.FileSort
import opensec.pibolt.app.ui.projects.FileTypes
import opensec.pibolt.app.ui.projects.ProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypesTest {
    @Test
    fun kindsFromNames() {
        assertEquals(FileKind.Markdown, FileTypes.kind("README.md"))
        assertEquals(FileKind.Markdown, FileTypes.kind("docs/Guide.MARKDOWN"))
        assertEquals(FileKind.Markdown, FileTypes.kind("page.mdx"))
        assertEquals(FileKind.Code, FileTypes.kind("src/Parser.kt"))
        assertEquals(FileKind.Code, FileTypes.kind("build.gradle.kts"))
        assertEquals(FileKind.Code, FileTypes.kind("index.tsx"))
        assertEquals(FileKind.Code, FileTypes.kind("Dockerfile"))
        assertEquals(FileKind.Code, FileTypes.kind("Makefile"))
        assertEquals(FileKind.Code, FileTypes.kind("icon.svg"))
        assertEquals(FileKind.Image, FileTypes.kind("assets/logo.PNG"))
        assertEquals(FileKind.Image, FileTypes.kind("photo.jpeg"))
        assertEquals(FileKind.Binary, FileTypes.kind("app.apk"))
        assertEquals(FileKind.Binary, FileTypes.kind("archive.tar.gz"))
        assertEquals(FileKind.Binary, FileTypes.kind("font.woff2"))
        assertEquals(FileKind.Text, FileTypes.kind("notes.txt"))
        assertEquals(FileKind.Text, FileTypes.kind("LICENSE"))
        assertEquals(FileKind.Text, FileTypes.kind("package-lock.lock"))
        assertEquals(FileKind.Text, FileTypes.kind(".gitignore"))
        assertEquals(FileKind.Text, FileTypes.kind("trailing."))
    }

    @Test
    fun languagesAndLabels() {
        assertEquals("kotlin", FileTypes.language("Parser.kt"))
        assertEquals("ts", FileTypes.language("a.ts"))
        assertEquals("python", FileTypes.language("x.py"))
        assertNull(FileTypes.language("README.md"))
        assertNull(FileTypes.language("notes.txt"))
        assertEquals("Kotlin", FileTypes.label("Parser.kt"))
        assertEquals("TypeScript", FileTypes.label("a.ts"))
        assertEquals("Markdown", FileTypes.label("README.md"))
        assertEquals("PNG image", FileTypes.label("logo.png"))
        assertEquals("ZIP file", FileTypes.label("a.zip"))
        assertEquals("Text", FileTypes.label("LICENSE"))
        assertEquals("image/png", FileTypes.mime("logo.png"))
        assertEquals("text/markdown", FileTypes.mime("README.md"))
        assertEquals("text/plain", FileTypes.mime("Parser.kt"))
        assertEquals("application/octet-stream", FileTypes.mime("lib.so"))
    }

    @Test
    fun binarySniffing() {
        assertFalse(FileTypes.looksBinary("hello\n\tworld\r\n".toByteArray()))
        assertFalse(FileTypes.looksBinary("grüße, 日本語 ✓".toByteArray()))
        assertFalse(FileTypes.looksBinary(ByteArray(0)))
        assertTrue(FileTypes.looksBinary(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x14)))
        assertTrue(FileTypes.looksBinary(ByteArray(100) { if (it % 3 == 0) 1 else 65 }))
        // Only the given length counts.
        assertFalse(FileTypes.looksBinary(byteArrayOf(65, 66, 0), 2))
    }

    @Test
    fun pagesCutAtLineEnds() {
        val text = "one\ntwo\nthr".toByteArray()
        assertEquals(8, ProjectFiles.pageCut(text, text.size))
        val noNewline = "abc".toByteArray()
        assertEquals(3, ProjectFiles.pageCut(noNewline, 3))
        // "aé" cut inside é (0xC3 0xA9): the lead byte goes to the next page.
        val split = byteArrayOf(0x61, 0xC3.toByte())
        assertEquals(1, ProjectFiles.pageCut(split, 2))
    }

    @Test
    fun searchFindsMatchesPerLine() {
        val text = "fun parse()\nval Parse = 1\n\nparse(parse)\n\n"
        val lines = FileSearch.lines(text)
        assertEquals(4, lines.size)
        val found = FileSearch.find(text, lines, "parse")
        assertEquals(listOf(FileMatch(0, 4, 9), FileMatch(1, 4, 9), FileMatch(3, 0, 5), FileMatch(3, 6, 11)), found)
        assertEquals(emptyList<FileMatch>(), FileSearch.find(text, lines, ""))
        assertEquals(emptyList<FileMatch>(), FileSearch.find(text, lines, "e()\nval"))
        assertEquals(2, FileSearch.find(text, lines, "parse", cap = 2).size)
        assertEquals("2 of 4", FileSearch.counter(1, 4, capped = false))
        assertEquals("No matches", FileSearch.counter(0, 0, capped = false))
        assertEquals(0, FileSearch.lineIndex("1", 4))
        assertEquals(3, FileSearch.lineIndex("999", 4))
        assertNull(FileSearch.lineIndex("x", 4))
    }

    @Test
    fun filesSortAndHide() {
        val entries = listOf(
            FileEntry("b.txt", false, 10, modified = 3),
            FileEntry("src", true, 0, modified = 1),
            FileEntry("A.md", false, 500, modified = 1),
            FileEntry(".git", true, 0, modified = 9),
            FileEntry(".gitignore", false, 5, modified = 2),
        )
        assertEquals(listOf(".git", "src", ".gitignore", "A.md", "b.txt"), ProjectFiles.order(entries, FileSort.Name).map { it.name })
        assertEquals(listOf(".git", "src", "b.txt", ".gitignore", "A.md"), ProjectFiles.order(entries, FileSort.Modified).map { it.name })
        assertEquals(listOf(".git", "src", "A.md", "b.txt", ".gitignore"), ProjectFiles.order(entries, FileSort.Size).map { it.name })
        assertEquals(listOf("b.txt", "src", "A.md", ".gitignore"), ProjectFiles.visible(entries, showHidden = false).map { it.name })
        assertEquals(5, ProjectFiles.visible(entries, showHidden = true).size)
    }

    @Test
    fun breadcrumbsAndNames() {
        assertEquals(listOf("calc" to ""), ProjectFiles.crumbs("calc", ""))
        assertEquals(listOf("calc" to "", "src" to "src", "ui" to "src/ui"), ProjectFiles.crumbs("calc", "src/ui"))
        assertNull(ProjectFiles.nameError(".env", listOf("a")))
        assertEquals("Give it a name", ProjectFiles.nameError("  ", emptyList()))
        assertEquals("No slashes or control characters", ProjectFiles.nameError("a/b", emptyList()))
        assertEquals("Not a usable name", ProjectFiles.nameError("..", emptyList()))
        assertEquals("Something with this name exists here", ProjectFiles.nameError("a", listOf("a")))
    }
}
