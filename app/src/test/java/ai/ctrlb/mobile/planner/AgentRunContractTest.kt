package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.layer1.*
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunContractTest {
    @Test
    fun `publishable run contract decodes safe activity and proposal state`() {
        val run = Json.decodeFromString<AgentRun>("""
            {
              "contractVersion":1,
              "runId":"run-1",
              "projectId":"project-1",
              "projectRevision":0,
              "status":"proposal_ready",
              "events":[{"sequence":1,"type":"analyzing","message":"Reviewing selected ranges.","createdAt":"2026-07-29T00:00:00.000Z"}],
              "decision":{"type":"edit_program","expectedRevision":0,"summary":"First cut","operations":[]}
            }
        """.trimIndent())
        assertEquals(AgentRunStatus.PROPOSAL_READY, run.status)
        assertEquals("analyzing", run.events.single().type)
        assertEquals("edit_program", run.decision["type"]?.toString()?.trim('"'))
    }
}
