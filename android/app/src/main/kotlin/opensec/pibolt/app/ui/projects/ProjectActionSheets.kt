package opensec.pibolt.app.ui.projects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.terminal.Terminals
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.session.TextPromptSheet
import java.io.File

/**
 * Rename (the shown title only: the folder keeps its name so Pi still finds its sessions) and Delete (folder, files
 * and saved sessions, after a confirmation that counts what goes). [onDeleted] runs after the folder is gone; the
 * delete runs in [scope], which must outlive the screen (the app scope).
 */
@Composable
fun ProjectActionSheets(action: ProjectAction?, projects: Projects, store: ProjectStore, scope: CoroutineScope, onDone: () -> Unit, onDeleted: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    when (action) {
        null -> Unit
        is ProjectAction.Rename -> TextPromptSheet(
            title = "Rename project",
            message = "The folder stays projects/${action.entry.name}.",
            initial = action.entry.title,
            placeholder = action.entry.name,
            confirm = "Rename",
            onConfirm = {
                store.setTitle(action.entry.name, it)
                onDone()
            },
            onDismiss = onDone,
        )
        is ProjectAction.Delete -> {
            val entry = action.entry
            val files by produceState<Int?>(null, entry.name) { value = projects.fileCount(entry.name) }
            val what = buildList {
                add(files?.let { if (it == 1) "1 file" else "$it files" } ?: "its files")
                if (entry.sessionCount > 0) add(if (entry.sessionCount == 1) "1 saved session" else "${entry.sessionCount} saved sessions")
            }.joinToString(" and ")
            ConfirmSheet(
                title = "Delete ${entry.title}?",
                message = "Deletes projects/${entry.name} with $what from this phone. This can't be undone. Pushed commits stay on the remote.",
                confirmLabel = "Delete",
                onConfirm = {
                    onDone()
                    LocalSessions.close(entry.name)
                    // Its shells (and a dev server in them) go too: left running, they would hold files in the folder
                    // being deleted, keep the terminal notification up, and show up in a new project of the same name.
                    Terminals.inProject(File(entry.path)).forEach { Terminals.close(context, it) }
                    scope.launch {
                        projects.delete(entry.name).onSuccess { onDeleted(entry.name) }.onFailure { onError(it.message ?: "Could not delete the folder") }
                    }
                },
                onDismiss = onDone,
            )
        }
    }
}
