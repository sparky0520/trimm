package ai.ctrlb.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class FumbleDetectorTest {
    private fun word(text: String, start: Long, end: Long, confidence: Float = .98f) = TranscriptWord(text, TimeRange(start, end), confidence)

    @Test fun `auto cuts immediate retake but leaves ambiguous filler for review`() {
        val words = listOf(word("open", 0, 300), word("settings", 350, 800), word("open", 900, 1200), word("settings", 1250, 1700), word("um", 1800, 1950, .8f), word("now", 2100, 2400))
        val all = FumbleDetector.detect(words, 3_000, Pace.NATURAL)
        assertTrue(all.any { "immediate-retake" in it.reason && it.confidence >= .93f })
        assertTrue(all.any { "filler" in it.reason && it.confidence < .93f })
        assertEquals(1, FumbleDetector.automatic(all).size)
    }

    @Test fun `natural short pause is preserved`() {
        val all = FumbleDetector.detect(listOf(word("hello", 0, 300), word("world", 1300, 1600)), 2_000, Pace.NATURAL)
        assertFalse(all.any { it.reason == "long-pause" })
    }
}
