package opensec.pibolt.app.ui.packages

import androidx.compose.runtime.Immutable

/** One compatibility finding before an install. [Level.Blocks]: it cannot run here; [Level.Warns]: it may not. */
@Immutable
data class CompatNote(val level: Level, val title: String, val detail: String) {
    enum class Level { Blocks, Warns, Notes }
}

/**
 * Whether a package runs on the phone's engine (pure, unit-tested). The engine is Pi-Bolt for glibc linux-arm64 in the
 * app's prefix (android/engine/CONTRACT.md): not Termux (bionic), so `android-arm64` builds do not count; `.node`
 * addons and wheels load, but there is no compiler, so anything built from source at install time fails, and static
 * binaries in a package do not run. npm runs install scripts.
 *
 * Checked, from the package.json and (when known) the published file list:
 * - `os`, `cpu` and `libc` fields that exclude linux, arm64 or glibc: it cannot install;
 * - a native addon built from source (`binding.gyp`, `gypfile`, node-gyp/cmake-js in its scripts);
 * - prebuilt binaries (`prebuilds/<platform>-<arch>/`, platform-named optional dependencies) without linux-arm64 glibc;
 * - `.node` binaries outside `prebuilds/`, which are built for one platform;
 * - direct dependencies known to carry native addons;
 * - install scripts (`preinstall`, `install`, `postinstall`), which run on the phone.
 */
object PackageCompat {
    const val PLATFORM = "linux-arm64 (glibc)"

    /** Direct dependencies that build or download a native addon. */
    private val NATIVE_DEPS = setOf(
        "better-sqlite3", "sqlite3", "node-pty", "@lydell/node-pty", "keytar", "canvas", "bcrypt", "argon2", "re2",
        "leveldown", "classic-level", "zeromq", "usb", "serialport", "@serialport/bindings-cpp", "cpu-features",
        "node-sass", "fsevents", "robotjs", "@nut-tree/nut-js", "node-hid", "ffi-napi", "ref-napi", "sharp",
        "onnxruntime-node", "@tensorflow/tfjs-node", "faiss-node", "hnswlib-node", "lmdb", "msgpackr-extract",
    )

    /** Helpers that build an addon from source or fetch a prebuilt one at install time. */
    private val ADDON_TOOLS = setOf("node-gyp", "node-gyp-build", "prebuild-install", "node-pre-gyp", "@mapbox/node-pre-gyp", "cmake-js", "nan", "node-addon-api", "bindings")

    private val INSTALL_SCRIPTS = listOf("preinstall", "install", "postinstall")

    /** `<name>-<os>-<cpu>[-<libc>]`, the naming of platform-specific optional dependencies (napi-rs, esbuild, swc). */
    private val PLATFORM_PACKAGE = Regex("""(?:^|[-/])(darwin|linux|win32|windows|freebsd|android|openbsd|sunos|aix)-(x64|arm64|ia32|arm|armv7|riscv64|ppc64|s390x|loong64|universal)(?:-(gnu|musl|msvc|gnueabihf|eabi|androideabi))?$""")

    /** `prebuilds/<os>-<cpu>/...` directories (prebuildify). */
    private val PREBUILD_DIR = Regex("""(?:^|/)prebuilds/([a-z0-9]+)-([a-z0-9]+)(?:[-+][^/]*)?/""")

    fun check(manifest: PackageManifest, files: List<String>?): List<CompatNote> {
        val out = ArrayList<CompatNote>()
        excludes(manifest.os, "linux")?.let { out += CompatNote(CompatNote.Level.Blocks, "Not for Linux", "It installs only on $it; the engine is $PLATFORM.") }
        excludes(manifest.cpu, "arm64")?.let { out += CompatNote(CompatNote.Level.Blocks, "Not for arm64", "It installs only on $it processors; the engine is $PLATFORM.") }
        excludes(manifest.libc, "glibc")?.let { out += CompatNote(CompatNote.Level.Blocks, "Not for glibc", "It needs $it; the engine is $PLATFORM.") }

        val all = files.orEmpty()
        val scripts = INSTALL_SCRIPTS.mapNotNull { k -> manifest.scripts[k]?.let { k to it } }
        val buildsFromSource = manifest.gypfile || "binding.gyp" in all ||
            scripts.any { (_, cmd) -> "node-gyp" in cmd || "cmake-js" in cmd || "prebuild-install" in cmd || "node-pre-gyp" in cmd }

        // Prebuilt binaries: which platforms they are for, and whether one fits.
        val prebuilds = all.mapNotNull { PREBUILD_DIR.find(it) }.map { it.groupValues[1] to it.groupValues[2] }.toSet()
        val prebuildFiles = all.filter { "prebuilds/linux-arm64" in it }
        val prebuildFits = prebuildFiles.isNotEmpty() && prebuildFiles.any { "musl" !in it }
        if (prebuilds.isNotEmpty() && !prebuildFits) {
            val list = prebuilds.map { "${it.first}-${it.second}" }.sorted().joinToString(", ")
            out += CompatNote(CompatNote.Level.Warns, "No prebuilt binary for this phone", "It ships native binaries for $list, not $PLATFORM. Android (Termux) builds do not fit the engine.")
        }
        val platformDeps = manifest.optionalDependencies.keys.filter { PLATFORM_PACKAGE.containsMatchIn(it) }
        if (platformDeps.isNotEmpty() && platformDeps.none(::fitsEngine)) {
            val list = platformDeps.mapNotNull { PLATFORM_PACKAGE.find(it)?.value?.trimStart('-', '/') }.distinct().sorted().joinToString(", ")
            out += CompatNote(CompatNote.Level.Warns, "No native build for this phone", "Its platform packages cover $list, not $PLATFORM.")
        }
        if (buildsFromSource && !prebuildFits) {
            out += CompatNote(CompatNote.Level.Warns, "Builds native code", "It compiles a native addon when it installs. The phone has no compiler, so the install may fail unless a $PLATFORM binary can be downloaded.")
        }
        val looseBinaries = all.filter { it.endsWith(".node") && "prebuilds/" !in it }
        if (looseBinaries.isNotEmpty() && prebuilds.isEmpty() && !buildsFromSource) {
            out += CompatNote(CompatNote.Level.Warns, "Contains native binaries", "${looseBinaries.first()}${if (looseBinaries.size > 1) " and ${looseBinaries.size - 1} more" else ""} may be built for another platform.")
        }
        val nativeDeps = manifest.dependencies.keys.filter { it in NATIVE_DEPS }
        if (nativeDeps.isNotEmpty()) {
            out += CompatNote(CompatNote.Level.Warns, "Native dependencies", "${nativeDeps.joinToString(", ")} ${if (nativeDeps.size == 1) "uses" else "use"} native code, which may not have a $PLATFORM build.")
        } else if (!buildsFromSource && manifest.dependencies.keys.any { it in ADDON_TOOLS }) {
            out += CompatNote(CompatNote.Level.Warns, "Native addon", "It loads a native addon, which may not have a $PLATFORM build.")
        }
        if (scripts.isNotEmpty()) {
            out += CompatNote(CompatNote.Level.Notes, "Runs install scripts", scripts.joinToString("\n") { (k, cmd) -> "$k: ${cmd.take(200)}" })
        }
        return out
    }

    /** A platform-specific package name that runs on the engine: linux-arm64, glibc or unspecified libc. */
    fun fitsEngine(name: String): Boolean {
        val m = PLATFORM_PACKAGE.find(name) ?: return false
        return m.groupValues[1] == "linux" && m.groupValues[2] == "arm64" && m.groupValues[3].let { it.isEmpty() || it == "gnu" }
    }

    /**
     * The allowed values when [field] (npm's `os`/`cpu`/`libc`: a list, `!x` to exclude) leaves out [ours], as text;
     * null when [ours] is allowed, the field is empty or it is npm's `["any"]`.
     */
    internal fun excludes(field: List<String>, ours: String): String? {
        // npm (npm-install-checks) takes a lone "any" as no restriction.
        if (field.isEmpty() || field == listOf("any")) return null
        val negated = field.filter { it.startsWith("!") }.map { it.removePrefix("!") }
        val allowed = field.filterNot { it.startsWith("!") }
        return when {
            ours in negated -> allowed.ifEmpty { listOf("other systems than $ours") }.joinToString(", ")
            allowed.isNotEmpty() && ours !in allowed -> allowed.joinToString(", ")
            else -> null
        }
    }
}
