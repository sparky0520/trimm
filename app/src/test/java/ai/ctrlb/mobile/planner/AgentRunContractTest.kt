package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.layer1.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun `agent inventory declares selective consent and canonical contract versions`() {
        val request = request()
        assertEquals("selective_analysis", request.mediaConsent.mode)
        assertEquals("media-v1", request.mediaConsent.disclosureVersion)
        assertTrue(runCatching { java.time.Instant.parse(request.mediaConsent.grantedAt) }.isSuccess)
        assertEquals(listOf(1), request.supportedContractVersions.agentRuns)
        assertEquals(listOf(1), request.supportedContractVersions.editPrograms)
    }

    @Test fun `one run persists safe activity through iterative evidence passes`() = runTest {
        val request = request()
        val calls = mutableListOf<List<String>>()
        val responses = ArrayDeque(listOf(
            evidenceRun(1, "job-1", reused = false),
            evidenceRun(3, "job-2", reused = true),
            terminalRun()
        ))
        val gateway = object : AgentRunGateway {
            override suspend fun start(request: AgentRunRequest) = responses.removeFirst()
            override suspend fun continueWith(runId: String, evidenceJobIds: List<String>): AgentRun {
                assertEquals("run-1", runId)
                calls += evidenceJobIds
                return responses.removeFirst()
            }
            override suspend fun recover(runId: String, action: String, clarification: String?) =
                error("Unexpected recovery")
        }
        val fulfilled = mutableListOf<String>()
        val persisted = mutableListOf<PersistedAgentRun>()
        val final = AgentRunCoordinator(gateway, AgentEvidenceFulfiller { job ->
            fulfilled += job.id
            job.id
        }).run(request) { persisted += it }

        assertEquals(listOf("job-1"), fulfilled)
        assertEquals(listOf(listOf("job-1"), listOf("job-2")), calls)
        assertEquals(AgentRunStatus.PROPOSAL_READY, final.status)
        assertEquals(listOf(1, 2, 3, 4, 5), final.events.map { it.sequence })
        assertEquals(listOf("job-1", "job-2"), final.fulfilledEvidenceJobIds)
        assertTrue(persisted.size >= 5)
        assertTrue(persisted.all { it.runId == "run-1" })
    }

    @Test fun `coordinator rejects unsafe activity stale state and changed continuation ids`() = runTest {
        val request = request()
        suspend fun rejected(run: AgentRun) {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    AgentRunCoordinator(object : AgentRunGateway {
                        override suspend fun start(request: AgentRunRequest) = run
                        override suspend fun continueWith(runId: String, evidenceJobIds: List<String>) = terminalRun()
                        override suspend fun recover(runId: String, action: String, clarification: String?) = terminalRun()
                    }, AgentEvidenceFulfiller { it.id }).run(request) {}
                }
            }
        }
        rejected(terminalRun().copy(projectRevision = 99))
        rejected(terminalRun().copy(events = listOf(event(1, "provider_thought", "hidden reasoning"))))
        rejected(terminalRun().copy(events = listOf(event(1, "analyzing", "x".repeat(501)))))
        rejected(terminalRun().copy(events = listOf(
            event(2, "analyzing", "Second"),
            event(1, "started", "First")
        )))
    }

    @Test fun `repair failure persists safe review state and retries the same run`() = runTest {
        val request = request()
        val recoveryCalls = mutableListOf<Triple<String, String, String?>>()
        val gateway = object : AgentRunGateway {
            override suspend fun start(request: AgentRunRequest) = repairFailedRun()
            override suspend fun continueWith(runId: String, evidenceJobIds: List<String>) = error("Unexpected evidence")
            override suspend fun recover(runId: String, action: String, clarification: String?): AgentRun {
                recoveryCalls += Triple(runId, action, clarification)
                return repairedRun()
            }
        }
        val coordinator = AgentRunCoordinator(gateway, AgentEvidenceFulfiller { it.id })
        val failed = coordinator.run(request) {}
        assertEquals(AgentRecoveryStatus.REPAIR_FAILED, failed.recovery?.status)
        assertTrue(failed.recovery?.issue?.contains("Nothing was applied") == true)
        assertTrue(failed.events.single().message.contains("fix the response format"))
        assertTrue(failed.events.none { it.message.contains("secret payload") })

        val persisted = mutableListOf<PersistedAgentRun>()
        val repaired = coordinator.recover(failed, request, AgentRecoveryAction.RETRY_REPAIR) { persisted += it }

        assertEquals(listOf(Triple("run-1", "retry", null)), recoveryCalls)
        assertEquals(AgentRecoveryStatus.RESOLVED, repaired.recovery?.status)
        assertEquals(listOf("Retry repair"), repaired.recovery?.conversation?.map { it.message })
        assertEquals(listOf(1, 2, 3), repaired.events.map { it.sequence })
        assertTrue(persisted.all { it.projectId == failed.projectId && it.projectRevision == failed.projectRevision })
    }

    @Test fun `review issue is durable and never calls cloud or changes proposal`() = runTest {
        val request = request()
        var cloudCalls = 0
        val gateway = object : AgentRunGateway {
            override suspend fun start(request: AgentRunRequest) = repairFailedRun()
            override suspend fun continueWith(runId: String, evidenceJobIds: List<String>) = error("Unexpected evidence")
            override suspend fun recover(runId: String, action: String, clarification: String?): AgentRun {
                cloudCalls++
                return repairedRun()
            }
        }
        val coordinator = AgentRunCoordinator(gateway, AgentEvidenceFulfiller { it.id })
        val failed = coordinator.run(request) {}
        val reviewed = coordinator.recover(failed, request, AgentRecoveryAction.REVIEW_ISSUE) {}

        assertEquals(0, cloudCalls)
        assertEquals(failed.decision, reviewed.decision)
        assertEquals(AgentRecoveryStatus.REPAIR_FAILED, reviewed.recovery?.status)
        assertEquals(true, reviewed.recovery?.reviewRequested)
        assertEquals("Review issue", reviewed.recovery?.conversation?.single()?.message)
    }

    @Test fun `recovery survives serialization and a failed retry remains actionable`() = runTest {
        val request = request()
        val gateway = object : AgentRunGateway {
            override suspend fun start(request: AgentRunRequest) = repairFailedRun()
            override suspend fun continueWith(runId: String, evidenceJobIds: List<String>) = error("Unexpected evidence")
            override suspend fun recover(runId: String, action: String, clarification: String?): AgentRun =
                throw IllegalStateException("offline")
        }
        val coordinator = AgentRunCoordinator(gateway, AgentEvidenceFulfiller { it.id })
        val failed = coordinator.run(request) {}
        val persisted = mutableListOf<PersistedAgentRun>()

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                coordinator.recover(failed, request, AgentRecoveryAction.RETRY_REPAIR) { persisted += it }
            }
        }

        val restored = Json.decodeFromString<PersistedAgentRun>(
            Json.encodeToString(PersistedAgentRun.serializer(), persisted.last())
        )
        assertEquals(AgentRecoveryStatus.REPAIR_FAILED, restored.recovery?.status)
        assertEquals("Retry repair", restored.recovery?.conversation?.single()?.message)
        assertEquals(failed.decision, restored.decision)
    }

    private fun request() = AgentRunRequest(
        projectId = "project-1", deviceSessionId = "device-1", projectRevision = 4,
        intent = "Make this publishable",
        sources = listOf(Layer2SourceInventory("source-1", "a".repeat(64), 60_000, 1920, 1080, true)),
        mediaIndex = RequestShapedMediaIndex(
            indexId = "index-1", requestScope = MediaIndexScope.BROAD_EDITORIAL,
            generatedAtEpochMs = 1, segments = emptyList(),
            selections = listOf(MediaIndexSelection(
                "selection-1", "source-1", 0, 1_000,
                listOf(SelectionReason.REPRESENTATIVE_COVERAGE),
                derivativeKinds = listOf(DerivativeKind.FRAME)
            ))
        )
    )

    private fun evidenceRun(sequence: Int, jobId: String, reused: Boolean) = AgentRun(
        1, "run-1", "project-1", 4, AgentRunStatus.WAITING_FOR_EVIDENCE,
        listOf(
            event(sequence, "evidence_requested", "I need one precise range."),
            event(sequence + 1, "analyzing", "Preparing the requested evidence.")
        ),
        buildJsonObject {
            put("type", "evidence_request")
            putJsonArray("jobs") {
                add(buildJsonObject {
                    put("id", jobId); put("sourceId", "source-1"); put("type", "audio")
                    put("purpose", "Measure speech"); put("acceptedFormat", "audio/mp4")
                    put("range", buildJsonObject { put("startMs", 1_000); put("endMs", 2_000) })
                    put("maximumBytes", 100_000); put("maximumDurationMs", 1_000)
                    put("expiresAt", "2026-07-29T16:00:00Z"); put("reused", reused)
                })
            }
        }
    )

    private fun terminalRun() = AgentRun(
        1, "run-1", "project-1", 4, AgentRunStatus.PROPOSAL_READY,
        listOf(event(5, "proposal_ready", "The proposal is ready to preview.")),
        buildJsonObject {
            put("type", "edit_program"); put("expectedRevision", 4)
            put("summary", "A grounded first cut."); putJsonArray("operations") {}
        }
    )

    private fun repairFailedRun() = AgentRun(
        1, "run-1", "project-1", 4, AgentRunStatus.WAITING_FOR_USER,
        listOf(event(1, "proposal_repair_failed", "Provider output: malformed secret payload")),
        buildJsonObject {
            put("type", "clarification")
            put("question", "Retry or clarify?")
            putJsonArray("options") { add(JsonPrimitive("retry")); add(JsonPrimitive("clarify")) }
        }
    )

    private fun repairedRun() = AgentRun(
        1, "run-1", "project-1", 4, AgentRunStatus.PROPOSAL_READY,
        listOf(
            event(2, "proposal_repairing", "Repairing."),
            event(3, "proposal_repaired", "Repaired.")
        ),
        buildJsonObject {
            put("type", "edit_program"); put("expectedRevision", 4)
            put("summary", "A repaired grounded cut."); putJsonArray("operations") {}
        }
    )

    private fun event(sequence: Int, type: String, message: String) =
        AgentActivityEvent(sequence, type, message, "2026-07-29T12:00:00Z")
}
