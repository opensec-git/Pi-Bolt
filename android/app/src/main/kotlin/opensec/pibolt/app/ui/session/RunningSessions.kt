package opensec.pibolt.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.RunningSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiMenu
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.UserItem
import opensec.pibolt.app.ui.projects.SessionMark
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/** The live on-device sessions that are working or waiting for an answer, in the order they were opened. */
@OptIn(ExperimentalCoroutinesApi::class)
fun runningSessions(): Flow<List<LocalSessions.Live>> = LocalSessions.all.flatMapLatest { all ->
    if (all.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(
            all.values.map { live ->
                live.controller.state.map(SwitcherRules::inTurn).distinctUntilChanged().map { live to it }
            },
        ) { pairs -> pairs.filter { it.second && !it.first.controller.stopped }.map { it.first } }
    }
}

/** What a live session is doing, in a few words ("Working", "Waiting for your answer", "Idle"). */
@Composable
internal fun rememberStatus(controller: SessionController): Pair<String, Boolean> {
    val state = controller.state.collectAsStateWithLifecycle()
    val link = controller.link.collectAsStateWithLifecycle()
    val status by remember {
        derivedStateOf {
            val s = state.value
            val entry = RunningSummary.Entry(controller.hostName.value, controller.isLocal, s.isRunning, s.dialogs.isNotEmpty(), s.isCompacting, link.value is LinkState.Connected)
            (if (entry.busy || s.isCompacting) entry.status else "Idle") to (entry.busy || s.isCompacting)
        }
    }
    return status
}

/** The Quad of a live session: still when idle, warning-coloured while it waits for an answer. */
@Composable
private fun LiveMark(controller: SessionController, busy: Boolean) {
    val colors = Pi.colors
    val state = controller.state.collectAsStateWithLifecycle()
    val waiting by remember { derivedStateOf { state.value.dialogs.isNotEmpty() } }
    val mode = when {
        waiting -> QuadMode.Wait
        state.value.isCompacting -> QuadMode.Compact
        else -> QuadMode.Run
    }
    QuadSpinner(mode, size = 16.dp, color = if (waiting) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
}

/**
 * A live session at the top of Home's "Recents" ([InsetRow], the references' "Working · project" row): its Quad in its
 * mode (warning while Pi waits for an answer), what it was asked (its name, else the latest prompt, else the project),
 * and what it does with the project under it. A tap opens it without stopping any other.
 */
@Composable
fun RunningRow(live: LocalSessions.Live, meta: ProjectMeta?, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    val project = meta?.title ?: live.project.name
    val (status, _) = rememberStatus(live.controller)
    val title = rememberAsked(live.controller, project)
    InsetRow(
        title = title,
        meta = "$status · $project",
        onClick = onClick,
        label = "Open session",
        divider = divider,
        modifier = modifier,
        leading = { SessionMark(live.controller) },
    )
}

/** What a live session was asked: its name, else its latest prompt, else [fallback] (its project). */
@Composable
private fun rememberAsked(controller: SessionController, fallback: String): String {
    val state = controller.state.collectAsStateWithLifecycle()
    val title by remember(fallback) {
        derivedStateOf {
            val s = state.value
            ProjectRules.focusTitle(s.sessionName, (s.items.lastOrNull { it is UserItem } as? UserItem)?.text, fallback)
        }
    }
    return title
}

/** The live on-device sessions waiting for an answer (a dialog is open), in the order they were opened. */
@OptIn(ExperimentalCoroutinesApi::class)
fun waitingSessions(): Flow<List<LocalSessions.Live>> = LocalSessions.all.flatMapLatest { all ->
    if (all.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(
            all.values.map { live -> live.controller.state.map { s -> s.dialogs.isNotEmpty() }.distinctUntilChanged().map { live to it } },
        ) { pairs -> pairs.filter { it.second && !it.first.controller.stopped }.map { it.first } }
    }
}

/** The words and badge of the top bar's session switcher, from how many other sessions are open and how many run. */
object SwitcherRules {
    /** A session in a turn: working, compacting or waiting for an answer. An open, idle session is not running. */
    fun inTurn(s: SessionState): Boolean = s.isRunning || s.isCompacting || s.dialogs.isNotEmpty()

    /** "Other sessions, 2 running" while any of them is in a turn, else "Other sessions, 1 open". */
    fun label(open: Int, running: Int): String = if (running > 0) "Other sessions, $running running" else "Other sessions, $open open"

    /** The accent badge counts running sessions only: none while all of them are idle. */
    fun badge(running: Int): Int? = running.takeIf { it > 0 }
}

/**
 * The session switcher in a session's top bar: shown only while other sessions are live; its badge counts the ones in
 * a turn ([SwitcherRules]). It lists them with what each is doing (several of one project by what each was asked), and
 * a tap moves to one without stopping any. [onOpen] gets its [LocalSessions.Live.key].
 */
@Composable
fun SessionSwitcher(current: SessionController, titles: Map<String, ProjectMeta>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val all by LocalSessions.all.collectAsStateWithLifecycle()
    val others = all.values.filter { it.controller !== current && !it.controller.stopped }
    val running by remember(others) {
        if (others.isEmpty()) flowOf(0) else combine(others.map { it.controller.state.map(SwitcherRules::inTurn).distinctUntilChanged() }) { busy -> busy.count { it } }
    }.collectAsStateWithLifecycle(0)
    if (others.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconAction(Icons.Rounded.Layers, SwitcherRules.label(others.size, running), onClick = { open = true }, tint = colors.ink2)
        SwitcherRules.badge(running)?.let { n ->
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 8.dp)
                    .size(16.dp)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", style = Pi.type.meta, color = colors.onAccent, maxLines = 1)
            }
        }
        PiMenu(expanded = open, onDismiss = { open = false }) {
            for (live in others) {
                val (status, busy) = rememberStatus(live.controller)
                val project = titles[live.project.name]?.title ?: live.project.name
                // Two sessions of one project (one ran when the other opened): each by what it was asked.
                val shared = all.values.count { it.project == live.project && !it.controller.stopped } > 1
                val asked = rememberAsked(live.controller, project)
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(min = 180.dp, max = 280.dp)) {
                            Text(if (shared) asked else project, style = Pi.type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (shared) "$status · $project" else status, style = Pi.type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingIcon = { Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { LiveMark(live.controller, busy) } },
                    onClick = {
                        open = false
                        onOpen(live.key)
                    },
                    modifier = Modifier.height(56.dp).padding(vertical = 0.dp),
                )
            }
        }
    }
}
