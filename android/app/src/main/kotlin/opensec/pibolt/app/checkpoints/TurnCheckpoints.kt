package opensec.pibolt.app.checkpoints

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a restore does to one file, and whether it was changed after the state being undone ([conflict]). */
data class RestoreItem(val path: String, val action: ChangeKind, val conflict: Boolean)

enum class RestoreKind { UndoTurn, RestoreBefore, Redo }

/**
 * A restore worked out but not applied: [items] go back to [source]. [current] is the project just before (taken while
 * planning), which the restore records so it can be undone. [undoAt] names the [UndoEntry] a [RestoreKind.Redo] reverses.
 */
data class RestorePlan(
    val turnId: String,
    val kind: RestoreKind,
    val source: String,
    val current: String,
    val items: List<RestoreItem>,
    val undoAt: Long? = null,
) {
    val conflicts: List<RestoreItem> get() = items.filter { it.conflict }
}

/** A restore that ran: [restored] paths, and [kept] ones left alone because the older state had skipped them. */
data class RestoreResult(val restored: List<String>, val kept: List<String>)

/**
 * Per-turn checkpoints of one project for a session on this phone: snapshots when a user message arrives (the turn
 * starts) and when the agent settles (it ends), the turns' changes, and restores that undo a turn, roll the project
 * back to before it, or undo such a restore.
 *
 * Nothing here blocks the caller: events queue snapshot work for one background worker (in order: a turn's end comes
 * before the next start), and every git call runs on [Dispatchers.IO] under a lock per shadow repository (two sessions
 * in one project take turns). A failure is one quiet [onNotice] until a snapshot works again, never an exception.
 */
class TurnCheckpoints(
    private val repo: ShadowRepo,
    private val index: CheckpointIndex = CheckpointIndex(File(repo.gitDir, "turns.json")),
    private val clock: () -> Long = System::currentTimeMillis,
    onNotice: (String) -> Unit = {},
) {
    /** Told once when snapshots start failing (again). */
    @Volatile
    var onNotice: (String) -> Unit = onNotice

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock: Mutex = locks.getOrPut(repo.gitDir.absolutePath) { Mutex() }
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _turns = MutableStateFlow<List<TurnRecord>>(emptyList())

    /** The project's turns, oldest first (all sessions in the project: a fork shares its parent's turns). */
    val turns: StateFlow<List<TurnRecord>> = _turns.asStateFlow()

    /** The turn whose end snapshot is still to come. Touched only by the worker. */
    @Volatile
    private var open: String? = null

    /** A start snapshot taken before a prompt was sent ([prepareStart]); the next user message's turn starts there. */
    @Volatile
    private var pendingStart: Snapshot? = null

    @Volatile
    private var failing = false

    @Volatile
    private var closed = false

    fun start() {
        scope.launch {
            try {
                // A first snapshot now: the one when the first message arrives is then incremental and quick, so the
                // agent's first edits cannot land before it.
                locked {
                    _turns.value = index.load()
                    repo.snapshot("open")
                }
            } catch (e: Exception) {
                fail(e)
            }
            for (job in work) {
                try {
                    job()
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }
    }

    fun close() {
        closed = true
        open?.let(openTurns::remove)
        work.close()
        scope.cancel()
    }

    /** An engine event of the session (called for every event: cheap unless it starts or ends a turn). */
    fun onEvent(event: JsonObject, sessionId: String?) {
        when (event.type) {
            "message_end" -> {
                val message = event.obj("message") ?: return
                if (message.str("role") != "user") return
                userMessage(message.long("timestamp"), textOf(message), sessionId)
            }
            "agent_settled" -> settled()
        }
    }

    /**
     * Takes the start snapshot for the prompt about to be sent to an idle agent and returns once it is taken (or after
     * [timeoutMs]): the agent cannot edit before its turn's start state is recorded.
     */
    suspend fun prepareStart(timeoutMs: Long = PREPARE_TIMEOUT_MS) {
        val done = CompletableDeferred<Unit>()
        enqueue {
            try {
                locked { pendingStart = repo.snapshot("start") }
            } finally {
                done.complete(Unit)
            }
        }
        if (closed) return
        withTimeoutOrNull(timeoutMs) { done.await() }
    }

    /** A user message reached the engine: the previous turn (a steer interrupts one) ends and this one starts. */
    fun userMessage(timestamp: Long?, text: String, sessionId: String?) = enqueue {
        locked {
            val prepared = pendingStart
            pendingStart = null
            val snapshot = try {
                prepared ?: repo.snapshot("start")
            } catch (e: Exception) {
                // No start for this turn: the open one cannot end here either, and must not take this turn's changes.
                open?.let(openTurns::remove)
                open = null
                throw e
            }
            failing = false
            // The open turn ends here, and so does one a process that died left open: the best end it can still get.
            val all = index.load()
            for (t in all) if (!t.finished && (t.id == open || t.id !in openTurns)) finishLocked(t.id, snapshot)
            open?.let(openTurns::remove)
            val record = TurnRecord(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                userTimestamp = timestamp,
                prompt = text.take(PROMPT_CHARS),
                startedAt = clock(),
                start = snapshot.commit,
            )
            open = record.id
            openTurns += record.id
            mutate { it + record }
        }
    }

    /** The agent settled: the open turn ends. */
    fun settled() = enqueue {
        val id = open ?: return@enqueue
        locked {
            val snapshot = repo.snapshot("end")
            failing = false
            finishLocked(id, snapshot)
            open = null
            openTurns -= id
            pruneLocked()
        }
    }

    /** Reloads [turns] from disk (another session in the project may have written). */
    suspend fun refresh() {
        locked { _turns.value = index.load() }
    }

    /** The record for a transcript user message ([timestamp] from the message; [text] when it has none). */
    fun turnFor(timestamp: Long?, text: String): TurnRecord? = find(_turns.value, timestamp, text)

    fun turn(id: String): TurnRecord? = _turns.value.firstOrNull { it.id == id }

    /** The files [turn] changed, from its start to its end. */
    suspend fun changes(turn: TurnRecord): List<FileDelta> {
        val end = turn.end ?: return emptyList()
        return locked { repo.changes(turn.start, end) }
    }

    /** [path]'s unified diff in [turn]. */
    suspend fun patch(turn: TurnRecord, path: String): String {
        val end = turn.end ?: return ""
        return locked { repo.patch(turn.start, end, path) }
    }

    /**
     * Undo [turn] ([paths] of it, or every file it changed): each file goes back to the turn's start. A file that is
     * no longer as the turn left it (a later turn or the user changed it) is a [RestoreItem.conflict]. Takes the
     * snapshot the restore can be undone to.
     */
    suspend fun planUndo(turn: TurnRecord, paths: Collection<String>? = null): RestorePlan {
        val end = turn.end ?: throw IllegalStateException("The turn is still running")
        return locked {
            val current = pin(repo.snapshot("before undo"))
            val touched = repo.changes(turn.start, end).map { it.path }.filter { paths == null || it in paths }
            val ended = repo.tree(end)
            val items = plan(turn.start, current.commit, touched) { path, now -> now != ended[path] }
            RestorePlan(turn.id, RestoreKind.UndoTurn, turn.start, current.commit, items)
        }
    }

    /**
     * Put the project back as it was when [turn] started: every file that differs from then. A file is safe to restore
     * when its newest change came from a turn or restore recorded here and it is still as that left it; anything else
     * (the user's own edit) is a conflict.
     */
    suspend fun planRestoreBefore(turn: TurnRecord): RestorePlan = locked {
        val current = pin(repo.snapshot("before restore"))
        val touched = repo.changes(turn.start, current.commit).map { it.path }
        val steps = steps(index.load(), turn.startedAt)
        val trees = HashMap<String, Map<String, TreeEntry>>()
        val changed = HashMap<Int, Set<String>>()
        val items = plan(turn.start, current.commit, touched) { path, now ->
            val last = steps.indices.reversed().firstOrNull { i ->
                path in changed.getOrPut(i) { repo.changes(steps[i].first, steps[i].second).map { it.path }.toSet() }
            } ?: return@plan true
            now != trees.getOrPut(steps[last].second) { repo.tree(steps[last].second) }[path]
        }
        RestorePlan(turn.id, RestoreKind.RestoreBefore, turn.start, current.commit, items)
    }

    /** Undo the restore [undo] of [turn]: its files go back to how they were just before it. */
    suspend fun planRedo(turn: TurnRecord, undo: UndoEntry): RestorePlan = locked {
        val current = pin(repo.snapshot("before redo"))
        val after = repo.tree(undo.after)
        val items = plan(undo.before, current.commit, undo.paths) { path, now -> now != after[path] }
        RestorePlan(turn.id, RestoreKind.Redo, undo.before, current.commit, items, undoAt = undo.at)
    }

    /** Runs [plan] for [paths] (the items the user kept; by default all). */
    suspend fun apply(plan: RestorePlan, paths: Collection<String> = plan.items.map { it.path }): RestoreResult {
        val chosen = plan.items.map { it.path }.filter { it in paths }
        if (chosen.isEmpty()) {
            discard(plan)
            return RestoreResult(emptyList(), emptyList())
        }
        return locked {
            try {
                val kept = repo.restore(plan.source, chosen)
                val after = repo.snapshot("after restore")
                val restored = chosen - kept.toSet()
                val now = clock()
                mutate { list ->
                    list.map { t ->
                        when {
                            t.id != plan.turnId -> t
                            plan.kind == RestoreKind.Redo -> t.copy(undos = t.undos.map { if (it.at == plan.undoAt) it.copy(redone = true) else it })
                            restored.isEmpty() -> t
                            else -> t.copy(undos = t.undos + UndoEntry(now, plan.current, after.commit, restored))
                        }
                    }
                }
                RestoreResult(restored, kept)
            } finally {
                pinned -= plan.current
            }
        }
    }

    /** A plan the user dismissed: its snapshot may be pruned again. */
    fun discard(plan: RestorePlan) {
        pinned -= plan.current
    }

    /** Suspends until the work queued before this call ran (tests, and a screen that wants the newest turn). */
    suspend fun idle() {
        val done = CompletableDeferred<Unit>()
        if (!work.trySend { done.complete(Unit) }.isSuccess) return
        done.await()
    }

    // Internals

    /**
     * The files of [paths] that differ between [source] and [current], each with its action and conflict rule
     * ([conflict] gets the file's entry now, null when absent).
     */
    private fun plan(source: String, current: String, paths: List<String>, conflict: (String, TreeEntry?) -> Boolean): List<RestoreItem> {
        val from = repo.tree(source)
        val now = repo.tree(current)
        val skippedNow = repo.skipped(current)
        return paths.distinct().mapNotNull { path ->
            val target = from[path]
            val present = now[path] ?: if (path in skippedNow) SKIPPED_ENTRY else null
            if (target == present) return@mapNotNull null
            val action = when {
                target == null -> ChangeKind.Deleted
                present == null -> ChangeKind.Added
                else -> ChangeKind.Modified
            }
            RestoreItem(path, action, conflict(path, present))
        }
    }

    /** Every change recorded since [since], oldest first: turns (start to end) and restores (before to after). */
    private fun steps(turns: List<TurnRecord>, since: Long): List<Pair<String, String>> {
        val list = ArrayList<Triple<Long, String, String>>()
        for (t in turns) {
            if (t.end != null && t.startedAt >= since) list += Triple(t.startedAt, t.start, t.end)
            for (u in t.undos) if (u.at >= since) list += Triple(u.at, u.before, u.after)
        }
        return list.sortedBy { it.first }.map { it.second to it.third }
    }

    private fun finishLocked(id: String, end: Snapshot) {
        val record = index.load().firstOrNull { it.id == id } ?: return
        val deltas = repo.changes(record.start, end.commit)
        mutate { list ->
            list.map {
                if (it.id != id) it else it.copy(endedAt = clock(), end = end.commit, files = deltas.size, added = deltas.sumOf { d -> d.added }, removed = deltas.sumOf { d -> d.removed })
            }
        }
    }

    private fun pruneLocked() {
        val all = index.load()
        val kept = CheckpointIndex.retain(all, clock())
        if (kept.size != all.size) {
            index.save(kept)
            _turns.value = kept
        }
        repo.prune(kept.flatMap { it.snapshots }.toSet() + pinned)
    }

    /** Read, change and write the index (the caller holds [lock]): another session in the project may have written. */
    private fun mutate(change: (List<TurnRecord>) -> List<TurnRecord>) {
        val next = change(index.load())
        index.save(next)
        _turns.value = next
    }

    private fun pin(snapshot: Snapshot): Snapshot {
        pinned += snapshot.commit
        return snapshot
    }

    private fun enqueue(job: suspend () -> Unit) {
        if (!closed) work.trySend(job)
    }

    private fun fail(e: Exception) {
        if (failing) return
        failing = true
        onNotice("Checkpoints paused: ${e.message ?: e.javaClass.simpleName}")
    }

    /** [block] on [Dispatchers.IO] holding this repository's lock. Blocks never nest. */
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private fun textOf(message: JsonObject): String = when (val content = message["content"]) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.type == "text" }?.str("text") }.joinToString("\n")
        else -> ""
    }

    companion object {
        private const val PROMPT_CHARS = 500

        // A huge project may take longer to snapshot the first time: the prompt then goes anyway.
        const val PREPARE_TIMEOUT_MS = 20_000L

        /** The turn of [turns] a user message started: by its [timestamp], or by its [text] when it has none. */
        fun find(turns: List<TurnRecord>, timestamp: Long?, text: String): TurnRecord? {
            if (timestamp != null) return turns.lastOrNull { it.userTimestamp == timestamp }
            val prompt = text.take(PROMPT_CHARS)
            return turns.lastOrNull { it.userTimestamp == null && it.prompt == prompt }
        }

        /** A file that exists but is over the size cap: not in the tree, and not to be mistaken for a deleted one. */
        private val SKIPPED_ENTRY = TreeEntry("large", "")

        private val locks = ConcurrentHashMap<String, Mutex>()

        /** Turns some live session in this process still has open: not left over by a dead one. */
        private val openTurns: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Snapshots of plans not applied yet: pruning keeps them. */
        private val pinned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
