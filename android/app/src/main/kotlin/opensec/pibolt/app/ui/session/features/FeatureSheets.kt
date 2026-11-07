package opensec.pibolt.app.ui.session.features

import opensec.pibolt.app.ui.session.features2.F2Empty
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.ForkPoint
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.shortPath
import opensec.pibolt.app.ui.providers.Brand
import opensec.pibolt.app.ui.providers.LogoMark
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.session.SessionReducer

/** What the session sheet shows besides the statistics (from the live state). */
data class SessionFacts(
    val model: ModelInfo?,
    val thinkingLevel: String?,
    val sessionName: String?,
    val cwd: String?,
    val version: String?,
    val compacting: Boolean,
    val connected: Boolean,
    /** The last reply's usage, for the context bar until the statistics arrive. */
    val lastTotalTokens: Long?,
)

/**
 * Session: the context bar and token line of the terminal's footer, the session's totals, model and thinking (tap to
 * pick, Next to cycle), folder and version, then Compact, Rename and Export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStatsSheet(
    facts: SessionFacts,
    stats: SessionStats?,
    loading: Boolean,
    onModel: () -> Unit,
    onThinking: () -> Unit,
    onCycleModel: () -> Unit,
    onCycleThinking: () -> Unit,
    onCompact: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Session", subtitle = facts.sessionName)
        Column(
            Modifier.padding(horizontal = 16.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val window = stats?.contextWindow ?: facts.model?.contextWindow
            val fraction = stats?.contextFraction ?: facts.lastTotalTokens?.let { t -> window?.takeIf { it > 0 }?.let { (t.toDouble() / it).toFloat().coerceIn(0f, 1f) } }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Context", style = type.bodyStrong, color = colors.ink1, modifier = Modifier.weight(1f))
                    val label = stats?.contextLabel ?: window?.let { w -> fraction?.let { "${(it * 100).toInt()}% of ${SessionReducer.formatTokens(w)}" } } ?: "No usage yet"
                    Text(label, style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink2)
                }
                Spacer(Modifier.height(10.dp))
                ContextBar(fraction)
                if (stats != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(stats.footer, style = type.monoMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
                } else if (loading) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuadSpinner(QuadMode.Think, size = 12.dp, color = colors.ink3)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading…", style = type.meta, color = colors.ink3)
                    }
                }
            }
            if (stats != null) {
                Card {
                    Row {
                        Stat("Input", SessionReducer.formatTokens(stats.input), Modifier.weight(1f))
                        Stat("Output", SessionReducer.formatTokens(stats.output), Modifier.weight(1f))
                        Stat("Cost", SessionStats.formatCost(stats.cost), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Stat("Cache read", SessionReducer.formatTokens(stats.cacheRead), Modifier.weight(1f))
                        Stat("Cache write", SessionReducer.formatTokens(stats.cacheWrite), Modifier.weight(1f))
                        Stat("Cache hit", stats.cacheHitPercent?.let { "$it%" } ?: "—", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        listOf(
                            plural(stats.userMessages, "prompt"),
                            plural(stats.assistantMessages, "reply", "replies"),
                            plural(stats.toolCalls, "tool call"),
                        ).joinToString(" · "),
                        style = type.meta.copy(fontFeatureSettings = Tnum),
                        color = colors.ink3,
                    )
                }
            }
            PickRow("Model", facts.model?.label ?: "—", enabled = facts.connected, onClick = { close(onModel) }, onNext = onCycleModel, nextLabel = "Next model", brand = facts.model?.let { ProviderCatalog.modelBrand(it.id, it.provider) })
            PickRow("Thinking", facts.thinkingLevel?.replaceFirstChar { it.uppercase() } ?: "—", enabled = facts.connected, onClick = { close(onThinking) }, onNext = onCycleThinking, nextLabel = "Next thinking level")
            facts.cwd?.takeIf { it.isNotBlank() }?.let { InfoLine("Folder", shortPath(it), mono = true) }
            facts.version?.takeIf { it.isNotBlank() }?.let { InfoLine("Pi-Bolt", "v$it") }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (facts.compacting) "Compacting…" else "Compact", onClick = { close(onCompact) }, enabled = facts.connected && !facts.compacting, modifier = Modifier.weight(1f))
                SecondaryButton("Rename", onClick = { close(onRename) }, enabled = facts.connected, modifier = Modifier.weight(1f))
                SecondaryButton("Export", onClick = { close(onExport) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun plural(n: Long, one: String, many: String = one + "s") = "$n ${if (n == 1L) one else many}"

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radius.tile)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Pi.colors.surface1, shape)
            .border(1.dp, Pi.colors.line, shape)
            .padding(16.dp),
    ) { content() }
}

/** The context in use as a 6dp bar: ink2, warning above 70%, danger above 90%; animates to its value. */
@Composable
fun ContextBar(fraction: Float?) {
    val colors = Pi.colors
    val value by animateFloatAsState(fraction ?: 0f, PiMotion.inPlace(PiMotion.SETTLE), label = "context")
    val color = when {
        (fraction ?: 0f) > 0.9f -> colors.danger
        (fraction ?: 0f) > 0.7f -> colors.warning
        else -> colors.ink2
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(colors.surface3, CircleShape)
            .semantics { stateDescription = fraction?.let { "${(it * 100).toInt()} percent of the context used" } ?: "Context use unknown" },
    ) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).fillMaxHeight().background(color, CircleShape))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = Pi.type.rowTitle.copy(fontFeatureSettings = Tnum), color = Pi.colors.ink1, maxLines = 1)
        Text(label, style = Pi.type.meta, color = Pi.colors.ink3, maxLines = 1)
    }
}

@Composable
private fun PickRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit, onNext: () -> Unit, nextLabel: String, brand: Brand? = null) {
    val colors = Pi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .pressable(onClick, shape = RoundedCornerShape(Radius.sm), enabled = enabled, pressedScale = 1f, label = "$label: $value")
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Pi.type.rowMeta, color = colors.ink3, modifier = Modifier.width(88.dp))
            if (brand != null) {
                LogoMark(brand, size = IconSize.sm)
                Spacer(Modifier.width(Space.sm))
            }
            Text(value, style = Pi.type.rowTitle, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp))
        }
        TextAction("Next", onClick = onNext, color = colors.accent, enabled = enabled, modifier = Modifier.semantics { contentDescription = nextLabel })
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Pi.type.rowMeta, color = Pi.colors.ink3, modifier = Modifier.width(88.dp))
        Text(value, style = if (mono) Pi.type.monoSmall else Pi.type.rowMeta, color = Pi.colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Another saved session of this project (as the terminal's /resume): newest first, the open one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeSheet(sessions: List<SessionSummary>?, current: String?, onPick: (SessionSummary) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val now = System.currentTimeMillis()
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Resume a session")
        when {
            sessions == null -> Loading("Loading sessions")
            sessions.isEmpty() -> F2Empty("No saved sessions yet", Icons.AutoMirrored.Rounded.Chat)
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(sessions, key = { it.file }) { s ->
                    val open = s.file == current
                    val meta = buildList {
                        if (open) add("Open")
                        if (s.messageCount > 0) add(if (s.messageCount == 1) "1 message" else "${s.messageCount} messages")
                        add(ProjectRules.ago(s.updatedAt, now))
                    }.joinToString(" · ")
                    ListRow(Icons.AutoMirrored.Rounded.Chat, ProjectRules.sessionTitle(s), meta, enabled = !open) { close { onPick(s) } }
                }
            }
        }
    }
}

/** A user message to fork from (as the terminal's /fork): the new session holds what came before it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForkSheet(points: List<ForkPoint>?, onPick: (ForkPoint) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Fork from a message", subtitle = "Starts a new session before the message you pick")
        when {
            points == null -> Loading("Loading messages")
            points.isEmpty() -> F2Empty("No messages to fork from yet", Icons.AutoMirrored.Rounded.CallSplit)
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                // Newest first: forks usually go back a step or two.
                items(points.asReversed(), key = { it.entryId }) { p ->
                    ListRow(Icons.AutoMirrored.Rounded.CallSplit, p.text.replace('\n', ' ').ifBlank { "(empty message)" }, null) { close { onPick(p) } }
                }
            }
        }
    }
}

@Composable
private fun ListRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, meta: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Pi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick, shape = RoundedCornerShape(0.dp), enabled = enabled, pressedScale = 1f)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Pi.type.rowTitle, color = if (enabled) colors.ink1 else colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = Pi.type.rowMeta, color = colors.ink3, maxLines = 1)
        }
    }
}

@Composable
private fun Loading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        QuadSpinner(QuadMode.Think, size = 18.dp, color = Pi.colors.ink2)
        Spacer(Modifier.width(12.dp))
        Text(label, style = Pi.type.body, color = Pi.colors.ink3)
    }
}

