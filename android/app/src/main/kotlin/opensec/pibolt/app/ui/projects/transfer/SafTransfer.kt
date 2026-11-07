package opensec.pibolt.app.ui.projects.transfer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.ui.session.features.ShareFiles
import java.io.File
import java.io.IOException

/**
 * Files in and out through Android's Storage Access Framework: documents and folder trees picked with
 * ACTION_OPEN_DOCUMENT / ACTION_OPEN_DOCUMENT_TREE are copied into a project, zips become projects, and a project or
 * folder is packed into a document picked with ACTION_CREATE_DOCUMENT or into the share sheet. Everything streams
 * through [ZipTransfer] (one buffer at a time) on [Dispatchers.IO]; cancelling the coroutine stops it, and the half
 * written file (or new project folder, or created document) is removed.
 */
object SafTransfer {
    /** One document of a picked tree: its URI, its path below the tree's root, and its size (folders end with `/`). */
    private class TreeEntry(val uri: Uri, val rel: String, val size: Long)

    fun displayName(context: Context, uri: Uri): String? = query(context, uri) { c ->
        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
    }

    fun size(context: Context, uri: Uri): Long? = query(context, uri) { c ->
        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
    }

    private fun <T> query(context: Context, uri: Uri, read: (Cursor) -> T?): T? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) read(c) else null }
    }.getOrNull()

    /**
     * Copies the documents at [uris] into [folder], each under its display name (made unique: `a (2).txt`). Returns
     * the names written.
     */
    suspend fun importDocuments(context: Context, uris: List<Uri>, folder: File, onProgress: (TransferProgress) -> Unit): List<String> = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val sizes = uris.map { size(context, it) }
        val total = if (sizes.all { it != null }) sizes.sumOf { it ?: 0 } else null
        var done = 0L
        val written = ArrayList<String>()
        for (uri in uris) {
            val name = ZipTransfer.uniqueName(ZipTransfer.safeName(displayName(context, uri)) ?: "file") { File(folder, it).exists() }
            val base = done
            copyInto(context, uri, File(folder, name)) { n -> onProgress(TransferProgress(base + n, total, written.size, name)) }.also { done += it }
            written += name
            onProgress(TransferProgress(done, total, written.size, name))
        }
        written
    }

    /** Copies the folder tree picked as [treeUri] into [folder] as a new folder (its name made unique). Returns its name. */
    suspend fun importTree(context: Context, treeUri: Uri, folder: File, onProgress: (TransferProgress) -> Unit): String = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val rootName = ZipTransfer.safeName(displayName(context, rootUri)) ?: "Imported"
        val entries = ArrayList<TreeEntry>()
        list(context, treeUri, rootId, "", entries)
        val total = entries.sumOf { it.size }
        val name = ZipTransfer.uniqueName(rootName) { File(folder, it).exists() }
        val target = File(folder, name)
        try {
            target.mkdirs()
            var done = 0L
            var files = 0
            for (e in entries) {
                val out = ZipTransfer.resolve(target, e.rel.trimEnd('/'), e.rel)
                if (e.rel.endsWith("/")) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                val base = done
                done += copyInto(context, e.uri, out) { n -> onProgress(TransferProgress(base + n, total, files, e.rel)) }
                files++
                onProgress(TransferProgress(done, total, files, e.rel))
            }
        } catch (e: Throwable) {
            SafeFiles.deleteTree(target)
            throw e
        }
        name
    }

    /** Every document under [parentId], depth first, paths checked like zip entries. */
    private suspend fun list(context: Context, treeUri: Uri, parentId: String, prefix: String, out: MutableList<TreeEntry>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val found = ArrayList<Triple<String, String, Pair<Boolean, Long>>>()
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = ZipTransfer.safeName(c.getString(1)) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                found += Triple(id, name, dir to size)
            }
        }
        for ((id, name, info) in found.sortedBy { it.second }) {
            currentCoroutineContext().ensureActive()
            val rel = prefix + name
            ZipTransfer.safePath(rel) ?: throw UnsafeEntryException(rel)
            if (out.size >= ZipTransfer.MAX_ENTRIES) throw ArchiveTooLargeException("More than ${ZipTransfer.MAX_ENTRIES} files")
            if (info.first) {
                out += TreeEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), "$rel/", 0)
                list(context, treeUri, id, "$rel/", out)
            } else {
                out += TreeEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), rel, info.second)
            }
        }
    }

    /** Streams [uri] into [out] through a part file renamed at the end; the part file is removed on failure. */
    private suspend fun copyInto(context: Context, uri: Uri, out: File, onBytes: (Long) -> Unit): Long {
        val part = File(out.parentFile, ".${out.name}.pibolt-part")
        try {
            val n = (context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open ${out.name}")).use { input ->
                part.outputStream().use { os -> ZipTransfer.copy(input, os, onBytes) }
            }
            if (!part.renameTo(out)) throw IOException("Could not write ${out.name}")
            return n
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /**
     * Unpacks the zip at [uri] into a new folder under [projectsDir] (named after the archive, made unique; a single
     * top folder in the zip moves up). Returns the project's folder name. The folder is removed if anything fails.
     */
    suspend fun importZipAsProject(context: Context, uri: Uri, projectsDir: File, onProgress: (TransferProgress) -> Unit): String = withContext(Dispatchers.IO) {
        val name = ZipTransfer.uniqueName(ZipTransfer.projectName(displayName(context, uri))) { File(projectsDir, it).exists() || it.startsWith(".") }
        val dir = File(projectsDir, name)
        projectsDir.mkdirs()
        if (!dir.mkdir()) throw IOException("Could not create the project folder")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the archive")
            input.use { ZipTransfer.unzip(it, dir, size(context, uri), onProgress) }
            ZipTransfer.hoistSingleFolder(dir)
        } catch (e: Throwable) {
            SafeFiles.deleteTree(dir)
            throw e
        }
        name
    }

    /** Packs [source] into the document [dest] (from ACTION_CREATE_DOCUMENT); the document is deleted on failure. */
    suspend fun exportZip(context: Context, source: File, dest: Uri, prefix: String, skip: Set<String>, onProgress: (TransferProgress) -> Unit): Int = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(dest, "wt") ?: throw IOException("Could not write the archive")
            out.use { ZipTransfer.zip(source, it, prefix, skip, onProgress) }
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, dest) }
            throw e
        }
    }

    /** Packs [source] into `cache/exports/[name]` and opens the share sheet for it. */
    suspend fun shareZip(context: Context, source: File, name: String, prefix: String, skip: Set<String>, onProgress: (TransferProgress) -> Unit) {
        val file = File(ShareFiles.exportsDir(context), name)
        withContext(Dispatchers.IO) {
            try {
                file.outputStream().use { ZipTransfer.zip(source, it, prefix, skip, onProgress) }
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
        ShareFiles.share(context, file, "application/zip", name)
    }
}
