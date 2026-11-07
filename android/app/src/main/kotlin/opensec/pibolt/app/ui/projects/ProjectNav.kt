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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Difference
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
import opensec.pibolt.app.ui.components.SOFT_PRESS
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Space

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
 * The project's bottom bar: Sessions, Files, Changes, Terminal on one floating glass capsule (radius full, glass),
 * 20dp from the window's sides, 8dp above the navigation bar, at least 64dp tall (it grows with large text). Each item
 * is a 22dp glyph over its label (metaStrong ink1 selected, meta ink3 otherwise); the selected one sits on a soft
 * surface3 capsule that crossfades. Changes carries the changed-file count (accent, micro), Sessions and Terminal an
 * 8dp success dot while something runs there.
 */
@Composable
fun ProjectNavBar(selected: ProjectTab, badges: ProjectBadges, onSelect: (ProjectTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .navigationBarsPadding()
            .padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.sm),
    ) {
        // A minimum height, so labels at a large font scale grow the bar instead of being cut; one selectable group, so
        // TalkBack reads "Tab, 2 of 4".
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .glass(CircleShape, colors)
                .padding(horizontal = Space.xs, vertical = Space.xs)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                shape = CircleShape,
                pressedScale = SOFT_PRESS,
                role = Role.Tab,
                label = tab.label,
            )
            .background(pill, CircleShape)
            .semantics {
                selected = on
                state?.let { stateDescription = it }
            }
            .padding(vertical = Space.xs + Space.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 26.dp), contentAlignment = Alignment.Center) {
                Icon(tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(IconSize.lg))
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
                        .offset(x = 4.dp, y = (-2).dp)
                        .background(colors.surface2, CircleShape)
                        .padding(1.5.dp)
                        .background(colors.accent, CircleShape)
                        .widthIn(min = 16.dp)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            } else if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 1.dp)
                        .size(10.dp)
                        .background(colors.surface2, CircleShape)
                        .padding(2.dp)
                        .background(colors.success, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(Space.xxs))
        Text(tab.label, style = if (on) Pi.type.metaStrong else Pi.type.meta, color = ink, maxLines = 1)
    }
}

// One icon family (DESIGN.md): the selected tab is told by its pill and ink, not a filled glyph.
private val ProjectTab.icon: ImageVector get() = when (this) {
    ProjectTab.Sessions -> Icons.AutoMirrored.Rounded.Chat
    ProjectTab.Files -> Icons.Rounded.Folder
    ProjectTab.Changes -> Icons.Rounded.Difference
    ProjectTab.Terminal -> Icons.Rounded.Terminal
}
