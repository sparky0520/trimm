package ai.ctrlb.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMapperTest {
    @Test fun `maps both directions across merged cuts`() {
        val mapper = TimelineMapper(listOf(TimeRange(1_000, 2_000), TimeRange(1_800, 2_500), TimeRange(5_000, 5_500)))
        assertEquals(1_500, mapper.removals.first().durationMs)
        assertEquals(3_500, mapper.sourceToOutput(5_000))
        assertEquals(6_000, mapper.outputToSource(4_000))
    }
}
