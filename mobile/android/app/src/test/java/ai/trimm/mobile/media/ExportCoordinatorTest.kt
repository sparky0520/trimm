package ai.trimm.mobile.media

import ai.trimm.mobile.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportCoordinatorTest {
    @Test fun `retained ranges exclude cuts without gaps`() {
        assertEquals(listOf(TimeRange(0, 1_000), TimeRange(2_000, 4_000), TimeRange(4_500, 5_000)), ExportCoordinator.retainedRanges(5_000, listOf(TimeRange(1_000, 2_000), TimeRange(4_000, 4_500))))
    }
}
