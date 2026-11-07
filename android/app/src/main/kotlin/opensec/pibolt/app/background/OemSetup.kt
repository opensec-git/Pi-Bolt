package opensec.pibolt.app.background

/**
 * The extra background switches of OEM builds that stop apps Android itself would keep running (MIUI, ColorOS,
 * Funtouch, EMUI/MagicOS, One UI, OxygenOS), and the screens that hold them. Pure: [resolve] takes the check that a
 * screen exists, so the order and the fallback are tested without a phone.
 *
 * The component names are the ones these builds have shipped (dontkillmyapp.com, AutoStarter); a build that renamed
 * them falls back to the app's own details page, where "Battery" holds the same switch on most of them.
 */
object OemSetup {
    enum class Vendor { Xiaomi, Huawei, Honor, Oppo, Vivo, Samsung, OnePlus }

    /** A screen to open: an explicit activity of the vendor's app, with string extras. */
    data class Screen(val pkg: String, val cls: String, val extras: Map<String, String> = emptyMap())

    /** One switch to turn on: its label, the one line saying what to do, and its screens, best first. */
    data class Step(val id: String, val label: String, val hint: String, val screens: List<Screen>)

    /** Where a step leads: the first [Screen] that exists, else the app's details page. */
    sealed interface Target {
        data class Open(val screen: Screen) : Target

        data object AppDetails : Target
    }

    /** The vendor whose switches apply, from Build.MANUFACTURER and Build.BRAND. Null: stock behaviour. */
    fun vendor(manufacturer: String, brand: String): Vendor? {
        val names = listOf(manufacturer, brand).map { it.trim().lowercase() }
        fun any(vararg n: String) = names.any { it in n }
        return when {
            any("xiaomi", "redmi", "poco") -> Vendor.Xiaomi
            any("honor") -> Vendor.Honor
            any("huawei") -> Vendor.Huawei
            any("oneplus") -> Vendor.OnePlus
            any("oppo", "realme") -> Vendor.Oppo
            any("vivo", "iqoo") -> Vendor.Vivo
            any("samsung") -> Vendor.Samsung
            else -> null
        }
    }

    private val colorOsStartup = listOf(
        Screen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Screen("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Screen("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        Screen("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"),
    )

    /** The vendor's steps, beyond Android's battery optimization (BackgroundSetup asks for that one on every phone). */
    fun steps(vendor: Vendor?, packageName: String, appLabel: String): List<Step> = when (vendor) {
        null -> emptyList()
        Vendor.Xiaomi -> listOf(
            Step(
                "autostart",
                "Autostart",
                "Turn on Autostart for $appLabel",
                listOf(Screen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            ),
            Step(
                "battery-saver",
                "Battery saver",
                "Choose No restrictions",
                listOf(
                    Screen(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                        mapOf("package_name" to packageName, "package_label" to appLabel),
                    ),
                ),
            ),
        )
        Vendor.Huawei, Vendor.Honor -> listOf(
            Step(
                "app-launch",
                "App launch",
                "Turn off Manage automatically for $appLabel",
                listOfNotNull(
                    Screen("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity").takeIf { vendor == Vendor.Honor },
                    Screen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    Screen("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                    Screen("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ),
            ),
        )
        Vendor.Oppo -> listOf(Step("auto-launch", "Auto launch", "Allow $appLabel to auto launch", colorOsStartup))
        // OxygenOS 12 and later is ColorOS underneath.
        Vendor.OnePlus -> listOf(
            Step(
                "auto-launch",
                "Auto launch",
                "Allow $appLabel to auto launch",
                listOf(Screen("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")) + colorOsStartup,
            ),
        )
        Vendor.Vivo -> listOf(
            Step(
                "background",
                "Background activity",
                "Allow $appLabel in the background",
                listOf(
                    Screen("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    Screen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ),
            ),
        )
        Vendor.Samsung -> listOf(
            Step(
                "sleeping-apps",
                "Sleeping apps",
                "Add $appLabel to Never sleeping apps",
                listOf(
                    Screen("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    Screen("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    Screen("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ),
            ),
        )
    }

    /** The first of [step]'s screens that [exists] (resolves to an exported activity), else the app's details. */
    fun resolve(step: Step, exists: (Screen) -> Boolean): Target = step.screens.firstOrNull(exists)?.let { Target.Open(it) } ?: Target.AppDetails

    /**
     * The step whose screen answers Android's battery optimization request, when several activities [handlers]
     * (package, class) answer it. MIUI does: its per-app battery saver ("No restrictions" puts the app on Android's
     * allowlist) and a power usage page, so the request shows a chooser and the step repeats the battery row. Null when
     * one or no activity answers (Android's own dialog) or none of them is a step's screen.
     */
    fun batteryStep(steps: List<Step>, handlers: List<Pair<String, String>>): Step? {
        if (handlers.size < 2) return null
        return steps.firstOrNull { step -> step.screens.any { s -> handlers.any { it.first == s.pkg && it.second == s.cls } } }
    }

    /** Every package [steps] may open, for the manifest's <queries> (package visibility). Kept in sync by a test. */
    fun packages(): Set<String> = Vendor.entries.flatMap { v -> steps(v, "p", "a").flatMap { s -> s.screens.map { it.pkg } } }.toSet()
}
