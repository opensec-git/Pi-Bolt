package opensec.pibolt.app.ui.markdown

/** HTML character references in markdown text: `&amp;`, `&#169;`, `&#x1F680;` and the named ones models use. */
object Entities {
    private val named: Map<String, String> = buildMap {
        val pairs = listOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "ensp" to "\u2002",
            "emsp" to "\u2003", "thinsp" to "\u2009", "shy" to "\u00AD", "zwj" to "\u200D", "zwnj" to "\u200C", "copy" to "©", "reg" to "®",
            "trade" to "™", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
            "lsaquo" to "‹", "rsaquo" to "›", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”",
            "bdquo" to "„", "bull" to "•", "middot" to "·", "deg" to "°", "plusmn" to "±", "times" to "×",
            "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup1" to "¹", "sup2" to "²", "sup3" to "³",
            "micro" to "µ", "para" to "¶", "sect" to "§", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
            "curren" to "¤", "iexcl" to "¡", "iquest" to "¿", "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
            "prime" to "′", "Prime" to "″", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
            "lArr" to "⇐", "rArr" to "⇒", "uArr" to "⇑", "dArr" to "⇓", "hArr" to "⇔", "crarr" to "↵", "le" to "≤",
            "ge" to "≥", "ne" to "≠", "asymp" to "≈", "equiv" to "≡", "infin" to "∞", "sum" to "∑", "prod" to "∏",
            "radic" to "√", "minus" to "−", "lowast" to "∗", "part" to "∂", "nabla" to "∇", "isin" to "∈",
            "notin" to "∉", "cap" to "∩", "cup" to "∪", "sub" to "⊂", "sup" to "⊃", "sube" to "⊆", "supe" to "⊇",
            "and" to "∧", "or" to "∨", "not" to "¬", "forall" to "∀", "exist" to "∃", "empty" to "∅", "int" to "∫",
            "there4" to "∴", "sim" to "∼", "cong" to "≅", "prop" to "∝", "ang" to "∠", "perp" to "⊥", "sdot" to "⋅",
            "oplus" to "⊕", "otimes" to "⊗", "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊", "rfloor" to "⌋",
            "lang" to "⟨", "rang" to "⟩", "loz" to "◊", "spades" to "♠", "clubs" to "♣", "hearts" to "♥", "diams" to "♦",
            "check" to "✓", "cross" to "✗", "star" to "☆", "starf" to "★", "half" to "½", "hyphen" to "‐", "dash" to "‐",
            "Aacute" to "Á", "aacute" to "á", "Eacute" to "É", "eacute" to "é", "Iacute" to "Í", "iacute" to "í",
            "Oacute" to "Ó", "oacute" to "ó", "Uacute" to "Ú", "uacute" to "ú", "Agrave" to "À", "agrave" to "à",
            "Egrave" to "È", "egrave" to "è", "Auml" to "Ä", "auml" to "ä", "Ouml" to "Ö", "ouml" to "ö", "Uuml" to "Ü",
            "uuml" to "ü", "szlig" to "ß", "Ntilde" to "Ñ", "ntilde" to "ñ", "Ccedil" to "Ç", "ccedil" to "ç",
            "Aring" to "Å", "aring" to "å", "AElig" to "Æ", "aelig" to "æ", "Oslash" to "Ø", "oslash" to "ø",
            "acirc" to "â", "ecirc" to "ê", "icirc" to "î", "ocirc" to "ô", "ucirc" to "û", "euml" to "ë", "iuml" to "ï",
        )
        for ((k, v) in pairs) put(k, v)
        val greek = listOf(
            "alpha" to 'α', "beta" to 'β', "gamma" to 'γ', "delta" to 'δ', "epsilon" to 'ε', "zeta" to 'ζ', "eta" to 'η',
            "theta" to 'θ', "iota" to 'ι', "kappa" to 'κ', "lambda" to 'λ', "mu" to 'μ', "nu" to 'ν', "xi" to 'ξ',
            "omicron" to 'ο', "pi" to 'π', "rho" to 'ρ', "sigma" to 'σ', "tau" to 'τ', "upsilon" to 'υ', "phi" to 'φ',
            "chi" to 'χ', "psi" to 'ψ', "omega" to 'ω',
        )
        for ((k, v) in greek) {
            put(k, v.toString())
            put(k.replaceFirstChar { it.uppercase() }, v.uppercaseChar().toString())
        }
        put("sigmaf", "ς")
    }

    /** End (exclusive) of a character reference starting at the `&` at [i], or -1. */
    fun match(s: String, i: Int): Int {
        val n = s.length
        var j = i + 1
        if (j < n && s[j] == '#') {
            j++
            val hex = j < n && (s[j] == 'x' || s[j] == 'X')
            if (hex) j++
            val start = j
            while (j < n && j - start < 7 && (if (hex) s[j].isHexDigitAscii() else s[j] in '0'..'9')) j++
            return if (j > start && j < n && s[j] == ';') j + 1 else -1
        }
        val start = j
        while (j < n && j - start < 32 && (s[j] in 'a'..'z' || s[j] in 'A'..'Z' || s[j] in '0'..'9')) j++
        if (j == start || j >= n || s[j] != ';') return -1
        return if (named.containsKey(s.substring(start, j))) j + 1 else -1
    }

    /** The text for a reference matched by [match]; invalid code points become U+FFFD. */
    fun decode(ref: String): String? {
        if (ref.startsWith("&#")) {
            val hex = ref.length > 3 && (ref[2] == 'x' || ref[2] == 'X')
            val digits = ref.substring(if (hex) 3 else 2, ref.length - 1)
            val cp = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            if (cp == 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return "�"
            return String(Character.toChars(cp))
        }
        return named[ref.substring(1, ref.length - 1)]
    }

    private fun Char.isHexDigitAscii(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
