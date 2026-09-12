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

@Serializable data class AgentBudgets(
    val maximumBytes: Long = 8L * 1024 * 1024,
    val maximumDurationMs: Long = 15_000L,
    val retentionMs: Long = 6L * 60 * 60 * 1000
)
