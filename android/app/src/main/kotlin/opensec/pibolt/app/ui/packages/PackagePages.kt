package opensec.pibolt.app.ui.packages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.engine.PageEngine
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.ErrorState
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.WorkingLine
import opensec.pibolt.app.ui.session.views.ViewButtons
import opensec.pibolt.app.ui.session.views.ViewHostUi
import opensec.pibolt.app.ui.session.views.ViewNodes
import opensec.pibolt.app.ui.session.views.ViewSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.DialogKind

/**
 * A package's page without a session (docs/mobile-ui.md#package-pages-and-home-tiles): opened on the page engine
 * while this sheet shows ([PageEngine.open]), closed with it ([PageEngine.close]); its events go to the page engine. A
 * sheet the page opens (`showView`) shows over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagePageSheet(pages: PageEngine, key: String, title: String, onClose: () -> Unit) {
    val state by pages.state.collectAsStateWithLifecycle()
    val status by pages.status.collectAsStateWithLifecycle()
    var failed by remember(key) { mutableStateOf(false) }
    val host = remember(pages) { ViewHostUi({ pages.state.value.views }) { k, node, kind, value, values -> pages.event(k, node, kind, value, values) } }
    LaunchedEffect(key) { failed = !pages.open(key) }
    DisposableEffect(key) { onDispose { pages.close(key) } }
    val entry = state.views[key]
    PiSheet(onDismiss = onClose) {
        val view = entry?.view
        SheetHeader(view?.title ?: title, subtitle = view?.subtitle)
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            when {
                view != null -> ViewNodes(view.body, host.fields(key), host.actions(key))
                failed -> ErrorState(
                    "Could not open the page",
                    message = when (val s = status) {
                        is PageEngine.Status.LowMemory -> "The phone is low on memory. Close other apps and try again."
                        is PageEngine.Status.Failed -> s.message
                        else -> "The package did not answer."
                    },
                )
                else -> WorkingLine("Opening", mode = QuadMode.Run)
            }
            Spacer(Modifier.height(Space.sm))
        }
        if (view != null && view.actions.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            ViewButtons(view.actions, host.actions(key), Modifier.padding(horizontal = Space.page), size = ButtonSize.Large)
        }
        if (view == null && !failed) Text("Starting the package without a session", style = Pi.type.meta, color = Pi.colors.ink3, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(Space.lg))
    }
    state.dialogs.firstOrNull { it.kind == DialogKind.View }?.let { dialog ->
        ViewSheet(
            dialog,
            onAnswer = { node, values -> pages.answerSheet(dialog.id, node, values) },
            onCancel = { pages.cancelSheet(dialog.id) },
            onEvent = { k, node, kind, value, values -> pages.event(k, node, kind, value, values) },
        )
    }
}
