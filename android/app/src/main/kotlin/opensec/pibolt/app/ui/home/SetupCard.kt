package opensec.pibolt.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * First run on Home: "Set up Pi-Bolt" with two numbered steps, connect a model, then create a project. Only the next
 * step is a primary button, so a project is never the first thing offered while no model can answer. [modelName] is
 * the connected provider's name, or null when none is connected.
 */
@Composable
fun SetupCard(modelName: String?, onConnect: () -> Unit, onNewProject: () -> Unit, onClone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.lg)
    val connected = modelName != null
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(20.dp),
    ) {
        Text("Set up Pi-Bolt", style = type.title, color = colors.ink1, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Text(
            "Pi works in a project folder on this phone and calls a model with your key.",
            style = type.body,
            color = colors.ink2,
        )
        Spacer(Modifier.height(12.dp))
        SetupStep(
            number = 1,
            label = "Connect a model",
            detail = modelName?.let { "Connected: $it" } ?: "An API key or a custom endpoint",
            done = connected,
            current = !connected,
        )
        SetupStep(
            number = 2,
            label = "Create a project",
            detail = "A folder Pi reads, edits and runs commands in",
            done = false,
            current = connected,
        )
        Spacer(Modifier.height(16.dp))
        if (!connected) {
            PrimaryButton("Connect a model", onClick = onConnect, icon = Icons.Rounded.Key, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            ButtonRow {
                SecondaryButton("New project", onClick = onNewProject)
                SecondaryButton("Clone from git", onClick = onClone)
            }
        } else {
            PrimaryButton("New project", onClick = onNewProject, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Clone from git", onClick = onClone, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 56dp step row: a numbered disc (a success check when done), the label and one line under it. */
@Composable
private fun SetupStep(number: Int, label: String, detail: String, done: Boolean, current: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    val state = when {
        done -> "done"
        current -> "next step"
        else -> "not started"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clearAndSetSemantics { contentDescription = "Step $number, $label, $state. $detail" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // As in the desktop onboarding: the current step is the accent disc, a finished one surface3 with a check in
        // ink1, a later one only a ring. Green stays for state, not progress.
        Box(
            Modifier
                .size(28.dp)
                .background(if (current) colors.accent else if (done) colors.surface3 else colors.surface1, CircleShape)
                .then(if (!current && !done) Modifier.border(1.dp, colors.lineStrong, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(16.dp))
            } else {
                Text(number.toString(), style = type.label, color = if (current) colors.onAccent else colors.ink3)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = type.bodyStrong, color = if (done || current) colors.ink1 else colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = type.meta, color = if (done) colors.ink2 else colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
