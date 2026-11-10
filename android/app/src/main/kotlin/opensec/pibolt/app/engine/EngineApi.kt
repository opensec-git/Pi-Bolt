package opensec.pibolt.app.engine

import kotlinx.coroutines.flow.StateFlow
import opensec.pibolt.app.cost.Cost
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

    /** The working directory of plain chats ([ChatSpace]): never a project. */
    val chatDir: File

    /**
     * Opens a plain chat ([ChatSpace]: no project, no tools): resumes [sessionFile] when given (a path from [chats]),
     * otherwise starts a new chat. One chat is live at a time; it moves to the wanted one.
     */
    fun openChat(sessionFile: String? = null): SessionController

    /** Saved chats with at least one message, newest first. Blocking file I/O: call off the main thread. */
    fun chats(): List<SessionSummary>

    /** Saved Pi sessions for [project], newest first (read from the agent dir; no engine needed). For [chatDir]: [chats]. */
    fun sessions(project: File): List<SessionSummary>

    /** What the saved session [sessionFile] cost so far, with its subagents' sessions. Blocking file I/O. */
    fun sessionCost(sessionFile: String): Cost = Cost.NONE

    /** Deletes every saved session of [project], including empty ones [sessions] does not list, and their folder. */
    fun deleteSessions(project: File)

    /** Provider credentials in Pi's auth.json. */
    val auth: ProviderAuth

    /**
     * Runs the engine as Pi's command line (`pi list`, `pi install …`, `pi mcp list --json`) in [cwd] (HOME when
     * null) and returns its exit code and output. [env] adds variables for this run only, from [CLI_ENV] (the private
     * registry's `.npmrc`, ui/packages/PrivateRegistry); others are ignored. Not available where the engine is not.
     */
    suspend fun cli(args: List<String>, cwd: File? = null, timeoutMs: Long = 180_000, env: Map<String, String> = emptyMap()): CliResult =
        CliResult(-1, "", "The engine is not available on this phone")

    companion object {
        /** The variables [cli] lets a caller set. */
        val CLI_ENV = setOf("npm_config_globalconfig")
    }

    /**
     * The engine's providers, built-in, from extensions and custom, with their auth and the models that work now (Pi's
     * `get_providers`, or the app's helper extension on engines before 1.1.7). Asks an engine the app keeps idle, never
     * a session's. Null when no engine could answer.
     */
    suspend fun providers(): ProviderReport? = null

    /**
     * Starts [provider]'s own login flow ([method] "oauth" or "api_key") in an engine the app keeps for itself; the
     * credential is saved to Pi's auth.json. Null where the engine is not available.
     */
    fun login(provider: String, method: String): ProviderLogin? = null

    /** Packages were installed, removed or switched on or off: idle engines start again with them. */
    fun packagesChanged() {}

    /** The language runtimes this build ships and the space npm and pip use. Blocking file I/O: call off the main thread. */
    fun runtimes(): RuntimesReport

    /** Deletes the npm or pip cache. Blocking: call off the main thread. */
    fun clearRuntimeStorage(kind: RuntimeStorage.Kind): Boolean
}

/** A finished `pi` command: [code] is its exit status (-1 when it did not run or timed out). */
data class CliResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0
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
    /** What the session cost: its own usage records plus its subagents' sessions. */
    val cost: Cost = Cost.NONE,
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
