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
private data class EvidenceRequestDecision(val type: String, val tool: String, val jobs: List<AuthorizedEvidenceJob>)

class ChatCompletionCoordinator(
    private val gateway: AgentRunGateway,
    private val fulfiller: AgentEvidenceFulfiller
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val allowedActivityTypes = setOf(
        "started", "transcript_received", "analyzing", "evidence_requested", "evidence_received",
        "proposal_repairing", "proposal_repaired", "proposal_repair_failed",
        "clarification", "proposal_ready", "partial", "failed", "cancelled"
    )



    suspend fun orchestrateStateless(
        request: AgentRunRequest,
        projectDurationMs: Long,
        onProgress: suspend (String, Int) -> Unit,
        onPersist: suspend (PersistedAgentRun, AgentPlanResponse?) -> Unit
    ): PersistedAgentRun {
        var snapshot = PersistedAgentRun(
            runId = java.util.UUID.randomUUID().toString(),
            projectId = request.projectId,
            projectRevision = request.projectRevision,
            status = AgentRunStatus.PARTIAL,
            events = listOf(
                AgentActivityEvent(1, "started", "Starting an orchestrated video edit.", java.time.Instant.now().toString())
            ),
            decision = buildJsonObject { put("type", "fallback"); put("reason", "Started") }
        )
        onPersist(snapshot, null)

        onProgress("agent-task: Executing user request", 50)
        
        var taskAttempt = 0
        while (taskAttempt < 3) {
            try {
                var currentRequest = request
                var outcome = gateway.chatCompletion(currentRequest)
                
                // advance stateless
                while (outcome["type"]?.jsonPrimitive?.content == "tool_request") {
                    val evidence = json.decodeFromJsonElement(EvidenceRequestDecision.serializer(), outcome)
                    require(evidence.tool == "inspect_video_range" && evidence.jobs.isNotEmpty())
                    val completed = evidence.jobs.map { job -> fulfiller.fulfill(job) }
                    
                    currentRequest = currentRequest.copy(
                        evidenceJobIds = currentRequest.evidenceJobIds + completed
                    )
                    outcome = gateway.chatCompletion(currentRequest)
                }

                if (outcome["type"]?.jsonPrimitive?.content == "edit_program") {
                    val existingRemovals = request.existingOperations.mapNotNull { opElem ->
                        val decoded = json.decodeFromJsonElement(EditInstruction.serializer(), opElem)
                        if (decoded is ai.trimm.mobile.domain.RemoveRanges) decoded.ranges else null
                    }.flatten()
                    
                    val newRemovals = outcome["operations"]?.jsonArray?.mapNotNull { element ->
                        val decoded = json.decodeFromJsonElement(EditInstruction.serializer(), element)
                        if (decoded is ai.trimm.mobile.domain.RemoveRanges) decoded.ranges else null
                    }?.flatten() ?: emptyList()

                    val mapper = ai.trimm.mobile.domain.TimelineMapper(existingRemovals + newRemovals)

                    val newOps = outcome["operations"]?.jsonArray?.map { element ->
                        var op = json.decodeFromJsonElement(EditInstruction.serializer(), element)
                        if (op is ai.trimm.mobile.domain.AddBgm && op.url.isBlank()) {
                            onProgress("Generating background music...", 75)
                            val bgmStartOutputMs = mapper.sourceToOutput(op.target?.startMs ?: 0L)
                            val bgmEndOutputMs = mapper.sourceToOutput(op.target?.endMs ?: projectDurationMs)
                            val durationMs = bgmEndOutputMs - bgmStartOutputMs
                            
                            op = op.copy(url = gateway.generateBgm(op.prompt, durationMs / 1000f, isEffect = false, chatId = request.projectId))
                        } else if (op is ai.trimm.mobile.domain.AddBgmEffect && op.url.isBlank()) {
                            onProgress("Generating music effect...", 75)
                            op = op.copy(url = gateway.generateBgm(op.prompt, 12f, isEffect = true, chatId = request.projectId))
                        } else if (op is ai.trimm.mobile.domain.AddSfx && op.url.isBlank()) {
                            onProgress("Generating sound effect...", 75)
                            op = op.copy(url = gateway.generateSfx(op.prompt))
                        }
                         json.encodeToJsonElement(EditInstruction.serializer(), op).jsonObject
                    } ?: emptyList()
                    
                    val newOpIds = newOps.mapNotNull { it["id"]?.jsonPrimitive?.content }.toSet()
                    val filteredExisting = request.existingOperations.filter { it["id"]?.jsonPrimitive?.content !in newOpIds }
                    val finalOperations = filteredExisting + newOps
                    
                    val summaryStr = outcome["summary"]?.jsonPrimitive?.content ?: "Proposed edit"
                    val msg = if (newOps.isEmpty()) "Completed task. (No edits needed)" else "Completed task."
                    
                    snapshot = snapshot.copy(
                        status = AgentRunStatus.PROPOSAL_READY,
                        decision = buildJsonObject {
                            put("type", "edit_program")
                            put("operations", kotlinx.serialization.json.JsonArray(finalOperations))
                            put("expectedRevision", request.projectRevision)
                            put("summary", summaryStr)
                        },
                        events = snapshot.events + AgentActivityEvent(
                            snapshot.events.size + 1,
                            "proposal_ready",
                            msg,
                            java.time.Instant.now().toString()
                        )
                    )
                } else if (outcome["type"]?.jsonPrimitive?.content == "clarification") {
                    snapshot = snapshot.copy(
                        status = AgentRunStatus.WAITING_FOR_USER,
                        decision = outcome,
                        events = snapshot.events + AgentActivityEvent(
                            snapshot.events.size + 1,
                            "clarification",
                            "Task requested clarification",
                            java.time.Instant.now().toString()
                        )
                    )
                } else if (outcome["type"]?.jsonPrimitive?.content == "fallback") {
                    throw Exception(outcome["reason"]?.jsonPrimitive?.content ?: "Task failed.")
                }
                
                onPersist(snapshot, null)
                break 
            } catch (e: Exception) {
                taskAttempt++
                if (taskAttempt == 3) {
                    snapshot = snapshot.copy(
                        status = AgentRunStatus.FAILED,
                        events = snapshot.events + AgentActivityEvent(
                            snapshot.events.size + 1,
                            "failed",
                            "Task failed: ${e.message}",
                            java.time.Instant.now().toString()
                        )
                    )
                    onPersist(snapshot, null)
                    throw e
                }
                kotlinx.coroutines.delay(2000L * taskAttempt)
            }
        }
        
        onProgress("agent-run-complete", 100)
        return snapshot
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
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun chatCompletion(request: AgentRunRequest): JsonObject = withContext(Dispatchers.IO) {
        val endpoint = URI("${runsEndpoint.toString().trimEnd('/')}/chat/completion")
        val responseText = postRaw(endpoint, json.encodeToString(AgentRunRequest.serializer(), request))
        json.parseToJsonElement(responseText).jsonObject
    }

    @Serializable private data class BgmRequest(val prompt: String, val durationSecs: Float, val beats: List<Float> = emptyList(), val isEffect: Boolean = false, val chatId: String)
    @Serializable private data class BgmResponse(val url: String)
    @Serializable private data class SfxRequest(val prompt: String)
    @Serializable private data class SfxResponse(val url: String)

    override suspend fun generateBgm(prompt: String, durationSecs: Float, isEffect: Boolean, chatId: String): String = withContext(Dispatchers.IO) {
        val endpoint = URI("${runsEndpoint.toString().trimEnd('/')}/audio/generate-bgm")
        val responseText = postRaw(endpoint, json.encodeToString(BgmRequest.serializer(), BgmRequest(prompt, durationSecs, isEffect = isEffect, chatId = chatId)))
        json.decodeFromString(BgmResponse.serializer(), responseText).url
    }

    override suspend fun generateSfx(prompt: String): String = withContext(Dispatchers.IO) {
        val endpoint = URI("${runsEndpoint.toString().trimEnd('/')}/audio/fetch-sfx")
        val responseText = postRaw(endpoint, json.encodeToString(SfxRequest.serializer(), SfxRequest(prompt)))
        json.decodeFromString(SfxResponse.serializer(), responseText).url
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
}
