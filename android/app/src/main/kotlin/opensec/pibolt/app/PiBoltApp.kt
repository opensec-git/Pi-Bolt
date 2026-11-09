package opensec.pibolt.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import opensec.pibolt.app.cost.SpendStore
import opensec.pibolt.app.data.DeviceIdentity
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.data.HostStore
import opensec.pibolt.app.data.RecentPrompts
import opensec.pibolt.app.data.SettingsStore
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.LocalEngine
import opensec.pibolt.app.engine.PageEngine
import opensec.pibolt.app.git.GitRunner
import opensec.pibolt.app.git.ProcessGitRunner
import opensec.pibolt.app.git.github.GitCredentialBroker
import opensec.pibolt.app.git.github.GitHub
import opensec.pibolt.app.git.github.KeystoreBox
import opensec.pibolt.app.git.github.LocalSocketListener
import opensec.pibolt.app.git.github.PrefsKeyValue
import opensec.pibolt.app.git.github.TokenStore
import opensec.pibolt.app.preview.PreviewBroker
import opensec.pibolt.app.preview.PreviewController
import opensec.pibolt.app.preview.PreviewReply
import opensec.pibolt.app.session.Notifications
import opensec.pibolt.app.terminal.Terminals
import opensec.pibolt.app.session.LiveRegistry
import opensec.pibolt.app.session.RunMarks
import opensec.pibolt.app.ui.chats.Chats
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.providers.ProviderDirectory
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.session.SessionService
import opensec.pibolt.app.ui.session.features2.SessionAlerts
import opensec.pibolt.app.update.UpdateManager
import opensec.pibolt.app.update.UpdateNotifications
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.PairingPayload
import opensec.pibolt.protocol.backend.RemoteBackend
import java.io.File
import java.util.concurrent.TimeUnit

class PiBoltApp : Application() {
    lateinit var hosts: HostStore
        private set
    lateinit var identity: DeviceIdentity
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var recents: RecentPrompts
        private set

    /** Spending per day and month, across sessions (cost/). */
    lateinit var spend: SpendStore
        private set
    lateinit var updates: UpdateManager
        private set

    /** The in-app preview's WebView, shared by the preview screen and Pi's preview tools. */
    lateinit var preview: PreviewController
        private set

    /**
     * The preview tools' socket (assets/extensions/pibolt-preview.js): project engines get its path; requests from the
     * chats' directory are refused (chat engines get no path and no tools either).
     */
    val previewBroker: PreviewBroker by lazy {
        PreviewBroker(File(noBackupFilesDir, "preview/socket"), { path, handle -> LocalSocketListener.listen(path, handle, "preview-tools") }) { request ->
            if (File(request.cwd).absoluteFile.normalize() == engine.chatDir.absoluteFile.normalize()) {
                PreviewReply.error("The preview is only for projects")
            } else {
                preview.handle(request)
            }
        }
    }
    val http: OkHttpClient by lazy { RemoteBackend.defaultClient() }

    private val localEngine: LocalEngine by lazy { LocalEngine(this) }

    /** Turns running on this phone, kept on disk to tell after a process death which ones were cut off. */
    val runMarks: RunMarks by lazy { RunMarks(File(noBackupFilesDir, "running-turns.json")) }

    /** GitHub sign-in (token sealed by the Keystore) and REST calls. A new token restarts the idle engine with it. */
    val github: GitHub by lazy {
        GitHub(
            store = TokenStore(PrefsKeyValue(getSharedPreferences("github", MODE_PRIVATE)), KeystoreBox("pibolt-github-token")),
            http = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build(),
            clientId = BuildConfig.GITHUB_CLIENT_ID,
            onCredentialsChanged = { localEngine.stopIdleEngines("GitHub credentials changed") },
            // The token reaches git through this socket, never through an environment a project's scripts inherit.
            socketBroker = { credential -> GitCredentialBroker(File(noBackupFilesDir, "git-credentials/socket"), credential, LocalSocketListener::listen) },
        )
    }

    /** The engine's own git, run by the app (Changes, clone), with the engine's environment. */
    val git: GitRunner by lazy { ProcessGitRunner({ localEngine.gitBinary() }, { localEngine.toolEnvironment() }) }

    /** The engine on this phone: projects, local sessions, provider keys. */
    val engine: EngineApi get() = localEngine

    /** Providers with keys, custom endpoints and those Pi packages register; which of them have a working model. */
    val providers: ProviderDirectory by lazy {
        ProviderDirectory(localEngine, File(noBackupFilesDir, "engine-providers.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /** Packages' pages and Home tiles without a session (docs/mobile-ui.md#package-pages-and-home-tiles). */
    val pages: PageEngine by lazy { PageEngine(this, localEngine) }

    /** Work that outlives any screen, on the main thread (UI-side bookkeeping such as turn durations). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val live = LiveRegistry<SessionController>(SystemClock::elapsedRealtime)

    /**
     * Every live session: up to [opensec.pibolt.app.engine.LivePolicy.MAX_LIVE] on this phone's engine (several in one
     * project when one was busy as another opened), each with its own engine process, and at most one with a computer. They run side by side: opening or
     * switching to one stops none of the others.
     */
    val sessions: StateFlow<List<SessionController>> = live.all

    /** The session last opened or shown (remote or local), if it is still live. */
    val session: StateFlow<SessionController?> = live.focused

    private val _remote = MutableStateFlow<SessionController?>(null)

    /** The live remote-control session, if any. */
    val remote: StateFlow<SessionController?> = _remote.asStateFlow()

    /** The user is looking at [controller]: it gets the focus (notifications, memory rules keep it last). */
    fun focus(controller: SessionController) = live.focus(controller)

    /** When [controller] was last opened or shown (elapsedRealtime), 0 when not live. */
    fun lastUsed(controller: SessionController): Long = live.lastUsed(controller)

    @Volatile
    private var startedActivities = 0
    val isForeground: Boolean get() = startedActivities > 0

    val deviceName: String by lazy {
        runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    val appVersion: String get() = BuildConfig.VERSION_NAME

    override fun onCreate() {
        super.onCreate()
        hosts = HostStore(this)
        settings = SettingsStore(this)
        spend = SpendStore(filesDir)
        recents = RecentPrompts(this)
        identity = DeviceIdentity(this)
        updates = UpdateManager(this)
        // Before any activity starts: the controller tracks the activity it parks its WebView in.
        preview = PreviewController(this) { extra -> Terminals.scanPorts(extra) }
        Notifications.createChannels(this)
        // No session lives yet: a "needs an answer" or session notification from a killed process is stale.
        Notifications.clearStale(this)
        // Turns the previous process was running when Android ended it: said once, each reopens its session.
        for (run in runMarks.takeAll()) Notifications.interrupted(this, run)
        LocalSessions.follow(this)
        Chats.follow(this)
        SessionAlerts.watchBackgroundAgents(this)
        UpdateNotifications.createChannel(this)
        localEngine.prefetch()
        // Ready the engine (prefix check, spare process) before any screen asks for it. All of it runs on Dispatchers.IO.
        localEngine.prepare()
        // Custom endpoints' and extension providers' names and marks, and which providers have a working model, for
        // Home, the model picker and Settings before Providers opens. The engine is asked once its spare is up.
        providers.refresh()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) {
                    localEngine.onForeground()
                    // Work that began while Android refused the service (the app was away): it may start now.
                    if (sessions.value.any { !it.stopped && it.keepsService }) SessionService.ensureRunning(this@PiBoltApp)
                    sessions.value.forEach { it.onAppForeground() }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) localEngine.onBackground()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Connect to a paired computer (or return the live session with it). */
    fun open(host: Host): SessionController {
        _remote.value?.let {
            if (it.hostId == host.id && !it.stopped) {
                live.focus(it)
                return it
            }
        }
        return replaceRemote(SessionController(this, host.id, host.name, host.endpoints, pairSecret = null))
    }

    /**
     * Use a pairing code: pair with a new computer, or reconnect to a known one with the code's (possibly new)
     * addresses. A known computer that no longer knows this phone is paired again with the code's secret.
     */
    fun pair(payload: PairingPayload): SessionController {
        val known = hosts.get(payload.hostKeyId)
        val secretUsable = !payload.isExpired()
        val controller = if (known != null) {
            // Nothing in the stored host changes until the handshake proves the host key: SessionController stores the
            // endpoint and name that worked on connect. The link's addresses are tried first, then the known ones.
            val endpoints = (payload.endpoints + known.endpoints).distinct()
            SessionController(this, known.id, known.name, endpoints, pairSecret = null, fallbackSecret = payload.secret.takeIf { secretUsable })
        } else {
            SessionController(this, payload.hostKeyId, payload.hostName, payload.endpoints, pairSecret = payload.secret)
        }
        return replaceRemote(controller)
    }

    /** Ends the remote-control session. Sessions on this phone keep running. */
    fun closeSession() {
        _remote.value?.stop()
    }

    /** Forget a computer on this phone. The computer still lists the phone until revoked in /remote-control devices. */
    fun forget(hostId: String) {
        if (_remote.value?.hostId == hostId) closeSession()
        hosts.remove(hostId)
        recents.forget(hostId)
    }

    /**
     * This phone's key as the computers list it (`/remote-control devices` shows the first 8 characters of the base64url
     * public key), so the user can tell which entry to revoke.
     */
    fun deviceKeyId(): String = Base64Url.encode(identity.keyPair().publicKey).take(8)

    /**
     * A new device key. No paired computer knows it, so the live session ends and every computer is forgotten on this
     * phone: each one has to be paired again with a new QR code. Returns the new key's id.
     */
    fun resetDeviceKey(): String {
        closeSession()
        identity.reset()
        for (host in hosts.hosts.value) hosts.remove(host.id)
        return deviceKeyId()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The idle warm engine is the first thing to give back when the system runs short. TRIM_MEMORY_BACKGROUND alone
        // arrives soon after every trip out of the app (API 34+ sends nothing stronger): stopping the spare at once made
        // the next open after a quick look at another app a cold start. It stops a minute later unless the app is back.
        @Suppress("DEPRECATION")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                localEngine.trimMemory("trim level $level", critical = true)
                pages.trim(critical = true)
            }
            // Sent while the app is in front: a package page on screen keeps its engine (it stopped and the page hung
            // on "Opening"); one that only serves Home tiles still stops.
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                localEngine.trimMemory("trim level $level", critical = true)
                pages.trim(critical = false)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                localEngine.trimMemory("trim level $level")
                pages.trim(critical = false)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> localEngine.trimSoon("trim level $level")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        @Suppress("DEPRECATION")
        super.onLowMemory()
        localEngine.trimMemory("low memory", critical = true)
        pages.trim(critical = true)
    }

    /**
     * Adds a session on this phone's engine and focuses it, in place of [replacing] (an idle session of the project,
     * which stops; never a busy one). Other sessions keep running.
     */
    internal fun addLocalSession(controller: SessionController, replacing: SessionController?): SessionController {
        live.add(controller, replacing)
        replacing?.stop()
        controller.start()
        return controller
    }

    /** The remote-control session: one at a time, so it replaces the previous one. Local sessions keep running. */
    private fun replaceRemote(controller: SessionController): SessionController {
        val previous = _remote.value
        _remote.value = controller
        live.add(controller, previous)
        previous?.stop()
        controller.start()
        return controller
    }

    /** [controller] stopped (SessionController.stop): it leaves the live sessions. */
    internal fun sessionStopped(controller: SessionController) {
        live.remove(controller)
        if (_remote.value === controller) _remote.value = null
    }
}
