package opensec.pibolt.app.engine

import java.io.File

/**
 * The engine's environment (android/engine/CONTRACT.md, Runtime setup step 3), on top of the app process's own
 * ([base]: ANDROID_ROOT, ANDROID_DATA and the like, which /system/bin tools need), then the manifest's `env`
 * ([fromPack]), then [extra] (the GitHub credential helper, see GitCredentials). No LD_LIBRARY_PATH or LD_PRELOAD: the
 * loader finds glibc in its compiled-in prefix.
 */
fun engineEnvironment(
    paths: EnginePaths,
    base: Map<String, String>,
    timeZone: String,
    fromPack: Map<String, String> = emptyMap(),
    extra: Map<String, String> = emptyMap(),
): Map<String, String> {
    val prefix = paths.prefix.path
    val env = base.toMutableMap()
    env.remove("LD_LIBRARY_PATH")
    env.remove("LD_PRELOAD")
    env["HOME"] = paths.home.path
    // Everything on internal storage (never /sdcard: FUSE there is several times slower for many small files).
    env["TMPDIR"] = paths.tmp.path
    env["XDG_CACHE_HOME"] = paths.cache.path
    env["XDG_CONFIG_HOME"] = File(paths.home, ".config").path
    env["XDG_DATA_HOME"] = File(paths.home, ".local/share").path
    env["XDG_STATE_HOME"] = File(paths.home, ".local/state").path
    env["TZ"] = timeZone
    env["LANG"] = "C.UTF-8"
    env["PATH"] = "$prefix/bin:/system/bin"
    env["TERM"] = "dumb"
    // bash once the userland pack ships it; until then the toybox shell, which Pi also picks from PATH.
    env["SHELL"] = File(paths.bin, "bash").takeIf { it.exists() }?.path ?: EnginePrefix.SYSTEM_SH
    env["PI_PACKAGE_DIR"] = paths.packageDir.path
    env["GIT_EXEC_PATH"] = "$prefix/libexec/git-core"
    env["GIT_TEMPLATE_DIR"] = "$prefix/share/git-core/templates"
    env["TERMINFO"] = "$prefix/share/terminfo"
    // Only when shipped: pointing TLS at a missing bundle would break HTTPS that works with built-in roots.
    val certs = File(paths.prefix, "etc/ssl/certs/ca-certificates.crt")
    if (certs.isFile) env["SSL_CERT_FILE"] = certs.path else env.remove("SSL_CERT_FILE")
    // Language runtimes: caches in the cache dir (the system may clear it, and Settings → Runtimes can), global npm
    // and user pip installs under HOME/.local (npm's builtin npmrc and the pack's pip.conf say so too).
    env["npm_config_cache"] = File(paths.cache, "npm").path
    env["PIP_CACHE_DIR"] = File(paths.cache, "pip").path
    env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
    env["PYTHONUSERBASE"] = paths.userBase.path
    // esbuild's npm package runs this binary instead of its own (a static Linux one, which cannot run from app data):
    // PREFIX/bin/esbuild, a link to nativeLibraryDir's libesbuild_exe.so. The link, not nativeLibraryDir itself, because
    // esbuild's postinstall writes the path into node_modules and nativeLibraryDir moves on every app update.
    val esbuild = File(paths.bin, "esbuild")
    if (esbuild.exists()) env["ESBUILD_BINARY_PATH"] = esbuild.path else env.remove("ESBUILD_BINARY_PATH")
    // The pack knows its own layout best (a shell it ships, for example).
    env.putAll(fromPack)
    // Commands installed by `npm install -g` and `pip install` come first, so an upgraded npm or pip wins.
    env["PATH"] = File(paths.userBase, "bin").path + ":" + (env["PATH"] ?: "$prefix/bin:/system/bin")
    env.putAll(extra)
    return env
}

/** `etc/resolv.conf` for glibc from the default network's DNS servers (glibc reads at most three). */
fun resolvConf(servers: List<String>, searchDomains: String?): String = buildString {
    append("# Written by Pi-Bolt from the active network's DNS servers\n")
    for (server in servers.distinct().take(3)) append("nameserver ").append(server).append('\n')
    searchDomains?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let {
        append("search ").append(it.joinToString(" ")).append('\n')
    }
    append("options timeout:2 attempts:2\n")
}

const val ETC_HOSTS = "127.0.0.1 localhost\n::1 localhost ip6-localhost\n"
