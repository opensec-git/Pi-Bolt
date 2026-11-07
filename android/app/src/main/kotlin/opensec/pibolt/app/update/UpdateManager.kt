package opensec.pibolt.app.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import opensec.pibolt.app.BuildConfig
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.BackgroundFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The download and install of one release. */
sealed interface UpdateJob {
    data object Idle : UpdateJob

    data class Downloading(val info: UpdateInfo, val bytes: Long, val total: Long, val waitingForNetwork: Boolean = false) : UpdateJob {
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    data class Verifying(val info: UpdateInfo) : UpdateJob

    /** Verified and waiting for the user to install it. [message] says why a previous attempt did not finish. */
    data class Ready(val info: UpdateInfo, val file: File, val message: String? = null) : UpdateJob

    /** Handed to Android's package installer, which asks the user to confirm. */
    data class Installing(val info: UpdateInfo) : UpdateJob

    /** [refused]: a check failed and the file was deleted (as opposed to a network or storage error). */
    data class Failed(val info: UpdateInfo, val message: String, val refused: Boolean) : UpdateJob
}

data class UpdateState(
    val installed: String,
    val loaded: Boolean = false,
    val checking: Boolean = false,
    val lastCheckAt: Long = 0,
    val latest: UpdateInfo? = null,
    val snoozedVersion: String? = null,
    val checkError: String? = null,
    val job: UpdateJob = UpdateJob.Idle,
    /** Set on the first launch after an update, until Home has said so. */
    val justUpdated: String? = null,
    /** This app's signing certificates, read with update.json (empty until then). */
    val installedCerts: Set<String> = emptySet(),
) {
    val decision: UpdateRules.Decision get() = UpdateRules.decide(installed, latest, snoozedVersion, installedCerts)
}

/** A sheet the app shows on top of any screen: confirm the download (network and size), or install. */
sealed interface UpdatePrompt {
    data class Download(val info: UpdateInfo) : UpdatePrompt

    data class Install(val info: UpdateInfo) : UpdatePrompt
}

/**
 * In-app updates for the sideloaded app. Checks android.json (GitHub's release list if the site is down) with a plain
 * GET, downloads the APK to the cache with resume, verifies it ([UpdateVerifier]) and hands it to Android's package
 * installer. The download keeps the process up through [UpdateService].
 */
class UpdateManager(private val app: PiBoltApp) {
    val source: UpdateSource = debugSource() ?: UpdateSource.RELEASE
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File get() = File(app.cacheDir, "updates")
    private var job: Job? = null

    private val _state = MutableStateFlow(UpdateState(installed = BuildConfig.VERSION_NAME))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt: StateFlow<UpdatePrompt?> = _prompt.asStateFlow()

    @Volatile
    private var saved = UpdateFile()
    private val file = BackgroundFile(app, "update.json") { text ->
        saved = UpdateFileCodec.decode(text, source)
        val certs = runCatching { AndroidPackageInspector(app).installed().certSha256 }.getOrDefault(emptySet())
        _state.update { it.copy(loaded = true, lastCheckAt = saved.lastCheckAt, latest = saved.latest, snoozedVersion = saved.snoozedVersion, installedCerts = certs) }
    }

    private val http: OkHttpClient by lazy {
        app.http.newBuilder()
            .pingInterval(0, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun save(change: (UpdateFile) -> UpdateFile) {
        synchronized(this) {
            saved = change(saved)
            file.write(UpdateFileCodec.encode(saved))
        }
    }

    /**
     * The launcher opened the app: note an update that just happened ("Updated to X" once), drop the downloads of
     * older versions, and run the automatic check if it is due (at most once a day, on IO).
     */
    fun onLaunch(autoCheck: Boolean) {
        io.launch {
            file.awaitLoaded()
            val installed = _state.value.installed
            val last = saved.lastRunVersion
            if (last != null && (AppVersion.compare(installed, last) ?: 0) > 0) _state.update { it.copy(justUpdated = installed) }
            if (last != installed) {
                save { it.copy(lastRunVersion = installed) }
                if (_state.value.job == UpdateJob.Idle) dir.listFiles()?.forEach { it.delete() }
            }
            if (UpdateRules.autoCheckDue(autoCheck, saved.lastCheckAt, System.currentTimeMillis())) check()
        }
    }

    fun consumeJustUpdated() = _state.update { it.copy(justUpdated = null) }

    /** Settings → Check for updates. */
    fun checkNow() {
        if (_state.value.checking) return
        io.launch { check() }
    }

    private suspend fun check() {
        _state.update { it.copy(checking = true, checkError = null) }
        val result = fetch(source.manifestUrl) { UpdateRules.parseManifest(it, source) }.recoverCatching { primary ->
            val fallback = source.githubReleasesUrl ?: throw primary
            fetch(fallback) { UpdateRules.parseGithubReleases(it, source) }.getOrThrow()
        }
        val now = System.currentTimeMillis()
        result.fold(
            onSuccess = { info ->
                save { it.copy(lastCheckAt = now, latest = info) }
                _state.update { it.copy(checking = false, lastCheckAt = now, latest = info) }
            },
            onFailure = { e ->
                _state.update { it.copy(checking = false, checkError = if (e is IOException) "No connection to the update server" else "Could not read the update information") }
            },
        )
    }

    private fun fetch(url: String, parse: (String) -> Result<UpdateInfo>): Result<UpdateInfo> = runCatching {
        // A plain GET: no cookies, no identifiers, no query beyond the fixed URL.
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body.string()
            if (body.length > 2_000_000) error("response too large")
            parse(body).getOrThrow()
        }
    }

    /** Later on Home: stay quiet about this version. */
    fun snooze(version: String) {
        save { it.copy(snoozedVersion = version) }
        _state.update { it.copy(snoozedVersion = version) }
    }

    /** Update tapped: confirm the download (size, network), or go straight to install when it is already here. */
    fun requestUpdate(info: UpdateInfo) {
        when (val j = _state.value.job) {
            is UpdateJob.Ready -> if (j.info == info) _prompt.value = UpdatePrompt.Install(info) else _prompt.value = UpdatePrompt.Download(info)
            is UpdateJob.Downloading, is UpdateJob.Verifying, is UpdateJob.Installing -> Unit
            else -> _prompt.value = UpdatePrompt.Download(info)
        }
    }

    fun requestInstall() {
        (_state.value.job as? UpdateJob.Ready)?.let { _prompt.value = UpdatePrompt.Install(it.info) }
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    fun dismissFailure() {
        if (_state.value.job is UpdateJob.Failed) _state.update { it.copy(job = UpdateJob.Idle) }
    }

    /** The network the download would use now: Wi-Fi, mobile data, metered or none. */
    fun network(): NetworkKind {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.Unknown
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return NetworkKind.None
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.None
        val metered = cm.isActiveNetworkMetered
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> if (metered) NetworkKind.MeteredWifi else NetworkKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Mobile
            else -> if (metered) NetworkKind.MeteredOther else NetworkKind.Unknown
        }
    }

    private fun connected(): Boolean = network() != NetworkKind.None

    fun startDownload(info: UpdateInfo) {
        _prompt.value = null
        if (job?.isActive == true) return
        _state.update { it.copy(job = UpdateJob.Downloading(info, 0, info.size)) }
        UpdateService.ensureRunning(app)
        job = io.launch {
            try {
                val apk = download(info)
                _state.update { it.copy(job = UpdateJob.Verifying(info)) }
                when (val result = UpdateVerifier.verify(apk, info, AndroidPackageInspector(app))) {
                    VerifyResult.Ok -> {
                        _state.update { it.copy(job = UpdateJob.Ready(info, apk)) }
                        if (app.isForeground) {
                            _prompt.value = UpdatePrompt.Install(info)
                        } else {
                            installWhenBack = true
                            UpdateNotifications.ready(app, info)
                        }
                    }
                    is VerifyResult.Refused -> {
                        apk.delete()
                        fail(info, result.message, refused = true)
                    }
                }
            } catch (e: CancellationException) {
                partFile(info).delete()
                _state.update { it.copy(job = UpdateJob.Idle) }
                throw e
            } catch (e: Exception) {
                fail(info, e.message ?: "The download failed", refused = false)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        activeCall?.cancel()
        job = null
        _state.update { if (it.job is UpdateJob.Downloading || it.job is UpdateJob.Verifying) it.copy(job = UpdateJob.Idle) else it }
    }

    private fun fail(info: UpdateInfo, message: String, refused: Boolean) {
        _state.update { it.copy(job = UpdateJob.Failed(info, message, refused)) }
        if (!app.isForeground) UpdateNotifications.failed(app, info, message)
    }

    private fun partFile(info: UpdateInfo) = File(dir, "pi-bolt-${info.version}.apk.part")

    private fun apkFile(info: UpdateInfo) = File(dir, "pi-bolt-${info.version}.apk")

    /**
     * Downloads [info]'s APK, resuming with an HTTP Range request after a dropped connection. Waits for the network to
     * come back instead of failing, and gives up after 10 minutes without progress.
     */
    private suspend fun download(info: UpdateInfo): File {
        dir.mkdirs()
        dir.listFiles()?.filter { it.name != partFile(info).name && it.name != apkFile(info).name }?.forEach { it.delete() }
        val done = apkFile(info)
        if (done.length() == info.size) return done
        val part = partFile(info)
        var failingSince = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = part.length()
            try {
                fetchInto(part, info)
                if (part.length() != info.size) throw IOException("incomplete")
                if (!part.renameTo(done)) throw FatalDownload("Could not save the download")
                return done
            } catch (e: IOException) {
                // Cancelled by the user (the call was cancelled under the read): not a network problem.
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                if (part.length() > before || failingSince == 0L) failingSince = now
                _state.update { s -> s.copy(job = UpdateJob.Downloading(info, part.length(), info.size, waitingForNetwork = true)) }
                delay(RETRY_PAUSE_MS)
                while (!connected()) {
                    if (System.currentTimeMillis() - failingSince > GIVE_UP_MS) throw FatalDownload("The download stopped: no connection")
                    delay(1000)
                }
                if (System.currentTimeMillis() - failingSince > GIVE_UP_MS) throw FatalDownload("The download stopped: the server does not answer")
            }
        }
    }

    /** A download error that retrying does not fix. */
    private class FatalDownload(message: String) : Exception(message)

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var installWhenBack = false

    private suspend fun fetchInto(part: File, info: UpdateInfo) {
        if (part.length() > info.size) part.delete()
        val from = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(info.apk).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
        val call = http.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                val append = when (response.code) {
                    206 -> from > 0 && response.header("Content-Range")?.startsWith("bytes $from-") == true
                    200 -> false
                    416 -> if (from == info.size) return else throw IOException("HTTP 416")
                    in 500..599, 408, 429 -> throw IOException("HTTP ${response.code}")
                    else -> throw FatalDownload("The download failed (HTTP ${response.code})")
                }
                var bytes = if (append) from else 0L
                var shownAt = 0L
                FileOutputStream(part, append).use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > info.size) {
                            out.close()
                            part.delete()
                            throw FatalDownload("The download is larger than announced; not installed.")
                        }
                        out.write(buffer, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - shownAt >= 250 || bytes == info.size) {
                            shownAt = now
                            _state.update { s -> s.copy(job = UpdateJob.Downloading(info, bytes, info.size)) }
                        }
                    }
                }
            }
        } finally {
            activeCall = null
        }
    }

    /** True when Android lets this app install packages ("Install unknown apps" is on for Pi-Bolt). */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /**
     * Hands the verified APK to Android's package installer, which asks the user to confirm and, once installed, offers
     * "Open" for the new version (Android stops the app to replace it and does not let the old or new version reopen
     * itself from the background). The file is hashed again first, so the installer gets exactly what was verified.
     */
    fun install(context: Context) {
        val ready = _state.value.job as? UpdateJob.Ready ?: return
        _prompt.value = null
        _state.update { it.copy(job = UpdateJob.Installing(ready.info)) }
        io.launch {
            if (!ready.file.isFile || UpdateVerifier.sha256(ready.file) != ready.info.sha256) {
                ready.file.delete()
                fail(ready.info, "The download changed after it was checked; not installed.", refused = true)
                return@launch
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", ready.file)
            val intent = installIntent(uri).putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, app.packageName)
            withContext(Dispatchers.Main) {
                if (runCatching { context.startActivity(intent) }.isFailure) {
                    _state.update { it.copy(job = UpdateJob.Ready(ready.info, ready.file, "Android did not open its installer.")) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * The app is in front again. A download that finished in the background offers its install; an install that was
     * handed over means the user cancelled Android's dialog or it failed (a finished update replaces the process
     * instead), and the verified file stays, so Install tries again.
     */
    fun onResume() {
        // The download finished in the background ("ready to install" notification): offer the install now.
        if (installWhenBack) {
            installWhenBack = false
            (_state.value.job as? UpdateJob.Ready)?.let { _prompt.value = UpdatePrompt.Install(it.info) }
        }
        val installing = _state.value.job as? UpdateJob.Installing ?: return
        val apk = apkFile(installing.info)
        _state.update { it.copy(job = if (apk.isFile) UpdateJob.Ready(installing.info, apk, "The update was not installed.") else UpdateJob.Idle) }
    }

    private fun debugSource(): UpdateSource? {
        if (!BuildConfig.DEBUG) return null
        val manifest = BuildConfig.UPDATE_MANIFEST_OVERRIDE.takeIf { it.isNotBlank() } ?: return null
        val prefix = BuildConfig.UPDATE_APK_PREFIX_OVERRIDE.takeIf { it.isNotBlank() } ?: return null
        return UpdateSource(manifest, prefix, githubReleasesUrl = null)
    }

    companion object {
        private const val RETRY_PAUSE_MS = 2_000L
        private const val GIVE_UP_MS = 10 * 60 * 1000L
    }
}

enum class NetworkKind { Wifi, MeteredWifi, Mobile, MeteredOther, None, Unknown }

/** For the receivers, which only get a Context. */
internal val Context.updates: UpdateManager get() = (applicationContext as PiBoltApp).updates
