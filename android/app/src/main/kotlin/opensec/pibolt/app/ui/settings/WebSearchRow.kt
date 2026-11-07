package opensec.pibolt.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.ui.graphics.Color
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.LocalEngine
import opensec.pibolt.app.ui.components.GroupSwitchRow

/**
 * "Web search" in Settings > Agent: the bundled web tools in on-device chats and projects (AppSettings.webSearch,
 * engine/ChatSpace.kt). A change stops the idle engines, so the next session starts with it; open sessions keep theirs.
 */
internal fun webSearchRow(app: PiBoltApp, on: Boolean, tint: Color): SettingsRow = { divider ->
    GroupSwitchRow(
        "Web search",
        on,
        { value ->
            app.settings.update { it.copy(webSearch = value) }
            (app.engine as? LocalEngine)?.stopIdleEngines("web search turned ${if (value) "on" else "off"}")
        },
        meta = "Searches go to Exa",
        icon = Icons.Rounded.Public,
        iconTint = tint,
        divider = divider,
    )
}
