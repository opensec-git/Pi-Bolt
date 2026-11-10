package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import java.io.File

/** What the Files tab asks for: a sheet for an entry, a name prompt, or a delete confirmation. */
sealed interface FileAction {
    data class Menu(val entry: FileEntry) : FileAction

    data class Rename(val entry: FileEntry) : FileAction

    data class Delete(val entry: FileEntry) : FileAction

    data class Create(val folder: Boolean) : FileAction

    /** Zip a folder (Save to… or Share); handled by the project screen. */
    data class Export(val entry: FileEntry) : FileAction

    /** Send one file through the share sheet; handled by the project screen. */
    data class Share(val entry: FileEntry) : FileAction
}

/**
 * The Files tab's sticky glass toolbar: tappable breadcrumbs (`calc-parser › src › ui`, rowMeta; the folder shown in
 * ink1, the way back in ink2, chevrons ink3), then two 40dp glass buttons: New (file, folder, import) and the sort
 * menu, which also holds "Show hidden files". 56dp on the canvas, so rows scrolling under it are cut cleanly.
 */
@Composable
fun FilesBar(
    root: String,
    path: String,
    sort: FileSort,
    showHidden: Boolean,
    onPath: (String) -> Unit,
    onSort: (FileSort) -> Unit,
    onHidden: (Boolean) -> Unit,
    onCreate: (folder: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Import from the system file picker: documents (false) or a whole folder (true). */
    onImport: ((folder: Boolean) -> Unit)? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val crumbs = remember(root, path) { ProjectFiles.crumbs(root, path) }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    var sortMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    // A crumb pads 8dp round its text, and a 40dp glass button sits in a 48dp target: both land on the 20dp gutter.
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = Space.page - Space.sm, end = Space.lg), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { i, (label, target) ->
                // ink3, not ink4: no text or glyph that carries meaning below ink3 (DESIGN.md "Contrast").
                if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.sm))
                val last = i == crumbs.lastIndex
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .pressable({ onPath(target) }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, enabled = !last, label = "Go to $label")
                        .padding(horizontal = Space.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = type.rowMeta, color = if (last) colors.ink1 else colors.ink2, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(Space.xs))
        Box {
            GlassIconButton(Icons.Rounded.Add, "New file or folder", onClick = { newMenu = true }, size = 40.dp)
            WorkspaceMenu(newMenu, { newMenu = false }) {
                WorkspaceMenuRow("New file", Icons.AutoMirrored.Rounded.NoteAdd, colors.ink1) {
                    newMenu = false
                    onCreate(false)
                }
                WorkspaceMenuRow("New folder", Icons.Rounded.CreateNewFolder, colors.ink1) {
                    newMenu = false
                    onCreate(true)
                }
                onImport?.let { import ->
                    WorkspaceMenuRow("Import files…", Icons.Rounded.FileOpen, colors.ink1) {
                        newMenu = false
                        import(false)
                    }
                    WorkspaceMenuRow("Import a folder…", Icons.Rounded.DriveFolderUpload, colors.ink1) {
                        newMenu = false
                        import(true)
                    }
                }
            }
        }
        Box {
            GlassIconButton(Icons.AutoMirrored.Rounded.Sort, "Sort and filter", onClick = { sortMenu = true }, size = 40.dp)
            WorkspaceMenu(sortMenu, { sortMenu = false }) {
                for (s in FileSort.entries) {
                    WorkspaceMenuRow("Sort by ${s.label.lowercase()}", if (s == sort) Icons.Rounded.Check else null, colors.ink1) {
                        sortMenu = false
                        onSort(s)
                    }
                }
                WorkspaceMenuRow("Show hidden files", if (showHidden) Icons.Rounded.Check else Icons.Rounded.Visibility, colors.ink1) {
                    sortMenu = false
                    onHidden(!showHidden)
                }
            }
        }
    }
}

/** Long press on a file or folder: Rename, Delete, and New file / New folder in the folder shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileMenuSheet(entry: FileEntry, onAction: (FileAction) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val colors = Pi.colors
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(entry.name, subtitle = if (entry.dir) "Folder" else "${FileTypes.label(entry.name)} · ${ProjectFiles.size(entry.size)}")
        Spacer(Modifier.height(4.dp))
        SheetRow("Rename", Icons.Rounded.DriveFileRenameOutline, colors.ink1) { close { onAction(FileAction.Rename(entry)) } }
        if (entry.dir) {
            SheetRow("Export as zip", Icons.Rounded.Archive, colors.ink1) { close { onAction(FileAction.Export(entry)) } }
        } else {
            SheetRow("Share", Icons.Rounded.Share, colors.ink1) { close { onAction(FileAction.Share(entry)) } }
        }
        SheetRow("New file here", Icons.AutoMirrored.Rounded.NoteAdd, colors.ink1) { close { onAction(FileAction.Create(folder = false)) } }
        SheetRow("New folder here", Icons.Rounded.CreateNewFolder, colors.ink1) { close { onAction(FileAction.Create(folder = true)) } }
        SheetRow("Delete", Icons.Rounded.DeleteOutline, colors.danger) { close { onAction(FileAction.Delete(entry)) } }
    }
}

@Composable
private fun SheetRow(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressable(onClick, shape = RoundedCornerShape(0.dp), pressedScale = 1f)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = Pi.type.body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Asks for a name (new file, new folder or rename). [onSubmit] runs the change and returns an error to show, or null
 * when it is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameSheet(title: String, initial: String, action: String, onSubmit: suspend (String) -> String?, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(250)
        runCatching { focus.requestFocus() }
    }
    fun submit() {
        if (busy || name.isBlank()) return
        busy = true
        scope.launch {
            val e = onSubmit(name)
            busy = false
            if (e == null) close(onDismiss) else error = e
        }
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(title)
        Column(Modifier.padding(horizontal = 16.dp)) {
            PiTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                placeholder = "Name",
                singleLine = true,
                style = Pi.type.mono,
                isError = error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Text(error ?: " ", style = Pi.type.meta, color = Pi.colors.danger, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp))
        }
        PrimaryButton(if (busy) "Working…" else action, onClick = ::submit, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
    }
}

/**
 * The sheet for the Files tab's current [action], in [folder] (the folder shown). [onMoved] gets an entry's old name
 * and its new one (null when deleted), so open editor tabs follow it.
 */
@Composable
fun FileActionSheets(action: FileAction?, folder: File, onAction: (FileAction?) -> Unit, onDone: () -> Unit, onError: (String) -> Unit, onMoved: (from: String, to: String?) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    when (action) {
        null, is FileAction.Export, is FileAction.Share -> Unit
        is FileAction.Menu -> FileMenuSheet(action.entry, onAction = { onAction(it) }, onDismiss = { onAction(null) })
        is FileAction.Rename -> NameSheet(
            title = "Rename",
            initial = action.entry.name,
            action = "Rename",
            onSubmit = { name ->
                ProjectFiles.rename(File(folder, action.entry.name), name).fold({ target ->
                    onMoved(action.entry.name, target.name)
                    onDone()
                    null
                }, { it.message ?: "Could not rename" })
            },
            onDismiss = { onAction(null) },
        )
        is FileAction.Create -> NameSheet(
            title = if (action.folder) "New folder" else "New file",
            initial = "",
            action = "Create",
            onSubmit = { name -> ProjectFiles.create(folder, name, action.folder).fold({ onDone(); null }, { it.message ?: "Could not create it" }) },
            onDismiss = { onAction(null) },
        )
        is FileAction.Delete -> ConfirmSheet(
            title = "Delete ${action.entry.name}?",
            message = if (action.entry.dir) "The folder and everything in it are deleted from this phone. This can't be undone." else "The file is deleted from this phone. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    ProjectFiles.delete(File(folder, action.entry.name))
                        .onSuccess { onMoved(action.entry.name, null) }
                        .onFailure { onError(it.message ?: "Could not delete") }
                    onDone()
                }
            },
            onDismiss = { onAction(null) },
        )
    }
}
