package opensec.pibolt.app.ui.markdown.math

/**
 * A small TeX math parser for what models write: symbols, Greek, `^` `_`, `\frac`, `\sqrt`, big operators with limits,
 * `\left…\right`, accents, font commands, `\text`, spacing and the matrix-like environments (`pmatrix`, `cases`,
 * `aligned`, `array`, …). Anything else throws [TexError] so the caller can show the source instead.
 */
class TexError(message: String) : Exception(message)

/** TeX atom classes, for spacing. */
enum class Atom { Ord, Op, Bin, Rel, Open, Close, Punct, Inner }

enum class MathFont { Italic, Roman, Bold, BoldItalic, DoubleStruck, Script, Fraktur, Sans, Mono }

sealed interface TexNode {
    /** One glyph run. [limits] (for big operators) puts scripts above and below in display style. */
    data class Sym(val text: String, val atom: Atom, val font: MathFont, val large: Boolean = false, val limits: Boolean = false) : TexNode

    data class Row(val items: List<TexNode>, val atom: Atom = Atom.Ord) : TexNode

    data class Frac(val num: TexNode, val den: TexNode, val bar: Boolean = true, val style: Style? = null) : TexNode

    data class Scripts(val base: TexNode, val sub: TexNode?, val sup: TexNode?) : TexNode

    data class Sqrt(val body: TexNode, val index: TexNode?) : TexNode

    /** `\left( … \right)`; an empty delimiter is `.`. [size] > 0 is a fixed `\big`-style scale. */
    data class Fenced(val left: String, val body: TexNode, val right: String, val size: Float = 0f) : TexNode

    /** A delimiter on its own (`\big(`, `|`). */
    data class Delim(val text: String, val size: Float, val atom: Atom) : TexNode

    data class Accent(val body: TexNode, val accent: AccentKind) : TexNode

    /** `\overset`, `\underset`, `\stackrel`: [over] / [under] at script size. */
    data class OverUnder(val base: TexNode, val over: TexNode?, val under: TexNode?) : TexNode

    data class Matrix(val rows: List<List<TexNode>>, val align: List<Char>, val colGap: Float, val left: String, val right: String, val small: Boolean = false) : TexNode

    data class Space(val em: Float) : TexNode

    /** Upright text (`\text{…}`), spaces kept. */
    data class Text(val text: String, val font: MathFont) : TexNode

    data class Boxed(val body: TexNode) : TexNode

    /** `\displaystyle` and friends for the rest of the group. */
    data class Styled(val style: Style, val body: TexNode) : TexNode
}

enum class Style { Display, Text, Script, ScriptScript }

enum class AccentKind { Hat, Bar, Vec, Dot, DDot, Tilde, Overline, Underline, Acute, Grave, Breve, Check, Ring, OverBrace, UnderBrace, OverArrowLeft }

object Tex {
    /** Parse [src]; throws [TexError] on anything unsupported or malformed. */
    fun parse(src: String): TexNode {
        if (src.length > 4_000) throw TexError("too long")
        return Parser(src).parseAll()
    }

    /** Parse, or null when the TeX is not supported. */
    fun parseOrNull(src: String): TexNode? = try {
        parse(src)
    } catch (_: TexError) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ",
        "mu" to "μ", "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
        "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ",
        "psi" to "ψ", "omega" to "ω", "digamma" to "ϝ",
    )
    private val upperGreek = mapOf(
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
        "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    )
    private val bins = mapOf(
        "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "⋅", "ast" to "∗", "star" to "⋆", "circ" to "∘",
        "bullet" to "∙", "oplus" to "⊕", "ominus" to "⊖", "otimes" to "⊗", "oslash" to "⊘", "odot" to "⊙", "cup" to "∪",
        "cap" to "∩", "setminus" to "∖", "wedge" to "∧", "vee" to "∨", "land" to "∧", "lor" to "∨", "sqcup" to "⊔",
        "sqcap" to "⊓", "uplus" to "⊎", "dagger" to "†", "ddagger" to "‡", "amalg" to "⨿", "bmod" to "mod", "triangleleft" to "◁",
        "triangleright" to "▷", "wr" to "≀", "diamond" to "⋄", "bigtriangleup" to "△", "bigtriangledown" to "▽",
    )
    private val rels = mapOf(
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠", "equiv" to "≡", "approx" to "≈",
        "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇", "subsetneq" to "⊊", "supsetneq" to "⊋",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔", "leftrightarrow" to "↔", "iff" to "⟺", "implies" to "⟹", "impliedby" to "⟸",
        "mapsto" to "↦", "longrightarrow" to "⟶", "longleftarrow" to "⟵", "Longrightarrow" to "⟹", "Longleftarrow" to "⟸",
        "longleftrightarrow" to "⟷", "Longleftrightarrow" to "⟺", "longmapsto" to "⟼", "uparrow" to "↑", "downarrow" to "↓",
        "Uparrow" to "⇑", "Downarrow" to "⇓", "updownarrow" to "↕", "nearrow" to "↗", "searrow" to "↘", "nwarrow" to "↖",
        "swarrow" to "↙", "hookrightarrow" to "↪", "hookleftarrow" to "↩", "leadsto" to "⇝", "rightleftharpoons" to "⇌",
        "parallel" to "∥", "perp" to "⊥", "mid" to "∣", "nmid" to "∤", "ll" to "≪", "gg" to "≫", "prec" to "≺", "succ" to "≻",
        "preceq" to "⪯", "succeq" to "⪰", "models" to "⊨", "vdash" to "⊢", "dashv" to "⊣", "doteq" to "≐", "asymp" to "≍",
        "coloneqq" to "≔", "eqqcolon" to "≕", "triangleq" to "≜", "lesssim" to "≲", "gtrsim" to "≳", "leqslant" to "⩽",
        "geqslant" to "⩾", "nleq" to "≰", "ngeq" to "≱", "nsim" to "≁", "ncong" to "≇", "sqsubseteq" to "⊑", "sqsupseteq" to "⊒",
        "bowtie" to "⋈", "smile" to "⌣", "frown" to "⌢", "vDash" to "⊨", "Vdash" to "⊩", "therefore" to "∴", "because" to "∵",
        "xrightarrow" to "⟶",
    )
    private val ords = mapOf(
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "emptyset" to "∅", "varnothing" to "∅", "neg" to "¬", "lnot" to "¬", "angle" to "∠", "measuredangle" to "∡",
        "triangle" to "△", "hbar" to "ℏ", "hslash" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "beth" to "ℶ",
        "wp" to "℘", "top" to "⊤", "bot" to "⊥", "prime" to "′", "dots" to "…", "ldots" to "…", "cdots" to "⋯",
        "vdots" to "⋮", "ddots" to "⋱", "checkmark" to "✓", "square" to "□", "Box" to "□", "blacksquare" to "■",
        "lozenge" to "◊", "clubsuit" to "♣", "diamondsuit" to "♢", "heartsuit" to "♡", "spadesuit" to "♠", "flat" to "♭",
        "sharp" to "♯", "natural" to "♮", "degree" to "°", "dagger" to "†", "S" to "§", "P" to "¶", "copyright" to "©",
        "pounds" to "£", "complement" to "∁", "mho" to "℧", "eth" to "ð", "imath" to "ı", "jmath" to "ȷ", "surd" to "√",
        "backslash" to "∖", "vert" to "|", "Vert" to "‖", "colon" to ":", "star" to "⋆", "infin" to "∞", "dag" to "†",
    )
    private val bigOps = mapOf(
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁",
        "bigotimes" to "⨂", "bigodot" to "⨀", "bigvee" to "⋁", "bigwedge" to "⋀", "bigsqcup" to "⨆", "biguplus" to "⨄",
    )
    private val integrals = mapOf("int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮", "oiint" to "∯", "intop" to "∫", "smallint" to "∫")
    private val functions = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth", "log", "ln",
        "lg", "exp", "arg", "deg", "dim", "hom", "ker", "sgn", "Tr", "tr", "rank", "span", "erf",
    )
    private val limitFunctions = setOf("lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "gcd", "lcm", "Pr", "argmax", "argmin", "plim")
    private val delimiters = mapOf(
        "(" to "(", ")" to ")", "[" to "[", "]" to "]", "\\{" to "{", "\\}" to "}", "\\lbrace" to "{", "\\rbrace" to "}",
        "|" to "|", "\\|" to "‖", "\\vert" to "|", "\\Vert" to "‖", "\\lvert" to "|", "\\rvert" to "|", "\\lVert" to "‖",
        "\\rVert" to "‖", "\\langle" to "⟨", "\\rangle" to "⟩", "<" to "⟨", ">" to "⟩", "\\lfloor" to "⌊", "\\rfloor" to "⌋",
        "\\lceil" to "⌈", "\\rceil" to "⌉", "." to "", "/" to "/", "\\backslash" to "∖", "\\lbrack" to "[", "\\rbrack" to "]",
        "\\uparrow" to "↑", "\\downarrow" to "↓",
    )
    private val bigSizes = mapOf(
        "big" to 1.2f, "Big" to 1.8f, "bigg" to 2.4f, "Bigg" to 3.0f, "bigl" to 1.2f, "Bigl" to 1.8f, "biggl" to 2.4f,
        "Biggl" to 3.0f, "bigr" to 1.2f, "Bigr" to 1.8f, "biggr" to 2.4f, "Biggr" to 3.0f, "bigm" to 1.2f, "Bigm" to 1.8f,
        "biggm" to 2.4f, "Biggm" to 3.0f,
    )
    private val accents = mapOf(
        "hat" to AccentKind.Hat, "widehat" to AccentKind.Hat, "bar" to AccentKind.Bar, "vec" to AccentKind.Vec,
        "overrightarrow" to AccentKind.Vec, "overleftarrow" to AccentKind.OverArrowLeft, "dot" to AccentKind.Dot,
        "ddot" to AccentKind.DDot, "tilde" to AccentKind.Tilde, "widetilde" to AccentKind.Tilde,
        "overline" to AccentKind.Overline, "underline" to AccentKind.Underline, "acute" to AccentKind.Acute,
        "grave" to AccentKind.Grave, "breve" to AccentKind.Breve, "check" to AccentKind.Check, "mathring" to AccentKind.Ring,
        "overbrace" to AccentKind.OverBrace, "underbrace" to AccentKind.UnderBrace,
    )
    private val fonts = mapOf(
        "mathrm" to MathFont.Roman, "mathbf" to MathFont.Bold, "mathit" to MathFont.Italic, "mathbb" to MathFont.DoubleStruck,
        "mathcal" to MathFont.Script, "mathscr" to MathFont.Script, "mathfrak" to MathFont.Fraktur, "mathsf" to MathFont.Sans,
        "mathtt" to MathFont.Mono, "boldsymbol" to MathFont.BoldItalic, "bm" to MathFont.BoldItalic, "mathbfit" to MathFont.BoldItalic,
        "pmb" to MathFont.BoldItalic, "mathnormal" to MathFont.Italic, "Bbb" to MathFont.DoubleStruck, "bold" to MathFont.Bold,
    )
    private val textCommands = mapOf(
        "text" to MathFont.Roman, "textrm" to MathFont.Roman, "textnormal" to MathFont.Roman, "mbox" to MathFont.Roman,
        "textbf" to MathFont.Bold, "textit" to MathFont.Italic, "texttt" to MathFont.Mono, "textsf" to MathFont.Sans,
        "hbox" to MathFont.Roman, "textup" to MathFont.Roman, "emph" to MathFont.Italic,
    )
    private val spaces = mapOf(
        "," to 3f / 18, ":" to 4f / 18, ">" to 4f / 18, ";" to 5f / 18, "!" to -3f / 18, "quad" to 1f, "qquad" to 2f,
        " " to 0.25f, "enspace" to 0.5f, "thinspace" to 3f / 18, "medspace" to 4f / 18, "thickspace" to 5f / 18,
        "negthinspace" to -3f / 18, "enskip" to 0.5f, "space" to 0.25f, "nobreakspace" to 0.25f,
    )
    /** Ignored: they change nothing we draw. */
    private val ignored = setOf("limits", "nolimits", "nonumber", "notag", "displaylimits", "hline", "middle", "relax", "strut", "mathstrut", "allowbreak", "nobreak", "left.", "right.", "protect", "centering")

    private class Parser(val src: String) {
        var i = 0
        var depth = 0

        fun parseAll(): TexNode {
            val row = parseList(setOf())
            if (i < src.length) throw TexError("unexpected '${src[i]}' at $i")
            return row
        }

        private fun skipSpace() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        /** The next command name after a backslash at [i] (without consuming), or null. */
        private fun peekCommand(): String? {
            skipSpace()
            if (i >= src.length || src[i] != '\\' || i + 1 >= src.length) return null
            var j = i + 1
            if (!src[j].isLetter()) return src[j].toString()
            while (j < src.length && src[j].isLetter()) j++
            return src.substring(i + 1, j)
        }

        private fun readCommand(): String {
            val name = peekCommand() ?: throw TexError("expected a command")
            i += 1 + name.length
            return name
        }

        /** Items until a closer in [stops] (`}`, `&`, `\\`, `\right`, `\end`) or the end; `\over` splits a fraction. */
        fun parseList(stops: Set<String>): TexNode {
            if (++depth > 40) throw TexError("too deep")
            val items = ArrayList<TexNode>()
            var numerator: TexNode? = null
            var overBar = true
            while (true) {
                skipSpace()
                if (i >= src.length) break
                val c = src[i]
                if (c == '}' && "}" in stops) break
                if (c == '}') throw TexError("unbalanced }")
                if (c == '&' && "&" in stops) break
                if (c == '&') throw TexError("& outside an environment")
                val cmd = peekCommand()
                if (cmd == "\\" && "\\\\" in stops) break
                if (cmd == "cr" && "\\\\" in stops) break
                if ((cmd == "right" || cmd == "middle") && "right" in stops) break
                if (cmd == "end" && "end" in stops) break
                if (cmd == "over" || cmd == "atop" || cmd == "choose") {
                    i += 1 + cmd.length
                    numerator = TexNode.Row(items.toList())
                    overBar = cmd == "over"
                    items.clear()
                    continue
                }
                if (cmd in setOf("displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle")) {
                    i += 1 + cmd!!.length
                    val style = when (cmd) {
                        "displaystyle" -> Style.Display
                        "textstyle" -> Style.Text
                        "scriptstyle" -> Style.Script
                        else -> Style.ScriptScript
                    }
                    val rest = parseList(stops)
                    items += TexNode.Styled(style, rest)
                    break
                }
                when (c) {
                    '^', '_' -> {
                        i++
                        val arg = parseArg()
                        val last = items.removeLastOrNull() ?: TexNode.Row(emptyList())
                        items += attach(last, if (c == '_') arg else null, if (c == '^') arg else null)
                    }
                    '\'' -> {
                        var n = 0
                        while (i < src.length && src[i] == '\'') {
                            n++
                            i++
                        }
                        val primes = TexNode.Sym("′".repeat(n), Atom.Ord, MathFont.Roman)
                        val last = items.removeLastOrNull() ?: TexNode.Row(emptyList())
                        items += attach(last, null, primes)
                    }
                    else -> items += parseAtom()
                }
            }
            depth--
            val row = TexNode.Row(items)
            if (numerator != null) {
                val frac = TexNode.Frac(numerator, row, bar = overBar)
                return if (overBar) frac else TexNode.Fenced("(", frac, ")")
            }
            return row
        }

        private fun attach(base: TexNode, sub: TexNode?, sup: TexNode?): TexNode {
            if (base is TexNode.Scripts) {
                if (sub != null && base.sub == null) return base.copy(sub = sub)
                if (sup != null && base.sup == null) return base.copy(sup = sup)
                // A second ^ on the same base (x^a^b) is an error in TeX; nest it.
                return TexNode.Scripts(base, sub, sup)
            }
            return TexNode.Scripts(base, sub, sup)
        }

        /** One argument: a `{group}` or a single token. */
        fun parseArg(): TexNode {
            skipSpace()
            if (i >= src.length) throw TexError("missing argument")
            if (src[i] == '{') {
                i++
                val row = parseList(setOf("}"))
                expect('}')
                return row
            }
            return parseAtom()
        }

        private fun expect(c: Char) {
            skipSpace()
            if (i >= src.length || src[i] != c) throw TexError("expected '$c'")
            i++
        }

        /** Raw text of a `{…}` argument (for \text, \operatorname, environment names, colours). */
        private fun rawGroup(): String {
            skipSpace()
            if (i >= src.length) throw TexError("missing argument")
            if (src[i] != '{') return src[i++].toString()
            var d = 0
            val start = i + 1
            while (i < src.length) {
                when (src[i]) {
                    '\\' -> i++
                    '{' -> d++
                    '}' -> {
                        d--
                        if (d == 0) {
                            i++
                            return src.substring(start, i - 1)
                        }
                    }
                }
                i++
            }
            throw TexError("unbalanced {")
        }

        private fun optionalArg(): TexNode? {
            skipSpace()
            if (i < src.length && src[i] == '[') {
                i++
                val start = i
                var d = 0
                while (i < src.length && !(src[i] == ']' && d == 0)) {
                    if (src[i] == '{') d++ else if (src[i] == '}') d--
                    i++
                }
                if (i >= src.length) throw TexError("unbalanced [")
                val inner = src.substring(start, i)
                i++
                return Parser(inner).parseAll()
            }
            return null
        }

        private fun delimiter(): String {
            skipSpace()
            if (i >= src.length) throw TexError("missing delimiter")
            val key = if (src[i] == '\\') "\\" + readCommand() else src[i++].toString()
            return delimiters[key] ?: throw TexError("unknown delimiter $key")
        }

        fun parseAtom(): TexNode {
            skipSpace()
            val c = src[i]
            if (c == '{') {
                i++
                val row = parseList(setOf("}"))
                expect('}')
                return row
            }
            if (c == '\\') return command(readCommand())
            i++
            return when {
                c.isLetter() -> TexNode.Sym(c.toString(), Atom.Ord, if (c.code < 128) MathFont.Italic else MathFont.Roman)
                c.isDigit() || c == '.' -> {
                    // A run of digits is one number.
                    val start = i - 1
                    while (i < src.length && (src[i].isDigit() || src[i] == '.' && i + 1 < src.length && src[i + 1].isDigit())) i++
                    TexNode.Sym(src.substring(start, i), Atom.Ord, MathFont.Roman)
                }
                c == '+' -> TexNode.Sym("+", Atom.Bin, MathFont.Roman)
                c == '-' -> TexNode.Sym("−", Atom.Bin, MathFont.Roman)
                c == '*' -> TexNode.Sym("∗", Atom.Bin, MathFont.Roman)
                c == '/' -> TexNode.Sym("/", Atom.Ord, MathFont.Roman)
                c == '=' || c == '<' || c == '>' -> TexNode.Sym(c.toString(), Atom.Rel, MathFont.Roman)
                c == ':' -> TexNode.Sym(":", Atom.Rel, MathFont.Roman)
                c == ',' || c == ';' -> TexNode.Sym(c.toString(), Atom.Punct, MathFont.Roman)
                c == '(' || c == '[' -> TexNode.Sym(c.toString(), Atom.Open, MathFont.Roman)
                c == ')' || c == ']' -> TexNode.Sym(c.toString(), Atom.Close, MathFont.Roman)
                c == '|' -> TexNode.Sym("|", Atom.Ord, MathFont.Roman)
                c == '!' || c == '?' -> TexNode.Sym(c.toString(), Atom.Close, MathFont.Roman)
                c == '~' -> TexNode.Space(0.25f)
                c == '"' || c == '@' || c == '`' -> TexNode.Sym(c.toString(), Atom.Ord, MathFont.Roman)
                c == '#' || c == '%' || c == '$' -> throw TexError("unexpected $c")
                else -> TexNode.Sym(c.toString(), Atom.Ord, MathFont.Roman)
            }
        }

        private fun command(name: String): TexNode {
            greek[name]?.let { return TexNode.Sym(it, Atom.Ord, MathFont.Italic) }
            upperGreek[name]?.let { return TexNode.Sym(it, Atom.Ord, MathFont.Roman) }
            bins[name]?.let { return TexNode.Sym(it, if (name == "bmod") Atom.Bin else Atom.Bin, MathFont.Roman) }
            rels[name]?.let {
                if (name == "xrightarrow") {
                    val over = optionalArg()
                    val label = parseArg()
                    return TexNode.OverUnder(TexNode.Sym("⟶", Atom.Rel, MathFont.Roman), label, over)
                }
                return TexNode.Sym(it, Atom.Rel, MathFont.Roman)
            }
            ords[name]?.let { return TexNode.Sym(it, Atom.Ord, MathFont.Roman) }
            bigOps[name]?.let { return TexNode.Sym(it, Atom.Op, MathFont.Roman, large = true, limits = true) }
            integrals[name]?.let { return TexNode.Sym(it, Atom.Op, MathFont.Roman, large = true, limits = false) }
            if (name in functions) return TexNode.Sym(name, Atom.Op, MathFont.Roman)
            if (name in limitFunctions) return TexNode.Sym(if (name == "argmax") "arg max" else if (name == "argmin") "arg min" else if (name == "limsup") "lim sup" else if (name == "liminf") "lim inf" else name, Atom.Op, MathFont.Roman, limits = true)
            spaces[name]?.let { return TexNode.Space(it) }
            fonts[name]?.let { font -> return withFont(parseArg(), font) }
            textCommands[name]?.let { font -> return TexNode.Text(unescapeText(rawGroup()), font) }
            accents[name]?.let { return TexNode.Accent(parseArg(), it) }
            bigSizes[name]?.let { size ->
                val d = delimiter()
                val atom = when {
                    name.endsWith("l") -> Atom.Open
                    name.endsWith("r") -> Atom.Close
                    name.endsWith("m") -> Atom.Rel
                    else -> Atom.Ord
                }
                return TexNode.Delim(d, size, atom)
            }
            if (name in ignored) return TexNode.Row(emptyList())
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val num = parseArg()
                    val den = parseArg()
                    TexNode.Frac(num, den, style = if (name == "dfrac" || name == "cfrac") Style.Display else if (name == "tfrac") Style.Text else null)
                }
                "binom", "dbinom", "tbinom" -> {
                    val num = parseArg()
                    val den = parseArg()
                    TexNode.Fenced("(", TexNode.Frac(num, den, bar = false, style = if (name == "dbinom") Style.Display else if (name == "tbinom") Style.Text else null), ")")
                }
                "sqrt" -> {
                    val index = optionalArg()
                    TexNode.Sqrt(parseArg(), index)
                }
                "left" -> {
                    val left = delimiter()
                    val body = parseList(setOf("right"))
                    val next = readCommand()
                    if (next == "middle") {
                        // \middle| splits the body; keep it simple: draw the middle delimiter inline.
                        val mid = delimiter()
                        val rest = parseList(setOf("right"))
                        if (readCommand() != "right") throw TexError("expected \\right")
                        val right = delimiter()
                        return TexNode.Fenced(left, TexNode.Row(listOf(body, TexNode.Delim(mid, 0f, Atom.Rel), rest)), right)
                    }
                    if (next != "right") throw TexError("expected \\right")
                    TexNode.Fenced(left, body, delimiter())
                }
                "right" -> throw TexError("\\right without \\left")
                "operatorname", "operatorname*" -> {
                    val star = i < src.length && src[i] == '*'
                    if (star) i++
                    TexNode.Sym(unescapeText(rawGroup()), Atom.Op, MathFont.Roman, limits = star)
                }
                "mathop" -> TexNode.Row(listOf(parseArg()), Atom.Op)
                "mathrel" -> TexNode.Row(listOf(parseArg()), Atom.Rel)
                "mathbin" -> TexNode.Row(listOf(parseArg()), Atom.Bin)
                "mathord" -> TexNode.Row(listOf(parseArg()), Atom.Ord)
                "mathopen" -> TexNode.Row(listOf(parseArg()), Atom.Open)
                "mathclose" -> TexNode.Row(listOf(parseArg()), Atom.Close)
                "pmod" -> {
                    val arg = parseArg()
                    TexNode.Row(listOf(TexNode.Space(0.5f), TexNode.Fenced("(", TexNode.Row(listOf(TexNode.Sym("mod", Atom.Op, MathFont.Roman), TexNode.Space(1f / 6), arg)), ")")))
                }
                "mod" -> TexNode.Row(listOf(TexNode.Space(0.5f), TexNode.Sym("mod", Atom.Op, MathFont.Roman), TexNode.Space(1f / 6)))
                "overset", "stackrel" -> {
                    val over = parseArg()
                    TexNode.OverUnder(parseArg(), over, null)
                }
                "underset" -> {
                    val under = parseArg()
                    TexNode.OverUnder(parseArg(), null, under)
                }
                "boxed", "fbox" -> TexNode.Boxed(parseArg())
                "color" -> {
                    rawGroup()
                    TexNode.Row(emptyList())
                }
                "textcolor", "colorbox" -> {
                    rawGroup()
                    parseArg()
                }
                "phantom", "hphantom", "vphantom" -> {
                    parseArg()
                    TexNode.Space(0.5f)
                }
                "hspace", "hspace*", "kern", "mkern", "mskip", "hskip" -> {
                    val raw = if (i < src.length && src[i] == '{') rawGroup() else readLength()
                    TexNode.Space(lengthEm(raw))
                }
                "tag", "tag*" -> TexNode.Row(listOf(TexNode.Space(2f), TexNode.Text("(" + unescapeText(rawGroup()) + ")", MathFont.Roman)))
                "label", "eqref", "ref" -> {
                    rawGroup()
                    TexNode.Row(emptyList())
                }
                "substack" -> {
                    val group = rawGroup()
                    val rows = Parser(group).environmentRows()
                    TexNode.Matrix(rows, listOf('c'), 0f, "", "", small = true)
                }
                "not" -> {
                    val next = parseAtom()
                    if (next is TexNode.Sym && next.text.length == 1) TexNode.Sym(next.text + "̸", next.atom, next.font) else next
                }
                "begin" -> environment(rawGroup())
                "end" -> throw TexError("\\end without \\begin")
                "\\" -> TexNode.Space(0f)
                "{" -> TexNode.Sym("{", Atom.Open, MathFont.Roman)
                "}" -> TexNode.Sym("}", Atom.Close, MathFont.Roman)
                "|" -> TexNode.Sym("‖", Atom.Ord, MathFont.Roman)
                "#", "$", "%", "&", "_" -> TexNode.Sym(name, Atom.Ord, MathFont.Roman)
                "lbrace" -> TexNode.Sym("{", Atom.Open, MathFont.Roman)
                "rbrace" -> TexNode.Sym("}", Atom.Close, MathFont.Roman)
                "langle" -> TexNode.Sym("⟨", Atom.Open, MathFont.Roman)
                "rangle" -> TexNode.Sym("⟩", Atom.Close, MathFont.Roman)
                "lfloor" -> TexNode.Sym("⌊", Atom.Open, MathFont.Roman)
                "rfloor" -> TexNode.Sym("⌋", Atom.Close, MathFont.Roman)
                "lceil" -> TexNode.Sym("⌈", Atom.Open, MathFont.Roman)
                "rceil" -> TexNode.Sym("⌉", Atom.Close, MathFont.Roman)
                "lvert", "rvert" -> TexNode.Sym("|", if (name == "lvert") Atom.Open else Atom.Close, MathFont.Roman)
                "lVert", "rVert" -> TexNode.Sym("‖", if (name == "lVert") Atom.Open else Atom.Close, MathFont.Roman)
                "lbrack" -> TexNode.Sym("[", Atom.Open, MathFont.Roman)
                "rbrack" -> TexNode.Sym("]", Atom.Close, MathFont.Roman)
                else -> throw TexError("unsupported \\$name")
            }
        }

        private fun readLength(): String {
            skipSpace()
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '.' || src[i] == '-')) i++
            return src.substring(start, i)
        }

        private fun lengthEm(raw: String): Float {
            val m = Regex("(-?[\\d.]+)\\s*(em|ex|pt|mu|px|cm|mm|in)?").find(raw) ?: return 0.25f
            val v = m.groupValues[1].toFloatOrNull() ?: return 0.25f
            return when (m.groupValues[2]) {
                "ex" -> v * 0.45f
                "pt", "px" -> v / 10f
                "mu" -> v / 18f
                "cm" -> v * 2.85f
                "mm" -> v * 0.285f
                "in" -> v * 7.2f
                else -> v
            }.coerceIn(-2f, 8f)
        }

        private fun unescapeText(raw: String): String =
            raw.replace(Regex("\\\\([\\{\\}$%&#_ ])"), "$1").replace("~", " ").replace("\\\\", " ").replace(Regex("\\\\textbackslash\\s*"), "\\\\")

        private fun withFont(node: TexNode, font: MathFont): TexNode = when (node) {
            is TexNode.Sym -> if (node.atom == Atom.Ord || font == MathFont.Bold || font == MathFont.BoldItalic) node.copy(font = font) else node
            is TexNode.Row -> node.copy(items = node.items.map { withFont(it, font) })
            is TexNode.Scripts -> node.copy(base = withFont(node.base, font))
            is TexNode.Frac -> node.copy(num = withFont(node.num, font), den = withFont(node.den, font))
            is TexNode.Accent -> node.copy(body = withFont(node.body, font))
            else -> node
        }

        /** Rows of `&`-separated cells up to the end of the text or an `\end`. */
        fun environmentRows(): List<List<TexNode>> {
            val rows = ArrayList<List<TexNode>>()
            var row = ArrayList<TexNode>()
            while (true) {
                val cell = parseList(setOf("&", "\\\\", "end"))
                row += cell
                skipSpace()
                if (i >= src.length) break
                if (src[i] == '&') {
                    i++
                    continue
                }
                val cmd = peekCommand()
                if (cmd == "\\" || cmd == "cr") {
                    i += 1 + cmd.length
                    // \\[2pt] spacing argument.
                    skipSpace()
                    if (i < src.length && src[i] == '[') {
                        val close = src.indexOf(']', i)
                        if (close > 0) i = close + 1
                    }
                    rows += row
                    row = ArrayList()
                    continue
                }
                break
            }
            if (row.size > 1 || row.firstOrNull().let { it !is TexNode.Row || it.items.isNotEmpty() }) rows += row
            return rows
        }

        private fun environment(name: String): TexNode {
            val base = name.removeSuffix("*")
            var align: List<Char> = emptyList()
            if (base == "array" || base == "alignat" || base == "subarray") {
                val spec = rawGroup()
                align = spec.filter { it in "lcr" }.toList()
            }
            val rows = environmentRows()
            if (readCommand() != "end") throw TexError("expected \\end{$name}")
            val endName = rawGroup()
            if (endName != name) throw TexError("\\begin{$name} ended by \\end{$endName}")
            val cols = rows.maxOfOrNull { it.size } ?: 0
            return when (base) {
                "matrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "", "")
                "pmatrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "(", ")")
                "bmatrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "[", "]")
                "Bmatrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "{", "}")
                "vmatrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "|", "|")
                "Vmatrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "‖", "‖")
                "smallmatrix" -> TexNode.Matrix(rows, List(cols) { 'c' }, 0.5f, "", "", small = true)
                "cases", "dcases" -> TexNode.Matrix(rows, List(cols) { 'l' }, 1f, "{", "")
                "rcases" -> TexNode.Matrix(rows, List(cols) { 'l' }, 1f, "", "}")
                "aligned", "align", "split", "alignat", "alignedat", "flalign", "eqnarray" -> TexNode.Matrix(rows, List(cols) { if (it % 2 == 0) 'r' else 'l' }, 0f, "", "")
                "gathered", "gather", "equation", "multline", "displaymath", "center" -> TexNode.Matrix(rows, List(cols) { 'c' }, 1f, "", "")
                "array", "subarray" -> TexNode.Matrix(rows, List(cols) { align.getOrElse(it) { 'c' } }, 0.5f, "", "")
                else -> throw TexError("unsupported environment $name")
            }
        }
    }
}
