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

@Serializable data class AgentPlanResponse(
    val tasks: List<AgentTask>? = null,
    val clarification: ClarificationDecision? = null
)

interface AgentRunGateway {
    suspend fun plan(request: AgentPlanRequest): AgentPlanResponse = error("Planning is not implemented by this gateway.")
    suspend fun executeStateless(request: AgentRunRequest): JsonObject = error("Stateless execution is not implemented by this gateway.")
    suspend fun generateBgm(prompt: String, durationSecs: Float, isEffect: Boolean = false, chatId: String): String = error("BGM generation is not implemented by this gateway.")
    suspend fun generateSfx(prompt: String): String = error("SFX generation is not implemented by this gateway.")
}

fun interface AgentEvidenceFulfiller {
    suspend fun fulfill(job: AuthorizedEvidenceJob): String
}
