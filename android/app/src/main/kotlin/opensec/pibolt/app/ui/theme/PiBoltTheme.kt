package opensec.pibolt.app.ui.theme

import android.app.Activity
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.Density
import opensec.pibolt.app.data.ReduceMotion
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.components.QuadMath

/** Sizes that change with the density setting. */
@Immutable
data class PiDims(val compact: Boolean) {
    /** The minimum height of a list row on Home and in a project (grouped rows, computer cards); text can grow it. */
    val homeRow: Dp get() = if (compact) 48.dp else 56.dp
    // Never under 48dp: an option row is one touch target.
    val optionRow: Dp get() = if (compact) 48.dp else 52.dp
    val turnGap: Dp get() = if (compact) 14.dp else 20.dp
    val stepGap: Dp get() = if (compact) 4.dp else 6.dp
    val gutter: Dp get() = 16.dp
}

/**
 * Corner radii (DESIGN.md "Space, shape, size"), Pi-Bolt Desktop's 4 / 6 / 10 / 14 by role: xs tags and inline code
 * (--radius-xs), sm chips and small buttons (--radius-sm), md cards, menus, snackbars and rows (--radius-lg), lg the
 * composer, user bubble and groups (--radius-xl), xl sheets (a dialog's --radius-xl).
 */
object Radius {
    val xs = 4.dp
    val sm = 6.dp
    val md = 10.dp
    val lg = 14.dp
    val xl = 14.dp

    /** Status tiles and the redesign's media cards. */
    val tile = 16.dp

    /** The user's message bubble in the redesign. */
    val bubble = 22.dp

    /** The pill composer: a full round at its one-line height (56dp), kept as the field grows. */
    val pill = 28.dp

    /** Rich media in the transcript: images, preview cards. */
    val media = 12.dp
}

/** The spacing scale. Side gutter is [lg] (16dp); sheets and headers inset their text by [xl] (20dp). */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val x3 = 32.dp
    val x4 = 40.dp

    /** The redesign's side gutter: lists, tiles and the composer pill sit this far from the window's edges. */
    val page = 20.dp
}

/**
 * Icon sizes, one Rounded family. [xs] 14dp beside meta text, [sm] 16dp beside labels and in small buttons, [md] 20dp
 * leading glyphs in rows, notices and buttons, [lg] 22dp toolbar actions ([IconAction]'s default), [hero] 32dp empty
 * and permission states.
 */
object IconSize {
    val xs = 14.dp
    val sm = 16.dp
    val md = 20.dp
    val lg = 22.dp
    val hero = 32.dp
}

/**
 * One animation clock per window for every Quad spinner, quantised to 50 ms (20 fps). Spinners read [time] only in
 * the draw phase, so a tick redraws them without recomposing anything. The clock runs only while the window is
 * started and at least one spinner is attached; at most [MAX_ANIMATED] spinners animate, the rest draw a still frame.
 */
@Stable
class QuadClock {
    val time = mutableLongStateOf(0L)
    private val tickets = mutableStateListOf<Int>()
    private var next = 0

    val active: Boolean get() = tickets.isNotEmpty()

    fun attach(): Int {
        val t = next++
        tickets.add(t)
        return t
    }

    fun detach(ticket: Int) {
        tickets.remove(ticket)
    }

    fun canAnimate(ticket: Int): Boolean = tickets.indexOf(ticket) in 0 until MAX_ANIMATED

    companion object {
        const val MAX_ANIMATED = 8
    }
}

val LocalPiColors = staticCompositionLocalOf { PiColors.of(ThemeId.Graphite, AccentId.Ink) }
val LocalPiType = staticCompositionLocalOf { PiType.build(compact = false) }
val LocalPiDims = staticCompositionLocalOf { PiDims(compact = false) }
val LocalMotion = staticCompositionLocalOf { MotionPrefs(reduced = false) }
val LocalHaptics = staticCompositionLocalOf { Haptics(null, false) }
val LocalQuadClock = staticCompositionLocalOf { QuadClock() }
val LocalSettings = staticCompositionLocalOf { AppSettings() }

/** Shorthand for the theme's locals. */
object Pi {
    val colors: PiColors @Composable get() = LocalPiColors.current
    val type: PiType @Composable get() = LocalPiType.current
    val dims: PiDims @Composable get() = LocalPiDims.current
    val reduceMotion: Boolean @Composable get() = LocalMotion.current.reduced
    val haptics: Haptics @Composable get() = LocalHaptics.current
    val settings: AppSettings @Composable get() = LocalSettings.current
}

@Composable
fun PiBoltTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val colors = remember(settings.theme, settings.accent) { PiColors.of(settings.theme, settings.accent) }
    val compact = settings.density == Density.Compact
    val type = remember(compact) { PiType.build(compact) }
    val systemReduced = rememberSystemReducedMotion()
    val reduced = settings.reduceMotion == ReduceMotion.On || systemReduced
    val view = LocalView.current
    val haptics = remember(view, settings.haptics) { Haptics(view, settings.haptics) }
    val clock = remember { QuadClock() }
    RunQuadClock(clock)

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.setBackgroundDrawable(colors.canvas.toArgb().toDrawable())
        // Dark icons on a light theme (Paper), light icons on every dark one.
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = colors.isLight
            isAppearanceLightNavigationBars = colors.isLight
        }
    }

    val scheme = remember(colors) {
        // Start from Material's light or dark scheme so roles not set here have the right polarity.
        (if (colors.isLight) lightColorScheme() else darkColorScheme()).copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.surface3,
            onPrimaryContainer = colors.ink1,
            secondary = colors.ink2,
            onSecondary = colors.canvas,
            secondaryContainer = colors.surface3,
            onSecondaryContainer = colors.ink1,
            tertiary = colors.ink2,
            onTertiary = colors.canvas,
            tertiaryContainer = colors.surface3,
            onTertiaryContainer = colors.ink1,
            background = colors.canvas,
            onBackground = colors.ink1,
            surface = colors.canvas,
            onSurface = colors.ink1,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.ink2,
            surfaceTint = Color.Transparent,
            inverseSurface = colors.ink1,
            inverseOnSurface = colors.canvas,
            inversePrimary = colors.canvas,
            error = colors.danger,
            onError = colors.canvas,
            errorContainer = colors.dangerSoft,
            onErrorContainer = colors.danger,
            outline = colors.lineStrong,
            outlineVariant = colors.line,
            scrim = Color.Black,
            surfaceBright = colors.surface3,
            surfaceDim = colors.canvas,
            surfaceContainerLowest = colors.canvas,
            surfaceContainerLow = colors.surface1,
            surfaceContainer = colors.surface1,
            surfaceContainerHigh = colors.surface2,
            surfaceContainerHighest = colors.surface3,
        )
    }
    val typography = remember(type) {
        Typography(
            displaySmall = type.display,
            headlineSmall = type.title,
            titleLarge = type.title,
            titleMedium = type.subhead,
            titleSmall = type.label,
            bodyLarge = type.body,
            bodyMedium = type.body,
            bodySmall = type.meta,
            labelLarge = type.label,
            labelMedium = type.metaStrong,
            labelSmall = type.micro,
        )
    }
    val shapes = remember {
        Shapes(
            extraSmall = RoundedCornerShape(Radius.xs),
            small = RoundedCornerShape(Radius.sm),
            medium = RoundedCornerShape(Radius.md),
            large = RoundedCornerShape(Radius.lg),
            extraLarge = RoundedCornerShape(Radius.xl),
        )
    }
    val selection = remember(colors) { TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = SELECTION_ALPHA)) }

    CompositionLocalProvider(
        LocalPiColors provides colors,
        LocalPiType provides type,
        LocalPiDims provides PiDims(compact),
        LocalMotion provides MotionPrefs(reduced),
        LocalHaptics provides haptics,
        LocalQuadClock provides clock,
        LocalSettings provides settings,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
            CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
        }
    }
}

@Composable
private fun RunQuadClock(clock: QuadClock) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { clock.active }.collectLatest { active ->
                if (!active) return@collectLatest
                val start = withFrameMillis { it }
                while (true) {
                    val now = withFrameMillis { it }
                    val elapsed = now - start
                    val quantised = elapsed / QuadMath.TICK_MS * QuadMath.TICK_MS
                    if (quantised != clock.time.longValue) clock.time.longValue = quantised
                    // Sleep until just before the next 50 ms step instead of waking every frame.
                    val wait = quantised + QuadMath.TICK_MS - elapsed - 8
                    if (wait > 0) delay(wait)
                }
            }
        }
    }
}

/** True while the system "Remove animations" setting (animator duration scale 0) is on. */
@Composable
private fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}
