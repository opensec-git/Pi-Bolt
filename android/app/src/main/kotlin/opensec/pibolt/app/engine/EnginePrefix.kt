package opensec.pibolt.app.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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
    /** Pi extensions the pack bundles, each with every file under assets `engine/extensions/<name>/` (relative). */
    val extensions: List<Extension> = emptyList(),
    /** Directory trees shipped as one zip asset each (the runtimes' libraries: npm, the Python stdlib). */
    val trees: List<Tree> = emptyList(),
    /** The language runtimes in this pack, for the Runtimes screen. */
    val runtimes: List<Runtime> = emptyList(),
) {
    /** `PREFIX/<path>` is a symlink to `nativeLibraryDir/<target>`. */
    data class Link(val path: String, val target: String)

    /** `PREFIX/<path>` is a copy of the APK asset [asset]. */
    data class Asset(val path: String, val asset: String)

    /**
     * A bundled Pi extension: assets `engine/extensions/<name>/<files>`, plus for one with runtime dependencies (the
     * third-party ones, android/engine/npm) its node_modules tree as the zip asset [modules], unpacked into
     * `<name>/node_modules`. [name] is a directory name; [packageName] the npm name ("@scope/pkg" ships as "scope-pkg").
     */
    data class Extension(
        val name: String,
        val version: String,
        val files: List<String>,
        val modules: String? = null,
        val packageName: String = name,
        /** The [modules] zip's sha256, when the pack lists it: an unchanged node_modules tree is kept on an update. */
        val modulesSha256: String? = null,
    )

    /**
     * `PREFIX/<path>` is the zip asset [asset] unpacked; [executables] (relative to it) get the execute bit. With its
     * [sha256] listed, an update to a pack with the same tree keeps the unpacked one.
     */
    data class Tree(val path: String, val asset: String, val executables: List<String>, val sha256: String? = null)

    /**
     * A language runtime: [commands] on PATH, [bytes] its installed size (executables, modules, libraries only it
     * uses, its library tree), [details] the versions of what comes with it (npm, pip).
     */
    data class Runtime(val id: String, val name: String, val version: String, val commands: List<String>, val bytes: Long, val details: Map<String, String>)

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
                trees = json.arr("trees").orEmpty().map { item ->
                    val o = item as JsonObject
                    Tree(
                        path = safeRelative(o.need("path")),
                        asset = o.need("asset"),
                        executables = o.arr("executables").orEmpty().map { safeRelative((it as JsonPrimitive).content) },
                        sha256 = o.str("sha256"),
                    )
                },
                runtimes = json.arr("runtimes").orEmpty().map { item ->
                    val o = item as JsonObject
                    Runtime(
                        id = safeName(o.need("id")),
                        name = o.need("name"),
                        version = o.need("version"),
                        commands = o.arr("commands").orEmpty().map { (it as JsonPrimitive).content },
                        bytes = o.long("bytes") ?: 0L,
                        details = o.obj("details").orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap(),
                    )
                },
                extensions = json.arr("extensions").orEmpty().map { item ->
                    val o = item as JsonObject
                    Extension(
                        name = safeName(o.need("name")),
                        version = o.need("version"),
                        files = o.arr("files").orEmpty().map { safeRelative((it as JsonPrimitive).content) },
                        modules = o.obj("modules")?.need("asset"),
                        packageName = o.str("package") ?: o.need("name"),
                        modulesSha256 = o.obj("modules")?.str("sha256"),
                    )
                },
            )
        }

        /** A path inside the prefix: relative, no `..`, no empty parts. */
        internal fun safeRelative(path: String): String {
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
    /**
     * The app data dir as the kernel names it: `/data/data/<applicationId>` (CONTRACT.md `DATA`). Pi records its working
     * directory with getcwd(), which gives this form, not `ApplicationInfo.dataDir`'s `/data/user/0/...` (a link to
     * it); session directories are named after it, so every path handed to the engine uses this form.
     */
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

    /** glibc's and Python's tz database directory (ZoneInfo writes the phone's zone there). */
    val zoneinfo = File(prefix, "share/zoneinfo")

    /** `HOME/.local`: global npm packages (lib/node_modules), user pip packages (lib/pythonX.Y), their commands (bin). */
    val userBase = File(home, ".local")
    val bin = File(prefix, "bin")

    /** PI_PACKAGE_DIR: theme/, export-html/, assets/, package.json from APK assets `engine/package/`. */
    val packageDir = File(prefix, "share/pi-bolt")

    /** The pack's bundled Pi extensions, one directory each, registered as packages in Pi's settings.json. */
    val extensionsDir = File(prefix, "share/pi-extensions")
    val engine = File(nativeLibDir, "libpibolt.so")
    val stamp = File(prefix, ".pack")

    companion object {
        /** Deliberately user 0's literal path: it is compiled into glibc and PT_INTERP (multi-user is checked apart). */
        @Suppress("SdCardPath")
        fun prefixFor(applicationId: String) = File("${dataFor(applicationId)}/files/usr")

        @Suppress("SdCardPath")
        fun dataFor(applicationId: String) = File("/data/data/$applicationId")
    }
}

/** Creates and refreshes the prefix: the symlink farm, data files and the engine's side files. */
class EnginePrefix(
    private val paths: EnginePaths,
    private val assets: AssetSource,
    /** Threads that unpack at once. */
    private val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
) {
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

    /**
     * The parts the last [ensure] kept from the installed pack, unchanged (runtime trees, extensions' node_modules), as
     * paths relative to the prefix. Empty after a first install.
     */
    @Volatile
    var kept: List<String> = emptyList()
        private set

    /**
     * Sets the prefix up for [manifest]. The large parts (runtime trees, extensions' node_modules: thousands of files) are
     * unpacked by [workers] threads at once, and one already unpacked from the same zip (same sha256, its marker file
     * written once it was complete) is kept: an app update that moves nativeLibraryDir with the same pack only renews the
     * links and the small files. The steps that may overlap run in the same order as one after another would.
     */
    private fun install(manifest: EngineManifest) {
        paths.stamp.delete()
        removeLinks(paths.prefix.toPath())
        val staging = File(paths.prefix, STAGING)
        SafeFiles.deleteTree(staging)
        val keptNow = ArrayList<String>()
        // What to unpack: trees into a temporary directory each (swapped in afterwards), node_modules in place.
        val unpacks = ArrayList<Unpack>()
        for (tree in manifest.trees) {
            val dir = File(paths.prefix, tree.path)
            val key = tree.sha256?.let { "${tree.asset}\n$it\n${tree.executables.joinToString(",")}\n" }
            if (key != null && isUnpacked(dir, key)) {
                keptNow += tree.path
                continue
            }
            val temp = File(dir.parentFile, ".${dir.name}.unpack")
            SafeFiles.deleteTree(temp)
            unpacks += Unpack(tree.asset, temp, key, tree)
        }
        val extensions = manifest.extensions.map { it.name }.toSet()
        paths.extensionsDir.listFiles().orEmpty().filter { it.name !in extensions }.forEach(SafeFiles::deleteTree)
        val ownFiles = ArrayList<Pair<String, File>>()
        for (extension in manifest.extensions) {
            val dir = File(paths.extensionsDir, extension.name)
            val modules = File(dir, "node_modules")
            val key = extension.modules?.let { asset -> extension.modulesSha256?.let { "$asset\n$it\n" } }
            val keepModules = key != null && isUnpacked(modules, key)
            // What was there goes, as with a fresh copy (an older version's files), except node_modules when kept.
            dir.listFiles().orEmpty().filter { !(keepModules && it.name == "node_modules") }.forEach(SafeFiles::deleteTree)
            if (keepModules) keptNow += modules.relativeTo(paths.prefix).path
            for (file in extension.files) ownFiles += "$EXTENSIONS_ASSETS/${extension.name}/$file" to File(dir, file)
            // Its runtime dependencies, unpacked after its own files (the old directory's are gone above).
            if (extension.modules != null && !keepModules) unpacks += Unpack(extension.modules, modules, key, tree = null)
        }
        paths.packageDir.deleteRecursively()

        val pool = Executors.newFixedThreadPool(workers)
        try {
            // 1. The zips to temporary files (random access for the workers), the package and the extensions' own files.
            val zips = unpacks.mapIndexed { i, unpack -> File(staging, "$i.zip") to unpack }
            val first = ArrayList<Callable<Unit>>()
            for ((zip, unpack) in zips) first += Callable { assets.open(unpack.asset).use { copyAtomically(it, zip) } }
            for ((asset, to) in ownFiles) first += Callable { assets.open(asset).use { copyAtomically(it, to) } }
            first += Callable { copyPackage(manifest) }
            runAll(pool, first)

            // 2. Every zip's entries, in chunks across the workers.
            val open = zips.map { (zip, unpack) -> ZipFile(zip) to unpack }
            try {
                val chunks = ArrayList<Callable<Unit>>()
                for ((zip, unpack) in open) {
                    unpack.into.mkdirs()
                    for (chunk in zip.entries().toList().chunked(CHUNK_ENTRIES)) chunks += Callable { extract(zip, chunk, unpack.into) }
                }
                runAll(pool, chunks)
            } finally {
                for ((zip, _) in open) runCatching { zip.close() }
            }
        } finally {
            pool.shutdownNow()
            SafeFiles.deleteTree(staging)
        }

        // 3. Trees swapped in, then the links (some go inside the trees), the shell, the files, as before.
        for (unpack in unpacks) {
            val tree = unpack.tree
            if (tree != null) {
                for (rel in tree.executables) File(unpack.into, rel).setExecutable(true, true)
                unpack.key?.let { writeAtomically(File(unpack.into, UNPACKED), it) }
                val dir = File(paths.prefix, tree.path)
                if (!SafeFiles.deleteTree(dir)) throw IOException("cannot remove the old ${tree.path}")
                dir.parentFile?.mkdirs()
                Files.move(unpack.into.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } else {
                unpack.key?.let { writeAtomically(File(unpack.into, UNPACKED), it) }
            }
        }
        kept = keptNow
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
        // Not fatal: without the entries Pi just runs without the extensions (the next pack update tries again).
        BundledExtensions.register(paths.agentDir, manifest.extensions.map { File(paths.extensionsDir, it.name).path }, paths.extensionsDir.path)
    }

    /** A zip asset to unpack into [into]; [key] goes into its marker once complete; [tree] when it is a runtime tree. */
    private class Unpack(val asset: String, val into: File, val key: String?, val tree: EngineManifest.Tree?)

    /** [dir] holds a complete unpack made with [key] (its marker says so). */
    private fun isUnpacked(dir: File, key: String): Boolean =
        SafeFiles.isRealDirectory(dir) && runCatching { File(dir, UNPACKED).readText() }.getOrNull() == key

    private fun copyPackage(manifest: EngineManifest) {
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
    }

    /** Writes [entries] of [zip] under [into]. Entries are paths inside it (no `..`, not absolute): a bad pack cannot write elsewhere. */
    private fun extract(zip: ZipFile, entries: List<ZipEntry>, into: File) {
        val buffer = ByteArray(64 * 1024)
        for (entry in entries) {
            val name = EngineManifest.safeRelative(entry.name.trimEnd('/'))
            val out = File(into, name)
            if (entry.isDirectory) {
                Files.createDirectories(out.toPath())
                continue
            }
            out.parentFile?.let { Files.createDirectories(it.toPath()) }
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { o ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        o.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    /** Runs [tasks] on [pool] and waits for all of them; the first failure is thrown as itself. */
    private fun runAll(pool: ExecutorService, tasks: List<Callable<Unit>>) {
        val futures = tasks.map(pool::submit)
        var failure: Throwable? = null
        for (future in futures) {
            try {
                future.get()
            } catch (e: ExecutionException) {
                if (failure == null) failure = e.cause ?: e
            }
        }
        failure?.let { throw it }
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
        const val EXTENSIONS_ASSETS = "engine/extensions"
        const val SYSTEM_SH = "/system/bin/sh"

        /** In an unpacked tree or node_modules: what it was unpacked from, written once it was complete. */
        const val UNPACKED = ".pibolt-unpacked"

        /** Under the prefix: the zips being unpacked, gone afterwards. */
        private const val STAGING = ".unpack"

        /** Entries per task: small enough to spread a zip across the workers, large enough to keep the overhead low. */
        private const val CHUNK_ENTRIES = 128

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

/**
 * What the engine banner says when the prefix could not be set up. A full disk says so (the setup unpacks a few hundred
 * MB on first launch and after updates): "Could not set up the engine" and Retry alone did not tell the user what to do.
 */
fun setupFailure(e: Throwable): String {
    val full = generateSequence(e) { it.cause }.any { t -> t.message?.let { "ENOSPC" in it || "No space left on device" in it } == true }
    return if (full) "Not enough free storage to set up the engine. Free some space, then retry." else "Could not set up the engine"
}
