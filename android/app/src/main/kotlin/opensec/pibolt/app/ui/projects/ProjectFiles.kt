package opensec.pibolt.app.ui.projects

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Tnum
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One directory entry: one stat (type, size and time together), taken off the main thread. */
@Immutable
data class FileEntry(val name: String, val dir: Boolean, val size: Long, val modified: Long = 0) {
    /** A dot file: listed, but dimmed. */
    val dot: Boolean get() = name.startsWith(".")

    /** Version-control and OS internals, listed only with "Show hidden files". */
    val hidden: Boolean get() = name in HIDDEN

    private companion object {
        val HIDDEN = setOf(".git", ".hg", ".svn", ".DS_Store")
    }
}

/** When and how big a file was on disk, to notice that someone else (Pi, a shell) changed it. */
@Immutable
data class FileStamp(val modified: Long, val size: Long)

/** Order of the Files tab. Folders always come first. */
enum class FileSort(val label: String) { Name("Name"), Modified("Last changed"), Size("Size") }

/** A file opened in the viewer. */
@Immutable
sealed interface FileDoc {
    /** Text read up to [next] (null when the whole file is in [text]). */
    data class Text(val text: String, val next: Long?, val size: Long) : FileDoc {
        val complete: Boolean get() = next == null
    }

    data class Image(val bitmap: ImageBitmap, val width: Int, val height: Int, val size: Long) : FileDoc

    data class Binary(val size: Long) : FileDoc

    data class Unreadable(val reason: String) : FileDoc
}

/**
 * The project's files, one directory at a time. Listing and stat run on [Dispatchers.IO] (10k entries is one pass of
 * `readAttributes`); a directory's item count is read only when its row is on screen; file contents load a page at a
 * time ([PAGE] bytes, cut at a line end) when opened. Git status needs the engine's git (a process per call) and is
 * not computed here; the branch comes from `.git/HEAD` when the header asks for it.
 */
object ProjectFiles {
    /** Bytes of text read per page; "Load more" reads the next one. */
    const val PAGE = 256 * 1024

    /** Files up to this size can be edited (they load whole). */
    const val MAX_EDIT = 1024 * 1024

    /** Images larger than this many pixels on a side are decoded downsampled. */
    private const val MAX_IMAGE_SIDE = 2048

    suspend fun list(dir: File): List<FileEntry> = withContext(Dispatchers.IO) {
        val names = dir.list() ?: return@withContext emptyList()
        order(
            names.map { name ->
                val path = File(dir, name).toPath()
                val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                // A link to a folder (pnpm's node_modules, a shared `docs`) opens as that folder, not as a file that
                // "no longer exists". Delete still removes only the link (SafeFiles never follows one).
                val folder = attrs?.isDirectory == true || attrs?.isSymbolicLink == true && Files.isDirectory(path)
                FileEntry(name, folder, attrs?.size() ?: 0, attrs?.lastModifiedTime()?.toMillis() ?: 0)
            },
        )
    }

    /** Folders first, then files by [sort] (name ascending; newest or largest first), ties by name. */
    fun order(entries: List<FileEntry>, sort: FileSort = FileSort.Name): List<FileEntry> {
        val byName = compareBy<FileEntry> { it.name.lowercase() }.thenBy { it.name }
        val inner = when (sort) {
            FileSort.Name -> byName
            FileSort.Modified -> compareByDescending<FileEntry> { it.modified }.then(byName)
            FileSort.Size -> compareByDescending<FileEntry> { if (it.dir) 0 else it.size }.then(byName)
        }
        return entries.sortedWith(compareByDescending<FileEntry> { it.dir }.then(inner))
    }

    /** `.git` and other internals ([FileEntry.hidden]) are left out unless [showHidden]; other dot files stay. */
    fun visible(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> = if (showHidden) entries else entries.filterNot { it.hidden }

    /** Breadcrumbs for [path] ("" is the root): the root label first, then each folder with its path. */
    fun crumbs(root: String, path: String): List<Pair<String, String>> {
        val out = mutableListOf(root to "")
        if (path.isEmpty()) return out
        var acc = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            out += part to acc
        }
        return out
    }

    suspend fun count(dir: File): Int = withContext(Dispatchers.IO) { dir.list()?.size ?: 0 }

    /** Opens [file] for the viewer: the first text page, a decoded image, or a binary note. */
    suspend fun open(file: File): FileDoc = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext FileDoc.Unreadable("This file no longer exists.")
        val size = file.length()
        when (FileTypes.kind(file.name)) {
            FileKind.Binary -> FileDoc.Binary(size)
            FileKind.Image -> decodeImage(file, size) ?: FileDoc.Binary(size)
            else -> {
                val head = runCatching { file.inputStream().use { input -> ByteArray(FileTypes.SNIFF).let { b -> b to input.read(b).coerceAtLeast(0) } } }.getOrNull()
                    ?: return@withContext FileDoc.Unreadable("This file can't be read.")
                if (FileTypes.looksBinary(head.first, head.second)) FileDoc.Binary(size) else readPage(file, 0, size)
            }
        }
    }

    /** The next page of [doc] appended to it. */
    suspend fun more(file: File, doc: FileDoc.Text): FileDoc.Text = withContext(Dispatchers.IO) {
        val from = doc.next ?: return@withContext doc
        val page = readPage(file, from, file.length())
        if (page is FileDoc.Text) page.copy(text = doc.text + page.text) else doc.copy(next = null)
    }

    private fun readPage(file: File, from: Long, size: Long): FileDoc = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val want = minOf(PAGE.toLong(), size - from).toInt().coerceAtLeast(0)
            val buf = ByteArray(want)
            var read = 0
            while (read < want) {
                val r = raf.read(buf, read, want - read)
                if (r < 0) break
                read += r
            }
            val end = from + read
            // Cut a partial page at its last line end so a multi-byte character or a line is never split.
            val cut = if (end < size) pageCut(buf, read) else read
            FileDoc.Text(String(buf, 0, cut, Charsets.UTF_8), if (from + cut < size) from + cut else null, size)
        }
    }.getOrElse { FileDoc.Unreadable("This file can't be read.") }

    /** Bytes of a partial page to keep: up to and including its last `\n`, or all of it when it has none. */
    fun pageCut(buf: ByteArray, length: Int): Int {
        for (i in length - 1 downTo 0) if (buf[i] == '\n'.code.toByte()) return i + 1
        // One very long line: cut before a UTF-8 continuation byte run.
        var i = length
        while (i > 0 && (buf[i - 1].toInt() and 0xC0) == 0x80) i--
        return if (i > 0 && (buf[i - 1].toInt() and 0x80) != 0) i - 1 else length
    }

    private fun decodeImage(file: File, size: Long): FileDoc.Image? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        FileDoc.Image(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight, size)
    }.getOrNull()

    /**
     * Writes [text] to [file] through a temporary file and a rename, so a failed save never leaves half a file. The
     * temporary file takes the original's permissions first, so an edited `gradlew` or script stays executable. A
     * symbolic link (`CLAUDE.md -> AGENTS.md`) stays a link: the file it points to is written.
     */
    suspend fun write(file: File, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = if (Files.isSymbolicLink(file.toPath())) file.toPath().toRealPath().toFile() else file
            val tmp = File(target.parentFile, ".${target.name}.pibolt-tmp")
            tmp.writeText(text)
            if (target.exists()) copyPermissions(target, tmp)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeText(text)
            }
        }
    }

    /**
     * Whether the file changed on disk since it was read as [read]: another time or size, or it is gone. Null [read]
     * (never stamped) is no change.
     */
    fun changedOnDisk(read: FileStamp?, now: FileStamp?): Boolean = read != null && read != now

    /** [file]'s modification time (nanoseconds where the file system has them) and size, or null when it is gone. */
    suspend fun stamp(file: File): FileStamp? = withContext(Dispatchers.IO) {
        runCatching {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            FileStamp(attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS), attrs.size())
        }.getOrNull()
    }

    /** Gives [to] the POSIX permissions of [from]; where those are unsupported, at least its executable bit. */
    private fun copyPermissions(from: File, to: File) {
        runCatching { Files.setPosixFilePermissions(to.toPath(), Files.getPosixFilePermissions(from.toPath())) }
            .onFailure { if (from.canExecute()) to.setExecutable(true) }
    }

    /** Why [name] cannot be a new entry next to [existing], or null. Dot files are allowed here. */
    fun nameError(name: String, existing: Collection<String>): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Give it a name"
            n.length > 255 -> "At most 255 characters"
            n == "." || n == ".." -> "Not a usable name"
            n.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } -> "No slashes or control characters"
            existing.any { it == n } -> "Something with this name exists here"
            else -> null
        }
    }

    suspend fun create(dir: File, name: String, folder: Boolean): Result<File> = withContext(Dispatchers.IO) {
        val n = name.trim()
        nameError(n, dir.list()?.toList().orEmpty())?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        val target = File(dir, n)
        val ok = runCatching { if (folder) target.mkdirs() else { dir.mkdirs(); target.createNewFile() } }.getOrDefault(false)
        if (ok) Result.success(target) else Result.failure(IllegalStateException(if (folder) "Could not create the folder" else "Could not create the file"))
    }

    suspend fun rename(file: File, name: String): Result<File> = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (n == file.name) return@withContext Result.success(file)
        val parent = file.parentFile ?: return@withContext Result.failure(IllegalStateException("Can't rename this"))
        nameError(n, parent.list()?.toList().orEmpty())?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        val target = File(parent, n)
        if (file.renameTo(target)) Result.success(target) else Result.failure(IllegalStateException("Could not rename"))
    }

    /** Deletes a file, or a folder with everything in it (links are removed, never followed). */
    suspend fun delete(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        val ok = if (SafeFiles.isRealDirectory(file)) SafeFiles.deleteTree(file) else file.delete()
        if (ok) Result.success(Unit) else Result.failure(IllegalStateException("Some files could not be deleted"))
    }

    /** The checked-out branch from `.git/HEAD`, or a short commit id when detached; null outside a repository. */
    suspend fun branch(root: File): String? = withContext(Dispatchers.IO) {
        val head = File(root, ".git/HEAD").takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() } ?: return@withContext null
        if (head.startsWith("ref: ")) head.removePrefix("ref: ").removePrefix("refs/heads/") else head.take(7)
    }

    /** "812 B", "4.2 KB", "13 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0).replace(".0 ", " ")
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024)).replace(".0 ", " ")
        else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024)).replace(".0 ", " ")
    }
}

/**
 * One entry of the Files tab, an [InsetRow]: a glyph for the type in the leading slot (folder ink2; code, document,
 * image or other ink3), the name (dot files dimmed), its size (files) or item count (folders, read when shown) at the
 * end, and a chevron for folders. Long press opens the file actions; TalkBack has them as a named action.
 */
@Composable
fun FileRow(entry: FileEntry, parent: File, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    val colors = Pi.colors
    val count by produceState<Int?>(null, entry, parent) { if (entry.dir) value = ProjectFiles.count(File(parent, entry.name)) }
    val kind = if (entry.dir) null else FileTypes.kind(entry.name)
    val meta = if (entry.dir) count?.let { if (it == 1) "1 item" else "$it items" }.orEmpty() else ProjectFiles.size(entry.size)
    InsetRow(
        title = entry.name,
        onClick = onClick,
        onLongClick = onLongClick,
        label = if (entry.dir) "Open folder" else "Open file",
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = (if (entry.dir) "Folder ${entry.name}" else "${entry.name}, ${FileTypes.label(entry.name)}") + if (meta.isNotEmpty()) ", $meta" else ""
            // Long press as a named action, so TalkBack users find Rename, Share and Delete.
            customActions = listOf(CustomAccessibilityAction(if (entry.dir) "Folder actions" else "File actions") { onLongClick(); true })
        },
        // A dot file reads one step quieter, about ink3 (DESIGN.md "Contrast").
        titleModifier = if (entry.dot) Modifier.alpha(DOT_FILE_ALPHA) else Modifier,
        leading = {
            Icon(
                when (kind) {
                    null -> Icons.Rounded.Folder
                    FileKind.Code -> Icons.Rounded.Code
                    FileKind.Markdown, FileKind.Text -> Icons.Rounded.Description
                    FileKind.Image -> Icons.Rounded.Image
                    FileKind.Binary -> Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = if (entry.dir) colors.ink2 else colors.ink3,
                modifier = Modifier.size(IconSize.md),
            )
        },
        trailing = { if (meta.isNotEmpty()) Text(meta, style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1) },
        chevron = entry.dir,
        divider = divider,
    )
}

/** The alpha of a dot file's name: ink1 at 62% lands on ink3 over the canvas. */
const val DOT_FILE_ALPHA = 0.62f
