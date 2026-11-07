package opensec.pibolt.app.ui.markdown

import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem

/**
 * Markdown parses of finished assistant messages, made off the main thread when a session's history arrives (opening
 * or resuming a session, a fork, a reload after compaction), so the transcript's first frame finds them parsed instead
 * of parsing every message while it composes. Keyed by the message text; a transcript takes each parse once
 * ([take]); the newest [MAX] are kept for transcripts that are not on screen yet.
 */
object MdPrefetch {
    /** Fewer new messages than this (a turn that ended) are parsed by the transcript as before. */
    const val MIN_NEW = 3

    const val MAX = 400

    private val docs = object : LinkedHashMap<String, MdDoc>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MdDoc>): Boolean = size > MAX
    }

    /** The parse of [text] made by [warm], removed from the cache; null when there is none. */
    fun take(text: String): MdDoc? = synchronized(docs) { docs.remove(text) }

    /**
     * Parses the finished assistant messages of [next] that [previous] did not have, when there are at least [MIN_NEW]
     * of them. Call it off the main thread, before [next] is published.
     */
    fun warm(previous: List<ChatItem>, next: List<ChatItem>) {
        if (next === previous || next.size < MIN_NEW) return
        val known = if (previous.isEmpty()) emptySet() else previous.mapTo(HashSet(previous.size * 2)) { it.key }
        val fresh = next.filter { it is AssistantTextItem && !it.streaming && it.text.isNotBlank() && it.key !in known }
        if (fresh.size < MIN_NEW) return
        for (item in fresh) {
            val text = (item as AssistantTextItem).text
            if (synchronized(docs) { docs.containsKey(text) }) continue
            val doc = Markdown.parse(text)
            synchronized(docs) { docs[text] = doc }
        }
    }

    internal fun clear() = synchronized(docs) { docs.clear() }
}
