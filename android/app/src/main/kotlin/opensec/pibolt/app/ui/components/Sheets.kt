package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.style.TextOverflow
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * Closes the enclosing [PiSheet] with its exit animation; [SheetHeader] draws its glass close button from it. Null
 * outside a sheet.
 */
val LocalSheetClose = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The app's bottom sheet, the dialog layer of the redesign: one surface (surface1) with a 1dp lineStrong ring along
 * its top and sides, [Radius.pill] (28dp) top corners, the theme scrim, no tonal elevation or shadow.
 * Predictive back comes from ModalBottomSheet, which also lifts the whole sheet above the keyboard. The sheet never
 * grows under the status bar: a tall sheet (or one pushed up by the keyboard) stops below it, and its lists shrink.
 * The drag handle is drawn here so TalkBack can close the sheet from it. Content is padded for the navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiSheet(
    onDismiss: () -> Unit,
    state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Pi.colors
    val close = rememberSheetCloser(state)
    val statusBars = WindowInsets.statusBars
    val shape = RoundedCornerShape(topStart = Radius.pill, topEnd = Radius.pill)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = colors.surface1,
        contentColor = colors.ink1,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        shape = shape,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // Keep the top edge below the status bar (the sheet window spans the whole screen).
                    val max = if (constraints.hasBoundedHeight) (constraints.maxHeight - statusBars.getTop(this)).coerceAtLeast(0) else constraints.maxHeight
                    val placeable = measurable.measure(constraints.copy(maxHeight = max, minHeight = constraints.minHeight.coerceAtMost(max)))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .drawWithContent {
                    drawContent()
                    sheetRing(colors.lineStrong, Radius.pill.toPx())
                }
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Drag handle"
                        onClick(label = "Close") {
                            close(onDismiss)
                            true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.padding(top = Space.sm + Space.xxs, bottom = Space.xs).size(width = 36.dp, height = 5.dp).background(colors.ink4, CircleShape))
            }
            CompositionLocalProvider(LocalSheetClose provides { close(onDismiss) }) { content() }
        }
    }
}

/** The sheet's 1dp ring: down the sides and round the top corners; the bottom edge is the screen's. */
private fun DrawScope.sheetRing(color: Color, radius: Float) {
    val w = 1.dp.toPx()
    val inset = w / 2
    val r = (radius - inset).coerceAtLeast(0f)
    val path = Path().apply {
        moveTo(inset, size.height)
        lineTo(inset, inset + r)
        arcTo(Rect(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f, false)
        lineTo(size.width - inset - r, inset)
        arcTo(Rect(size.width - inset - 2 * r, inset, size.width - inset, inset + 2 * r), 270f, 90f, false)
        lineTo(size.width - inset, size.height)
    }
    drawPath(path, color, style = Stroke(w))
}

/** Hide [state] with its exit animation, then run [then]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetCloser(state: SheetState): (then: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { then ->
        scope.launch { state.hide() }.invokeOnCompletion { if (state.currentValue == SheetValue.Hidden || !state.isVisible) then() }
    }
}

/**
 * A sheet's header: the [title] in largeTitle (a heading for TalkBack) with an optional [subtitle] (body ink2) under
 * it. A glass back button leads when [onBack] is given ([backLabel] for TalkBack); a glass close button ends the row
 * inside a [PiSheet] unless [closable] is false. [leading] sits before the title (a provider's mark).
 */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    closable: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = Pi.colors
    val close = LocalSheetClose.current.takeIf { closable }
    Column(modifier.fillMaxWidth().padding(start = if (onBack != null) Space.sm else Space.page, end = if (close != null) Space.sm else Space.page, top = Space.xs, bottom = Space.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (onBack != null) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, onClick = onBack, size = SHEET_BUTTON)
                Spacer(Modifier.width(Space.xs))
            }
            Row(Modifier.weight(1f).heightIn(min = 48.dp).padding(top = (48.dp - 34.dp) / 2), verticalAlignment = Alignment.Top) {
                if (leading != null) {
                    Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) { leading() }
                    Spacer(Modifier.width(Space.md))
                }
                Text(title, style = Pi.type.largeTitle, color = colors.ink1, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            }
            if (close != null) {
                Spacer(Modifier.width(Space.sm))
                GlassIconButton(Icons.Rounded.Close, "Close", onClick = close, size = SHEET_BUTTON, tint = colors.ink2)
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = Pi.type.body,
                color = colors.ink2,
                modifier = Modifier.padding(start = if (onBack != null) Space.page - Space.sm else 0.dp, end = if (close != null) Space.page - Space.sm else 0.dp, top = Space.xs),
            )
        }
    }
}

/** A sheet's glass back and close buttons: 36dp circles in 48dp targets. */
private val SHEET_BUTTON = 36.dp

/** A yes/no sheet for destructive or consequential actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val haptics = Pi.haptics
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(title, subtitle = message, closable = false)
        Spacer(Modifier.height(Space.xl))
        ButtonRow(Modifier.padding(horizontal = Space.page)) {
            SecondaryButton("Cancel", onClick = { close(onDismiss) })
            if (destructive) {
                DangerButton(confirmLabel, onClick = {
                    haptics.confirm()
                    close(onConfirm)
                })
            } else {
                PrimaryButton(confirmLabel, onClick = {
                    haptics.confirm()
                    close(onConfirm)
                })
            }
        }
    }
}
