package opensec.pibolt.app.ui.theme

import android.view.View
import androidx.compose.runtime.Stable
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

/** Semantic haptics through core's compat layer (falls back on old API levels). Never per streamed token. */
@Stable
class Haptics(private val view: View?, private val enabled: Boolean) {
    private fun play(constant: Int) {
        if (enabled && view != null) ViewCompat.performHapticFeedback(view, constant)
    }

    fun send() = play(HapticFeedbackConstantsCompat.CLOCK_TICK)

    fun confirm() = play(HapticFeedbackConstantsCompat.CONFIRM)

    fun reject() = play(HapticFeedbackConstantsCompat.REJECT)

    fun longPress() = play(HapticFeedbackConstantsCompat.LONG_PRESS)

    fun tick() = play(HapticFeedbackConstantsCompat.SEGMENT_TICK)

    fun toggle(on: Boolean) = play(if (on) HapticFeedbackConstantsCompat.TOGGLE_ON else HapticFeedbackConstantsCompat.TOGGLE_OFF)
}
