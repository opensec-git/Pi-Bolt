package opensec.pibolt.app.ui.markdown

/**
 * A cheap, language-agnostic highlighter: comments, strings, numbers, keywords, types, call names, punctuation and the
 * shell `$` prompt for the C/JS/Kotlin/Python/Go/Rust/shell family. One linear pass; good enough to make code
 * readable, not a parser. Plain-text languages (`text`, `markdown`, `log`) get no tokens.
 */
object Highlighter {
    enum class Kind { Keyword, String, Comment, Number, Function, Type, Punctuation, Prompt }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    /** Above this size a block is shown plain. */
    const val MAX_CHARS = 30_000

    private val keywords = setOf(
        "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "data", "def",
        "default", "defer", "del", "do", "elif", "else", "enum", "except", "export", "extends", "false", "final", "finally",
        "fn", "for", "from", "fun", "func", "function", "go", "if", "impl", "implements", "import", "in", "interface", "is",
        "lambda", "let", "loop", "match", "mod", "mut", "new", "nil", "None", "not", "null", "object", "or", "override",
        "package", "pass", "private", "protected", "pub", "public", "raise", "readonly", "return", "self", "static",
        "struct", "super", "switch", "this", "throw", "throws", "trait", "true", "True", "False", "try", "type", "typeof",
        "undefined", "use", "val", "var", "void", "when", "where", "while", "with", "yield", "then", "fi", "done", "esac",
        "echo", "local", "sealed", "internal", "suspend", "satisfies", "keyof", "declare",
    )

    /** Lower-case built-in type names that read as types wherever they appear. */
    private val builtinTypes = setOf(
        "string", "number", "boolean", "bigint", "any", "unknown", "never", "symbol", "int", "uint", "float", "double",
        "bool", "char", "str", "byte", "short", "long", "usize", "isize", "u8", "u16", "u32", "u64", "i8", "i16", "i32",
        "i64", "f32", "f64",
    )

    private val shells = setOf("sh", "bash", "zsh", "shell", "console", "fish", "powershell", "ps1")

    /** Languages shown without highlighting. */
    private val plain = setOf("text", "txt", "plain", "plaintext", "md", "markdown", "log", "output", "diff", "patch")

    /** Languages where `#` starts a comment. */
    private val hashComments = setOf("", "sh", "bash", "zsh", "shell", "console", "fish", "py", "python", "rb", "ruby", "yaml", "yml", "toml", "make", "makefile", "dockerfile", "r", "perl", "conf", "ini")

    private val noSlashComments = setOf("sh", "bash", "zsh", "shell", "console", "fish", "py", "python", "yaml", "yml", "toml", "ini")

    private const val PUNCTUATION = "{}()[];,.:=+-*/%<>!&|^~?@"

    fun tokenize(code: String, language: String = ""): List<Token> {
        if (code.length > MAX_CHARS) return emptyList()
        val lang = language.lowercase()
        if (lang in plain) return emptyList()
        val hash = lang in hashComments
        val slash = lang !in noSlashComments
        val shell = lang in shells
        val json = lang == "json" || lang == "jsonc" || lang == "json5"
        val tokens = ArrayList<Token>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                slash && c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    tokens += Token(i, end, Kind.Comment)
                    i = end
                }
                slash && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    tokens += Token(i, end, Kind.Comment)
                    i = end
                }
                hash && c == '#' && (i == 0 || code[i - 1].isWhitespace()) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    tokens += Token(i, end, Kind.Comment)
                    i = end
                }
                shell && c == '$' && i + 1 < n && code[i + 1] == ' ' && atLineStart(code, i) -> {
                    tokens += Token(i, i + 1, Kind.Prompt)
                    i++
                }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n && code[j] != c) {
                        if (code[j] == '\\') j++
                        if (c != '`' && j < n && code[j] == '\n') break
                        j++
                    }
                    val end = (j + 1).coerceAtMost(n)
                    // JSON object keys stay in the base ink; only values are coloured.
                    if (!(json && c == '"' && nextNonSpace(code, end) == ':')) tokens += Token(i, end, Kind.String)
                    i = end
                }
                c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    tokens += Token(i, j, Kind.Number)
                    i = j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
                    val word = code.substring(i, j)
                    when {
                        word in keywords -> tokens += Token(i, j, Kind.Keyword)
                        isTypeName(word) -> tokens += Token(i, j, Kind.Type)
                        j < n && code[j] == '(' -> tokens += Token(i, j, Kind.Function)
                    }
                    i = j
                }
                c in PUNCTUATION -> {
                    // One token per run, so `=>` or `?.` is a single span. A run stops before a comment opener.
                    var j = i + 1
                    while (j < n && code[j] in PUNCTUATION && !(slash && code[j] == '/' && j + 1 < n && (code[j + 1] == '/' || code[j + 1] == '*'))) j++
                    tokens += Token(i, j, Kind.Punctuation)
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }

    /** `Foo`, `HttpClient` and built-ins such as `string`; ALL_CAPS constants stay plain. */
    private fun isTypeName(word: String): Boolean {
        if (word in builtinTypes) return true
        if (!word[0].isUpperCase()) return false
        return word.any { it.isLowerCase() }
    }

    private fun atLineStart(code: String, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && (code[j] == ' ' || code[j] == '\t')) j--
        return j < 0 || code[j] == '\n'
    }

    private fun nextNonSpace(code: String, from: Int): Char? {
        var j = from
        while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++
        return code.getOrNull(j)
    }
}
