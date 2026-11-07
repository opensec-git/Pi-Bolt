package opensec.pibolt.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/** The project's terminals full screen, above the keyboard. */
@Composable
fun TerminalScreen(app: PiBoltApp, project: File, onBack: () -> Unit, onPreview: (Int) -> Unit) {
    Box(Modifier.fillMaxSize().background(Pi.colors.canvas)) {
        TerminalPane(
            app = app,
            project = project,
            fullScreen = true,
            onToggleFullScreen = onBack,
            onPreview = onPreview,
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        )
    }
}
