package opensec.pibolt.app.ui.dev

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.theme.Pi

/**
 * DEBUG ONLY (Settings > About, tap Version five times): drives [FakeEngine] for screenshots and manual checks.
 * Delete with ui/dev/.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevSheet(app: PiBoltApp, onDismiss: () -> Unit) {
    val fake = app.engine as? FakeEngine
    PiSheet(onDismiss = onDismiss) {
        SheetHeader("Developer", subtitle = if (fake != null) "Fake engine scenarios" else "The real engine is active: nothing to change here.")
        if (fake == null) return@PiSheet
        val scenario by fake.scenario.collectAsStateWithLifecycle()
        val configured by fake.auth.configured.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Label("Engine status")
            Segmented(FakeEngine.Scenario.entries.map { it to it.label }, scenario, onSelect = fake::setScenario, modifier = Modifier.fillMaxWidth(), height = 40.dp)
            Label("Providers")
            Segmented(
                listOf(false to "None", true to "Anthropic + OpenRouter"),
                configured.isNotEmpty(),
                onSelect = { fake.auth.replace(if (it) setOf("anthropic", "openrouter") else emptySet()) },
                modifier = Modifier.fillMaxWidth(),
                height = 40.dp,
            )
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Create a 10,000-file project", onClick = fake::createLargeProject, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Show the first-run sheet again", onClick = { ProjectStore.get(app).resetProviderPrompt() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink3, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}
