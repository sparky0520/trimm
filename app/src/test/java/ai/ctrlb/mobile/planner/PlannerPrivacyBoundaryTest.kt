package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlannerPrivacyBoundaryTest {
    @Test fun `wire payload excludes media identifiers and paths`() {
        val request = PlannerPrivacyBoundary.create("make it tight", listOf(TranscriptWord("hello", TimeRange(0, 200), .9f)), SourceMedia("content://secret/video", "private-hash", 1_000, 1080, 1920), WizardPreferences(), emptyList(), 0)
        val wire = Json.encodeToString(PlannerRequest.serializer(), request)
        PlannerPrivacyBoundary.forbiddenWireKeys().forEach { assertFalse("forbidden key $it", Regex("\\\"$it\\\"\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(wire)) }
        assertFalse(wire.contains("content://")); assertFalse(wire.contains("private-hash"))
    }
}
