package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeRankingTest {
    @Test fun `repeated wording groups while repeated topic does not`() {
        val takes = listOf(
            take("a", 0, "Open settings and turn on the notification light"),
            take("b", 2_000, "Open the settings, then turn on the notification light"),
            take("topic", 4_000, "Notifications help you notice important phone messages")
        )
        val groups = TakeRanker().detectAndRank(takes)

        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups.single().candidates.map { it.id }.toSet())
        assertTrue(groups.single().similarityEvidence.single().repeatedWording)
    }

    @Test fun `ranking is explainable and near ties preserve alternatives`() {
        val groups = TakeRanker().detectAndRank(listOf(
            take("a", 0, "Can I get some water in my water bottle", quality = .85f),
            take("b", 2_000, "Can I get some water in my water bottle", quality = .86f)
        ))
        val group = groups.single()

        assertEquals(TakeResolution.ALTERNATIVES, group.resolution)
        assertEquals(2, group.scores.size)
        assertTrue(group.scores.all { it.evidenceIds.isNotEmpty() })
        assertTrue(group.alternativeTakeIds.isNotEmpty())
        assertTrue(group.confidence in 0f..1f)
    }

    @Test fun `factual product state differences require clarification`() {
        val group = TakeRanker().detectAndRank(listOf(
            take("old", 0, "Tap export to save the finished video", facts = mapOf("export-button" to "top-right")),
            take("new", 2_000, "Tap export to save the finished video", facts = mapOf("export-button" to "bottom bar"))
        )).single()

        assertEquals(TakeResolution.CLARIFICATION_REQUIRED, group.resolution)
        assertEquals("export-button", group.factualDifferences.single().key)
        assertTrue(group.preferredTakeId != null)
    }

    @Test fun `explicit choice is a hard constraint and fixture metrics catch destructive errors`() {
        val ranker = TakeRanker(nearTieDelta = 0f)
        val candidates = listOf(
            take("a", 0, "Can I get some water in my water bottle", quality = .95f),
            take("b", 2_000, "Can I get some water in my water bottle", quality = .75f),
            take("c", 4_000, "Can I get some water in my water bottle", quality = .65f)
        )
        val first = ranker.detectAndRank(candidates).single()
        val selected = ranker.detectAndRank(candidates,
            TakeConstraints(explicitChoiceByGroup = mapOf(first.id to "b"))).single()
        assertEquals("b", selected.preferredTakeId)
        assertEquals(TakeResolution.SELECTED, selected.resolution)

        val metrics = TakeDetectionEvaluator.evaluate(listOf(selected), TakeDetectionGroundTruth(
            groups = setOf(setOf("a", "b", "c")),
            preferredTakeByGroupMembers = mapOf("a|b|c" to "b"),
            mustKeepTakeIds = setOf("c")
        ))
        assertEquals(1.0, metrics.groupPrecision, 0.0)
        assertEquals(1.0, metrics.groupRecall, 0.0)
        assertEquals(1.0, metrics.preferredTakeAccuracy, 0.0)
        assertEquals(1, metrics.destructiveFalsePositives)
    }

    @Test fun `must keep wording cannot lose to a higher technical score`() {
        val candidates = listOf(
            take("complete", 0, "Export the video with captions enabled", quality = .7f),
            take("short", 2_000, "Export the video with captions", quality = .98f)
        )
        val group = TakeRanker(nearTieDelta = 0f).detectAndRank(
            candidates,
            TakeConstraints(mustKeepWording = listOf("captions enabled"))
        ).single()

        assertEquals("complete", group.preferredTakeId)
        assertEquals(TakeResolution.SELECTED, group.resolution)
        assertTrue(group.explanation.any { it.contains("Must-keep wording") })
    }

    private fun take(
        id: String,
        start: Long,
        text: String,
        quality: Float = .8f,
        facts: Map<String, String> = emptyMap()
    ) = TakeCandidate(
        id, "fixture-source", TimeRange(start, start + 1_500), text, "speaker-1", setOf("speech-$id", "shot-$id"),
        facts, quality, quality, quality, quality, quality, quality, 1f - quality
    )
}
