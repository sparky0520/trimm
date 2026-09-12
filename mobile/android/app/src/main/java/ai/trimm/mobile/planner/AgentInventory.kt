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

@Serializable data class AgentInventory(
    val projectId: String,
    val deviceSessionId: String,
    val projectRevision: Long,
    val intent: String,
    val sources: List<AgentSourceInventory>,
    val mediaIndex: RequestShapedMediaIndex,
    val evidenceJobIds: List<String> = emptyList(),
     val existingOperations: List<EditInstruction> = emptyList(),
    val budgets: AgentBudgets = AgentBudgets()
)
