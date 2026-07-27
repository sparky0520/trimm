package ai.ctrlb.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryAudioRankingTest {
    private fun asset(
        id: String, kind: AudioKind = AudioKind.MUSIC, duration: Long = 30_000,
        energy: Float = .4f, tags: Set<String> = setOf("demonstration")
    ) = AudioCatalogItem(
        id, kind, "$id.mp3", duration, 120f, energy, tags, "license",
        id.first().digitToIntOrNull()?.toString()?.repeat(64) ?: "a".repeat(64),
        allowedUse = setOf("rendered-video"), status = "ready", tags = tags,
        integratedLufs = -22f, truePeakDbtp = -4f, beatConfidence = .9f,
        sections = listOf(AudioSection("intro", 0, 10_000, .8f), AudioSection("body", 10_000, duration, .8f))
    )

    private val context = StoryAudioContext(
        editDurationMs = 30_000,
        speechRanges = listOf(TimeRange(2_000, 12_000), TimeRange(15_000, 24_000)),
        storyEvents = listOf(
            StoryAudioEvent("event-1", 10_000, "demonstration", setOf("visual-1"), .9f, true),
            StoryAudioEvent("event-2", 11_000, "demonstration", setOf("beat-2"), .8f, false)
        ),
        toneTags = setOf("demonstration"),
        energyArc = listOf(AudioEnergyPoint(0, .3f), AudioEnergyPoint(20_000, .7f)),
        editFingerprint = "edit-v1"
    )

    @Test fun `music ranking is explained stable and includes no-music comparison`() {
        val matches = AudioMatcher.rankMusic(listOf(asset("a"), asset("b", duration = 8_000)), context, "catalog-7")
        assertEquals("a", matches.first().item.id)
        assertEquals(6, matches.first().explanation.reasons().size)
        val first = AudioMatcher.comparisonSet(matches, context, "catalog-7")
        val second = AudioMatcher.comparisonSet(matches.reversed().reversed(), context, "catalog-7")
        assertEquals(first.resultFingerprint, second.resultFingerprint)
        assertEquals(null, first.choices.last().catalogId)
        assertEquals(first.choices.first().previewTimelineStartMs, first.choices[1].previewTimelineStartMs)
        assertEquals(3f, first.choices.first().previewGainDb)
    }

    @Test fun `sound effects require matching grounded events and obey density budget`() {
        val recommendations = AudioMatcher.rankSoundEffects(
            listOf(asset("a", AudioKind.SOUND_EFFECT), asset("b", AudioKind.SOUND_EFFECT, tags = setOf("recap"))),
            context
        )
        assertEquals(1, recommendations.size)
        assertEquals("event-1", recommendations.single().eventId)
        assertTrue(recommendations.single().evidenceIds.isNotEmpty())
    }

    @Test fun `identical inputs break score ties by catalog id`() {
        val ids = AudioMatcher.rankMusic(listOf(asset("b"), asset("a")), context, "catalog-7").map { it.item.id }
        assertEquals(listOf("a", "b"), ids)
    }
}
