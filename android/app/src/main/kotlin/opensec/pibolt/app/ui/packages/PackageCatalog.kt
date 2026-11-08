package opensec.pibolt.app.ui.packages

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.io.IOException

/** One package in a catalog search (the npm registry's search, keyword `pi-package`: what pi.dev/packages lists). */
@Immutable
data class CatalogEntry(
    val name: String,
    val version: String,
    val description: String?,
    val weeklyDownloads: Long?,
    val repository: String?,
    val keywords: List<String> = emptyList(),
)

@Immutable
data class CatalogPage(val entries: List<CatalogEntry>, val total: Int)

/** The resources a package declares under `pi` in its package.json (docs/packages.md). */
@Immutable
data class PiManifest(
    val extensions: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = extensions.isEmpty() && skills.isEmpty() && prompts.isEmpty() && themes.isEmpty()
}

/** What the app reads of a package's package.json: what it adds and what decides whether it runs on this phone. */
@Immutable
data class PackageManifest(
    val name: String,
    val version: String?,
    val description: String?,
    val license: String?,
    val repository: String?,
    val homepage: String?,
    val keywords: List<String>,
    /** Null when there is no `pi` key: Pi then discovers the conventional directories. */
    val pi: PiManifest?,
    val os: List<String>,
    val cpu: List<String>,
    val libc: List<String>,
    val scripts: Map<String, String>,
    val gypfile: Boolean,
    val dependencies: Map<String, String>,
    val optionalDependencies: Map<String, String>,
    val unpackedSize: Long?,
)

/** A package resource, for the detail view: its type and its name. */
data class AddedResource(val kind: String, val name: String)

class CatalogException(message: String) : Exception(message)

/**
 * The JSON answers the catalog reads (pure, unit-tested): npm's search (`/-/v1/search`), a version's package.json
 * (`/<name>/<version>`), last week's downloads (api.npmjs.org) and jsDelivr's file list of a published version.
 */
object CatalogJson {
    fun parseSearch(text: String): CatalogPage {
        val root = parse(text) ?: throw CatalogException("The package registry sent an unexpected answer.")
        val entries = (root["objects"] as? JsonArray).orEmpty().mapNotNull { o ->
            val obj = o as? JsonObject ?: return@mapNotNull null
            val pkg = obj.obj("package") ?: return@mapNotNull null
            val name = pkg.str("name") ?: return@mapNotNull null
            CatalogEntry(
                name = name,
                version = pkg.str("version").orEmpty(),
                description = pkg.str("description")?.trim()?.takeIf { it.isNotEmpty() },
                weeklyDownloads = obj.obj("downloads")?.long("weekly"),
                repository = repositoryUrl(pkg.obj("links")?.get("repository")),
                keywords = strings(pkg["keywords"]),
            )
        }
        return CatalogPage(entries, root.long("total")?.toInt() ?: entries.size)
    }

    fun parseManifest(text: String): PackageManifest {
        val o = parse(text) ?: throw CatalogException("The package registry sent an unexpected answer.")
        val name = o.str("name") ?: throw CatalogException("The package has no name.")
        val pi = o.obj("pi")?.let {
            PiManifest(strings(it["extensions"]), strings(it["skills"]), strings(it["prompts"]), strings(it["themes"]))
        }
        val scripts = (o.obj("scripts")).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
        return PackageManifest(
            name = name,
            version = o.str("version"),
            description = o.str("description")?.trim()?.takeIf { it.isNotEmpty() },
            license = o.str("license") ?: o.obj("license")?.str("type"),
            repository = repositoryUrl(o["repository"]),
            homepage = o.str("homepage")?.takeIf { it.startsWith("https://") },
            keywords = strings(o["keywords"]),
            pi = pi,
            os = strings(o["os"]),
            cpu = strings(o["cpu"]),
            libc = strings(o["libc"]),
            scripts = scripts,
            gypfile = o.bool("gypfile") == true,
            dependencies = deps(o.obj("dependencies")),
            optionalDependencies = deps(o.obj("optionalDependencies")),
            unpackedSize = o.obj("dist")?.long("unpackedSize"),
        )
    }

    /** `downloads` of api.npmjs.org's `/downloads/point/last-week/<name>`. */
    fun parseDownloads(text: String): Long? = parse(text)?.long("downloads")

    /** The paths (without the leading slash) in jsDelivr's flat file list (`?structure=flat`). */
    fun parseFiles(text: String): List<String> =
        (parse(text)?.get("files") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name")?.removePrefix("/") }

    /**
     * A repository as a browsable https URL: `git+https://github.com/o/r.git`, `git://`, `git@github.com:o/r.git`,
     * the `github:o/r` and `o/r` shorthands, or the object form's `url`. Null for anything else.
     */
    fun repositoryUrl(element: JsonElement?): String? {
        val raw = when (element) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content
            is JsonObject -> element.str("url")
            else -> null
        }?.trim() ?: return null
        val shorthand = Regex("^(github|gitlab|bitbucket):([A-Za-z0-9._-]+/[A-Za-z0-9._-]+)$").matchEntire(raw)
        if (shorthand != null) {
            val host = mapOf("github" to "github.com", "gitlab" to "gitlab.com", "bitbucket" to "bitbucket.org").getValue(shorthand.groupValues[1])
            return "https://$host/${shorthand.groupValues[2].removeSuffix(".git")}"
        }
        if (Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$").matches(raw)) return "https://github.com/${raw.removeSuffix(".git")}"
        val scp = Regex("^git@([A-Za-z0-9.-]+):(.+)$").matchEntire(raw)
        val url = when {
            scp != null -> "https://${scp.groupValues[1]}/${scp.groupValues[2]}"
            else -> raw.removePrefix("git+").replaceFirst(Regex("^(git|ssh|http)://(?:[^@/]+@)?"), "https://")
        }
        return url.takeIf { it.startsWith("https://") && it.length < 500 }?.removeSuffix("/")?.removeSuffix(".git")
    }

    /**
     * What the package adds, by type, from its `pi` manifest and, when known, its published [files]
     * (docs/packages.md, extensions.md, skills.md): declared files and globs as they are, declared directories (or,
     * without a manifest, the conventional `extensions/`, `skills/`, `prompts/` and `themes/`) by Pi's discovery rules:
     * `.ts`/`.js` files and folders with an `index.ts` as extensions, `SKILL.md` folders at any depth, `.md` prompts,
     * `.json` themes. Without the file list, the declared paths themselves.
     */
    fun resources(manifest: PackageManifest, files: List<String>?): List<AddedResource> {
        val pi = manifest.pi?.takeIf { !it.isEmpty }
        if (files == null) {
            pi ?: return emptyList()
            fun declared(kind: String, entries: List<String>) = entries.filter { !it.startsWith("!") && !it.startsWith("-") }
                .map { AddedResource(kind, it.removePrefix("+").removePrefix("./")) }
            return declared("extension", pi.extensions) + declared("skill", pi.skills) + declared("prompt", pi.prompts) + declared("theme", pi.themes)
        }
        val out = ArrayList<AddedResource>()
        for (kind in Kind.entries) {
            val entries = when (kind) {
                Kind.Extension -> pi?.extensions
                Kind.Skill -> pi?.skills
                Kind.Prompt -> pi?.prompts
                Kind.Theme -> pi?.themes
            } ?: if (pi == null) listOf(kind.dir) else emptyList()
            val found = LinkedHashSet<String>()
            for (entry in entries) {
                if (entry.startsWith("!") || entry.startsWith("-")) continue
                val e = entry.removePrefix("+").removePrefix("./").trimEnd('/')
                when {
                    '*' in e || '?' in e -> glob(e).let { rx -> files.filterTo(found) { rx.matches(it) && kind.accepts(it) } }
                    e in files -> found += e
                    else -> files.filterTo(found) { it.startsWith("$e/") && kind.discovers(it.removePrefix("$e/")) }
                }
            }
            found.map { kind.label(it) }.distinct().mapTo(out) { AddedResource(kind.id, it) }
        }
        return out
    }

    private enum class Kind(val id: String, val dir: String) {
        Extension("extension", "extensions"),
        Skill("skill", "skills"),
        Prompt("prompt", "prompts"),
        Theme("theme", "themes");

        fun accepts(path: String): Boolean = when (this) {
            Extension -> CODE.matches(path) && !path.endsWith(".d.ts")
            Skill -> path.endsWith(".md")
            Prompt -> path.endsWith(".md")
            Theme -> path.endsWith(".json")
        }

        /** [rel] (a path inside a declared or conventional directory) is a resource Pi discovers there. */
        fun discovers(rel: String): Boolean {
            val parts = rel.split('/')
            return when (this) {
                Extension -> accepts(rel) && (parts.size == 1 || parts.size == 2 && parts[1].substringBeforeLast('.') == "index")
                Skill -> parts.last() == "SKILL.md"
                Prompt -> parts.size == 1 && accepts(rel)
                Theme -> parts.size == 1 && accepts(rel)
            }
        }

        /** A directory extension and a skill go by their folder; other resources by their file name. */
        fun label(path: String): String {
            val file = path.substringAfterLast('/')
            return when (this) {
                Extension -> if (file.substringBeforeLast('.') == "index" && '/' in path) path.substringBeforeLast('/').substringAfterLast('/') else file
                Skill -> if (file == "SKILL.md") path.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { ROOT_LABEL } else file.removeSuffix(".md")
                Prompt -> file.removeSuffix(".md")
                Theme -> file.removeSuffix(".json")
            }
        }
    }

    private const val ROOT_LABEL = "(package root)"
    private val CODE = Regex(""".*\.(ts|js|mjs|cjs|mts|cts)$""")

    /** A package glob (`*` within a path segment, `**` across them) as a regex over whole paths. */
    internal fun glob(pattern: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.getOrNull(i + 1) == '*' -> {
                    sb.append(".*")
                    i++
                    if (pattern.getOrNull(i + 1) == '/') i++
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.toString())
    }

    private fun parse(text: String): JsonObject? = runCatching { ProtocolJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun strings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        is JsonPrimitive -> listOfNotNull(e.takeIf { it.isString }?.content)
        else -> emptyList()
    }

    private fun deps(o: JsonObject?): Map<String, String> =
        o.orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }.toMap()
}

/**
 * Pi packages online, read with plain HTTPS GETs and no cookies, tokens or extra headers: the npm registry's search
 * for the `pi-package` keyword (pi.dev/packages has no JSON API and lists the same packages), a version's package.json
 * from the registry, last week's downloads from api.npmjs.org and a version's file list from jsDelivr (for the
 * resources and the compatibility checks). A GitHub source's package.json comes from raw.githubusercontent.com.
 */
class PackageCatalog(
    private val http: OkHttpClient,
    private val registry: String = "https://registry.npmjs.org",
    private val downloadsApi: String = "https://api.npmjs.org",
    private val filesApi: String = "https://data.jsdelivr.com",
    private val githubRaw: String = "https://raw.githubusercontent.com",
) {
    /** Packages matching [query] (all of them, most downloaded first, when blank). */
    suspend fun search(query: String, from: Int = 0, size: Int = PAGE): CatalogPage {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && !it.contains(':') }.take(8)
        val url = "$registry/-/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("text", (listOf("keywords:pi-package") + words).joinToString(" "))
            .addQueryParameter("size", size.toString())
            .addQueryParameter("from", from.toString())
            .build()
        return CatalogJson.parseSearch(get(url.toString()) ?: throw CatalogException("No packages found."))
    }

    /** [name]'s package.json at [version] (the latest when null). */
    suspend fun manifest(name: String, version: String? = null): PackageManifest {
        val text = get("$registry/${name.replace("/", "%2F")}/${version ?: "latest"}") ?: throw CatalogException("$name is not on npm.")
        return CatalogJson.parseManifest(text)
    }

    suspend fun weeklyDownloads(name: String): Long? = runCatching { get("$downloadsApi/downloads/point/last-week/$name")?.let(CatalogJson::parseDownloads) }.getOrNull()

    /** The files of [name]@[version] as published, or null when the list is not available. */
    suspend fun files(name: String, version: String): List<String>? =
        runCatching { get("$filesApi/v1/packages/npm/$name@$version?structure=flat")?.let(CatalogJson::parseFiles) }.getOrNull()

    /** The package.json at the root of a GitHub repository ([ref], else its default branch). */
    suspend fun githubManifest(owner: String, repo: String, ref: String?): PackageManifest {
        val text = get("$githubRaw/$owner/$repo/${ref ?: "HEAD"}/package.json") ?: throw CatalogException("The repository has no package.json.")
        return CatalogJson.parseManifest(text)
    }

    /** The body of a 200 answer, null for 404, a [CatalogException] for anything else or no network. */
    private suspend fun get(url: String): String? {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return runInterruptible(Dispatchers.IO) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw CatalogException("Could not reach the package registry. Check the network and try again.")
            }
            response.use { r ->
                when {
                    r.isSuccessful -> r.body.string().takeIf { it.length <= MAX_BODY }
                    r.code == 404 -> null
                    r.code == 429 -> throw CatalogException("The package registry is busy. Try again in a minute.")
                    else -> throw CatalogException("The package registry answered ${r.code}.")
                }
            }
        }
    }

    companion object {
        const val PAGE = 20
        private const val MAX_BODY = 8 * 1024 * 1024
    }
}
