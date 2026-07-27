package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.EditOperation
import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TranscriptWord
import ai.ctrlb.mobile.domain.WizardPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Serializable data class PlannerWord(val text: String, val startMs: Long, val endMs: Long, val confidence: Float)
@Serializable data class PlannerMedia(val durationMs: Long, val width: Int, val height: Int, val rotation: Int, val hasAudio: Boolean)
@Serializable data class PlannerRequest(
    val prompt: String,
    val transcript: List<PlannerWord>,
    val media: PlannerMedia,
    val preferences: WizardPreferences,
    val operations: List<EditOperation>,
    val projectRevision: Long
)

object PlannerPrivacyBoundary {
    private const val MAX_WORDS = 320
    fun create(prompt: String, words: List<TranscriptWord>, source: SourceMedia, preferences: WizardPreferences, operations: List<EditOperation>, revision: Long): PlannerRequest {
        require(prompt.length <= 2_000)
        return PlannerRequest(
            prompt = prompt,
            transcript = words.take(MAX_WORDS).map { PlannerWord(it.text.take(80), it.range.startMs, it.range.endMs, it.confidence) },
            media = PlannerMedia(source.durationMs, source.width, source.height, source.rotation, source.hasAudio),
            preferences = preferences,
            operations = operations,
            projectRevision = revision
        )
    }

    fun forbiddenWireKeys() = setOf("uri", "proxyUri", "fingerprint", "file", "path", "bytes", "frame", "image", "audio")
}

@Serializable sealed interface PlannerResponse
@Serializable @SerialName("message") data class PlannerMessage(val text: String) : PlannerResponse
@Serializable @SerialName("clarification") data class PlannerClarification(val question: String, val options: List<String>) : PlannerResponse
@Serializable @SerialName("proposal") data class PlannerProposal(val expectedRevision: Long, val summary: String, val operations: List<EditOperation>) : PlannerResponse

class PlannerClient(private val endpoint: String, private val accessToken: suspend () -> String) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
    init { require(URI(endpoint).scheme == "https") { "Planner endpoint must use HTTPS" } }
    suspend fun plan(request: PlannerRequest): PlannerResponse = withContext(Dispatchers.IO) {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 15_000; connection.readTimeout = 45_000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            connection.outputStream.use { it.write(json.encodeToString(PlannerRequest.serializer(), request).toByteArray()) }
            require(connection.responseCode in 200..299) { "Planner request failed (${connection.responseCode})" }
            connection.inputStream.bufferedReader().use { json.decodeFromString(PlannerResponse.serializer(), it.readText()) }
        } finally { connection.disconnect() }
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
    val maximumDurationMs: Long = 10L * 60 * 1000,
    val maximumCostMicros: Long = 50_000,
    val maximumRequests: Int = 8,
    val retentionMs: Long = 6L * 60 * 60 * 1000
)
@Serializable data class Layer2Inventory(
    val projectId: String,
    val deviceSessionId: String,
    val projectRevision: Long,
    val intent: String,
    val sources: List<Layer2SourceInventory>,
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
    val expiresAt: String,
    val reused: Boolean
)
@Serializable sealed interface Layer2Response
@Serializable @SerialName("evidence_request") data class Layer2EvidenceRequest(val jobs: List<AuthorizedEvidenceJob>) : Layer2Response
@Serializable @SerialName("edit_program") data class Layer2EditProgram(
    val expectedRevision: Long,
    val summary: String,
    val operations: List<JsonObject>
) : Layer2Response
@Serializable @SerialName("clarification") data class Layer2Clarification(val question: String, val options: List<String>) : Layer2Response
@Serializable @SerialName("fallback") data class Layer2Fallback(val reason: String) : Layer2Response

class Layer2Client(endpoint: String, private val accessToken: suspend () -> String) {
    private val orchestrationEndpoint = URI(endpoint).also {
        require(it.scheme == "https") { "Layer 2 endpoint must use HTTPS" }
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }

    suspend fun orchestrate(inventory: Layer2Inventory): Layer2Response = withContext(Dispatchers.IO) {
        val connection = orchestrationEndpoint.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            connection.outputStream.use {
                it.write(json.encodeToString(Layer2Inventory.serializer(), inventory).toByteArray())
            }
            require(connection.responseCode in 200..299) { "Layer 2 request failed (${connection.responseCode})" }
            connection.inputStream.bufferedReader().use {
                json.decodeFromString(Layer2Response.serializer(), it.readText())
            }
        } finally {
            connection.disconnect()
        }
    }
}
