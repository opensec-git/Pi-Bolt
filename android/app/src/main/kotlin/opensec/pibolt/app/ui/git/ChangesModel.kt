package opensec.pibolt.app.ui.git

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.git.DiffStats
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.git.GitProgress
import opensec.pibolt.app.git.GitRepo
import opensec.pibolt.app.git.GitRunner
import opensec.pibolt.app.git.GitSource
import opensec.pibolt.app.git.GitStatus
import opensec.pibolt.app.git.github.GitHubException
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.AssistantTextItem
import java.io.File

/**
 * The Changes tab's state for one project: git status, the running operation and its progress. Every git call runs
 * through [GitRepo] (IO dispatcher, timeouts); one operation at a time, and each one refreshes the status after.
 *
 * With another [source] (a computer's working tree over remote control) the model is [readOnly]: it reads status,
 * diffs and history, and [run] does nothing.
 */
@Stable
class ChangesModel(val source: GitSource, val dir: File, private val scope: CoroutineScope, private val onError: (String) -> Unit) {
    constructor(runner: GitRunner, dir: File, scope: CoroutineScope, onError: (String) -> Unit) : this(GitRepo(runner, dir), dir, scope, onError)

    /** No staging, committing, branches or remotes: the views show only what [source] reads. */
    val readOnly: Boolean = source !is GitRepo

    /** This phone's repository, for the operations; only a model that is not [readOnly] has one. */
    val repo: GitRepo get() = source as? GitRepo ?: throw GitException("Read-only")

    /** Null until the first check. */
    var isRepo by mutableStateOf<Boolean?>(null)
        private set
    var status by mutableStateOf<GitStatus?>(null)
        private set

    /** What runs now ("Pushing", "Committing"), or null. */
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<GitProgress?>(null)
        private set
    var remote by mutableStateOf<String?>(null)
        private set

    /** Line counts of the staged and unstaged changes, for the "+N −M" summary and the file rows. */
    var stats by mutableStateOf(DiffStats.EMPTY)
        private set

    /** Bumped after every change so open diffs and the history reload. */
    var version by mutableStateOf(0)
        private set

    private var job: Job? = null

    fun refresh() {
        scope.launch { load() }
    }

    /** A read-only source that could not be read (the computer did not answer): the tab says so, with Try again. */
    var failed by mutableStateOf<String?>(null)
        private set

    private suspend fun load() {
        val repoHere = try {
            (!readOnly && File(dir, ".git").exists()) || source.isRepo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "Could not read the changes"
            return
        }
        failed = null
        isRepo = repoHere
        if (!repoHere) {
            status = null
            stats = DiffStats.EMPTY
            return
        }
        runCatching { source.status() }.onSuccess { status = it }.onFailure { if (it !is CancellationException) onError(it.message ?: "git status failed") }
        // Counts are a summary: only read when something changed, and a failure leaves them out quietly.
        stats = if (status?.clean == false) runCatching { source.diffStats() }.getOrDefault(DiffStats.EMPTY) else DiffStats.EMPTY
        remote = runCatching { source.remoteUrl() }.getOrNull()
    }

    /** Runs [block] unless something else runs; reports failures with their explained message, then refreshes. */
    fun run(label: String, onDone: () -> Unit = {}, block: suspend GitRepo.(progress: (GitProgress) -> Unit) -> Unit) {
        if (readOnly || job?.isActive == true) return
        busy = label
        progress = null
        job = scope.launch {
            try {
                repo.block { progress = it }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitException) {
                onError(e.message ?: "$label failed")
            } catch (e: GitHubException) {
                onError(e.message ?: "$label failed")
            } catch (e: Exception) {
                onError(e.message ?: "$label failed")
            } finally {
                busy = null
                progress = null
                version++
                load()
            }
        }
    }
}

/**
 * Asks Pi in this project's session for text (a commit message, a pull request) and returns its answer: the session
 * opens if needed, the prompt goes out, and the last assistant text of that turn comes back with any code fence
 * removed. Null when the turn ends without text or takes longer than [timeoutMs].
 */
object PiDraft {
    suspend fun ask(engine: EngineApi, store: ProjectStore, dir: File, prompt: String, timeoutMs: Long = 5 * 60_000L): String? {
        val live = LocalSessions.get(dir.name)?.takeIf { it.project.absolutePath == dir.absolutePath }
            ?: LocalSessions.open(engine, store, dir).getOrThrow()
        val c = live.controller
        val link = withTimeout(180_000) { c.link.first { it is LinkState.Connected || it is LinkState.Failed } }
        if (link is LinkState.Failed) throw IllegalStateException(link.message)
        withTimeout(10 * 60_000L) { c.state.first { !it.isRunning } }
        val before = c.state.value.items.size
        c.send(prompt, StreamingBehavior.FollowUp)
        return withTimeoutOrNull(timeoutMs) {
            // A short turn can start and end between two looks at the state: its answer counts as the start too.
            c.state.first { st -> st.isRunning || st.items.size > before + 1 }
            val done = c.state.first { !it.isRunning }
            done.items.drop(before).filterIsInstance<AssistantTextItem>().lastOrNull()?.text?.let(::clean)?.ifBlank { null }
        }
    }

    /** The text inside the first ``` fence when there is one, else the text; trimmed. */
    fun clean(text: String): String {
        val t = text.trim()
        val open = t.indexOf("```")
        if (open < 0) return t
        val bodyStart = t.indexOf('\n', open).takeIf { it >= 0 }?.plus(1) ?: return t
        val close = t.indexOf("```", bodyStart)
        return (if (close < 0) t.substring(bodyStart) else t.substring(bodyStart, close)).trim()
    }

    const val COMMIT_PROMPT =
        "Write a commit message for the staged changes in this repository (run `git diff --cached` to see them). " +
            "Use a short imperative subject line of at most 72 characters, a blank line, then a brief body only if it helps. " +
            "Reply with the message alone in one ```text fence. Do not commit, stage or change anything."

    fun pullRequestPrompt(base: String): String =
        "Write a pull request title and description for the commits on this branch that are not on `$base` " +
            "(run `git log --oneline $base..HEAD` and `git diff $base...HEAD --stat`, fetching first if needed). " +
            "Reply with one ```text fence: the title on the first line, a blank line, then the description in Markdown. " +
            "Do not push, commit or change anything."

    /** Title and body from a drafted pull request: the first line, then the rest. */
    fun splitTitle(draft: String): Pair<String, String> {
        val lines = draft.trim().lines()
        val title = lines.firstOrNull().orEmpty().trim().removePrefix("#").removePrefix("Title:").trim()
        return title to lines.drop(1).joinToString("\n").trim()
    }
}
