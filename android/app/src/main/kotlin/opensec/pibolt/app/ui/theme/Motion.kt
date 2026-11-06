package opensec.pibolt.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/** Motion tokens from DESIGN.md. Durations in ms; exits run at 0.7x of the enter duration. */
object PiMotion {
    const val TAP = 90
    const val QUICK = 150
    const val STANDARD = 220
    const val SHEET = 300
    const val SETTLE = 400

    fun exit(enterMs: Int): Int = enterMs * 7 / 10

    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val InPlace = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enter(ms: Int, delay: Int = 0): TweenSpec<T> = tween(ms, delay, Enter)

    fun <T> leave(enterMs: Int): TweenSpec<T> = tween(exit(enterMs), 0, Exit)

    fun <T> inPlace(ms: Int = STANDARD): TweenSpec<T> = tween(ms, 0, InPlace)

    /** Drags and settles. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> fast(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Colours and alpha: never overshoot. */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
}

/** Per-window motion state: [reduced] is the in-app toggle or the system "Remove animations" setting. */
@Immutable
data class MotionPrefs(val reduced: Boolean)
