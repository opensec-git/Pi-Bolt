package opensec.pibolt.app.background

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What keeps a run going with the app away, beyond the foreground service: Android's battery optimization exemption,
 * the absence of the user's "background restriction", and the OEM switches of [OemSetup]. Reads the state, opens the
 * screens that change it, and decides the one-time prompt (ui/settings/BackgroundRun.kt shows it).
 */
object BackgroundSetup {
    private const val PREFS = "background_run"
    private const val KEY_PROMPTED = "prompted"
    private const val KEY_PENDING = "pending"

    data class State(
        /** Android's battery optimization is off for the app (on the allowlist). */
        val unrestricted: Boolean,
        /** The user restricted the app's background use (Settings, Battery): foreground services may not start. */
        val restricted: Boolean,
        val notifications: Boolean,
        /** The vendor's switches still to check, without [battery]. */
        val steps: List<OemSetup.Step>,
        /** The vendor's screen that stands in for Android's battery optimization request ([OemSetup.batteryStep]). */
        val battery: OemSetup.Step? = null,
    ) {
        val ready: Boolean get() = unrestricted && !restricted
    }

    fun state(context: Context): State {
        val pkg = context.packageName
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val steps = OemSetup.steps(OemSetup.vendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()), pkg, label(context))
        val battery = if (steps.isEmpty()) null else OemSetup.batteryStep(steps, handlers(context, askBattery(context)))
        return State(
            unrestricted = runCatching { power.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false),
            restricted = Build.VERSION.SDK_INT >= 28 && runCatching { activity.isBackgroundRestricted }.getOrDefault(false),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            steps = steps - setOfNotNull(battery),
            battery = battery,
        )
    }

    /** Android's battery optimization request for this app (see [openBattery] on why a sideloaded APK may ask). */
    @SuppressLint("BatteryLife")
    private fun askBattery(context: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ("package:" + context.packageName).toUri())

    /** The (package, class) of every activity that handles [intent]; their packages must be in the manifest's <queries>. */
    private fun handlers(context: Context, intent: Intent): List<Pair<String, String>> = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
    }.getOrDefault(emptyList()).mapNotNull { it.activityInfo?.let { a -> a.packageName to a.name } }

    private fun label(context: Context): String = runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("Pi-Bolt")

    // The one-time prompt: armed the first time a local run continues with the app away, shown when the user is back.

    private val _prompt = MutableStateFlow(false)

    /** True while the prompt waits to be shown. */
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var prompted = false

    /** Set up already when this process checked: no prompt needed now. */
    @Volatile
    private var readyNow = false

    private fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prompted = prefs.getBoolean(KEY_PROMPTED, false)
        _prompt.value = !prompted && prefs.getBoolean(KEY_PENDING, false)
        loaded = true
    }

    /** Reads the saved prompt state (the composable, on start). */
    fun restore(context: Context) = load(context.applicationContext)

    /** A local run works with the app away (SessionService, on each update). Cheap after the first call. */
    fun noteBackgroundRun(context: Context) {
        load(context.applicationContext)
        if (prompted || readyNow || _prompt.value) return
        val s = state(context)
        if (s.ready && s.steps.isEmpty()) {
            readyNow = true
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_PENDING, true) }
        _prompt.value = true
    }

    /** The prompt was shown and closed: never again (Settings keeps the entry). */
    fun promptDone(context: Context) {
        prompted = true
        _prompt.value = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PROMPTED, true)
            putBoolean(KEY_PENDING, false)
        }
    }

    // Screens

    /**
     * Asks Android to stop optimizing the app's battery use (its system dialog), or, once exempt, opens the list where
     * it can be undone. A sideloaded APK may ask directly (Play policy limits this to some app kinds; it is not on Play).
     * Where the vendor answers the request with its own screen ([State.battery], MIUI's per-app battery saver), that
     * screen opens directly, either way: on MIUI the request and the list both show a "Complete action using" chooser.
     */
    @SuppressLint("BatteryLife")
    fun openBattery(context: Context, state: State) {
        if (state.battery != null) return open(context, state.battery)
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val first = if (state.unrestricted) list else askBattery(context)
        if (!start(context, first) && !start(context, list)) openAppDetails(context)
    }

    /** Opens [step]'s screen, or the app's details when this build has none of them. */
    fun open(context: Context, step: OemSetup.Step) {
        when (val target = OemSetup.resolve(step) { exists(context, it) }) {
            is OemSetup.Target.Open -> if (!start(context, intent(target.screen))) openAppDetails(context)
            OemSetup.Target.AppDetails -> openAppDetails(context)
        }
    }

    fun openAppDetails(context: Context) {
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + context.packageName).toUri()))
    }

    fun openNotifications(context: Context) {
        start(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    private fun intent(screen: OemSetup.Screen): Intent = Intent().setClassName(screen.pkg, screen.cls).apply { screen.extras.forEach { (k, v) -> putExtra(k, v) } }

    /** The screen's activity is installed and other apps may start it. Its package is in the manifest's <queries>. */
    private fun exists(context: Context, screen: OemSetup.Screen): Boolean {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent(screen), PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent(screen), 0)
            }
        }.getOrNull()
        return info?.activityInfo?.exported == true
    }

    /** Starts [intent]; false when nothing handles it or the vendor refuses (a SecurityException). */
    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
