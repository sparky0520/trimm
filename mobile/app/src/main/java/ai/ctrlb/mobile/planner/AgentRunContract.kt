package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.EditOperation
import ai.ctrlb.mobile.domain.PendingEditorialProposal
import ai.ctrlb.mobile.domain.StagedEditorialPlan
import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TranscriptWord
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
import ai.ctrlb.mobile.layer1.RequestShapedMediaIndex

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
    val decision: JsonObject,
    val usage: AgentUsageSummary = AgentUsageSummary()
)

@Serializable
data class AgentUsageCall(
    val callId: String,
    val provider: String,
    val model: String,
    val purpose: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val thinkingTokens: Long = 0,
    val cachedTokens: Long = 0,
    val textTokens: Long = 0,
    val videoTokens: Long = 0,
    val totalTokens: Long = 0,
    val estimatedCostMicros: Long = 0,
    val occurredAt: String? = null,
    val sourceId: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val resolution: String? = null,
    val costEstimated: Boolean = true
)

@Serializable
data class AgentUsageSummary(
    val calls: List<AgentUsageCall> = emptyList(),
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val thinkingTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalTokens: Long = 0,
    val estimatedCostMicros: Long = 0
)

@Serializable data class ClarificationOption(val id: String, val label: String, val description: String)
@Serializable data class ClarificationQuestion(
    val id: String,
    val prompt: String,
    val options: List<ClarificationOption>,
    val recommendedOptionId: String = ""
)
@Serializable data class ClarificationDecision(val type: String, val questions: List<ClarificationQuestion>)

@Serializable
data class AgentRunRequest(
    val projectId: String,
    val deviceSessionId: String,
    val projectRevision: Long,
    val intent: String,
    val sources: List<Layer2SourceInventory>,
    val transcript: List<Layer2TranscriptWord> = emptyList(),
    val evidenceJobIds: List<String> = emptyList(),
    val existingOperations: List<JsonObject> = emptyList(),
    val budgets: Layer2Budgets = Layer2Budgets(),
    val mediaConsent: Layer2MediaConsent = Layer2MediaConsent(),
    val supportedContractVersions: Layer2ContractVersions = Layer2ContractVersions()
)

fun sourceTranscript(words: List<TranscriptWord>, sources: List<SourceMedia>): List<Layer2TranscriptWord> {
    var offset = 0L
    val result = mutableListOf<Layer2TranscriptWord>()
    sources.forEachIndexed { index, source ->
        val sourceEnd = offset + source.durationMs
        words.filter { it.text.isNotBlank() && it.range.startMs in offset until sourceEnd }.forEach { word ->
            result += Layer2TranscriptWord(
                "${source.fingerprint}-$index", word.text, word.range.startMs - offset, word.range.endMs.coerceAtMost(sourceEnd) - offset,
                word.confidence, word.speakerId
            )
        }
        offset = sourceEnd
    }
    return result
}

@Serializable
data class Layer2MediaConsent(
    val mode: String = "video_analysis",
    val grantedAt: String = java.time.Instant.now().toString(),
    val disclosureVersion: String = "video-analysis-v2"
)

@Serializable
data class Layer2ContractVersions(
    val agentRuns: List<Int> = listOf(2),
    val activityEvents: List<Int> = listOf(2),
    val videoToolRequests: List<Int> = listOf(2),
    val editPrograms: List<Int> = listOf(2)
)

@Serializable
data class ToolArtifactResult(val toolCallId: String, val artifactId: String)

@Serializable
data class ContinueAgentRunRequest(
    val toolArtifacts: List<ToolArtifactResult> = emptyList(),
    val message: String? = null,
    val projectRevision: Long? = null,
    val existingOperations: List<JsonObject> = emptyList()
)

@Serializable
data class RecoverAgentRunRequest(val action: String, val clarification: String? = null)

@Serializable data class AgentPlanRequest(val prompt: String)
@Serializable data class AgentTask(val id: String, val description: String, val dependsOn: List<String> = emptyList())
@Serializable data class AgentPlanResponse(val tasks: List<AgentTask>)

interface AgentRunGateway {
    suspend fun plan(request: AgentPlanRequest): AgentPlanResponse = error("Planning is not implemented by this gateway.")
    suspend fun executeStateless(request: AgentRunRequest): JsonObject = error("Stateless execution is not implemented by this gateway.")
    suspend fun start(request: AgentRunRequest): AgentRun
    suspend fun continueWith(runId: String, evidenceJobIds: List<String>): AgentRun
    suspend fun sendMessage(runId: String, message: String, projectRevision: Long, existingOperations: List<JsonObject>): AgentRun =
        error("Conversation messages are not implemented by this gateway.")
    suspend fun recover(runId: String, action: String, clarification: String? = null): AgentRun
    suspend fun cancel(runId: String): AgentRun = error("Cancellation is not implemented by this gateway.")
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
            json.decodeFromJsonElement(EditOperation.serializer(), element)
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

@Serializable
private data class EvidenceRequestDecision(val type: String, val tool: String, val jobs: List<AuthorizedEvidenceJob>)

class AgentRunCoordinator(
    private val gateway: AgentRunGateway,
    private val fulfiller: AgentEvidenceFulfiller
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val allowedActivityTypes = setOf(
        "started", "transcript_received", "analyzing", "evidence_requested", "evidence_received",
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

    suspend fun orchestrateStateless(
        request: AgentRunRequest,
        onProgress: suspend (String, Int) -> Unit,
        onPersist: suspend (PersistedAgentRun, AgentPlanResponse?) -> Unit
    ): PersistedAgentRun {
        var planResponse: AgentPlanResponse? = null
        var planAttempt = 0
        while (planAttempt < 3) {
            try {
                planResponse = gateway.plan(AgentPlanRequest(request.intent))
                break
            } catch (e: Exception) {
                planAttempt++
                if (planAttempt == 3) throw e
                kotlinx.coroutines.delay(2000L * planAttempt)
            }
        }
        var tasks = planResponse?.tasks ?: emptyList()
        if (tasks.isEmpty()) {
            tasks = listOf(AgentTask("Execute prompt", request.intent, emptyList()))
            planResponse = AgentPlanResponse(tasks)
        } else {
            val sortedTasks = mutableListOf<AgentTask>()
            val visited = mutableSetOf<String>()
            fun visit(task: AgentTask, path: Set<String> = emptySet()) {
                if (task.id in visited) return
                if (task.id in path) return // break cycles gracefully instead of crashing
                task.dependsOn.forEach { depId ->
                    tasks.find { it.id == depId }?.let { visit(it, path + task.id) }
                }
                visited.add(task.id)
                sortedTasks.add(task)
            }
            tasks.forEach { visit(it) }
            tasks = sortedTasks
        }

        var snapshot = PersistedAgentRun(
            runId = java.util.UUID.randomUUID().toString(),
            projectId = request.projectId,
            projectRevision = request.projectRevision,
            status = AgentRunStatus.PARTIAL,
            events = listOf(
                AgentActivityEvent(1, "started", "Starting an orchestrated video edit.", java.time.Instant.now().toString())
            ),
            decision = buildJsonObject { put("type", "fallback"); put("reason", "Planning") }
        )
        onPersist(snapshot, planResponse)

        var existingOperations = request.existingOperations
        tasks.forEachIndexed { index, task ->
            onProgress("agent-task: ${index + 1} of ${tasks.size}: ${task.description}", (index * 100) / tasks.size)
            var taskAttempt = 0
            while (taskAttempt < 3) {
                try {
                    val taskRequest = request.copy(
                        intent = task.description,
                        existingOperations = existingOperations
                    )
                    var outcome = gateway.executeStateless(taskRequest)
                    
                    // advance stateless
                    while (outcome["type"]?.jsonPrimitive?.content == "tool_request") {
                        val evidence = json.decodeFromJsonElement(EvidenceRequestDecision.serializer(), outcome)
                        require(evidence.tool == "inspect_video_range" && evidence.jobs.isNotEmpty())
                        val completed = evidence.jobs.map { job -> fulfiller.fulfill(job) }
                        
                        // Hit continueWith on the stateless side? No, stateless doesn't have continue.
                        // We must append evidenceJobIds to the request and hit executeStateless again!
                        val nextTaskRequest = taskRequest.copy(
                            evidenceJobIds = taskRequest.evidenceJobIds + completed
                        )
                        outcome = gateway.executeStateless(nextTaskRequest)
                    }

                    // Once we have a final outcome for this task, append operations to our existing ones
                    if (outcome["type"]?.jsonPrimitive?.content == "edit_program") {
                        val newOps = outcome["operations"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
                        existingOperations = existingOperations + newOps
                        
                        snapshot = snapshot.copy(
                            status = if (index == tasks.size - 1) AgentRunStatus.PROPOSAL_READY else AgentRunStatus.PARTIAL,
                            decision = buildJsonObject {
                                put("type", "edit_program")
                                put("operations", kotlinx.serialization.json.JsonArray(existingOperations))
                                put("expectedRevision", request.projectRevision)
                                put("summary", "Proposed edit")
                            },
                            events = snapshot.events + AgentActivityEvent(
                                snapshot.events.size + 1,
                                "partial",
                                "Completed task: ${task.description}",
                                java.time.Instant.now().toString()
                            )
                        )
                    } else if (outcome["type"]?.jsonPrimitive?.content == "clarification") {
                        snapshot = snapshot.copy(
                            status = AgentRunStatus.PROPOSAL_READY,
                            decision = outcome,
                            events = snapshot.events + AgentActivityEvent(
                                snapshot.events.size + 1,
                                "clarification",
                                "Task requested clarification: ${task.description}",
                                java.time.Instant.now().toString()
                            )
                        )
                        onPersist(snapshot, planResponse)
                        break
                    } else if (outcome["type"]?.jsonPrimitive?.content == "fallback") {
                        throw Exception(outcome["reason"]?.jsonPrimitive?.content ?: "Task failed: ${task.description}")
                    }
                    onPersist(snapshot, planResponse)
                    break // break retry loop on success
                } catch (e: Exception) {
                    taskAttempt++
                    if (taskAttempt == 3) {
                        snapshot = snapshot.copy(
                            status = AgentRunStatus.FAILED,
                            events = snapshot.events + AgentActivityEvent(
                                snapshot.events.size + 1,
                                "failed",
                                "Task failed: ${task.description}. ${e.message}",
                                java.time.Instant.now().toString()
                            )
                        )
                        onPersist(snapshot, planResponse)
                        throw e
                    }
                    kotlinx.coroutines.delay(2000L * taskAttempt)
                }
            }
        }
        
        onProgress("agent-run-complete", 100)
        return snapshot
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

    suspend fun message(
        previous: PersistedAgentRun,
        request: AgentRunRequest,
        message: String,
        existingOperations: List<JsonObject>,
        onPersist: suspend (PersistedAgentRun) -> Unit
    ): PersistedAgentRun {
        require(message.isNotBlank() && message.length <= 2_000)
        var snapshot = reduce(previous, gateway.sendMessage(
            previous.runId, message.trim(), request.projectRevision, existingOperations
        ), request)
        onPersist(snapshot)
        snapshot = advance(snapshot, request, onPersist)
        return snapshot
    }

    private suspend fun advance(
        initial: PersistedAgentRun,
        request: AgentRunRequest,
        onPersist: suspend (PersistedAgentRun) -> Unit
    ): PersistedAgentRun {
        var snapshot = initial
        while (snapshot.status == AgentRunStatus.WAITING_FOR_EVIDENCE) {
            val evidence = json.decodeFromJsonElement(EvidenceRequestDecision.serializer(), snapshot.decision)
            require(evidence.type == "tool_request" && evidence.tool == "inspect_video_range" && evidence.jobs.isNotEmpty()) { "Agent video tool request is invalid." }
            val completed = evidence.jobs.map { job -> fulfiller.fulfill(job) }
            require(completed.toSet() == evidence.jobs.map { it.id }.toSet()) { "Evidence fulfillment returned unexpected job ids." }
            snapshot = snapshot.copy(fulfilledEvidenceJobIds = (snapshot.fulfilledEvidenceJobIds + completed).distinct())
            onPersist(snapshot)
            snapshot = reduce(snapshot, gateway.continueWith(snapshot.runId, completed), request)
            onPersist(snapshot)
        }
        return snapshot
    }

    private fun reduce(previous: PersistedAgentRun?, run: AgentRun, request: AgentRunRequest): PersistedAgentRun {
        require(run.contractVersion == 2) { "Unsupported agent run contract." }
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
        require(run.decision["type"]?.jsonPrimitive?.content in setOf("tool_request", "edit_program", "clarification", "fallback")) {
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
            merged, run.decision, previous?.fulfilledEvidenceJobIds.orEmpty(), recovery, run.usage
        )
    }
}

class AgentRunClient(endpoint: String, private val accessToken: suspend () -> String) : AgentRunGateway {
    private companion object {
        // Gemini video decisions can exceed one minute even after the bounded local
        // index is ready. Keep this below WorkManager's execution window while the
        // cloud run-start endpoint remains synchronous.
        const val AGENT_RUN_READ_TIMEOUT_MS = 360_000
    }

    private val runsEndpoint = URI(endpoint).also { require(it.scheme == "https") }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun plan(request: AgentPlanRequest): AgentPlanResponse = withContext(Dispatchers.IO) {
        val endpoint = URI("${runsEndpoint.toString().trimEnd('/').substringBeforeLast('/')}/agent/plan")
        val responseText = postRaw(endpoint, json.encodeToString(AgentPlanRequest.serializer(), request))
        json.decodeFromString(AgentPlanResponse.serializer(), responseText)
    }

    override suspend fun executeStateless(request: AgentRunRequest): JsonObject = withContext(Dispatchers.IO) {
        val endpoint = URI("${runsEndpoint.toString().trimEnd('/').substringBeforeLast('/')}/agent/execute")
        val responseText = postRaw(endpoint, json.encodeToString(AgentRunRequest.serializer(), request))
        json.parseToJsonElement(responseText).jsonObject
    }

    override suspend fun start(request: AgentRunRequest): AgentRun = withContext(Dispatchers.IO) {
        decodeRun(postRaw(runsEndpoint, json.encodeToString(AgentRunRequest.serializer(), request)))
    }

    override suspend fun continueWith(runId: String, evidenceJobIds: List<String>): AgentRun = withContext(Dispatchers.IO) {
        require(runId.isNotBlank() && runId.length <= 200)
        require(evidenceJobIds.isNotEmpty())
        decodeRun(postRaw(
            URI("${runsEndpoint.toString().trimEnd('/')}/$runId/messages"),
            json.encodeToString(ContinueAgentRunRequest.serializer(), ContinueAgentRunRequest(
                toolArtifacts = evidenceJobIds.map { ToolArtifactResult(it, it) }
            ))
        ))
    }

    override suspend fun sendMessage(runId: String, message: String, projectRevision: Long, existingOperations: List<JsonObject>): AgentRun = withContext(Dispatchers.IO) {
        require(runId.isNotBlank() && runId.length <= 200)
        require(message.isNotBlank() && message.length <= 2_000)
        decodeRun(postRaw(
            URI("${runsEndpoint.toString().trimEnd('/')}/$runId/messages"),
            json.encodeToString(ContinueAgentRunRequest.serializer(), ContinueAgentRunRequest(
                message = message.trim(), projectRevision = projectRevision, existingOperations = existingOperations
            ))
        ))
    }

    override suspend fun recover(runId: String, action: String, clarification: String?): AgentRun = withContext(Dispatchers.IO) {
        require(runId.isNotBlank() && runId.length <= 200)
        require(action in setOf("retry", "clarify"))
        if (action == "clarify") require(!clarification.isNullOrBlank() && clarification.length <= 500)
        decodeRun(postRaw(
            URI("${runsEndpoint.toString().trimEnd('/')}/$runId/messages"),
            json.encodeToString(
                RecoverAgentRunRequest.serializer(),
                RecoverAgentRunRequest(action, clarification)
            )
        ))
    }

    override suspend fun cancel(runId: String): AgentRun = withContext(Dispatchers.IO) {
        require(runId.isNotBlank() && runId.length <= 200)
        val endpoint = URI("${runsEndpoint.toString().trimEnd('/')}/$runId")
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalArgumentException("Agent cancellation failed ($status)")
            connection.inputStream.bufferedReader().use { decodeRun(it.readText()) }
        } finally { connection.disconnect() }
    }

    private suspend fun postRaw(endpoint: URI, body: String): String {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val connection = endpoint.toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 30_000
                connection.readTimeout = AGENT_RUN_READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText().take(1_000) }
                        ?.takeIf(String::isNotBlank)
                        
                    if (responseCode in listOf(502, 503, 504) && attempt < 3) {
                        attempt++
                        kotlinx.coroutines.delay(1000L * attempt)
                        continue
                    }
                    
                    throw IllegalArgumentException(
                        buildString {
                            append("Agent request failed ($responseCode)")
                            if (error != null) append(": $error")
                        }
                    )
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } catch (failure: java.io.IOException) {
                if (attempt == 3) throw failure
                attempt++
                kotlinx.coroutines.delay(1000L * attempt)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun decodeRun(text: String): AgentRun {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["conversationId"] == null) return json.decodeFromString(AgentRun.serializer(), text)
        
        val publicOutcome = root.getValue("outcome").jsonObject
        val outcome = when (publicOutcome["type"]?.jsonPrimitive?.content) {
            "tool_call" -> buildJsonObject {
                put("type", "tool_request")
                put("tool", publicOutcome.getValue("name"))
                put("jobs", publicOutcome.getValue("calls"))
            }
            "proposal" -> buildJsonObject {
                publicOutcome.forEach { (key, value) -> if (key != "proposalId") put(key, value) }
                put("type", "edit_program")
            }
            else -> publicOutcome
        }

        val publicStatus = root.getValue("status").jsonPrimitive.content
        val status = when (publicStatus) {
            "waiting_for_tool" -> AgentRunStatus.WAITING_FOR_EVIDENCE
            "awaiting_approval" -> AgentRunStatus.PROPOSAL_READY
            "completed" -> if (outcome["type"]?.jsonPrimitive?.content == "edit_program") AgentRunStatus.PROPOSAL_READY else AgentRunStatus.PARTIAL
            else -> json.decodeFromJsonElement(AgentRunStatus.serializer(), root.getValue("status"))
        }
        return AgentRun(
            contractVersion = 2,
            runId = root.getValue("conversationId").jsonPrimitive.content,
            projectId = root.getValue("projectId").jsonPrimitive.content,
            projectRevision = root.getValue("projectRevision").jsonPrimitive.content.toLong(),
            status = status,
            events = json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(AgentActivityEvent.serializer()), root.getValue("events")
            ),
            decision = outcome,
            usage = root["usage"]?.let { json.decodeFromJsonElement(AgentUsageSummary.serializer(), it) } ?: AgentUsageSummary()
        )
    }
}
@Serializable data class Layer2SourceInventory(
    val id: String,
    val fingerprint: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean
)
@Serializable data class Layer2Budgets(
    val maximumBytes: Long = 8L * 1024 * 1024,
    val maximumDurationMs: Long = 15_000L,
    val retentionMs: Long = 6L * 60 * 60 * 1000
)
@Serializable data class Layer2TranscriptWord(
    val sourceId: String, val text: String, val startMs: Long, val endMs: Long,
    val confidence: Float, val speakerId: String? = null
)
@Serializable data class Layer2Inventory(
    val projectId: String,
    val deviceSessionId: String,
    val projectRevision: Long,
    val intent: String,
    val sources: List<Layer2SourceInventory>,
    val mediaIndex: RequestShapedMediaIndex,
    val evidenceJobIds: List<String> = emptyList(),
    val existingOperations: List<EditOperation> = emptyList(),
    val budgets: Layer2Budgets = Layer2Budgets()
)
@Serializable data class EvidenceRange(val startMs: Long, val endMs: Long)
@Serializable data class AuthorizedEvidenceJob(
    val id: String,
    val sourceId: String,
    val type: String,
    val purpose: String,
    val acceptedFormat: String,
    val range: EvidenceRange,
    val maximumBytes: Long,
    val maximumDurationMs: Long? = null,
    val resolution: String,
    val uncertainty: String = "",
    val expiresAt: String,
    val reused: Boolean,
    val toolCallId: String = id
)
@Serializable sealed interface Layer2Response
@Serializable @SerialName("tool_request") data class Layer2EvidenceRequest(val tool: String, val jobs: List<AuthorizedEvidenceJob>) : Layer2Response
@Serializable @SerialName("edit_program") data class Layer2EditProgram(
    val expectedRevision: Long,
    val summary: String,
    val operations: List<JsonObject>
) : Layer2Response
@Serializable @SerialName("clarification") data class Layer2Clarification(val question: String, val options: List<String>) : Layer2Response
@Serializable @SerialName("fallback") data class Layer2Fallback(val reason: String) : Layer2Response
