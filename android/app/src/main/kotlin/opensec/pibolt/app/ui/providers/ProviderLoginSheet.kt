package opensec.pibolt.app.ui.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.EngineProvider
import opensec.pibolt.app.engine.ProviderLogin
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.session.DialogSheet
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/**
 * Signs in to [provider] with its own login flow ([ProviderLogin], run by the engine on this phone). Its prompts are
 * Pi's extension dialogs ([DialogSheet]); a sign-in page opens in a Custom Tab, and the provider's redirect reaches the
 * engine here, which saves the credential to Pi's auth.json. Once signed in the app comes back to the front.
 * [onDone] runs after a sign-in that worked; [onDismiss] after a cancel or a failure the user closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderLoginSheet(provider: EngineProvider, method: String = "oauth", onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PiBoltApp
    val login = remember(provider.id, method) { app.engine.login(provider.id, method) }
    if (login == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val state by login.state.collectAsStateWithLifecycle()
    // Leaving the sheet before the flow ends stops it: nothing waits in an engine nobody shows.
    DisposableEffect(login) { onDispose { if (!login.state.value.finished) login.cancel() } }
    var opened by remember { mutableStateOf<String?>(null) }
    val step = state.step
    // A sign-in page opens once by itself; "Open again" reopens it.
    LaunchedEffect(step) {
        val url = (step as? ProviderLogin.Step.OpenUrl)?.url ?: return@LaunchedEffect
        if (opened != url) {
            opened = url
            openCustomTab(context, url)
        }
    }
    // Watched apart from [state], which stops while the browser tab is in front: the end of the flow brings the app
    // back from there.
    LaunchedEffect(login) {
        val end = withContext(Dispatchers.Default) {
            login.state.first { it.finished }.also { if (it.succeeded && opened != null) bringToFront(context) }
        }
        if (end.succeeded) {
            app.providers.refresh()
            onDone()
        }
    }
    state.dialog?.let { dialog ->
        // The flow's question, in Pi's own dialog sheet; answering returns to this sheet.
        val shownAt = remember(dialog.id) { SystemClock.uptimeMillis() }
        DialogSheet(
            dialog = dialog,
            shownAt = shownAt,
            onValue = { login.answer(dialog.id, it) },
            onConfirm = { login.answer(dialog.id, it.toString()) },
            onCancel = { login.decline(dialog.id) },
            onHide = { login.decline(dialog.id) },
        )
        return
    }

    val colors = Pi.colors
    val type = Pi.type
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheet)
    PiSheet(onDismiss = onDismiss, state = sheet) {
        SheetHeader(
            provider.name,
            subtitle = provider.loginLabel ?: provider.loginName ?: "Sign in",
            leading = { LogoMark(ProviderCatalog.brand(provider.id), size = IconSize.lg, tint = colors.ink1) },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
            when {
                state.finished && state.error != null -> {
                    Text(
                        state.error ?: "",
                        style = type.body,
                        color = if (state.error == ProviderLogin.CANCELLED) colors.ink2 else colors.danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(Space.lg))
                    PrimaryButton("Close", onClick = { close(onDismiss) }, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        QuadSpinner(QuadMode.Wait, size = 16.dp, color = colors.ink2)
                        Spacer(Modifier.width(Space.sm))
                        Text(state.progress ?: waitingText(step), style = type.body, color = colors.ink2)
                    }
                    Spacer(Modifier.height(Space.md))
                    when (step) {
                        is ProviderLogin.Step.OpenUrl -> {
                            step.instructions?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = type.meta, color = colors.ink3)
                                Spacer(Modifier.height(Space.md))
                            }
                            SecondaryButton("Open the sign-in page again", onClick = { openCustomTab(context, step.url) }, modifier = Modifier.fillMaxWidth())
                        }
                        is ProviderLogin.Step.DeviceCode -> {
                            Text("Enter this code on the sign-in page:", style = type.meta, color = colors.ink3)
                            Spacer(Modifier.height(Space.xs))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(step.code, style = type.title.copy(fontFamily = FontFamily.Monospace), color = colors.ink1, modifier = Modifier.weight(1f))
                                CopyButton({ step.code }, description = "Copy the code")
                            }
                            Spacer(Modifier.height(Space.md))
                            PrimaryButton("Open the sign-in page", onClick = { openCustomTab(context, step.url) }, modifier = Modifier.fillMaxWidth())
                        }
                        is ProviderLogin.Step.Info -> {
                            Text(step.message, style = type.meta, color = colors.ink3)
                            step.links.firstOrNull()?.let { link ->
                                Spacer(Modifier.height(Space.md))
                                SecondaryButton("Open link", onClick = { openCustomTab(context, link) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        null -> Unit
                    }
                    Spacer(Modifier.height(Space.sm))
                    GhostButton("Cancel", onClick = { login.cancel() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun waitingText(step: ProviderLogin.Step?): String = when (step) {
    is ProviderLogin.Step.OpenUrl -> "Finish signing in in the browser"
    is ProviderLogin.Step.DeviceCode -> "Waiting for the code to be entered"
    else -> "Starting the sign-in"
}

/**
 * Opens [url] in a Custom Tab of the default browser (the Custom Tabs intent extra; a browser without them opens it
 * normally). The tab joins the app's task, so [bringToFront] closes it again.
 */
private fun openCustomTab(context: Context, url: String) {
    if (!ProviderLogin.isWebUrl(url)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No browser at all: the instructions on the sheet stay.
    }
}

/**
 * MainActivity is singleTask: starting it clears the browser tab above it in its task. Allowed from the background:
 * the app's own task is the one in front.
 */
private fun bringToFront(context: Context) {
    runCatching {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

/** androidx.browser's CustomTabsIntent.EXTRA_SESSION, without the library. */
private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
