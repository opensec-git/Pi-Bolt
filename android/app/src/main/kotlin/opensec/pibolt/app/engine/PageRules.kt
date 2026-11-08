package opensec.pibolt.app.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.PageInfo
import opensec.pibolt.protocol.str
import java.io.File
import java.security.MessageDigest

/**
 * When the page engine ([PageEngine]) runs (docs/mobile-ui.md#package-pages-and-home-tiles): while something holds it
 * (an open page, Home with tiles on screen, a refresh of the page list), at most one, never started while the phone is
 * low on memory, and stopped [STOP_DELAY_MS] after the last hold ended.
 */
object PageRules {
    const val STOP_DELAY_MS = 30_000L

    enum class Decision {
        /** Held and not running: start it. */
        Start,

        /** Held, not running, and the phone is low on memory: do not start; say so. */
        Refuse,

        /** Held and running: keep it (cancel a pending stop). */
        Keep,

        /** Running and no longer held: stop it after [STOP_DELAY_MS] unless held again. */
        StopLater,

        /** Neither held nor running. */
        Idle,
    }

    fun decide(holds: Int, running: Boolean, lowMemory: Boolean): Decision = when {
        holds > 0 && running -> Decision.Keep
        holds > 0 && lowMemory -> Decision.Refuse
        holds > 0 -> Decision.Start
        running -> Decision.StopLater
        else -> Decision.Idle
    }

    /** Memory pressure: a page engine that only serves Home tiles stops at once; an open page keeps it unless [critical]. */
    fun stopsOnTrim(holders: Set<String>, critical: Boolean): Boolean = critical || holders.none { it.startsWith(HOLD_PAGE) }

    const val HOLD_PAGE = "page:"
    const val HOLD_HOME = "home"
    const val HOLD_LIST = "list"
}

/**
 * The page list kept between page-engine starts: what `get_pages` said, and for which packages (a hash of Pi's
 * settings `packages` and `extensions` and the engine version), so it is read again when the packages change.
 */
object PageCatalog {
    data class Cached(val key: String, val pages: List<PageInfo>)

    /** The key of an installed package set. [settings] is Pi's settings.json (null when unreadable). */
    fun key(settings: JsonObject?, engineVersion: String, installed: String = ""): String {
        val text = buildString {
            // What is installed now: an update in place changes no setting, only the installed files.
            append(installed)
            append('\n')
            append(settings?.get("packages")?.toString().orEmpty())
            append('\n')
            append(settings?.get("extensions")?.toString().orEmpty())
            append('\n')
            // Pi-Bolt's own package list, merged into what the engine loads (SettingsManager.resolvePackages).
            append((settings?.get("piBolt") as? JsonObject)?.get("packages")?.toString().orEmpty())
            append('\n')
            append(engineVersion)
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun encode(cached: Cached): String = buildJsonObject {
        put("version", 1)
        put("key", cached.key)
        put(
            "pages",
            buildJsonArray {
                for (p in cached.pages) {
                    add(
                        buildJsonObject {
                            put("key", p.key)
                            put("kind", p.kind)
                            put("title", p.title)
                            p.icon?.let { put("icon", it) }
                            p.description?.let { put("description", it) }
                            p.page?.let { put("page", it) }
                            if (p.source != null || p.path != null) {
                                put(
                                    "sourceInfo",
                                    buildJsonObject {
                                        p.source?.let { put("source", it) }
                                        p.path?.let { put("path", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }.toString()

    fun decode(text: String?): Cached? {
        val json = text?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        val key = json.str("key") ?: return null
        return Cached(key, MobileViews.pages(json))
    }

    /** The cache, when it was made for the package set [key]. */
    fun valid(cached: Cached?, key: String): List<PageInfo>? = cached?.takeIf { it.key == key }?.pages

    /**
     * [pages] that belong to a package: named by its [source] (`sourceInfo.source`), or loaded from inside its
     * installed directory [dir] (`sourceInfo.baseDir` or `path`).
     */
    fun ofPackage(pages: List<PageInfo>, source: String, dir: String?): List<PageInfo> {
        val root = dir?.trimEnd('/')
        return pages.filter { p ->
            val path = p.path
            p.source == source || (root != null && path != null && (path == root || path.startsWith("$root/")))
        }
    }

    /**
     * A stamp of the packages installed in [agentDir], as Pi's package manager lays them out: the npm root's lockfile
     * (rewritten by every install and update) and each git checkout's index (rewritten when a pull changes its files).
     */
    fun installStamp(agentDir: File): String = buildString {
        val lock = File(agentDir, "npm/package-lock.json")
        if (lock.isFile) append("npm ${lock.lastModified()} ${lock.length()}\n")
        File(agentDir, "git").walkTopDown().maxDepth(6).onEnter { it.name != "node_modules" }
            .filter { it.isDirectory && it.name == ".git" }
            .map { File(it, "index") }
            .filter { it.isFile }
            .sortedBy { it.path }
            .forEach { append("git ${it.parentFile.parentFile.relativeTo(agentDir).path} ${it.lastModified()} ${it.length()}\n") }
    }

    /** Opted-in tile keys that still exist, in the order [pages] lists them. */
    fun shownTiles(pages: List<PageInfo>, optedIn: Set<String>): List<PageInfo> = pages.filter { it.isTile && it.key in optedIn }

    /**
     * The opted-in tiles after turning [key] on or off, keeping only tiles [pages] still has: tiles of removed packages
     * drop out instead of holding Home's slots.
     */
    fun toggleTile(pages: List<PageInfo>, optedIn: Set<String>, key: String, on: Boolean): Set<String> {
        val kept = shownTiles(pages, optedIn).map { it.key }.toSet()
        return if (on) kept + key else kept - key
    }
}
