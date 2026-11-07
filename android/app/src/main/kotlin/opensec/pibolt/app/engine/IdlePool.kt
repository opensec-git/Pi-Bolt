package opensec.pibolt.app.engine

/**
 * The idle engines [LocalEngine] keeps for fast opens: at most one fresh spare (started for the next open: a new, empty
 * session in [Entry.cwd]) and up to [maxParked] engines parked on the session they last showed. An open takes the engine
 * that already has what it wants (no `switch_session`, which rebuilds Pi's whole runtime), else the spare, else the
 * oldest parked one. Thread-safe; [E] is the engine type (a LocalBackend in the app, anything in tests).
 */
class IdlePool<E : Any>(private val maxParked: Int = 1, private val sessionFileOf: (E) -> String?) {
    class Entry<E>(val engine: E, val fresh: Boolean, val cwd: String)

    /** What an open wants: a saved session, or a new session in a project directory. */
    sealed interface Want {
        data class Session(val file: String) : Want

        data class New(val cwd: String) : Want
    }

    /** An engine taken for an open; [exact] when it already has the wanted session open. */
    class Taken<E>(val engine: E, val exact: Boolean)

    private val entries = ArrayList<Entry<E>>()

    /** The engine that already has [want] open, or (unless [exactOnly]) the spare, else the oldest parked engine. */
    @Synchronized
    fun take(want: Want, exactOnly: Boolean = false): Taken<E>? {
        val exact = entries.firstOrNull { matches(it, want) }
        val chosen = exact ?: if (exactOnly) null else entries.firstOrNull { it.fresh } ?: entries.firstOrNull()
        chosen ?: return null
        entries.remove(chosen)
        return Taken(chosen.engine, exact != null)
    }

    private fun matches(entry: Entry<E>, want: Want): Boolean = when (want) {
        is Want.Session -> sessionFileOf(entry.engine) == want.file
        is Want.New -> entry.fresh && entry.cwd == want.cwd
    }

    /** Add the spare unless there is one. False when one is already there. */
    @Synchronized
    fun addFresh(engine: E, cwd: String): Boolean {
        if (entries.any { it.fresh }) return false
        entries += Entry(engine, fresh = true, cwd = cwd)
        return true
    }

    /** Park [engine] on its session. Returns the parked engines it pushes out (oldest first): the caller stops them. */
    @Synchronized
    fun park(engine: E, cwd: String): List<E> {
        entries.removeAll { it.engine === engine }
        entries += Entry(engine, fresh = false, cwd = cwd)
        val parked = entries.filter { !it.fresh }
        val out = parked.take((parked.size - maxParked).coerceAtLeast(0))
        entries.removeAll { e -> out.any { it === e } }
        return out.map { it.engine }
    }

    @Synchronized
    fun remove(engine: E): Boolean = entries.removeAll { it.engine === engine }

    @Synchronized
    fun hasFresh(): Boolean = entries.any { it.fresh }

    /** Empties the pool; the caller stops what it returns. */
    @Synchronized
    fun drain(): List<E> = entries.map { it.engine }.also { entries.clear() }

    @Synchronized
    fun engines(): List<E> = entries.map { it.engine }
}
