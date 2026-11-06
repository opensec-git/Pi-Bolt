package opensec.pibolt.app

import opensec.pibolt.app.data.RecentPrompts
import opensec.pibolt.app.ui.session.ImageSizing
import opensec.pibolt.app.ui.session.PendingSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentsTest {
    @Test
    fun imagesScaleToTheLongEdgeAndNeverUp() {
        assertEquals(2000 to 1500, ImageSizing.target(4000, 3000))
        assertEquals(1125 to 2000, ImageSizing.target(3024, 5376))
        assertEquals(800 to 600, ImageSizing.target(800, 600))
        assertEquals(2000 to 1, ImageSizing.target(10_000, 2))
        assertEquals(192 to 144, ImageSizing.target(4000, 3000, ImageSizing.THUMB_EDGE))
    }

    @Test
    fun subsamplingNeverDecodesBelowTheTarget() {
        assertEquals(1, ImageSizing.sampleSize(1999, 1000))
        assertEquals(1, ImageSizing.sampleSize(3999, 3000))
        assertEquals(2, ImageSizing.sampleSize(4000, 3000))
        assertEquals(4, ImageSizing.sampleSize(9000, 6000))
    }

    @Test
    fun sizeLimitsAndFormat() {
        assertEquals(4, ImageSizing.base64Size(1))
        assertEquals(4, ImageSizing.base64Size(3))
        assertEquals(8, ImageSizing.base64Size(4))
        // 4.5 MiB of base64 is 3.375 MiB of image bytes.
        assertTrue(ImageSizing.fits(3_538_944))
        assertFalse(ImageSizing.fits(3_538_945))
        assertTrue(ImageSizing.keepPng(hasAlpha = true, pngBytes = 400_000))
        assertFalse(ImageSizing.keepPng(hasAlpha = true, pngBytes = 2_000_000))
        assertFalse(ImageSizing.keepPng(hasAlpha = false, pngBytes = 10_000))
        assertEquals(4, ImageSizing.slotsLeft(0))
        assertEquals(0, ImageSizing.slotsLeft(5))
    }

    @Test
    fun anImageOnlyPromptIsShownBeforeTheEcho() {
        assertTrue(PendingSend.eligible("", running = false, images = 2))
        assertFalse(PendingSend.eligible("", running = false, images = 0))
        assertFalse(PendingSend.eligible("look", running = true, images = 1))
    }

    @Test
    fun recentPromptsAreDistinctNewestFirstAndCapped() {
        var list = emptyList<String>()
        for (p in listOf("one", "two", "three")) list = RecentPrompts.add(list, p)
        assertEquals(listOf("three", "two", "one"), list)
        // Sending one again moves it to the front instead of repeating it; surrounding space does not count.
        list = RecentPrompts.add(list, "  one ")
        assertEquals(listOf("one", "three", "two"), list)
        assertEquals(list, RecentPrompts.add(list, "   "))
        var many = emptyList<String>()
        for (i in 1..25) many = RecentPrompts.add(many, "p$i")
        assertEquals(RecentPrompts.CAP, many.size)
        assertEquals("p25", many.first())
        assertEquals("p6", many.last())
    }
}
