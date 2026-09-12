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
data class ChatMessage(val role: String, val content: String)

@Serializable
data class AgentRunRequest(
    val projectId: String,
    val deviceSessionId: String,
    val projectRevision: Long,
    val intent: String,
    val sources: List<AgentSourceInventory>,
    val transcript: List<AgentTranscriptWord> = emptyList(),
    val history: List<ChatMessage> = emptyList(),
    val evidenceJobIds: List<String> = emptyList(),
    val existingOperations: List<JsonObject> = emptyList(),
    val uploadInitialVideoContext: Boolean = false,
    val budgets: AgentBudgets = AgentBudgets(),
    val mediaConsent: AgentMediaConsent = AgentMediaConsent(),
    val supportedContractVersions: AgentContractVersions = AgentContractVersions()
)

fun sourceTranscript(words: List<TranscriptWord>, sources: List<SourceMedia>): List<AgentTranscriptWord> {
    var offset = 0L
    val result = mutableListOf<AgentTranscriptWord>()
    sources.forEachIndexed { index, source ->
        val sourceEnd = offset + source.durationMs
        var lastWordEndMs = offset
        val sourceId = "${source.fingerprint}-$index"
        words.filter { it.text.isNotBlank() && it.range.startMs in offset until sourceEnd }.forEach { word ->
            val startMs = word.range.startMs
            if (startMs - lastWordEndMs > 1000L) {
                val gapSecs = String.format(java.util.Locale.US, "%.1f", (startMs - lastWordEndMs) / 1000f)
                result += AgentTranscriptWord(sourceId, "[SILENCE: $gapSecs seconds]", lastWordEndMs - offset, startMs - offset, 1.0f, null)
            }
            result += AgentTranscriptWord(
                sourceId, word.text, startMs - offset, word.range.endMs.coerceAtMost(sourceEnd) - offset,
                word.confidence, word.speakerId
            )
            lastWordEndMs = word.range.endMs.coerceAtLeast(lastWordEndMs)
        }
        if (sourceEnd - lastWordEndMs > 1000L) {
            val gapSecs = String.format(java.util.Locale.US, "%.1f", (sourceEnd - lastWordEndMs) / 1000f)
            result += AgentTranscriptWord(sourceId, "[SILENCE: $gapSecs seconds]", lastWordEndMs - offset, sourceEnd - offset, 1.0f, null)
        }
        offset = sourceEnd
    }
    return result
}
