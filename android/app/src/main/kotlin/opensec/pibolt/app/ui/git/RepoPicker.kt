package opensec.pibolt.app.ui.git

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.git.github.GitHub
import opensec.pibolt.app.git.github.GitHubException
import opensec.pibolt.app.git.github.GitHubRepo
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi

/** The signed-in user's repositories (most recently pushed first, 100 per page), filtered as they type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoPickerSheet(github: GitHub, onPick: (GitHubRepo) -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(page) {
        loading = true
        try {
            val got = github.api.repos(page, PER_PAGE)
            repos = (repos + got).distinctBy { it.fullName }
            more = got.size == PER_PAGE
        } catch (e: GitHubException) {
            error = e.message
            more = false
        }
        loading = false
    }
    val shown = remember(repos, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) repos else repos.filter { q in it.fullName.lowercase() || q in it.description.orEmpty().lowercase() }
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Clone from GitHub", subtitle = github.account?.login?.let { "@$it" })
        PiTextField(
            query,
            { query = it },
            placeholder = "Search repositories",
            singleLine = true,
            leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = "Search repositories" },
        )
        Spacer(Modifier.height(8.dp))
        error?.let { Text(it, style = type.body, color = colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(shown, key = { it.fullName }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .pressable({ close { onPick(r) } }, label = "Clone ${r.fullName}")
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.fullName, style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        r.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (r.private) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Lock, contentDescription = "Private", tint = colors.ink3, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (loading) {
                item(key = "loading") {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        QuadSpinner(QuadMode.Connect, size = 16.dp, color = colors.ink2)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading repositories…", style = type.meta, color = colors.ink2)
                    }
                }
            } else if (more) {
                item(key = "more") { TextAction("Load more", onClick = { page++ }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) }
            } else if (repos.isEmpty() && error == null) {
                item(key = "none") { Note("No repositories on this account.") }
            }
        }
    }
}

private const val PER_PAGE = 100
