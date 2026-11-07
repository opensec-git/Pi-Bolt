package opensec.pibolt.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.ui.graphics.Color
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.LocalEngine
import opensec.pibolt.app.ui.components.GroupSwitchRow

/**
 * "Web search" in Settings > Agent: the bundled web tools in on-device chats and projects (AppSettings.webSearch,
 * engine/ChatSpace.kt). A change stops the idle engines and idle sessions on this phone, so the next one starts with
 * it (a busy session keeps its engine until it ends).
 */
internal fun webSearchRow(app: PiBoltApp, on: Boolean, tint: Color): SettingsRow = { divider ->
    GroupSwitchRow(
        "Web search",
        on,
        { value ->
            app.settings.update { it.copy(webSearch = value) }
            (app.engine as? LocalEngine)?.restartForSettings("web search turned ${if (value) "on" else "off"}")
        },
        // The privacy note only while searches can happen.
        meta = if (on) "Searches go to Exa" else null,
        icon = Icons.Rounded.Public,
        iconTint = tint,
        divider = divider,
    )
}
