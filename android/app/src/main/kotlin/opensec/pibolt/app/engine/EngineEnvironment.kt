package opensec.pibolt.app.engine

import java.io.File

/**
 * The engine's environment (android/engine/CONTRACT.md, Runtime setup step 3), on top of the app process's own
 * ([base]: ANDROID_ROOT, ANDROID_DATA and the like, which /system/bin tools need), then the manifest's `env`
 * ([fromPack]). No LD_LIBRARY_PATH or LD_PRELOAD: the loader finds glibc in its compiled-in prefix.
 */
fun engineEnvironment(paths: EnginePaths, base: Map<String, String>, timeZone: String, fromPack: Map<String, String> = emptyMap()): Map<String, String> {
    val prefix = paths.prefix.path
    val env = base.toMutableMap()
    env.remove("LD_LIBRARY_PATH")
    env.remove("LD_PRELOAD")
    env["HOME"] = paths.home.path
    env["TMPDIR"] = paths.tmp.path
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
    // The pack knows its own layout best (a shell it ships, for example).
    env.putAll(fromPack)
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
