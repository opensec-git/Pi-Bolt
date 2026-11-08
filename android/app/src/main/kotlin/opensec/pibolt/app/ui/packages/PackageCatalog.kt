package opensec.pibolt.app.ui.packages

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
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
data class CatalogPage(
    val entries: List<CatalogEntry>,
    /** The registry's total for the query (before any scope filter). */
    val total: Int,
    /** Where the next page starts in the registry's results (they can be more than [entries] after a filter). */
    val nextFrom: Int = entries.size,
) {
    val hasMore: Boolean get() = nextFrom < total
}

/** The resources a package declares under `pi` in its package.json (docs/packages.md). */
@Immutable
data class PiManifest(
    /** Null when the field is absent; `[]` is present and loads none (package-manager.ts hasManifestResourceFields). */
    val extensions: List<String>? = null,
    val skills: List<String>? = null,
    val prompts: List<String>? = null,
    val themes: List<String>? = null,
) {
    /** No resource field at all: Pi then discovers the conventional directories. */
    val isEmpty: Boolean get() = extensions == null && skills == null && prompts == null && themes == null
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

/** A catalog call that failed, with a message for the user; [status] is the HTTP code (0 without an answer). */
class CatalogException(message: String, val status: Int = 0) : Exception(message)

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
                description = text(pkg.str("description")),
                weeklyDownloads = obj.obj("downloads")?.long("weekly"),
                repository = repositoryUrl(pkg.obj("links")?.get("repository")),
                keywords = strings(pkg["keywords"]),
            )
        }
        val objects = (root["objects"] as? JsonArray)?.size ?: 0
        return CatalogPage(entries, root.long("total")?.toInt() ?: entries.size, nextFrom = objects)
    }

    fun parseManifest(text: String): PackageManifest {
        val o = parse(text) ?: throw CatalogException("The package registry sent an unexpected answer.")
        val name = o.str("name") ?: throw CatalogException("The package has no name.")
        val pi = o.obj("pi")?.let {
            PiManifest(it["extensions"]?.let(::strings), it["skills"]?.let(::strings), it["prompts"]?.let(::strings), it["themes"]?.let(::strings))
        }
        val scripts = o.obj("scripts").orEmpty().entries.asSequence().take(MAX_MAP)
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k.take(MAX_SHORT) to it.take(MAX_SHORT) } }.toMap()
        return PackageManifest(
            name = name,
            version = o.str("version"),
            description = text(o.str("description")),
            license = text(o.str("license") ?: o.obj("license")?.str("type"), MAX_SHORT),
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
            fun declared(kind: String, entries: List<String>) =
                entries.take(MAX_ENTRIES).filterNot { isOverride(it) || it.length > MAX_GLOB }.map { AddedResource(kind, it.removePrefix("./")) }
            return declared("extension", pi.extensions.orEmpty()) + declared("skill", pi.skills.orEmpty()) +
                declared("prompt", pi.prompts.orEmpty()) + declared("theme", pi.themes.orEmpty())
        }
        val out = ArrayList<AddedResource>()
        val budget = Budget()
        val tree = Tree(files)
        // A file tree too large to index (a hostile or huge package): show the declared paths instead.
        if (tree.overflow) return resources(manifest, null)
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
                if (isOverride(entry) || entry.length > MAX_GLOB) continue
                val e = entry.removePrefix("./").trimEnd('/')
                when {
                    budget.spent -> break
                    isGlob(e) -> {
                        tree.files.filterTo(found) { kind.accepts(it) && visible(it) && globMatches(e, it, budget) }
                        for (dir in tree.dirs) if (visible(dir) && globMatches(e, dir, budget)) found += kind.discover(tree, dir)
                    }
                    e in tree.fileSet -> found += e
                    e in tree.dirs || e.isEmpty() -> kind.discover(tree, e).let { list -> if (budget.spend(list.size.toLong() + 1)) found += list }
                }
            }
            val enabled = applyOverrides(found.toList(), bounded.filter { isOverride(it) && it.length <= MAX_GLOB }, budget)
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

        /** More directories (or characters of them) than [MAX_DIRS] / [MAX_DIR_CHARS]: the index stopped, no preview. */
        var overflow = false
            private set

        init {
            var chars = 0L
            for (f in files) {
                if (overflow) break
                val parent = f.substringBeforeLast('/', "")
                filesIn.getOrPut(parent) { ArrayList() } += f
                var d = parent
                while (d.isNotEmpty() && d !in dirs) {
                    chars += d.length
                    if (dirs.size >= MAX_DIRS || chars > MAX_DIR_CHARS) {
                        overflow = true
                        break
                    }
                    dirs.add(d)
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
        // `{a,b}` alternatives (Node's globSync expands them): each one is matched, at most MAX_BRACES of them.
        if ('{' in pattern) return expandBraces(pattern)?.any { globMatches(it, path, budget) } ?: false
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

    /** An entry Pi expands with globSync (package-manager.ts hasGlobPattern): one with `*` or `?`; others are paths. */
    internal fun isGlob(entry: String): Boolean = entry.any { it == '*' || it == '?' }

    /**
     * [pattern] with its `{a,b}` groups expanded (nested groups too), or null when it would make more than [MAX_BRACES]
     * patterns or a brace is unbalanced (then nothing matches, as the bounded preview cannot say).
     */
    internal fun expandBraces(pattern: String): List<String>? {
        val open = pattern.indexOf('{')
        if (open < 0) return listOf(pattern)
        var depth = 0
        var close = -1
        val cuts = ArrayList<Int>()
        for (i in open until pattern.length) {
            when (pattern[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { close = i; break }
                ',' -> if (depth == 1) cuts += i
            }
        }
        if (close < 0) return null
        val bounds = listOf(open) + cuts + listOf(close)
        val out = ArrayList<String>()
        for (k in 0 until bounds.size - 1) {
            val alt = pattern.substring(bounds[k] + 1, bounds[k + 1])
            val rest = expandBraces(pattern.substring(0, open) + alt + pattern.substring(close + 1)) ?: return null
            out += rest
            if (out.size > MAX_BRACES) return null
        }
        return out
    }

    /** One pattern element within a segment: a literal, `?`, `*` or a `[...]` class. */
    private sealed interface Tok {
        data class Lit(val c: Char) : Tok

        data object One : Tok

        data object Star : Tok

        data class Cls(val negated: Boolean, val ranges: List<CharRange>) : Tok
    }

    private fun tokens(p: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' -> if (out.lastOrNull() != Tok.Star) out += Tok.Star
                c == '?' -> out += Tok.One
                c == '[' && p.indexOf(']', i + 2) > i -> {
                    val end = p.indexOf(']', i + 2)
                    var body = p.substring(i + 1, end)
                    val negated = body.startsWith("!") || body.startsWith("^")
                    if (negated) body = body.drop(1)
                    val ranges = ArrayList<CharRange>()
                    var k = 0
                    while (k < body.length) {
                        if (k + 2 < body.length && body[k + 1] == '-') { ranges += body[k]..body[k + 2]; k += 3 } else { ranges += body[k]..body[k]; k++ }
                    }
                    out += Tok.Cls(negated, ranges)
                    i = end
                }
                else -> out += Tok.Lit(c)
            }
            i++
        }
        return out
    }

    private fun Tok.matches(c: Char): Boolean = when (this) {
        is Tok.Lit -> this.c == c
        Tok.One -> true
        Tok.Star -> false
        is Tok.Cls -> ranges.any { c in it } != negated
    }

    private fun segmentMatches(pattern: String, s: String, budget: Budget): Boolean {
        val p = tokens(pattern)
        var i = 0
        var j = 0
        var star = -1
        var mark = 0
        while (j < s.length) {
            if (!budget.spend(1)) return false
            when {
                i < p.size && p[i] != Tok.Star && p[i].matches(s[j]) -> { i++; j++ }
                i < p.size && p[i] == Tok.Star -> { star = i; mark = j; i++ }
                star >= 0 -> { i = star + 1; mark++; j = mark }
                else -> return false
            }
        }
        while (i < p.size && p[i] == Tok.Star) i++
        return i == p.size
    }

    private const val MAX_GLOB = 300
    private const val MAX_BRACES = 32
    private const val MAX_DIRS = 5_000
    private const val MAX_DIR_CHARS = 1_000_000L
    private const val MAX_PATH = 1000
    private const val MAX_SEGMENTS = 64

    /** Real package globs (a directory and a file pattern) are one to three segments deep. */
    private const val MAX_GLOB_SEGMENTS = 8
    private const val GLOB_BUDGET = 2_000_000L

    /** At most this many declared entries per resource type and files are considered. */
    private const val MAX_ENTRIES = 32
    private const val MAX_FILES = 10_000

    private fun parse(text: String): JsonObject? = runCatching { ProtocolJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /**
     * Author-written arrays (`pi.*`, keywords, os/cpu/libc) and maps (scripts, dependencies) are capped where they are
     * read, so a huge one is never copied: [MAX_LIST] strings, [MAX_MAP] entries, [MAX_SHORT] characters a value.
     */
    private fun strings(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.asSequence().take(MAX_LIST).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.take(MAX_SHORT) }.toList()
        is JsonPrimitive -> listOfNotNull(e.takeIf { it.isString }?.content?.take(MAX_SHORT))
        else -> emptyList()
    }

    private fun deps(o: JsonObject?): Map<String, String> =
        o.orEmpty().entries.asSequence().take(MAX_MAP).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k.take(MAX_SHORT) to it.take(MAX_SHORT) } }.toMap()

    /** A display string from a package: trimmed, at most [max] characters, null when empty. */
    private fun text(s: String?, max: Int = MAX_TEXT): String? = s?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    private const val MAX_LIST = 64
    private const val MAX_MAP = 500
    private const val MAX_SHORT = 300
    private const val MAX_TEXT = 500
}

/**
 * Pi packages online, read with plain HTTPS GETs and no cookies, tokens or extra headers: the npm registry's search
 * for the `pi-package` keyword (pi.dev/packages has no JSON API and lists the same packages), a version's package.json
 * from the registry, last week's downloads from api.npmjs.org and a version's file list from jsDelivr (for the
 * resources and the compatibility checks). A GitHub source's package.json comes from raw.githubusercontent.com.
 * For a private registry ([registry] with [authToken]) the token goes, as a Bearer header, to that registry's URLs
 * only; [private] catalogs search without the keyword filter and never ask the public services about their packages.
 */
class PackageCatalog(
    client: OkHttpClient,
    private val registry: String = "https://registry.npmjs.org",
    private val authToken: String? = null,
    val private: Boolean = false,
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

    /**
     * Packages matching [query] (all of them, most downloaded first, when blank). In a [private] catalog: every package
     * of [scope] matching [query] (no keyword filter: private packages rarely carry it); a registry without npm's
     * search API answers with [NO_SEARCH].
     */
    suspend fun search(query: String, from: Int = 0, size: Int = PAGE, scope: String? = null): CatalogPage {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && !it.contains(':') }.take(8)
        val terms = if (private) words.ifEmpty { listOfNotNull(scope?.removePrefix("@")) } else listOf("keywords:pi-package") + words
        val url = "$registry/-/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("text", terms.joinToString(" "))
            .addQueryParameter("size", size.toString())
            .addQueryParameter("from", from.toString())
            .build()
        val text = try {
            get(url.toString())
        } catch (e: CatalogException) {
            if (private && e.status in setOf(400, 405, 501)) throw CatalogException(NO_SEARCH, e.status) else throw e
        } ?: throw CatalogException(if (private) NO_SEARCH else "No packages found.", 404)
        val page = withContext(Dispatchers.Default) { CatalogJson.parseSearch(text) }.let { it.copy(nextFrom = from + it.nextFrom) }
        if (!private || scope == null) return page
        // Other scopes are dropped, but the paging goes on from the registry's own offset.
        return page.copy(entries = page.entries.filter { it.name.startsWith("$scope/") })
    }

    /** [name]'s package.json at [version] (the latest when null). */
    suspend fun manifest(name: String, version: String? = null): PackageManifest {
        // The registry takes the scoped name as one segment (`@scope%2Fname`) and an exact version or a dist-tag.
        val url = registry.toHttpUrl().newBuilder().addPathSegment(name).addPathSegment(version ?: "latest").build()
        val text = get(url.toString()) ?: throw CatalogException(if (version == null) "$name is not on npm." else "$name@$version is not on npm.")
        return withContext(Dispatchers.Default) { CatalogJson.parseManifest(text) }
    }

    suspend fun weeklyDownloads(name: String): Long? =
        runCatching { get("$downloadsApi/downloads/point/last-week/$name")?.let { withContext(Dispatchers.Default) { CatalogJson.parseDownloads(it) } } }.getOrNull()

    /** The files of [name]@[version] as published, or null when the list is not available. */
    suspend fun files(name: String, version: String): List<String>? =
        runCatching { get("$filesApi/v1/packages/npm/$name@$version?structure=flat")?.let { withContext(Dispatchers.Default) { CatalogJson.parseFiles(it) } } }.getOrNull()

    /** The package.json at the root of a GitHub repository ([ref], else its default branch). */
    suspend fun githubManifest(owner: String, repo: String, ref: String?): PackageManifest {
        val text = get("$githubRaw/$owner/$repo/${ref ?: "HEAD"}/package.json") ?: throw CatalogException("The repository has no package.json.")
        return withContext(Dispatchers.Default) { CatalogJson.parseManifest(text) }
    }

    /** The body of a 200 answer, null for 404, a [CatalogException] for anything else or no network. */
    private suspend fun get(url: String): String? {
        val target = url.toHttpUrl()
        val builder = Request.Builder().url(target).header("Accept", "application/json").get()
        if (authToken != null && inRegistry(target)) builder.header("Authorization", "Bearer $authToken")
        val request = builder.build()
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
                    r.code == 401 || r.code == 403 -> throw CatalogException("The registry refused the token (${r.code}). Check it under Advanced.", r.code)
                    r.code == 429 -> throw CatalogException("The package registry is busy. Try again in a minute.", r.code)
                    else -> throw CatalogException("The package registry answered ${r.code}.", r.code)
                }
            }
        }
    }

    private val registryUrl = registry.trimEnd('/').toHttpUrl()

    /** [url] is the registry's (same scheme, host and port, and under its path), compared parsed, not as text. */
    private fun inRegistry(url: HttpUrl): Boolean {
        if (url.scheme != registryUrl.scheme || url.host != registryUrl.host || url.port != registryUrl.port) return false
        val base = registryUrl.encodedPath.trimEnd('/')
        return base.isEmpty() || url.encodedPath == base || url.encodedPath.startsWith("$base/")
    }

    companion object {
        const val PAGE = 20
        const val NO_SEARCH = "This registry has no search. Install its packages by name under Installed."
        private const val MAX_BODY = 4L * 1024 * 1024
    }
}
