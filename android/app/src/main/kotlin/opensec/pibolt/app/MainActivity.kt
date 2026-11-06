package opensec.pibolt.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import opensec.pibolt.app.ui.hosts.HostsScreen
import opensec.pibolt.app.ui.scan.ScanScreen
import opensec.pibolt.app.ui.session.SessionScreen
import opensec.pibolt.app.ui.theme.PiBoltTheme
import opensec.pibolt.protocol.PairingPayload

class MainActivity : ComponentActivity() {
    /** A pairing link from a deep link, waiting for the UI to pick it up. */
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingLink = linkFrom(intent)
        val app = application as PiBoltApp
        setContent {
            PiBoltTheme {
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

object Routes {
    const val HOSTS = "hosts"
    const val SCAN = "scan"
    const val SESSION = "session"
}

@Composable
private fun AppNav(app: PiBoltApp, pendingLink: String?, consumeLink: () -> Unit) {
    val nav = rememberNavController()
    var linkError by remember { mutableStateOf<String?>(null) }
    // A link from outside the app (a browser, a chat) pairs only after the user confirms it in the app.
    var linkToConfirm by remember { mutableStateOf<PairingPayload?>(null) }
    val liveSession by app.session.collectAsState()

    fun usePairing(text: String): String? {
        val payload = try {
            PairingPayload.parse(text)
        } catch (e: Exception) {
            return e.message ?: "Not a Pi-Bolt pairing code"
        }
        if (payload.isExpired() && app.hosts.get(payload.hostKeyId) == null) {
            return "This pairing code has expired. Run /remote-control on the computer for a new one."
        }
        app.pair(payload)
        nav.navigate(Routes.SESSION) {
            popUpTo(Routes.HOSTS)
            launchSingleTop = true
        }
        return null
    }

    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        consumeLink()
        linkToConfirm = try {
            PairingPayload.parse(link)
        } catch (e: Exception) {
            linkError = e.message ?: "Not a Pi-Bolt pairing code"
            null
        }
    }

    linkToConfirm?.let { payload ->
        val known = app.hosts.get(payload.hostKeyId) != null
        val live = liveSession?.takeIf { !it.stopped }
        AlertDialog(
            onDismissRequest = { linkToConfirm = null },
            title = { Text(if (known) "Connect to ${payload.hostName}?" else "Pair with ${payload.hostName}?") },
            text = {
                Text(
                    buildString {
                        append("A link opened Pi-Bolt to ")
                        append(if (known) "connect to a paired computer" else "pair this phone with a computer")
                        append(". Only continue if you just ran /remote-control on it.\n\n")
                        append(payload.endpoints.joinToString("\n"))
                        if (live != null) append("\n\nThis ends the current session.")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = payload.encode()
                    linkToConfirm = null
                    linkError = usePairing(text)
                }) { Text(if (known) "Connect" else "Pair") }
            },
            dismissButton = { TextButton(onClick = { linkToConfirm = null }) { Text("Cancel") } },
        )
    }

    NavHost(nav, startDestination = Routes.HOSTS) {
        composable(Routes.HOSTS) {
            HostsScreen(
                app = app,
                externalError = linkError,
                onErrorShown = { linkError = null },
                onOpen = { host ->
                    app.open(host)
                    nav.navigate(Routes.SESSION) { launchSingleTop = true }
                },
                onScan = { nav.navigate(Routes.SCAN) { launchSingleTop = true } },
                onPairingText = { usePairing(it) },
                onResume = { nav.navigate(Routes.SESSION) { launchSingleTop = true } },
            )
        }
        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { nav.popBackStack() },
                onCode = { usePairing(it) },
            )
        }
        composable(Routes.SESSION) {
            SessionScreen(
                app = app,
                onBack = { nav.popBackStack(Routes.HOSTS, inclusive = false) },
                onForgotten = { nav.popBackStack(Routes.HOSTS, inclusive = false) },
            )
        }
    }
}
