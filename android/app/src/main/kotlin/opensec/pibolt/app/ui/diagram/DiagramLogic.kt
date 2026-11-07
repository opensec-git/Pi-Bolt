package opensec.pibolt.app.ui.diagram

import java.security.MessageDigest

/**
 * JSON literals for the renderer page. The diagram source reaches JavaScript only as a JSON string literal built here,
 * never spliced into code, so no source text can end the string and run as script.
 */
object JsonText {
    fun quote(s: String): String {
        val out = StringBuilder(s.length + 16)
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // Control characters; line terminators in older JavaScript engines; `<` so `</script>` never appears.
                c < ' ' || c == '\u2028' || c == '\u2029' || c == '<' -> out.append(unicode(c))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    out.append(c).append(s[i + 1])
                    i++
                }
                // A lone surrogate is not valid UTF-16 text.
                Character.isSurrogate(c) -> out.append(unicode(c))
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
        return out.toString()
    }

    private fun unicode(c: Char): String = "\\u" + c.code.toString(16).padStart(4, '0')

    /** A flat JSON object of string, number and boolean values (and nested maps), keys in insertion order. */
    fun obj(values: Map<String, Any>): String = buildString {
        append('{')
        var first = true
        for ((k, v) in values) {
            if (!first) append(',')
            first = false
            append(quote(k)).append(':')
            when (v) {
                is String -> append(quote(v))
                is Boolean -> append(v)
                is Int, is Long -> append(v)
                is Float -> append(if (v.isFinite()) v.toString() else "0")
                is Double -> append(if (v.isFinite()) v.toString() else "0")
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") append(obj(v as Map<String, Any>))
                else -> append(quote(v.toString()))
            }
        }
        append('}')
    }
}

/** Cache keys: one rendered bitmap per diagram source, theme and render size. */
object DiagramKey {
    fun of(code: String, themeJson: String, variant: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length-prefixed parts, so moving text between parts never collides.
        for (part in listOf(code, themeJson, variant)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What a diagram block shows. */
enum class DiagramView {
    /** Nothing drawn yet: a skeleton with a spinner. */
    Skeleton,

    /** The diagram for the current source. */
    Image,

    /** The last diagram drawn, while a newer source waits to be drawn. */
    ImageUpdating,

    /** The source as a code block with the error under it. */
    Error,
}

/**
 * When to render and what to show while a ```mermaid fence streams in. Every change to the source waits
 * [DEBOUNCE_MS] for the next one (so a streaming fence renders when the model pauses or the fence closes, not per
 * token); a fence known to be closed renders at once; a cached diagram shows at once.
 */
object DiagramGate {
    const val DEBOUNCE_MS = 300L

    /** [closed]: true once the fence is closed, false while it is still being written, null when unknown. */
    fun delayBeforeRender(closed: Boolean?, cached: Boolean): Long = if (cached || closed == true) 0L else DEBOUNCE_MS

    /**
     * [current] is the result for the source being shown now (null while it is waiting or rendering); [hasStaleImage]
     * whether an older source's diagram is on screen. An error in a fence that is still open is not shown: the next
     * tokens usually fix it.
     */
    fun view(current: Outcome?, hasStaleImage: Boolean, closed: Boolean?): DiagramView = when (current) {
        Outcome.Drawn -> DiagramView.Image
        Outcome.Failed -> if (closed == false) waiting(hasStaleImage) else DiagramView.Error
        null -> waiting(hasStaleImage)
    }

    private fun waiting(hasStaleImage: Boolean) = if (hasStaleImage) DiagramView.ImageUpdating else DiagramView.Skeleton

    enum class Outcome { Drawn, Failed }
}

/** Texts for diagrams: type names, TalkBack descriptions, error lines. */
object DiagramText {
    /** Mermaid's diagram type id (from its detector) as a readable name. */
    fun kindName(kind: String): String = when (kind.lowercase()) {
        "flowchart", "flowchart-v2", "flowchart-elk", "graph" -> "Flowchart"
        "sequence", "sequencediagram" -> "Sequence diagram"
        "class", "classdiagram" -> "Class diagram"
        "state", "statediagram" -> "State diagram"
        "er", "erdiagram" -> "Entity relationship diagram"
        "gantt" -> "Gantt chart"
        "pie" -> "Pie chart"
        "mindmap" -> "Mind map"
        "journey" -> "User journey"
        "timeline" -> "Timeline"
        "gitgraph" -> "Git graph"
        "quadrantchart" -> "Quadrant chart"
        "xychart" -> "XY chart"
        "requirement" -> "Requirement diagram"
        "sankey" -> "Sankey diagram"
        "block" -> "Block diagram"
        "architecture" -> "Architecture diagram"
        "packet" -> "Packet diagram"
        "kanban" -> "Kanban board"
        "radar" -> "Radar chart"
        "treemap" -> "Treemap"
        else -> "Diagram"
    }

    /** The type named by the source's first meaningful line, for the header before a diagram is drawn. */
    fun kindFromSource(code: String): String {
        for (raw in code.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("%%") || line == "---") continue
            if (line.startsWith("title:") || line.startsWith("config:")) continue
            val word = line.takeWhile { !it.isWhitespace() && it != ';' && it != '{' }.lowercase()
            return when (word) {
                "graph", "flowchart", "flowchart-elk" -> "flowchart"
                "sequencediagram" -> "sequence"
                "classdiagram", "classdiagram-v2" -> "class"
                "statediagram", "statediagram-v2" -> "state"
                "erdiagram" -> "er"
                "xychart-beta", "xychart" -> "xychart"
                "sankey-beta", "sankey" -> "sankey"
                "block-beta", "block" -> "block"
                "architecture-beta", "architecture" -> "architecture"
                "packet-beta", "packet" -> "packet"
                "requirementdiagram" -> "requirement"
                else -> word
            }
        }
        return ""
    }

    /** "Diagram: Flowchart, Start" for TalkBack. */
    fun description(kind: String, label: String): String {
        val name = kindName(kind)
        val first = label.replace(Regex("\\s+"), " ").trim().take(80)
        return if (first.isEmpty()) "Diagram: $name" else "Diagram: $name, $first"
    }

    /**
     * Mermaid's error as one short line: its first line, without the parser's caret drawing, plus what the parser
     * found instead ("Parse error on line 5, unexpected end of input").
     */
    fun errorLine(message: String): String {
        val first = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.all { c -> c == '-' || c == '^' } } ?: "Unknown error"
        var clean = first.removeSuffix(":").trim()
        val got = Regex("got '([^']*)'\\s*$").find(message.trim())?.groupValues?.get(1)
        // The parser's token names are words (PIPE, SQE); a bare number is an internal id and says nothing.
        if (got != null && clean.startsWith("Parse error") && got.any { it.isLetter() }) {
            clean += if (got == "EOF") ", unexpected end of input" else ", unexpected $got"
        }
        return if (clean.length > 160) clean.take(159) + "…" else clean
    }
}
