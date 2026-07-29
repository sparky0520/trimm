package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.layer1.RequestShapedMediaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

@Serializable
enum class AgentRunStatus {
    @SerialName("waiting_for_evidence") WAITING_FOR_EVIDENCE,
    @SerialName("waiting_for_user") WAITING_FOR_USER,
    @SerialName("proposal_ready") PROPOSAL_READY,
    @SerialName("partial") PARTIAL,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED
}

@Serializable
data class AgentActivityEvent(
    val sequence: Int,
    val type: String,
    val message: String,
    val createdAt: String
)

@Serializable
data class AgentRun(
    val contractVersion: Int,
    val runId: String,
    val projectId: String,
    val projectRevision: Long,
    val status: AgentRunStatus,
    val events: List<AgentActivityEvent>,
    val decision: JsonObject
)

@Serializable
data class AgentRunRequest(
    val projectId: String,
    val deviceSessionId: String,
    val projectRevision: Long,
    val intent: String,
    val sources: List<Layer2SourceInventory>,
    val mediaIndex: RequestShapedMediaIndex,
    val evidenceJobIds: List<String> = emptyList(),
    val existingOperations: List<JsonObject> = emptyList(),
    val budgets: Layer2Budgets = Layer2Budgets()
)

@Serializable
data class ContinueAgentRunRequest(val evidenceJobIds: List<String>)

@Serializable
data class RecoverAgentRunRequest(val action: String, val clarification: String? = null)

interface AgentRunGateway {
    suspend fun start(request: AgentRunRequest): AgentRun
    suspend fun continueWith(runId: String, evidenceJobIds: List<String>): AgentRun
    suspend fun recover(runId: String, action: String, clarification: String? = null): AgentRun
}

fun interface AgentEvidenceFulfiller {
    suspend fun fulfill(job: AuthorizedEvidenceJob): String
}

@Serializable
enum class AgentRecoveryStatus {
    @SerialName("repair_failed") REPAIR_FAILED,
    @SerialName("retrying") RETRYING,
    @SerialName("resolved") RESOLVED
}

@Serializable
enum class AgentRecoveryAction {
    @SerialName("retry_repair") RETRY_REPAIR,
    @SerialName("regenerate_proposal") REGENERATE_PROPOSAL,
    @SerialName("review_issue") REVIEW_ISSUE
}

@Serializable
data class AgentRecoveryTurn(
    val action: AgentRecoveryAction,
    val message: String,
    val createdAt: String
)

@Serializable
data class AgentRecoveryState(
    val status: AgentRecoveryStatus,
    val issue: String = "The response format could not be fixed safely. Nothing was applied.",
    val reviewRequested: Boolean = false,
    val conversation: List<AgentRecoveryTurn> = emptyList()
)

@Serializable
data class PersistedAgentRun(
    val contractVersion: Int = 1,
    val runId: String,
    val projectId: String,
    val projectRevision: Long,
    val status: AgentRunStatus,
    val events: List<AgentActivityEvent>,
    val decision: JsonObject,
    val fulfilledEvidenceJobIds: List<String> = emptyList(),
    val recovery: AgentRecoveryState? = null
)

@Serializable
private data class EvidenceRequestDecision(val type: String, val jobs: List<AuthorizedEvidenceJob>)

class AgentRunCoordinator(
    private val gateway: AgentRunGateway,
    private val fulfiller: AgentEvidenceFulfiller,
    private val maximumEvidencePasses: Int = 8
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val allowedActivityTypes = setOf(
        "started", "index_received", "analyzing", "evidence_requested", "evidence_received",
        "proposal_repairing", "proposal_repaired", "proposal_repair_failed",
        "clarification", "proposal_ready", "partial", "failed", "cancelled"
    )

    suspend fun run(
        request: AgentRunRequest,
        onPersist: suspend (PersistedAgentRun) -> Unit
    ): PersistedAgentRun {
        var snapshot = reduce(null, gateway.start(request), request)
        onPersist(snapshot)
        return advance(snapshot, request, onPersist)
    }

    suspend fun recover(
        previous: PersistedAgentRun,
        request: AgentRunRequest,
        action: AgentRecoveryAction,
        onPersist: suspend (PersistedAgentRun) -> Unit
    ): PersistedAgentRun {
        require(previous.recovery?.status == AgentRecoveryStatus.REPAIR_FAILED) {
            "Agent run has no recoverable proposal."
        }
        val message = when (action) {
            AgentRecoveryAction.RETRY_REPAIR -> "Retry repair"
            AgentRecoveryAction.REGENERATE_PROPOSAL -> "Regenerate proposal"
            AgentRecoveryAction.REVIEW_ISSUE -> "Review issue"
        }
        val turn = AgentRecoveryTurn(action, message, java.time.Instant.now().toString())
        val recorded = previous.copy(recovery = previous.recovery.copy(
            reviewRequested = previous.recovery.reviewRequested || action == AgentRecoveryAction.REVIEW_ISSUE,
            conversation = previous.recovery.conversation + turn
        ))
        onPersist(recorded)
        if (action == AgentRecoveryAction.REVIEW_ISSUE) return recorded

        val retrying = recorded.copy(recovery = recorded.recovery?.copy(status = AgentRecoveryStatus.RETRYING))
        onPersist(retrying)
        val run = try {
            when (action) {
                AgentRecoveryAction.RETRY_REPAIR -> gateway.recover(retrying.runId, "retry")
                AgentRecoveryAction.REGENERATE_PROPOSAL -> gateway.recover(
                    retrying.runId,
                    "clarify",
                    "Regenerate the proposal from the accepted request and current project state."
                )
                AgentRecoveryAction.REVIEW_ISSUE -> error("Handled above")
            }
        } catch (error: Throwable) {
            onPersist(recorded)
            throw error
        }
        val snapshot = reduce(retrying, run, request)
        onPersist(snapshot)
        return advance(snapshot, request, onPersist)
    }

    private suspend fun advance(
        initial: PersistedAgentRun,
        request: AgentRunRequest,
        onPersist: suspend (PersistedAgentRun) -> Unit
    ): PersistedAgentRun {
        var snapshot = initial
        var passes = 0
        while (snapshot.status == AgentRunStatus.WAITING_FOR_EVIDENCE) {
            if (++passes > maximumEvidencePasses) throw IllegalStateException("Agent requested too many evidence passes.")
            val evidence = json.decodeFromJsonElement(EvidenceRequestDecision.serializer(), snapshot.decision)
            require(evidence.type == "evidence_request" && evidence.jobs.isNotEmpty()) { "Agent evidence request is invalid." }
            val completed = evidence.jobs.map { job -> if (job.reused) job.id else fulfiller.fulfill(job) }
            require(completed.toSet() == evidence.jobs.map { it.id }.toSet()) { "Evidence fulfillment returned unexpected job ids." }
            snapshot = snapshot.copy(fulfilledEvidenceJobIds = (snapshot.fulfilledEvidenceJobIds + completed).distinct())
            onPersist(snapshot)
            snapshot = reduce(snapshot, gateway.continueWith(snapshot.runId, completed), request)
            onPersist(snapshot)
        }
        return snapshot
    }

    private fun reduce(previous: PersistedAgentRun?, run: AgentRun, request: AgentRunRequest): PersistedAgentRun {
        require(run.contractVersion == 1) { "Unsupported agent run contract." }
        require(run.runId.isNotBlank()) { "Agent run id is missing." }
        require(run.projectId == request.projectId && run.projectRevision == request.projectRevision) {
            "Agent run targets stale project state."
        }
        if (previous != null) require(run.runId == previous.runId) { "Agent continuation changed run id." }
        val prior = previous?.events.orEmpty()
        val incoming = run.events.map { event ->
            require(event.sequence > 0 && event.type in allowedActivityTypes) { "Unsafe agent activity." }
            require(event.message.isNotBlank() && event.message.length <= 500) { "Unsafe agent activity message." }
            require(runCatching { java.time.Instant.parse(event.createdAt) }.isSuccess) { "Invalid agent activity timestamp." }
            val safeMessage = when (event.type) {
                "proposal_repairing" -> "I’m fixing the response format before anything can be applied."
                "proposal_repaired" -> "The response format is fixed and the proposal passed validation."
                "proposal_repair_failed" -> "I couldn’t safely fix the response format. Nothing was applied."
                else -> event.message.replace(Regex("[\\r\\n]+"), " ").trim()
            }
            event.copy(message = safeMessage)
        }
        require(incoming.zipWithNext().all { (left, right) -> right.sequence > left.sequence }) {
            "Agent activity is out of order."
        }
        val conflicts = (prior + incoming).groupBy(AgentActivityEvent::sequence)
            .values.any { sameSequence -> sameSequence.distinct().size > 1 }
        require(!conflicts) { "Agent activity sequence was rewritten." }
        val merged = (prior + incoming).distinctBy(AgentActivityEvent::sequence).sortedBy(AgentActivityEvent::sequence)
        require(merged.zipWithNext().all { (left, right) -> right.sequence > left.sequence }) { "Agent activity is out of order." }
        require(run.decision["type"]?.jsonPrimitive?.content in setOf("evidence_request", "edit_program", "clarification", "fallback")) {
            "Unsupported agent decision."
        }
        val recovery = when {
            merged.any { it.type == "proposal_repair_failed" } &&
                incoming.none { it.type == "proposal_repaired" } ->
                (previous?.recovery ?: AgentRecoveryState(AgentRecoveryStatus.REPAIR_FAILED))
                    .copy(status = AgentRecoveryStatus.REPAIR_FAILED)
            incoming.any { it.type == "proposal_repaired" } ->
                previous?.recovery?.copy(status = AgentRecoveryStatus.RESOLVED)
                    ?: AgentRecoveryState(AgentRecoveryStatus.RESOLVED)
            else -> previous?.recovery
        }
        return PersistedAgentRun(
            run.contractVersion, run.runId, run.projectId, run.projectRevision, run.status,
            merged, run.decision, previous?.fulfilledEvidenceJobIds.orEmpty(), recovery
        )
    }
}

class AgentRunClient(endpoint: String, private val accessToken: suspend () -> String) : AgentRunGateway {
    private val runsEndpoint = URI(endpoint).also { require(it.scheme == "https") }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun start(request: AgentRunRequest): AgentRun = withContext(Dispatchers.IO) {
        post(runsEndpoint, json.encodeToString(AgentRunRequest.serializer(), request))
    }

    override suspend fun continueWith(runId: String, evidenceJobIds: List<String>): AgentRun = withContext(Dispatchers.IO) {
        require(runId.isNotBlank() && runId.length <= 200)
        require(evidenceJobIds.isNotEmpty())
        post(
            URI("${runsEndpoint.toString().trimEnd('/')}/$runId/continue"),
            json.encodeToString(ContinueAgentRunRequest.serializer(), ContinueAgentRunRequest(evidenceJobIds))
        )
    }

    override suspend fun recover(runId: String, action: String, clarification: String?): AgentRun = withContext(Dispatchers.IO) {
        require(runId.isNotBlank() && runId.length <= 200)
        require(action in setOf("retry", "clarify"))
        if (action == "clarify") require(!clarification.isNullOrBlank() && clarification.length <= 500)
        post(
            URI("${runsEndpoint.toString().trimEnd('/')}/$runId/continue"),
            json.encodeToString(
                RecoverAgentRunRequest.serializer(),
                RecoverAgentRunRequest(action, clarification)
            )
        )
    }

    private suspend fun post(endpoint: URI, body: String): AgentRun {
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            connection.outputStream.use { it.write(body.toByteArray()) }
            require(connection.responseCode in 200..299) { "Agent run failed (${connection.responseCode})" }
            connection.inputStream.bufferedReader().use {
                json.decodeFromString(AgentRun.serializer(), it.readText())
            }
        } finally {
            connection.disconnect()
        }
    }
}
