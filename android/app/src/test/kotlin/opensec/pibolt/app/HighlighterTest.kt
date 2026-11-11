package opensec.pibolt.app

import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.markdown.Highlighter.Kind
import opensec.pibolt.app.ui.markdown.Languages
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlighterTest {
    /** The kind of the token that covers exactly [text] in [code] ([nth] occurrence), or null. */
    private fun kindOf(code: String, lang: String, text: String, nth: Int = 0): Kind? {
        val tokens = Highlighter.tokenize(code, lang)
        var from = 0
        var at = -1
        repeat(nth + 1) {
            at = code.indexOf(text, from)
            from = at + 1
        }
        assertTrue(at >= 0, "'$text' not in code")
        return tokens.firstOrNull { it.start <= at && it.end >= at + text.length }?.kind
    }

    private fun check(lang: String, code: String, vararg expected: Pair<String, Kind?>) {
        for ((text, kind) in expected) assertEquals(kind, kindOf(code, lang, text), "$lang: '$text'")
    }

    @Test
    fun kotlin() = check(
        "kotlin",
        "@Composable\nfun Greeting(name: String): String {\n    val n = 0xFF + 1_000L\n    return \"Hi \${name}!\\n\" // c\n}",
        "@Composable" to Kind.Attribute, "fun" to Kind.Keyword, "Greeting" to Kind.Function, "String" to Kind.Type,
        "0xFF" to Kind.Number, "1_000L" to Kind.Number, "\${" to Kind.Escape, "\\n" to Kind.Escape, "// c" to Kind.Comment,
        "\"Hi " to Kind.String,
    )

    @Test
    fun typescriptAndJsx() {
        check(
            "ts",
            "interface User { id: number }\nconst re = /^u-(\\d+)$/gi;\nfetch(`/api/\${id}`).then((r) => r.json() as Promise<User>);",
            "interface" to Kind.Keyword, "User" to Kind.Type, "number" to Kind.Type, "/^u-(\\d+)$/gi" to Kind.Regex,
            "fetch" to Kind.Function, "`/api/" to Kind.String, "\${" to Kind.Escape, "=>" to Kind.Operator, "Promise" to Kind.Type,
        )
        check(
            "tsx",
            "const B = () => <button className=\"btn\" onClick={() => go(1)}>Go</button>;",
            "button" to Kind.Tag, "className" to Kind.Attribute, "\"btn\"" to Kind.String, "go" to Kind.Function,
        )
        // A comparison is not a tag, a division is not a regex.
        check("tsx", "if (a < b) x = c / d / e;", "b" to null, "d" to null)
    }

    @Test
    fun python() = check(
        "py",
        "@cache\nclass P:\n    def norm(self) -> float:\n        \"\"\"Doc.\"\"\"\n        return f\"v={self.x:.2f}\" # c\nprint(None, True)",
        "@cache" to Kind.Attribute, "class" to Kind.Keyword, "P" to Kind.Type, "norm" to Kind.Function, "self" to Kind.Keyword,
        "float" to Kind.Type, "\"\"\"Doc.\"\"\"" to Kind.String, "{" to Kind.Escape, "# c" to Kind.Comment, "print" to Kind.Function,
        "None" to Kind.Constant, "True" to Kind.Constant,
    )

    @Test
    fun rust() = check(
        "rs",
        "#[derive(Debug)]\npub struct C<'a> { n: &'a str }\nfn main() { let mut v: Vec<i64> = vec![1]; println!(\"{}\", r#\"raw\"#); }",
        "#[derive(Debug)]" to Kind.Attribute, "'a" to Kind.Attribute, "Vec" to Kind.Type, "vec!" to Kind.Function,
        "println!" to Kind.Function, "r#\"raw\"#" to Kind.String, "i64" to Kind.Type, "mut" to Kind.Keyword,
    )

    @Test
    fun goSwiftJava() {
        check("go", "func main() {\n\tch := make(chan int)\n\tfmt.Printf(`raw`)\n}", "func" to Kind.Keyword, "main" to Kind.Function, "make" to Kind.Function, "int" to Kind.Type, "Printf" to Kind.Function, "`raw`" to Kind.String)
        check("swift", "@State var c = 0\nText(\"n: \\(c)\")", "@State" to Kind.Attribute, "\\(" to Kind.Escape, "Text" to Kind.Type)
        check("java", "public static void main(String[] a) throws IOException {}", "public" to Kind.Keyword, "void" to Kind.Keyword, "String" to Kind.Type, "IOException" to Kind.Type)
    }

    @Test
    fun cFamily() {
        check("c", "#include <stdio.h>\nint main(void) { printf(\"%s\\n\", NULL); }", "#include" to Kind.Keyword, "<stdio.h>" to Kind.String, "int" to Kind.Type, "printf" to Kind.Function, "NULL" to Kind.Constant)
        check("c++", "template <typename T> std::vector<T> f();", "template" to Kind.Keyword, "typename" to Kind.Keyword, "vector" to Kind.Type)
        check("cs", "public override string ToString() => \$\"P {Name}\";", "override" to Kind.Keyword, "string" to Kind.Type, "ToString" to Kind.Function, "{" to Kind.Escape)
    }

    @Test
    fun scripting() {
        check("rb", "def greet(n) = puts \"Hi #{n}\" # c\n[1].each { |x| go(:sym) }", "def" to Kind.Keyword, "greet" to Kind.Function, "#{" to Kind.Escape, "# c" to Kind.Comment, ":sym" to Kind.Constant)
        check("php", "<?php\nfunction add(int \$a): int { return \$a; } // s", "<?php" to Kind.Tag, "function" to Kind.Keyword, "\$a" to Kind.Variable, "// s" to Kind.Comment)
        check("lua", "local function fib(n) return n end -- r", "local" to Kind.Keyword, "fib" to Kind.Function, "-- r" to Kind.Comment)
        check("dart", "void main() { print('s: \${1 + 2}'); }", "void" to Kind.Keyword, "print" to Kind.Function, "\${" to Kind.Escape, "1" to Kind.Number)
        check("ps1", "Get-ChildItem -Path \$env:HOME | Where-Object { \$_.Length -gt 1MB } # c", "Get-ChildItem" to Kind.Function, "-Path" to Kind.Attribute, "\$env:HOME" to Kind.Variable, "1MB" to Kind.Number, "# c" to Kind.Comment)
        check("sql", "SELECT COUNT(*) AS n FROM users WHERE active = TRUE; -- n", "SELECT" to Kind.Keyword, "COUNT" to Kind.Function, "TRUE" to Kind.Constant, "-- n" to Kind.Comment)
    }

    @Test
    fun shell() = check(
        "bash",
        "#!/usr/bin/env bash\n\$ set -euo pipefail\nfor f in *.md; do\n  echo \"f: \$f \${HOME}\" | grep -v '^#' > /dev/null\ndone\ncat <<EOF\nraw \$x\nEOF\nls",
        "#!/usr/bin/env bash" to Kind.Comment, "\$" to Kind.Prompt, "set" to Kind.Function, "-euo" to Kind.Attribute,
        "for" to Kind.Keyword, "echo" to Kind.Function, "\$f" to Kind.Variable, "\${HOME}" to Kind.Variable, "grep" to Kind.Function,
        "'^#'" to Kind.String, "raw \$x" to Kind.String, "ls" to Kind.Function,
    )

    @Test
    fun dataFormats() {
        check("json", "{ \"name\": \"pi\", \"n\": 12, \"ok\": true, \"x\": null }", "\"name\"" to Kind.Attribute, "\"pi\"" to Kind.String, "12" to Kind.Number, "true" to Kind.Constant, "null" to Kind.Constant)
        check("jsonc", "// c\n{ \"a\": 1 /* i */ }", "// c" to Kind.Comment, "/* i */" to Kind.Comment)
        check(
            "yml",
            "on: [push]\njobs:\n  build:\n    runs-on: ubuntu # r\n    steps:\n      - uses: a/b@v4\n        with: { depth: 0 }\n    run: |\n      echo hi\n    x: &anchor 1",
            "jobs" to Kind.Attribute, "push" to Kind.String, "runs-on" to Kind.Attribute, "ubuntu" to Kind.String, "# r" to Kind.Comment,
            "uses" to Kind.Attribute, "depth" to Kind.Attribute, "0" to Kind.Number, "      echo hi" to Kind.String, "&anchor" to Kind.Variable,
        )
        check("toml", "[package]\nname = \"pi\"\nedition = 2021 # e", "[package]" to Kind.Tag, "name" to Kind.Attribute, "\"pi\"" to Kind.String, "2021" to Kind.Number, "# e" to Kind.Comment)
        check("ini", "[server]\n; c\nport = 8080\nhost = localhost", "[server]" to Kind.Tag, "; c" to Kind.Comment, "port" to Kind.Attribute, "8080" to Kind.Number, "localhost" to Kind.String)
    }

    @Test
    fun markupAndStyles() {
        check(
            "xml",
            "<?xml version=\"1.0\"?>\n<a:b id=\"1\">\n  <!-- c -->\n  <c d='e' />\n</a:b>",
            "xml" to Kind.Keyword, "a:b" to Kind.Tag, "id" to Kind.Attribute, "\"1\"" to Kind.String, "<!-- c -->" to Kind.Comment, "d" to Kind.Attribute,
        )
        check("html", "<div class=\"c\">A &amp; B</div><script>let a = 1;</script>", "div" to Kind.Tag, "&amp;" to Kind.Escape, "let" to Kind.Keyword)
        check(
            "css",
            ".card:hover > li { color: #ff6600; margin: 0 4px !important; }\n@media (max-width: 600px) { .x { display: none; } }",
            ".card" to Kind.Attribute, ":hover" to Kind.Keyword, "li" to Kind.Tag, "color" to Kind.Attribute, "#ff6600" to Kind.Number,
            "4px" to Kind.Number, "!important" to Kind.Keyword, "@media" to Kind.Keyword, "display" to Kind.Attribute, "none" to Kind.Constant,
        )
        check("scss", "\$accent: #d97757;\n.btn { &:hover { color: darken(\$accent, 10%); } }", "\$accent" to Kind.Variable, "#d97757" to Kind.Number, "darken" to Kind.Function, "10%" to Kind.Number)
        check("md", "# Title\nSome **bold** and [link](https://x.y) `code`\n- item", "# Title" to Kind.Heading, "**bold**" to Kind.Emphasis, "[link]" to Kind.Function, "`code`" to Kind.String, "-" to Kind.Punctuation)
    }

    @Test
    fun buildFiles() {
        check("dockerfile", "FROM node:22 AS build\nRUN npm ci --ignore-scripts \\\n  && echo ok\nENV A=\$B", "FROM" to Kind.Keyword, "AS" to Kind.Keyword, "RUN" to Kind.Keyword, "npm" to Kind.Function, "--ignore-scripts" to Kind.Attribute, "echo" to Kind.Function, "\$B" to Kind.Variable)
        check("makefile", "CC := gcc\nbuild: deps\n\tgo build \$(FLAGS)\n.PHONY: build", "CC" to Kind.Attribute, "build" to Kind.Function, "go" to Kind.Function, "\$(FLAGS)" to Kind.Variable, ".PHONY" to Kind.Keyword)
        check("diff", "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n same", "--- a/x" to Kind.Heading, "@@ -1 +1 @@" to Kind.Attribute, "-old" to Kind.Deleted, "+new" to Kind.Inserted, "same" to null)
    }

    @Test
    fun aliasesResolve() {
        val pairs = mapOf(
            "ts" to "typescript", "py" to "python", "sh" to "shell", "zsh" to "shell", "kt" to "kotlin", "yml" to "yaml", "rb" to "ruby",
            "rs" to "rust", "cs" to "csharp", "c++" to "cpp", "ps1" to "powershell", "golang" to "go", "js" to "javascript",
            "jsx" to "jsx", "tsx" to "tsx", "h" to "c", "patch" to "diff", "md" to "markdown", "Dockerfile" to "dockerfile",
            "make" to "makefile", "htm" to "html", "svg" to "xml", "jsonc" to "json", "cfg" to "ini", "sass" to "scss", "console" to "shell",
            "mysql" to "sql", "swift" to "swift", "java" to "java", "lua" to "lua", "dart" to "dart", "toml" to "toml", "php" to "php",
        )
        for ((alias, id) in pairs) assertEquals(id, Languages.of(alias)?.id, alias)
        assertNull(Highlighter.langFor("text"))
        assertNotNull(Highlighter.langFor("unknown-lang"))
    }

    @Test
    fun detectsUntaggedCode() {
        val cases = mapOf(
            "#!/usr/bin/env python3\nimport sys" to "python",
            "def main():\n    print('x')" to "python",
            "package main\n\nfunc main() {}" to "go",
            "fn main() {\n    let mut x = 1;\n}" to "rust",
            "{\n  \"a\": 1\n}" to "json",
            "<?php echo 1;" to "php",
            "\$ npm test\nok" to "shell",
            "SELECT * FROM t;" to "sql",
            "#include <stdio.h>\nint main() {}" to "c",
            "const a = 1;\nconsole.log(a);" to "javascript",
            "interface A { b: string }\nconst x: number = 1;" to "typescript",
            "fun main() {\n    println(\"hi\")\n}" to "kotlin",
            "FROM node:22\nRUN npm ci" to "dockerfile",
            "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b" to "diff",
            "name: CI\non: push\njobs:\n  b: 1" to "yaml",
            "just some words here" to "",
        )
        for ((code, lang) in cases) assertEquals(lang, Languages.detect(code), code)
    }

    @Test
    fun streamingMatchesAFullTokenisation() {
        val fixture = javaClass.getResource("/markdown/fixture.md")!!.readText()
        val blocks = Markdown.parse(fixture).blocks.filterIsInstance<MdBlock.Code>()
        assertTrue(blocks.size > 30)
        for (block in blocks) {
            val code = block.code + "\n"
            val stream = Highlighter.Stream(block.language)
            var i = 0
            while (i < code.length) {
                val nl = code.indexOf('\n', i)
                i = nl + 1
                stream.update(code, i)
            }
            assertEquals(Highlighter.tokenize(code, block.language), stream.tokens, block.language)
        }
    }

    @Test
    fun tokensAreSortedAndInsideTheText() {
        val fixture = javaClass.getResource("/markdown/fixture.md")!!.readText()
        for (block in Markdown.parse(fixture).blocks.filterIsInstance<MdBlock.Code>()) {
            val tokens = Highlighter.tokenize(block.code, block.language)
            var end = 0
            for (t in tokens) {
                assertTrue(t.start >= end && t.end > t.start && t.end <= block.code.length, "${block.language}: $t")
                end = t.end
            }
            if (block.language !in setOf("", "mermaid")) assertTrue(tokens.isNotEmpty(), block.language)
        }
    }

    @Test
    fun hostileCodeTokenisesInLinearTime() {
        val samples = mapOf(
            "kotlin" to "\"\${", "ts" to "/[", "tsx" to "<a {", "python" to "f\"{", "bash" to "\$(", "html" to "<a b='",
            "css" to "a{b:", "yaml" to "- a: [", "rust" to "/*", "markdown" to "**[`", "sql" to "'--", "" to "\"\\",
        )
        for ((lang, unit) in samples) {
            assertLinear(lang.ifEmpty { "generic" }, 10_000) { size ->
                Highlighter.tokenize(unit.repeat(size / unit.length), lang, limit = Highlighter.MAX_BACKGROUND_CHARS)
            }
        }
    }

    @Test
    fun hostileMarkdownLineHighlightsInLinearTime() {
        // PR #254 review: each failed opener used to scan to the end of the text for its closer. 4n is a 100 KB line.
        for (unit in listOf("`", "*", "**", "_", "[", "[a](", "<a")) {
            assertLinear(unit, 25_000) { size ->
                Highlighter.tokenize(unit.repeat(size / unit.length), "markdown", limit = Highlighter.MAX_BACKGROUND_CHARS)
            }
        }
    }

    // PR #254 review (opensec-code-review, CWE-1333): a line of short words made the CSS selector heuristic backtrack
    // exponentially; 40 words never returned. Now it must fail fast and real selectors must still be detected.
    @Test
    fun cssHeuristicDoesNotBacktrackOnWords() {
        val words = List(200) { "a" }.joinToString(" ")
        val started = System.nanoTime()
        Languages.detect(words)
        Languages.detect(List(60) { "ab-c" }.joinToString(" ") + " !")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "detect took too long")
        assertEquals("css", Languages.detect(".card > .title, h1 a:hover {\n  color: red;\n}"))
    }

    // PR #254 review: the YAML branch copied the rest of the line and compiled a Regex for every `|` or `>`.
    @Test
    fun yamlPipesTokeniseInLinearTime() {
        assertLinear("yaml |,|,…", 6_000) { n -> Highlighter.tokenize("key: " + "|,".repeat(n), "yaml") }
        val tokens = Highlighter.tokenize("text: |-\n  body\nn: 0x1F", "yaml")
        assertTrue(tokens.any { it.kind == Highlighter.Kind.Number }, "number still highlighted")
    }

}
