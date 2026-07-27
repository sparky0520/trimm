package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.*
import ai.ctrlb.mobile.evidence.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlannerPrivacyBoundaryTest {
    @Test fun `wire payload excludes media identifiers and paths`() {
        val packet = EvidencePacket(evidenceFingerprint = "safe-version", items = listOf(
            RetrievedEvidence("sentence-1", RetrievedEvidenceType.TRANSCRIPT, "source-1", TimeRange(0, 200), .9f, "hello")
        ), exhaustedTypes = emptySet(), omittedCounts = emptyMap())
        val request = PlannerPrivacyBoundary.create("make it tight", SourceMedia("content://secret/video", "private-hash", 1_000, 1080, 1920), WizardPreferences(), emptyList(), 0, packet)
        val wire = Json.encodeToString(PlannerRequest.serializer(), request)
        PlannerPrivacyBoundary.forbiddenWireKeys().forEach { assertFalse("forbidden key $it", Regex("\\\"$it\\\"\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(wire)) }
        assertFalse(wire.contains("content://")); assertFalse(wire.contains("private-hash"))
    }
}
