package ai.ctrlb.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatPolicyTest {
    private fun source(width: Int, height: Int, rotation: Int = 0) =
        SourceMedia("content://video", "fingerprint", 1_000, width, height, rotation)

    @Test
    fun `aspect follows displayed source orientation including rotation metadata`() {
        assertEquals(AspectRatio.PORTRAIT, source(1080, 1920).detectedAspectRatio())
        assertEquals(AspectRatio.LANDSCAPE, source(1920, 1080).detectedAspectRatio())
        assertEquals(AspectRatio.PORTRAIT, source(1920, 1080, 90).detectedAspectRatio())
        assertEquals(AspectRatio.LANDSCAPE, source(1080, 1920, 270).detectedAspectRatio())
        assertEquals(AspectRatio.SQUARE, source(1080, 1080).detectedAspectRatio())
    }

    @Test
    fun `source resolution ceiling never asks for an upscale`() {
        assertEquals(1280 to 720, source(1280, 720).sourceResolutionCeiling())
        assertEquals(3840 to 2160, source(7680, 4320).sourceResolutionCeiling())
        assertEquals(1920 to 1080, source(1080, 1920).sourceResolutionCeiling())
    }
}
