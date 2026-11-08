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
import java.util.concurrent.TimeUnit

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
        (parse(text)?.get("files") as? JsonArray).orEmpty().asSequence().take(MAX_FILES).mapNotNull { (it as? JsonObject)?.str("name")?.removePrefix("/") }.toList()

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
     * (docs/packages.md), the way Pi's package manager collects them (package-manager.ts): declared files as they
     * are; declared directories, directories a glob matches, or without a manifest the conventional `extensions/`,
     * `skills/`, `prompts/` and `themes/`, by `collectResourceFiles`: extensions are the directory's own `index.ts`
     * (or `index.js`), else its `.ts`/`.js` files and its folders with an index; skills are a folder's `SKILL.md`
     * (not looking deeper) or `.md` files at the directory's top, at any depth; prompts and themes are every `.md` /
     * `.json` below it. Dot folders and `node_modules` are skipped. Then the manifest's overrides, as Pi's package manager applies them (`addManifestEntries`):
     * `!pattern` excludes by glob (the path, its name, or a skill's folder), `+path` puts an excluded path back and
     * `-path` removes one, both exact. Without the file list, the declared paths themselves.
     */
    fun resources(manifest: PackageManifest, files: List<String>?): List<AddedResource> {
        val pi = manifest.pi?.takeIf { !it.isEmpty }
        if (files == null) {
            pi ?: return emptyList()
            fun declared(kind: String, entries: List<String>) = entries.filterNot(::isOverride).map { AddedResource(kind, it.removePrefix("./")) }
            return declared("extension", pi.extensions) + declared("skill", pi.skills) + declared("prompt", pi.prompts) + declared("theme", pi.themes)
        }
        val out = ArrayList<AddedResource>()
        val budget = Budget()
        val tree = Tree(files)
        for (kind in Kind.entries) {
            val entries = when (kind) {
                Kind.Extension -> pi?.extensions
                Kind.Skill -> pi?.skills
                Kind.Prompt -> pi?.prompts
                Kind.Theme -> pi?.themes
            } ?: if (pi == null) listOf(kind.dir) else emptyList()
            val found = LinkedHashSet<String>()
            val bounded = entries.take(MAX_ENTRIES)
            for (entry in bounded) {
                if (isOverride(entry)) continue
                val e = entry.removePrefix("./").trimEnd('/')
                when {
                    budget.spent -> break
                    '*' in e || '?' in e -> {
                        tree.files.filterTo(found) { kind.accepts(it) && visible(it) && globMatches(e, it, budget) }
                        for (dir in tree.dirs) if (visible(dir) && globMatches(e, dir, budget)) found += kind.discover(tree, dir)
                    }
                    e in tree.fileSet -> found += e
                    e in tree.dirs || e.isEmpty() -> found += kind.discover(tree, e)
                }
            }
            val enabled = applyOverrides(found.toList(), bounded.filter(::isOverride), budget)
            enabled.map { kind.label(it) }.distinct().mapTo(out) { AddedResource(kind.id, it) }
        }
        return out
    }

    private fun isOverride(entry: String): Boolean = entry.startsWith("!") || entry.startsWith("+") || entry.startsWith("-")

    /** [paths] after `!glob` exclusions, `+path` re-inclusions and `-path` exclusions (package-manager.ts applyPatterns). */
    private fun applyOverrides(paths: List<String>, overrides: List<String>, budget: Budget): List<String> {
        if (overrides.isEmpty()) return paths
        fun norm(p: String) = p.removePrefix("./").trimEnd('/')
        val excludes = overrides.filter { it.startsWith("!") }.map { norm(it.drop(1)) }
        val forceIn = overrides.filter { it.startsWith("+") }.map { norm(it.drop(1)) }.toSet()
        val forceOut = overrides.filter { it.startsWith("-") }.map { norm(it.drop(1)) }.toSet()
        // A skill is also matched by its folder (its path and its name).
        fun names(path: String): List<String> {
            val own = listOf(path, path.substringAfterLast('/'))
            if (path.substringAfterLast('/') != "SKILL.md" || '/' !in path) return own
            val dir = path.substringBeforeLast('/')
            return own + listOf(dir, dir.substringAfterLast('/'))
        }
        fun exact(path: String, set: Set<String>) = path in set || path.substringAfterLast('/') == "SKILL.md" && path.substringBeforeLast('/', "") in set
        return paths.filter { path ->
            var on = excludes.none { pattern -> names(path).any { globMatches(pattern, it, budget) } }
            if (exact(path, forceIn)) on = true
            if (exact(path, forceOut)) on = false
            on
        }
    }

    /** No segment hidden (Pi's globs and directory walks skip dot paths). */
    private fun visible(path: String): Boolean = path.split('/').none { it.startsWith(".") }

    /** The published files as directories: each directory's own files and subdirectories ("" is the root). */
    private class Tree(published: List<String>) {
        val files: List<String> = published.filter { it.length <= MAX_PATH && it.count { c -> c == '/' } < MAX_SEGMENTS }
        val fileSet: Set<String> = files.toHashSet()
        val dirs = LinkedHashSet<String>()
        val filesIn = HashMap<String, MutableList<String>>()
        val dirsIn = HashMap<String, MutableList<String>>()

        init {
            for (f in files) {
                val parent = f.substringBeforeLast('/', "")
                filesIn.getOrPut(parent) { ArrayList() } += f
                var d = parent
                while (d.isNotEmpty() && dirs.add(d)) {
                    val up = d.substringBeforeLast('/', "")
                    dirsIn.getOrPut(up) { ArrayList() } += d
                    d = up
                }
            }
        }

        fun name(path: String) = path.substringAfterLast('/')

        /** Subdirectories Pi walks into: not hidden, not node_modules. */
        fun walkable(dir: String): List<String> = dirsIn[dir].orEmpty().filter { name(it) != "node_modules" && !name(it).startsWith(".") }

        fun own(dir: String): List<String> = filesIn[dir].orEmpty().filter { !name(it).startsWith(".") }

        fun below(dir: String): List<String> = own(dir) + walkable(dir).flatMap { below(it) }
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

        /** What Pi collects from the directory [dir] for this type (package-manager.ts `collectResourceFiles`). */
        fun discover(tree: Tree, dir: String): List<String> = when (this) {
            Extension -> index(tree, dir)?.let(::listOf) ?: (
                tree.own(dir).filter { it.endsWith(".ts") || it.endsWith(".js") }.filter { accepts(it) } +
                    tree.walkable(dir).mapNotNull { index(tree, it) }
                )
            Skill -> skills(tree, dir, root = true)
            Prompt, Theme -> tree.below(dir).filter { accepts(it) }
        }

        private fun index(tree: Tree, dir: String): String? {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            return listOf("${prefix}index.ts", "${prefix}index.js").firstOrNull { it in tree.fileSet }
        }

        private fun skills(tree: Tree, dir: String, root: Boolean): List<String> {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            if ("${prefix}SKILL.md" in tree.fileSet) return listOf("${prefix}SKILL.md")
            val loose = if (root) tree.own(dir).filter { it.endsWith(".md") } else emptyList()
            return loose + tree.walkable(dir).flatMap { skills(tree, it, root = false) }
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

    /**
     * Whether [path] matches a package glob: `*` and `?` within one path segment, a `**` segment across any number of
     * segments. The manifest and file list are untrusted, so this is no regex: a lazily memoised walk over segments
     * (the pattern at most [MAX_GLOB_SEGMENTS] deep) and a two-pointer match within a segment. Every step is charged to
     * [budget]; once it is spent, nothing more matches, so one `resources` call does a bounded amount of work.
     */
    internal fun globMatches(pattern: String, path: String, budget: Budget = Budget()): Boolean {
        if (pattern.length > MAX_GLOB || path.length > MAX_PATH) return false
        val p = pattern.split('/')
        val s = path.split('/')
        if (p.size > MAX_GLOB_SEGMENTS || s.size > MAX_SEGMENTS) return false
        val memo = HashMap<Int, Boolean>()
        fun match(i: Int, j: Int): Boolean {
            val key = i * (s.size + 1) + j
            memo[key]?.let { return it }
            if (!budget.spend(1)) return false
            val result = when {
                i == p.size -> j == s.size
                p[i] == "**" -> match(i + 1, j) || (j < s.size && match(i, j + 1))
                j == s.size -> false
                else -> segmentMatches(p[i], s[j], budget) && match(i + 1, j + 1)
            }
            memo[key] = result
            return result
        }
        return match(0, 0)
    }

    /** The work one `resources` call may do (glob steps); a hostile package cannot make it longer. */
    class Budget(private var left: Long = GLOB_BUDGET) {
        val spent: Boolean get() = left <= 0

        fun spend(n: Long): Boolean {
            left -= n
            return left > 0
        }
    }

    private fun segmentMatches(p: String, s: String, budget: Budget): Boolean {
        var i = 0
        var j = 0
        var star = -1
        var mark = 0
        while (j < s.length) {
            if (!budget.spend(1)) return false
            when {
                i < p.length && (p[i] == '?' || p[i] == s[j]) -> { i++; j++ }
                i < p.length && p[i] == '*' -> { star = i; mark = j; i++ }
                star >= 0 -> { i = star + 1; mark++; j = mark }
                else -> return false
            }
        }
        while (i < p.length && p[i] == '*') i++
        return i == p.length
    }

    private const val MAX_GLOB = 300
    private const val MAX_PATH = 1000
    private const val MAX_SEGMENTS = 64

    /** Real package globs (a directory and a file pattern) are one to three segments deep. */
    private const val MAX_GLOB_SEGMENTS = 8
    private const val GLOB_BUDGET = 2_000_000L

    /** At most this many declared entries per resource type and files are considered. */
    private const val MAX_ENTRIES = 32
    private const val MAX_FILES = 10_000

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
    client: OkHttpClient,
    private val registry: String = "https://registry.npmjs.org",
    private val downloadsApi: String = "https://api.npmjs.org",
    private val filesApi: String = "https://data.jsdelivr.com",
    private val githubRaw: String = "https://raw.githubusercontent.com",
    readTimeoutMs: Long = 20_000,
) {
    // The app's shared client has no read timeout (remote sessions stream); a stalled registry must reach an error.
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

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
        // The registry takes the scoped name as one segment (`@scope%2Fname`) and an exact version or a dist-tag.
        val url = registry.toHttpUrl().newBuilder().addPathSegment(name).addPathSegment(version ?: "latest").build()
        val text = get(url.toString()) ?: throw CatalogException(if (version == null) "$name is not on npm." else "$name@$version is not on npm.")
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
                    // At most MAX_BODY bytes are read; a longer answer is cut and then fails to parse.
                    r.isSuccessful -> r.peekBody(MAX_BODY).string()
                    r.code == 404 -> null
                    r.code == 429 -> throw CatalogException("The package registry is busy. Try again in a minute.")
                    else -> throw CatalogException("The package registry answered ${r.code}.")
                }
            }
        }
    }

    companion object {
        const val PAGE = 20
        private const val MAX_BODY = 8L * 1024 * 1024
    }
}
