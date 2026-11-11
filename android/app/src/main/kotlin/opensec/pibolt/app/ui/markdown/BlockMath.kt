package opensec.pibolt.app.ui.markdown

import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import java.util.Locale

/**
 * What a code fence says about its block: [language] for the highlighter (lower case, aliases resolved from a file
 * extension when the tag is missing), the [filename] when the fence names one, the header [label] and whether the block
 * is a [diff].
 */
data class CodeInfo(val language: String, val filename: String?, val label: String, val diff: Boolean) {
    /** Title for the full-screen viewer; it resolves back to the language through [viewerLanguage]. */
    val viewerTitle: String get() = when {
        diff -> "diff"
        filename != null -> filename
        language.isBlank() -> ""
        else -> label
    }

    companion object {
        private val shellAliases = setOf("sh", "bash", "zsh", "shell", "console", "shellsession", "terminal")

        private val extensions = mapOf(
            "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "ts" to "ts", "mts" to "ts", "cts" to "ts", "tsx" to "tsx",
            "js" to "js", "mjs" to "js", "cjs" to "js", "jsx" to "jsx", "py" to "python", "rb" to "ruby", "rs" to "rust",
            "go" to "go", "swift" to "swift", "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
            "hpp" to "cpp", "cs" to "csharp", "php" to "php", "sh" to "shell", "bash" to "shell", "zsh" to "shell",
            "fish" to "fish", "ps1" to "powershell", "json" to "json", "jsonc" to "jsonc", "yaml" to "yaml", "yml" to "yaml",
            "toml" to "toml", "ini" to "ini", "xml" to "xml", "html" to "html", "htm" to "html", "css" to "css",
            "scss" to "scss", "sql" to "sql", "md" to "markdown", "txt" to "text", "diff" to "diff", "patch" to "diff",
            "gradle" to "groovy", "groovy" to "groovy", "lua" to "lua", "dart" to "dart", "scala" to "scala",
            "ex" to "elixir", "exs" to "elixir", "hs" to "haskell", "vue" to "vue", "svelte" to "svelte",
            "proto" to "proto", "graphql" to "graphql", "gql" to "graphql", "zig" to "zig", "nix" to "nix",
            "psm1" to "powershell", "mk" to "makefile", "cfg" to "ini", "conf" to "ini", "properties" to "ini",
            "less" to "css", "sass" to "scss", "mdx" to "markdown", "xaml" to "xml", "svg" to "xml", "plist" to "xml",
            "pyi" to "python", "rake" to "ruby", "jsonl" to "json", "json5" to "json", "kt.kts" to "kotlin",
        )

        private val fileNames = mapOf(
            "dockerfile" to "dockerfile", "containerfile" to "dockerfile", "makefile" to "makefile", "gnumakefile" to "makefile",
            "gemfile" to "ruby", "rakefile" to "ruby", ".bashrc" to "shell", ".zshrc" to "shell", ".profile" to "shell",
            ".env" to "shell", ".gitconfig" to "ini", ".editorconfig" to "ini",
        )

        /** Tags the viewer accepts as a language title (besides file names). */
        private val knownTags: Set<String> by lazy {
            extensions.keys + extensions.values + fileNames.values + shellAliases + Languages.aliases +
                setOf("kotlin", "typescript", "javascript", "python", "ruby", "rust", "golang", "yaml", "objc", "r", "perl") - plainLanguages
        }

        /** No highlighting for these, and the viewer keeps them as plain text. */
        private val plainLanguages = setOf("markdown", "md", "text", "txt", "plain", "plaintext", "log", "output")

        private val titleAttr = Regex("""(?:title|file|filename|name|path)\s*=\s*(?:"([^"]+)"|'([^']+)'|(\S+))""")

        /** Parse the fence tag (`ts`, `ts:src/a.ts`, `src/a.ts`) and the rest of its info string (`title="a.ts"`). */
        fun of(tag: String, info: String = ""): CodeInfo {
            var lang = tag.trim()
            var file: String? = null
            val colon = lang.indexOf(':')
            if (colon > 0 && colon < lang.length - 1) {
                file = lang.substring(colon + 1)
                lang = lang.substring(0, colon)
            } else if (looksLikeFile(lang)) {
                file = lang
                lang = ""
            }
            if (file == null && info.isNotBlank()) {
                val m = titleAttr.find(info)
                file = if (m != null) {
                    m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                } else {
                    info.trim().substringBefore(' ').takeIf { !it.startsWith("{") && looksLikeFile(it) }
                }
            }
            val language = lang.lowercase().ifBlank { file?.let(::languageOfFile).orEmpty() }
            val diff = language == "diff" || language == "patch"
            val label = file ?: displayLanguage(lang.lowercase())
            return CodeInfo(language, file, label, diff)
        }

        /** Header label for a tag: lower case, shell aliases as `shell`, blank as `code`. */
        fun displayLanguage(tag: String): String = when {
            tag.isBlank() -> "code"
            tag in shellAliases -> "shell"
            else -> tag
        }

        fun languageOfFile(path: String): String? {
            val name = path.substringAfterLast('/').lowercase()
            fileNames[name]?.let { return it }
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return null
            return extensions[name.substring(dot + 1)]
        }

        private fun looksLikeFile(token: String): Boolean {
            if (token.isEmpty() || token.any { it.isWhitespace() || it == '=' || it == '"' }) return false
            if (languageOfFile(token) != null && (token.contains('.') || token.lowercase() in fileNames)) return true
            return token.contains('/') && token.substringAfterLast('/').isNotEmpty()
        }

        /**
         * The language a viewer [title] stands for, or null for prose and output. `Code` (an untagged block) is the
         * generic highlighter (""); `diff` renders as diff rows.
         */
        fun viewerLanguage(title: String): String? {
            if (title == "Code") return ""
            val t = title.trim().lowercase()
            if (t.isEmpty() || t in plainLanguages) return null
            if (t in knownTags) return if (t in shellAliases) "shell" else t
            if (t.any { it.isWhitespace() }) return null
            return languageOfFile(t)?.takeIf { it !in plainLanguages }
        }
    }
}

/** How a code block is cut down: long blocks fold to their start, streaming ones follow their end. */
object CodeFold {
    /** Blocks longer than this fold. */
    const val FOLD_AT = 20

    /** Lines a folded (or streaming) block shows. */
    const val FOLDED = 14

    /** Up to this many lines expand in place; longer blocks open the viewer. */
    const val INLINE_MAX = 150

    /** Line numbers from this many lines on. */
    const val NUMBERS_FROM = 3

    /** The header's Open action shows for blocks at least this long, even when expanded. */
    const val OPEN_FROM = 40

    enum class Mode { Full, Head, Tail }

    enum class Action { Expand, Collapse, Open }

    data class Footer(val label: String, val action: Action)

    fun mode(lines: Int, closed: Boolean, expanded: Boolean): Mode = when {
        lines <= FOLD_AT -> Mode.Full
        !closed -> Mode.Tail
        expanded && lines <= INLINE_MAX -> Mode.Full
        else -> Mode.Head
    }

    /** The bar under a long block; null when the block is short. While streaming it opens a snapshot in the viewer. */
    fun footer(lines: Int, closed: Boolean, expanded: Boolean): Footer? = when {
        lines <= FOLD_AT -> null
        lines > INLINE_MAX -> Footer("Open all ${count(lines)} lines", Action.Open)
        !closed -> Footer("Show all ${count(lines)} lines", Action.Open)
        expanded -> Footer("Show less", Action.Collapse)
        else -> Footer("Show all ${count(lines)} lines", Action.Expand)
    }

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** The first [n] lines of [text]. */
    fun head(text: String, n: Int): String {
        if (n <= 0) return ""
        var i = 0
        repeat(n) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) return text
            i = nl + 1
        }
        return text.substring(0, i - 1)
    }

    /** Offset where the last [n] lines of [text] start. */
    fun tailStart(text: String, n: Int): Int {
        var i = text.length
        repeat(n) {
            val nl = text.lastIndexOf('\n', i - 1)
            if (nl < 0) return 0
            i = nl
        }
        return i + 1
    }

    fun count(n: Int): String = String.format(Locale.US, "%,d", n)
}

/** Unified diffs in markdown (```diff blocks): rows with line numbers taken from the `@@` headers. */
object UnifiedDiff {
    /** Larger hunk numbers (or ones that overflow Int) are shown without line numbers. */
    private const val MAX_LINE_NUMBER = 100_000_000
    private val hunk = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")
    private val meta = listOf("diff ", "index ", "new file mode", "deleted file mode", "similarity index", "rename from", "rename to", "old mode", "new mode", "Binary files")

    /** An untagged block that is mostly a unified diff. */
    fun looksLikeDiff(code: String): Boolean {
        val lines = code.lineSequence().filter { it.isNotBlank() }.take(400).toList()
        if (lines.size < 3) return false
        val hasHunk = lines.any { it.startsWith("@@ ") }
        val hasFiles = lines.any { it.startsWith("--- ") } && lines.any { it.startsWith("+++ ") }
        if (!hasHunk && !hasFiles) return false
        val diffish = lines.count { it[0] in "+-@ \\" || meta.any(it::startsWith) }
        return diffish * 10 >= lines.size * 9
    }

    fun parse(code: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0
        var remOld = 0
        var remNew = 0
        var inHunk = false
        for (line in code.lineSequence()) {
            val h = hunk.matchEntire(line)
            when {
                h != null -> {
                    // Model output: a header can carry any digits. One that is out of range keeps the lines but drops
                    // the numbers.
                    val numbers = listOf(h.groupValues[1], h.groupValues[2].ifEmpty { "1" }, h.groupValues[3], h.groupValues[4].ifEmpty { "1" })
                        .map { it.toIntOrNull()?.takeIf { n -> n <= MAX_LINE_NUMBER } }
                    if (numbers.all { it != null }) {
                        oldNo = numbers[0]!!
                        remOld = numbers[1]!!
                        newNo = numbers[2]!!
                        remNew = numbers[3]!!
                    } else {
                        oldNo = 0
                        newNo = 0
                        remOld = 0
                        remNew = 0
                    }
                    inHunk = remOld > 0 || remNew > 0
                    out += DiffLine(DiffKind.Hunk, "", line)
                }
                !inHunk && (line.startsWith("--- ") || line.startsWith("+++ ") || meta.any(line::startsWith)) -> out += DiffLine(DiffKind.Hunk, "", line)
                line.startsWith("\\") -> out += DiffLine(DiffKind.Hunk, "", line)
                line.startsWith("+") -> {
                    out += DiffLine(DiffKind.Add, if (newNo > 0) (newNo++).toString() else "", line.substring(1))
                    remNew--
                }
                line.startsWith("-") -> {
                    out += DiffLine(DiffKind.Del, if (oldNo > 0) (oldNo++).toString() else "", line.substring(1))
                    remOld--
                }
                else -> {
                    val inside = inHunk && (line.isEmpty() || line[0] == ' ')
                    out += DiffLine(DiffKind.Context, if (inside && newNo > 0) (newNo++).toString() else "", if (inside) line.drop(1) else line)
                    if (inside) {
                        oldNo++
                        remOld--
                        remNew--
                    }
                }
            }
            if (inHunk && remOld <= 0 && remNew <= 0) inHunk = false
        }
        return out
    }

    /** The changed file's path from `+++ b/…` (or `--- a/…` for a deletion). */
    fun path(code: String): String? {
        var minus: String? = null
        for (line in code.lineSequence().take(50)) {
            if (line.startsWith("+++ ")) {
                val p = clean(line.substring(4))
                if (p != null) return p
            }
            if (line.startsWith("--- ") && minus == null) minus = clean(line.substring(4))
        }
        return minus
    }

    private fun clean(raw: String): String? {
        val p = raw.substringBefore('\t').trim()
        if (p.isEmpty() || p == "/dev/null") return null
        return if (p.startsWith("a/") || p.startsWith("b/")) p.substring(2) else p
    }

    /**
     * Word-level highlights: a run of deleted lines followed by added lines is paired line by line, and each pair marks
     * what differs between their common prefix and suffix. Null where nothing useful can be marked.
     */
    fun wordRanges(lines: List<DiffLine>): Array<IntRange?> {
        val out = arrayOfNulls<IntRange>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (lines[i].kind != DiffKind.Del) {
                i++
                continue
            }
            val delStart = i
            while (i < lines.size && lines[i].kind == DiffKind.Del) i++
            val addStart = i
            while (i < lines.size && lines[i].kind == DiffKind.Add) i++
            val pairs = minOf(addStart - delStart, i - addStart)
            for (k in 0 until pairs) {
                val a = lines[delStart + k].text
                val b = lines[addStart + k].text
                val max = minOf(a.length, b.length)
                var prefix = 0
                while (prefix < max && a[prefix] == b[prefix]) prefix++
                var suffix = 0
                while (suffix < max - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) suffix++
                if (prefix + suffix == 0) continue
                if (prefix < a.length - suffix) out[delStart + k] = prefix until a.length - suffix
                if (prefix < b.length - suffix) out[addStart + k] = prefix until b.length - suffix
            }
        }
        return out
    }
}

/** Column widths for markdown tables, in pixels. */
object TableMath {
    private val numeric = Regex("""^[-+]?[\d.,]+\s?(%|[kKMGT]?B|ms|s|x)?$""")

    /**
     * Natural widths clamped to [min]…[max]. When they fit in [available], the spare width is spread in proportion to
     * them so the table spans the card exactly (rounding goes to the last column); otherwise they stay natural and the
     * table scrolls.
     */
    fun widths(natural: IntArray, available: Int, min: Int, max: Int): IntArray {
        val clamped = IntArray(natural.size) { natural[it].coerceIn(min, max) }
        val sum = clamped.sum()
        if (sum >= available || sum == 0) return clamped
        val out = IntArray(clamped.size) { (clamped[it].toLong() * available / sum).toInt() }
        out[out.lastIndex] += available - out.sum()
        return out
    }

    /** Every non-blank body cell is a number, size or percentage (and there is at least one). */
    fun isNumeric(cells: List<String>): Boolean {
        var any = false
        for (c in cells) {
            val t = c.trim()
            if (t.isEmpty()) continue
            if (!numeric.matches(t)) return false
            any = true
        }
        return any
    }

    enum class Glyph { Yes, No, Dash }

    /** Comparison cells: ✓ / yes, ✗ / no, and a lone dash. */
    fun glyph(cell: String): Glyph? = when (cell.trim().lowercase()) {
        "✓", "✔", "✅", "yes" -> Glyph.Yes
        "✗", "✘", "❌", "no" -> Glyph.No
        "—", "–", "-" -> Glyph.Dash
        else -> null
    }
}

/**
 * Display text for inline markdown. Inline code gets a U+202F (narrow no-break space) pad on each side so its chip has
 * room inside the text instead of bleeding into neighbours, and long unbroken code (paths, dotted names) gets U+200B
 * break opportunities after `/`, `.`, `_` and `-`.
 */
object InlineDisplay {
    const val PAD = ' '
    const val BREAK = '​'
    private const val LONG_CODE = 24

    /** [spans] are in display offsets (code spans cover the code only); [chips] cover code plus its pads. */
    class Result(val text: String, val spans: List<InlineSpan>, val chips: List<IntRange>)

    fun build(parsed: InlineText): Result {
        val src = parsed.text
        val code = parsed.spans.filter { it.kind == InlineSpan.Kind.Code && it.end > it.start }
        if (code.isEmpty()) return Result(src, parsed.spans, emptyList())
        val opens = HashSet<Int>()
        val closes = HashMap<Int, Int>()
        val breakable = BooleanArray(src.length)
        for (s in code) {
            opens += s.start
            closes[s.end] = (closes[s.end] ?: 0) + 1
            if (s.end - s.start >= LONG_CODE && (s.start until s.end).none { src[it].isWhitespace() }) {
                for (k in s.start until s.end - 1) if (src[k] in "/._-") breakable[k] = true
            }
        }
        val map = IntArray(src.length + 1)
        val innerStart = HashMap<Int, Int>()
        val innerEnd = HashMap<Int, Int>()
        val out = StringBuilder(src.length + code.size * 2 + 8)
        for (i in 0..src.length) {
            if (i in closes) {
                innerEnd[i] = out.length
                repeat(closes.getValue(i)) { out.append(PAD) }
            }
            map[i] = out.length
            if (i == src.length) break
            if (i in opens) {
                out.append(PAD)
                innerStart[i] = out.length
                map[i] = out.length
            }
            out.append(src[i])
            if (breakable[i]) out.append(BREAK)
        }
        val spans = ArrayList<InlineSpan>(parsed.spans.size)
        val chips = ArrayList<IntRange>(code.size)
        for (s in parsed.spans) {
            if (s.kind == InlineSpan.Kind.Code && s.end > s.start) {
                val a = innerStart.getValue(s.start)
                val b = innerEnd.getValue(s.end)
                spans += s.copy(start = a, end = b)
                chips += (a - 1)..b
            } else {
                spans += s.copy(start = map[s.start], end = map[s.end])
            }
        }
        return Result(out.toString(), spans, chips)
    }
}
