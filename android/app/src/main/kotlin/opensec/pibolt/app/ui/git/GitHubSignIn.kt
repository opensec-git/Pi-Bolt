package opensec.pibolt.app.ui.git

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor

/**
 * Settings > GitHub. Signed out: "Sign in with GitHub" (device flow, when this build has an OAuth client id) and "Use a
 * personal access token". Signed in: the account, its scopes and Sign out. The token itself is never shown again.
 */
@Composable
fun GitHubSection(app: PiBoltApp) {
    val colors = Pi.colors
    val type = Pi.type
    val github = app.github
    val state by github.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<TokenKind?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    when (val s = state) {
        is GitHubState.SignedIn -> {
            val a = s.account
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name?.let { "$it (@${a.login})" } ?: "@${a.login}", style = type.bodyStrong, color = colors.ink1)
                    val how = if (a.kind == TokenKind.Device) "Signed in with GitHub" else "Personal access token"
                    val scopes = if (a.scopes.isEmpty()) "fine-grained or no scopes" else a.scopes.joinToString(", ")
                    Text("$how · $scopes", style = type.meta, color = colors.ink3)
                }
            }
            Text(
                "git push, pull and clone to github.com use this account, in the Changes tab and in Pi's own git commands (new sessions).",
                style = type.meta,
                color = colors.ink3,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SecondaryButton("Sign out", onClick = { signingOut = true }, modifier = Modifier.fillMaxWidth())
        }
        GitHubState.SignedOut -> {
            Text(
                "Sign in to clone private repositories, push, pull and open pull requests from this phone.",
                style = type.body,
                color = colors.ink2,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (github.deviceFlowAvailable) PrimaryButton("Sign in with GitHub", onClick = { sheet = TokenKind.Device }, modifier = Modifier.fillMaxWidth())
                SecondaryButton("Use a personal access token", onClick = { sheet = TokenKind.Personal }, icon = Icons.Rounded.Key, modifier = Modifier.fillMaxWidth())
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
                }
            } else {
                step = s
            }
        }
    }
    PiSheet(onDismiss = onDismiss, state = sheetState) {
        SheetHeader("Sign in with GitHub")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = step) {
                DeviceFlowState.Requesting, is DeviceFlowState.Authorized -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    QuadSpinner(QuadMode.Connect, size = 20.dp, color = colors.ink2)
                    Spacer(Modifier.width(12.dp))
                    Text("Asking GitHub for a code…", style = type.body, color = colors.ink2)
                }
                is DeviceFlowState.ShowCode -> {
                    Text("Open the GitHub page and enter this code:", style = type.body, color = colors.ink2, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    val shape = RoundedCornerShape(Radius.md)
                    Box(
                        Modifier
                            .background(colors.surface1, shape)
                            .border(1.dp, colors.lineStrong, shape)
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                            .semantics { contentDescription = "Code ${s.userCode.toCharArray().joinToString(" ")}" },
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
                        Text("Waiting for you to approve on GitHub", style = type.meta, color = colors.ink3)
                    }
                }
                is DeviceFlowState.Failed -> {
                    Text(s.message, style = type.body, color = colors.danger, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
                    if (s.canRetry) PrimaryButton("Try again", onClick = { attempt++ }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            TextAction("Cancel", onClick = { close(onDismiss) }, color = colors.ink2, modifier = Modifier.height(48.dp))
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
    PiSheet(onDismiss = onDismiss, state = sheetState) {
        SheetHeader(
            "Personal access token",
            subtitle = "Create a classic token on GitHub with the repo scope (private repositories, push) and workflow (to push CI files), or a fine-grained token with Contents and Pull requests read and write.",
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                "Create a token on GitHub",
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, TOKEN_URL.toUri())) } },
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                modifier = Modifier.fillMaxWidth(),
            )
            val shape = RoundedCornerShape(Radius.md)
            BasicTextField(
                value = token,
                onValueChange = {
                    token = it
                    error = null
                },
                singleLine = true,
                textStyle = type.mono.copy(color = colors.ink1),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "GitHub token" },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface1, shape)
                            .border(1.dp, if (error != null) colors.danger.copy(alpha = 0.7f) else colors.lineStrong, shape)
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        if (token.isEmpty()) Text("ghp_… or github_pat_…", style = type.mono, color = colors.ink3)
                        inner()
                    }
                },
            )
            error?.let { Text(it, style = type.meta, color = colors.danger) }
            Text("Stored encrypted with a key in this phone's Keystore. It is never shown again or written to logs.", style = type.meta, color = colors.ink3)
            PrimaryButton(
                if (checking) "Checking…" else "Save",
                onClick = {
                    checking = true
                    scope.launch {
                        try {
                            app.github.signIn(token, TokenKind.Personal)
                            token = ""
                            setIdentityIfMissing(app)
                            close(onDismiss)
                        } catch (e: GitHubException) {
                            error = e.message
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = token.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Pi-Bolt%20on%20Android"
