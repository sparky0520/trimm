package ai.ctrlb.mobile.planner

import ai.ctrlb.mobile.layer1.RequestShapedMediaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class AgentRunClient(endpoint: String, private val accessToken: suspend () -> String) {
    private val runsEndpoint = URI(endpoint).also { require(it.scheme == "https") }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun start(request: AgentRunRequest): AgentRun = withContext(Dispatchers.IO) {
        post(runsEndpoint, json.encodeToString(AgentRunRequest.serializer(), request))
    }

    suspend fun continueWith(runId: String, evidenceJobIds: List<String>): AgentRun = withContext(Dispatchers.IO) {
        require(runId.matches(Regex("^[0-9a-f-]{36}$", RegexOption.IGNORE_CASE)))
        require(evidenceJobIds.isNotEmpty())
        post(
            URI("${runsEndpoint.toString().trimEnd('/')}/$runId/continue"),
            json.encodeToString(ContinueAgentRunRequest.serializer(), ContinueAgentRunRequest(evidenceJobIds))
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
