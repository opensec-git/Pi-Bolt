package opensec.pibolt.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.heading
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

private const val THEME_TILES_PER_ROW = 4

/**
 * Near-black themes look alike at tile size; their canvas hex goes under the name. Pi-Bolt (Graphite) is warm charcoal
 * since the redesign and reads apart without it.
 */
private val NEAR_BLACK = setOf(ThemeId.Contrast, ThemeId.Codex, ThemeId.Vercel)

/** Four tiles a row; an incomplete last row keeps the tile width. */
@Composable
fun ThemeGrid(selected: ThemeId, accent: AccentId, onSelect: (ThemeId) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (row in ThemeId.entries.chunked(THEME_TILES_PER_ROW)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (theme in row) {
                    ThemeTile(theme, accent, selected = selected == theme, onClick = { onSelect(theme) }, modifier = Modifier.weight(1f))
                }
                repeat(THEME_TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A theme preview drawn in that theme's own colours: a user bubble, a line of prose, a line of code in the theme's
 * syntax colours, an inline code chip, and the composer with the accent's send button.
 */
@Composable
private fun ThemeTile(theme: ThemeId, accent: AccentId, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = Pi.colors
    val c = remember(theme, accent) { PiColors.of(theme, accent) }
    val ring by animateColorAsState(if (selected) current.accent else current.line, PiMotion.effects(), label = "tile")
    val shape = RoundedCornerShape(Radius.md)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .semantics {
                    contentDescription = "${theme.label} theme"
                    this.selected = selected
                }
                .pressable(onClick, shape = shape, role = Role.RadioButton, label = "Use ${theme.label}")
                .border(if (selected) 1.5.dp else 1.dp, ring, shape)
                .padding(if (selected) 3.dp else 0.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) { drawThemePreview(c, (Radius.md - if (selected) 3.dp else 0.dp).toPx().coerceAtLeast(4f)) }
        }
        Spacer(Modifier.height(8.dp))
        // The tile itself carries the name for TalkBack; the captions are for the eye.
        Text(theme.label, style = Pi.type.meta, color = if (selected) current.ink1 else current.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
        if (theme in NEAR_BLACK) {
            Text(hex(c), style = Pi.type.monoMeta, color = current.ink3, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

private fun hex(c: PiColors): String = "#" + (c.canvas.toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

private fun DrawScope.drawThemePreview(c: PiColors, radius: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(c.canvas, cornerRadius = CornerRadius(radius))
    val pad = w * 0.12f
    val bar = h * 0.042f
    fun pill(color: Color, x: Float, y: Float, width: Float) =
        drawRoundRect(color, Offset(x, y), Size(width, bar), CornerRadius(bar / 2))
    // User bubble.
    drawRoundRect(c.surface2, Offset(w * 0.40f, pad), Size(w - w * 0.40f - pad, h * 0.12f), CornerRadius(6.dp.toPx()))
    pill(c.ink2, w * 0.40f + w * 0.06f, pad + h * 0.06f - bar / 2, w * 0.30f)
    // Prose, with an inline code chip.
    val prose = h * 0.33f
    pill(c.ink1.copy(alpha = 0.9f), pad, prose, w * 0.34f)
    drawRoundRect(c.surface3, Offset(pad + w * 0.38f, prose - bar * 0.6f), Size(w * 0.30f, bar * 2.2f), CornerRadius(3.dp.toPx()))
    pill(c.ink1.copy(alpha = 0.8f), pad + w * 0.42f, prose, w * 0.22f)
    // A code card: keyword, function and string in the theme's syntax colours.
    // Two code lines centred in the card, with the same inset on every side.
    val card = h * 0.43f
    val cardH = h * 0.20f
    val cardX = pad * 0.7f
    drawRoundRect(c.surface1, Offset(cardX, card), Size(w - pad * 1.4f, cardH), CornerRadius(5.dp.toPx()))
    drawRoundRect(c.line, Offset(cardX, card), Size(w - pad * 1.4f, cardH), CornerRadius(5.dp.toPx()), style = Stroke(1.dp.toPx()))
    val code1 = card + (cardH - bar * 3f) / 2
    val x0 = cardX + w * 0.06f
    pill(c.syntax.keyword, x0, code1, w * 0.14f)
    pill(c.syntax.function, x0 + w * 0.18f, code1, w * 0.22f)
    pill(c.syntax.string, x0 + w * 0.44f, code1, w * 0.26f)
    val code2 = code1 + bar * 2f
    pill(c.syntax.type, x0 + w * 0.08f, code2, w * 0.18f)
    pill(c.syntax.number, x0 + w * 0.30f, code2, w * 0.12f)
    // Composer and the accent send button.
    val composerTop = h * 0.76f
    val composerH = h * 0.16f
    drawRoundRect(c.surface1, Offset(pad * 0.7f, composerTop), Size(w - pad * 1.4f, composerH), CornerRadius(8.dp.toPx()))
    drawRoundRect(c.lineStrong, Offset(pad * 0.7f, composerTop), Size(w - pad * 1.4f, composerH), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
    val dot = composerH * 0.55f
    drawCircle(c.accent, dot / 2, Offset(w - pad * 0.7f - dot * 0.9f, composerTop + composerH / 2))
}

/** An accent choice. "Theme" is a split disc: the theme's own accent beside surface3, since it follows the theme. */
@Composable
fun AccentDot(accent: AccentId, theme: ThemeId, selected: Boolean, onClick: () -> Unit) {
    val current = Pi.colors
    val c = remember(theme, accent) { PiColors.of(theme, accent) }
    val ring by animateColorAsState(if (selected) current.accent else current.canvas.copy(alpha = 0f), PiMotion.effects(), label = "accent")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = if (accent == AccentId.Theme) "Theme's own accent" else "${accent.label} accent"
                    this.selected = selected
                }
                .pressable(onClick, shape = CircleShape, pressedScale = 0.92f, role = Role.RadioButton, label = "Use ${accent.label}")
                .border(1.5.dp, ring, CircleShape)
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (accent == AccentId.Theme) {
                    drawCircle(c.surface3)
                    drawArc(c.accent, startAngle = 90f, sweepAngle = 180f, useCenter = true)
                    drawCircle(current.lineStrong, style = Stroke(1.dp.toPx()))
                } else {
                    drawCircle(c.accent)
                }
            }
            val shown by animateFloatAsState(if (selected) 1f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "check")
            Box(
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        alpha = shown
                        scaleX = 0.6f + 0.4f * shown
                        scaleY = 0.6f + 0.4f * shown
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(c.accent) }
                Icon(Icons.Rounded.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(accent.label, style = Pi.type.meta, color = if (selected) current.ink1 else current.ink2, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * Appearance > Theme and Accent: one sheet with the theme tiles and the accent circles; a pick applies at once and
 * the sheet stays open, so the next one can be compared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(theme: ThemeId, accent: AccentId, onTheme: (ThemeId) -> Unit, onAccent: (AccentId) -> Unit, onDismiss: () -> Unit) {
    PiSheet(onDismiss = onDismiss) {
        SheetHeader("Theme")
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = Space.page)) {
            Spacer(Modifier.height(Space.sm))
            ThemeGrid(theme, accent, onSelect = onTheme)
            Spacer(Modifier.height(Space.xxl))
            Text("Accent", style = Pi.type.rowMeta, color = Pi.colors.ink3, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                for (a in AccentId.entries) AccentDot(a, theme, selected = accent == a, onClick = { onAccent(a) })
            }
            Spacer(Modifier.height(Space.lg))
        }
    }
}
