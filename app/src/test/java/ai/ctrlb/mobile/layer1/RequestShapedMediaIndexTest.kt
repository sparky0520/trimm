package ai.ctrlb.mobile.layer1

import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.evidence.EvidenceDisclosure
import ai.ctrlb.mobile.evidence.EvidenceGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestShapedMediaIndexTest {
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
        val index = RequestShapedMediaIndexBuilder.build(
            listOf(graph),
            MediaIndexRequirements(scope = MediaIndexScope.BROAD_EDITORIAL, maximumSelections = 4)
        )
        assertEquals(0, index.selections.first().startMs)
        assertEquals(60_000, index.selections.last().endMs)
        assertFalse(index.selections.any { it.reasons != listOf(SelectionReason.REPRESENTATIVE_COVERAGE) })
    }
}
