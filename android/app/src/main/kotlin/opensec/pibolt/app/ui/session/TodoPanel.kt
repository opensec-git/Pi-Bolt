package opensec.pibolt.app.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.TodoState
import opensec.pibolt.protocol.session.TodoStatus
import opensec.pibolt.protocol.session.TodoTask

/** Task rows the open panel shows, the "+n more" line included; past it completed rows go first. */
const val TODO_ROWS = 6

/** The rows that fit [budget] and what was left out. */
@Immutable
data class TodoLayout(val rows: List<TodoTask>, val hiddenCompleted: Int, val hiddenOpen: Int) {
    val hidden: Int get() = hiddenCompleted + hiddenOpen
}

/**
 * The todo extension's own layout: everything when it fits; else one line goes to "+n more", completed rows are
 * dropped first (the earliest kept while room is left), then the open tasks past the budget.
 */
fun todoLayout(tasks: List<TodoTask>, budget: Int = TODO_ROWS): TodoLayout {
    if (tasks.size <= budget) return TodoLayout(tasks, 0, 0)
    val room = budget - 1
    val open = tasks.filter { it.status != TodoStatus.Completed }
    val completed = tasks.size - open.size
    if (open.size >= room) return TodoLayout(open.take(room), completed, open.size - room)
    val kept = HashSet<TodoTask>(open)
    for (t in tasks) {
        if (kept.size >= room) break
        if (t.status == TodoStatus.Completed) kept.add(t)
    }
    val rows = tasks.filter { it in kept }
    return TodoLayout(rows, completed - rows.count { it.status == TodoStatus.Completed }, 0)
}

/** "+3 more (2 completed, 1 pending)". */
fun todoOverflow(layout: TodoLayout): String {
    val parts = listOfNotNull(
        layout.hiddenCompleted.takeIf { it > 0 }?.let { "$it completed" },
        layout.hiddenOpen.takeIf { it > 0 }?.let { "$it pending" },
    )
    return "+${layout.hidden} more (${parts.joinToString(", ")})"
}

/** What a row says, also to TalkBack: the in-progress task by its active form while the agent works. */
fun todoText(task: TodoTask, running: Boolean): String =
    if (task.status == TodoStatus.InProgress && running) task.activeForm ?: task.subject else task.subject

/**
 * The todo list above the composer: "Todos (done/total)" with a progress ring, then a row per task. The heading
 * collapses the panel to itself (the current task then follows the count). It appears and leaves with the list.
 *
 * [autoCollapse] (idle, or a short window) shows the collapsed form until the user taps the heading; a change of it
 * drops that choice again. [quiet] (the live status line is on screen and owns the one spinner) draws the task in
 * progress as a still half ring instead of a Quad.
 */
@Composable
fun TodoPanel(todos: TodoState, running: Boolean, autoCollapse: Boolean = false, quiet: Boolean = false) {
    val colors = Pi.colors
    // The last non-empty list, so the panel keeps its rows while it leaves.
    val shown = remember { mutableStateOf(todos) }
    if (todos.visible.isNotEmpty()) shown.value = todos
    AnimatedVisibility(
        visible = todos.visible.isNotEmpty(),
        enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
        exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
    ) {
        // The user's own choice (true = collapsed); null follows [autoCollapse].
        var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(autoCollapse) { choice = null }
        val collapsed = choice ?: autoCollapse
        val tasks = shown.value.visible
        val done = tasks.count { it.status == TodoStatus.Completed }
        val current = tasks.firstOrNull { it.status == TodoStatus.InProgress } ?: tasks.firstOrNull { it.status == TodoStatus.Pending }
        val layout = remember(tasks) { todoLayout(tasks) }
        val reduced = Pi.reduceMotion
        val shape = RoundedCornerShape(Radius.tile)
        Column(
            Modifier
                .padding(start = Space.page, end = Space.page, bottom = 8.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface1, shape)
                .border(1.dp, colors.line, shape)
                .animateContentSize(if (reduced) snap() else PiMotion.inPlace()),
        ) {
            TodoHeading(done, tasks.size, if (collapsed) current?.let { todoText(it, running) } else null, collapsed) { choice = !collapsed }
            AnimatedVisibility(
                visible = !collapsed,
                enter = expandVertically(PiMotion.inPlace(), expandFrom = Alignment.Top) + fadeIn(PiMotion.inPlace(PiMotion.QUICK)),
                exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD), shrinkTowards = Alignment.Top) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    for (task in layout.rows) key(task.id) { TodoRow(task, running, quiet) }
                    if (layout.hidden > 0) {
                        Text(
                            todoOverflow(layout),
                            style = Pi.type.meta,
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 46.dp, end = 14.dp, top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 48dp heading: the progress ring, "Todos", the count, the current task when collapsed, the chevron. */
@Composable
private fun TodoHeading(done: Int, total: Int, current: String?, collapsed: Boolean, onToggle: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "todoChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onToggle, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = if (collapsed) "Expand" else "Collapse")
            .clearAndSetSemantics {
                contentDescription = "Todos, $done of $total done" + (current?.let { ". Now: $it" } ?: "")
                stateDescription = if (collapsed) "Collapsed" else "Expanded"
            }
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { ProgressRing(if (total == 0) 0f else done / total.toFloat()) }
        Spacer(Modifier.width(12.dp))
        Text("Todos", style = type.label, color = colors.ink1)
        Spacer(Modifier.width(6.dp))
        Text("$done/$total", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
        if (current != null) {
            Spacer(Modifier.width(12.dp))
            Text(current, style = type.meta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
    }
}

/** A 14dp ring: an ink4 track and the done share in success, sweeping to its new value (settle). */
@Composable
private fun ProgressRing(fraction: Float) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val sweep by animateFloatAsState(fraction, if (reduced) snap() else tween(PiMotion.SETTLE, easing = PiMotion.InPlace), label = "todoRing")
    val track = colors.ink4
    val fill = colors.success
    Canvas(Modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        if (sweep > 0f) drawArc(fill, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(stroke))
    }
}

private enum class Glyph { Pending, Working, Paused, Done }

/** One task: 20dp glyph slot, 12dp gap, the text. Not interactive, so 32dp tall. */
@Composable
private fun TodoRow(task: TodoTask, running: Boolean, quiet: Boolean) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val glyph = when (task.status) {
        TodoStatus.Pending -> Glyph.Pending
        TodoStatus.InProgress -> if (running && !quiet) Glyph.Working else Glyph.Paused
        TodoStatus.Completed -> Glyph.Done
    }
    val text = todoText(task, running)
    val color by animateColorAsState(
        when (task.status) {
            TodoStatus.InProgress -> colors.ink1
            TodoStatus.Pending -> colors.ink2
            TodoStatus.Completed -> colors.ink3
        },
        if (reduced) snap() else PiMotion.inPlace(PiMotion.QUICK),
        label = "todoInk",
    )
    val state = when (task.status) {
        TodoStatus.Pending -> "To do"
        TodoStatus.InProgress -> "In progress"
        TodoStatus.Completed -> "Done"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clearAndSetSemantics { contentDescription = "$state: $text" }
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = glyph,
            transitionSpec = {
                if (reduced) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    (fadeIn(PiMotion.inPlace(PiMotion.QUICK)) + scaleIn(PiMotion.inPlace(PiMotion.QUICK), initialScale = 0.7f)) togetherWith
                        fadeOut(tween(PiMotion.exit(PiMotion.QUICK)))
                }
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(20.dp),
            label = "todoGlyph",
        ) { g ->
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when (g) {
                    Glyph.Pending -> StatusCircle(filled = false)
                    Glyph.Paused -> StatusCircle(filled = true, ink = if (running) colors.ink1 else colors.ink2)
                    Glyph.Working -> QuadSpinner(QuadMode.Think, size = IconSize.xs, color = colors.ink1)
                    Glyph.Done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = Pi.type.body.copy(textDecoration = if (task.status == TodoStatus.Completed) TextDecoration.LineThrough else null),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A 12dp ring for a pending task (ink3); [filled] fills its left half for one in progress while the agent is idle. */
@Composable
private fun StatusCircle(filled: Boolean, ink: Color = if (filled) Pi.colors.ink2 else Pi.colors.ink3) {
    Canvas(Modifier.size(12.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(ink, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (filled) drawArc(ink, 90f, 180f, true, Offset.Zero, size)
    }
}
