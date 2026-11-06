package opensec.pibolt.app.ui.markdown

/**
 * A cheap, language-agnostic highlighter: comments, strings, numbers, keywords and call names for the C/JS/Kotlin/
 * Python/Go/Rust/shell family. One linear pass; good enough to make code readable, not a parser.
 */
object Highlighter {
    enum class Kind { Keyword, String, Comment, Number, Function }

    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val keywords = setOf(
        "abstract", "and", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "data", "def",
        "default", "defer", "del", "do", "elif", "else", "enum", "except", "export", "extends", "false", "final", "finally",
        "fn", "for", "from", "fun", "func", "function", "go", "if", "impl", "implements", "import", "in", "interface", "is",
        "lambda", "let", "loop", "match", "mod", "mut", "new", "nil", "None", "not", "null", "object", "or", "override",
        "package", "pass", "private", "protected", "pub", "public", "raise", "readonly", "return", "self", "static",
        "struct", "super", "switch", "this", "throw", "throws", "trait", "true", "True", "False", "try", "type", "typeof",
        "undefined", "use", "val", "var", "void", "when", "where", "while", "with", "yield", "then", "fi", "done", "esac",
        "echo", "export", "local", "sealed", "internal", "suspend", "override", "satisfies", "keyof", "declare",
    )

    /** Languages where `#` starts a comment. */
    private val hashComments = setOf("", "sh", "bash", "zsh", "shell", "console", "py", "python", "rb", "ruby", "yaml", "yml", "toml", "make", "makefile", "dockerfile", "r", "perl", "conf", "ini")

    fun tokenize(code: String, language: String = ""): List<Token> {
        if (code.length > 30_000) return emptyList()
        val lang = language.lowercase()
        val hash = lang in hashComments
        val slash = lang !in setOf("sh", "bash", "zsh", "shell", "console", "py", "python", "yaml", "yml", "toml", "ini")
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
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n && code[j] != c) {
                        if (code[j] == '\\') j++
                        if (c != '`' && j < n && code[j] == '\n') break
                        j++
                    }
                    val end = (j + 1).coerceAtMost(n)
                    tokens += Token(i, end, Kind.String)
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
                        j < n && code[j] == '(' -> tokens += Token(i, j, Kind.Function)
                    }
                    i = j
                }
                else -> i++
            }
        }
        return tokens
    }
}
