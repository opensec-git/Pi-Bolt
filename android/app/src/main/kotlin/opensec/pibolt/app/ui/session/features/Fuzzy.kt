package opensec.pibolt.app.ui.session.features

/**
 * Fuzzy matching for the command palette and the `@` file picker: every query character must appear in order. A
 * substring beats a scattered match; a match at the start or at a word boundary (`/`, `-`, `_`, `.`, space, camelCase)
 * beats one in the middle; shorter candidates win ties.
 */
object Fuzzy {
    /** How well [query] matches [candidate] (higher is better), or null when it does not match. Case-insensitive. */
    fun score(query: String, candidate: String): Int? {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val c = candidate.lowercase()
        val at = c.indexOf(q)
        if (at >= 0) {
            var s = 1000 - at * 2 - (c.length - q.length)
            if (at == 0) s += 500 else if (isBoundary(candidate, at)) s += 250
            return s
        }
        var from = 0
        var previous = -2
        var s = 0
        for (ch in q) {
            val i = c.indexOf(ch, from)
            if (i < 0) return null
            s += when {
                i == previous + 1 -> 15
                isBoundary(candidate, i) -> 10
                else -> 1
            }
            s -= (i - from).coerceAtMost(10)
            previous = i
            from = i + 1
        }
        return s - c.length / 8
    }

    fun isBoundary(s: String, i: Int): Boolean {
        if (i <= 0) return true
        val before = s[i - 1]
        return before in BOUNDARIES || (s[i].isUpperCase() && before.isLowerCase())
    }

    /** [items] that match [query], best first (stable for equal scores), at most [limit]. */
    fun <T> rank(query: String, items: List<T>, limit: Int, key: (T) -> String): List<T> {
        if (query.isEmpty()) return items.take(limit)
        return items.mapIndexedNotNull { i, item -> score(query, key(item))?.let { Triple(item, it, i) } }
            .sortedWith(compareByDescending<Triple<T, Int, Int>> { it.second }.thenBy { it.third })
            .take(limit)
            .map { it.first }
    }

    private const val BOUNDARIES = "/\\-_. :"
}
