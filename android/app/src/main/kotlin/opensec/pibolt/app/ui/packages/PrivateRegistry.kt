package opensec.pibolt.app.ui.packages

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.CliResult
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.git.github.KeyValue
import opensec.pibolt.app.git.github.KeystoreBox
import opensec.pibolt.app.git.github.PrefsKeyValue
import opensec.pibolt.app.git.github.SecretBox
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID

/**
 * A private npm registry for packages (the packages sheet's Advanced section): its URL and, optionally, the scope it
 * serves (`@company`). Without a scope it replaces the default registry for every npm package. Never holds the token.
 */
@Immutable
data class RegistryConfig(val url: String, val scope: String?) {
    /** Whether [npmName] (`@scope/name` or `name`) is served by this registry. */
    fun serves(npmName: String): Boolean = if (scope == null) url != Npmrc.DEFAULT_REGISTRY else npmName.startsWith("$scope/")
}

/** One registry line set of an `.npmrc`: where [scope] (or every package) comes from, and its token. */
data class NpmrcEntry(val url: String, val scope: String?, val token: String?) {
    override fun toString(): String = "NpmrcEntry(url=$url, scope=$scope, token=${if (token == null) "none" else "set"})"
}

/**
 * The `.npmrc` the engine's npm reads at install time (pure, unit-tested). Values are checked rather than escaped:
 * a registry URL is https (http only for this phone's own 127.0.0.1/localhost, a forwarded test registry), a scope is
 * `@name`, and a token is one run of the characters registry tokens use (npm, GitHub, JWT, base64), so no value can add
 * a line, start a comment, be read as JSON or name an environment variable (`${...}`) in npm's ini parser.
 */
object Npmrc {
    const val DEFAULT_REGISTRY = "https://registry.npmjs.org/"
    private val SCOPE = Regex("^@[a-z0-9][a-z0-9._~-]{0,213}$")
    private val TOKEN = Regex("^[A-Za-z0-9._~+/=:-]{8,4096}$")
    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost")

    /** [input] as a registry URL ending in `/`, or null when it is not one ([urlProblem] says why). */
    fun normalizeUrl(input: String): String? {
        if (urlProblem(input) != null) return null
        val u = URI(input.trim())
        // Scheme and host are case-insensitive: lowercased, so every later comparison sees one form.
        val port = if (u.port == -1) "" else ":${u.port}"
        val path = (u.rawPath ?: "").ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
        return "${u.scheme.lowercase()}://${u.host.lowercase()}$port$path"
    }

    fun urlProblem(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return "Enter the registry URL"
        if (t.length > 500 || t.any { it.isWhitespace() || it.isISOControl() || it in "\"'`;#\\\${}" }) return "Not a registry URL"
        val uri = runCatching { URI(t) }.getOrNull() ?: return "Not a registry URL"
        val host = uri.host?.lowercase() ?: return "Not a registry URL"
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return "Use the registry's address only (no ?, # or user)"
        // The same parser the catalog uses: a port out of range or a host it refuses is caught here, not after Save.
        if (uri.port != -1 && uri.port !in 1..65535 || t.toHttpUrlOrNull() == null) return "Not a registry URL"
        return when (uri.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (host in LOCAL_HOSTS) null else "Use https: the token must not travel in the clear"
            else -> "Use an https:// address"
        }
    }

    /** Null when [scope] is empty (no scope) or usable; `company` is read as `@company`. */
    fun normalizeScope(scope: String): String? = scope.trim().lowercase().takeIf { it.isNotEmpty() }?.let { if (it.startsWith("@")) it else "@$it" }

    fun scopeProblem(scope: String): String? {
        val s = normalizeScope(scope) ?: return null
        return if (SCOPE.matches(s)) null else "A scope is @ and a name, like @company"
    }

    fun tokenProblem(token: String): String? = when {
        token.isEmpty() -> "Enter the token"
        !TOKEN.matches(token) -> "That does not look like a registry token"
        else -> null
    }

    /** npm's key for a registry's credentials: the URL without its scheme, ending in `/` (`//host/path/`). */
    fun nerfDart(url: String): String {
        val u = URI(url)
        val port = if (u.port == -1) "" else ":${u.port}"
        val path = (u.rawPath ?: "").ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
        return "//${u.host.lowercase()}$port$path"
    }

    /**
     * The file: per entry `@scope:registry=<url>` (or `registry=<url>` when it has no scope and is not npm's own) and
     * `//host/path/:_authToken=<token>` when it has one. An entry with an unusable URL, scope or token is left out.
     */
    fun render(entries: List<NpmrcEntry>): String = buildString {
        append("# Written by Pi-Bolt for one install; removed when it ends.\n")
        for (e in entries) {
            val url = normalizeUrl(e.url) ?: continue
            if (e.scope != null && scopeProblem(e.scope) != null) continue
            if (e.token != null && tokenProblem(e.token) != null) continue
            when {
                e.scope != null -> append(normalizeScope(e.scope)).append(":registry=").append(url).append('\n')
                url != DEFAULT_REGISTRY -> append("registry=").append(url).append('\n')
            }
            if (e.token != null) append(nerfDart(url)).append(":_authToken=").append(e.token).append('\n')
        }
    }

    /** [text] with [secrets] replaced, for anything shown or logged after an install that used them. */
    fun redact(text: String, vararg secrets: String?): String {
        var out = text
        for (s in secrets) if (!s.isNullOrEmpty()) out = out.replace(s, "[token]")
        return out
    }
}

/**
 * The private registry setting: URL and scope in plain [kv], the token sealed by [box] (the Android Keystore on the
 * phone, `KeystoreBox`), never readable back by the UI: [hasToken] only. Prefs are excluded from backup and device
 * transfer (data_extraction_rules.xml, allowBackup=false).
 */
class RegistryStore(private val kv: KeyValue, private val box: SecretBox) {
    fun config(): RegistryConfig? {
        val url = kv.get(KEY_URL)?.let(Npmrc::normalizeUrl) ?: return null
        return RegistryConfig(url, kv.get(KEY_SCOPE)?.takeIf { Npmrc.scopeProblem(it) == null })
    }

    /** Saves [config]; a token saved for another registry is removed (it must never go to a different server). */
    fun save(config: RegistryConfig) {
        val before = kv.get(KEY_URL)?.let(Npmrc::normalizeUrl)
        if (before != null && before != Npmrc.normalizeUrl(config.url)) kv.put(KEY_TOKEN, null)
        kv.put(KEY_URL, config.url)
        kv.put(KEY_SCOPE, config.scope)
    }

    val hasToken: Boolean get() = kv.get(KEY_TOKEN) != null

    fun saveToken(token: String) {
        require(Npmrc.tokenProblem(token) == null) { "not a registry token" }
        kv.put(KEY_TOKEN, Base64.getEncoder().encodeToString(box.seal(token.toByteArray(Charsets.UTF_8))))
    }

    /** The token for an install or a registry request; null when none is saved or it no longer opens. */
    fun token(): String? {
        val sealed = kv.get(KEY_TOKEN) ?: return null
        return runCatching { String(box.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8) }.getOrNull()
    }

    fun removeToken() = kv.put(KEY_TOKEN, null)

    fun clear() {
        for (k in listOf(KEY_URL, KEY_SCOPE, KEY_TOKEN)) kv.put(k, null)
    }

    /** The `.npmrc` entries of this setting (empty when nothing is configured). */
    fun entries(): List<NpmrcEntry> = config()?.let { listOf(NpmrcEntry(it.url, it.scope, token())) }.orEmpty()

    override fun toString(): String = "RegistryStore(url=${kv.get(KEY_URL)}, scope=${kv.get(KEY_SCOPE)}, token=${if (hasToken) "set" else "none"})"

    private companion object {
        const val KEY_URL = "url"
        const val KEY_SCOPE = "scope"
        const val KEY_TOKEN = "token.sealed"
    }
}

/**
 * Runs a package command with the registry's `.npmrc`: written to a fresh 0600 file in [dir] (the app's no-backup
 * directory), named to npm as its global config (`npm_config_globalconfig`, so the user's own `~/.npmrc` still applies
 * on top) and deleted when [block] returns, whatever happens. With no registry configured, [block] gets no extra
 * environment. Output that comes back is redacted of the token before anyone sees it.
 */
object NpmrcRun {
    const val ENV = "npm_config_globalconfig"

    suspend fun <T> with(dir: File, entries: List<NpmrcEntry>, block: suspend (env: Map<String, String>) -> T): T {
        if (entries.isEmpty()) return block(emptyMap())
        val folder = File(dir, "npmrc").apply { mkdirs() }
        runCatching { Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwx------")) }
        // A file outlives its run only when the process was killed mid-install (installs time out after 10 minutes).
        val stale = System.currentTimeMillis() - 60 * 60_000L
        folder.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val file = File(folder, "npmrc-" + UUID.randomUUID())
        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            file.writeText(Npmrc.render(entries))
            return block(mapOf(ENV to file.path))
        } finally {
            file.delete()
        }
    }
}

/** The app's one [RegistryStore] (prefs `npm-registry`, Keystore alias `pibolt-npm-token`). */
object PrivateRegistries {
    @Volatile private var store: RegistryStore? = null

    fun get(context: Context): RegistryStore = store ?: synchronized(this) {
        store ?: RegistryStore(
            PrefsKeyValue(context.applicationContext.getSharedPreferences("npm-registry", Context.MODE_PRIVATE)),
            KeystoreBox("pibolt-npm-token"),
        ).also { store = it }
    }

    /** The private registry's catalog (its config and a catalog that sends the token to it), or null. Blocking: IO. */
    fun catalog(store: RegistryStore, http: OkHttpClient): Pair<RegistryConfig, PackageCatalog>? {
        val config = store.config() ?: return null
        return runCatching { config to PackageCatalog(http, registry = config.url.trimEnd('/'), authToken = store.token(), private = true) }.getOrNull()
    }
}

/**
 * A package command (`pi install`, `pi remove`) through the engine with the private registry's `.npmrc` for that run
 * only ([NpmrcRun]); its output comes back with the token redacted.
 */
suspend fun packageCli(engine: EngineApi, context: Context, args: List<String>, cwd: File? = null, timeoutMs: Long = 600_000): CliResult =
    withContext(Dispatchers.IO) {
        val store = PrivateRegistries.get(context)
        val token = store.token()
        val result = NpmrcRun.with(context.noBackupFilesDir, store.entries()) { env -> engine.cli(args, cwd = cwd, timeoutMs = timeoutMs, env = env) }
        if (result.ok && args.firstOrNull() in PACKAGE_CHANGES) packagesChanged(engine, context)
        result.copy(stdout = Npmrc.redact(result.stdout, token), stderr = Npmrc.redact(result.stderr, token))
    }

private val PACKAGE_CHANGES = setOf("install", "remove", "uninstall", "update")

/** The installed packages changed: idle engines restart with them, and the providers they register are listed again. */
fun packagesChanged(engine: EngineApi, context: Context) {
    engine.packagesChanged()
    (context.applicationContext as? PiBoltApp)?.providers?.refresh()
}
