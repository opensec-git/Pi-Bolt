package opensec.pibolt.app.engine

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import opensec.pibolt.app.data.SafeFiles

/** The Runtimes screen's data: the runtimes in this build's pack, and the space their package managers use. */
data class RuntimesReport(val runtimes: List<EngineManifest.Runtime>, val storage: List<RuntimeStorage>)

/** Space one kind of package-manager data takes; [clearable] when it is a cache that is safe to delete. */
data class RuntimeStorage(val kind: Kind, val bytes: Long) {
    enum class Kind(val label: String, val detail: String, val clearable: Boolean) {
        NpmCache("npm cache", "Safe to clear", true),
        PipCache("pip cache", "Safe to clear", true),
        NpmGlobal("npm global packages", "npm install -g", false),
        PipUser("pip packages", "pip install --user", false),
    }

    val clearable: Boolean get() = kind.clearable
}

/** Where npm and pip keep their data (EngineEnvironment.kt sets the same paths), and their sizes. Blocking file I/O. */
object RuntimeStore {
    fun dirs(paths: EnginePaths, kind: RuntimeStorage.Kind): List<File> = when (kind) {
        RuntimeStorage.Kind.NpmCache -> listOf(File(paths.cache, "npm"))
        RuntimeStorage.Kind.PipCache -> listOf(File(paths.cache, "pip"))
        RuntimeStorage.Kind.NpmGlobal -> listOf(File(paths.userBase, "lib/node_modules"))
        RuntimeStorage.Kind.PipUser -> File(paths.userBase, "lib").listFiles { f -> f.name.startsWith("python") && SafeFiles.isRealDirectory(f) }?.sortedBy { it.name }.orEmpty()
    }

    /** Sizes of what exists; kinds of a runtime the pack does not ship are left out. */
    fun usage(paths: EnginePaths, runtimes: List<EngineManifest.Runtime>): List<RuntimeStorage> {
        val ids = runtimes.map { it.id }.toSet()
        return RuntimeStorage.Kind.entries
            .filter { if (it == RuntimeStorage.Kind.NpmCache || it == RuntimeStorage.Kind.NpmGlobal) "node" in ids else "python" in ids }
            .map { kind -> RuntimeStorage(kind, dirs(paths, kind).sumOf(::sizeOf)) }
    }

    /** Deletes a cache. False when something could not be deleted (in use by a running install, for example). */
    fun clear(paths: EnginePaths, kind: RuntimeStorage.Kind): Boolean {
        require(kind.clearable) { "${kind.label} is not a cache" }
        return dirs(paths, kind).all(SafeFiles::deleteTree)
    }

    /** Bytes of the regular files under [root]; links are not followed or counted. */
    fun sizeOf(root: File): Long {
        val start = root.toPath()
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return 0
        var total = 0L
        try {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
        }
        return total
    }
}

/** "128 MB", "4.2 MB", "860 KB", "0 B": sizes on the Runtimes screen (decimal units, like Android's storage screens). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(Locale.ROOT, bytes / 1e9)
    bytes >= 100_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1e6)
    bytes >= 1_000 -> "${(bytes + 500) / 1_000} KB"
    else -> "$bytes B"
}
