package opensec.pibolt.app.ui.projects.transfer

import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.projects.Projects
import opensec.pibolt.app.ui.session.ImageSizing
import java.io.File

/**
 * Content shared into Pi-Bolt from another app ([pending], from MainActivity): the [ShareInSheet], then the choice.
 * Images and text go to the composer of the project's session (the live one, else its latest, else a new one) and
 * that session opens ([onSession]); files are copied into the project's root, a zip becomes a new project, and the
 * project opens ([onProject]). A package link goes to its install confirmation ([onInstallPackage]). Copies stream
 * with a [TransferBar] and can be cancelled.
 */
@Composable
fun ShareInHost(app: PiBoltApp, pending: SharedContent?, onConsumed: () -> Unit, onProject: (String) -> Unit, onSession: (String) -> Unit, onInstallPackage: (String) -> Unit) {
    val context = LocalContext.current
    val engine = app.engine
    val store = remember { ProjectStore.get(app) }
    val projects = remember(engine) { Projects(engine, store) }
    val runner = remember { TransferRunner(app.appScope) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<SharedContent?>(null) }
    LaunchedEffect(pending) {
        if (pending != null) {
            shown = pending
            onConsumed()
        }
    }

    fun fail(message: String) {
        scope.launch { snackbar.show(message, SnackKind.Error) }
    }

    fun run(content: SharedContent, choice: ShareChoice) {
        val uris = content.items.map { it.uri.toUri() }
        when (choice.action) {
            ShareAction.AttachImages, ShareAction.InsertText -> {
                val name = choice.project ?: return
                val text = if (choice.action == ShareAction.InsertText) ShareRouting.composerText(content) else null
                val images = if (choice.action == ShareAction.AttachImages) uris else emptyList()
                scope.launch {
                    val live = LocalSessions.get(name)
                        ?: run {
                            val dir = File(engine.projectsDir, name)
                            val latest = projects.sessions(dir).firstOrNull()?.file
                            LocalSessions.open(engine, store, dir, sessionFile = latest, fresh = latest == null).getOrElse {
                                fail(it.message ?: "Could not open the session")
                                return@launch
                            }
                        }
                    live.drop(text, images)
                    onSession(live.key)
                }
            }
            ShareAction.InstallPackage -> ShareRouting.packageSource(content)?.let(onInstallPackage)
            ShareAction.ImportFiles -> {
                val name = choice.project ?: return
                runner.run(
                    if (uris.size == 1) "Importing 1 file" else "Importing ${uris.size} files",
                    { progress -> SafTransfer.importDocuments(context, uris, File(engine.projectsDir, name), progress) },
                    onDone = { onProject(name) },
                    onError = ::fail,
                )
            }
            ShareAction.NewProjectFromZip -> {
                val uri = uris.singleOrNull() ?: return
                runner.run(
                    "Importing the zip",
                    { progress -> SafTransfer.importZipAsProject(context, uri, engine.projectsDir, progress) },
                    onDone = { project -> onProject(project) },
                    onError = ::fail,
                )
            }
        }
    }

    shown?.let { content ->
        ShareInSheet(
            content = content,
            maxImages = ImageSizing.MAX_IMAGES,
            projects = { projects.list() },
            onChoose = { choice ->
                shown = null
                run(content, choice)
            },
            onDismiss = { shown = null },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.navigationBarsPadding()) {
            TransferBar(runner)
            PiSnackHost(snackbar)
        }
    }
}
