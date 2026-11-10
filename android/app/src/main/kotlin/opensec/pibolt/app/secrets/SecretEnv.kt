package opensec.pibolt.app.secrets

import java.io.File

/** Secret names, masking, environment injection and the optional `.env` file. Pure: unit-tested on the JVM. */
object SecretEnv {
    /** What the UI shows instead of a value: fixed length, so it does not reveal the value's length either. */
    const val MASK = "••••••••"

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Variables the engine and the terminal set themselves (android/engine/CONTRACT.md, step 3) and the loader's: a
     * secret must not change where tools, the shell or the engine's files are.
     */
    val RESERVED = setOf(
        "PATH", "HOME", "TMPDIR", "SHELL", "LANG", "TZ", "TERM", "COLORTERM", "TERMINFO", "PI_PACKAGE_DIR",
        "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR", "SSL_CERT_FILE", "ANDROID_ROOT", "ANDROID_DATA", "PWD", "OLDPWD",
        // The runtimes' package-manager dirs (EngineEnvironment.kt, Runtimes.kt) and the pack's esbuild.
        "npm_config_cache", "PIP_CACHE_DIR", "PYTHONUSERBASE", "ESBUILD_BINARY_PATH",
    )

    /** Prefixes the app owns: the loader's, XDG dirs, git's config (the GitHub credential helper) and Pi-Bolt's own. */
    private val RESERVED_PREFIXES = listOf("LD_", "XDG_", "GIT_CONFIG_", "PIBOLT_")

    /** Null when [name] is usable, else why not (shown in the form). */
    fun nameProblem(name: String): String? = when {
        name.isEmpty() -> "Enter a name"
        name.length > 128 -> "Use at most 128 characters"
        !NAME.matches(name) -> "Use letters, digits and _, not starting with a digit"
        name in RESERVED || RESERVED_PREFIXES.any(name::startsWith) -> "$name is set by Pi-Bolt"
        else -> null
    }

    fun mask(value: String): String = if (value.isEmpty()) "" else MASK

    /**
     * [env] with [secrets] added. Variables in [env] that the app owns ([RESERVED], `LD_*`, `XDG_*`, `GIT_CONFIG_*`, `PIBOLT_*`) are never
     * replaced, whatever a secrets file holds; other names take the secret's value.
     */
    fun inject(env: Map<String, String>, secrets: Map<String, String>): Map<String, String> {
        if (secrets.isEmpty()) return env
        val out = env.toMutableMap()
        for ((name, value) in secrets) if (nameProblem(name) == null) out[name] = value
        return out
    }

    /** A `.env` file: `NAME="value"`, with `\`, `"`, `$`, and newlines escaped (dotenv and docker-compose read it). */
    fun dotEnv(secrets: List<Secret>): String = buildString {
        append("# Written by Pi-Bolt from the project's secrets. Do not commit this file.\n")
        for (s in secrets) {
            append(s.name).append("=\"")
            for (c in s.value) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
            append("\"\n")
        }
    }

    /**
     * Writes `.env` in [project] (owner-only permissions). When the project is a git repository and `.gitignore`
     * does not ignore `.env` yet, adds it. Returns whether `.gitignore` changed.
     */
    fun writeDotEnv(project: File, secrets: List<Secret>): Boolean {
        val file = File(project, ".env")
        file.writeText(dotEnv(secrets))
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        if (!File(project, ".git").exists()) return false
        val ignore = File(project, ".gitignore")
        val text = if (ignore.isFile) ignore.readText() else ""
        if (ignoresDotEnv(text)) return false
        ignore.writeText(buildString {
            append(text)
            if (text.isNotEmpty() && !text.endsWith('\n')) append('\n')
            append(".env\n")
        })
        return true
    }

    /** Whether a `.gitignore` already has a line that ignores the top-level `.env`. */
    fun ignoresDotEnv(gitignore: String): Boolean = gitignore.lineSequence().map { it.trim() }.any {
        it == ".env" || it == "/.env" || it == "*.env" || it == ".env*" || it == "/.env*"
    }
}
