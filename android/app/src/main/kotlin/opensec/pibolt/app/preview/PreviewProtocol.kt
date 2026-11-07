package opensec.pibolt.app.preview

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.net.URI

/** What the engine's preview tools ask the app for (assets/extensions/pibolt-preview.js, one JSON line each). */
sealed interface PreviewCommand {
    data class Open(val url: String?, val reload: Boolean) : PreviewCommand

    data object Screenshot : PreviewCommand

    data class Console(val since: Long?, val errorsOnly: Boolean) : PreviewCommand

    /** By [selector], or at [x], [y] pixels of the last screenshot. */
    data class Click(val selector: String?, val x: Double?, val y: Double?) : PreviewCommand

    data class Type(val selector: String, val text: String, val submit: Boolean) : PreviewCommand

    data class Scroll(val selector: String?, val dy: Double?, val to: String?) : PreviewCommand

    data class Eval(val js: String) : PreviewCommand
}

/** One request: the calling session's working directory and the command. */
data class PreviewRequest(val cwd: String, val command: PreviewCommand)

/** The answer: model-facing [text], or an [error]; a screenshot as base64 PNG in [image]. */
data class PreviewReply(val ok: Boolean, val text: String? = null, val error: String? = null, val image: String? = null, val details: JsonObject? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", ok)
        text?.let { put("text", it) }
        error?.let { put("error", it) }
        image?.let { data -> put("image", buildJsonObject { put("data", data); put("mimeType", "image/png") }) }
        details?.let { put("details", it) }
    }

    companion object {
        fun error(message: String) = PreviewReply(ok = false, error = message)

        fun text(message: String, details: JsonObject? = null) = PreviewReply(ok = true, text = message, details = details)
    }
}

/**
 * Parses and checks a request line. The extension validates first; the app checks again, since anything running as the
 * app's uid can reach the socket.
 */
object PreviewRequests {
    const val MAX_SELECTOR = 1_000
    const val MAX_TEXT = 10_000
    const val MAX_JS = 20_000
    const val MAX_URL = 2_000

    fun parse(line: String): Result<PreviewRequest> = runCatching {
        val json = ProtocolJson.parseToJsonElement(line) as? JsonObject ?: fail("not a JSON object")
        val cwd = json.str("cwd")?.takeIf { it.startsWith("/") } ?: fail("cwd is missing")
        val args = json.obj("args") ?: JsonObject(emptyMap())
        PreviewRequest(cwd, command(json.str("op") ?: fail("op is missing"), args))
    }

    private fun command(op: String, args: JsonObject): PreviewCommand = when (op) {
        "open" -> PreviewCommand.Open(args.str("url")?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_URL) fail("url is too long") }, args.bool("reload") == true)
        "screenshot" -> PreviewCommand.Screenshot
        "console" -> PreviewCommand.Console(
            args.double("since")?.also { if (!it.isFinite() || it < 0) fail("since must be a cursor of 0 or more") }?.toLong(),
            args.bool("errorsOnly") == true,
        )
        "click" -> {
            val selector = selector(args)
            val x = args.double("x")
            val y = args.double("y")
            when {
                selector != null && (x != null || y != null) -> fail("give either selector or x and y, not both")
                selector != null -> PreviewCommand.Click(selector, null, null)
                x == null || y == null -> fail("give a CSS selector, or x and y from the last screenshot")
                !x.isFinite() || !y.isFinite() || x < 0 || y < 0 -> fail("x and y must be pixels of 0 or more")
                else -> PreviewCommand.Click(null, x, y)
            }
        }
        "type" -> {
            val selector = selector(args) ?: fail("selector is required")
            val text = args.str("text") ?: fail("text is required")
            if (text.length > MAX_TEXT) fail("text is too long")
            PreviewCommand.Type(selector, text, args.bool("submit") == true)
        }
        "scroll" -> {
            val selector = selector(args)
            val to = args.str("to")?.also { if (it != "top" && it != "bottom") fail("to must be top or bottom") }
            val dy = args.double("dy")?.also { if (!it.isFinite()) fail("dy must be a number") }
            if (selector == null && to == null && dy == null) fail("give a selector, dy or to")
            PreviewCommand.Scroll(selector, dy, to)
        }
        "eval" -> {
            val js = args.str("js")?.takeIf { it.isNotBlank() } ?: fail("js is required")
            if (js.length > MAX_JS) fail("js is too long")
            PreviewCommand.Eval(js)
        }
        else -> fail("unknown op $op")
    }

    private fun selector(args: JsonObject): String? =
        args.str("selector")?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_SELECTOR) fail("selector is too long") }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}

/**
 * Which pages the preview may show: plain http on this phone's loopback (localhost or 127.0.0.1). Pi's tools may only
 * open a port the app detected as a dev server of its own processes ([resolve]); the user's own taps on links stay
 * within loopback too.
 */
object PreviewOrigins {
    fun isLoopbackHttp(url: String?): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("http", ignoreCase = true) && uri.rawUserInfo == null && uri.host?.lowercase() in LOOPBACK
    }

    /** The port of a loopback http [url] (80 when it names none), else null. */
    fun portOf(url: String?): Int? {
        if (!isLoopbackHttp(url)) return null
        val port = parse(url)?.port ?: return null
        return if (port == -1) 80 else port
    }

    /** True for a loopback http [url] on one of [ports]. */
    fun allowed(url: String?, ports: Collection<Int>): Boolean = portOf(url)?.let { it in ports } == true

    sealed interface Target {
        data class Url(val url: String) : Target

        data class Refused(val message: String) : Target
    }

    /**
     * Where `preview_open` goes: [target] (an absolute loopback URL, or a path on the open page's origin), else the
     * page already open, else the first detected dev server. [ports] are the dev servers detected now (with [target]'s
     * port probed too) plus the open page's.
     */
    fun resolve(target: String?, current: String?, ports: List<Int>): Target {
        val open = current?.takeIf { allowed(it, ports) }
        val base = open ?: ports.firstOrNull()?.let { "http://localhost:$it/" }
        val running = if (ports.isEmpty()) "none detected" else ports.joinToString(", ") { "localhost:$it" }
        if (target.isNullOrBlank()) {
            return base?.let { Target.Url(it) } ?: Target.Refused(NO_SERVER)
        }
        if (target.startsWith("/") && !target.startsWith("//") && '\\' !in target) {
            base ?: return Target.Refused(NO_SERVER)
            val resolved = runCatching { URI(base).resolve(target).toString() }.getOrNull()
            return if (resolved != null && allowed(resolved, ports)) Target.Url(resolved) else Target.Refused("$target is not a path on the dev server.")
        }
        if (!isLoopbackHttp(target)) return Target.Refused("The preview only shows this phone's dev servers (http://localhost:<port>). Running: $running.")
        if (!allowed(target, ports)) return Target.Refused("Nothing of this project's answers on localhost:${portOf(target)}. Running: $running.")
        return Target.Url(target)
    }

    const val NO_SERVER = "No dev server is running. Start one in the background (for example `npm run dev`) and open the preview again."

    private val LOOPBACK = setOf("localhost", "127.0.0.1")

    private fun parse(url: String?): URI? = runCatching { URI(url ?: return null) }.getOrNull()
}

/** Screenshot sizes: the preview's pixels fitted into [MAX_SIDE] (never enlarged), and taps mapped back. */
object ScreenshotScale {
    const val MAX_SIDE = 1280

    data class Size(val width: Int, val height: Int)

    fun fit(width: Int, height: Int, max: Int = MAX_SIDE): Size {
        if (width <= 0 || height <= 0) return Size(0, 0)
        if (maxOf(width, height) <= max) return Size(width, height)
        // Integer maths: the long side is exactly [max], the other rounds down (at least 1).
        return if (width >= height) {
            Size(max, (height.toLong() * max / width).toInt().coerceAtLeast(1))
        } else {
            Size((width.toLong() * max / height).toInt().coerceAtLeast(1), max)
        }
    }

    /**
     * [x], [y] pixels of a screenshot of a [viewWidth] × [viewHeight] preview as fractions of the viewport (what the
     * page's `innerWidth`/`innerHeight` scale), or null when the point is outside it.
     */
    fun fraction(x: Double, y: Double, viewWidth: Int, viewHeight: Int): Pair<Double, Double>? {
        val shot = fit(viewWidth, viewHeight)
        if (shot.width == 0 || x > shot.width || y > shot.height) return null
        return (x / shot.width) to (y / shot.height)
    }

    /** True when every sampled pixel of [pixels] (ARGB) is the same colour: a page that drew nothing. */
    fun looksBlank(pixels: IntArray, step: Int = 97): Boolean {
        if (pixels.isEmpty()) return true
        val first = pixels[0]
        var i = 0
        while (i < pixels.size) {
            if (pixels[i] != first) return false
            i += step
        }
        return true
    }
}
