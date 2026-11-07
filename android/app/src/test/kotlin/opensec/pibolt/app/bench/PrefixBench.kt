package opensec.pibolt.app.bench

import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.engine.AssetSource
import opensec.pibolt.app.engine.EngineManifest
import opensec.pibolt.app.engine.EnginePaths
import opensec.pibolt.app.engine.EnginePrefix
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * The engine prefix setup, before (0.6.0's [OldEnginePrefix]) and after ([EnginePrefix]), on a real pack: a first install,
 * and an app update with the same pack (nativeLibraryDir moved). Not a unit test: a main, run on the phone with
 * app_process (android/tools/perf/prefix-bench.sh) or on a computer against android/engine/out/assets.
 *
 *   PrefixBench <app.apk, or an assets dir (holds engine/manifest.json)> <work dir> [rounds] [workers]
 */
object PrefixBench {
    @JvmStatic
    fun main(args: Array<String>) {
        // An APK (the installed one: /data/app/.../base.apk is readable by the shell) reads like the app's AssetManager.
        val assets = if (args[0].endsWith(".apk")) ApkAssets(ZipFile(args[0])) else DirAssets(File(args[0]))
        val work = File(args[1])
        val rounds = args.getOrNull(2)?.toInt() ?: 2
        val workers = args.getOrNull(3)?.toInt()
        val manifest = assets.open(EnginePrefix.MANIFEST_ASSET).use { EngineManifest.parse(it.reader().readText()) }
        println("pack: ${manifest.trees.size} trees, ${manifest.extensions.size} extensions, ${manifest.links.size} links; ${Runtime.getRuntime().availableProcessors()} cpus")
        repeat(rounds) { round ->
            for (kind in listOf("before", "after")) {
                val dir = File(work, kind)
                SafeFiles.deleteTree(dir)
                fun paths(lib: String) = EnginePaths(File(dir, "usr"), File(dir, "data"), File(dir, lib))
                fun setup(p: EnginePaths): Pair<Long, List<String>> {
                    val started = System.nanoTime()
                    val kept = if (kind == "before") {
                        OldEnginePrefix(p, assets).ensure(manifest)
                        emptyList()
                    } else {
                        (if (workers != null) EnginePrefix(p, assets, workers) else EnginePrefix(p, assets)).let { it.ensure(manifest); it.kept }
                    }
                    return (System.nanoTime() - started) / 1_000_000 to kept
                }
                val (install, _) = setup(paths("lib1"))
                val (update, kept) = setup(paths("lib2"))
                println("round ${round + 1} $kind: first install $install ms, app update (same pack) $update ms, kept ${kept.size}")
                SafeFiles.deleteTree(dir)
            }
        }
    }

    private class ApkAssets(private val apk: ZipFile) : AssetSource {
        override fun open(path: String): InputStream = apk.getEntry("assets/$path")?.let(apk::getInputStream) ?: throw FileNotFoundException(path)

        override fun list(path: String): List<String> =
            apk.entries().asSequence().map { it.name }.filter { it.startsWith("assets/$path/") }.map { it.removePrefix("assets/$path/").substringBefore('/') }.distinct().toList()
    }

    private class DirAssets(private val root: File) : AssetSource {
        override fun open(path: String): InputStream = File(root, path).takeIf { it.isFile }?.let(::FileInputStream) ?: throw FileNotFoundException(path)

        override fun list(path: String): List<String> = File(root, path).list()?.toList().orEmpty()
    }
}
