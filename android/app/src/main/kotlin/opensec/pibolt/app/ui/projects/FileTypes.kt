package opensec.pibolt.app.ui.projects

import opensec.pibolt.app.ui.markdown.CodeInfo

/** How the Files tab draws a file and how the viewer opens it. */
enum class FileKind { Markdown, Code, Text, Image, Binary }

/**
 * File-type rules for the Files tab and the viewer (pure, unit-tested). The name decides first (an extension or a well
 * known file name); for text kinds the content is checked too, so a `.txt` full of NUL bytes still opens as binary.
 */
object FileTypes {
    private val markdown = setOf("md", "markdown", "mdx", "mdown", "mkd")

    /** Raster formats Android decodes. SVG is XML and opens as code. */
    private val images = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "ico")

    private val binaries = setOf(
        "zip", "jar", "apk", "aab", "aar", "dex", "class", "so", "o", "a", "dylib", "dll", "exe", "bin", "img", "iso",
        "gz", "tgz", "xz", "bz2", "zst", "7z", "rar", "tar", "pdf", "woff", "woff2", "ttf", "otf", "eot", "mp3", "mp4",
        "m4a", "mov", "avi", "mkv", "wav", "ogg", "flac", "webm", "sqlite", "db", "wasm", "pyc", "jks", "keystore",
        "p12", "der", "psd", "xcf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "node", "pack", "idx",
    )

    /** Plain text with no highlighting. */
    private val plain = setOf("txt", "text", "log", "csv", "tsv", "lock", "license", "out")

    fun extension(name: String): String {
        val base = name.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0 || dot == base.length - 1) "" else base.substring(dot + 1).lowercase()
    }

    /** The kind from the name alone. */
    fun kind(name: String): FileKind {
        val ext = extension(name)
        return when {
            ext in markdown -> FileKind.Markdown
            ext in images -> FileKind.Image
            ext in binaries -> FileKind.Binary
            ext in plain -> FileKind.Text
            language(name) != null -> FileKind.Code
            else -> FileKind.Text
        }
    }

    /** The highlighter language for a code file, or null for prose and plain text. */
    fun language(name: String): String? =
        CodeInfo.languageOfFile(name)?.takeIf { it != "markdown" && it != "text" && extension(name) !in plain }

    /**
     * True when [bytes] (the first [length] bytes of a file) look binary: a NUL byte, or more than 10% control
     * characters other than tab, CR, LF and form feed. UTF-8 multi-byte sequences are not control characters.
     */
    fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val n = minOf(length, SNIFF)
        if (n == 0) return false
        var control = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x20 && b != 9 && b != 10 && b != 13 && b != 12) control++
        }
        return control * 10 > n
    }

    /** MIME type for sharing. */
    fun mime(name: String): String = when (extension(name)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "json" -> "application/json"
        "md", "markdown", "mdx" -> "text/markdown"
        "html", "htm" -> "text/html"
        "svg" -> "image/svg+xml"
        else -> when (kind(name)) {
            FileKind.Markdown, FileKind.Code, FileKind.Text -> "text/plain"
            FileKind.Image -> "image/*"
            FileKind.Binary -> "application/octet-stream"
        }
    }

    /** "Kotlin", "Markdown", "PNG image", "ZIP file": a short type label for the viewer and binary notes. */
    fun label(name: String): String {
        val ext = extension(name)
        return when (kind(name)) {
            FileKind.Markdown -> "Markdown"
            FileKind.Image -> "${ext.uppercase()} image"
            FileKind.Binary -> if (ext.isEmpty()) "Binary file" else "${ext.uppercase()} file"
            FileKind.Code -> language(name)?.let(::languageName) ?: "Code"
            FileKind.Text -> "Text"
        }
    }

    private fun languageName(id: String): String = when (id) {
        "ts" -> "TypeScript"
        "tsx" -> "TSX"
        "js" -> "JavaScript"
        "jsx" -> "JSX"
        "csharp" -> "C#"
        "cpp" -> "C++"
        "json", "jsonc" -> "JSON"
        "yaml" -> "YAML"
        "toml" -> "TOML"
        "ini" -> "INI"
        "xml" -> "XML"
        "html" -> "HTML"
        "css" -> "CSS"
        "scss" -> "SCSS"
        "sql" -> "SQL"
        "php" -> "PHP"
        "shell" -> "Shell"
        "graphql" -> "GraphQL"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    /** Bytes [looksBinary] reads. */
    const val SNIFF = 8_000
}

/** A match of the viewer's find bar: [line] (0-based) and the range [start, end) inside that line. */
data class FileMatch(val line: Int, val start: Int, val end: Int)

/** Lines and find-in-file for the viewer (pure, unit-tested). */
object FileSearch {
    /** Matches past this many are not collected (the count reads "1000+"). */
    const val MAX_MATCHES = 1_000

    /** `[start, end)` of each line as `start until end`, trailing blank lines dropped (at least one line). */
    fun lines(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        while (true) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            out += start until end
            if (nl < 0) break
            start = nl + 1
        }
        while (out.size > 1 && text.substring(out.last().first, out.last().last + 1).isBlank()) out.removeAt(out.lastIndex)
        return out
    }

    /** Case-insensitive matches of [query] in [text], line by line (a match never spans lines), in order. */
    fun find(text: String, lines: List<IntRange>, query: String, cap: Int = MAX_MATCHES): List<FileMatch> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<FileMatch>()
        var line = 0
        var at = text.indexOf(query, 0, ignoreCase = true)
        // One pass over the text; the line pointer only moves forward.
        while (at >= 0 && line < lines.size) {
            while (line < lines.size && lines[line].last + 1 < at) line++
            if (line >= lines.size) break
            val range = lines[line]
            if (at >= range.first && at + query.length <= range.last + 1) {
                out += FileMatch(line, at - range.first, at - range.first + query.length)
                if (out.size >= cap) break
                at = text.indexOf(query, at + query.length, ignoreCase = true)
            } else {
                at = text.indexOf(query, at + 1, ignoreCase = true)
            }
        }
        return out
    }

    /** "3 of 12", "1000+ matches" style counter text; "No matches" for none. */
    fun counter(current: Int, total: Int, capped: Boolean): String = when {
        total == 0 -> "No matches"
        capped -> "${current + 1} of ${total}+"
        else -> "${current + 1} of $total"
    }

    /** The line index for a typed line number, clamped to the file; null when it is not a number. */
    fun lineIndex(typed: String, lineCount: Int): Int? = typed.trim().toIntOrNull()?.let { (it - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0)) }
}
