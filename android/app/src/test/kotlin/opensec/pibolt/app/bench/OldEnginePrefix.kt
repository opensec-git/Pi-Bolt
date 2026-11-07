package opensec.pibolt.app.bench

import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.engine.AssetSource
import opensec.pibolt.app.engine.BundledExtensions
import opensec.pibolt.app.engine.EngineManifest
import opensec.pibolt.app.engine.EnginePaths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipInputStream

/**
 * EnginePrefix as Android 0.6.0 ships it (one thread, the zips streamed, everything unpacked again on every pack
 * change), kept here unchanged for [PrefixBench]'s "before" numbers.
 */
class OldEnginePrefix(private val paths: EnginePaths, private val assets: AssetSource) {
    /**
     * Brings the prefix up to date with [manifest]. Cheap when nothing changed (one small file read); after an install or
     * update it recreates the links and copies files. Returns true when it did the full setup.
     */
    fun ensure(manifest: EngineManifest): Boolean {
        for (dir in listOf(paths.tmp, paths.etc, paths.bin, paths.projects)) dir.mkdirs()
        val stamp = stampFor(manifest)
        if (paths.stamp.isFile && runCatching { paths.stamp.readText() }.getOrNull() == stamp) return false
        install(manifest)
        writeAtomically(paths.stamp, stamp)
        return true
    }

    private fun stampFor(manifest: EngineManifest) = "${manifest.packFingerprint}\n${paths.nativeLibDir.path}\n"

    private fun install(manifest: EngineManifest) {
        paths.stamp.delete()
        removeLinks(paths.prefix.toPath())
        // Trees first: the links of their extension modules go inside them.
        for (tree in manifest.trees) installTree(tree)
        for (link in manifest.links) {
            val at = File(paths.prefix, link.path).toPath()
            Files.createDirectories(at.parent)
            Files.deleteIfExists(at)
            Files.createSymbolicLink(at, File(paths.nativeLibDir, link.target).toPath())
        }
        // glibc's system()/popen() run PREFIX/bin/sh: the toybox shell until the userland pack ships one.
        val sh = File(paths.bin, "sh").toPath()
        if (!Files.exists(sh, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(sh, File(SYSTEM_SH).toPath())
        for (file in manifest.files) {
            assets.open(file.asset).use { input -> copyAtomically(input, File(paths.prefix, file.path)) }
        }
        paths.packageDir.deleteRecursively()
        val listed = manifest.packageFiles
        if (listed != null) {
            for (file in listed) {
                // The APK build drops dot files (an example's .gitignore): nothing the engine reads.
                val input = try {
                    assets.open("$PACKAGE_ASSETS/$file")
                } catch (e: FileNotFoundException) {
                    continue
                }
                input.use { copyAtomically(it, File(paths.packageDir, file)) }
            }
        } else {
            copyTree(PACKAGE_ASSETS, paths.packageDir)
        }
        paths.extensionsDir.deleteRecursively()
        for (extension in manifest.extensions) {
            val dir = File(paths.extensionsDir, extension.name)
            for (file in extension.files) {
                assets.open("$EXTENSIONS_ASSETS/${extension.name}/$file").use { copyAtomically(it, File(dir, file)) }
            }
            // Its runtime dependencies (the extensions dir was emptied above, so nothing older is left in it).
            extension.modules?.let { unzip(it, File(dir, "node_modules")) }
        }
        // Not fatal: without the entries Pi just runs without the extensions (the next pack update tries again).
        BundledExtensions.register(paths.agentDir, manifest.extensions.map { File(paths.extensionsDir, it.name).path }, paths.extensionsDir.path)
    }

    /** Every symlink under the prefix (not tmp/): the links of a previous pack, which may point at a moved directory. */
    private fun removeLinks(root: Path) {
        if (!Files.isDirectory(root)) return
        val tmp = paths.tmp.toPath()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir == tmp) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }

    /**
     * Unpacks [tree] next to its place and swaps it in, so a crash never leaves half a tree behind (the stamp is
     * written only after every step). What was there before (an older version, written bytecode caches) goes.
     */
    private fun installTree(tree: EngineManifest.Tree) {
        val dir = File(paths.prefix, tree.path)
        val temp = File(dir.parentFile, ".${dir.name}.unpack")
        SafeFiles.deleteTree(temp)
        unzip(tree.asset, temp)
        for (rel in tree.executables) File(temp, rel).setExecutable(true, true)
        if (!SafeFiles.deleteTree(dir)) throw IOException("cannot remove the old ${tree.path}")
        dir.parentFile?.mkdirs()
        Files.move(temp.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** Unpacks the zip asset [asset] into [into]. */
    private fun unzip(asset: String, into: File) {
        into.mkdirs()
        assets.open(asset).use { raw ->
            ZipInputStream(raw.buffered(64 * 1024)).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Entries are paths inside the tree (no `..`, not absolute): a bad pack cannot write elsewhere.
                    val name = EngineManifest.safeRelative(entry.name.trimEnd('/'))
                    val out = File(into, name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            o.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    }

    private fun copyTree(asset: String, to: File) {
        val children = assets.list(asset)
        if (children.isEmpty()) {
            runCatching { assets.open(asset) }.getOrNull()?.use { copyAtomically(it, to) }
            return
        }
        to.mkdirs()
        for (child in children) copyTree("$asset/$child", File(to, child))
    }

    private fun copyAtomically(input: InputStream, to: File) {
        to.parentFile?.mkdirs()
        val temp = File(to.parentFile, ".${to.name}.tmp")
        temp.outputStream().use { input.copyTo(it, 64 * 1024) }
        Files.move(temp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val MANIFEST_ASSET = "engine/manifest.json"
        const val PACKAGE_ASSETS = "engine/package"
        const val EXTENSIONS_ASSETS = "engine/extensions"
        const val SYSTEM_SH = "/system/bin/sh"

        fun writeAtomically(to: File, text: String) {
            to.parentFile?.mkdirs()
            val temp = File(to.parentFile, ".${to.name}.tmp")
            temp.writeText(text)
            Files.move(temp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

