package ai.trimm.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FumbleDetectorTest {
    private fun word(
        text: String,
        start: Long,
        end: Long,
        confidence: Float = .98f,
        speaker: String? = "speaker-1"
    ) = TranscriptWord(text, TimeRange(start, end), confidence, speaker)

    @Test fun `pause filler and repeated phrase remain contextual review hints`() {
        val words = listOf(
            word("first", 0, 250), word("open", 300, 550), word("settings", 600, 950),
            word("open", 1_000, 1_250), word("settings", 1_300, 1_650),
            word("um", 3_600, 3_800, .82f), word("now", 3_900, 4_200)
        )
        val hints = FumbleDetector.detect(words, 5_000, Pace.NATURAL)

        assertTrue(hints.any { it.reason == "repeated-phrase-candidate" })
        assertTrue(hints.any { it.reason == "pause-candidate" })
        assertTrue(hints.any { it.reason == "possible-filler" })
        assertTrue(hints.all { it.provenance == "local-transcript-timing" })
        assertTrue(hints.all { it.context.isNotBlank() })
        assertTrue(hints.any { it.contextBefore.isNotBlank() || it.contextAfter.isNotBlank() })
    }

    @Test fun `intentional pause is measured but a natural short pause is not over-flagged`() {
        val intentional = FumbleDetector.detect(
            listOf(word("important", 0, 300), word("point", 2_500, 2_800)),
            3_000, Pace.NATURAL
        )
        assertEquals(1, intentional.count { it.reason == "pause-candidate" })
        assertTrue(intentional.single { it.reason == "pause-candidate" }.context.contains("important"))

        val natural = FumbleDetector.detect(
            listOf(word("hello", 0, 300), word("world", 1_300, 1_600)),
            2_000, Pace.NATURAL
        )
        assertFalse(natural.any { it.reason == "pause-candidate" })
    }

    @Test fun `transcription uncertainty is preserved even for a possible filler`() {
        val hints = FumbleDetector.detect(
            listOf(word("we", 0, 200), word("um", 250, 450, .42f), word("ship", 500, 800)),
            1_000, Pace.NATURAL
        )
        assertTrue(hints.any { it.reason == "possible-filler-transcript-uncertain" })
        val uncertain = hints.single { it.reason == "transcript-uncertainty" }
        assertEquals(.42f, uncertain.transcriptConfidence)
        assertEquals("um", uncertain.context)
    }

    @Test fun `repeated wording never crosses explicit speaker boundaries`() {
        val differentSpeakers = listOf(
            word("try", 0, 200, speaker = "a"), word("again", 250, 500, speaker = "a"),
            word("try", 600, 800, speaker = "b"), word("again", 850, 1_100, speaker = "b")
        )
        assertFalse(FumbleDetector.detect(differentSpeakers, 1_500, Pace.NATURAL)
            .any { it.reason == "repeated-phrase-candidate" })

        val sameSpeaker = differentSpeakers.map { it.copy(speakerId = "a") }
        assertTrue(FumbleDetector.detect(sameSpeaker, 1_500, Pace.NATURAL)
            .any { it.reason == "repeated-phrase-candidate" && it.speakerIds == setOf("a") })
    }

    @Test fun `repeated emphasis is surfaced with context rather than treated as deletion truth`() {
        val hints = FumbleDetector.detect(
            listOf(
                word("this", 0, 180), word("is", 200, 320),
                word("very", 350, 520), word("important", 540, 800),
                word("very", 850, 1_020), word("important", 1_040, 1_300),
                word("today", 1_350, 1_600)
            ),
            2_000, Pace.NATURAL
        )
        val emphasis = hints.single { it.reason == "repeated-phrase-candidate" }
        assertEquals("very important very important", emphasis.context)
        assertTrue(emphasis.contextBefore.contains("this is"))
        assertTrue(emphasis.contextAfter.contains("today"))
    }

    @Test fun `speaking pace changes hint threshold without enabling an edit`() {
        val words = listOf(word("one", 0, 250), word("two", 1_600, 1_850))
        assertTrue(FumbleDetector.detect(words, 2_000, Pace.TIGHT).any { it.reason == "pause-candidate" })
        assertFalse(FumbleDetector.detect(words, 2_000, Pace.RELAXED).any { it.reason == "pause-candidate" })
    }
}
