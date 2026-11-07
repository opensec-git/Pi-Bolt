package opensec.pibolt.app.ui.projects

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.git.GitStatus
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion

/** The four places of a project. [index] is what the screen saves across rotation and process death. */
enum class ProjectTab(val index: Int, val label: String) {
    Sessions(0, "Sessions"),
    Files(1, "Files"),
    Changes(2, "Changes"),
    Terminal(3, "Terminal"),
    ;

    companion object {
        fun of(index: Int): ProjectTab = entries.firstOrNull { it.index == index } ?: Sessions
    }
}

/** What the project's bottom bar marks on each tab, so the other places say when they have something. */
@Immutable
data class ProjectBadges(
    /** Changed files in the repository ("4", "99+"), or null when clean or not a repository. */
    val changes: String? = null,
    /** A shell runs in this project. */
    val terminal: Boolean = false,
    /** This project's session is open and Pi works in it. */
    val sessions: Boolean = false,
)

/** Pure rules of the project's navigation (unit-tested). */
object ProjectNavRules {
    /** The count on the Changes tab: every changed path (staged, unstaged and conflicts), capped at "99+". */
    fun changesBadge(status: GitStatus?): String? {
        val n = status?.entries?.size ?: return null
        return when {
            n <= 0 -> null
            n > 99 -> "99+"
            else -> n.toString()
        }
    }

    /** TalkBack's state for a tab: what its badge says in words. */
    fun badgeDescription(tab: ProjectTab, badges: ProjectBadges): String? = when (tab) {
        ProjectTab.Sessions -> if (badges.sessions) "Pi is working" else null
        ProjectTab.Files -> null
        ProjectTab.Changes -> badges.changes?.let { if (it == "1") "1 changed file" else "$it changed files" }
        ProjectTab.Terminal -> if (badges.terminal) "Shell running" else null
    }

    /**
     * The bar steps aside while the keyboard is up (the terminal and the commit field keep the room) and while a
     * full-screen view (file, diff, history) covers the project.
     */
    fun showBar(imeVisible: Boolean, overlayOpen: Boolean): Boolean = !imeVisible && !overlayOpen

    /** Below this window height (landscape phones) the keyboard leaves room for only a few lines. */
    const val COMPACT_HEIGHT_DP = 480

    /**
     * Secondary chrome (the project's top bar over the terminal, the editor's tab strip) while typing: hidden when
     * the keyboard is up in a compact-height window, so the shell or the text keeps its lines.
     */
    fun showChrome(imeVisible: Boolean, windowHeightDp: Int): Boolean = !imeVisible || windowHeightDp >= COMPACT_HEIGHT_DP
}

/**
 * The project's bottom bar: Sessions, Files, Changes, Terminal. 64dp on surface1 with a 1dp line above; each item a
 * 22dp glyph in a 56x30 pill (surface3 when selected) over its label (meta; ink1 selected, ink3 otherwise). Changes
 * carries the changed-file count (accent, micro), Sessions and Terminal an 8dp dot while something runs there.
 */
@Composable
fun ProjectNavBar(selected: ProjectTab, badges: ProjectBadges, onSelect: (ProjectTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    Column(modifier.fillMaxWidth().background(colors.surface1)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            for (tab in ProjectTab.entries) {
                NavItem(
                    tab = tab,
                    on = tab == selected,
                    badge = if (tab == ProjectTab.Changes) badges.changes else null,
                    dot = (tab == ProjectTab.Sessions && badges.sessions) || (tab == ProjectTab.Terminal && badges.terminal),
                    state = ProjectNavRules.badgeDescription(tab, badges),
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(tab: ProjectTab, on: Boolean, badge: String?, dot: Boolean, state: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    val pill by animateColorAsState(if (on) colors.surface3 else colors.surface3.copy(alpha = 0f), PiMotion.effects(), label = "nav-pill")
    val ink = if (on) colors.ink1 else colors.ink3
    Column(
        modifier
            .heightIn(min = 56.dp)
            .pressable(
                {
                    if (!on) haptics.tick()
                    onClick()
                },
                shape = RoundedCornerShape(12.dp),
                pressedScale = 1f,
                role = Role.Tab,
                label = tab.label,
            )
            .semantics {
                selected = on
                state?.let { stateDescription = it }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 56.dp, height = 30.dp).background(pill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(tab.icon(on), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
            if (badge != null) {
                Text(
                    badge,
                    style = Pi.type.micro,
                    color = colors.onAccent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-4).dp)
                        .background(colors.surface1, CircleShape)
                        .padding(1.5.dp)
                        .background(colors.accent, CircleShape)
                        .widthIn(min = 16.dp)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            } else if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-8).dp, y = 2.dp)
                        .size(10.dp)
                        .background(colors.surface1, CircleShape)
                        .padding(2.dp)
                        .background(colors.success, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tab.label, style = if (on) Pi.type.metaStrong else Pi.type.meta, color = ink, maxLines = 1)
    }
}

private fun ProjectTab.icon(on: Boolean): ImageVector = when (this) {
    ProjectTab.Sessions -> if (on) Icons.AutoMirrored.Rounded.Chat else Icons.AutoMirrored.Outlined.Chat
    ProjectTab.Files -> if (on) Icons.Rounded.Folder else Icons.Outlined.Folder
    ProjectTab.Changes -> if (on) Icons.Rounded.Difference else Icons.Outlined.Difference
    ProjectTab.Terminal -> Icons.Rounded.Terminal
}
