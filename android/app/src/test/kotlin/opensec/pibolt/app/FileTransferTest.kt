package opensec.pibolt.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.projects.transfer.ArchiveTooLargeException
import opensec.pibolt.app.ui.projects.transfer.ShareAction
import opensec.pibolt.app.ui.projects.transfer.ShareRouting
import opensec.pibolt.app.ui.projects.transfer.SharedContent
import opensec.pibolt.app.ui.projects.transfer.SharedItem
import opensec.pibolt.app.ui.projects.transfer.TransferProgress
import opensec.pibolt.app.ui.projects.transfer.UnsafeEntryException
import opensec.pibolt.app.ui.projects.transfer.ZipTransfer
import opensec.pibolt.app.ui.session.voice.VoiceDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, String?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                body?.let { z.write(it.toByteArray()) }
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun names(bytes: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        generateSequence { z.nextEntry }.map { it.name }.toList()
    }

    @Test
    fun safePathsAreNormalisedAndEscapesRefused() {
        assertEquals("src/a.kt", ZipTransfer.safePath("src/a.kt"))
        assertEquals("src/a.kt", ZipTransfer.safePath("./src//a.kt"))
        assertEquals("dir", ZipTransfer.safePath("dir/"))
        assertEquals("win/path.txt", ZipTransfer.safePath("win\\path.txt"))
        assertNull(ZipTransfer.safePath("../evil.sh"))
        assertNull(ZipTransfer.safePath("a/../../evil.sh"))
        assertNull(ZipTransfer.safePath("a\\..\\..\\evil.sh"))
        assertNull(ZipTransfer.safePath("/etc/passwd"))
        assertNull(ZipTransfer.safePath("C:/Windows/x"))
        assertNull(ZipTransfer.safePath("a\u0000b"))
        assertNull(ZipTransfer.safePath(""))
        assertNull(ZipTransfer.safePath("./"))
    }

    @Test
    fun unzipWritesFilesAndFolders() = runBlocking {
        val target = tmp.newFolder("project")
        val zip = zipOf("src/" to null, "src/Main.kt" to "fun main() {}", "README.md" to "# Hi", "empty/" to null)
        val progress = mutableListOf<TransferProgress>()
        val files = ZipTransfer.unzip(ByteArrayInputStream(zip), target, zip.size.toLong()) { progress += it }
        assertEquals(2, files)
        assertEquals("fun main() {}", File(target, "src/Main.kt").readText())
        assertEquals("# Hi", File(target, "README.md").readText())
        assertTrue(File(target, "empty").isDirectory)
        assertEquals(2, progress.last().files)
        assertTrue(progress.last().bytes > 0)
    }

    /** Zip slip: an entry naming a path outside the target is refused and nothing is written outside. */
    @Test
    fun zipSlipIsRejected() = runBlocking {
        val root = tmp.newFolder("root")
        val target = File(root, "project").apply { mkdirs() }
        val zip = zipOf("ok.txt" to "fine", "../escaped.txt" to "owned")
        try {
            ZipTransfer.unzip(ByteArrayInputStream(zip), target)
            fail("expected the archive to be refused")
        } catch (e: UnsafeEntryException) {
            assertEquals("../escaped.txt", e.entry)
        }
        assertFalse(File(root, "escaped.txt").exists())
        val absolute = zipOf("/tmp/pibolt-zip-slip.txt" to "owned")
        try {
            ZipTransfer.unzip(ByteArrayInputStream(absolute), target)
            fail("expected an absolute entry to be refused")
        } catch (_: UnsafeEntryException) {
        }
    }

    @Test
    fun zipSlipThroughASymlinkedFolderIsRejected() = runBlocking {
        val root = tmp.newFolder("root2")
        val outside = File(root, "outside").apply { mkdirs() }
        val target = File(root, "project").apply { mkdirs() }
        Files.createSymbolicLink(File(target, "link").toPath(), outside.toPath())
        val zip = zipOf("link/planted.txt" to "owned")
        try {
            ZipTransfer.unzip(ByteArrayInputStream(zip), target)
            fail("expected a path through a link to be refused")
        } catch (_: UnsafeEntryException) {
        }
        assertFalse(File(outside, "planted.txt").exists())
    }

    @Test
    fun zipExportRoundTrips() = runBlocking {
        val src = tmp.newFolder("calc")
        File(src, "src").mkdirs()
        File(src, "src/a.kt").writeText("val a = 1")
        File(src, "notes.txt").writeText("n".repeat(200_000))
        File(src, ".git").mkdirs()
        File(src, ".git/HEAD").writeText("ref: refs/heads/main")
        Files.createSymbolicLink(File(src, "link").toPath(), File("/etc").toPath())
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<TransferProgress>()
        val files = ZipTransfer.zip(src, out, prefix = "calc", skip = ZipTransfer.HEAVY) { progress += it }
        assertEquals(2, files)
        val names = names(out.toByteArray())
        assertEquals(listOf("calc/", "calc/notes.txt", "calc/src/", "calc/src/a.kt"), names)
        assertEquals(1f, progress.last().fraction)
        // Unpacked again, a single top folder moves up.
        val back = tmp.newFolder("back")
        ZipTransfer.unzip(ByteArrayInputStream(out.toByteArray()), back)
        assertEquals("calc", ZipTransfer.hoistSingleFolder(back))
        assertEquals("val a = 1", File(back, "src/a.kt").readText())
        assertEquals(200_000L, File(back, "notes.txt").length())
        assertNull(ZipTransfer.hoistSingleFolder(back))
    }

    @Test
    fun cancellingStopsAnExport() {
        val src = tmp.newFolder("big")
        repeat(20) { File(src, "f$it.bin").writeBytes(ByteArray(256 * 1024)) }
        val job = Job()
        var calls = 0
        try {
            runBlocking {
                withContext(job) {
                    ZipTransfer.zip(src, ByteArrayOutputStream()) {
                        if (++calls == 3) job.cancel()
                    }
                }
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(calls in 3..4)
    }

    @Test
    fun hugeEntryCountIsRefused() {
        // A zip bomb in entry count: the cap trips long before the end.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> repeat(ZipTransfer.MAX_ENTRIES + 1) { z.putNextEntry(ZipEntry("d$it/")); z.closeEntry() } }
        try {
            runBlocking { ZipTransfer.unzip(ByteArrayInputStream(out.toByteArray()), tmp.newFolder("bomb")) }
            fail("expected the archive to be refused")
        } catch (_: ArchiveTooLargeException) {
        }
    }

    @Test
    fun namesForImports() {
        assertEquals("calc-parser-main", ZipTransfer.projectName("calc-parser-main.zip"))
        assertEquals("Imported project", ZipTransfer.projectName(null))
        assertEquals("a_b", ZipTransfer.safeName("a/b"))
        assertNull(ZipTransfer.safeName(".."))
        val taken = setOf("a.txt", "a (2).txt", "dir")
        assertEquals("a (3).txt", ZipTransfer.uniqueName("a.txt") { it in taken })
        assertEquals("dir (2)", ZipTransfer.uniqueName("dir") { it in taken })
        assertEquals("b.txt", ZipTransfer.uniqueName("b.txt") { it in taken })
        assertEquals(".env (2)", ZipTransfer.uniqueName(".env") { it == ".env" })
        // A dot-leading archive name gave a hidden project name no suffix could free: import spun forever.
        assertEquals("env", ZipTransfer.projectName(".env.zip"))
        assertEquals("Imported project", ZipTransfer.projectName("...zip"))
        assertEquals("env", ZipTransfer.uniqueName(ZipTransfer.projectName(".env.zip")) { it.startsWith(".") })
        assertThrows(IOException::class.java) { ZipTransfer.uniqueName("x") { true } }
    }

    // ---- Share-intent routing

    private fun item(mime: String?, name: String? = null) = SharedItem("content://x/${name ?: mime}", mime, name)

    @Test
    fun sharedImagesDefaultToTheComposer() {
        val r = ShareRouting.route(SharedContent(listOf(item("image/png"), item("image/jpeg"))), maxImages = 4)
        assertEquals(listOf(ShareAction.AttachImages, ShareAction.ImportFiles), r.actions)
        assertTrue(r.needsProject(ShareAction.AttachImages))
        assertEquals(2, r.images)
    }

    @Test
    fun tooManyImagesCanOnlyBeImported() {
        val r = ShareRouting.route(SharedContent(List(5) { item("image/png") }), maxImages = 4)
        assertEquals(listOf(ShareAction.ImportFiles), r.actions)
    }

    @Test
    fun aZipDefaultsToANewProject() {
        val r = ShareRouting.route(SharedContent(listOf(item("application/zip", "repo-main.zip"))), maxImages = 4)
        assertEquals(ShareAction.NewProjectFromZip, r.default)
        assertFalse(r.needsProject(ShareAction.NewProjectFromZip))
        assertTrue(ShareAction.ImportFiles in r.actions)
        // Known by its name when the sender says octet-stream.
        assertEquals(ShareAction.NewProjectFromZip, ShareRouting.route(SharedContent(listOf(item("application/octet-stream", "a.zip"))), 4).default)
    }

    @Test
    fun mixedOrOtherFilesImport() {
        val r = ShareRouting.route(SharedContent(listOf(item("image/png"), item("text/plain", "a.txt"))), maxImages = 4)
        assertEquals(listOf(ShareAction.ImportFiles), r.actions)
        val two = ShareRouting.route(SharedContent(listOf(item("application/zip", "a.zip"), item("application/zip", "b.zip"))), 4)
        assertEquals(listOf(ShareAction.ImportFiles), two.actions)
    }

    @Test
    fun textGoesToTheComposer() {
        val r = ShareRouting.route(SharedContent(emptyList(), text = "https://example.com/issue/1"), 4)
        assertEquals(listOf(ShareAction.InsertText), r.actions)
        assertEquals("Bug\nhttps://x", ShareRouting.composerText(SharedContent(emptyList(), "https://x", "Bug")))
        assertEquals("see Bug here", ShareRouting.composerText(SharedContent(emptyList(), "see Bug here", "Bug")))
        val withFile = ShareRouting.route(SharedContent(listOf(item("text/plain", "log.txt")), text = "caption"), 4)
        assertEquals(listOf(ShareAction.ImportFiles, ShareAction.InsertText), withFile.actions)
        assertTrue(ShareRouting.route(SharedContent(emptyList(), text = "  "), 4).actions.isEmpty())
    }

    @Test
    fun imagesAreKnownByTypeOrName() {
        assertTrue(ShareRouting.isImage(item("image/webp")))
        assertTrue(ShareRouting.isImage(item(null, "shot.PNG")))
        assertFalse(ShareRouting.isImage(item("image/svg+xml", "a.svg")))
        assertFalse(ShareRouting.isImage(item("application/pdf", "a.pdf")))
    }

    // ---- Voice

    @Test
    fun dictationGoesAtTheCursorWithSpaces() {
        val v = VoiceDraft("fix the bug", 4)
        assertEquals("fix parser the bug" to 10, v.with("parser"))
        assertEquals("fix big parser the bug" to 14, v.with(" big  parser "))
        assertEquals("fix the bug" to 4, v.with(""))
        assertEquals("hello" to 5, VoiceDraft("", 0).with("hello"))
        assertEquals("explain this." to 12, VoiceDraft("explain .", 8).with("this"))
    }

    @Test
    fun dictationReplacesTheSelection() {
        assertEquals("rename foo to bar" to 17, VoiceDraft("rename x to y", 7, 13).with("foo to bar"))
    }
}
