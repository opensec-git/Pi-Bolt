package opensec.pibolt.app.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** `assets/engine/manifest.json`, written by the pack build (android/engine/CONTRACT.md). */
data class EngineManifest(
    val version: Int,
    val engine: String,
    val glibc: String,
    val prefix: String,
    val links: List<Link>,
    val files: List<Asset>,
    val packFingerprint: String,
    /** Every file under assets `engine/package/` (relative), so the copy needs no slow recursive asset listing. */
    val packageFiles: List<String>? = null,
    /** Environment the pack wants on top of the app's defaults (PREFIX already expanded). */
    val env: Map<String, String> = emptyMap(),
) {
    /** `PREFIX/<path>` is a symlink to `nativeLibraryDir/<target>`. */
    data class Link(val path: String, val target: String)

    /** `PREFIX/<path>` is a copy of the APK asset [asset]. */
    data class Asset(val path: String, val asset: String)

    companion object {
        fun parse(text: String): EngineManifest {
            val json = ProtocolJson.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("manifest is not an object")
            fun JsonObject.need(key: String) = str(key) ?: throw IllegalArgumentException("manifest: $key missing")
            return EngineManifest(
                version = json.int("version") ?: 1,
                engine = json.need("engine"),
                glibc = json.str("glibc").orEmpty(),
                prefix = json.need("prefix"),
                links = json.arr("links").orEmpty().map { item ->
                    val o = item as JsonObject
                    Link(safeRelative(o.need("path")), safeName(o.need("target")))
                },
                files = json.arr("files").orEmpty().map { item ->
                    val o = item as JsonObject
                    Asset(safeRelative(o.need("path")), o.need("asset"))
                },
                packFingerprint = json.need("packFingerprint"),
                packageFiles = json.arr("package")?.map { safeRelative((it as JsonPrimitive).content) },
                env = json.obj("env").orEmpty().mapNotNull { (k, v) ->
                    // The loader must find glibc by its compiled-in prefix only.
                    if (k.startsWith("LD_")) null else (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content }
                }.toMap(),
            )
        }

        /** A path inside the prefix: relative, no `..`, no empty parts. */
        private fun safeRelative(path: String): String {
            val parts = path.split('/')
            require(path.isNotEmpty() && !path.startsWith("/") && parts.none { it.isEmpty() || it == "." || it == ".." }) { "manifest: bad path $path" }
            return path
        }

        /** A file name in nativeLibraryDir. */
        private fun safeName(name: String): String {
            require(name.isNotEmpty() && '/' !in name && name != "." && name != "..") { "manifest: bad target $name" }
            return name
        }
    }
}

/** Read access to the APK's assets (an AssetManager in the app, a directory in tests). */
interface AssetSource {
    fun open(path: String): InputStream

    /** Entries of a directory, or empty when [path] is a file or absent. */
    fun list(path: String): List<String>
}

/** Where everything lives (android/engine/CONTRACT.md, Identity and paths). */
class EnginePaths(
    /** `/data/data/<applicationId>/files/usr`, as compiled into glibc and the engine's PT_INTERP. */
    val prefix: File,
    /** This user's app data dir (`ApplicationInfo.dataDir`). */
    val dataDir: File,
    val nativeLibDir: File,
) {
    val home = File(dataDir, "files/home")
    val projects = File(home, "projects")
    val agentDir = File(home, ".pi/agent")
    val tmp = File(prefix, "tmp")

    /** XDG_CACHE_HOME: the app's cache dir, which the system may clear when storage runs low. */
    val cache = File(dataDir, "cache/xdg")
    val etc = File(prefix, "etc")
    val bin = File(prefix, "bin")

    /** PI_PACKAGE_DIR: theme/, export-html/, assets/, package.json from APK assets `engine/package/`. */
    val packageDir = File(prefix, "share/pi-bolt")
    val engine = File(nativeLibDir, "libpibolt.so")
    val stamp = File(prefix, ".pack")

    companion object {
        /** Deliberately user 0's literal path: it is compiled into glibc and PT_INTERP (multi-user is checked apart). */
        @Suppress("SdCardPath")
        fun prefixFor(applicationId: String) = File("/data/data/$applicationId/files/usr")
    }
}

/** Creates and refreshes the prefix: the symlink farm, data files and the engine's side files. */
class EnginePrefix(private val paths: EnginePaths, private val assets: AssetSource) {
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
            for (file in listed) assets.open("$PACKAGE_ASSETS/$file").use { copyAtomically(it, File(paths.packageDir, file)) }
        } else {
            copyTree(PACKAGE_ASSETS, paths.packageDir)
        }
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
        const val SYSTEM_SH = "/system/bin/sh"

        fun writeAtomically(to: File, text: String) {
            to.parentFile?.mkdirs()
            val temp = File(to.parentFile, ".${to.name}.tmp")
            temp.writeText(text)
            Files.move(temp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

/**
 * Why this device cannot run the engine, or null when it can. Checked once per process, off the main thread.
 *
 * - [abis]: the engine is linux-arm64 only.
 * - [sdk]: only API 29+ is supported (the spike ran API 36; below 29 nothing is proven).
 * - the pack: the manifest and `libpibolt.so` must be in this build.
 * - the prefix: glibc's paths name `/data/data/<applicationId>`, which is user 0's data. In another user or a work
 *   profile, the app's real data dir differs ([dataDirReal] vs [prefixDataReal]), so the engine could not find its loader.
 */
fun engineUnavailableReason(
    applicationId: String,
    abis: List<String>,
    sdk: Int,
    manifest: EngineManifest?,
    engineShipped: Boolean,
    dataDirReal: String,
    prefixDataReal: String?,
): String? = when {
    "arm64-v8a" !in abis -> "The on-device engine needs a 64-bit ARM phone. Remote control works here."
    sdk < MIN_ENGINE_SDK -> "The on-device engine needs Android 10 or newer. Remote control works here."
    manifest == null || !engineShipped -> "This build of the app does not include the engine."
    manifest.prefix != EnginePaths.prefixFor(applicationId).path ->
        "This build's engine is made for another app id (${manifest.prefix}). Install the release build."
    prefixDataReal != dataDirReal ->
        "The on-device engine runs only in the phone owner's main profile. Remote control works here."
    else -> null
}

const val MIN_ENGINE_SDK = 29
