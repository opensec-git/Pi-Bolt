package opensec.pibolt.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/** The navigation host's shared-transition scope, and the destination's own visibility scope. */
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

object SharedKeys {
    fun card(hostId: String) = "host-card-$hostId"

    fun name(hostId: String) = "host-name-$hostId"

    fun dot(hostId: String) = "host-dot-$hostId"

    fun project(name: String) = "project-card-$name"

    fun projectName(name: String) = "project-name-$name"
}

/** Identical content shared between the host card and the session top bar (name, status). */
@Composable
fun Modifier.sharedHostElement(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    if (Pi.reduceMotion) return this
    return with(shared) {
        this@sharedHostElement.sharedElement(rememberSharedContentState(key), nav, boundsTransform = { _, _ -> tween(PiMotion.SHEET, easing = PiMotion.Enter) })
    }
}

/** The host card's container becoming the session screen. */
@Composable
fun Modifier.sharedHostBounds(key: String, card: Boolean): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    if (Pi.reduceMotion) return this
    return with(shared) {
        this@sharedHostBounds.sharedBounds(
            rememberSharedContentState(key),
            nav,
            enter = fadeIn(tween(PiMotion.SHEET, easing = PiMotion.Enter)),
            exit = fadeOut(tween(PiMotion.exit(PiMotion.SHEET), easing = PiMotion.Exit)),
            boundsTransform = { _, _ -> tween(PiMotion.SHEET, easing = PiMotion.Enter) },
            clipInOverlayDuringTransition = OverlayClip(if (card) RoundedCornerShape(Radius.md) else RectangleShape),
        )
    }
}
