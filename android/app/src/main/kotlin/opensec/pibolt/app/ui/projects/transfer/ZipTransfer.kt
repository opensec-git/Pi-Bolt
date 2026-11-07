package opensec.pibolt.app.ui.projects.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** How far a transfer is: [bytes] of [total] (null when unknown), [files] done, [current] the file being copied. */
data class TransferProgress(val bytes: Long, val total: Long?, val files: Int, val current: String = "") {
    /** 0..1, or null when the total is unknown. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** A zip entry that would land outside the target folder ("zip slip"), or is otherwise not a usable path. */
class UnsafeEntryException(val entry: String) : IOException("Unsafe path in the archive: $entry")

/** The archive is larger than [ZipTransfer.MAX_TOTAL] unpacked or has more than [ZipTransfer.MAX_ENTRIES] entries. */
class ArchiveTooLargeException(message: String) : IOException(message)

/**
 * Zip import and export, streamed (pure JVM, unit-tested): one buffer at a time, never a whole file in memory, with
 * progress after every buffer and cancellation checked there too (cancel the coroutine). Entry names are checked
 * before anything is written: absolute paths, `..` segments, drive letters and NULs are refused, and the resolved
 * path must stay inside the target ([UnsafeEntryException]); one bad entry fails the whole import. Unpacked size and
 * entry count are capped against zip bombs. Symbolic links are never followed when packing.
 */
object ZipTransfer {
    const val BUFFER = 64 * 1024
    const val MAX_ENTRIES = 100_000
    const val MAX_TOTAL = 4L * 1024 * 1024 * 1024
    const val MAX_SUFFIX = 9_999

    /** Folders left out of an export when "Leave out dependencies and .git" is on. */
    val HEAVY = setOf(".git", "node_modules", ".gradle", "__pycache__", ".venv", "venv", ".next", ".cache")

    /**
     * [name] as a safe relative path with `/` separators (no leading `./`, no trailing `/`), or null when it would
     * escape the target: absolute, a drive letter, a `..` segment, a NUL or control character, or empty.
     */
    fun safePath(name: String): String? {
        if (name.isEmpty() || name.length > 4096) return null
        if (name.any { it == '\u0000' || it.isISOControl() }) return null
        val n = name.replace('\\', '/')
        if (n.startsWith("/")) return null
        if (n.length >= 2 && n[1] == ':' && n[0].isLetter()) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.length > 255 }) return null
        return parts.joinToString("/")
    }

    /** The file for [rel] under [root], or [UnsafeEntryException] when its canonical path is not inside [root]. */
    fun resolve(root: File, rel: String, entry: String = rel): File {
        val base = root.canonicalFile
        val file = File(base, rel)
        val canonical = file.canonicalFile
        if (canonical != base && !canonical.path.startsWith(base.path + File.separator)) throw UnsafeEntryException(entry)
        return file
    }

    /**
     * Unpacks the zip read from [input] into [target] (created when missing). [size] is the archive's size when known,
     * for progress. Returns the number of files written. On any failure or cancellation, the files written so far stay;
     * callers that create [target] for the import delete it.
     */
    suspend fun unzip(input: InputStream, target: File, size: Long? = null, onProgress: (TransferProgress) -> Unit = {}): Int {
        target.mkdirs()
        val counted = CountingInput(input)
        var files = 0
        var entries = 0
        var unpacked = 0L
        val buf = ByteArray(BUFFER)
        ZipInputStream(counted).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                currentCoroutineContext().ensureActive()
                if (++entries > MAX_ENTRIES) throw ArchiveTooLargeException("More than $MAX_ENTRIES entries")
                val rel = safePath(entry.name) ?: throw UnsafeEntryException(entry.name)
                val out = resolve(target, rel, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                // A file where a folder of the same name was (or the reverse) can't be written: the archive is broken.
                if (out.isDirectory) throw IOException("Both a file and a folder are named $rel")
                out.outputStream().use { os ->
                    while (true) {
                        val r = zip.read(buf)
                        if (r < 0) break
                        unpacked += r
                        if (unpacked > MAX_TOTAL) throw ArchiveTooLargeException("More than ${MAX_TOTAL / (1024 * 1024 * 1024)} GB unpacked")
                        os.write(buf, 0, r)
                        onProgress(TransferProgress(counted.count, size, files, rel))
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (entry.time > 0) out.setLastModified(entry.time)
                files++
                onProgress(TransferProgress(counted.count, size, files, rel))
            }
        }
        return files
    }

    /** Files (not links) under [root] with their relative paths, folders before their contents, [skip] names left out. */
    fun walk(root: File, skip: Set<String> = emptySet()): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        fun visit(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (child.name in skip) continue
                if (Files.isSymbolicLink(child.toPath())) continue
                val rel = prefix + child.name
                if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    out += child to "$rel/"
                    visit(child, "$rel/")
                } else if (Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    out += child to rel
                }
            }
        }
        if (root.isDirectory) visit(root, "") else if (root.isFile) out += root to root.name
        return out
    }

    /**
     * Packs [root] (a folder's contents, or one file) into a zip written to [output], entries relative to [root] and
     * prefixed with [prefix] (e.g. the project name and `/`). Returns the number of files packed.
     */
    suspend fun zip(root: File, output: OutputStream, prefix: String = "", skip: Set<String> = emptySet(), onProgress: (TransferProgress) -> Unit = {}): Int {
        val items = walk(root, skip)
        val total = items.sumOf { (f, rel) -> if (rel.endsWith("/")) 0L else f.length() }
        var bytes = 0L
        var files = 0
        val buf = ByteArray(BUFFER)
        val base = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        ZipOutputStream(output.buffered(BUFFER)).use { zip ->
            if (base.isNotEmpty()) zip.putNextEntry(ZipEntry(base)).also { zip.closeEntry() }
            for ((file, rel) in items) {
                currentCoroutineContext().ensureActive()
                val entry = ZipEntry(base + rel).apply { time = file.lastModified() }
                zip.putNextEntry(entry)
                if (!rel.endsWith("/")) {
                    file.inputStream().use { input ->
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            zip.write(buf, 0, r)
                            bytes += r
                            onProgress(TransferProgress(bytes, total, files, rel))
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    files++
                }
                zip.closeEntry()
                onProgress(TransferProgress(bytes, total, files, rel))
            }
        }
        return files
    }

    /** Copies [input] to [out], a buffer at a time, with progress and cancellation; returns the bytes copied. */
    suspend fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}): Long {
        val buf = ByteArray(BUFFER)
        var n = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            n += r
            onBytes(n)
        }
        return n
    }

    /**
     * After unpacking a new project into [dir]: when everything sits in one top folder (`repo-main/...`, as GitHub
     * zips do), its contents move up one level. Returns that folder's name, or null when nothing moved.
     */
    fun hoistSingleFolder(dir: File): String? {
        val children = dir.listFiles() ?: return null
        val only = children.singleOrNull() ?: return null
        if (!Files.isDirectory(only.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val name = only.name
        // Move through a temporary name, in case the folder holds an entry named like itself.
        val tmp = File(dir, ".pibolt-hoist-${System.nanoTime()}")
        if (!only.renameTo(tmp)) return null
        tmp.listFiles()?.forEach { child -> if (!child.renameTo(File(dir, child.name))) throw IOException("Could not move ${child.name}") }
        tmp.delete()
        return name
    }

    /** A project name from an archive's display name: `calc-parser-main.zip` gives `calc-parser-main`. */
    fun projectName(displayName: String?): String {
        // A hidden folder would not show as a project (and no numbered name could fix that): no leading dots.
        val base = displayName.orEmpty().substringAfterLast('/').removeSuffix(".zip").removeSuffix(".ZIP").trim().trimStart('.')
        return safeName(base) ?: "Imported project"
    }

    /** [name] usable as one file or folder name (no slashes, controls, `.` or `..`), or null. */
    fun safeName(name: String?): String? {
        val n = name.orEmpty().trim().filterNot { it == '\u0000' || it.isISOControl() }.replace('/', '_').replace('\\', '_').take(255)
        return n.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** [name], or `name (2).ext`, `name (3).ext`, … — the first that [exists] says is free. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        for (i in 2..MAX_SUFFIX) {
            val candidate = "$stem ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        throw IOException("Could not pick a free name for $name")
    }

    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
