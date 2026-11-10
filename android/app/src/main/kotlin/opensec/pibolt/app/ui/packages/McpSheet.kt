package opensec.pibolt.app.ui.packages

import androidx.compose.material.icons.rounded.Cable
import opensec.pibolt.app.ui.components.ErrorState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import opensec.pibolt.app.engine.EngineApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.session.features2.F2Empty
import opensec.pibolt.app.ui.session.features2.F2Loading
import opensec.pibolt.app.ui.session.features2.F2Row
import opensec.pibolt.app.ui.session.features2.F2Section
import opensec.pibolt.app.ui.session.features2.F2Tag
import opensec.pibolt.app.ui.session.features2.F2Well
import opensec.pibolt.app.ui.theme.Pi

/**
 * MCP servers (docs/mcp.md) through Pi's own commands: `pi mcp list --json` (it connects to each enabled server and
 * reports its state and tools), `pi mcp add` and `pi mcp remove` on the user-level `mcp.json`. A server started by a
 * command must be a program this phone has; remote servers (a URL) work anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSheet(engine: EngineApi, onReload: (() -> Unit)?, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<McpReport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var tokenEnv by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<McpServer?>(null) }

    LaunchedEffect(reload) {
        report = null
        failure = null
        val result = engine.cli(listOf("mcp", "list", "--json"), timeoutMs = 90_000)
        // Exit 1 also means "a server is not connected": the JSON is still there.
        report = McpServers.parse(result.stdout)
        if (report == null) failure = result.stderr.trim().ifBlank { result.stdout.trim() }.ifBlank { "Pi could not list MCP servers (exit code ${result.code})" }
    }

    fun run(label: String, args: List<String>, done: String, after: () -> Unit = {}) {
        working = label
        scope.launch {
            val result = engine.cli(args, timeoutMs = 60_000)
            working = null
            if (result.ok) {
                changed = true
                onMessage(done)
                after()
            } else {
                onMessage(result.stderr.trim().ifBlank { result.stdout.trim() }.lineSequence().firstOrNull() ?: "Failed")
            }
            reload++
        }
    }

    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("MCP servers", subtitle = "Tools for every session on this phone.")
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            if (changed && onReload != null) {
                F2Well {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reload the session to apply changes", style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f))
                        SecondaryButton("Reload", onClick = { close(onReload) })
                    }
                }
            }
            working?.let { F2Loading(it) }
            val r = report
            when {
                failure != null -> ErrorState("Could not reach the MCP servers", message = failure, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                r == null -> F2Loading("Connecting to servers")
                else -> {
                    if (r.servers.isEmpty()) F2Empty("No MCP servers yet", Icons.Rounded.Cable)
                    for (s in r.servers) {
                        val meta = buildList {
                            add(s.state)
                            if (s.tools.isNotEmpty()) add("${s.tools.size} tools")
                            if (s.transport.isNotBlank()) add(s.transport)
                            if (s.scope != "global") add(s.scope)
                            s.error?.let { add(it) }
                        }.joinToString(" · ")
                        F2Row(s.name, meta, Icons.Rounded.Hub, onClick = null, iconTint = if (s.state == "connected") Pi.colors.success else Pi.colors.ink3) {
                            F2Tag(if (s.enabled) s.state else "disabled", color = if (s.state == "connected") Pi.colors.success else Pi.colors.ink2)
                            if (s.scope == "global") IconAction(Icons.Rounded.Delete, "Remove ${s.name}", onClick = { removing = s })
                        }
                        if (s.tools.isNotEmpty()) {
                            Text(s.tools.joinToString(", "), style = Pi.type.monoMeta, color = Pi.colors.ink3, maxLines = 3, modifier = Modifier.padding(start = 54.dp, end = 20.dp, bottom = 6.dp))
                        }
                    }
                    for (e in r.errors) Text(e, style = Pi.type.meta, color = Pi.colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                    r.note?.let { Text(it, style = Pi.type.meta, color = Pi.colors.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) }
                }
            }
            if (!adding) {
                SecondaryButton("Add a server", onClick = { adding = true }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            } else {
                F2Section("New server")
                val badName = name.isNotEmpty() && !McpServers.validName(name)
                PiTextField(name, { name = it }, placeholder = "Name (letters, digits, - and _)", singleLine = true, isError = badName, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = "Server name" })
                if (badName) FieldHint("Use letters, digits, - and _ only", isError = true, modifier = Modifier.padding(horizontal = 16.dp))
                PiTextField(
                    target,
                    { target = it },
                    placeholder = "https://example.com/mcp, or a command",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { contentDescription = "Server address or command" },
                )
                if (target.trim().startsWith("http")) {
                    PiTextField(tokenEnv, { tokenEnv = it }, placeholder = "Optional: bearer token environment variable", singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                val args = McpServers.addArgs(name, target, tokenEnv)
                ButtonRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SecondaryButton("Cancel", onClick = {
                        adding = false
                        name = ""
                        target = ""
                        tokenEnv = ""
                    })
                    PrimaryButton("Add", onClick = {
                        args?.let {
                            run("Adding $name", it, "Added $name") {
                                adding = false
                                name = ""
                                target = ""
                                tokenEnv = ""
                            }
                        }
                    }, enabled = args != null && working == null)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    removing?.let { s ->
        ConfirmSheet(
            title = "Remove ${s.name}?",
            message = "Its sign-in, if any, is kept.",
            confirmLabel = "Remove",
            onConfirm = {
                removing = null
                run("Removing ${s.name}", listOf("mcp", "remove", s.name), "Removed ${s.name}")
            },
            onDismiss = { removing = null },
        )
    }
}
