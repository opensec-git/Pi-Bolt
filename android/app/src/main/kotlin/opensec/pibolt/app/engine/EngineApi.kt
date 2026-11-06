package opensec.pibolt.app.engine

import kotlinx.coroutines.flow.StateFlow
import opensec.pibolt.app.session.SessionController
import java.io.File

/**
 * The on-device engine as the UI sees it (android/engine/CONTRACT.md). The runtime implements it; screens use only
 * this, so they work the same whether the engine is starting, ready or unavailable on this device.
 */
interface EngineApi {
    val status: StateFlow<EngineStatus>

    /** Where projects live (`HOME/projects`). */
    val projectsDir: File

    /** Pi's agent directory (`HOME/.pi/agent`): settings.json, auth.json, sessions. */
    val agentDir: File

    /** Sets up the prefix if needed and starts a warm engine in the background. Safe to call often. */
    fun prepare()

    /**
     * Opens a session in [project]: resumes [sessionFile] when given (a path from [sessions]), otherwise continues the
     * project's most recent session, or starts a new one when [fresh] is true. Replaces the live session.
     */
    fun open(project: File, sessionFile: String? = null, fresh: Boolean = false): SessionController

    /** Saved Pi sessions for [project], newest first (read from the agent dir; no engine needed). */
    fun sessions(project: File): List<SessionSummary>

    /** Deletes every saved session of [project], including empty ones [sessions] does not list, and their folder. */
    fun deleteSessions(project: File)

    /** Provider credentials in Pi's auth.json. */
    val auth: ProviderAuth
}

sealed interface EngineStatus {
    /** Not started yet in this process. */
    data object Idle : EngineStatus

    /** Creating links, copying files, or starting the process. */
    data class Preparing(val step: String) : EngineStatus

    data class Ready(val engineVersion: String) : EngineStatus

    /** This device cannot run the engine (other user/work profile, unsupported ABI or Android version, missing pack). */
    data class Unavailable(val reason: String) : EngineStatus

    /** The engine failed to start or crashed; [detail] is for the log/sheet, [canRetry] when a retry may help. */
    data class Failed(val message: String, val detail: String?, val canRetry: Boolean) : EngineStatus
}

data class SessionSummary(
    /** Absolute path of the session file. */
    val file: String,
    val id: String,
    val name: String?,
    /** First user message, shortened, when the session has no name. */
    val preview: String?,
    val updatedAt: Long,
    val messageCount: Int,
)

/** Pi's provider credentials (auth.json in the agent dir). Values never leave the device and are never logged. */
interface ProviderAuth {
    /** Provider ids that have a stored credential (API key or OAuth). */
    val configured: StateFlow<Set<String>>

    /** True once [configured] reflects auth.json (it starts empty before the first read). */
    val loaded: StateFlow<Boolean>

    fun setApiKey(provider: String, key: String)

    fun remove(provider: String)
}
