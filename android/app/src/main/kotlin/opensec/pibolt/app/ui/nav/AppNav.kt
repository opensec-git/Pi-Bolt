package opensec.pibolt.app.ui.nav

import opensec.pibolt.app.ui.settings.BackgroundRunPrompt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.Notice
import opensec.pibolt.app.ui.components.PiCard
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.SnackKind
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
import opensec.pibolt.app.ui.chats.ChatSessionScreen
import opensec.pibolt.app.ui.chats.ChatsScreen
import opensec.pibolt.app.ui.home.HomeScreen
import opensec.pibolt.app.ui.packages.InstallLinkHost
import opensec.pibolt.app.ui.projects.transfer.ShareInHost
import opensec.pibolt.app.ui.projects.transfer.SharedContent
import opensec.pibolt.app.ui.projects.ProjectScreen
import opensec.pibolt.app.ui.scan.ScanScreen
import opensec.pibolt.app.ui.secrets.SecretsScreen
import opensec.pibolt.app.ui.session.SessionScreen
import opensec.pibolt.app.ui.session.features2.SessionAlerts
import opensec.pibolt.app.ui.session.subagent.LocalSubagentNav
import opensec.pibolt.app.ui.session.subagent.SubagentNav
import opensec.pibolt.app.ui.session.subagent.SubagentOverlay
import opensec.pibolt.app.ui.settings.ProvidersScreen
import opensec.pibolt.app.ui.settings.RuntimesScreen
import opensec.pibolt.app.ui.settings.SettingsScreen
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.terminal.PreviewScreen
import opensec.pibolt.app.ui.terminal.TerminalScreen
import opensec.pibolt.app.ui.update.UpdateSheets
import opensec.pibolt.protocol.PairingPayload
import java.io.File

object Routes {
    const val HOME = "home"
    const val PROJECT = "project/{name}"
    const val LOCAL_SESSION = "local/{name}"
    const val SCAN = "scan"
    const val SESSION = "session"
    const val SETTINGS = "settings"
    const val TERMINAL = "terminal/{name}"
    const val SECRETS = "secrets/{name}"
    const val PREVIEW = "preview/{port}"
    const val RUNTIMES = "runtimes"
    const val PROVIDERS = "providers"

    /** Plain chats (ui/chats): the list, and the live chat. */
    const val CHATS = "chats"
    const val CHAT = "chat"

    fun project(name: String) = "project/" + Uri.encode(name)

    fun localSession(name: String) = "local/" + Uri.encode(name)

    fun terminal(name: String) = "terminal/" + Uri.encode(name)

    fun secrets(name: String) = "secrets/" + Uri.encode(name)

    fun preview(port: Int) = "preview/$port"
}

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

@Composable
fun AppNav(
    app: PiBoltApp,
    pendingLink: String?,
    consumeLink: () -> Unit,
    pendingShare: SharedContent? = null,
    consumeShare: () -> Unit = {},
    pendingInstall: String? = null,
    consumeInstall: () -> Unit = {},
) {
    val nav = rememberNavController()
    // A package link shared into the app (the share sheet's "Install package"), like [pendingInstall].
    var sharedInstall by remember { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
    // A link from outside the app (a browser, a chat) pairs only after the user confirms it in the app.
    var linkToConfirm by remember { mutableStateOf<PairingPayload?>(null) }
    val liveSession by app.remote.collectAsState()

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
            popUpTo(Routes.HOME)
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
            fadeIn(tween(PiMotion.FADE))
        } else if (initialState.destination.route == Routes.HOME && targetState.destination.route in setOf(Routes.SESSION, Routes.PROJECT)) {
            // The host or project card's shared bounds carry the motion.
            fadeIn(PiMotion.enter(PiMotion.SHEET))
        } else {
            slideInHorizontally(PiMotion.enter(PiMotion.SHEET)) { shift } + fadeIn(PiMotion.enter(PiMotion.SHEET))
        }
    }
    val exit: Exit = {
        if (reduced) {
            fadeOut(tween(PiMotion.exit(PiMotion.FADE)))
        } else {
            slideOutHorizontally(PiMotion.leave(PiMotion.SHEET)) { -shift } + fadeOut(PiMotion.leave(PiMotion.SHEET))
        }
    }
    val popEnter: Enter = {
        if (reduced) fadeIn(tween(PiMotion.FADE)) else slideInHorizontally(PiMotion.enter(PiMotion.SHEET)) { -shift } + fadeIn(PiMotion.enter(PiMotion.SHEET))
    }
    // Predictive back seeks this with the gesture: the screen shrinks to 0.9 as it is pulled away.
    val popExit: Exit = {
        if (reduced) {
            fadeOut(tween(PiMotion.exit(PiMotion.FADE)))
        } else {
            scaleOut(tween(PiMotion.SHEET, easing = PiMotion.InPlace), targetScale = 0.9f) + fadeOut(PiMotion.leave(PiMotion.SHEET))
        }
    }

    // A subagent's transcript opens over the session screen (which stays as it was) from its card in the transcript.
    val subagents = remember { SubagentNav() }
    Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this, LocalSubagentNav provides subagents) {
                NavHost(
                    nav,
                    startDestination = Routes.HOME,
                    modifier = Modifier.background(Pi.colors.canvas),
                    enterTransition = enter,
                    exitTransition = exit,
                    popEnterTransition = popEnter,
                    popExitTransition = popExit,
                ) {
                    composable(Routes.HOME) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            HomeScreen(
                                app = app,
                                externalError = linkError,
                                onErrorShown = { linkError = null },
                                onOpenProject = { nav.navigate(Routes.project(it.name)) { launchSingleTop = true } },
                                onOpenLocalSession = { nav.navigate(Routes.localSession(it)) { launchSingleTop = true } },
                                onOpenHost = { host ->
                                    app.open(host)
                                    nav.navigate(Routes.SESSION) { launchSingleTop = true }
                                },
                                onResumeHost = { nav.navigate(Routes.SESSION) { launchSingleTop = true } },
                                onScan = { nav.navigate(Routes.SCAN) { launchSingleTop = true } },
                                onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                                onPairingText = { usePairing(it) },
                                onOpenChats = { nav.navigate(Routes.CHATS) { launchSingleTop = true } },
                                onOpenChat = { nav.navigate(Routes.CHAT) { launchSingleTop = true } },
                            )
                        }
                    }
                    composable(Routes.PROJECT, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                        val name = entry.arguments?.getString("name").orEmpty()
                        CompositionLocalProvider(LocalNavScope provides this) {
                            ProjectScreen(
                                app = app,
                                name = name,
                                onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
                                onOpenSession = { key -> nav.navigate(Routes.localSession(key)) { launchSingleTop = true } },
                                onTerminal = { nav.navigate(Routes.terminal(name)) { launchSingleTop = true } },
                                onSecrets = { nav.navigate(Routes.secrets(name)) { launchSingleTop = true } },
                                onPreview = { nav.navigate(Routes.preview(it)) { launchSingleTop = true } },
                                onRuntimes = { nav.navigate(Routes.RUNTIMES) { launchSingleTop = true } },
                            )
                        }
                    }
                    composable(Routes.LOCAL_SESSION, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                        val name = entry.arguments?.getString("name").orEmpty()
                        CompositionLocalProvider(LocalNavScope provides this) {
                            SessionScreen(
                                app = app,
                                onBack = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) },
                                onForgotten = { nav.popBackStack(Routes.HOME, inclusive = false) },
                                local = name,
                                // The switcher: the other session takes this screen's place; both keep running.
                                onOpenSession = { other ->
                                    nav.navigate(Routes.localSession(other)) {
                                        popUpTo(Routes.LOCAL_SESSION) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onPreview = { nav.navigate(Routes.preview(it)) { launchSingleTop = true } },
                            )
                        }
                    }
                    composable(Routes.TERMINAL, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                        val name = entry.arguments?.getString("name").orEmpty()
                        TerminalScreen(
                            app = app,
                            project = File(app.engine.projectsDir, name),
                            onBack = { nav.popBackStack() },
                            onPreview = { nav.navigate(Routes.preview(it)) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.SECRETS, arguments = listOf(navArgument("name") { type = NavType.StringType })) { entry ->
                        SecretsScreen(app = app, project = File(app.engine.projectsDir, entry.arguments?.getString("name").orEmpty()), onBack = { nav.popBackStack() })
                    }
                    composable(Routes.PREVIEW, arguments = listOf(navArgument("port") { type = NavType.IntType })) { entry ->
                        PreviewScreen(port = entry.arguments?.getInt("port") ?: 0, onBack = { nav.popBackStack() })
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
                                onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
                                onForgotten = { nav.popBackStack(Routes.HOME, inclusive = false) },
                                onOpenSession = { other -> nav.navigate(Routes.localSession(other)) { launchSingleTop = true } },
                            )
                        }
                    }
                    composable(Routes.CHATS) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            ChatsScreen(
                                app = app,
                                onBack = { nav.popBackStack() },
                                onOpenChat = { nav.navigate(Routes.CHAT) { launchSingleTop = true } },
                            )
                        }
                    }
                    composable(Routes.CHAT) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            ChatSessionScreen(
                                app = app,
                                onBack = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) },
                                onOpenSession = { other -> nav.navigate(Routes.localSession(other)) { launchSingleTop = true } },
                                // Back to the Chats list when the chat was opened from it; otherwise the list takes the
                                // chat's place, so Back from it goes where the chat would have.
                                onOpenChats = {
                                    if (!nav.popBackStack(Routes.CHATS, inclusive = false)) {
                                        nav.navigate(Routes.CHATS) {
                                            popUpTo(Routes.CHAT) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                            )
                        }
                    }
                    composable(Routes.SETTINGS) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            SettingsScreen(
                                app = app,
                                onBack = { nav.popBackStack() },
                                onRuntimes = { nav.navigate(Routes.RUNTIMES) { launchSingleTop = true } },
                                onProviders = { nav.navigate(Routes.PROVIDERS) { launchSingleTop = true } },
                                onScan = { nav.navigate(Routes.SCAN) { launchSingleTop = true } },
                                onOpenHost = { host ->
                                    app.open(host)
                                    nav.navigate(Routes.SESSION) { launchSingleTop = true }
                                },
                                onResumeHost = { nav.navigate(Routes.SESSION) { launchSingleTop = true } },
                                onPairingText = { usePairing(it) },
                            )
                        }
                    }
                    composable(Routes.RUNTIMES) {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            RuntimesScreen(app = app, onBack = { nav.popBackStack() })
                        }
                    }
                    composable(Routes.PROVIDERS) {
                        ProvidersScreen(app = app, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
        SubagentOverlay(app, subagents)
    }
    // A tapped session notification opens that session.
    SessionAlerts.Navigate(
        app,
        onLocal = { nav.navigate(Routes.localSession(it)) { launchSingleTop = true } },
        onRemote = { nav.navigate(Routes.SESSION) { launchSingleTop = true } },
        onChat = { nav.navigate(Routes.CHAT) { launchSingleTop = true } },
    )
    // Content shared from another app: a sheet picks what to do and where (ui/projects/transfer).
    ShareInHost(
        app,
        pendingShare,
        onConsumed = consumeShare,
        onProject = { nav.navigate(Routes.project(it)) { launchSingleTop = true } },
        onSession = { nav.navigate(Routes.localSession(it)) { launchSingleTop = true } },
        onInstallPackage = { sharedInstall = it },
    )
    // A package link (pibolt://install, pi.dev, or shared): its details and Install, never installed without the tap.
    InstallLinkHost(app, pendingInstall ?: sharedInstall, onConsumed = {
        consumeInstall()
        sharedInstall = null
    })

    UpdateSheets(app)
    BackgroundRunPrompt()
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
        SheetHeader(
            if (known) "Connect to ${payload.hostName}?" else "Pair with ${payload.hostName}?",
            subtitle = "Only continue if you just ran /\u2060remote-control on this computer.",
            closable = false,
        )
        Column(Modifier.padding(horizontal = Space.lg)) {
            Spacer(Modifier.height(Space.sm))
            Text(if (payload.endpoints.size == 1) "Address" else "Addresses", style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(start = Space.lg, bottom = Space.sm))
            PiCard(radius = Radius.tile, fill = colors.surface2, padding = PaddingValues(horizontal = Space.lg, vertical = Space.md)) {
                for (endpoint in payload.endpoints) Text(endpoint, style = type.monoMeta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
            }
            if (endsSession != null) {
                Spacer(Modifier.height(Space.md))
                Notice("This ends the current session with $endsSession.", kind = SnackKind.Warning)
            }
            Spacer(Modifier.height(Space.xl))
            ButtonRow(Modifier.padding(horizontal = Space.xs)) {
                SecondaryButton("Cancel", onClick = { close(onCancel) })
                PrimaryButton(if (known) "Connect" else "Pair", onClick = {
                    haptics.confirm()
                    close(onConfirm)
                })
            }
            Spacer(Modifier.height(Space.sm))
        }
    }
}
