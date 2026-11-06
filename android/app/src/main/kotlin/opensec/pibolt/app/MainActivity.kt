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
import opensec.pibolt.app.ui.theme.PiBoltTheme
import opensec.pibolt.protocol.PairingPayload

class MainActivity : ComponentActivity() {
    /** A pairing link from a deep link, waiting for the UI to pick it up. */
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: light system bar icons on every theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) pendingLink = linkFrom(intent)
        val app = application as PiBoltApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            PiBoltTheme(settings) {
                AppNav(app, pendingLink) { pendingLink = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink = it }
    }

    private fun linkFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.takeIf { PairingPayload.looksLikePairing(it) }
}
