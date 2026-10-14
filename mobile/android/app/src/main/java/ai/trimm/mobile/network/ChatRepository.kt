package ai.trimm.mobile.network

import ai.trimm.mobile.BuildConfig
import ai.trimm.mobile.billing.BillingManager
import ai.trimm.mobile.domain.CompositionSchema
import ai.trimm.mobile.domain.EditInstruction
import ai.trimm.mobile.domain.MobileProject
import ai.trimm.mobile.planner.AgentRunRequest
import ai.trimm.mobile.planner.ClarificationQuestion
import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class ChatSummary(
    val id: String,
    val title: String
)

@Serializable
data class AgentExecuteResponse(
    val type: String,
    val operations: List<EditInstruction>? = null,
    val removedOperations: List<String>? = null,
    val questions: List<ClarificationQuestion>? = null,
    val summary: String? = null,
    val schema: CompositionSchema? = null
)

sealed class ChatRepositoryException(message: String) : Exception(message) {
    data object Unauthorized : ChatRepositoryException("Please sign in to continue chat.")
    data class InsufficientCredits(val balance: Int, val required: Int) : ChatRepositoryException("You need more credits to continue.")
    data class BadServerResponse(val code: Int? = null) : ChatRepositoryException(
        if (code != null && code in 502..504) "Server is temporarily unavailable (HTTP $code). Please try again in a moment."
        else "Server returned an unexpected response (HTTP ${code ?: "unknown"})."
    )
}

class ChatRepository {
    companion object {
        val shared = ChatRepository()
        private const val TAG = "ChatRepository"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Infinite read timeout for long agent streaming
            .build()
    }

    suspend fun isAuthenticated(): Boolean {
        val token = runCatching { supabase.auth.currentAccessTokenOrNull() }.getOrNull()
        return !token.isNullOrEmpty()
    }

    suspend fun executeAgentTask(
        request: AgentRunRequest,
        onStatus: ((String) -> Unit)? = null
    ): AgentExecuteResponse = withContext(Dispatchers.IO) {
        val backendUrl = BuildConfig.PLANNER_URL.removeSuffix("/v1").trimEnd('/')
        val url = "$backendUrl/v1/chat/completion"

        var token = runCatching { supabase.auth.currentAccessTokenOrNull() }.getOrNull()
        if (token.isNullOrEmpty()) {
            throw ChatRepositoryException.Unauthorized
        }

        val requestBodyJson = json.encodeToString(AgentRunRequest.serializer(), request)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        fun buildRequest(bearerToken: String): Request {
            return Request.Builder()
                .url(url)
                .post(requestBodyJson.toRequestBody(mediaType))
                .addHeader("Authorization", "Bearer $bearerToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .build()
        }

        val currentToken = token ?: throw ChatRepositoryException.Unauthorized
        var call = httpClient.newCall(buildRequest(currentToken))
        var response = call.execute()

        // Handle 401 refresh (matching iOS)
        if (response.code == 401) {
            response.close()
            try {
                supabase.auth.refreshCurrentSession()
                token = supabase.auth.currentAccessTokenOrNull()
            } catch (e: Exception) {
                throw ChatRepositoryException.Unauthorized
            }
            val refreshedToken = token ?: throw ChatRepositoryException.Unauthorized
            call = httpClient.newCall(buildRequest(refreshedToken))
            response = call.execute()
            if (response.code == 401) {
                response.close()
                throw ChatRepositoryException.Unauthorized
            }
        }

        // Handle 402 Insufficient credits
        if (response.code == 402) {
            response.close()
            CoroutineScope(Dispatchers.Main).launch {
                BillingManager.setStorePresented(true)
                BillingManager.fetchCredits()
            }
            throw ChatRepositoryException.InsufficientCredits(BillingManager.balance.value, 2)
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw ChatRepositoryException.BadServerResponse(code)
        }

        val reader = response.body?.byteStream()?.bufferedReader()
            ?: throw ChatRepositoryException.BadServerResponse(response.code)

        var currentEvent = ""
        var doneResult: AgentExecuteResponse? = null

        reader.use { r ->
            for (line in r.lineSequence()) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.removePrefix("event: ").trim()
                } else if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    when (currentEvent) {
                        "status" -> {
                            try {
                                val statusObj = json.parseToJsonElement(data).jsonObject
                                val status = statusObj["status"]?.jsonPrimitive?.content
                                if (status != null) {
                                    withContext(Dispatchers.Main) {
                                        onStatus?.invoke(status)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse status event", e)
                            }
                        }
                        "done" -> {
                            Log.d(TAG, "Raw SSE done data received: $data")
                            doneResult = json.decodeFromString<AgentExecuteResponse>(data)
                            Log.d(TAG, "Agent stream done event parsed: type=${doneResult?.type}, summary=${doneResult?.summary}, timelineNodes=${doneResult?.schema?.timeline?.size}")
                            break
                        }
                        "error" -> {
                            val errorObj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                            val errMsg = errorObj?.get("error")?.jsonPrimitive?.content ?: "Agent stream error"
                            throw Exception(errMsg)
                        }
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            BillingManager.fetchCredits()
        }

        return@withContext doneResult ?: throw Exception("Stream ended without a done event")
    }

    private fun getProjectsDir(context: Context): File {
        val dir = File(context.filesDir, "Projects")
        if (!dir.exists()) {
            val legacy = File(context.filesDir, "projects")
            if (legacy.exists() && legacy.isDirectory) {
                legacy.renameTo(dir)
            }
            if (!dir.exists()) dir.mkdirs()
        }
        return dir
    }

    suspend fun getProjects(context: Context): List<ChatSummary> = withContext(Dispatchers.IO) {
        val dir = getProjectsDir(context)
        val files = dir.listFiles { _, name -> name.endsWith(".json") && !name.endsWith(".tmp") } ?: emptyArray()
        files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            runCatching {
                val text = file.readText()
                val obj = json.parseToJsonElement(text).jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: file.nameWithoutExtension
                val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled Chat"
                ChatSummary(id = id, title = title)
            }.getOrElse {
                runCatching {
                    val project = json.decodeFromString<MobileProject>(file.readText())
                    ChatSummary(id = project.id, title = project.title)
                }.getOrNull()
            }
        }
    }

    suspend fun getProject(context: Context, id: String): MobileProject = withContext(Dispatchers.IO) {
        val file = File(getProjectsDir(context), "$id.json")
        val content = file.readText()
        json.decodeFromString<MobileProject>(content)
    }

    suspend fun syncProject(context: Context, project: MobileProject) = withContext(Dispatchers.IO) {
        val dir = getProjectsDir(context)
        val file = File(dir, "${project.id}.json")
        val content = json.encodeToString(MobileProject.serializer(), project)
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(content)
        tmp.renameTo(file)

        project.undo.lastOrNull()?.schema?.let { schema ->
            runCatching {
                val schemaFile = File(context.filesDir, "${project.id}_composition.json")
                val schemaContent = json.encodeToString(CompositionSchema.serializer(), schema)
                schemaFile.writeText(schemaContent)
            }
        }
        Log.d(TAG, "Project ${project.id} synced to ${file.absolutePath}")
    }

    suspend fun deleteProject(context: Context, id: String) = withContext(Dispatchers.IO) {
        runCatching {
            val project = getProject(context, id)
            for (source in project.sources) {
                val uri = Uri.parse(source.uri)
                if (uri.scheme == "file") {
                    File(uri.path ?: "").delete()
                }
            }
        }
        File(getProjectsDir(context), "$id.json").delete()
        File(context.filesDir, "${id}_composition.json").delete()
        File(context.filesDir, "${id}_initial_composition.json").delete()
    }

    suspend fun listenForCheckpoints(chatId: String): Flow<JsonObject> {
        val channel = supabase.realtime.channel("public:checkpoints:chat_id=eq.$chatId")
        val changes = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "checkpoints"
        }

        channel.subscribe()

        return changes
            .filter { it.record["chat_id"]?.jsonPrimitive?.content == chatId }
            .filter { it.record["status"]?.jsonPrimitive?.content in listOf("completed", "failed") }
            .mapNotNull { it.record }
    }
}
