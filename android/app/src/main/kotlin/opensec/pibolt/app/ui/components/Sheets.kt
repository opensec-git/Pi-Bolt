package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * The app's bottom sheet, the dialog layer of the desktop app's elevation model: one surface (surface1) with a 1dp
 * lineStrong ring along its top and sides, Radius.xl top corners, the theme scrim, no tonal elevation or shadow.
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
    val shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl)
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
                    sheetRing(colors.lineStrong, Radius.xl.toPx())
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
                Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).background(colors.ink4, CircleShape))
            }
            content()
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

/** Sheet title (a heading for TalkBack) with an optional subtitle under it. */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = Pi.type.title, color = Pi.colors.ink1, modifier = Modifier.semantics { heading() })
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = Pi.type.body, color = Pi.colors.ink2)
        }
    }
}

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
        SheetHeader(title, subtitle = message)
        Spacer(Modifier.height(20.dp))
        ButtonRow(Modifier.padding(horizontal = 20.dp)) {
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
