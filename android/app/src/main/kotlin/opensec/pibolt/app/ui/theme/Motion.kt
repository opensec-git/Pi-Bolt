package opensec.pibolt.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/**
 * Motion tokens from DESIGN.md, Pi-Bolt Desktop's (`tokens.css`) mapped by role. Durations in ms. Small things leave in
 * [EXIT] (110 ms, a step faster than they came); sheets and screens leave at 0.7x of their entrance.
 */
object PiMotion {
    /** Press overlay and scale, colour (--dur-1). */
    const val TAP = 120

    /** Chevrons, chip select, icon swaps, crossfades, menus (--dur-2). */
    const val QUICK = 140

    /** Expand and collapse, message rise, folds (--dur-3). */
    const val STANDARD = 180

    /** Sheets, viewers, screen transitions (--dur-4, layout). */
    const val SHEET = 240

    /** Meters, counters, a value settling (--dur-slow). */
    const val SETTLE = 360

    /** A finished step's spinner turning into its check or cross (the desktop's pop-in, --dur-3). */
    const val GLYPH = 180

    /** Reduced motion: transitions become this short crossfade. */
    const val FADE = 120

    /** Half a period of the end-of-text caret pulse (1 s round trip, --dur-blink). */
    const val PULSE = 500

    /** The working hairline and a running card's top band (--dur-pulse). */
    const val SWEEP = 1400

    /** Anything small leaving (--dur-exit). */
    const val EXIT = 110

    /** A welcome's staggered entrance (assistant-ui's): the first element waits this long… */
    const val STAGGER = 120

    /** …and each later one this much more, up to [STAGGER_MAX_STEPS] steps, so the last never lags far behind. */
    const val STAGGER_STEP = 70

    const val STAGGER_MAX_STEPS = 8

    /** The delay of the [step]th element of a staggered entrance: 120 + step × 70 ms, capped at 8 steps. */
    fun stagger(step: Int): Int = STAGGER + step.coerceIn(0, STAGGER_MAX_STEPS) * STAGGER_STEP

    fun exit(enterMs: Int): Int = if (enterMs >= SHEET) enterMs * 7 / 10 else minOf(enterMs, EXIT)

    /** Things arriving (--ease-out). */
    val Enter = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Things leaving (--ease-in). */
    val Exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Things moving in place: a segment sliding, a fold resizing (--ease-in-out). */
    val InPlace = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

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
