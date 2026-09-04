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
