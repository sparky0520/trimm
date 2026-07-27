package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryGraphTest {
    @Test fun `derives cited flexible beats and a readable editorial read`() {
        val graph = graph(listOf(
            sentence("hook", 0, "Want to edit a video faster?"),
            sentence("demo", 2_000, "Open the app and tap create"),
            sentence("cta", 4_000, "Try it and let me know")
        ))

        val story = StoryGraphDeriver.derive(listOf(graph), emptyList(), "tap create")

        assertEquals(listOf("hook", "demonstration", "call to action"), story.beats.map { it.role })
        assertTrue(story.beats.all { it.candidateRanges.isNotEmpty() && it.claims.isNotEmpty() })
        assertTrue(story.beats.all { it.evidenceIds.isNotEmpty() })
        assertTrue(story.beats[1].supportingVisualEvidenceIds.contains("visual-demo"))
        assertEquals(StoryMaterialStatus.ESSENTIAL, story.beats[1].status)
        assertTrue(story.constraints.single().satisfied)
        assertTrue(story.editorialRead.joinToString(" ").contains("Must keep"))
        assertTrue(story.editorialRead.none { it.startsWith("{") })
    }

    @Test fun `marks repeated and conflicting takes without silently deleting them`() {
        val graph = graph(listOf(
            sentence("a", 0, "Tap export to save the video"),
            sentence("b", 2_000, "Tap export to save the video")
        ))
        val candidates = TakeCandidateGenerator.fromEvidence(listOf(graph))
        val group = TakeRanker().detectAndRank(candidates).single()
            .copy(resolution = TakeResolution.CLARIFICATION_REQUIRED)

        val story = StoryGraphDeriver.derive(listOf(graph), listOf(group), "")

        assertTrue(story.beats.all { it.status == StoryMaterialStatus.CONFLICTING })
        assertTrue(story.editorialRead.any { it.contains("require clarification") })
    }

    @Test fun `missing must keep is explicit but absent canonical recipes are not invented`() {
        val story = StoryGraphDeriver.derive(
            listOf(graph(listOf(sentence("context", 0, "Today we are editing a short clip")))),
            emptyList(),
            "show the privacy setting"
        )

        assertFalse(story.constraints.single().satisfied)
        assertEquals(1, story.missingMaterial.size)
        assertTrue(story.beats.none { it.role == "problem" || it.role == "proof" })
        assertTrue(story.editorialRead.last().contains("not treated as required"))
    }

    @Test fun `identical evidence produces deterministic graph ids and fingerprint`() {
        val graph = graph(listOf(sentence("point", 0, "This workflow keeps the edit local")))
        val first = StoryGraphDeriver.derive(listOf(graph), emptyList(), "")
        val second = StoryGraphDeriver.derive(listOf(graph), emptyList(), "")

        assertEquals(first.evidenceFingerprint, second.evidenceFingerprint)
        assertEquals(first.beats.map { it.id }, second.beats.map { it.id })
    }

    private fun sentence(id: String, start: Long, text: String): SpeechEvidence {
        val analyzer = AnalyzerIdentity("speech", "1", ExecutionLocation.EXTERNAL_SERVICE)
        return SpeechEvidence(id, "source", TimeRange(start, start + 1_500), .9f,
            EvidenceProvenance(analyzer, "source"), SpeechKind.SENTENCE, text, "speaker")
    }

    private fun graph(sentences: List<SpeechEvidence>): EvidenceGraph {
        val analyzer = AnalyzerIdentity("visual", "1", ExecutionLocation.LOCAL_DEVICE)
        val visuals = sentences.map {
            VisualEvidence("visual-${it.id}", "source", it.range, .8f,
                EvidenceProvenance(analyzer, "source"), VisualKind.SALIENCE, .7f)
        }
        return EvidenceGraph(
            sourceFingerprint = "source",
            sourceDurationMs = (sentences.maxOfOrNull { it.range.endMs } ?: 1L) + 1,
            createdAtEpochMs = 1,
            observations = sentences + visuals,
            analyzerRuns = emptyList(),
            disclosure = EvidenceDisclosure(emptyList(), emptyList())
        )
    }
}
