package opensec.pibolt.app.ui.markdown

/** How a language is tokenised: most share the code tokenizer, the rest have one of their own. */
enum class LangMode { Code, Shell, Markup, Css, Json, Yaml, Toml, Ini, Markdown, Diff, Makefile, Dockerfile, Plain }

/** String interpolation inside double-quoted (or template) strings. */
enum class Interp { None, Dollar, DollarBrace, Hash, Brace, Swift, Php, PowerShell }

/** One language for [Highlighter]: word lists and the lexical features its code tokenizer turns on. */
class Lang(
    val id: String,
    val mode: LangMode = LangMode.Code,
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val constants: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val lineComments: List<String> = listOf("//"),
    val blockComment: Pair<String, String>? = "/*" to "*/",
    val nestedComments: Boolean = false,
    /** Quote characters that open strings ('\'' may be a char literal or lifetime, see [lifetimes]). */
    val quotes: String = "\"'",
    /** Backtick strings span lines (JS templates, Go raw strings, shell command substitution). */
    val backtick: Boolean = false,
    val tripleQuotes: Boolean = false,
    val interp: Interp = Interp.None,
    /** [interp] applies inside backtick strings only (JS template literals). */
    val interpInBacktickOnly: Boolean = false,
    val caseInsensitive: Boolean = false,
    /** `@Name` is an annotation or decorator. */
    val annotations: Boolean = false,
    /** `#include` style lines (C family) or `#[attr]` (Rust). */
    val preprocessor: Boolean = false,
    /** `$name` is a variable (PHP, Perl, PowerShell). */
    val dollarVariables: Boolean = false,
    /** `@name` / `@@name` are variables (Ruby). */
    val atVariables: Boolean = false,
    /** `/re/` literals where an expression may start (JS, TS). */
    val regexLiterals: Boolean = false,
    /** `<Tag attr="x">` in expressions (JSX, TSX). */
    val jsx: Boolean = false,
    /** `'a` lifetimes (Rust): a quote not closed by the next character but one. */
    val lifetimes: Boolean = false,
    /** `name!(` macros (Rust). */
    val macros: Boolean = false,
    /** `:symbol` (Ruby). */
    val symbols: Boolean = false,
    /** Capitalised identifiers read as types. */
    val capitalTypes: Boolean = true,
    /** String prefixes such as Python's `f"…"`, `r"…"`, `b"…"`, C#'s `$"…"` / `@"…"`. */
    val stringPrefixes: Set<String> = emptySet(),
    /** Lua `[[ … ]]` long strings and `--[[ … ]]` comments. */
    val luaLong: Boolean = false,
)

object Languages {
    private fun words(s: String): Set<String> = s.trim().split(Regex("\\s+")).toSet()

    private val cTypes = words(
        "void char short int long float double signed unsigned bool _Bool size_t ssize_t ptrdiff_t intptr_t uintptr_t " +
            "int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t wchar_t FILE",
    )
    private val cKeywords = words(
        "auto break case const continue default do else enum extern for goto if inline register restrict return " +
            "sizeof static struct switch typedef union volatile while _Alignas _Alignof _Atomic _Generic _Noreturn " +
            "_Static_assert _Thread_local",
    )
    private val cppKeywords = cKeywords + words(
        "alignas alignof and asm catch class concept consteval constexpr constinit co_await co_return co_yield " +
            "decltype delete explicit export friend mutable namespace new noexcept not operator or override private " +
            "protected public requires static_assert static_cast dynamic_cast reinterpret_cast const_cast template this " +
            "thread_local throw try typeid typename using virtual final xor",
    )
    private val jsKeywords = words(
        "async await break case catch class const continue debugger default delete do else export extends finally for " +
            "from function get if import in instanceof let new of return set static super switch this throw try typeof " +
            "var void while with yield as",
    )
    private val tsKeywords = jsKeywords + words(
        "abstract declare enum implements interface is keyof module namespace override private protected public readonly " +
            "satisfies type infer unique asserts",
    )
    private val jsConstants = words("true false null undefined NaN Infinity")
    private val tsTypes = words("string number boolean bigint symbol any unknown never object void")
    private val jsBuiltins = words("console require")

    val kotlin = Lang(
        "kotlin",
        keywords = words(
            "abstract actual annotation as break by catch class companion const constructor continue crossinline data " +
                "delegate do dynamic else enum expect external field final finally for fun get if import in infix init " +
                "inline inner interface internal is lateinit noinline object open operator out override package private " +
                "protected public reified return sealed set super suspend tailrec this throw try typealias typeof val " +
                "value var vararg when where while",
        ),
        types = words("Int Long Short Byte Double Float Boolean Char String Unit Any Nothing Array List Map Set"),
        constants = words("true false null"),
        builtins = words("println print listOf mapOf setOf mutableListOf mutableMapOf arrayOf require check error TODO lazy"),
        nestedComments = true,
        tripleQuotes = true,
        interp = Interp.Dollar,
        annotations = true,
        quotes = "\"'",
    )

    val java = Lang(
        "java",
        keywords = words(
            "abstract assert break case catch class const continue default do else enum exports extends final finally for " +
                "goto if implements import instanceof interface module native new non-sealed package permits private " +
                "protected public record requires return sealed static strictfp super switch synchronized this throw " +
                "throws transient try var void volatile while yield",
        ),
        types = words("boolean byte char double float int long short String Object Integer Long Boolean"),
        constants = words("true false null"),
        tripleQuotes = true,
        annotations = true,
    )

    val swift = Lang(
        "swift",
        keywords = words(
            "actor associatedtype async await break case catch class continue convenience default defer deinit didSet do " +
                "dynamic else enum extension fallthrough fileprivate final for func get guard if import in indirect infix " +
                "init inout internal is lazy let mutating nonisolated nonmutating open operator optional override " +
                "postfix precedencegroup prefix private protocol public repeat required rethrows return set some any static " +
                "struct subscript super switch throw throws try typealias unowned var weak where while willSet self Self",
        ),
        types = words("Int Double Float Bool String Character Void Any AnyObject"),
        constants = words("true false nil"),
        builtins = words("print debugPrint fatalError precondition assert"),
        nestedComments = true,
        tripleQuotes = true,
        interp = Interp.Swift,
        annotations = true,
        quotes = "\"",
        preprocessor = true,
    )

    val javascript = Lang(
        "javascript",
        keywords = jsKeywords,
        constants = jsConstants,
        builtins = jsBuiltins,
        backtick = true,
        interp = Interp.DollarBrace,
        interpInBacktickOnly = true,
        regexLiterals = true,
        annotations = true,
    )
    val jsx = Lang(
        "jsx", keywords = jsKeywords, constants = jsConstants, builtins = jsBuiltins, backtick = true, interp = Interp.DollarBrace,
        interpInBacktickOnly = true, regexLiterals = true, jsx = true,
    )
    val typescript = Lang(
        "typescript", keywords = tsKeywords, types = tsTypes, constants = jsConstants, builtins = jsBuiltins, backtick = true,
        interp = Interp.DollarBrace, interpInBacktickOnly = true, regexLiterals = true, annotations = true,
    )
    val tsx = Lang(
        "tsx", keywords = tsKeywords, types = tsTypes, constants = jsConstants, builtins = jsBuiltins, backtick = true,
        interp = Interp.DollarBrace, interpInBacktickOnly = true, regexLiterals = true, jsx = true, annotations = true,
    )

    val python = Lang(
        "python",
        keywords = words(
            "and as assert async await break class continue def del elif else except finally for from global if import in " +
                "is lambda nonlocal not or pass raise return try while with yield match case self cls",
        ),
        types = words("int float str bool bytes list dict set tuple object complex type"),
        constants = words("True False None NotImplemented Ellipsis __name__ __file__"),
        builtins = words(
            "print len range open input isinstance issubclass enumerate zip map filter sorted reversed sum min max abs any " +
                "all iter next super getattr setattr hasattr repr format round divmod id hash vars dir",
        ),
        lineComments = listOf("#"),
        blockComment = null,
        tripleQuotes = true,
        interp = Interp.Brace,
        annotations = true,
        stringPrefixes = words("f r b u rb br fr rf F R B U Rb bR"),
    )

    val rust = Lang(
        "rust",
        keywords = words(
            "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub " +
                "ref return self Self static struct super trait type union unsafe use where while yield",
        ),
        types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box"),
        constants = words("true false None Some Ok Err"),
        nestedComments = true,
        preprocessor = true,
        lifetimes = true,
        macros = true,
        stringPrefixes = words("b r br"),
    )

    val go = Lang(
        "go",
        keywords = words(
            "break case chan const continue default defer else fallthrough for func go goto if import interface map package " +
                "range return select struct switch type var",
        ),
        types = words(
            "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 " +
                "uint32 uint64 uintptr any comparable",
        ),
        constants = words("true false nil iota"),
        builtins = words("append cap clear close complex copy delete imag len make max min new panic print println real recover"),
        backtick = true,
        capitalTypes = true,
    )

    val c = Lang("c", keywords = cKeywords, types = cTypes, constants = words("NULL true false"), preprocessor = true)
    val cpp = Lang(
        "cpp", keywords = cppKeywords, types = cTypes + words("string vector map set unordered_map unique_ptr shared_ptr auto"),
        constants = words("nullptr NULL true false"), preprocessor = true,
    )
    val csharp = Lang(
        "csharp",
        keywords = words(
            "abstract as async await base break case catch checked class const continue default delegate do else enum event " +
                "explicit extern finally fixed for foreach get goto if implicit in init interface internal is lock namespace " +
                "new operator out override params partial private protected public readonly record ref required return " +
                "sealed set sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using value var " +
                "virtual void volatile when where while with yield",
        ),
        types = words("bool byte char decimal double float int long object sbyte short string uint ulong ushort dynamic nint nuint"),
        constants = words("true false null"),
        interp = Interp.Brace,
        preprocessor = true,
        stringPrefixes = words("$ @ $@ @$"),
    )

    val ruby = Lang(
        "ruby",
        keywords = words(
            "alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo rescue " +
                "retry return self super then undef unless until when while yield require require_relative include extend " +
                "attr_reader attr_writer attr_accessor private protected public lambda proc raise loop",
        ),
        constants = words("true false nil __FILE__ __LINE__"),
        builtins = words("puts print p pp gets format sprintf"),
        lineComments = listOf("#"),
        blockComment = "=begin" to "=end",
        interp = Interp.Hash,
        atVariables = true,
        symbols = true,
    )

    val php = Lang(
        "php",
        keywords = words(
            "abstract and array as break callable case catch class clone const continue declare default do echo else elseif " +
                "empty enddeclare endfor endforeach endif endswitch endwhile enum extends final finally fn for foreach function " +
                "global goto if implements include include_once instanceof insteadof interface isset list match namespace new " +
                "or print private protected public readonly require require_once return static switch throw trait try unset " +
                "use var while xor yield",
        ),
        types = words("int float string bool array object mixed void never iterable null self static"),
        constants = words("true false null TRUE FALSE NULL"),
        lineComments = listOf("//", "#"),
        interp = Interp.Php,
        dollarVariables = true,
        caseInsensitive = true,
    )

    val shell = Lang(
        "shell",
        mode = LangMode.Shell,
        keywords = words("if then else elif fi for while until do done case esac in function select time return break continue"),
        builtins = words(
            "echo printf cd pwd export unset local readonly declare typeset source alias unalias set shift exit exec eval trap " +
                "read test true false wait kill jobs bg fg umask type command builtin let getopts",
        ),
    )

    val powershell = Lang(
        "powershell",
        keywords = words(
            "begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach " +
                "from function hidden if in param process return static switch throw trap try until using var while workflow",
        ),
        constants = words("\$true \$false \$null"),
        lineComments = listOf("#"),
        blockComment = "<#" to "#>",
        interp = Interp.PowerShell,
        dollarVariables = true,
        caseInsensitive = true,
        capitalTypes = false,
    )

    val sql = Lang(
        "sql",
        keywords = words(
            "select from where and or not in is null like ilike between exists join inner left right full outer cross on " +
                "using group by order having limit offset union all distinct as insert into values update set delete " +
                "create alter drop table view index unique primary key foreign references constraint default check " +
                "database schema if begin commit rollback transaction case when then else end with recursive returning " +
                "asc desc nulls first last cascade truncate grant revoke explain analyze partition over window fetch next " +
                "rows only replace temporary temp trigger function procedure language returns declare",
        ),
        types = words(
            "int integer bigint smallint tinyint serial bigserial decimal numeric real float double precision boolean bool " +
                "char varchar text date time timestamp timestamptz interval uuid json jsonb blob bytea",
        ),
        constants = words("true false null"),
        lineComments = listOf("--"),
        quotes = "'\"",
        caseInsensitive = true,
        capitalTypes = false,
    )

    val lua = Lang(
        "lua",
        keywords = words("and break do else elseif end for function goto if in local not or repeat return then until while"),
        constants = words("true false nil"),
        builtins = words("print pairs ipairs require type tostring tonumber setmetatable getmetatable pcall error assert select"),
        lineComments = listOf("--"),
        blockComment = null,
        luaLong = true,
    )

    val dart = Lang(
        "dart",
        keywords = words(
            "abstract as assert async await base break case catch class const continue covariant default deferred do dynamic " +
                "else enum export extends extension external factory final finally for get hide if implements import in " +
                "interface is late library mixin new on operator part required rethrow return sealed set show static super " +
                "switch sync this throw try typedef var void when while with yield",
        ),
        types = words("int double num bool String List Map Set Future Stream Object dynamic void Iterable"),
        constants = words("true false null"),
        tripleQuotes = true,
        interp = Interp.Dollar,
        annotations = true,
        stringPrefixes = words("r"),
    )

    val groovy = Lang(
        "groovy",
        keywords = java.keywords + words("def as in trait it"),
        constants = words("true false null"),
        tripleQuotes = true,
        interp = Interp.Dollar,
        annotations = true,
    )

    val scala = Lang(
        "scala",
        keywords = words(
            "abstract case catch class def do else enum export extends final finally for given if implicit import lazy match " +
                "new object override package private protected return sealed super then this throw trait try type using val " +
                "var while with yield",
        ),
        constants = words("true false null"),
        tripleQuotes = true,
        annotations = true,
    )

    val json = Lang("json", mode = LangMode.Json, constants = words("true false null"))
    val yaml = Lang("yaml", mode = LangMode.Yaml, constants = words("true false null yes no on off True False Null TRUE FALSE NULL ~"))
    val toml = Lang("toml", mode = LangMode.Toml, constants = words("true false inf nan"))
    val ini = Lang("ini", mode = LangMode.Ini, constants = words("true false yes no on off"))
    val markup = Lang("xml", mode = LangMode.Markup)
    val html = Lang("html", mode = LangMode.Markup)
    val css = Lang("css", mode = LangMode.Css)
    val scss = Lang("scss", mode = LangMode.Css)
    val markdown = Lang("markdown", mode = LangMode.Markdown)
    val diff = Lang("diff", mode = LangMode.Diff)
    val makefile = Lang("makefile", mode = LangMode.Makefile, keywords = words("include ifeq ifneq ifdef ifndef else endif define endef export override"))
    val dockerfile = Lang(
        "dockerfile",
        mode = LangMode.Dockerfile,
        keywords = words(
            "FROM RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL " +
                "HEALTHCHECK SHELL AS",
        ),
    )
    val plain = Lang("text", mode = LangMode.Plain)

    /** C-like fallback for untagged code that detection could not place. */
    val generic = Lang(
        "",
        keywords = words(
            "abstract and as async await break case catch class const continue def default defer do elif else enum except " +
                "export extends final finally fn for from fun func function go if impl implements import in interface is " +
                "lambda let loop match mod mut new not object or override package pass private protected pub public raise " +
                "readonly return self static struct super switch this throw throws trait try type typeof use val var void " +
                "when where while with yield then fi done esac echo local sealed internal suspend satisfies keyof declare",
        ),
        constants = words("true false null nil None True False undefined"),
        lineComments = listOf("//", "#"),
        backtick = true,
    )

    private val byAlias: Map<String, Lang> = buildMap {
        fun reg(lang: Lang, vararg aliases: String) {
            put(lang.id, lang)
            for (a in aliases) put(a, lang)
        }
        reg(kotlin, "kt", "kts")
        reg(java)
        reg(swift)
        reg(javascript, "js", "mjs", "cjs", "node", "es6", "ecmascript")
        reg(jsx)
        reg(typescript, "ts", "mts", "cts")
        reg(tsx)
        reg(python, "py", "py3", "python3", "gyp", "ipython", "pyi", "bzl", "starlark")
        reg(rust, "rs")
        reg(go, "golang")
        reg(c, "h", "objc", "objective-c")
        reg(cpp, "c++", "cc", "cxx", "hpp", "hh", "hxx", "ino", "cuda", "cu")
        reg(csharp, "cs", "c#", "dotnet")
        reg(ruby, "rb", "gemfile", "rake", "podfile")
        reg(php, "php3", "php4", "php5", "php7", "php8")
        reg(shell, "sh", "bash", "zsh", "ksh", "fish", "console", "shellsession", "terminal", "sh-session", "shell-session", "bashrc", "zshrc", "env", "dotenv")
        reg(powershell, "ps1", "psm1", "pwsh", "ps")
        reg(sql, "mysql", "postgres", "postgresql", "psql", "pgsql", "sqlite", "plsql", "tsql", "mssql", "sqlite3", "hql")
        reg(json, "json5", "jsonc", "jsonl", "geojson", "webmanifest", "ndjson")
        reg(yaml, "yml")
        reg(toml)
        reg(ini, "cfg", "conf", "properties", "editorconfig", "gitconfig", "desktop", "service", "prefs")
        reg(markup, "svg", "xhtml", "plist", "xaml", "xsd", "xsl", "rss", "atom", "wsdl", "csproj")
        reg(html, "htm", "vue", "svelte", "astro", "erb", "jinja", "handlebars", "hbs")
        reg(css, "less")
        reg(scss, "sass")
        reg(markdown, "md", "mdx", "rmd")
        reg(diff, "patch", "udiff")
        reg(makefile, "make", "mk", "mak", "gnumakefile")
        reg(dockerfile, "docker", "containerfile")
        reg(lua, "luau")
        reg(dart)
        reg(groovy, "gradle")
        reg(scala, "sc", "sbt")
        reg(plain, "txt", "plain", "plaintext", "log", "output", "none", "nohighlight", "raw")
    }

    /** The language for a fence tag or alias (case-insensitive), or null when it is unknown. */
    fun of(tag: String): Lang? = byAlias[tag.trim().lowercase()]

    /** Every alias that names a known language (the viewer resolves titles through it). */
    val aliases: Set<String> get() = byAlias.keys

    /**
     * A language for untagged code, from its first lines: shebangs, `<?php`, `<?xml`, JSON, diffs, shell prompts and
     * each language's telltale words. Empty when nothing is clear.
     */
    fun detect(code: String): String {
        val head = code.take(3_000)
        val t = head.trimStart()
        if (t.isEmpty()) return ""
        val first = t.substringBefore('\n').trim()
        if (first.startsWith("#!")) {
            return when {
                "python" in first -> "python"
                "node" in first || "deno" in first || "bun" in first -> "javascript"
                "ruby" in first -> "ruby"
                "php" in first -> "php"
                "pwsh" in first || "powershell" in first -> "powershell"
                "lua" in first -> "lua"
                else -> "shell"
            }
        }
        if (t.startsWith("<?php")) return "php"
        if (t.startsWith("<?xml") || t.startsWith("<svg") || t.startsWith("<manifest")) return "xml"
        if (t.startsWith("<!DOCTYPE", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)) return "html"
        if (UnifiedDiff.looksLikeDiff(head)) return "diff"
        if ((t.startsWith("{") || t.startsWith("[")) && looksLikeJson(t)) return "json"
        if (Regex("^\\$ \\S", RegexOption.MULTILINE).containsMatchIn(head)) return "shell"
        val lines = head.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(40).toList()
        fun any(r: String) = Regex(r, RegexOption.MULTILINE).containsMatchIn(head)
        return when {
            any("^package main\\b") || any("^func \\w+\\(.*\\)\\s*[\\w*\\[\\]]*\\s*\\{") || any(":= ") && any("\\bfunc\\b") -> "go"
            any("^\\s*fn \\w+") && (any("\\blet mut\\b") || any("->") || any("::")) || any("^use \\w+(::\\w+)+;") -> "rust"
            any("^\\s*(fun|val|var) \\w+") && (any("^\\s*fun ") || any("^import [a-z]+\\.[a-z.]+$")) || any("^\\s*data class ") -> "kotlin"
            any("^#include\\s*[<\"]") -> if (any("std::|template\\s*<|namespace |cout|class ")) "cpp" else "c"
            any("^\\s*(public|private|protected)?\\s*(static\\s+)?(final\\s+)?class \\w+") && any(";\\s*$") && any("\\b(public|private) ") -> if (any("\\busing System|\\bnamespace\\b|\\{ get;")) "csharp" else "java"
            any("^\\s*def \\w+\\(.*\\):\\s*$") || any("^\\s*(from \\w[\\w.]* )?import \\w+") && !any(";\\s*$") && !any("\\bfrom ['\"]") || any("^\\s*if __name__ == ") || any("^\\s*print\\(") && !any(";\\s*$") -> "python"
            any("^\\s*(import|export) .* from ['\"]") || any("^\\s*(const|let) \\w+\\s*[:=]") || any("=> \\{") || any("\\bconsole\\.log\\(") -> if (any(":\\s*(string|number|boolean)\\b|^\\s*interface \\w+|^\\s*type \\w+ =")) "typescript" else "javascript"
            any("^\\s*(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|WITH)\\b") -> "sql"
            any("^\\s*(def|class|module) \\w+") && any("^\\s*end\\s*$") -> "ruby"
            any("^\\s*func \\w+") && any("\\b(let|var) \\w+") || any("^import (SwiftUI|UIKit|Foundation)$") -> "swift"
            any("^(FROM|RUN|COPY|WORKDIR|ENTRYPOINT) ") && any("^FROM ") -> "dockerfile"
            any("^[\\w.-]+:( [\\w./$()-]+)*\\s*$") && any("^\\t") -> "makefile"
            any("^\\s*[\\w-]+:\\s") && lines.count { Regex("^[\\w\"'-]+:( |$)|^- ").containsMatchIn(it) } * 2 >= lines.size -> "yaml"
            any("^\\[[\\w.\"-]+\\]\\s*$") && any("^[\\w.-]+\\s*=") -> if (any("=\\s*\"") || any("=\\s*\\[")) "toml" else "ini"
            // Possessive and atomic: the repeated selector part cannot be split in several ways, so a line of words
            // ("a a a a …") fails in linear time instead of backtracking exponentially (PR #254 review).
            any("^\\s*[.#]?[\\w-]++(?>\\s*[,>+~]?\\s*[.#]?[\\w:-]++)*+\\s*\\{\\s*$") && any("^\\s*[\\w-]+:\\s*[^;]+;\\s*$") -> "css"
            any("^\\s*<\\w+[^>]*>") && any("</\\w+>") -> "html"
            any("^\\s*(Get|Set|New|Remove|Invoke|Write)-\\w+") || any("\\\$env:") -> "powershell"
            any("^\\s*(sudo |npm |npx |yarn |pnpm |git |cd |ls |mkdir |curl |brew |apt(-get)? |pip3? |docker |kubectl |export |echo |chmod |cargo |go |make )") -> "shell"
            any("^\\s*local \\w+ = ") || any("\\bfunction \\w+\\(.*\\)\\s*$") && any("^\\s*end\\s*$") -> "lua"
            else -> ""
        }
    }

    private fun looksLikeJson(t: String): Boolean {
        val s = t.trimEnd()
        val open = s[0]
        val close = if (open == '{') '}' else ']'
        if (open == '[' && s.length > 1 && s[1].isLetter()) return false
        return s.endsWith(close) || s.contains("\":") || s.length > 2 && s[1] in "\n \"{[0123456789"
    }
}
