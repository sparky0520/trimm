package ai.trimm.mobile.planning

import ai.trimm.mobile.domain.TimeRange
import ai.trimm.mobile.evidence.EvidenceDisclosure
import ai.trimm.mobile.evidence.EvidenceGraph
import ai.trimm.mobile.evidence.AnalyzerIdentity
import ai.trimm.mobile.evidence.EvidenceProvenance
import ai.trimm.mobile.evidence.ExecutionLocation
import ai.trimm.mobile.evidence.SoundEvidence
import ai.trimm.mobile.evidence.SoundKind
import ai.trimm.mobile.evidence.VisualEvidence
import ai.trimm.mobile.evidence.VisualKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestShapedMediaIndexTest {
    private val analyzer = AnalyzerIdentity("local measurements", "1", ExecutionLocation.LOCAL_DEVICE)
    private val provenance = EvidenceProvenance(analyzer, "a".repeat(64))
    private val graph = EvidenceGraph(
        sourceFingerprint = "a".repeat(64),
        sourceDurationMs = 60_000,
        createdAtEpochMs = 1,
        observations = emptyList(),
        analyzerRuns = emptyList(),
        disclosure = EvidenceDisclosure(computedLocally = emptyList(), sentExternally = emptyList())
    )

    @Test fun exactRequestSelectsOnlyTheRequestedRange() {
        val index = RequestShapedMediaIndexBuilder.build(
            listOf(graph),
            MediaIndexRequirements(
                scope = MediaIndexScope.EXACT,
                requestedRanges = listOf(RequestedRange(
                    graph.sourceFingerprint, TimeRange(5_000, 8_000), SelectionReason.USER_REQUESTED
                ))
            )
        )
        assertEquals(listOf(5_000L to 8_000L), index.selections.map { it.startMs to it.endMs })
    }

    @Test fun broadRequestCoversTheWholeSourceWithoutSemanticLabels() {
        val measuredGraph = graph.copy(observations = listOf(
            SoundEvidence("silence", graph.sourceFingerprint, TimeRange(8_000, 9_000), .91f, provenance, SoundKind.SILENCE, -48f, "dB"),
            VisualEvidence("motion", graph.sourceFingerprint, TimeRange(30_000, 31_000), .48f, provenance, VisualKind.MOTION, .4f)
        ))
        val index = RequestShapedMediaIndexBuilder.build(
            listOf(measuredGraph),
            MediaIndexRequirements(scope = MediaIndexScope.BROAD_EDITORIAL, maximumSelections = 12)
        )
        assertEquals(0, index.selections.first().startMs)
        assertTrue(index.selections.any { it.endMs == 60_000L })
        assertEquals(
            setOf(SelectionReason.REPRESENTATIVE_COVERAGE, SelectionReason.MEASURED_CANDIDATE, SelectionReason.UNCERTAINTY),
            index.selections.flatMap { it.reasons }.toSet()
        )
        assertFalse(index.selections.any { it.reasons.isEmpty() })
        val uncertainty = index.selections.single { it.reasons == listOf(SelectionReason.UNCERTAINTY) }
        assertTrue(uncertainty.startMs < 30_000 && uncertainty.endMs > 31_000)
    }

    @Test fun requestPlannerNarrowsTimestampedAudioAndBroadensPublishableRequests() {
        val source = graph.sourceFingerprint to graph.sourceDurationMs
        val exact = MediaIndexRequestPlanner.plan("Remove the pause at 00:20", listOf(source))
        assertEquals(MediaIndexScope.EXACT, exact.scope)
        assertEquals(TimeRange(18_000, 22_000), exact.requestedRanges.single().range)
        assertTrue(DerivativeKind.AUDIO in exact.requestedRanges.single().derivativeKinds)
        assertFalse(DerivativeKind.CROP in exact.requestedRanges.single().derivativeKinds)

        val broad = MediaIndexRequestPlanner.plan("Make this video publishable", listOf(source))
        assertEquals(MediaIndexScope.BROAD_EDITORIAL, broad.scope)
        assertTrue(broad.requestedRanges.isEmpty())
    }

    @Test fun specialistVisualRequestStaysMeasurementOnlyAndSelectsRequiredDerivativeKinds() {
        val planned = MediaIndexRequestPlanner.plan(
            "Check the screen text and crop",
            listOf(graph.sourceFingerprint to graph.sourceDurationMs)
        )
        assertEquals(MediaIndexScope.SPECIALIST, planned.scope)
        assertEquals(SelectionReason.USER_REQUESTED, planned.requestedRanges.single().reason)
        assertTrue(planned.requestedRanges.single().derivativeKinds.containsAll(
            listOf(DerivativeKind.FRAME, DerivativeKind.CROP, DerivativeKind.TEXT, DerivativeKind.EVIDENCE)
        ))
    }
}
