package opensec.pibolt.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import opensec.pibolt.app.data.DeviceIdentity
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.data.HostStore
import opensec.pibolt.app.data.RecentPrompts
import opensec.pibolt.app.data.SettingsStore
import opensec.pibolt.app.session.Notifications
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.PairingPayload
import opensec.pibolt.protocol.backend.RemoteBackend

class PiBoltApp : Application() {
    lateinit var hosts: HostStore
        private set
    lateinit var identity: DeviceIdentity
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var recents: RecentPrompts
        private set
    val http: OkHttpClient by lazy { RemoteBackend.defaultClient() }

    /** Work that outlives any screen, on the main thread (UI-side bookkeeping such as turn durations). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<SessionController?>(null)

    /** The one live session, if any. */
    val session: StateFlow<SessionController?> = _session.asStateFlow()

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
        recents = RecentPrompts(this)
        identity = DeviceIdentity(this)
        Notifications.createChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
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
        _session.value?.let { if (it.hostId == host.id && !it.stopped) return it }
        return replace(SessionController(this, host.id, host.name, host.endpoints, pairSecret = null))
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
        return replace(controller)
    }

    fun closeSession() {
        _session.value?.stop()
        _session.value = null
    }

    /** Forget a computer on this phone. The computer still lists the phone until revoked in /remote-control devices. */
    fun forget(hostId: String) {
        if (_session.value?.hostId == hostId) closeSession()
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

    private fun replace(controller: SessionController): SessionController {
        _session.value?.stop()
        _session.value = controller
        controller.start()
        return controller
    }
}
