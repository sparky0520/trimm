package ai.trimm.mobile.planner

import ai.trimm.mobile.domain.EditInstruction
import ai.trimm.mobile.domain.PendingEditorialProposal
import ai.trimm.mobile.domain.StagedEditorialPlan
import ai.trimm.mobile.domain.SourceMedia
import ai.trimm.mobile.domain.TranscriptWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import ai.trimm.mobile.planning.RequestShapedMediaIndex

@Serializable
data class PersistedAgentRun(
    val contractVersion: Int = 2,
    val runId: String,
    val projectId: String,
    val projectRevision: Long,
    val status: AgentRunStatus,
    val events: List<AgentActivityEvent>,
    val decision: JsonObject,
    val fulfilledEvidenceJobIds: List<String> = emptyList(),
    val recovery: AgentRecoveryState? = null,
    val usage: AgentUsageSummary = AgentUsageSummary()
)

object AgentDecisionMapper {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun pendingProposal(run: PersistedAgentRun): PendingEditorialProposal? {
        if (run.status !in setOf(AgentRunStatus.PROPOSAL_READY, AgentRunStatus.PARTIAL, AgentRunStatus.FAILED) ||
            run.decision["type"]?.jsonPrimitive?.content != "edit_program") return null
        val operations = run.decision["operations"]?.jsonArray?.map { element ->
                             json.decodeFromJsonElement(EditInstruction.serializer(), element)
        } ?: return null
        val evidenceIds = run.decision["operations"]?.jsonArray.orEmpty()
            .flatMap { operation ->
                operation.jsonObject["evidenceIds"]?.jsonArray.orEmpty()
                    .map { it.jsonPrimitive.content }
            }.distinct()
        return PendingEditorialProposal(
            expectedRevision = run.decision["expectedRevision"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: run.projectRevision,
            summary = run.decision["summary"]?.jsonPrimitive?.content ?: "Proposed edit",
            plan = StagedEditorialPlan(emptyList(), emptyList(), emptyList()),
            operations = operations,
            evidenceIds = evidenceIds
        )
    }

    fun clarification(run: PersistedAgentRun): List<ClarificationQuestion> {
        if (run.status != AgentRunStatus.WAITING_FOR_USER || run.decision["type"]?.jsonPrimitive?.content != "clarification") return emptyList()
        return runCatching { json.decodeFromJsonElement(ClarificationDecision.serializer(), run.decision).questions }
            .getOrDefault(emptyList())
    }
}
