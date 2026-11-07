package opensec.pibolt.app

import opensec.pibolt.app.ui.components.ComposerPrimaryMode
import opensec.pibolt.app.ui.components.DotWaveMath
import opensec.pibolt.app.ui.theme.PiMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The redesign kit's logic: the staggered entrance and the dot-matrix waveform. */
class KitTest {
    @Test
    fun staggerIsAssistantUisAndCapped() {
        assertEquals(120, PiMotion.stagger(0))
        assertEquals(190, PiMotion.stagger(1))
        assertEquals(400, PiMotion.stagger(4))
        // Capped at 8 steps, so a long list never leaves its last element waiting.
        assertEquals(PiMotion.stagger(8), PiMotion.stagger(20))
        assertEquals(680, PiMotion.stagger(8))
        assertEquals(120, PiMotion.stagger(-3))
    }

    @Test
    fun dotWaveRestsOnALitLineAndFollowsTheLevel() {
        // Silence: amplitude 0 everywhere, one lit dot (the centre line) per column.
        for (x in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(0f, DotWaveMath.amplitude(x, t = 1.7f, level = 0f))
            assertEquals(1, DotWaveMath.litDots(9, 0f))
        }
        // Louder is never smaller, and the amplitude stays within the half height.
        for (x in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            for (t in listOf(0f, 0.4f, 2.3f)) {
                var last = -1f
                for (level in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
                    val a = DotWaveMath.amplitude(x, t, level)
                    assertTrue(a in 0f..1f, "amplitude $a out of range")
                    assertTrue(a >= last, "a louder level lit less at x=$x t=$t")
                    last = a
                }
            }
        }
        // Out-of-range levels are clamped.
        assertEquals(DotWaveMath.amplitude(0.5f, 1f, 1f), DotWaveMath.amplitude(0.5f, 1f, 3f))
        // A full column lights every dot.
        assertEquals(9, DotWaveMath.litDots(9, 1f))
        assertEquals(5, DotWaveMath.litDots(9, 0.5f))
        assertEquals(50, DotWaveMath.columns(300f))
        assertEquals(1, DotWaveMath.columns(0f))
    }
}

class ComposerPrimaryTest {
    @Test
    fun theCircleIsSendWithTextStopWhileRunningVoiceWhenEmpty() {
        assertEquals(ComposerPrimaryMode.Send, ComposerPrimaryMode.of(hasText = true, running = true, canStop = true, canVoice = true))
        assertEquals(ComposerPrimaryMode.Stop, ComposerPrimaryMode.of(hasText = false, running = true, canStop = true, canVoice = true))
        assertEquals(ComposerPrimaryMode.Voice, ComposerPrimaryMode.of(hasText = false, running = true, canStop = false, canVoice = true))
        assertEquals(ComposerPrimaryMode.Voice, ComposerPrimaryMode.of(hasText = false, running = false, canStop = true, canVoice = true))
        assertEquals(ComposerPrimaryMode.SendDisabled, ComposerPrimaryMode.of(hasText = false, running = false, canStop = false, canVoice = false))
    }
}
