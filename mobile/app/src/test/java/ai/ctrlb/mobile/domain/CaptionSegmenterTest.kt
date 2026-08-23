package ai.ctrlb.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSegmenterTest {
    @Test fun `segments words at pauses and punctuation`() {
        val words = listOf(
            TranscriptWord("Hello", TimeRange(0, 300), .99f),
            TranscriptWord("there.", TimeRange(320, 700), .99f),
            TranscriptWord("A", TimeRange(1_500, 1_650), .99f),
            TranscriptWord("new", TimeRange(1_670, 1_900), .99f),
            TranscriptWord("thought", TimeRange(1_920, 2_200), .99f),
        )
        val cues = CaptionSegmenter.segment(words)
        assertEquals(2, cues.size)
        assertEquals("Hello there.", cues.first().text)
        assertTrue(cues.zipWithNext().all { (a, b) -> a.range.endMs <= b.range.startMs })
    }
}
