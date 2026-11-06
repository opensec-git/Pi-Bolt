package opensec.pibolt.app.ui.diagram

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import opensec.pibolt.app.ui.components.CodeBlock

/**
 * A ```mermaid fence. Stub until the native diagram renderer lands (branch android/diagrams replaces this file): the
 * source as a code block headed "Diagram".
 */
@Composable
fun MermaidBlock(code: String, modifier: Modifier = Modifier) {
    CodeBlock(code, "mermaid", "", closed = true, onOpen = null, modifier = modifier, title = "Diagram")
}
