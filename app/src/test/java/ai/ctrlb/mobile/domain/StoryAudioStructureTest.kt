package ai.ctrlb.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class StoryAudioStructureTest {
    private fun track(beatConfidence: Float = .9f) = AudioCatalogItem(
        id = "music", kind = AudioKind.MUSIC, durationMs = 60_000,
        licenseId = "licensed", checksum = "a".repeat(64),
        beatConfidence = beatConfidence,
        sections = listOf(
            AudioSection("intro", 0, 10_000, .9f),
            AudioSection("lift", 10_000, 25_000, .9f),
            AudioSection("outro", 25_000, 60_000, .9f)
        )
    )

    private fun context(events: List<StoryAudioEvent>) = StoryAudioContext(
        editDurationMs = 30_000,
        speechRanges = listOf(TimeRange(1_000, 8_000), TimeRange(12_000, 18_000)),
        storyEvents = events,
        toneTags = emptySet(), energyArc = emptyList(), editFingerprint = "edit"
    )

    @Test fun `aligns confident sections and reports all structural choices`() {
        val plan = StoryAudioStructurePlanner.plan(track(), context(listOf(
            StoryAudioEvent("open", 1_000, "opening", setOf("sentence-1"), .9f, true),
            StoryAudioEvent("reveal", 11_000, "reveal", setOf("sentence-2", "frame-2"), .85f, true),
            StoryAudioEvent("close", 26_000, "ending", setOf("sentence-3"), .8f, false)
        )))

        assertEquals(1_000, plan.segments.first().timelineStartMs)
        assertTrue(plan.decisions.any { it.kind == AudioStructuralDecisionKind.SECTION_CHANGE && it.storyEventId == "reveal" })
        assertTrue(plan.decisions.any { it.kind == AudioStructuralDecisionKind.ENDING })
        assertTrue(plan.decisions.any { it.kind == AudioStructuralDecisionKind.DUCKING })
        assertTrue(plan.decisions.any { it.kind == AudioStructuralDecisionKind.SPEECH_TIMING })
        assertTrue(plan.segments.zipWithNext().all { (a, b) -> a.timelineEndMs <= b.timelineStartMs })
    }

    @Test fun `low beat confidence preserves continuous music and reports fallback`() {
        val plan = StoryAudioStructurePlanner.plan(track(.4f), context(listOf(
            StoryAudioEvent("open", 0, "opening", setOf("sentence-1"), .9f, true),
            StoryAudioEvent("reveal", 10_000, "reveal", setOf("sentence-2"), .9f, true)
        )))

        assertEquals(1, plan.segments.size)
        assertFalse(plan.decisions.any { it.kind == AudioStructuralDecisionKind.SECTION_CHANGE })
        assertTrue(plan.decisions.any { it.fallback && "too low" in it.rationale })
    }

    @Test fun `story timestamps remain inputs rather than retimed outputs`() {
        val speech = listOf(TimeRange(3_000, 7_000))
        val input = context(listOf(StoryAudioEvent("beat", 3_000, "opening", setOf("word"), .9f, false)))
            .copy(speechRanges = speech)
        val plan = StoryAudioStructurePlanner.plan(track(), input)

        assertEquals(speech, input.speechRanges)
        assertEquals(3_000, plan.segments.first().timelineStartMs)
        assertTrue(plan.decisions.single { it.kind == AudioStructuralDecisionKind.SPEECH_TIMING }
            .rationale.contains("keeps its authored timing"))
    }
}
