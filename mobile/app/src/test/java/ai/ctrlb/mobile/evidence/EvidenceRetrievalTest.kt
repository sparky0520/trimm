package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import org.junit.Assert.*
import org.junit.Test

class EvidenceRetrievalTest {
    private val analyzer = AnalyzerIdentity("test", "1", ExecutionLocation.LOCAL_DEVICE)

    @Test fun `retrieval enforces budgets and strips local identifiers`() {
        val graph = EvidenceGraph(sourceFingerprint = "private-fingerprint", sourceDurationMs = 2_000, createdAtEpochMs = 1,
            observations = listOf(
                SpeechEvidence("speech-1", "private-fingerprint", TimeRange(0, 500), .9f, EvidenceProvenance(analyzer, "private-fingerprint"), SpeechKind.SENTENCE, "A useful product demo"),
                SpeechEvidence("speech-2", "private-fingerprint", TimeRange(500, 1_000), .8f, EvidenceProvenance(analyzer, "private-fingerprint"), SpeechKind.SENTENCE, "Unrelated"),
                FrameEvidence("frame-1", "private-fingerprint", TimeRange(0, 1), .9f, EvidenceProvenance(analyzer, "private-fingerprint"), "/secret/frame.jpg", 100, 200)
            ), analyzerRuns = emptyList(), disclosure = EvidenceDisclosure(emptyList(), emptyList()))
        val story = StoryGraph(evidenceFingerprint = "version-1", beats = emptyList(), constraints = emptyList(), missingMaterial = emptyList(), editorialRead = emptyList())
        val packet = BoundedEvidenceRetriever.retrieve(listOf(graph), story, EvidenceRetrievalQuery("product demo",
            budget = EvidenceRetrievalBudget(0, 1, 1, 0, 0, 0, 2)))
        assertEquals(2, packet.items.size)
        assertTrue(packet.items.any { it.id == "speech-1" })
        val wire = kotlinx.serialization.json.Json.encodeToString(EvidencePacket.serializer(), packet)
        assertFalse(wire.contains("private-fingerprint"))
        assertFalse(wire.contains("/secret/frame.jpg"))
    }

    @Test fun `invented references fail`() {
        val packet = EvidencePacket(evidenceFingerprint = "v", items = listOf(
            RetrievedEvidence("valid", RetrievedEvidenceType.TRANSCRIPT, "source-1", TimeRange(0, 1), 1f, "text")
        ), exhaustedTypes = emptySet(), omittedCounts = emptyMap())
        BoundedEvidenceRetriever.validateReferences(packet, listOf("valid"))
        assertThrows(IllegalArgumentException::class.java) { BoundedEvidenceRetriever.validateReferences(packet, listOf("invented")) }
    }
}
