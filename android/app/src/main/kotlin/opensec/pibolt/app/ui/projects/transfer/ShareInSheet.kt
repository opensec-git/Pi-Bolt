package opensec.pibolt.app.ui.projects.transfer

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.projects.ProjectEntry
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/** What the user picked in [ShareInSheet]: the action and, for all but a new project, the project's folder name. */
data class ShareChoice(val action: ShareAction, val project: String?)

/** ACTION_SEND / ACTION_SEND_MULTIPLE as [SharedContent]; null for any other intent or an empty share. */
fun sharedContent(intent: Intent?): SharedContent? {
    if (intent == null) return null
    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(stream(intent))
        Intent.ACTION_SEND_MULTIPLE -> streams(intent)
        else -> return null
    }
    val type = intent.type
    val clipTypes = intent.clipData?.description
    val items = uris.mapIndexed { i, uri ->
        val mime = clipTypes?.takeIf { it.mimeTypeCount > i }?.getMimeType(i)?.takeIf { !it.endsWith("/*") } ?: type
        SharedItem(uri.toString(), mime)
    }
    return SharedContent(items, intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).takeIf { !it.isEmpty }
}

@Suppress("DEPRECATION")
private fun stream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

@Suppress("DEPRECATION")
private fun streams(intent: Intent): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

private fun ShareAction.label(): String = when (this) {
    ShareAction.AttachImages -> "Attach to chat"
    ShareAction.ImportFiles -> "Import files"
    ShareAction.NewProjectFromZip -> "New project"
    ShareAction.InsertText -> "Add to chat"
}

/**
 * Content shared into Pi-Bolt: what to do with it (the default first, see [ShareRouting]) and which project it goes
 * to. A new project from a zip needs no project. [onChoose] runs the choice after the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareInSheet(content: SharedContent, maxImages: Int, projects: suspend () -> List<ProjectEntry>, onChoose: (ShareChoice) -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val route = remember(content) { ShareRouting.route(content, maxImages) }
    var action by remember { mutableStateOf(route.default) }
    val list by produceState<List<ProjectEntry>?>(null) { value = projects() }
    var project by remember { mutableStateOf<String?>(null) }
    if (project == null) list?.firstOrNull()?.let { project = it.name }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val chosen = action ?: return
    val needsProject = route.needsProject(chosen)
    PiSheet(onDismiss = onDismiss, state = state) {
        val what = when {
            route.files == 0 -> "Text"
            route.images == route.files -> if (route.images == 1) "1 image" else "${route.images} images"
            else -> if (route.files == 1) "1 file" else "${route.files} files"
        }
        SheetHeader("Share to Pi-Bolt", subtitle = what)
        if (route.actions.size > 1) {
            Segmented(
                options = route.actions.map { it to it.label() },
                selected = chosen,
                onSelect = { action = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                height = 40.dp,
            )
        }
        if (needsProject) {
            Text("Project", style = type.metaStrong, color = colors.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp))
            val entries = list
            when {
                entries == null -> Spacer(Modifier.height(56.dp))
                entries.isEmpty() -> Text(
                    "No projects yet. " + if (ShareAction.NewProjectFromZip in route.actions) "Make this zip a new project." else "Create one on Home.",
                    style = type.body,
                    color = colors.ink2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                else -> Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (p in entries) {
                        val selected = p.name == project
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .pressable({ project = p.name }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.RadioButton, label = "Choose ${p.meta.title ?: p.name}")
                                .semantics { this.selected = selected }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(p.meta.title ?: p.name, style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val ready = !needsProject || project != null
        PrimaryButton(
            when (chosen) {
                ShareAction.AttachImages -> "Attach"
                ShareAction.ImportFiles -> "Import"
                ShareAction.NewProjectFromZip -> "Create project"
                ShareAction.InsertText -> "Add to chat"
            },
            onClick = { if (ready) close { onChoose(ShareChoice(chosen, if (needsProject) project else null)) } },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
