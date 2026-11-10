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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/**
 * First run on Home, drawn on the canvas under the title (no card): two numbered steps, connect a model, then create a
 * project, and under them the next step's primary button with the other ways in as quiet text actions. A project is
 * never the first thing offered while no model can answer. [modelName] is the connected provider's name, or null when
 * none is connected. Everything sits on the 20dp page gutter.
 */
@Composable
fun SetupCard(modelName: String?, onConnect: () -> Unit, onNewProject: () -> Unit, onClone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val connected = modelName != null
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.page)) {
            SetupStep(
                number = 1,
                label = "Connect a model",
                detail = modelName,
                done = connected,
                current = !connected,
            )
            SetupStep(
                number = 2,
                label = "Create a project",
                detail = null,
                done = false,
                current = connected,
            )
        }
        Spacer(Modifier.height(Space.lg))
        PrimaryButton(
            if (connected) "New project" else "Connect a model",
            onClick = if (connected) onNewProject else onConnect,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page),
        )
        Spacer(Modifier.height(Space.xs))
        // The other ways in: text actions whose labels start on the column (a TextAction pads its label 12dp).
        Row(Modifier.padding(start = Space.page - Space.md)) {
            if (!connected) TextAction("New project", onClick = onNewProject, color = colors.ink2)
            TextAction("Clone from git", onClick = onClone, color = colors.ink2)
        }
    }
}

/** 56dp step row: a 28dp numbered disc (a check when done), 12dp, the label (body) and, once done, what it holds. */
@Composable
private fun SetupStep(number: Int, label: String, detail: String?, done: Boolean, current: Boolean) {
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
            .clearAndSetSemantics { contentDescription = listOfNotNull("Step $number", label, state, detail).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // As in the desktop onboarding: the current step is the accent disc, a finished one surface3 with a check in
        // ink1, a later one only a ring. Green stays for state, not progress.
        Box(
            Modifier
                .size(28.dp)
                .background(if (current) colors.accent else if (done) colors.surface3 else colors.canvas, CircleShape)
                .then(if (!current && !done) Modifier.border(1.dp, colors.lineStrong, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(16.dp))
            } else {
                Text(number.toString(), style = type.label, color = if (current) colors.onAccent else colors.ink3)
            }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = type.body, color = if (done || current) colors.ink1 else colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
