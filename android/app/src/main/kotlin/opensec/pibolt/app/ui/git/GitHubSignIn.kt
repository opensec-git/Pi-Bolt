package opensec.pibolt.app.ui.git

import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.SecretField
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Key
import opensec.pibolt.app.ui.components.GroupEnd
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.InsetGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.git.GitRepo
import opensec.pibolt.app.git.github.DeviceFlowState
import opensec.pibolt.app.git.github.GitHub
import opensec.pibolt.app.git.github.GitHubException
import opensec.pibolt.app.git.github.GitHubState
import opensec.pibolt.app.git.github.TokenKind
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * Settings > GitHub, as a group. Signed out: "Sign in with GitHub" (device flow, when this build has an OAuth client
 * id) and "Use a personal access token". Signed in: the account with how and which scopes, and Sign out (confirmed).
 * The token itself is never shown again.
 */
@Composable
fun GitHubSection(app: PiBoltApp, header: String = "GitHub") {
    val colors = Pi.colors
    val github = app.github
    val state by github.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<TokenKind?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    when (val s = state) {
        is GitHubState.SignedIn -> {
            val a = s.account
            val how = if (a.kind == TokenKind.Device) "Signed in with GitHub" else "Personal access token"
            val scopes = if (a.scopes.isEmpty()) "fine-grained or no scopes" else a.scopes.joinToString(", ")
            InsetGroup(header = header) {
                GroupRow(a.name?.let { "$it (@${a.login})" } ?: "@${a.login}", icon = Icons.Rounded.AccountCircle, meta = "$how · $scopes")
                GroupRow("Sign out", icon = Icons.AutoMirrored.Rounded.Logout, iconTint = colors.danger, titleColor = colors.danger, onClick = { signingOut = true }, end = GroupEnd.None, divider = false)
            }
        }
        GitHubState.SignedOut -> {
            InsetGroup(header = header) {
                if (github.deviceFlowAvailable) GroupRow("Sign in with GitHub", icon = Icons.Rounded.Code, onClick = { sheet = TokenKind.Device })
                GroupRow("Use a personal access token", icon = Icons.Rounded.Key, onClick = { sheet = TokenKind.Personal }, divider = false)
            }
        }
    }
    when (sheet) {
        TokenKind.Device -> DeviceSignInSheet(app, onDismiss = { sheet = null })
        TokenKind.Personal -> TokenSheet(app, onDismiss = { sheet = null })
        null -> Unit
    }
    if (signingOut) {
        ConfirmSheet(
            title = "Sign out of GitHub?",
            message = "The token is deleted from this phone. It stays valid on GitHub until you revoke it there.",
            confirmLabel = "Sign out",
            onConfirm = {
                signingOut = false
                github.signOut()
            },
            onDismiss = { signingOut = false },
        )
    }
}

/** The sign-in sheet of Settings' account card: the device flow when this build has it, else the token sheet. */
@Composable
fun GitHubSignInSheet(app: PiBoltApp, onDismiss: () -> Unit) {
    if (app.github.deviceFlowAvailable) DeviceSignInSheet(app, onDismiss) else TokenSheet(app, onDismiss)
}

/** After sign-in: commits get the account's name and no-reply email unless git already has an identity. */
private suspend fun setIdentityIfMissing(app: PiBoltApp) {
    val a = app.github.account ?: return
    runCatching {
        val repo = GitRepo(app.git, app.engine.projectsDir.apply { mkdirs() })
        val (name, email) = repo.identity()
        if (name == null || email == null) repo.setGlobalIdentity(a.name ?: a.login, a.noreplyEmail)
    }
}

/** Device flow: the code to type at github.com/login/device, polling meanwhile; closes itself once signed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSignInSheet(app: PiBoltApp, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState)
    var step by remember { mutableStateOf<DeviceFlowState>(DeviceFlowState.Requesting) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        app.github.deviceFlow().run(GitHub.SCOPES).collect { s ->
            if (s is DeviceFlowState.Authorized) {
                try {
                    app.github.signIn(s.token, TokenKind.Device)
                    setIdentityIfMissing(app)
                    close(onDismiss)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubException) {
                    step = DeviceFlowState.Failed(e.message ?: "Sign-in failed", canRetry = true)
                } catch (e: Exception) {
                    // The Keystore refused to seal the token: an error in the sheet, not a crash of the app.
                    step = DeviceFlowState.Failed("Could not store the token on this phone", canRetry = true)
                }
            } else {
                step = s
            }
        }
    }
    PiSheet(onDismiss = onDismiss, state = sheetState) {
        SheetHeader("Sign in with GitHub")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = step) {
                DeviceFlowState.Requesting, is DeviceFlowState.Authorized -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    QuadSpinner(QuadMode.Connect, size = 20.dp, color = colors.ink2)
                    Spacer(Modifier.width(12.dp))
                    Text("Asking GitHub for a code…", style = type.body, color = colors.ink2)
                }
                is DeviceFlowState.ShowCode -> {
                    Text("Enter this code on GitHub.", style = type.body, color = colors.ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    val shape = RoundedCornerShape(Radius.tile)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface2, shape)
                            .border(1.dp, colors.line, shape)
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                            .semantics { contentDescription = "Code ${s.userCode.toCharArray().joinToString(" ")}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(s.userCode, style = type.display.merge(Pi.type.mono.copy(fontSize = type.display.fontSize)), color = colors.ink1)
                    }
                    CopyButton(text = { s.userCode }, description = "Copy the code")
                    PrimaryButton(
                        "Open github.com/login/device",
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, s.verificationUri.toUri())) } },
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuadSpinner(QuadMode.Wait, size = 16.dp, color = colors.ink3)
                        Spacer(Modifier.width(10.dp))
                        Text("Waiting for your approval", style = type.meta, color = colors.ink3)
                    }
                }
                is DeviceFlowState.Failed -> {
                    Text(s.message, style = type.body, color = colors.danger, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
                    if (s.canRetry) PrimaryButton("Try again", onClick = { attempt++ }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Personal access token: paste it, it is checked against GitHub and stored sealed; the field is cleared after. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TokenSheet(app: PiBoltApp, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState)
    // Not rememberSaveable: the token never goes into saved instance state.
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (token.isBlank() || checking) return
        checking = true
        scope.launch {
            try {
                app.github.signIn(token, TokenKind.Personal)
                token = ""
                setIdentityIfMissing(app)
                close(onDismiss)
            } catch (e: GitHubException) {
                error = e.message ?: "GitHub did not accept the token"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Could not store the token on this phone"
            } finally {
                checking = false
            }
        }
    }
    PiSheet(onDismiss = onDismiss, state = sheetState) {
        SheetHeader(
            "Personal access token",
            subtitle = "Classic token: the repo and workflow scopes. Fine-grained: Contents and Pull requests, read and write.",
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                "Create a token on GitHub",
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, TOKEN_URL.toUri())) } },
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.fillMaxWidth(),
            )
            var shown by remember { mutableStateOf(false) }
            SecretField(
                value = token,
                onValueChange = {
                    token = it
                    error = null
                },
                shown = shown,
                onToggleShown = { shown = !shown },
                placeholder = "ghp_… or github_pat_…",
                label = "GitHub token",
                isError = error != null,
                onDone = ::save,
            )
            FieldHint(error ?: "Encrypted on this phone. Never shown again or logged.", isError = error != null)
            PrimaryButton(
                if (checking) "Checking…" else "Save",
                onClick = ::save,
                enabled = token.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Pi-Bolt%20on%20Android"
