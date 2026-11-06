package opensec.pibolt.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.LocalNavScope
import opensec.pibolt.app.ui.components.LocalSharedScope
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.hosts.HostsScreen
import opensec.pibolt.app.ui.scan.ScanScreen
import opensec.pibolt.app.ui.session.SessionScreen
import opensec.pibolt.app.ui.settings.SettingsScreen
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.protocol.PairingPayload

object Routes {
    const val HOSTS = "hosts"
    const val SCAN = "scan"
    const val SESSION = "session"
    const val SETTINGS = "settings"
}

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

@Composable
fun AppNav(app: PiBoltApp, pendingLink: String?, consumeLink: () -> Unit) {
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
        PairConfirmSheet(
            payload = payload,
            known = known,
            endsSession = live?.hostName?.collectAsState()?.value,
            onConfirm = {
                val text = payload.encode()
                linkToConfirm = null
                linkError = usePairing(text)
            },
            onCancel = { linkToConfirm = null },
        )
    }

    val reduced = Pi.reduceMotion
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }
    val enter: Enter = {
        if (reduced) {
            fadeIn(tween(120))
        } else if (initialState.destination.route == Routes.HOSTS && targetState.destination.route == Routes.SESSION) {
            // The host card's shared bounds carry the motion.
            fadeIn(PiMotion.enter(PiMotion.SHEET))
        } else {
            slideInHorizontally(PiMotion.enter(PiMotion.SHEET)) { shift } + fadeIn(PiMotion.enter(PiMotion.SHEET))
        }
    }
    val exit: Exit = {
        if (reduced) {
            fadeOut(tween(90))
        } else {
            slideOutHorizontally(PiMotion.leave(PiMotion.SHEET)) { -shift } + fadeOut(PiMotion.leave(PiMotion.SHEET))
        }
    }
    val popEnter: Enter = {
        if (reduced) fadeIn(tween(120)) else slideInHorizontally(PiMotion.enter(PiMotion.SHEET)) { -shift } + fadeIn(PiMotion.enter(PiMotion.SHEET))
    }
    // Predictive back seeks this with the gesture: the screen shrinks to 0.9 as it is pulled away.
    val popExit: Exit = {
        if (reduced) {
            fadeOut(tween(90))
        } else {
            scaleOut(tween(PiMotion.SHEET, easing = PiMotion.InPlace), targetScale = 0.9f) + fadeOut(PiMotion.leave(PiMotion.SHEET))
        }
    }

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
            NavHost(
                nav,
                startDestination = Routes.HOSTS,
                modifier = Modifier.background(Pi.colors.canvas),
                enterTransition = enter,
                exitTransition = exit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                composable(Routes.HOSTS) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        HostsScreen(
                            app = app,
                            externalError = linkError,
                            onErrorShown = { linkError = null },
                            onOpen = { host ->
                                app.open(host)
                                nav.navigate(Routes.SESSION) { launchSingleTop = true }
                            },
                            onScan = { nav.navigate(Routes.SCAN) { launchSingleTop = true } },
                            onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                            onPairingText = { usePairing(it) },
                            onResume = { nav.navigate(Routes.SESSION) { launchSingleTop = true } },
                        )
                    }
                }
                composable(Routes.SCAN) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        ScanScreen(
                            onBack = { nav.popBackStack() },
                            onCode = { usePairing(it) },
                        )
                    }
                }
                composable(Routes.SESSION) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        SessionScreen(
                            app = app,
                            onBack = { nav.popBackStack(Routes.HOSTS, inclusive = false) },
                            onForgotten = { nav.popBackStack(Routes.HOSTS, inclusive = false) },
                        )
                    }
                }
                composable(Routes.SETTINGS) {
                    CompositionLocalProvider(LocalNavScope provides this) {
                        SettingsScreen(app = app, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

/**
 * Confirmation for a pairing link opened from outside the app. Nothing is paired or replaced until Pair/Connect;
 * dismissing the sheet cancels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairConfirmSheet(payload: PairingPayload, known: Boolean, endsSession: String?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onCancel, state = state) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(if (known) "Connect to ${payload.hostName}?" else "Pair with ${payload.hostName}?", style = type.title, color = colors.ink1)
            Spacer(Modifier.height(8.dp))
            Text(
                "A link opened Pi-Bolt to " + (if (known) "connect to a paired computer." else "pair this phone with a computer.") +
                    " Only continue if you just ran /\u2060remote-control on this computer.",
                style = type.body,
                color = colors.ink2,
            )
            Spacer(Modifier.height(14.dp))
            val shape = RoundedCornerShape(Radius.md)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface1, shape)
                    .border(1.dp, colors.line, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (endpoint in payload.endpoints) Text(endpoint, style = type.monoMeta, color = colors.ink2, maxLines = 1)
            }
            if (endsSession != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("This ends the current session with $endsSession.", style = type.body, color = colors.warning)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Cancel", onClick = { close(onCancel) }, modifier = Modifier.weight(1f))
                PrimaryButton(if (known) "Connect" else "Pair", onClick = {
                    haptics.confirm()
                    close(onConfirm)
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
