package opensec.pibolt.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.ui.nav.AppNav
import opensec.pibolt.app.ui.packages.PackageLinks
import opensec.pibolt.app.ui.projects.transfer.SharedContent
import opensec.pibolt.app.ui.projects.transfer.sharedContent
import opensec.pibolt.app.ui.session.features2.SessionAlerts
import opensec.pibolt.app.ui.theme.PiBoltTheme
import opensec.pibolt.app.update.UpdateNotifications
import opensec.pibolt.protocol.PairingPayload

class MainActivity : ComponentActivity() {
    /** A pairing link from a deep link, waiting for the UI to pick it up. */
    private var pendingLink by mutableStateOf<String?>(null)

    /** A package to review and install (a `pibolt://install?source=…` link), waiting for its sheet. */
    private var pendingInstall by mutableStateOf<String?>(null)

    /** Files, images or text shared from another app, waiting for the share sheet. */
    private var pendingShare by mutableStateOf<SharedContent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light system bar icons to start (the default theme is dark); PiBoltTheme switches them for Paper.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) {
            pendingLink = linkFrom(intent)
            pendingInstall = installFrom(intent)
            pendingShare = sharedContent(intent)
        }
        if (savedInstanceState == null) SessionAlerts.take(intent)
        val app = application as PiBoltApp
        if (savedInstanceState == null) {
            UpdateNotifications.clearResult(this)
            app.updates.onLaunch(autoCheck = app.settings.settings.value.autoUpdateCheck)
        }
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            PiBoltTheme(settings) {
                AppNav(
                    app,
                    pendingLink,
                    consumeLink = { pendingLink = null },
                    pendingShare = pendingShare,
                    consumeShare = { pendingShare = null },
                    pendingInstall = pendingInstall,
                    consumeInstall = { pendingInstall = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's installer without an update (cancelled or failed): offer Install again.
        (application as PiBoltApp).updates.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
        installFrom(intent)?.let { pendingInstall = it }
        sharedContent(intent)?.let { pendingShare = it }
        SessionAlerts.take(intent)
    }

    private fun linkFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
            ?.takeIf { it.length <= PairingPayload.MAX_LINK_CHARS && PairingPayload.looksLikePairing(it) }

    /** The `pi install` source of a package link, which only opens its confirmation sheet. */
    private fun installFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(PackageLinks::fromUri)
}
