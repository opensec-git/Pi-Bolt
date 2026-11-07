package opensec.pibolt.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.engine.BackgroundJobs
import opensec.pibolt.app.engine.EnginePaths
import opensec.pibolt.app.terminal.DevServerPorts
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import java.io.File

/**
 * The "Background" pill of a project session while it has background jobs (BackgroundJobs), and its sheet: each job's
 * name, state, uptime and last output lines, live, with Stop and the full log. Reads the extension's jobs file of the
 * engine [enginePid] every 2 s (every second while the sheet is open). Ports the jobs print feed the Preview chip.
 */
@Composable
fun BackgroundJobsPill(enginePid: Long?, modifier: Modifier = Modifier) {
    if (enginePid == null) return
    val context = LocalContext.current
    val file = remember(enginePid) { BackgroundJobs.stateFile(File(EnginePaths.prefixFor(context.packageName), "tmp"), enginePid) }
    var jobs by remember(enginePid) { mutableStateOf(emptyList<BackgroundJobs.Job>()) }
    var tails by remember(enginePid) { mutableStateOf(emptyMap<Int, String>()) }
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(file, open) {
        while (true) {
            val (j, t) = withContext(Dispatchers.IO) {
                val list = BackgroundJobs.read(file)
                list to (if (open || list.any { it.running }) list.associate { it.id to BackgroundJobs.tail(it.logFile) } else emptyMap())
            }
            jobs = j
            tails = t
            now = System.currentTimeMillis()
            BackgroundJobs.hintPorts = j.filter { it.running }.flatMap { DevServerPorts.portsMentioned(t[it.id].orEmpty()) }.distinct()
            delay(if (open) 1_000 else 2_000)
        }
    }
    if (jobs.isEmpty()) return
    val colors = Pi.colors
    val running = jobs.any { it.running }
    GlassPill(modifier, onClick = { open = true }, label = "Background jobs") {
        Box(Modifier.size(8.dp).background(if (running) colors.success else colors.ink3, CircleShape))
        Text("Background · ${BackgroundJobs.summary(jobs)}", style = Pi.type.label, color = colors.ink1, maxLines = 1)
    }
    if (open) BackgroundJobsSheet(jobs, tails, now, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundJobsSheet(jobs: List<BackgroundJobs.Job>, tails: Map<Int, String>, now: Long, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val scope = rememberCoroutineScope()
    var full by remember { mutableStateOf<BackgroundJobs.Job?>(null) }
    var stopping by remember { mutableStateOf(emptySet<Int>()) }
    PiSheet(onDismiss = onDismiss) {
        val shown = full
        if (shown != null) {
            SheetHeader(shown.name, subtitle = "Full log", onBack = { full = null })
            var text by remember(shown.id) { mutableStateOf("") }
            LaunchedEffect(shown.id) {
                while (true) {
                    text = withContext(Dispatchers.IO) { BackgroundJobs.tail(shown.logFile, 256 * 1024) }
                    delay(1_000)
                }
            }
            Text(
                text.ifEmpty { "(no output)" },
                style = Pi.type.monoSmall,
                color = colors.ink1,
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState(), reverseScrolling = true).horizontalScroll(rememberScrollState()).padding(horizontal = Space.page),
            )
            return@PiSheet
        }
        SheetHeader("Background", subtitle = BackgroundJobs.summary(jobs))
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            for (job in jobs.sortedWith(compareByDescending<BackgroundJobs.Job> { it.running }.thenByDescending { it.id })) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (job.running) colors.success else if (job.exitCode == 0) colors.ink3 else colors.danger, CircleShape))
                        Spacer(Modifier.size(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text("#${job.id} ${job.name}", style = Pi.type.bodyStrong, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${job.state} · ${BackgroundJobs.uptime(job, now)}", style = Pi.type.label, color = colors.ink2, maxLines = 1)
                        }
                        GhostButton("Log", onClick = { full = job }, size = ButtonSize.Small)
                        if (job.running) {
                            GhostButton(
                                if (job.id in stopping) "Stopping" else "Stop",
                                enabled = job.id !in stopping,
                                color = colors.danger,
                                size = ButtonSize.Small,
                                onClick = {
                                    stopping = stopping + job.id
                                    scope.launch(Dispatchers.IO) { BackgroundJobs.stop(job) }
                                },
                            )
                        }
                    }
                    val tail = tails[job.id].orEmpty().trimEnd().lines().takeLast(6).joinToString("\n")
                    if (tail.isNotEmpty()) {
                        Text(tail, style = Pi.type.monoSmall, color = colors.ink2, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
                    }
                }
            }
        }
    }
}
