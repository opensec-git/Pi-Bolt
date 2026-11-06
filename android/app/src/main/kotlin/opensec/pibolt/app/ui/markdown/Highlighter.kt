package opensec.pibolt.app.ui.markdown

/**
 * Syntax highlighting for code blocks: one linear pass per language family (see [Languages]) that emits sorted,
 * non-overlapping [Token]s. It is a lexer, not a parser: good enough to make model output readable, and cheap.
 *
 * The tokenizer is resumable at line boundaries: [Stream] feeds the complete lines of a block that is still streaming
 * and keeps block comments, multi-line strings, heredocs and open tags across feeds, so each line is tokenised once.
 */
object Highlighter {
    enum class Kind {
        Keyword, Type, Function, String, Number, Comment, Operator, Punctuation, Attribute, Tag, Regex, Escape, Constant,
        Variable, Prompt, Inserted, Deleted, Heading, Emphasis,
    }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    /** Above this size a block is shown plain when highlighted on the calling thread. */
    const val MAX_CHARS = 30_000

    /** Above this size even background highlighting gives up. */
    const val MAX_BACKGROUND_CHARS = 400_000

    /** The language a tag stands for; null for plain text. Unknown tags get the generic tokenizer. */
    fun langFor(language: String): Lang? {
        val lang = if (language.isBlank()) Languages.generic else Languages.of(language) ?: Languages.generic
        return if (lang.mode == LangMode.Plain) null else lang
    }

    fun tokenize(code: String, language: String = "", limit: Int = MAX_CHARS): List<Token> {
        if (code.length > limit) return emptyList()
        val lang = langFor(language) ?: return emptyList()
        val t = Tokenizer(lang)
        t.run(code, 0, code.length)
        return t.tokens
    }

    /**
     * Tokens of a text that only grows (a streaming code block). [update] tokenises what was added since the last
     * call, from the state the previous call ended in. Feed whole lines: a call should end just after a newline.
     */
    class Stream(language: String) {
        private val tokenizer = langFor(language)?.let { Tokenizer(it) }
        private var done = 0

        val tokens: List<Token> get() = tokenizer?.tokens ?: emptyList()

        /** Characters tokenised so far. */
        val length: Int get() = done

        fun update(code: String, upTo: Int = code.length): List<Token> {
            val t = tokenizer ?: return emptyList()
            if (upTo > done && upTo <= code.length) {
                t.run(code, done, upTo)
                done = upTo
            }
            return t.tokens
        }
    }

    private val sharedTypes = setOf(
        "string", "number", "boolean", "bigint", "any", "unknown", "never", "symbol", "int", "uint", "float", "double",
        "bool", "char", "str", "byte", "short", "long", "usize", "isize", "u8", "u16", "u32", "u64", "i8", "i16", "i32",
        "i64", "f32", "f64",
    )

    private const val OPERATORS = "=+-*/%<>!&|^~?"
    private const val PUNCTUATION = "{}()[];,.:"

    /** Words after which the next name is being declared. */
    private val functionDeclarers = setOf("fun", "def", "function", "fn", "func", "sub", "proc", "macro_rules")
    private val typeDeclarers = setOf("class", "struct", "interface", "enum", "trait", "type", "object", "record", "protocol", "extension", "actor", "typealias", "union", "impl", "mixin")

    // Block states carried between lines.
    private const val NONE = 0
    private const val COMMENT = 1
    private const val STRING = 2
    private const val HEREDOC = 3
    private const val MARKUP_COMMENT = 4
    private const val MARKUP_TAG = 5
    private const val MARKUP_CDATA = 6
    private const val EMBED = 7
    private const val YAML_BLOCK = 8
    private const val FENCE = 9
    private const val SHELL_QUOTE = 10

    /** Interpolations, JSX attributes and embedded code nest at most this deep; deeper text stays plain. */
    private const val MAX_NESTING = 4

    private class Tokenizer(val lang: Lang, val level: Int = 0) {
        val tokens = ArrayList<Token>()

        private var block = NONE
        private var close = ""
        private var depth = 0
        /** The open multi-line string interpolates / processes escapes. */
        private var blockInterp = false
        private var blockEscapes = true
        /** A `'` / `"` opened the multi-line shell string. */
        private var blockQuote = '"'
        /** Regex and JSX may start here: the previous token cannot end an expression. */
        private var exprStart = true
        private var lastWord = ""
        /** Shell: the next word is a command. */
        private var commandPos = true
        private var pendingHeredoc: String? = null
        private var cssDepth = 0
        private var yamlIndent = 0
        private var dockerShell = false
        private var embedded: Tokenizer? = null
        private var markupTagName = ""

        private fun emit(start: Int, end: Int, kind: Kind) {
            if (end <= start) return
            val last = tokens.lastOrNull()
            // Adjacent tokens of one kind merge, so long strings or comments stay a single span.
            if (last != null && last.kind == kind && last.end == start && kind != Kind.Punctuation && kind != Kind.Operator) {
                tokens[tokens.size - 1] = Token(last.start, end, kind)
            } else {
                tokens += Token(start, end, kind)
            }
        }

        /** Tokens of code[a, b) from a fresh tokenizer for [l] (interpolations, embedded code, attribute values). */
        private fun sub(code: String, a: Int, b: Int, l: Lang = lang) {
            if (b <= a || level >= MAX_NESTING) return
            val t = Tokenizer(l, level + 1)
            t.run(code, a, b)
            for (tok in t.tokens) emit(tok.start, tok.end, tok.kind)
        }

        fun run(code: String, from: Int, end: Int) {
            when (lang.mode) {
                LangMode.Code -> code(code, from, end)
                LangMode.Shell -> shell(code, from, end)
                LangMode.Markup -> markup(code, from, end)
                LangMode.Css -> css(code, from, end)
                LangMode.Json -> json(code, from, end)
                LangMode.Yaml, LangMode.Toml, LangMode.Ini, LangMode.Markdown, LangMode.Diff, LangMode.Makefile, LangMode.Dockerfile -> lines(code, from, end)
                LangMode.Plain -> Unit
            }
        }

        // ------------------------------------------------------------------------------------------------------
        // Code (C family, Python, Ruby, Lua, SQL, PowerShell, …)

        private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$' && lang.interpInBacktickOnly
        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' && lang.interpInBacktickOnly

        private fun lineEnd(code: String, i: Int, end: Int): Int = code.indexOf('\n', i).let { if (it < 0 || it > end) end else it }

        private fun atLineStart(code: String, i: Int): Boolean {
            var j = i - 1
            while (j >= 0 && (code[j] == ' ' || code[j] == '\t')) j--
            return j < 0 || code[j] == '\n'
        }

        private fun code(code: String, from: Int, end: Int) {
            var i = from
            while (i < end) {
                if (block == COMMENT) {
                    i = blockCommentBody(code, i, end)
                    continue
                }
                if (block == STRING) {
                    i = stringBody(code, i, end, close, blockInterp, blockEscapes, multiline = true)
                    continue
                }
                val c = code[i]
                if (c == '\n' || c == ' ' || c == '\t' || c == '\r') {
                    i++
                    continue
                }
                // Comments.
                val lineComment = lang.lineComments.firstOrNull { code.startsWith(it, i) }
                if (lineComment != null && !(lineComment == "#" && lang.preprocessor)) {
                    if (lang.luaLong && code.startsWith("--[", i) && longBracket(code, i + 2) != null) {
                        val level = longBracket(code, i + 2)!!
                        block = COMMENT
                        close = "]" + "=".repeat(level) + "]"
                        depth = 0
                        emit(i, i + 4 + level, Kind.Comment)
                        i += 4 + level
                        continue
                    }
                    // `#` after `$` or inside a word is not a comment (PowerShell `$#`, PHP attributes `#[`).
                    if (!(lineComment == "#" && i > 0 && (code[i - 1] == '$' || code[i - 1] == '{'))) {
                        val e = lineEnd(code, i, end)
                        emit(i, e, Kind.Comment)
                        i = e
                        continue
                    }
                }
                val bc = lang.blockComment
                if (bc != null && code.startsWith(bc.first, i) && (bc.first != "=begin" || atLineStart(code, i))) {
                    block = COMMENT
                    close = bc.second
                    depth = 1
                    emit(i, i + bc.first.length, Kind.Comment)
                    i = blockCommentBody(code, i + bc.first.length, end)
                    continue
                }
                if (lang.preprocessor && c == '#') {
                    i = preprocessor(code, i, end)
                    continue
                }
                // Strings, with prefixes (f"…", r#"…"#, $"…", @"…").
                if (isIdentStart(c) || c == '$' || c == '@') {
                    val p = stringPrefix(code, i, end)
                    if (p > 0) {
                        i = prefixedString(code, i, p, end)
                        exprStart = false
                        continue
                    }
                }
                if (lang.tripleQuotes && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i) && lang.id != "kotlin" && lang.id != "java" && lang.id != "swift")) {
                    val q = code.substring(i, i + 3)
                    emit(i, i + 3, Kind.String)
                    block = STRING
                    close = q
                    blockInterp = lang.interp != Interp.None && lang.id != "python"
                    blockEscapes = true
                    i = stringBody(code, i + 3, end, q, blockInterp, true, multiline = true)
                    exprStart = false
                    continue
                }
                if (c == '\'' && lang.lifetimes && i + 2 < end && isIdentStart(code[i + 1]) && code[i + 2] != '\'') {
                    var j = i + 1
                    while (j < end && isIdent(code[j])) j++
                    emit(i, j, Kind.Attribute)
                    i = j
                    continue
                }
                if (c in lang.quotes) {
                    val interp = lang.interp != Interp.None && !lang.interpInBacktickOnly && (c == '"' || lang.id == "dart")
                    emit(i, i + 1, Kind.String)
                    i = stringBody(code, i + 1, end, c.toString(), interp && lang.interp != Interp.Brace, escapes = true, multiline = false)
                    exprStart = false
                    continue
                }
                if (c == '`' && lang.backtick) {
                    emit(i, i + 1, Kind.String)
                    block = STRING
                    close = "`"
                    blockInterp = lang.interpInBacktickOnly
                    blockEscapes = lang.interpInBacktickOnly
                    i = stringBody(code, i + 1, end, "`", blockInterp, blockEscapes, multiline = true)
                    exprStart = false
                    continue
                }
                if (lang.luaLong && c == '[') {
                    val level = longBracket(code, i)
                    if (level != null) {
                        emit(i, i + 2 + level, Kind.String)
                        block = STRING
                        close = "]" + "=".repeat(level) + "]"
                        blockInterp = false
                        blockEscapes = false
                        i = stringBody(code, i + 2 + level, end, close, false, false, multiline = true)
                        continue
                    }
                }
                // Numbers.
                if (c.isDigit() && (i == 0 || !isIdent(code[i - 1])) || c == '.' && i + 1 < end && code[i + 1].isDigit() && (i == 0 || !isIdent(code[i - 1]) && code[i - 1] != ')')) {
                    val j = number(code, i, end)
                    emit(i, j, Kind.Number)
                    i = j
                    exprStart = false
                    continue
                }
                // Variables and annotations.
                if (c == '$' && lang.dollarVariables && i + 1 < end && (isIdentStart(code[i + 1]) || code[i + 1] == '{' || lang.id == "powershell" && code[i + 1] in "_?^$")) {
                    var j = i + 1
                    if (code[j] == '{') {
                        j = code.indexOf('}', j).let { if (it < 0 || it >= end) end else it + 1 }
                    } else {
                        while (j < end && (isIdent(code[j]) || lang.id == "powershell" && code[j] == ':' && j + 1 < end && isIdentStart(code[j + 1]))) j++
                    }
                    val word = code.substring(i, j)
                    emit(i, j, if (lang.constants.any { it.equals(word, ignoreCase = true) }) Kind.Constant else Kind.Variable)
                    i = j
                    exprStart = false
                    continue
                }
                if (c == '@' && i + 1 < end && (lang.atVariables || lang.annotations || lang.id == "csharp")) {
                    var j = i + 1
                    if (lang.atVariables && j < end && code[j] == '@') j++
                    if (j < end && isIdentStart(code[j])) {
                        while (j < end && (isIdent(code[j]) || lang.annotations && code[j] == '.' && j + 1 < end && isIdentStart(code[j + 1]))) j++
                        emit(i, j, if (lang.atVariables) Kind.Variable else Kind.Attribute)
                        i = j
                        continue
                    }
                }
                if (c == ':' && lang.symbols && i + 1 < end && isIdentStart(code[i + 1]) && (i == 0 || code[i - 1] != ':' && !isIdent(code[i - 1]))) {
                    var j = i + 1
                    while (j < end && (isIdent(code[j]) || code[j] == '?' || code[j] == '!')) j++
                    emit(i, j, Kind.Constant)
                    i = j
                    exprStart = false
                    continue
                }
                if (lang.id == "php" && (code.startsWith("<?php", i) || code.startsWith("?>", i) || code.startsWith("<?=", i))) {
                    val e = i + if (code.startsWith("<?php", i)) 5 else if (code.startsWith("<?=", i)) 3 else 2
                    emit(i, e, Kind.Tag)
                    i = e
                    continue
                }
                if (lang.id == "powershell" && c == '-' && i + 1 < end && code[i + 1].isLetter() && (i == 0 || code[i - 1].isWhitespace() || code[i - 1] == '(')) {
                    var j = i + 1
                    while (j < end && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    emit(i, j, Kind.Attribute)
                    i = j
                    exprStart = true
                    continue
                }
                if (isIdentStart(c)) {
                    i = word(code, i, end)
                    continue
                }
                if (c == '/' && lang.regexLiterals && exprStart && i + 1 < end && code[i + 1] != '/' && code[i + 1] != '*') {
                    val j = regex(code, i, end)
                    if (j > i) {
                        emit(i, j, Kind.Regex)
                        i = j
                        exprStart = false
                        continue
                    }
                }
                if (c == '<' && lang.jsx && exprStart && i + 1 < end && (code[i + 1].isLetter() || code[i + 1] == '>' || code[i + 1] == '/')) {
                    i = jsxTag(code, i, end)
                    exprStart = false
                    continue
                }
                if (c in OPERATORS) {
                    var j = i + 1
                    while (j < end && code[j] in OPERATORS && !commentAt(code, j)) j++
                    emit(i, j, Kind.Operator)
                    i = j
                    exprStart = true
                    continue
                }
                if (c in PUNCTUATION) {
                    var j = i + 1
                    while (j < end && code[j] in PUNCTUATION && !(code[j] == '.' && j + 1 < end && code[j + 1].isDigit()) &&
                        !(lang.symbols && code[j] == ':' && j + 1 < end && isIdentStart(code[j + 1]) && code[j - 1] != ':')
                    ) j++
                    emit(i, j, Kind.Punctuation)
                    exprStart = code[j - 1] !in ")]}"
                    i = j
                    continue
                }
                i++
            }
        }

        private fun commentAt(code: String, j: Int): Boolean = lang.lineComments.any { code.startsWith(it, j) } || lang.blockComment?.let { code.startsWith(it.first, j) } == true

        /** `[[`, `[=[`, …: the level of a Lua long bracket at [i], or null. */
        private fun longBracket(code: String, i: Int): Int? {
            if (i >= code.length || code[i] != '[') return null
            var j = i + 1
            while (j < code.length && code[j] == '=') j++
            return if (j < code.length && code[j] == '[') j - i - 1 else null
        }

        private fun blockCommentBody(code: String, from: Int, end: Int): Int {
            val open = lang.blockComment?.first
            var i = from
            while (i < end) {
                if (code.startsWith(close, i)) {
                    depth--
                    i += close.length
                    if (depth <= 0 || !lang.nestedComments) {
                        emit(from, i, Kind.Comment)
                        block = NONE
                        depth = 0
                        return i
                    }
                    continue
                }
                if (lang.nestedComments && open != null && code.startsWith(open, i)) {
                    depth++
                    i += open.length
                    continue
                }
                i++
            }
            emit(from, end, Kind.Comment)
            return end
        }

        private fun preprocessor(code: String, i: Int, end: Int): Int {
            val n = end
            // Rust attributes: #[derive(Debug)], #![allow(x)].
            if (i + 1 < n && (code[i + 1] == '[' || code[i + 1] == '!' && i + 2 < n && code[i + 2] == '[')) {
                var j = code.indexOf('[', i) + 1
                var d = 1
                while (j < n && d > 0 && code[j] != '\n') {
                    if (code[j] == '[') d++ else if (code[j] == ']') d--
                    j++
                }
                emit(i, j, Kind.Attribute)
                return j
            }
            if (!atLineStart(code, i) && lang.id != "swift") {
                emit(i, i + 1, Kind.Operator)
                return i + 1
            }
            var j = i + 1
            while (j < n && (code[j] == ' ' || code[j] == '\t')) j++
            while (j < n && isIdent(code[j])) j++
            emit(i, j, Kind.Keyword)
            val directive = code.substring(i + 1, j).trim()
            if (directive == "include" || directive == "import") {
                var k = j
                while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
                if (k < n && code[k] == '<') {
                    val e = code.indexOf('>', k).let { if (it < 0 || it >= lineEnd(code, k, n)) lineEnd(code, k, n) else it + 1 }
                    emit(k, e, Kind.String)
                    return e
                }
            }
            return j
        }

        /** Length of a string prefix at [i] that is followed by a quote, or 0. */
        private fun stringPrefix(code: String, i: Int, end: Int): Int {
            if (lang.stringPrefixes.isEmpty() || i > 0 && isIdent(code[i - 1])) return 0
            for (len in 2 downTo 1) {
                if (i + len >= end) continue
                val p = code.substring(i, i + len)
                if (p !in lang.stringPrefixes) continue
                val q = code[i + len]
                if (q == '"' || q == '\'' && lang.id == "python" || lang.id == "rust" && q == '#' && 'r' in p) return len
            }
            return 0
        }

        private fun prefixedString(code: String, i: Int, plen: Int, end: Int): Int {
            val prefix = code.substring(i, i + plen).lowercase()
            var j = i + plen
            if (lang.id == "rust" && 'r' in prefix) {
                var hashes = 0
                while (j < end && code[j] == '#') {
                    hashes++
                    j++
                }
                if (j >= end || code[j] != '"') {
                    emit(i, j, Kind.String)
                    return j
                }
                val closeQ = "\"" + "#".repeat(hashes)
                emit(i, j + 1, Kind.String)
                block = STRING
                close = closeQ
                blockInterp = false
                blockEscapes = false
                return stringBody(code, j + 1, end, closeQ, false, false, multiline = true)
            }
            val raw = 'r' in prefix || prefix.contains('@')
            val interp = 'f' in prefix || prefix.contains('$')
            val q = code[j]
            val triple = lang.tripleQuotes && code.startsWith("$q$q$q", j)
            val closeQ = if (triple) "$q$q$q" else q.toString()
            emit(i, j + closeQ.length, Kind.String)
            if (triple || prefix.contains('@')) {
                block = STRING
                close = closeQ
                blockInterp = interp
                blockEscapes = !raw
                return stringBody(code, j + closeQ.length, end, closeQ, interp, !raw, multiline = true)
            }
            return stringBody(code, j + 1, end, closeQ, interp, !raw, multiline = false, brace = interp)
        }

        /**
         * The body of a string after its opening quote, up to and including [closeQ]. Escapes and interpolations get
         * their own tokens. A single-line string ends at the line's end when unclosed; a multi-line one keeps
         * [block] set until it closes.
         */
        private fun stringBody(code: String, from: Int, end: Int, closeQ: String, interp: Boolean, escapes: Boolean, multiline: Boolean, brace: Boolean = false): Int {
            var i = from
            var segment = from
            val braceInterp = brace || lang.interp == Interp.Brace && interp
            while (i < end) {
                val c = code[i]
                if (code.startsWith(closeQ, i)) {
                    emit(segment, i + closeQ.length, Kind.String)
                    if (multiline) block = NONE
                    return i + closeQ.length
                }
                if (c == '\n' && !multiline) {
                    emit(segment, i, Kind.String)
                    return i
                }
                if (interp || braceInterp) {
                    val k = interpolation(code, i, end, braceInterp, segment)
                    if (k > i) {
                        i = k
                        segment = i
                        continue
                    }
                }
                if (escapes && c == '\\' && i + 1 < end) {
                    emit(segment, i, Kind.String)
                    val e = escapeEnd(code, i, end)
                    emit(i, e, Kind.Escape)
                    i = e
                    segment = i
                    continue
                }
                i++
            }
            emit(segment, end, Kind.String)
            return end
        }

        private fun escapeEnd(code: String, i: Int, end: Int): Int {
            val c = code[i + 1]
            return when (c) {
                'u' -> if (i + 2 < end && code[i + 2] == '{') (code.indexOf('}', i).let { if (it < 0 || it >= end) end else it + 1 }) else minOf(end, i + 6)
                'x' -> minOf(end, i + 4)
                'U' -> minOf(end, i + 10)
                in '0'..'7' -> {
                    var j = i + 1
                    while (j < end && j < i + 4 && code[j] in '0'..'7') j++
                    j
                }
                '\n' -> i + 1
                else -> i + 2
            }
        }

        /** An interpolation at [i] inside a string: its end, or [i] when there is none. */
        private fun interpolation(code: String, i: Int, end: Int, braces: Boolean, segment: Int): Int {
            val c = code[i]
            val open: Int = when {
                braces && c == '{' -> {
                    if (i + 1 < end && code[i + 1] == '{') return i + 2
                    1
                }
                (lang.interp == Interp.Dollar || lang.interp == Interp.DollarBrace || lang.interp == Interp.Php || lang.interp == Interp.PowerShell) && c == '$' && i + 1 < end && code[i + 1] == '{' -> 2
                lang.interp == Interp.Hash && c == '#' && i + 1 < end && code[i + 1] == '{' -> 2
                lang.interp == Interp.Swift && c == '\\' && i + 1 < end && code[i + 1] == '(' -> 2
                lang.interp == Interp.PowerShell && c == '$' && i + 1 < end && code[i + 1] == '(' -> 2
                (lang.interp == Interp.Dollar || lang.interp == Interp.Php || lang.interp == Interp.PowerShell) && c == '$' && i + 1 < end && (code[i + 1].isLetter() || code[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < end && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    emit(segment, i, Kind.String)
                    emit(i, j, if (lang.dollarVariables) Kind.Variable else Kind.Escape)
                    return j
                }
                else -> return i
            }
            val closer = if (code[i + open - 1] == '(') ')' else '}'
            val opener = code[i + open - 1]
            var j = i + open
            var d = 1
            val limit = lineEnd(code, i, end)
            while (j < limit) {
                if (code[j] == opener) d++ else if (code[j] == closer) {
                    d--
                    if (d == 0) break
                }
                j++
            }
            if (j >= limit) return i
            emit(segment, i, Kind.String)
            emit(i, i + open, Kind.Escape)
            sub(code, i + open, j)
            emit(j, j + 1, Kind.Escape)
            return j + 1
        }

        private fun number(code: String, i: Int, end: Int): Int {
            var j = i
            if (code[j] == '0' && j + 1 < end && code[j + 1] in "xXbBoO") {
                j += 2
                while (j < end && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                return j
            }
            while (j < end) {
                val ch = code[j]
                when {
                    ch.isDigit() || ch == '_' -> j++
                    ch == '.' && j + 1 < end && code[j + 1].isDigit() -> j++
                    (ch == 'e' || ch == 'E') && j + 1 < end && (code[j + 1].isDigit() || code[j + 1] in "+-" && j + 2 < end && code[j + 2].isDigit()) -> j += 2
                    ch.isLetter() -> j++ // suffixes: 1L, 2.5f, 10u32, 1e9, 1n
                    else -> return j
                }
            }
            return j
        }

        private fun word(code: String, i: Int, end: Int): Int {
            var j = i + 1
            while (j < end && (isIdent(code[j]) || lang.id == "ruby" && (code[j] == '?' || code[j] == '!') && j + 1 < end && code[j + 1] != '=')) j++
            // PowerShell cmdlets: Verb-Noun.
            var cmdlet = false
            if (lang.id == "powershell") {
                while (j + 1 < end && code[j] == '-' && code[j + 1].isLetter()) {
                    j++
                    while (j < end && isIdent(code[j])) j++
                    cmdlet = true
                }
            }
            val word = code.substring(i, j)
            val key = if (lang.caseInsensitive) word.lowercase() else word
            val previous = lastWord
            lastWord = key
            val next = if (j < end) code[j] else '\n'
            val kind: Kind? = when {
                cmdlet -> Kind.Function
                key in lang.keywords || lang.caseInsensitive && lang.keywords.contains(word) -> Kind.Keyword
                word in lang.constants || lang.caseInsensitive && lang.constants.any { it.equals(word, ignoreCase = true) } -> Kind.Constant
                lang.macros && next == '!' && !(j + 1 < end && code[j + 1] == '=') -> {
                    emit(i, j + 1, Kind.Function)
                    exprStart = false
                    return j + 1
                }
                previous in functionDeclarers -> Kind.Function
                previous in typeDeclarers && lang.id != "sql" -> Kind.Type
                word in lang.types || (if (lang.caseInsensitive) key in lang.types else false) || word in sharedTypes -> Kind.Type
                // Capitalised calls are constructors (Kotlin, Java, Swift…), but methods in Go, C# and PowerShell.
                lang.capitalTypes && word[0].isUpperCase() && word.any { it.isLowerCase() } && !(next == '(' && lang.id in setOf("go", "csharp", "")) -> Kind.Type
                next == '(' -> Kind.Function
                word in lang.builtins -> Kind.Function
                word.length > 1 && word.all { it.isUpperCase() || it.isDigit() || it == '_' } && word.any { it.isLetter() } -> Kind.Constant
                // Kotlin and Swift generic calls: `listOf<Int>(…)`.
                next == '<' && lang.id in setOf("kotlin", "rust", "swift", "typescript", "tsx", "csharp", "java", "dart") && genericCall(code, j, end) -> Kind.Function
                else -> null
            }
            if (kind != null) emit(i, j, kind)
            exprStart = kind == Kind.Keyword && key !in setOf("this", "self", "super", "true", "false", "null")
            return j
        }

        private fun genericCall(code: String, j: Int, end: Int): Boolean {
            var k = j
            var d = 0
            val limit = minOf(end, j + 80)
            while (k < limit) {
                val ch = code[k]
                if (ch == '<') d++ else if (ch == '>') {
                    d--
                    if (d == 0) return k + 1 < end && (code[k + 1] == '(' || code[k + 1] == ':' && k + 2 < end && code[k + 2] == ':')
                } else if (ch == '\n' || ch == ';' || ch == '=') return false
                k++
            }
            return false
        }

        private fun regex(code: String, i: Int, end: Int): Int {
            var j = i + 1
            var inClass = false
            val limit = lineEnd(code, i, end)
            while (j < limit) {
                val ch = code[j]
                when {
                    ch == '\\' -> j++
                    ch == '[' -> inClass = true
                    ch == ']' -> inClass = false
                    ch == '/' && !inClass -> {
                        j++
                        while (j < limit && code[j].isLetter()) j++
                        return j
                    }
                }
                j++
            }
            return i
        }

        /** A JSX tag at [i]: `<Name attr="x" on={…}>`, `</Name>` or a fragment. */
        private fun jsxTag(code: String, i: Int, end: Int): Int {
            val limit = lineEnd(code, i, end)
            var j = i + 1
            if (j < limit && code[j] == '/') j++
            emit(i, j, Kind.Punctuation)
            val nameStart = j
            while (j < limit && (isIdent(code[j]) || code[j] == '.' || code[j] == '-' || code[j] == ':')) j++
            emit(nameStart, j, if (nameStart < j && code[nameStart].isUpperCase()) Kind.Type else Kind.Tag)
            while (j < limit) {
                val ch = code[j]
                when {
                    ch == '>' -> {
                        emit(j, j + 1, Kind.Punctuation)
                        return j + 1
                    }
                    ch == '/' && j + 1 < limit && code[j + 1] == '>' -> {
                        emit(j, j + 2, Kind.Punctuation)
                        return j + 2
                    }
                    ch == '"' || ch == '\'' -> {
                        val e = code.indexOf(ch, j + 1).let { if (it < 0 || it >= limit) limit else it + 1 }
                        emit(j, e, Kind.String)
                        j = e
                    }
                    ch == '{' -> {
                        var d = 1
                        var k = j + 1
                        while (k < limit && d > 0) {
                            if (code[k] == '{') d++ else if (code[k] == '}') d--
                            if (d > 0) k++
                        }
                        emit(j, j + 1, Kind.Punctuation)
                        sub(code, j + 1, minOf(k, limit))
                        if (k < limit) emit(k, k + 1, Kind.Punctuation)
                        j = minOf(k + 1, limit)
                    }
                    isIdentStart(ch) -> {
                        var k = j + 1
                        while (k < limit && (isIdent(code[k]) || code[k] == '-' || code[k] == ':')) k++
                        emit(j, k, Kind.Attribute)
                        j = k
                    }
                    ch == '=' -> {
                        emit(j, j + 1, Kind.Operator)
                        j++
                    }
                    else -> j++
                }
            }
            return limit
        }

        // ------------------------------------------------------------------------------------------------------
        // Shell

        private fun shell(code: String, from: Int, end: Int) {
            var i = from
            while (i < end) {
                if (block == HEREDOC) {
                    val e = lineEnd(code, i, end)
                    val line = code.substring(i, e)
                    emit(i, e, if (line.trim() == close) Kind.Keyword else Kind.String)
                    if (line.trim() == close) block = NONE
                    i = if (e < end) e + 1 else e
                    continue
                }
                if (block == SHELL_QUOTE) {
                    i = shellString(code, i, end, blockQuote)
                    continue
                }
                val c = code[i]
                when {
                    c == '\n' -> {
                        // A backslash before the newline continues the command.
                        val continued = i > 0 && code[i - 1] == '\\'
                        if (!continued) commandPos = true
                        pendingHeredoc?.let {
                            block = HEREDOC
                            close = it
                            pendingHeredoc = null
                        }
                        i++
                    }
                    c == ' ' || c == '\t' -> i++
                    (c == '$' || c == '%') && i + 1 < end && code[i + 1] == ' ' && atLineStart(code, i) -> {
                        emit(i, i + 1, Kind.Prompt)
                        commandPos = true
                        i += 2
                    }
                    c == '#' && (i == 0 || code[i - 1].isWhitespace() || code[i - 1] == ';') -> {
                        val e = lineEnd(code, i, end)
                        emit(i, e, Kind.Comment)
                        i = e
                    }
                    c == '\'' || c == '"' -> {
                        emit(i, i + 1, Kind.String)
                        i = shellString(code, i + 1, end, c)
                        commandPos = false
                    }
                    c == '$' && i + 1 < end && code[i + 1] == '\'' -> {
                        emit(i, i + 2, Kind.String)
                        i = shellString(code, i + 2, end, '\'')
                        commandPos = false
                    }
                    c == '$' -> {
                        if (i + 1 < end && code[i + 1] == '(') {
                            emit(i, i + 2, Kind.Operator)
                            commandPos = true
                            i += 2
                        } else {
                            val e = shellVariable(code, i, end)
                            if (e > i + 1) emit(i, e, Kind.Variable) else emit(i, e, Kind.Operator)
                            i = e
                            commandPos = false
                        }
                    }
                    c == '`' -> {
                        emit(i, i + 1, Kind.Operator)
                        commandPos = true
                        i++
                    }
                    c == '<' && code.startsWith("<<", i) && !code.startsWith("<<<", i) -> {
                        var j = i + 2
                        if (j < end && code[j] == '-') j++
                        while (j < end && code[j] == ' ') j++
                        val quoted = j < end && (code[j] == '\'' || code[j] == '"')
                        if (quoted) j++
                        val ws = j
                        while (j < end && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        if (j > ws) {
                            pendingHeredoc = code.substring(ws, j)
                            if (quoted && j < end) j++
                            emit(i, j, Kind.Operator)
                            i = j
                        } else {
                            emit(i, i + 2, Kind.Operator)
                            i += 2
                        }
                    }
                    c in "|&;" -> {
                        var j = i + 1
                        while (j < end && code[j] in "|&;") j++
                        emit(i, j, Kind.Operator)
                        commandPos = true
                        i = j
                    }
                    c in "<>" -> {
                        var j = i + 1
                        while (j < end && code[j] in "<>&") j++
                        emit(i, j, Kind.Operator)
                        i = j
                    }
                    c in "(){}[]" -> {
                        emit(i, i + 1, Kind.Punctuation)
                        if (c == '(' || c == '{') commandPos = true
                        i++
                    }
                    c == '=' -> {
                        emit(i, i + 1, Kind.Operator)
                        i++
                    }
                    c == '-' && (i == 0 || code[i - 1].isWhitespace()) && !commandPos -> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == '_')) j++
                        emit(i, j, Kind.Attribute)
                        i = j
                    }
                    c.isDigit() && (i == 0 || code[i - 1].isWhitespace() || code[i - 1] in "=(:,") -> {
                        var j = i
                        while (j < end && (code[j].isDigit() || code[j] == '.')) j++
                        if (j >= end || code[j].isWhitespace() || code[j] in ";)|&>") emit(i, j, Kind.Number)
                        i = maxOf(j, i + 1)
                        commandPos = false
                    }
                    else -> {
                        var j = i
                        while (j < end && !code[j].isWhitespace() && code[j] !in "|&;<>()\"'`$=#{}") j++
                        if (j == i) {
                            i++
                            continue
                        }
                        val word = code.substring(i, j)
                        val assign = j < end && code[j] == '=' && commandPos && word.all { it.isLetterOrDigit() || it == '_' }
                        when {
                            assign -> emit(i, j, Kind.Attribute)
                            word in lang.keywords -> {
                                emit(i, j, Kind.Keyword)
                                commandPos = word in setOf("then", "do", "else", "elif", "if", "while", "until", "time", "!")
                                i = j
                                continue
                            }
                            commandPos -> {
                                emit(i, j, Kind.Function)
                                commandPos = word in setOf("sudo", "time", "exec", "nohup", "xargs", "env", "command", "builtin", "nice")
                                i = j
                                continue
                            }
                        }
                        i = j
                    }
                }
            }
        }

        private fun shellVariable(code: String, i: Int, end: Int): Int {
            var j = i + 1
            if (j >= end) return j
            val c = code[j]
            return when {
                c == '{' -> code.indexOf('}', j).let { if (it < 0 || it >= lineEnd(code, j, end)) lineEnd(code, j, end) else it + 1 }
                c.isLetter() || c == '_' -> {
                    while (j < end && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    j
                }
                c.isDigit() || c in "@*#?$!-" -> j + 1
                else -> j
            }
        }

        private fun shellString(code: String, from: Int, end: Int, quote: Char): Int {
            var i = from
            var segment = from
            while (i < end) {
                val c = code[i]
                if (c == quote) {
                    emit(segment, i + 1, Kind.String)
                    block = NONE
                    return i + 1
                }
                if (quote == '"' && c == '\\' && i + 1 < end) {
                    emit(segment, i, Kind.String)
                    emit(i, i + 2, Kind.Escape)
                    i += 2
                    segment = i
                    continue
                }
                if (quote == '"' && c == '$' && i + 1 < end) {
                    val e = if (code[i + 1] == '(') {
                        code.indexOf(')', i).let { if (it < 0 || it >= end) i + 1 else it + 1 }
                    } else {
                        shellVariable(code, i, end)
                    }
                    if (e > i + 1) {
                        emit(segment, i, Kind.String)
                        emit(i, e, Kind.Variable)
                        i = e
                        segment = i
                        continue
                    }
                }
                i++
            }
            emit(segment, end, Kind.String)
            block = SHELL_QUOTE
            blockQuote = quote
            return end
        }

        // ------------------------------------------------------------------------------------------------------
        // XML / HTML

        private fun markup(code: String, from: Int, end: Int) {
            var i = from
            while (i < end) {
                when (block) {
                    MARKUP_COMMENT, MARKUP_CDATA -> {
                        val e = code.indexOf(close, i).let { if (it < 0 || it >= end) end else it + close.length }
                        emit(i, e, if (block == MARKUP_COMMENT) Kind.Comment else Kind.String)
                        if (e < end || code.startsWith(close, e - close.length)) block = NONE
                        i = e
                        continue
                    }
                    MARKUP_TAG -> {
                        i = tagBody(code, i, end)
                        continue
                    }
                    EMBED -> {
                        val e = code.indexOf(close, i).let { if (it < 0 || it >= end) end else it }
                        embedded?.let {
                            it.run(code, i, e)
                            for (t in it.tokens) emit(t.start, t.end, t.kind)
                            it.tokens.clear()
                        }
                        if (e < end) {
                            block = NONE
                            embedded = null
                        }
                        i = e
                        continue
                    }
                }
                val c = code[i]
                when {
                    code.startsWith("<!--", i) -> {
                        block = MARKUP_COMMENT
                        close = "-->"
                        emit(i, i + 4, Kind.Comment)
                        i += 4
                    }
                    code.startsWith("<![CDATA[", i) -> {
                        block = MARKUP_CDATA
                        close = "]]>"
                        emit(i, i + 9, Kind.Punctuation)
                        i += 9
                    }
                    c == '<' && i + 1 < end && (code[i + 1].isLetter() || code[i + 1] in "/?!") -> {
                        var j = i + 1
                        if (code[j] in "/?!") j++
                        emit(i, j, Kind.Punctuation)
                        val ns = j
                        while (j < end && (code[j].isLetterOrDigit() || code[j] in "-_:.")) j++
                        markupTagName = code.substring(ns, j).lowercase()
                        emit(ns, j, if (code[i + 1] == '!' || code[i + 1] == '?') Kind.Keyword else Kind.Tag)
                        if (code[i + 1] == '/') markupTagName = ""
                        block = MARKUP_TAG
                        i = tagBody(code, j, end)
                    }
                    c == '&' -> {
                        val e = Entities.match(code, i).let { if (it < 0) code.indexOf(';', i).let { s -> if (s in i + 2..i + 10) s + 1 else -1 } else it }
                        if (e > i) {
                            emit(i, e, Kind.Escape)
                            i = e
                        } else {
                            i++
                        }
                    }
                    else -> i++
                }
            }
        }

        /** Attributes of an open tag until its `>`; may continue on the next line. */
        private fun tagBody(code: String, from: Int, end: Int): Int {
            var i = from
            while (i < end) {
                val c = code[i]
                when {
                    c == '>' || (c == '/' || c == '?') && i + 1 < end && code[i + 1] == '>' -> {
                        val e = if (c == '>') i + 1 else i + 2
                        emit(i, e, Kind.Punctuation)
                        block = NONE
                        if (c == '>' && (markupTagName == "script" || markupTagName == "style")) {
                            block = EMBED
                            close = "</$markupTagName"
                            embedded = Tokenizer(if (markupTagName == "script") Languages.javascript else Languages.css)
                        }
                        return e
                    }
                    c == '"' || c == '\'' -> {
                        val e = code.indexOf(c, i + 1).let { if (it < 0 || it >= end) end else it + 1 }
                        emit(i, e, Kind.String)
                        i = e
                    }
                    c == '=' -> {
                        emit(i, i + 1, Kind.Operator)
                        i++
                    }
                    c.isLetter() || c in "_:@#.[(*" -> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] in "-_:.@#[]()*")) j++
                        emit(i, j, Kind.Attribute)
                        i = j
                    }
                    else -> i++
                }
            }
            return end
        }

        // ------------------------------------------------------------------------------------------------------
        // CSS / SCSS / Less

        private fun css(code: String, from: Int, end: Int) {
            var i = from
            var declaration = false
            var value = false
            var statementStart = true
            while (i < end) {
                if (block == COMMENT) {
                    val e = code.indexOf("*/", i).let { if (it < 0 || it >= end) end else it + 2 }
                    emit(i, e, Kind.Comment)
                    if (e <= end && code.startsWith("*/", e - 2) && e - 2 >= i) block = NONE
                    i = e
                    continue
                }
                val c = code[i]
                if (statementStart && !c.isWhitespace()) {
                    statementStart = false
                    declaration = cssDepth > 0 && isDeclaration(code, i, end)
                    value = false
                }
                when {
                    c.isWhitespace() -> i++
                    code.startsWith("/*", i) -> {
                        block = COMMENT
                        emit(i, i + 2, Kind.Comment)
                        i += 2
                    }
                    code.startsWith("//", i) && (lang.id == "scss") && (i == 0 || code[i - 1] != ':') -> {
                        val e = lineEnd(code, i, end)
                        emit(i, e, Kind.Comment)
                        i = e
                    }
                    c == '"' || c == '\'' -> {
                        emit(i, i + 1, Kind.String)
                        i = stringBody(code, i + 1, end, c.toString(), false, true, multiline = false)
                    }
                    c == '{' -> {
                        cssDepth++
                        emit(i, i + 1, Kind.Punctuation)
                        statementStart = true
                        i++
                    }
                    c == '}' -> {
                        cssDepth = maxOf(0, cssDepth - 1)
                        emit(i, i + 1, Kind.Punctuation)
                        statementStart = true
                        i++
                    }
                    c == ';' -> {
                        emit(i, i + 1, Kind.Punctuation)
                        statementStart = true
                        i++
                    }
                    c == '@' -> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == '_')) j++
                        emit(i, j, Kind.Keyword)
                        value = true
                        i = j
                    }
                    c == '$' || c == '-' && i + 1 < end && code[i + 1] == '-' && (i == 0 || !code[i - 1].isLetterOrDigit()) -> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == '_')) j++
                        emit(i, j, Kind.Variable)
                        if (!value && j < end && code[j] == ':') {
                            emit(j, j + 1, Kind.Punctuation)
                            value = true
                            j++
                        }
                        i = j
                    }
                    c == '!' -> {
                        var j = i + 1
                        while (j < end && code[j].isLetter()) j++
                        emit(i, j, Kind.Keyword)
                        i = j
                    }
                    c == '#' && (value || declaration) -> {
                        var j = i + 1
                        while (j < end && code[j].isLetterOrDigit()) j++
                        emit(i, j, Kind.Number)
                        i = j
                    }
                    (c.isDigit() || c == '.' && i + 1 < end && code[i + 1].isDigit()) && (value || cssDepth == 0 && i > 0 && code[i - 1] in " (:,") -> {
                        var j = i
                        while (j < end && (code[j].isDigit() || code[j] == '.')) j++
                        while (j < end && (code[j].isLetter() || code[j] == '%')) j++
                        emit(i, j, Kind.Number)
                        i = j
                    }
                    c.isLetter() || c == '-' || c == '_' || (!declaration && !value && c in ".#&*:")-> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == '_')) j++
                        when {
                            declaration && !value -> {
                                emit(i, j, Kind.Attribute)
                                if (j < end && code[j] == ':') {
                                    emit(j, j + 1, Kind.Punctuation)
                                    value = true
                                    j++
                                }
                            }
                            value || declaration -> emit(i, j, if (j < end && code[j] == '(') Kind.Function else Kind.Constant)
                            c == '.' -> emit(i, j, Kind.Attribute)
                            c == '#' -> emit(i, j, Kind.Constant)
                            c == ':' -> {
                                var k = j
                                if (k < end && code[k] == ':') k++
                                while (k < end && (code[k].isLetterOrDigit() || code[k] == '-')) k++
                                emit(i, k, Kind.Keyword)
                                j = k
                            }
                            c == '&' || c == '*' -> emit(i, i + 1, Kind.Operator)
                            else -> emit(i, j, Kind.Tag)
                        }
                        i = j
                    }
                    c in ">+~,()[]=" -> {
                        emit(i, i + 1, if (c in ">+~=") Kind.Operator else Kind.Punctuation)
                        i++
                    }
                    else -> i++
                }
            }
        }

        /** Inside a rule: `name: value` before the next `;`, `{` or `}` (a nested selector has its `{` first). */
        private fun isDeclaration(code: String, i: Int, end: Int): Boolean {
            var j = i
            var colon = false
            while (j < end) {
                when (code[j]) {
                    ':' -> colon = true
                    ';', '}' -> return colon
                    '{' -> return false
                    '\n' -> return colon
                }
                j++
            }
            return colon
        }

        // ------------------------------------------------------------------------------------------------------
        // JSON (with comments: JSONC, JSON5)

        private fun json(code: String, from: Int, end: Int) {
            var i = from
            while (i < end) {
                if (block == COMMENT) {
                    i = blockCommentBody(code, i, end)
                    continue
                }
                val c = code[i]
                when {
                    c.isWhitespace() -> i++
                    code.startsWith("//", i) -> {
                        val e = lineEnd(code, i, end)
                        emit(i, e, Kind.Comment)
                        i = e
                    }
                    code.startsWith("/*", i) -> {
                        block = COMMENT
                        close = "*/"
                        depth = 1
                        emit(i, i + 2, Kind.Comment)
                        i = blockCommentBody(code, i + 2, end)
                    }
                    c == '"' || c == '\'' -> {
                        val start = tokens.size
                        emit(i, i + 1, Kind.String)
                        val e = stringBody(code, i + 1, end, c.toString(), false, true, multiline = false)
                        var k = e
                        while (k < end && (code[k] == ' ' || code[k] == '\t')) k++
                        if (k < end && code[k] == ':') {
                            // Object keys: one Attribute token instead of the string's.
                            while (tokens.size > start) tokens.removeAt(tokens.size - 1)
                            emit(i, e, Kind.Attribute)
                        }
                        i = e
                    }
                    c == '-' || c.isDigit() -> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] in ".+-")) j++
                        emit(i, j, Kind.Number)
                        i = j
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i + 1
                        while (j < end && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        val word = code.substring(i, j)
                        emit(i, j, if (word in lang.constants) Kind.Constant else Kind.Attribute)
                        i = j
                    }
                    c in "{}[],:" -> {
                        emit(i, i + 1, Kind.Punctuation)
                        i++
                    }
                    else -> i++
                }
            }
        }

        // ------------------------------------------------------------------------------------------------------
        // Line-based languages

        private fun lines(code: String, from: Int, end: Int) {
            var i = from
            while (i < end) {
                val e = lineEnd(code, i, end)
                when (lang.mode) {
                    LangMode.Yaml -> yamlLine(code, i, e)
                    LangMode.Toml, LangMode.Ini -> tomlLine(code, i, e)
                    LangMode.Markdown -> markdownLine(code, i, e)
                    LangMode.Diff -> diffLine(code, i, e)
                    LangMode.Makefile -> makeLine(code, i, e)
                    LangMode.Dockerfile -> dockerLine(code, i, e)
                    else -> Unit
                }
                i = e + 1
            }
        }

        private fun indentAt(code: String, i: Int, e: Int): Int {
            var j = i
            while (j < e && (code[j] == ' ' || code[j] == '\t')) j++
            return j
        }

        private fun yamlLine(code: String, start: Int, e: Int) {
            var i = indentAt(code, start, e)
            val indent = i - start
            if (block == YAML_BLOCK) {
                if (i >= e || indent > yamlIndent) {
                    emit(start, e, Kind.String)
                    return
                }
                block = NONE
            }
            if (i >= e) return
            if (code[i] == '#') {
                emit(i, e, Kind.Comment)
                return
            }
            if (code.startsWith("---", i) || code.startsWith("...", i) && i == start) {
                emit(i, minOf(e, i + 3), Kind.Punctuation)
                i += 3
            }
            while (i < e && code[i] == '-' && (i + 1 >= e || code[i + 1] == ' ')) {
                emit(i, i + 1, Kind.Punctuation)
                i = indentAt(code, i + 1, e)
            }
            // key:
            val keyEnd = yamlKey(code, i, e)
            if (keyEnd > i) {
                emit(i, keyEnd, Kind.Attribute)
                emit(keyEnd, keyEnd + 1, Kind.Punctuation)
                i = keyEnd + 1
            }
            yamlValue(code, i, e, indent)
        }

        /** End of a mapping key at [i] (the index of its `:`), or [i]. */
        private fun yamlKey(code: String, i: Int, e: Int): Int {
            if (i >= e) return i
            if (code[i] == '"' || code[i] == '\'') {
                val q = code.indexOf(code[i], i + 1)
                return if (q in i + 1 until e - 1 && code[q + 1] == ':' && (q + 2 >= e || code[q + 2] == ' ')) q + 1 else i
            }
            var j = i
            while (j < e) {
                val ch = code[j]
                if (ch == ':' && (j + 1 >= e || code[j + 1] == ' ' || code[j + 1] == '\t')) return if (j > i) j else i
                if (ch == '#' && j > i && code[j - 1] == ' ') return i
                if (ch in "{}[],&*!|>'\"%@`" && j == i) return i
                if (ch in "{}[],") return i
                j++
            }
            return i
        }

        private fun yamlValue(code: String, from: Int, e: Int, indent: Int) {
            var i = from
            while (i < e) {
                val c = code[i]
                when {
                    c == ' ' || c == '\t' -> i++
                    c == '#' && (i == 0 || code[i - 1] == ' ') -> {
                        emit(i, e, Kind.Comment)
                        return
                    }
                    c == '"' || c == '\'' -> {
                        emit(i, i + 1, Kind.String)
                        i = stringBody(code, i + 1, e, c.toString(), false, c == '"', multiline = false)
                    }
                    (c == '|' || c == '>') && code.substring(i, e).trim().matches(Regex("[|>][+-]?\\d?(\\s+#.*)?")) -> {
                        emit(i, i + 1, Kind.Operator)
                        block = YAML_BLOCK
                        yamlIndent = indent
                        i++
                    }
                    c == '&' || c == '*' -> {
                        var j = i + 1
                        while (j < e && !code[j].isWhitespace() && code[j] !in ",]}") j++
                        emit(i, j, Kind.Variable)
                        i = j
                    }
                    c == '!' -> {
                        var j = i + 1
                        while (j < e && !code[j].isWhitespace()) j++
                        emit(i, j, Kind.Type)
                        i = j
                    }
                    c in "{}[]," -> {
                        emit(i, i + 1, Kind.Punctuation)
                        i++
                        // Keys inside flow mappings.
                        val k = indentAt(code, i, e)
                        val keyEnd = yamlKey(code, k, e)
                        if (keyEnd > k) {
                            emit(k, keyEnd, Kind.Attribute)
                            emit(keyEnd, keyEnd + 1, Kind.Punctuation)
                            i = keyEnd + 1
                        }
                    }
                    else -> {
                        var j = i
                        while (j < e && !(code[j] in ",]}" ) && !(code[j] == '#' && code[j - 1] == ' ') && !(code[j] == ':' && (j + 1 >= e || code[j + 1] == ' '))) j++
                        var t = j
                        while (t > i && code[t - 1] == ' ') t--
                        val word = code.substring(i, t)
                        when {
                            word in lang.constants -> emit(i, t, Kind.Constant)
                            word.matches(Regex("[-+]?(\\d[\\d_]*(\\.\\d*)?([eE][-+]?\\d+)?|0x[0-9a-fA-F]+|\\.inf|\\.nan)")) -> emit(i, t, Kind.Number)
                            else -> emit(i, t, Kind.String)
                        }
                        i = maxOf(j, i + 1)
                    }
                }
            }
        }

        private fun tomlLine(code: String, start: Int, e: Int) {
            if (block == STRING) {
                val k = stringBody(code, start, e, close, false, blockEscapes, multiline = true)
                if (block == STRING) return
                tomlValue(code, k, e)
                return
            }
            var i = indentAt(code, start, e)
            if (i >= e) return
            val c = code[i]
            if (c == '#' || c == ';' && lang.mode == LangMode.Ini) {
                emit(i, e, Kind.Comment)
                return
            }
            if (c == '[') {
                val close = code.lastIndexOf(']', e - 1).let { if (it < i) e else it + 1 }
                emit(i, close, Kind.Tag)
                val rest = indentAt(code, close, e)
                if (rest < e && code[rest] == '#') emit(rest, e, Kind.Comment)
                return
            }
            // key = value (INI also key: value)
            var j = i
            while (j < e && code[j] != '=' && !(lang.mode == LangMode.Ini && code[j] == ':')) j++
            if (j >= e) {
                emit(i, e, Kind.Attribute)
                return
            }
            var k = j
            while (k > i && code[k - 1] == ' ') k--
            emit(i, k, Kind.Attribute)
            emit(j, j + 1, Kind.Operator)
            tomlValue(code, j + 1, e)
        }

        private fun tomlValue(code: String, from: Int, e: Int) {
            var i = from
            while (i < e) {
                val c = code[i]
                when {
                    c == ' ' || c == '\t' -> i++
                    c == '#' || c == ';' && lang.mode == LangMode.Ini -> {
                        emit(i, e, Kind.Comment)
                        return
                    }
                    code.startsWith("\"\"\"", i) || code.startsWith("'''", i) -> {
                        val q = code.substring(i, i + 3)
                        emit(i, i + 3, Kind.String)
                        block = STRING
                        close = q
                        blockEscapes = q == "\"\"\""
                        i = stringBody(code, i + 3, e, q, false, blockEscapes, multiline = true)
                    }
                    c == '"' || c == '\'' -> {
                        emit(i, i + 1, Kind.String)
                        i = stringBody(code, i + 1, e, c.toString(), false, c == '"', multiline = false)
                    }
                    c in "[]{}," -> {
                        emit(i, i + 1, Kind.Punctuation)
                        i++
                    }
                    c == '=' -> {
                        emit(i, i + 1, Kind.Operator)
                        i++
                    }
                    else -> {
                        var j = i
                        while (j < e && code[j] !in ",]}=#" && !(code[j] == ' ' && lang.mode == LangMode.Toml)) j++
                        val word = code.substring(i, j).trim()
                        when {
                            word in lang.constants -> emit(i, j, Kind.Constant)
                            word.isNotEmpty() && (word[0].isDigit() || word[0] in "+-" && word.length > 1) -> emit(i, j, Kind.Number)
                            j < e && code[j] == '=' -> emit(i, j, Kind.Attribute)
                            lang.mode == LangMode.Ini -> emit(i, j, Kind.String)
                            else -> emit(i, j, Kind.Attribute)
                        }
                        i = maxOf(j, i + 1)
                    }
                }
            }
        }

        private fun markdownLine(code: String, start: Int, e: Int) {
            val i = indentAt(code, start, e)
            if (block == FENCE) {
                if (code.startsWith(close, i)) {
                    emit(i, e, Kind.Punctuation)
                    block = NONE
                } else {
                    emit(start, e, Kind.String)
                }
                return
            }
            if (i >= e) return
            if (code.startsWith("```", i) || code.startsWith("~~~", i)) {
                block = FENCE
                close = code.substring(i, i + 3)
                emit(i, i + 3, Kind.Punctuation)
                emit(i + 3, e, Kind.Attribute)
                return
            }
            val c = code[i]
            if (c == '#') {
                emit(i, e, Kind.Heading)
                return
            }
            if (c == '>') {
                emit(i, e, Kind.Comment)
                return
            }
            var j = i
            if (code.substring(i, e).trim().let { it.length >= 3 && (it.all { ch -> ch == '-' || ch == ' ' } || it.all { ch -> ch == '*' || ch == ' ' }) }) {
                emit(i, e, Kind.Punctuation)
                return
            }
            if ((c == '-' || c == '*' || c == '+') && i + 1 < e && code[i + 1] == ' ') {
                emit(i, i + 1, Kind.Punctuation)
                j = i + 2
            } else if (c.isDigit()) {
                var k = i
                while (k < e && code[k].isDigit()) k++
                if (k < e && (code[k] == '.' || code[k] == ')') && k + 1 < e && code[k + 1] == ' ') {
                    emit(i, k + 1, Kind.Punctuation)
                    j = k + 2
                }
            }
            markdownInline(code, j, e)
        }

        private fun markdownInline(code: String, from: Int, e: Int) {
            var i = from
            while (i < e) {
                val c = code[i]
                when {
                    c == '`' -> {
                        val close = code.indexOf('`', i + 1)
                        if (close in i + 1 until e) {
                            emit(i, close + 1, Kind.String)
                            i = close + 1
                        } else {
                            i++
                        }
                    }
                    (c == '*' || c == '_') && i + 1 < e -> {
                        val double = code[i + 1] == c
                        val marker = if (double) "$c$c" else "$c"
                        val close = code.indexOf(marker, i + marker.length)
                        if (close in i + marker.length + 1 until e) {
                            emit(i, close + marker.length, Kind.Emphasis)
                            i = close + marker.length
                        } else {
                            i += marker.length
                        }
                    }
                    c == '[' || c == '!' && i + 1 < e && code[i + 1] == '[' -> {
                        val open = if (c == '!') i + 1 else i
                        val closeText = code.indexOf(']', open)
                        if (closeText in open until e && closeText + 1 < e && code[closeText + 1] == '(') {
                            val closeUrl = code.indexOf(')', closeText)
                            if (closeUrl in closeText until e) {
                                emit(i, closeText + 1, Kind.Function)
                                emit(closeText + 1, closeUrl + 1, Kind.Attribute)
                                i = closeUrl + 1
                                continue
                            }
                        }
                        i++
                    }
                    c == '<' && i + 1 < e && (code[i + 1].isLetter() || code[i + 1] == '/') -> {
                        val close = code.indexOf('>', i)
                        if (close in i until e) {
                            emit(i, close + 1, Kind.Tag)
                            i = close + 1
                        } else {
                            i++
                        }
                    }
                    else -> i++
                }
            }
        }

        private fun diffLine(code: String, start: Int, e: Int) {
            if (start >= e) return
            val kind = when {
                code.startsWith("+++", start) || code.startsWith("---", start) || code.startsWith("diff ", start) || code.startsWith("index ", start) -> Kind.Heading
                code[start] == '@' -> Kind.Attribute
                code[start] == '+' -> Kind.Inserted
                code[start] == '-' -> Kind.Deleted
                code[start] == '\\' -> Kind.Comment
                else -> null
            }
            if (kind != null) emit(start, e, kind)
        }

        private fun makeLine(code: String, start: Int, e: Int) {
            if (start >= e) return
            if (code[start] == '\t') {
                // A recipe line: shell.
                makeVariables(code, start, e) { a, b -> sub(code, a, b, Languages.shell) }
                return
            }
            val i = indentAt(code, start, e)
            if (i >= e) return
            if (code[i] == '#') {
                emit(i, e, Kind.Comment)
                return
            }
            var j = i
            while (j < e && (code[j].isLetterOrDigit() || code[j] in "_-.%/$()")) j++
            val word = code.substring(i, j)
            if (word in lang.keywords) {
                emit(i, j, Kind.Keyword)
                makeVariables(code, j, e) { _, _ -> }
                return
            }
            var k = j
            while (k < e && code[k] == ' ') k++
            when {
                k < e && (code.startsWith(":=", k) || code.startsWith("?=", k) || code.startsWith("+=", k) || code.startsWith("::=", k) || code[k] == '=') -> {
                    emit(i, j, Kind.Attribute)
                    val opEnd = if (code[k] == '=') k + 1 else code.indexOf('=', k) + 1
                    emit(k, opEnd, Kind.Operator)
                    makeVariables(code, opEnd, e) { a, b -> emit(a, b, Kind.String) }
                }
                k < e && code[k] == ':' -> {
                    emit(i, j, if (word.startsWith(".")) Kind.Keyword else Kind.Function)
                    emit(k, k + 1, Kind.Operator)
                    makeVariables(code, k + 1, e) { _, _ -> }
                }
                else -> makeVariables(code, i, e) { _, _ -> }
            }
        }

        /** `$(VAR)`, `${VAR}`, `$@`… as variables; [other] gets the text between them. */
        private inline fun makeVariables(code: String, from: Int, e: Int, other: (Int, Int) -> Unit) {
            var i = from
            var segment = from
            while (i < e) {
                if (code[i] == '$' && i + 1 < e) {
                    val n = code[i + 1]
                    val endVar = when (n) {
                        '(', '{' -> code.indexOf(if (n == '(') ')' else '}', i).let { if (it < 0 || it >= e) e else it + 1 }
                        else -> i + 2
                    }
                    other(segment, i)
                    emit(i, endVar, Kind.Variable)
                    i = endVar
                    segment = i
                    continue
                }
                if (code[i] == '#' && (i == from || code[i - 1] == ' ')) {
                    other(segment, i)
                    emit(i, e, Kind.Comment)
                    return
                }
                i++
            }
            other(segment, e)
        }

        private fun dockerLine(code: String, start: Int, e: Int) {
            val i = indentAt(code, start, e)
            if (i >= e) {
                dockerShell = false
                return
            }
            if (code[i] == '#') {
                emit(i, e, Kind.Comment)
                return
            }
            var from = i
            if (!dockerShell) {
                var j = i
                while (j < e && code[j].isLetter()) j++
                val word = code.substring(i, j).uppercase()
                if (word in lang.keywords) {
                    emit(i, j, Kind.Keyword)
                    from = j
                    dockerShell = word == "RUN" || word == "CMD" || word == "ENTRYPOINT" || word == "SHELL"
                    if (!dockerShell) {
                        dockerArgs(code, j, e)
                        dockerShell = false
                        return
                    }
                }
            }
            val continued = code.substring(i, e).trimEnd().endsWith("\\")
            val rest = indentAt(code, from, e)
            if (rest < e && code[rest] == '[') {
                json(code, rest, e)
            } else {
                sub(code, from, e, Languages.shell)
            }
            dockerShell = continued
        }

        private fun dockerArgs(code: String, from: Int, e: Int) {
            var i = from
            while (i < e) {
                val c = code[i]
                when {
                    c == ' ' -> i++
                    c == '"' || c == '\'' -> {
                        emit(i, i + 1, Kind.String)
                        i = stringBody(code, i + 1, e, c.toString(), false, true, multiline = false)
                    }
                    c == '$' -> {
                        val endVar = if (i + 1 < e && code[i + 1] == '{') code.indexOf('}', i).let { if (it < 0 || it >= e) e else it + 1 } else {
                            var j = i + 1
                            while (j < e && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                            j
                        }
                        emit(i, endVar, Kind.Variable)
                        i = maxOf(endVar, i + 1)
                    }
                    c == '-' && i + 1 < e && code[i + 1] == '-' -> {
                        var j = i + 2
                        while (j < e && code[j] != '=' && code[j] != ' ') j++
                        emit(i, j, Kind.Attribute)
                        i = j
                    }
                    else -> {
                        var j = i
                        while (j < e && code[j] != ' ' && code[j] != '$' && code[j] != '"') j++
                        val word = code.substring(i, j)
                        if (word.uppercase() == "AS") emit(i, j, Kind.Keyword) else if (word.all { it.isDigit() || it == '/' }) emit(i, j, Kind.Number)
                        i = maxOf(j, i + 1)
                    }
                }
            }
        }
    }
}
