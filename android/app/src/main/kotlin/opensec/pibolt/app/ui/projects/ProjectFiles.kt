package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import java.io.File
import java.util.Locale
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** One directory entry: one stat (type and size together), taken off the main thread. */
@Immutable
data class FileEntry(val name: String, val dir: Boolean, val size: Long)

/**
 * The project's files, one directory at a time. Listing and stat run on [Dispatchers.IO] (10k entries is one pass of
 * `readAttributes`); a directory's item count is read only when its row is on screen; file contents load only when
 * opened, capped at [MAX_VIEW] bytes. Git status needs the engine's git and is not computed here; the branch comes
 * from `.git/HEAD` when the header asks for it.
 */
object ProjectFiles {
    const val MAX_VIEW = 512 * 1024

    suspend fun list(dir: File): List<FileEntry> = withContext(Dispatchers.IO) {
        val names = dir.list() ?: return@withContext emptyList()
        order(
            names.map { name ->
                val attrs = runCatching { Files.readAttributes(File(dir, name).toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                FileEntry(name, attrs?.isDirectory == true, attrs?.size() ?: 0)
            },
        )
    }

    /** Folders first, then files; each by name, case-insensitive. */
    fun order(entries: List<FileEntry>): List<FileEntry> = entries.sortedWith(compareByDescending<FileEntry> { it.dir }.thenBy { it.name.lowercase() }.thenBy { it.name })

    suspend fun count(dir: File): Int = withContext(Dispatchers.IO) { dir.list()?.size ?: 0 }

    /** The file for the viewer: text (capped), or a short note for binary or unreadable files. */
    suspend fun view(file: File, relative: String): ViewerContent = withContext(Dispatchers.IO) {
        val size = file.length()
        val bytes = runCatching { file.inputStream().use { it.readNBytesCompat(MAX_VIEW) } }.getOrNull()
        val text = when {
            bytes == null -> "This file can't be read."
            bytes.take(8_000).any { it == 0.toByte() } -> "Binary file, ${size(size)}."
            size > MAX_VIEW -> String(bytes, Charsets.UTF_8) + "\n\n… the first ${size(MAX_VIEW.toLong())} of ${size(size)}"
            else -> String(bytes, Charsets.UTF_8)
        }
        ViewerContent(file.name, relative, text)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var left = n
        while (left > 0) {
            val r = read(buf, 0, minOf(buf.size, left))
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
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

/** 52dp row: folder or file glyph, the name, and its size (files) or item count (folders, read when shown). */
@Composable
fun FileRow(entry: FileEntry, parent: File, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val count by produceState<Int?>(null, entry, parent) { if (entry.dir) value = ProjectFiles.count(File(parent, entry.name)) }
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (entry.dir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.dir) colors.ink2 else colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = type.monoSmall, color = if (entry.name.startsWith(".")) colors.ink3 else colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Spacer(Modifier.width(12.dp))
        val meta = if (entry.dir) count?.let { if (it == 1) "1 item" else "$it items" }.orEmpty() else ProjectFiles.size(entry.size)
        Text(meta, style = type.meta, color = colors.ink3, maxLines = 1)
    }
}
