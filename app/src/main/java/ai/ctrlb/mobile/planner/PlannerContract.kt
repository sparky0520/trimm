package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.domain.EditOperation
import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TranscriptWord
import ai.ctrlb.mobile.domain.WizardPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
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
