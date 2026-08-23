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
        assertEquals(DisplaySize(640, 360), source(640, 360).renderSizeWithin(1920, 1080))
        assertEquals(DisplaySize(3840, 2160), source(7680, 4320).renderSizeWithin(3840, 2160))
        assertEquals(DisplaySize(1080, 1920), source(1920, 1080, 90).renderSizeWithin(3840, 2160))
        assertEquals(DisplaySize(1080, 1080), source(1080, 1080).renderSizeWithin(3840, 2160))
    }

    @Test
    fun `device ceiling preserves rotated portrait landscape and square framing`() {
        assertEquals(DisplaySize(720, 1280), source(3840, 2160, 90).renderSizeWithin(1280, 720))
        assertEquals(DisplaySize(1280, 720), source(3840, 2160).renderSizeWithin(1280, 720))
        assertEquals(DisplaySize(720, 720), source(2160, 2160).renderSizeWithin(1280, 720))
    }

    @Test
    fun `mixed sources use the lowest source quality ceiling`() {
        val sources = listOf(source(3840, 2160), source(1280, 720))
        assertEquals(DisplaySize(1280, 720), sources.renderSizeWithin(3840, 2160))
        val mixedOrientations = listOf(source(1920, 1080), source(1920, 1080, 90))
        assertEquals(DisplaySize(1080, 606), mixedOrientations.renderSizeWithin(3840, 2160))
    }
}
