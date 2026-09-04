package ai.trimm.mobile.data

import ai.trimm.mobile.domain.MobileProject
import ai.trimm.mobile.domain.CreatorPreferenceProfile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ChatSummary(val id: String, val title: String)

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val creatorProfile = File(context.filesDir, "creator-preferences.json")

    private val apiService by lazy { ai.trimm.mobile.network.ChatApiService.create() }

    suspend fun save(project: MobileProject) = withContext(Dispatchers.IO) {
        val projectJsonStr = json.encodeToString(MobileProject.serializer(), project)
        val token = ai.trimm.mobile.auth.AuthTokenProvider.currentToken()
        val requestBody = projectJsonStr.toRequestBody("application/json".toMediaType())
        apiService.syncProject("Bearer $token", requestBody)
        Unit
    }

    suspend fun load(id: String): MobileProject = withContext(Dispatchers.IO) {
        val token = ai.trimm.mobile.auth.AuthTokenProvider.currentToken()
        val response = apiService.getProject("Bearer $token", id)
        json.decodeFromString(MobileProject.serializer(), response.toString())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val token = ai.trimm.mobile.auth.AuthTokenProvider.currentToken()
        apiService.deleteProject("Bearer $token", id)
    }

    suspend fun listAll(): List<ChatSummary> = withContext(Dispatchers.IO) {
        val summaries = mutableListOf<ChatSummary>()
        val token = ai.trimm.mobile.auth.AuthTokenProvider.currentToken()
        val response = apiService.getProjects("Bearer $token")
        val projectsArray = response.getAsJsonArray("projects")
        projectsArray.forEach { projectJson ->
            val id = projectJson.asJsonObject.get("id").asString
            val title = projectJson.asJsonObject.get("title").asString
            summaries.add(ChatSummary(id, title))
        }
        summaries
    }

    suspend fun saveCreatorPreferences(profile: CreatorPreferenceProfile) = withContext(Dispatchers.IO) {
        val temporary = File(creatorProfile.parentFile, "${creatorProfile.name}.partial")
        temporary.writeText(json.encodeToString(CreatorPreferenceProfile.serializer(), profile))
        Files.move(temporary.toPath(), creatorProfile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    suspend fun loadCreatorPreferences(): CreatorPreferenceProfile = withContext(Dispatchers.IO) {
        if (!creatorProfile.exists()) CreatorPreferenceProfile()
        else runCatching {
            json.decodeFromString(CreatorPreferenceProfile.serializer(), creatorProfile.readText())
        }.getOrDefault(CreatorPreferenceProfile())
    }
}
