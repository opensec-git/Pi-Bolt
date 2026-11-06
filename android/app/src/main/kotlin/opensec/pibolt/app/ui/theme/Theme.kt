package opensec.pibolt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFFFFC53D),
    onPrimary = Color(0xFF2B1D00),
    primaryContainer = Color(0xFF3A2C05),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF8AB4FF),
    onSecondary = Color(0xFF0B1E44),
    secondaryContainer = Color(0xFF1C2A44),
    onSecondaryContainer = Color(0xFFD5E3FF),
    tertiary = Color(0xFF5EE0AE),
    onTertiary = Color(0xFF00291B),
    tertiaryContainer = Color(0xFF0F3A2B),
    onTertiaryContainer = Color(0xFFB4F5DA),
    error = Color(0xFFFF7A72),
    onError = Color(0xFF3B0907),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1F2530),
    onSurfaceVariant = Color(0xFF9AA3B2),
    surfaceTint = Color(0xFFFFC53D),
    inverseSurface = Color(0xFFE6E9EF),
    inverseOnSurface = Color(0xFF1A1D23),
    inversePrimary = Color(0xFF8A5A00),
    outline = Color(0xFF3A4352),
    outlineVariant = Color(0xFF252C38),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A303B),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF13171E),
    surfaceContainer = Color(0xFF171C24),
    surfaceContainerHigh = Color(0xFF1D232D),
    surfaceContainerHighest = Color(0xFF252C37),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3A3),
    onPrimaryContainer = Color(0xFF2B1D00),
    secondary = Color(0xFF2F5BD3),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE6FF),
    onSecondaryContainer = Color(0xFF0B1E44),
    tertiary = Color(0xFF0B7A55),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9F2DF),
    onTertiaryContainer = Color(0xFF00291B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFAF7),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFECE9E2),
    onSurfaceVariant = Color(0xFF5D6470),
    surfaceTint = Color(0xFF8A5A00),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF2F0EB),
    inversePrimary = Color(0xFFFFC53D),
    outline = Color(0xFFC9C4B9),
    outlineVariant = Color(0xFFE4E0D7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E1DA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4EF),
    surfaceContainer = Color(0xFFF1EEE8),
    surfaceContainerHigh = Color(0xFFEBE8E1),
    surfaceContainerHighest = Color(0xFFE5E2DA),
)

/** Colors outside Material's scheme: code, diffs, status. */
@Immutable
data class PiColors(
    val codeBackground: Color,
    val codeText: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
    val diffAdd: Color,
    val diffAddBackground: Color,
    val diffRemove: Color,
    val diffRemoveBackground: Color,
    val success: Color,
    val running: Color,
    val userBubble: Color,
    val onUserBubble: Color,
)

private val DarkPi = PiColors(
    codeBackground = Color(0xFF0A0D12),
    codeText = Color(0xFFD7DCE5),
    keyword = Color(0xFFC792EA),
    string = Color(0xFFA5E075),
    comment = Color(0xFF6B7587),
    number = Color(0xFFF78C6C),
    function = Color(0xFF82AAFF),
    diffAdd = Color(0xFF7EE2A8),
    diffAddBackground = Color(0x2236C47A),
    diffRemove = Color(0xFFFF8F87),
    diffRemoveBackground = Color(0x22E5534B),
    success = Color(0xFF5EE0AE),
    running = Color(0xFFFFC53D),
    userBubble = Color(0xFF232A36),
    onUserBubble = Color(0xFFEDEFF4),
)

private val LightPi = PiColors(
    codeBackground = Color(0xFFF4F2EE),
    codeText = Color(0xFF24292F),
    keyword = Color(0xFF8E3FBF),
    string = Color(0xFF1F7A34),
    comment = Color(0xFF8A919C),
    number = Color(0xFFB4501C),
    function = Color(0xFF2F5BD3),
    diffAdd = Color(0xFF16703A),
    diffAddBackground = Color(0x2236C47A),
    diffRemove = Color(0xFFB42318),
    diffRemoveBackground = Color(0x1FE5534B),
    success = Color(0xFF0B7A55),
    running = Color(0xFF8A5A00),
    userBubble = Color(0xFFEDE8DC),
    onUserBubble = Color(0xFF1A1C20),
)

val LocalPiColors = staticCompositionLocalOf { DarkPi }

val Mono: FontFamily = FontFamily.Monospace

private val baseline = Typography()

private val AppTypography = Typography(
    displaySmall = baseline.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = baseline.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = baseline.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = baseline.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = baseline.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = baseline.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    bodySmall = baseline.bodySmall.copy(lineHeight = 17.sp),
    labelLarge = baseline.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = baseline.labelMedium.copy(fontWeight = FontWeight.Medium),
)

/** Code and tool output. */
val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PiBoltTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalPiColors provides if (dark) DarkPi else LightPi) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object PiTheme {
    val colors: PiColors
        @Composable get() = LocalPiColors.current
}
