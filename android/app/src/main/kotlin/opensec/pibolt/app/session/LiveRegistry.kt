package opensec.pibolt.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live sessions of the app (several at once: up to [opensec.pibolt.app.engine.LivePolicy.MAX_LIVE] on this phone's
 * engine plus one remote-control session), the one in focus (shown last), and when each was last used. Adding,
 * focusing and removing are cheap and thread-safe; nothing here starts or stops a session.
 */
class LiveRegistry<C : Any>(private val clock: () -> Long) {
    private val _all = MutableStateFlow<List<C>>(emptyList())

    /** Every live session, in the order they were opened. */
    val all: StateFlow<List<C>> = _all.asStateFlow()

    private val _focused = MutableStateFlow<C?>(null)

    /** The session last opened or shown, if it is still live. */
    val focused: StateFlow<C?> = _focused.asStateFlow()

    private val used = HashMap<C, Long>()

    /** Adds [session] (in place of [replacing], if given) and focuses it. */
    @Synchronized
    fun add(session: C, replacing: C? = null) {
        val list = _all.value.toMutableList()
        val at = replacing?.let { r -> list.indexOfFirst { it === r } } ?: -1
        list.removeAll { it === session || it === replacing }
        if (at >= 0) list.add(at.coerceAtMost(list.size), session) else list += session
        replacing?.let(used::remove)
        _all.value = list
        focus(session)
    }

    /** The user is looking at [session] now. Ignored when it is not live. */
    @Synchronized
    fun focus(session: C) {
        if (_all.value.none { it === session }) return
        used[session] = clock()
        _focused.value = session
    }

    /** [session] stopped. Nothing is in focus then until another is opened or shown. */
    @Synchronized
    fun remove(session: C) {
        if (_all.value.none { it === session }) return
        _all.value = _all.value.filterNot { it === session }
        used.remove(session)
        if (_focused.value === session) _focused.value = null
    }

    @Synchronized
    fun lastUsed(session: C): Long = used[session] ?: 0L
}
