package opensec.pibolt.app.data

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * File operations on project folders that never follow symbolic links. Kotlin's `deleteRecursively` and `walkTopDown`
 * descend into a link to a directory, so a link in a cloned repository (`x -> ../..`) would make deleting one project
 * delete other projects, the sessions and the keys. Here a link is removed or counted as itself, never its target.
 */
object SafeFiles {
    fun isRealDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Deletes [root] and everything under it; true when nothing is left. */
    fun deleteTree(root: File): Boolean {
        val start = root.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Regular files under [root], not counting [skipDir] folders, stopping at [cap]. */
    fun countFiles(root: File, cap: Int, skipDir: String): Int {
        var count = 0
        try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir.fileName?.toString() == skipDir) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) count++
                        return if (count >= cap) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        } catch (e: IOException) {
            // A folder that went away while counting: report what was counted.
        }
        return count
    }
}
