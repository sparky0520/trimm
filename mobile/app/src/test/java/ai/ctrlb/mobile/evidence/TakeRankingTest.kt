package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeRankingTest {
    @Test fun `candidate generation cites evidence and ranks repeated sentences across sources`() {
        val graphs = listOf(
            graph("source-a", "sentence-a", "Can I get some water in my water bottle", .82f, clipping = .04f),
            graph("source-b", "sentence-b", "Can I get some water in my water bottle", .97f, clipping = 0f)
        )

        val candidates = TakeCandidateGenerator.fromEvidence(graphs)
        val group = TakeRanker(nearTieDelta = 0f).detectAndRank(candidates).single()

        assertEquals(setOf("sentence-a", "sentence-b"), group.candidates.map { it.id }.toSet())
        assertEquals("sentence-b", group.preferredTakeId)
        assertEquals(TakeResolution.SELECTED, group.resolution)
        assertTrue(group.candidates.all { it.evidenceIds.size >= 3 })
        assertTrue(group.scores.all { it.evidenceIds.isNotEmpty() })
    }

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

    private fun graph(
        fingerprint: String,
        sentenceId: String,
        text: String,
        confidence: Float,
        clipping: Float
    ): EvidenceGraph {
        val range = TimeRange(0, 1_500)
        val speechAnalyzer = AnalyzerIdentity("speech", "1", ExecutionLocation.EXTERNAL_SERVICE)
        val mediaAnalyzer = AnalyzerIdentity("local", "1", ExecutionLocation.LOCAL_DEVICE)
        val provenance = EvidenceProvenance(mediaAnalyzer, fingerprint)
        return EvidenceGraph(
            sourceFingerprint = fingerprint,
            sourceDurationMs = 2_000,
            createdAtEpochMs = 1,
            observations = listOf(
                SpeechEvidence(sentenceId, fingerprint, range, confidence,
                    EvidenceProvenance(speechAnalyzer, fingerprint), SpeechKind.SENTENCE, text, "speaker-1"),
                FrameEvidence("frame-$sentenceId", fingerprint, TimeRange(0, 1), .95f,
                    provenance, "frame.jpg", 720, 1280),
                VisualEvidence("exposure-$sentenceId", fingerprint, TimeRange(0, 1), .8f,
                    provenance, VisualKind.EXPOSURE, .5f),
                SoundEvidence("clip-$sentenceId", fingerprint, range, .99f,
                    provenance, SoundKind.CLIPPING, clipping, "ratio")
            ),
            analyzerRuns = emptyList(),
            disclosure = EvidenceDisclosure(emptyList(), emptyList())
        )
    }
}
